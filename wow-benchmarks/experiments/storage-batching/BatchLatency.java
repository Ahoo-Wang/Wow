/* Licensed under the Apache License, Version 2.0. */
package batchharness;
import co.elastic.clients.elasticsearch._types.Refresh;
import com.mongodb.reactivestreams.client.MongoDatabase;
import kotlin.jvm.functions.Function1;
import me.ahoo.wow.api.modeling.AggregateId;
import me.ahoo.wow.benchmark.fixture.BenchmarkAggregates;
import me.ahoo.wow.benchmark.fixture.BenchmarkEvents;
import me.ahoo.wow.event.DomainEventStream;
import me.ahoo.wow.eventsourcing.EventStore;
import me.ahoo.wow.eventsourcing.snapshot.SimpleSnapshot;
import me.ahoo.wow.eventsourcing.snapshot.Snapshot;
import me.ahoo.wow.eventsourcing.snapshot.SnapshotStore;
import me.ahoo.wow.eventsourcing.state.StateEvent;
import me.ahoo.wow.infra.batch.BatchItemResult;
import me.ahoo.wow.infra.batch.BatchWriter;
import me.ahoo.wow.infrastructure.mongo.MongoBenchmarkFixture;
import me.ahoo.wow.infrastructure.elasticsearch.ElasticsearchBenchmarkFixture;
import me.ahoo.wow.metrics.WowMetrics;
import me.ahoo.wow.modeling.DefaultAggregateId;
import me.ahoo.wow.modeling.MaterializedNamedAggregate;
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory;
import me.ahoo.wow.mongo.AggregateSchemaInitializer;
import me.ahoo.wow.mongo.SnapshotSchemaInitializer;
import me.ahoo.wow.elasticsearch.IndexNameConverter;
import me.ahoo.wow.serialization.JsonSerializerKt;
import org.bson.Document;
import reactor.core.publisher.Mono;
import java.io.*;
import java.lang.invoke.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;

/** Frozen external harness: same bytecode uses both jars; reflection is restricted to ABI setup. */
public class BatchLatency implements AutoCloseable {
    static final Duration TIMEOUT = Duration.ofSeconds(10);
    final String path, distribution;
    final int lanes;
    final Object store;
    final MethodHandle submit;
    MongoBenchmarkFixture mongo;
    ElasticsearchBenchmarkFixture elastic;
    final List<MaterializedNamedAggregate> names = new ArrayList<>();
    final Map<AggregateId,Integer> expectedVersions = new ConcurrentHashMap<>();
    final AtomicLong confirmed = new AtomicLong();
    final AtomicLong sequence = new AtomicLong();
    final Semaphore hot = new Semaphore(1);
    final boolean snapshot;

    BatchLatency(String path, String distribution, int lanes) throws Throwable {
        this.path=path; this.distribution=distribution; this.lanes=lanes; snapshot=path.endsWith("snapshot");
        for(int i=0;i<(distribution.equals("multi")?8:1);i++) names.add(new MaterializedNamedAggregate("batch-perf", "cart"+i));
        Object options;
        WowMetrics metrics = WowMetrics.Companion.getNONE();
        if(path.equals("core")) {
            Class<?> oc=Class.forName("me.ahoo.wow.infra.batch.BatchOptions");
            BatchWriter<Object> writer = batch -> Mono.just(Collections.nCopies(batch.size(), BatchItemResult.Success.INSTANCE));
            Function1<Object,Object> key = item -> item;
            Object coordinator;
            try {
                options=oc.getConstructor(int.class,Duration.class,int.class,int.class).newInstance(128,Duration.ofMillis(1),4096,lanes);
                Class<?> cc=Class.forName("me.ahoo.wow.infra.batch.BatchCoordinator");
                coordinator=cc.getConstructor(String.class,oc,BatchWriter.class,Function1.class,WowMetrics.class).newInstance("perf",options,writer,key,metrics);
            } catch(NoSuchMethodException oldAbi) {
                options=oc.getConstructor(int.class,Duration.class,int.class).newInstance(128,Duration.ofMillis(1),4096);
                Class<?> cc=Class.forName("me.ahoo.wow.infra.batch.KeyedBatchCoordinator");
                coordinator=cc.getConstructor(String.class,oc,int.class,Function1.class,BatchWriter.class,WowMetrics.class).newInstance("perf",options,lanes,key,writer,metrics);
            }
            store=coordinator;
            submit=MethodHandles.lookup().unreflect(store.getClass().getMethod("submit",Object.class)).bindTo(store);
        } else {
            String cn=path.startsWith("mongo")?"me.ahoo.wow.mongo.Mongo":"me.ahoo.wow.elasticsearch.eventsourcing.Elasticsearch";
            cn+=snapshot?"SnapshotStore":"EventStore";
            Class<?> sc=Class.forName(cn);
            Constructor<?> constructor=Arrays.stream(sc.getConstructors()).filter(c->!c.isSynthetic()).findFirst().orElseThrow();
            Class<?> oc=constructor.getParameterTypes()[1];
            if(oc.getSimpleName().equals("BatchOptions")) options=oc.getConstructor(int.class,Duration.class,int.class,int.class).newInstance(128,Duration.ofMillis(1),4096,lanes);
            else options=oc.getConstructor(boolean.class,int.class,Duration.class,int.class,int.class).newInstance(true,128,Duration.ofMillis(1),4096,lanes);
            if(path.startsWith("mongo")) {
                mongo=new MongoBenchmarkFixture();
                for(var n:names) {
                    if(snapshot) new SnapshotSchemaInitializer(mongo.getDatabase()).initSchema(n);
                    else new me.ahoo.wow.mongo.EventStreamSchemaInitializer(mongo.getDatabase(),true).initSchema(n);
                }
                store=constructor.newInstance(mongo.getDatabase(),options,metrics);
            } else {
                elastic=new ElasticsearchBenchmarkFixture();
                clearElastic();
                store=snapshot?constructor.newInstance(elastic.getClient(),options,Refresh.False,metrics):constructor.newInstance(elastic.getClient(),options,Refresh.False,10000,metrics);
            }
            submit=null;
        }
    }
    void clearElastic() {
        for(var n:names) elastic.getClient().indices().delete(b->b.index(index(n)).ignoreUnavailable(true)).block(TIMEOUT);
    }
    String index(MaterializedNamedAggregate n) {return snapshot?IndexNameConverter.INSTANCE.toSnapshotIndexName(n):IndexNameConverter.INSTANCE.toEventStreamIndexName(n);}
    String collection(MaterializedNamedAggregate n) {return snapshot?AggregateSchemaInitializer.INSTANCE.toSnapshotCollectionName(n):AggregateSchemaInitializer.INSTANCE.toEventStreamCollectionName(n);}
    Object item(long id) {
        long key=(distribution.equals("hot") && id%10!=0)?-1:id;
        if(distribution.equals("duplicate")) key=id/128;
        if(path.equals("core")) return key;
        AggregateId aid=new DefaultAggregateId(names.get((int)(id%names.size())),Long.toHexString(key | (1L<<60)),"");
        int version=(distribution.equals("hot")&&key==-1)?(int)(id-id/10):1;
        if(distribution.equals("duplicate")) version=(int)(id%128)+1;
        DomainEventStream event=BenchmarkEvents.INSTANCE.singleEventStream(aid,version-1);
        if(!snapshot) return event;
        var aggregate=ConstructorStateAggregateFactory.INSTANCE.create(BenchmarkAggregates.INSTANCE.getCartMetadata().getState(),aid);
        return new SimpleSnapshot<>(StateEvent.Companion.toStateEvent(event,aggregate),1);
    }
    @SuppressWarnings("unchecked") Mono<Void> write(Object item) throws Throwable {
        if(submit!=null) return (Mono<Void>)submit.invokeExact(item);
        if(snapshot) return ((SnapshotStore)store).save((Snapshot<?>)item);
        return ((EventStore)store).append((DomainEventStream)item);
    }
    void success(Object item) {
        confirmed.incrementAndGet();
        if(snapshot && (distribution.equals("duplicate") || distribution.equals("hot"))) {Snapshot<?> s=(Snapshot<?>)item; expectedVersions.merge(s.getAggregateId(),s.getVersion(),Math::max);}
    }
    void reset() {
        confirmed.set(0); sequence.set(0); expectedVersions.clear();
        if(mongo!=null) for(var n:names) Mono.from(mongo.getDatabase().getCollection(collection(n)).deleteMany(new Document())).block(TIMEOUT);
        if(elastic!=null) clearElastic();
    }
    void verify() {
        if(mongo!=null) {
            long count=0;
            for(var n:names) count+=Mono.from(mongo.getDatabase().getCollection(collection(n)).countDocuments()).block(TIMEOUT);
            long expected=snapshot && (distribution.equals("duplicate") || distribution.equals("hot"))?expectedVersions.size():confirmed.get();
            if(count!=expected) throw new AssertionError("mongo count: "+count+" expected "+expected);
            System.out.println("VERIFIED documents="+count);
        }
        if(elastic!=null) {
            long count=0;
            for(var n:names) {
                elastic.getClient().indices().refresh(b->b.index(index(n))).block(TIMEOUT);
                count+=elastic.getClient().count(b->b.index(index(n))).block(TIMEOUT).count();
            }
            long expected=snapshot && (distribution.equals("duplicate") || distribution.equals("hot"))?expectedVersions.size():confirmed.get();
            if(count!=expected) throw new AssertionError("elastic count: "+count+" expected "+expected);
            System.out.println("VERIFIED documents="+count);
        }
        if(snapshot && expectedVersions.isEmpty()) {
            long invalid=0;
            for(var n:names) {
                if(mongo!=null) invalid+=Mono.from(mongo.getDatabase().getCollection(collection(n)).countDocuments(new Document("version",new Document("$ne",1)))).block(TIMEOUT);
                else invalid+=elastic.getClient().count(b->b.index(index(n)).query(q->q.bool(v->v.mustNot(t->t.term(x->x.field("version").value(1)))))).block(TIMEOUT).count();
            }
            if(invalid!=0) throw new AssertionError("snapshot versions != 1: "+invalid);
            System.out.println("VERIFIED all versions=1");
        }
        if(snapshot) {
            for(var e:expectedVersions.entrySet()) {
                int actual;
                if(mongo!=null) {
                    Document doc=Mono.from(mongo.getDatabase().getCollection(AggregateSchemaInitializer.INSTANCE.toSnapshotCollectionName(e.getKey())).find(new Document("_id",e.getKey().getId())).first()).block(TIMEOUT);
                    actual=((Number)doc.get("version")).intValue();
                } else {
                    Map doc=elastic.getClient().get(b->b.index(IndexNameConverter.INSTANCE.toSnapshotIndexName(e.getKey())).id(e.getKey().getId()),Map.class).block(TIMEOUT).source();
                    actual=((Number)doc.get("version")).intValue();
                }
                if(actual!=e.getValue()) throw new AssertionError("snapshot version "+actual+" expected "+e.getValue());
            }
            System.out.println("VERIFIED versions="+expectedVersions.size());
        }
    }
    static class Sample {long planned,submitted,terminal; String result="not_sent";}
    void run(double rate,int seconds,Path file) throws Throwable {
        int count=(int)Math.ceil(rate*seconds);
        Sample[] samples=new Sample[count];
        CountDownLatch done=new CountDownLatch(count);
        long start=System.nanoTime();
        for(int i=0;i<count;i++) {
            Sample s=new Sample(); samples[i]=s; s.planned=start+(long)(i*1e9/rate);
            long delay;
            while((delay=s.planned-System.nanoTime())>0) LockSupport.parkNanos(delay);
            long id=sequence.getAndIncrement();
            boolean serial=distribution.equals("hot") && !snapshot && !path.equals("core") && id%10!=0;
            if(serial && !hot.tryAcquire(10,TimeUnit.SECONDS)) {s.terminal=System.nanoTime(); done.countDown(); continue;}
            s.submitted=System.nanoTime();
            try {
                Object item=item(id);
                write(item).timeout(TIMEOUT).subscribe(ignored->{},error->{
                    s.terminal=System.nanoTime(); s.result=error instanceof TimeoutException?"timeout":error.getClass().getSimpleName().contains("Overflow")?"rejected":error.getClass().getName();
                    if(serial)hot.release(); done.countDown();
                },()->{s.terminal=System.nanoTime(); s.result="success"; success(item); if(serial)hot.release(); done.countDown();});
            } catch(Throwable error) {s.terminal=System.nanoTime();s.result=error.getClass().getName();if(serial)hot.release();done.countDown();}
        }
        if(!done.await(20,TimeUnit.SECONDS)) throw new AssertionError("notifications not drained");
        if(file!=null) {
            try(var out=new BufferedWriter(new OutputStreamWriter(new java.util.zip.GZIPOutputStream(Files.newOutputStream(file)),java.nio.charset.StandardCharsets.UTF_8))) {
                out.write("planned_ns,submitted_ns,terminal_ns,result\n");
                for(var s:samples) out.write((s.planned-start)+","+(s.submitted==0?0:s.submitted-start)+","+(s.terminal-start)+","+s.result+"\n");
            }
            Map<String,Long> counts=new TreeMap<>();for(var s:samples) counts.merge(s.result,1L,Long::sum);
            System.out.println("RESULT "+counts+" elapsed_ns="+(System.nanoTime()-start));
        }
    }
    public void close() throws Exception {
        try {store.getClass().getMethod("close").invoke(store);} finally {
            if(mongo!=null)mongo.close();
            if(elastic!=null){try{clearElastic();}finally{elastic.close();}}
        }
    }
    public static void main(String[] args) throws Throwable {
        if(args.length!=6)throw new IllegalArgumentException("path distribution lanes rate seconds output.csv");
        try(var fixture=new BatchLatency(args[0],args[1],Integer.parseInt(args[2]))) {
            Object payload=fixture.item(1111);
            System.out.println("PAYLOAD bytes="+JsonSerializerKt.toJsonString(payload).getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            fixture.run(Double.parseDouble(args[3]),5,null);
            fixture.verify();
            fixture.reset();
            fixture.run(Double.parseDouble(args[3]),Integer.parseInt(args[4]),Path.of(args[5]));
            fixture.verify();
        }
    }
}
