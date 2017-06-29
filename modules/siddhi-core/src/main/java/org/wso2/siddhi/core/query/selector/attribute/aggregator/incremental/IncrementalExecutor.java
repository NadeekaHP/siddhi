/*
 * Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.siddhi.core.query.selector.attribute.aggregator.incremental;

import org.wso2.siddhi.core.config.ExecutionPlanContext;
import org.wso2.siddhi.core.event.ComplexEvent;
import org.wso2.siddhi.core.event.ComplexEventChunk;
import org.wso2.siddhi.core.event.stream.MetaStreamEvent;
import org.wso2.siddhi.core.event.stream.StreamEvent;
import org.wso2.siddhi.core.event.stream.StreamEventPool;
import org.wso2.siddhi.core.event.stream.converter.ConversionStreamEventChunk;
import org.wso2.siddhi.core.event.stream.converter.StreamEventConverter;
import org.wso2.siddhi.core.executor.ExpressionExecutor;
import org.wso2.siddhi.core.executor.VariableExpressionExecutor;
import org.wso2.siddhi.core.query.selector.GroupByKeyGenerator;
import org.wso2.siddhi.core.table.InMemoryTable;
import org.wso2.siddhi.core.table.Table;
import org.wso2.siddhi.core.util.Scheduler;
import org.wso2.siddhi.core.util.parser.AggregationParser;
import org.wso2.siddhi.query.api.aggregation.TimePeriod;
import org.wso2.siddhi.query.api.expression.Variable;

import java.util.*;
import java.util.stream.Collectors;

public class IncrementalExecutor implements Executor {
    private TimePeriod.Duration duration; // TODO: 6/16/17 remove unnecessary fields
    private IncrementalExecutor child;
    private MetaStreamEvent metaEvent;
    private Map<String, Table> tableMap;
    private ExecutionPlanContext executionPlanContext;
    private String aggregatorName;
    private List<CompositeAggregator> compositeAggregators;
    private List<AggregationParser.ExpressionExecutorDetails> basicExecutorDetails;
    private List<Variable> groupByVariables;
    private ExpressionExecutor timeStampExecutor;
    private List<ExpressionExecutor> genericExpressionExecutors;
    private GroupByKeyGenerator groupByKeyGenerator;
    private int bufferCount;
    private StreamEventPool streamEventPool;

    private long nextEmitTime = -1;
    private boolean isRoot = false;
    private boolean isExternalTimeStampBased = false;
    private boolean isGroupBy;
    private Executor next;
    private static final ThreadLocal<String> keyThreadLocal = new ThreadLocal<>();
    private final StreamEvent resetEvent;
    private long startTimeOfAggregates;
    private Map<Long, Map<String, BaseIncrementalAggregatorStore>> bufferedBaseAggregatorMap;
    private Map<Long, List<AggregationParser.ExpressionExecutorDetails>> basicExecutorsOfBufferedEvents;
    private Map<String, BaseIncrementalAggregatorStore> runningBaseAggregatorCollection;
    private ComplexEventChunk<StreamEvent> timerStreamEventChunk;
    private Scheduler scheduler;

    public IncrementalExecutor(TimePeriod.Duration duration, IncrementalExecutor child, MetaStreamEvent metaEvent,
            Map<String, Table> tableMap, ExecutionPlanContext executionPlanContext, String aggregatorName,
            List<CompositeAggregator> compositeAggregators,
            List<AggregationParser.ExpressionExecutorDetails> basicExecutorDetails, List<Variable> groupByVariables,
            VariableExpressionExecutor timeStampExecutor, GroupByKeyGenerator groupByKeyGenerator,
            List<ExpressionExecutor> genericExpressionExecutors, int bufferCount, StreamEventPool streamEventPool) {
        this.duration = duration;
        this.child = child;
        this.metaEvent = metaEvent;
        this.tableMap = tableMap;
        this.executionPlanContext = executionPlanContext;
        this.aggregatorName = aggregatorName;
        this.compositeAggregators = compositeAggregators;
        this.basicExecutorDetails = basicExecutorDetails;
        this.groupByVariables = groupByVariables;
        this.timeStampExecutor = timeStampExecutor;
        if (groupByKeyGenerator != null) {
            this.groupByKeyGenerator = groupByKeyGenerator;
            isGroupBy = true;
        } else {
            isGroupBy = false;
        }
        this.genericExpressionExecutors = genericExpressionExecutors;
        this.bufferCount = bufferCount;
        this.streamEventPool = streamEventPool;

        this.resetEvent = streamEventPool.borrowEvent();
        resetEvent.setType(ComplexEvent.Type.RESET);
        setNextExecutor(child); // TODO: 6/13/17 this must be set by entry valve also
        runningBaseAggregatorCollection = new HashMap<>();
        bufferedBaseAggregatorMap = new TreeMap<>();
        basicExecutorsOfBufferedEvents = new TreeMap<>();
        timerStreamEventChunk = new ConversionStreamEventChunk((StreamEventConverter) null, streamEventPool);
    }

    public void setRoot() {
        this.isRoot = true;
    }

    public void setIsExternalTimeStampBased() {
        this.isExternalTimeStampBased = true;
    }

    public void setScheduler(Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    public void resetAggregatorStore() {
        this.runningBaseAggregatorCollection = new HashMap<>();
    }

    @Override
    public void execute(ComplexEventChunk streamEventChunk) {
        while (streamEventChunk.hasNext()) {
            StreamEvent event = (StreamEvent) streamEventChunk.next();

            System.out.println(this.duration + "..." + event.getType());

            // Create new chunk to hold one stream event only
            ComplexEventChunk<StreamEvent> newEventChunk = new ComplexEventChunk<>(event, event,
                    streamEventChunk.isBatch());
            long timeStamp;
            if (event.getType() == ComplexEvent.Type.CURRENT) {
                timeStamp = (long) timeStampExecutor.execute(event);
                if (nextEmitTime == -1) { // The first event is always a CURRENT event
                    nextEmitTime = IncrementalTimeConverterUtil.getNextEmitTime(timeStamp, this.duration);
                    startTimeOfAggregates = IncrementalTimeConverterUtil.getStartTimeOfAggregates(timeStamp,
                            this.duration);
                }
            } else {
                // TIMER event has arrived. A timer event never arrives for external timeStamp based execution
                timeStamp = event.getTimestamp();
                if (isRoot) {
                    // Scheduling is done by root incremental executor only
                    scheduler.notifyAt(IncrementalTimeConverterUtil.getNextEmitTime(timeStamp, this.duration));
                }
            }
            if (timeStamp >= nextEmitTime) {
                long copyOfEmitTime = nextEmitTime;
                nextEmitTime = IncrementalTimeConverterUtil.getNextEmitTime(timeStamp, this.duration);
                startTimeOfAggregates = IncrementalTimeConverterUtil.getStartTimeOfAggregates(timeStamp, this.duration);
                dispatchEvents(copyOfEmitTime, timeStamp);
                if (event.getType() == ComplexEvent.Type.TIMER && getNextExecutor() != null) {
                    // Send TIMER event to next executor.
                    // TODO: 6/29/17 This must be corrected. Timer events must be sent only after sending atleast 1
                    // event to next
                    StreamEvent timerEvent = streamEventPool.borrowEvent();
                    timerEvent.setType(ComplexEvent.Type.TIMER);
                    timerEvent.setTimestamp(IncrementalTimeConverterUtil.getEmitTimeOfLastEventToRemove(timeStamp,
                            this.duration, this.bufferCount));
                    timerStreamEventChunk.add(timerEvent);
                    getNextExecutor().execute(timerStreamEventChunk);
                    timerStreamEventChunk.clear();
                }
            }

            if (event.getType() == ComplexEvent.Type.CURRENT) {
                processAggregates(newEventChunk, timeStamp);

                for (Map.Entry<String, BaseIncrementalAggregatorStore> x : runningBaseAggregatorCollection.entrySet()) {
                    String a = "___";
                    for (Object z : x.getValue().getBaseIncrementalValues()) {
                        a = a.concat(z.toString() + "___");
                    }
                    System.out.println(this.duration + "...." + isRoot + "..." + x.getValue().getKey() + "...." + a);

                }

            }
        }
    }

    @Override
    public Executor getNextExecutor() {
        return next;
    }

    @Override
    public void setNextExecutor(Executor nextExecutor) {
        next = nextExecutor;
    }

    @Override
    public void setToLast(Executor executor) { // TODO: 6/13/17 correct?
        if (next == null) {
            this.next = executor;
        } else {
            this.next.setToLast(executor);
        }
    }

    @Override
    public Executor cloneExecutor(String key) {
        return null;
    }

    private void processAggregates(ComplexEventChunk complexEventChunk, long timeStamp) {
        synchronized (this) {
            while (complexEventChunk.hasNext()) {
                ComplexEvent event = complexEventChunk.next();
                String groupedByKey = "KEY::"; // This dummy key is used when group by is not given
                if (isGroupBy) {
                    groupedByKey = groupByKeyGenerator.constructEventKey(event);
                    keyThreadLocal.set(groupedByKey);
                }

                if (isRoot) {
                    // get time at which the incoming event should have expired
                    // get relevant base map corresponding to that expiry time
                    // update aggregates of that map
                    long actualEmitTimeForEvent = IncrementalTimeConverterUtil.getNextEmitTime(timeStamp,
                            this.duration);
                    if (actualEmitTimeForEvent == nextEmitTime) {
                        updateRunningBaseAggregatorCollection(groupedByKey, event);
                    } else {
                        Map<String, BaseIncrementalAggregatorStore> bufferedBaseAggregatorCollection = bufferedBaseAggregatorMap
                                .get(actualEmitTimeForEvent);
                        if (bufferedBaseAggregatorCollection != null) {
                            updateBufferedBaseAggregatorCollection(groupedByKey, event,
                                    bufferedBaseAggregatorCollection, timeStamp,
                                    basicExecutorsOfBufferedEvents.get(actualEmitTimeForEvent));

                        } else {
                            // Null means, the incoming event is older than buffered data
                            // TODO: 6/26/17 in this case process event with current event or oldest buffered event?
                            updateRunningBaseAggregatorCollection(groupedByKey, event);
                        }
                    }
                } else {
                    updateRunningBaseAggregatorCollection(groupedByKey, event);
                }
                keyThreadLocal.remove();
            }
        }
    }

    public static String getThreadLocalGroupByKey() {
        return keyThreadLocal.get();
    }

    private void updateRunningBaseAggregatorCollection(String groupedByKey, ComplexEvent event) {
        BaseIncrementalAggregatorStore runningBaseAggregator = runningBaseAggregatorCollection.get(groupedByKey);
        if (runningBaseAggregator == null) {
            runningBaseAggregator = new BaseIncrementalAggregatorStore(startTimeOfAggregates, groupedByKey,
                    genericExpressionExecutors.size(), basicExecutorDetails.size());
            runningBaseAggregatorCollection.put(groupedByKey, runningBaseAggregator);
        }
        for (int i = 0; i < genericExpressionExecutors.size(); i++) {
            runningBaseAggregator.setGenericValues(genericExpressionExecutors.get(i).execute(event), i);
        }
        for (int i = 0; i < basicExecutorDetails.size(); i++) {
            runningBaseAggregator.setBaseIncrementalValue(basicExecutorDetails.get(i).getExecutor().execute(event), i);
        }
    }

    private void updateBufferedBaseAggregatorCollection(String groupedByKey, ComplexEvent event,
            Map<String, BaseIncrementalAggregatorStore> bufferedBaseAggregatorCollection, long timeStamp,
            List<AggregationParser.ExpressionExecutorDetails> basicExecutorsOfBufferedEvents) {
        BaseIncrementalAggregatorStore bufferedBaseAggregator = bufferedBaseAggregatorCollection.get(groupedByKey);
        if (bufferedBaseAggregator == null) {
            bufferedBaseAggregator = new BaseIncrementalAggregatorStore(timeStamp, groupedByKey,
                    basicExecutorsOfBufferedEvents.size(), genericExpressionExecutors.size());
            bufferedBaseAggregatorCollection.put(groupedByKey, bufferedBaseAggregator);
        }
        for (int i = 0; i < genericExpressionExecutors.size(); i++) {
            bufferedBaseAggregator.setGenericValues(genericExpressionExecutors.get(i).execute(event), i);
        }
        for (int i = 0; i < basicExecutorsOfBufferedEvents.size(); i++) {
            bufferedBaseAggregator
                    .setBaseIncrementalValue(basicExecutorsOfBufferedEvents.get(i).getExecutor().execute(event), i);
        }
    }

    private void dispatchEvents(long copyOfEmitTime, long currentTimeStamp) {

        if (isRoot) {
            // Clone base executors and add to basicExecutorsOfBufferedEvents
            List<AggregationParser.ExpressionExecutorDetails> bufferedBasicExecutorDetails = basicExecutorDetails
                    .stream().map(AggregationParser.ExpressionExecutorDetails::clone).collect(Collectors.toList());
            basicExecutorsOfBufferedEvents.put(copyOfEmitTime, bufferedBasicExecutorDetails);
            // Add current base aggregator collection to buffer
            bufferedBaseAggregatorMap.put(copyOfEmitTime, runningBaseAggregatorCollection);
            // Reset running base aggregator collection
            resetAggregatorStore();
            if (isExternalTimeStampBased) {
                // When external timestamp is used, there could be instances where events which
                // were supposed to have expired earlier, still remain in the buffer, due to
                // events not arriving at the end of each duration period (e.g. For sec window
                // events may not arrive for several seconds. Therefore, there could be several
                // events in the buffer, which should have expired earlier. All such events must
                // be dispatched.

                // Remove oldest base executors from basicExecutorsOfBufferedEvents.
                // This would remove all values corresponding to key equal to or less than
                // "EmitTimeOfLastEventToRemove"
                ((TreeMap<Long, List<AggregationParser.ExpressionExecutorDetails>>) basicExecutorsOfBufferedEvents)
                        .headMap(IncrementalTimeConverterUtil.getEmitTimeOfLastEventToRemove(currentTimeStamp,
                                this.duration, this.bufferCount), true)
                        .clear();

                // Remove oldest base aggregator collections from bufferedBaseAggregatorMap
                // TODO: 6/26/17 verify mapOfBaseAggregatesToDispatch is ascending
                NavigableMap<Long, Map<String, BaseIncrementalAggregatorStore>> mapOfBaseAggregatesToDispatch = ((TreeMap<Long, Map<String, BaseIncrementalAggregatorStore>>) bufferedBaseAggregatorMap)
                        .headMap(IncrementalTimeConverterUtil.getEmitTimeOfLastEventToRemove(currentTimeStamp,
                                this.duration, this.bufferCount), true);

                // Send oldest base aggregator collection to next executor
                if (mapOfBaseAggregatesToDispatch != null) {
                    // Null check is done, since if the buffer is not filled yet,
                    // there's no requirement to send oldest event
                    for (Map.Entry<Long, Map<String, BaseIncrementalAggregatorStore>> baseAggregatesToDispatch : mapOfBaseAggregatesToDispatch
                            .entrySet()) {
                        sendToNextExecutor(baseAggregatesToDispatch.getValue());
                    }
                    mapOfBaseAggregatesToDispatch.clear();
                }

            } else {
                // Remove oldest base executors from basicExecutorsOfBufferedEvents
                basicExecutorsOfBufferedEvents.remove(IncrementalTimeConverterUtil
                        .getEmitTimeOfLastEventToRemove(currentTimeStamp, this.duration, this.bufferCount));

                // Remove oldest base aggregator collection from bufferedBaseAggregatorMap
                Map<String, BaseIncrementalAggregatorStore> baseAggregatesToDispatch = bufferedBaseAggregatorMap
                        .remove(IncrementalTimeConverterUtil.getEmitTimeOfLastEventToRemove(currentTimeStamp,
                                this.duration, this.bufferCount));

                // Send oldest base aggregator collection to next executor
                if (baseAggregatesToDispatch != null) {
                    // Null check is done, since if the buffer is not filled yet, there's no requirement to send oldest
                    // event
                    sendToNextExecutor(baseAggregatesToDispatch);
                }
            }
            // Send RESET event to groupByExecutor
            // TODO: 6/2/17 call reset method (can't reset since GroupByAggregationAttributeExecutor is called)
            for (AggregationParser.ExpressionExecutorDetails basicExecutor : this.basicExecutorDetails) {
                basicExecutor.getExecutor().execute(resetEvent);
            }
        } else {
            sendToNextExecutor(runningBaseAggregatorCollection);
            // Reset running base aggregator collection
            resetAggregatorStore();
            // Send RESET event to groupByExecutor
            for (AggregationParser.ExpressionExecutorDetails basicExecutor : this.basicExecutorDetails) {
                basicExecutor.getExecutor().execute(resetEvent);
            }
        }
    }

    private void sendToNextExecutor(Map<String, BaseIncrementalAggregatorStore> baseAggregatesToDispatch) {
        InMemoryTable inMemoryTable = ((InMemoryTable) tableMap.get(aggregatorName + "_" + this.duration.toString()));
        ComplexEventChunk<StreamEvent> newComplexEventChunk;
        for (Map.Entry<String, BaseIncrementalAggregatorStore> baseAggregateToDispatch : baseAggregatesToDispatch
                .entrySet()) {
            StreamEvent streamEvent = streamEventPool.borrowEvent();
            streamEvent.getOnAfterWindowData()[0] = baseAggregateToDispatch.getValue().getTimeStamp();
            int i = 1;
            if (isGroupBy) {
                String[] groupByValues = baseAggregateToDispatch.getValue().getKey().split("::");
                for (String groupByValue : groupByValues) {
                    streamEvent.getOnAfterWindowData()[i] = groupByValue;
                    i++;
                }
            }
            for (Object genericValues : baseAggregateToDispatch.getValue().getGenericValues()) {
                streamEvent.getOnAfterWindowData()[i] = genericValues;
                i++;
            }
            for (Object baseIncrementalValue : baseAggregateToDispatch.getValue().getBaseIncrementalValues()) {
                streamEvent.getOnAfterWindowData()[i] = baseIncrementalValue;
                i++;
            }
            newComplexEventChunk = new ComplexEventChunk<>(streamEvent, streamEvent, false);
            inMemoryTable.add(baseAggregateToDispatch.getValue().getTimeStamp(), streamEvent.getOnAfterWindowData());
            System.out.println(inMemoryTable.getElementId() + "........" + inMemoryTable.currentState());
            // TODO: 6/13/17 table may not always be there?
            if (getNextExecutor() != null) {
                getNextExecutor().execute(newComplexEventChunk);
            }
        }
    }

    private class BaseIncrementalAggregatorStore {
        private long timeStamp; // This is the starting timeStamp of aggregates
        private String key;
        private Object[] genericValues;
        private Object[] baseIncrementalValues;

        private BaseIncrementalAggregatorStore(long timeStamp, String key, int numberOfGenericValues,
                int numberOfBaseValues) {
            this.timeStamp = timeStamp;
            this.key = key;
            genericValues = new Object[numberOfGenericValues];
            baseIncrementalValues = new Object[numberOfBaseValues];
        }

        public long getTimeStamp() {
            return this.timeStamp;
        }

        public String getKey() {
            return this.key;
        }

        public void setGenericValues(Object genericValue, int position) {
            genericValues[position] = genericValue;
        }

        public void setBaseIncrementalValue(Object baseValue, int position) {
            baseIncrementalValues[position] = baseValue;
        }

        public Object[] getGenericValues() {
            return this.genericValues;
        }

        public Object[] getBaseIncrementalValues() {
            return this.baseIncrementalValues;
        }
    }
}
