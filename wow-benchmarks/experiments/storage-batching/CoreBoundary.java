/* Licensed under the Apache License, Version 2.0. */
package batchharness;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.ThreadParams;
import reactor.core.publisher.*;
import me.ahoo.wow.infra.batch.*;
import me.ahoo.wow.metrics.WowMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import kotlin.jvm.functions.Function1;
import java.lang.invoke.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Same class bytes on both artifacts; reflection only bridges constructor ABI. */
@State(Scope.Benchmark) @BenchmarkMode(Mode.Throughput) @OutputTimeUnit(TimeUnit.SECONDS)
public class CoreBoundary {
    @Param({"none","enabled"}) public String metrics;
    Object coordinator; MethodHandle submit; SimpleMeterRegistry registry;
    @Setup(Level.Trial) public void setup() throws Throwable {
        registry=metrics.equals("enabled")?new SimpleMeterRegistry():null;
        WowMetrics m=registry==null?WowMetrics.Companion.getNONE():new WowMetrics(registry);
        Class<?> oc=Class.forName("me.ahoo.wow.infra.batch.BatchOptions");
        BatchWriter<Object> writer=batch->Mono.just(Collections.nCopies(batch.size(),BatchItemResult.Success.INSTANCE));
        Function1<Object,Object> key=item->item;
        try {
            Object options=oc.getConstructor(int.class,Duration.class,int.class,int.class).newInstance(128,Duration.ofMillis(1),4096,4);
            coordinator=Class.forName("me.ahoo.wow.infra.batch.BatchCoordinator").getConstructor(String.class,oc,BatchWriter.class,Function1.class,WowMetrics.class).newInstance("perf",options,writer,key,m);
        } catch(NoSuchMethodException e) {
            Object options=oc.getConstructor(int.class,Duration.class,int.class).newInstance(128,Duration.ofMillis(1),4096);
            coordinator=Class.forName("me.ahoo.wow.infra.batch.KeyedBatchCoordinator").getConstructor(String.class,oc,int.class,Function1.class,BatchWriter.class,WowMetrics.class).newInstance("perf",options,4,key,writer,m);
        }
        submit=MethodHandles.lookup().unreflect(coordinator.getClass().getMethod("submit",Object.class)).bindTo(coordinator);
    }
    Mono<Integer> write(Object item) {
        try {return ((Mono<Void>)submit.invokeExact(item)).thenReturn(1);}
        catch(Throwable e){return Mono.error(e);}
    }
    @State(Scope.Thread) public static class Producer {
        Object[] items=new Object[128]; long sequence;
        @Setup(Level.Trial) public void prepare(ThreadParams p) {
            sequence=(long)p.getThreadIndex()<<48;
            for(int i=0;i<items.length;i++)items[i]=Long.valueOf(sequence+i);
        }
    }
    long complete(Flux<Integer> results) {
        long count=results.count().block(Duration.ofSeconds(10));
        if(count!=128)throw new AssertionError("terminal count "+count);
        return count;
    }
    // Includes boxing/input generation, Flux construction, subscription, scheduling and blocking.
    @Benchmark @OperationsPerInvocation(128) public long burst(Producer p) {
        return complete(Flux.range(0,128).flatMap(i->write(Long.valueOf(p.sequence++)),128,1));
    }
    // Prebuilt immutable keys; still includes publisher composition and terminal aggregation.
    @Benchmark @OperationsPerInvocation(128) public long prepared(Producer p) {
        return complete(Flux.fromArray(p.items).flatMap(this::write,128,1));
    }
    @TearDown(Level.Trial) public void close() throws Exception {
        coordinator.getClass().getMethod("close").invoke(coordinator);
        if(registry!=null){if(registry.getMeters().isEmpty())throw new AssertionError("metrics absent");registry.close();}
    }
}
