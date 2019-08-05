package com.cloudera.streaming.examples.flink.utils.testing;

import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.java.typeutils.ResultTypeQueryable;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.source.SourceFunction;
import org.apache.flink.streaming.api.watermark.Watermark;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class ManualFlinkSource<T> implements SourceFunction<T>, ResultTypeQueryable<T>, ManualSource<T> {

    private static final long serialVersionUID = 1L;

    private static int numSources = 0;

    private static final List<BlockingQueue<Tuple2<?, Long>>> queues = Collections.synchronizedList(new ArrayList<>());
    private static final CountDownLatch atLeastOneStarted = new CountDownLatch(1);

    private transient DataStream<T> stream;
    private transient StreamExecutionEnvironment env;
    private transient BlockingQueue<Tuple2<?, Long>> currentQueue;

    private final TypeInformation<T> type;
    private final int index;
    private volatile boolean isRunning = false;

    public ManualFlinkSource(StreamExecutionEnvironment env, TypeInformation<T> type) {
        this.type = type;
        this.env = env;
        index = numSources++;
        currentQueue = new LinkedBlockingQueue<>();
        queues.add(currentQueue);
    }

    @Override
    public void sendRecord(T event) {
        sendInternal(Tuple2.of(event, null));
    }

    @Override
    public void sendRecord(T event, long ts) {
        sendInternal(Tuple2.of(event, ts));
    }

    @Override
    public void sendWatermark(long timeStamp) {
        sendInternal(Tuple2.of(null, timeStamp));
    }

    @Override
    public void markFinished() {
        sendWatermark(Long.MAX_VALUE);
        sendInternal(Tuple2.of(null, null));
    }

    private void sendInternal(Tuple2<?, Long> t) {
        try {
            atLeastOneStarted.await(1, TimeUnit.MINUTES);
        } catch (InterruptedException ignore) {}
        currentQueue.offer(t);
    }

    @Override
    public void cancel() {
        isRunning = false;
    }

    @Override
    public void run(SourceContext<T> ctx) throws Exception {
        atLeastOneStarted.countDown();
        BlockingQueue<Tuple2<?, Long>> queue = queues.get(index);
        isRunning = true;
        while (isRunning) {
            Tuple2<?, Long> t = queue.take();
            if (t.f0 != null) {
                if (t.f1 != null) {
                    ctx.collectWithTimestamp((T) t.f0, t.f1);
                } else {
                    ctx.collect((T) t.f0);
                }
            } else if (t.f1 != null) {
                ctx.emitWatermark(new Watermark(t.f1));
            } else {
                isRunning = false;
                break;
            }
        }
    }

    @Override
    public TypeInformation<T> getProducedType() {
        return type;
    }

    @Override
    public DataStream<T> getDataStream() {
        if (stream == null) {
            stream = env.addSource(this);
        }
        return stream;
    }

}