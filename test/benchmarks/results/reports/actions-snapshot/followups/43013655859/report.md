# V10 end-to-end benchmark (D224) on GitHub Actions

Head: `e1fa5a04f139a747a0ab1a55b7482c53af0af3e1` e1fa5a04f139a747a0ab1a55b7482c53af0af3e1; V10 baseline: `v10.0.0-beta.1` 22f8ac19cb50b73d90613d7d401257600b3a914f; harness and load generator from head.

Tier: custom; coverage: e1-mongo-uniform@100k.

Each suite ran on one runner of its own (a sessions suite of several sweeps, each sweep in fresh sessions on one of its own), so only its ratios compare across suites; absolute numbers are that runner's. Ratios are b/a per pair (head/base): median (min–max, n pairs). Lower is better except throughput.

## Anomalies

Checks: a partition paused (D243), a tail read the store after a gap or an overflow (D249), more than 1% errors, a measured second below half the rate, p99 above 10× p50 (when p99 ≥ 50 ms), a head allocating more than 5.0% more bytes per operation than its baseline. Errors, dips and tails are not checked where a run went past what the node keeps up with by design (probes, cliff steps that did not keep up).

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e1-mongo-uniform@100k | r2-head | steady | **unstable** | second half against first, beyond the A/A noise: p50 +44.2% (±19.9%) |
| e1-mongo-uniform@100k | r2-base | steady | **unstable** | second half against first, beyond the A/A noise: p50 +31.6% (±19.9%) |
| e1-mongo-uniform@100k | r3-head | steady | **unstable** | second half against first, beyond the A/A noise: p50 +22.2% (±19.9%) |
| e1-mongo-uniform@100k | r5-head | steady | **unstable** | second half against first, beyond the A/A noise: p50 +37.8% (±19.9%) |
| e1-mongo-uniform@100k | e1-head | steady | **unstable** | second half against first, beyond the A/A noise: p50 +23.8% (±19.9%) |
| e1-mongo-uniform@100k | e2-head | steady | **unstable** | second half against first, beyond the A/A noise: p50 +89.8% (±19.9%) |
| e1-mongo-uniform@100k | e2-base | steady | **unstable** | second half against first, beyond the A/A noise: p50 -26.1% (±19.9%) |
| e1-mongo-uniform@100k | e4-head | steady | **unstable** | second half against first, beyond the A/A noise: p50 -24.8% (±19.9%) |

## Significance

Beyond noise: 2 of 3 ratios; 1 of 1 suites have an A/A noise threshold (test/benchmarks/noise.json). A ratio counts when its 95% bootstrap interval (over the measured seconds) clears the suite's threshold; without one it is `uncalibrated`.

| Suite | b/a | Metric | Rounds | Ratio [95% CI] | Noise | Verdict |
| --- | --- | --- | --- | --- | --- | --- |
| e1-mongo-uniform@100k | head/base | throughput | calibrated | 0.998 [0.998–0.998] | ±2.0% | within noise |
| e1-mongo-uniform@100k | head/base | p50 | equal | 1.310 [1.225–1.815] | ±19.9% | **higher** |
| e1-mongo-uniform@100k | head/base | p99 | equal | 1.446 [1.208–1.602] | ±12.1% | **higher** |

## Runners

| Suite | CPU | Cores | Memory | JDK | CPU layout |
| --- | --- | --- | --- | --- | --- |
| e1-mongo-uniform@100k | AMD EPYC 7763 64-Core Processor | 4 | 16 GB | Eclipse Adoptium 25.0.5+8-LTS | generator 3 · node 0 · store 1 · Kafka 2 |

## Saturation points (closed loop)

| Suite | Arm | Saturation | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- | --- |
| e1-mongo-uniform@100k | base | 1492.9/s | 512 | 1194.3/s | 80% |
| e1-mongo-uniform@100k | head | 1489.9/s | 512 | 1191.9/s | 80% |

## Paired ratios

`calibrated`: each arm at its own calibrated rate, so throughput and CPU per operation compare but the latencies are at **unequal loads**. `equal rate`: both arms at the lower of the two calibrated rates; read the latency ratios from these rows.

| Suite | b/a | Rounds | Saturation | Throughput | p50 | p95 | p99 | p999 | Worst-second p99 | CPU ms/op | Alloc/op |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| e1-mongo-uniform@100k | head/base | calibrated (unequal load) | 0.998 | 0.998 (0.998–0.998, n=5) | 1.071 (0.878–1.493, n=5) | 1.172 (0.980–1.454, n=5) | 1.236 (1.051–1.618, n=5) | - | 1.209 (0.930–1.641, n=5) | 1.015 (1.000–1.024, n=5) | 1.016 (1.008–1.028, n=5) |
| e1-mongo-uniform@100k | head/base | equal rate 1191.9/s | 0.998 | 1.000 (1.000–1.000, n=4) | 1.231 (1.032–2.590, n=4) | 1.349 (1.127–2.273, n=4) | 1.435 (1.215–1.977, n=4) | - | 1.516 (1.050–1.600, n=4) | 1.012 (1.002–1.033, n=4) | 1.006 (0.975–1.023, n=4) |

---

### e1-mongo-uniform@100k

E1 write on MongoDB, uniform keys: the head against the V10 baseline (regression guard), throughput and CPU at each calibrated rate, latency at one rate.

E1, uniform keys over 100000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 4 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 7763 64-Core Processor, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (66–74 s), then waited until the nodes were quiet again (3–4 s).

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 1492.9/s | 512 | 1194.3/s | 80% |
| head | 1489.9/s | 512 | 1191.9/s | 80% |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 1194.3/s | 1194.3/s | 23886 | 0 | 91.6 ms | 286.7 ms | 387.1 ms | n/a | 532.5 ms | 0.77 cores | kafka 49% · mongo 99% | 0.59 of 1 | mongo 0.99 | 1.88 | 97.2 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 5 s | - |
| probe1-head | head | 1191.9/s | 1191.9/s | 23838 | 0 | 159.7 ms | 438.3 ms | 540.7 ms | n/a | 618.5 ms | 0.73 cores | kafka 52% · mongo 97% | 0.65 of 1 | mongo 0.97 | 1.86 | 97.8 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 9 s | - |

Profile of base (profile-base, not a timed round):

`hot-methods`:

```
                                                               Java Methods that Execute the Most
Method                                                                                                                                          Samples Percent
----------------------------------------------------------------------------------------------------------------------------------------------- ------- -------
java.util.HashMap.getNode(Object)                                                                                                                   302   2.46%
org.apache.kafka.clients.producer.KafkaProducer.send(ProducerRecord, Callback)                                                                      224   1.83%
java.util.HashMap.putVal(int, Object, Object, boolean, boolean)                                                                                     210   1.71%
kotlin.coroutines.jvm.internal.ContinuationImpl.<init>(Continuation)                                                                                180   1.47%
com.mongodb.internal.connection.ByteBufferBsonOutput.writeOnArrayAscii(String, byte[], int, boolean)                                                148   1.21%
kotlin.coroutines.CombinedContext.get(CoroutineContext$Key)                                                                                         144   1.18%
java.util.concurrent.ConcurrentHashMap.get(Object)                                                                                                  141   1.15%
kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith(Object)                                                                              127   1.04%
java.util.Objects.equals(Object, Object)                                                                                                            122   1.00%
reactor.core.publisher.InnerOperator.currentContext()                                                                                               107   0.87%
java.util.regex.Pattern$BmpCharProperty.match(Matcher, int, CharSequence)                                                                           105   0.86%
kotlin.jvm.internal.Intrinsics.throwParameterIsNullNPE(String)                                                                                       98   0.80%
java.util.regex.Pattern$BmpCharPropertyGreedy.match(Matcher, int, CharSequence)                                                                      97   0.79%
```

`allocation-by-class`:

```
                                                                      Allocation by Class
Object Type                                                                                                                                 Allocation Pressure
------------------------------------------------------------------------------------------------------------------------------------------- -------------------
byte[]                                                                                                                                                    7.84%
java.lang.Object[]                                                                                                                                        6.97%
java.util.HashMap$Node[]                                                                                                                                  4.29%
java.util.LinkedHashMap$Entry                                                                                                                             4.07%
java.util.LinkedHashMap                                                                                                                                   3.38%
jdk.internal.vm.StackChunk                                                                                                                                2.71%
java.lang.String                                                                                                                                          2.32%
org.bson.internal.ChildCodecRegistry                                                                                                                      2.19%
java.util.ArrayList                                                                                                                                       2.07%
org.bson.internal.CodecCache$CodecCacheKey                                                                                                                1.62%
org.bson.codecs.Codec[]                                                                                                                                   1.60%
org.bson.BsonBinaryWriter$Context                                                                                                                         1.55%
org.bson.AbstractBsonWriter$State[]                                                                                                                       1.20%
```

`contention-by-site`:

```
                                                                       Contention by Site
StackTrace                                                                                                                                Count    Avg.    Max.
----------------------------------------------------------------------------------------------------------------------------------------- ----- ------- -------
tools.jackson.databind.ser.SerializerCache.addAndResolveNonTypedSerializer(Class, JavaType, ValueSerializer, SerializationContext)            1  195 ms  195 ms
me.ahoo.wow.runtime.snapshot.SnapshotWrites.save(SnapshotRecord, Continuation)                                                                1  147 ms  147 ms
sun.nio.ch.EPollSelectorImpl.clearInterrupt()                                                                                                36 16.4 ms 96.2 ms
jdk.internal.loader.BuiltinClassLoader.loadClassOrNull(String, boolean)                                                                       1 49.4 ms 49.4 ms
tools.jackson.databind.ser.SerializerCache.addAndResolveNonTypedSerializer(Class, JavaType, ValueSerializer, SerializationContext)            1 40.8 ms 40.8 ms
tools.jackson.databind.ser.SerializerCache.addAndResolveNonTypedSerializer(Class, JavaType, ValueSerializer, SerializationContext)            1 39.8 ms 39.8 ms
org.apache.kafka.clients.NetworkClient.parseResponse(ByteBuffer, RequestHeader)                                                               1 31.7 ms 31.7 ms
jdk.internal.loader.BuiltinClassLoader.loadClassOrNull(String, boolean)                                                                       1 31.1 ms 31.1 ms
jdk.internal.loader.BuiltinClassLoader.loadClassOrNull(String, boolean)                                                                       1 29.4 ms 29.4 ms
org.apache.kafka.common.requests.ApiVersionsRequest$Builder.build(short)                                                                      1 21.3 ms 21.3 ms
me.ahoo.wow.runtime.wait.Waiting.carry(CommandResult)                                                                                        15 13.1 ms 18.0 ms
me.ahoo.wow.runtime.subscription.SubscriptionWorker.runLive(SubscriptionWorker$Item$Live, Continuation)                                       2 14.0 ms 15.7 ms
jdk.internal.loader.BuiltinClassLoader.loadClassOrNull(String, boolean)                                                                       1 14.8 ms 14.8 ms
```

`gc-pauses`:

```
GC Pauses
---------
Total Pause Time: 5.82 s
Number of Pauses: 79
Minimum Pause Time: 0.0165 ms
Median Pause Time: 82.1 ms
Average Pause Time: 73.7 ms
P90 Pause Time: 117 ms
P95 Pause Time: 125 ms
P99 Pause Time: 208 ms
P99.9% Pause Time: 208 ms
Maximum Pause Time: 208 ms
```

`safepoints`:

```
                                                                            Safepoints
Start Time                  Duration                                     State Syncronization                  JNI Critical Threads                  Total Threads
--------------------------- --------------------------- ------------------------------------- ------------------------------------- ------------------------------
14:04:48                    Indefinite                                                    N/A                                     0                             11
14:04:48                    0.164 ms                                                      N/A                                     0                             11
14:04:49                    14.2 ms                                                       N/A                                     0                             12
14:04:50                    15.3 ms                                                       N/A                                     0                             12
14:04:50                    3.73 ms                                                       N/A                                     0                             12
14:04:50                    0.0968 ms                                                     N/A                                     0                             12
14:04:53                    24.3 ms                                                       N/A                                     0                             13
14:04:53                    19.9 ms                                                       N/A                                     0                             13
14:04:53                    31.6 ms                                                       N/A                                     0                             13
14:04:53                    12.2 ms                                                       N/A                                     0                             13
14:04:53                    0.101 ms                                                      N/A                                     0                             13
14:04:55                    44.1 ms                                                       N/A                                     0                             24
14:04:55                    0.115 ms                                                      N/A                                     0                             24
```

`latencies-by-type`:

```
                                                                       Latencies by Type
Event Type                                                                                                              Count Average    P 99 Longest     Total
--------------------------------------------------------------------------------------------------------------------- ------- ------- ------- ------- ---------
Java Thread Park                                                                                                      212,815 15.2 ms  110 ms 1 m 0 s  54 m 4 s
Socket Read                                                                                                            73,149 33.6 ms  140 ms  15.5 s 40 m 56 s
Java Thread Sleep                                                                                                          34  10.0 s  10.0 s  10.0 s  5 m 40 s
Java Monitor Wait                                                                                                          13  24.0 s 1 m 0 s 1 m 0 s  5 m 12 s
Socket Write                                                                                                            4,733 2.88 ms 13.4 ms 91.7 ms    13.6 s
Java Monitor Blocked                                                                                                       81 20.1 ms  195 ms  195 ms    1.63 s
File Read                                                                                                                  17 4.04 ms 13.1 ms 13.1 ms   68.7 ms
File Write                                                                                                                  6 3.53 ms 8.32 ms 8.32 ms   21.2 ms
```

`socket-reads-by-host`:

```
                                                                      Socket Reads by Host
Host                                                                                                                                           Reads Total Read
--------------------------------------------------------------------------------------------------------------------------------------------- ------ ----------
localhost                                                                                                                                     73,131     1.1 MB
10.1.0.157                                                                                                                                        18     1.8 kB
```

Profile of head (profile-head, not a timed round):

`hot-methods`:

```
                                                               Java Methods that Execute the Most
Method                                                                                                                                          Samples Percent
----------------------------------------------------------------------------------------------------------------------------------------------- ------- -------
java.util.HashMap.getNode(Object)                                                                                                                   358   2.91%
java.util.HashMap.putVal(int, Object, Object, boolean, boolean)                                                                                     250   2.03%
org.apache.kafka.clients.producer.KafkaProducer.send(ProducerRecord, Callback)                                                                      190   1.54%
java.util.concurrent.ConcurrentHashMap.get(Object)                                                                                                  184   1.49%
kotlin.coroutines.jvm.internal.ContinuationImpl.<init>(Continuation)                                                                                183   1.49%
kotlin.coroutines.CombinedContext.get(CoroutineContext$Key)                                                                                         126   1.02%
com.mongodb.internal.connection.ByteBufferBsonOutput.writeOnArrayAscii(String, byte[], int, boolean)                                                125   1.02%
java.util.regex.Pattern$BmpCharProperty.match(Matcher, int, CharSequence)                                                                           125   1.02%
kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith(Object)                                                                              116   0.94%
reactor.core.publisher.InnerOperator.currentContext()                                                                                               109   0.89%
java.lang.invoke.Invokers$Holder.invokeExact_MT(Object, Object, Object)                                                                              92   0.75%
kotlin.jvm.internal.Intrinsics.throwParameterIsNullNPE(String)                                                                                       90   0.73%
kotlinx.coroutines.JobSupport.removeNode$kotlinx_coroutines_core(JobNode)                                                                            89   0.72%
```

`allocation-by-class`:

```
                                                                      Allocation by Class
Object Type                                                                                                                                 Allocation Pressure
------------------------------------------------------------------------------------------------------------------------------------------- -------------------
byte[]                                                                                                                                                    7.45%
java.lang.Object[]                                                                                                                                        6.85%
java.util.HashMap$Node[]                                                                                                                                  4.27%
java.util.LinkedHashMap$Entry                                                                                                                             3.95%
java.util.LinkedHashMap                                                                                                                                   3.50%
jdk.internal.vm.StackChunk                                                                                                                                2.50%
java.lang.String                                                                                                                                          2.33%
org.bson.internal.ChildCodecRegistry                                                                                                                      2.23%
java.util.ArrayList                                                                                                                                       2.17%
org.bson.codecs.Codec[]                                                                                                                                   1.58%
org.bson.internal.CodecCache$CodecCacheKey                                                                                                                1.55%
org.bson.BsonBinaryWriter$Context                                                                                                                         1.49%
kotlin.coroutines.CombinedContext                                                                                                                         1.39%
```

`contention-by-site`:

```
                                                                       Contention by Site
StackTrace                                                                                                                                Count    Avg.    Max.
----------------------------------------------------------------------------------------------------------------------------------------- ----- ------- -------
tools.jackson.databind.ser.SerializerCache.addAndResolveNonTypedSerializer(Class, JavaType, ValueSerializer, SerializationContext)            1  156 ms  156 ms
sun.nio.ch.EPollSelectorImpl.clearInterrupt()                                                                                                32 16.0 ms  105 ms
tools.jackson.databind.ser.SerializerCache.addAndResolveNonTypedSerializer(Class, JavaType, ValueSerializer, SerializationContext)            1 62.0 ms 62.0 ms
tools.jackson.databind.ser.SerializerCache.addAndResolveNonTypedSerializer(Class, JavaType, ValueSerializer, SerializationContext)            1 43.9 ms 43.9 ms
org.apache.kafka.common.requests.ApiVersionsRequest$Builder.build(short)                                                                      1 33.5 ms 33.5 ms
org.apache.kafka.clients.NetworkClient.parseResponse(ByteBuffer, RequestHeader)                                                               1 31.9 ms 31.9 ms
me.ahoo.wow.runtime.subscription.PartitionPause.isPaused()                                                                                    2 15.6 ms 20.4 ms
org.apache.kafka.clients.producer.internals.RecordAccumulator.drainBatchesForOneNode(MetadataSnapshot, Node, int, long)                       1 19.0 ms 19.0 ms
jdk.internal.loader.BuiltinClassLoader.loadClassOrNull(String, boolean)                                                                       1 18.4 ms 18.4 ms
me.ahoo.wow.runtime.wait.Waiting.carry(CommandResult)                                                                                         8 12.7 ms 17.4 ms
me.ahoo.wow.runtime.subscription.SubscriptionWorker.drain(AggregateRef, SubscriptionWorker$Lane, Continuation)                                4 13.5 ms 16.0 ms
jdk.internal.loader.BuiltinClassLoader.loadClassOrNull(String, boolean)                                                                       1 14.7 ms 14.7 ms
jdk.internal.loader.BuiltinClassLoader.loadClassOrNull(String, boolean)                                                                       1 13.8 ms 13.8 ms
```

`gc-pauses`:

```
GC Pauses
---------
Total Pause Time: 6.29 s
Number of Pauses: 80
Minimum Pause Time: 0.0114 ms
Median Pause Time: 80.9 ms
Average Pause Time: 78.6 ms
P90 Pause Time: 127 ms
P95 Pause Time: 141 ms
P99 Pause Time: 174 ms
P99.9% Pause Time: 174 ms
Maximum Pause Time: 174 ms
```

`safepoints`:

```
                                                                            Safepoints
Start Time                  Duration                                     State Syncronization                  JNI Critical Threads                  Total Threads
--------------------------- --------------------------- ------------------------------------- ------------------------------------- ------------------------------
14:11:03                    Indefinite                                                    N/A                                     0                             11
14:11:03                    0.195 ms                                                      N/A                                     0                             11
14:11:04                    14.4 ms                                                       N/A                                     0                             12
14:11:05                    13.1 ms                                                       N/A                                     0                             12
14:11:05                    3.45 ms                                                       N/A                                     0                             12
14:11:05                    0.0944 ms                                                     N/A                                     0                             12
14:11:07                    46.6 ms                                                       N/A                                     0                             13
14:11:08                    13.4 ms                                                       N/A                                     0                             13
14:11:08                    14.1 ms                                                       N/A                                     0                             13
14:11:08                    2.13 ms                                                       N/A                                     0                             13
14:11:08                    7.07 ms                                                       N/A                                     0                             13
14:11:08                    0.570 ms                                                      N/A                                     0                             13
14:11:10                    45.5 ms                                                       N/A                                     0                             22
```

`latencies-by-type`:

```
                                                                       Latencies by Type
Event Type                                                                                                             Count Average    P 99  Longest     Total
-------------------------------------------------------------------------------------------------------------------- ------- ------- ------- -------- ---------
Java Thread Park                                                                                                     204,912 17.1 ms  111 ms 5 m 30 s 58 m 30 s
Socket Read                                                                                                           70,846 34.3 ms  159 ms   28.0 s 40 m 33 s
Java Thread Sleep                                                                                                         33  10.0 s  10.0 s   10.0 s  5 m 30 s
Java Monitor Wait                                                                                                         11  28.4 s 1 m 0 s  1 m 0 s  5 m 12 s
Socket Write                                                                                                           5,060 2.89 ms 12.6 ms   130 ms    14.6 s
Java Monitor Blocked                                                                                                      61 19.1 ms  156 ms   156 ms    1.16 s
File Read                                                                                                                 24 4.84 ms 44.0 ms  44.0 ms    116 ms
File Write                                                                                                                14 2.31 ms 4.51 ms  4.51 ms   32.3 ms
```

`socket-reads-by-host`:

```
                                                                      Socket Reads by Host
Host                                                                                                                                           Reads Total Read
--------------------------------------------------------------------------------------------------------------------------------------------- ------ ----------
localhost                                                                                                                                     70,825     1.1 MB
10.1.0.157                                                                                                                                        21     2.0 kB
```

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 1194.3/s | 1194.3/s | 47772 | 0 | 86.0 ms | 237.6 ms | 331.8 ms | n/a | 520.2 ms | 0.73 cores | kafka 50% · mongo 99% | 0.42 of 1 | mongo 0.99 | 1.86 | 98.0 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 7 s | - |
| r1-head | head | 1191.9/s | 1191.9/s | 47676 | 0 | 92.2 ms | 278.5 ms | 387.1 ms | n/a | 532.5 ms | 0.75 cores | kafka 50% · mongo 99% | 0.41 of 1 | mongo 0.99 | 1.88 | 99.6 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 3 s | - |
| r2-head | head | 1191.9/s | 1191.9/s | 47676 | 0 | 79.4 ms | 250.9 ms | 335.9 ms | n/a | 436.2 ms | 0.73 cores | kafka 52% · mongo 98% | 0.41 of 1 | mongo 0.98 | 1.86 | 101.0 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 6 s | - |
| r2-base | base | 1194.3/s | 1194.3/s | 47772 | 0 | 76.3 ms | 239.6 ms | 319.5 ms | n/a | 469.0 ms | 0.69 cores | kafka 49% · mongo 100% | 0.40 of 1 | mongo 1.00 | 1.82 | 99.6 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 6 s | - |
| r3-base | base | 1194.3/s | 1194.3/s | 47772 | 0 | 58.6 ms | 187.4 ms | 284.7 ms | n/a | 401.4 ms | 0.69 cores | kafka 50% · mongo 100% | 0.41 of 1 | mongo 1.00 | 1.83 | 99.9 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 9 s | - |
| r3-head | head | 1191.9/s | 1191.9/s | 47676 | 0 | 87.6 ms | 272.4 ms | 368.6 ms | n/a | 489.5 ms | 0.74 cores | kafka 49% · mongo 100% | 0.42 of 1 | mongo 1.00 | 1.87 | 100.6 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 10 s | - |
| r4-head | head | 1191.9/s | 1191.9/s | 47676 | 0 | 88.1 ms | 309.2 ms | 503.8 ms | n/a | 692.2 ms | 0.73 cores | kafka 49% · mongo 100% | 0.39 of 1 | mongo 1.00 | 1.86 | 100.8 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 12 s | - |
| r4-base | base | 1194.3/s | 1194.3/s | 47772 | 0 | 75.3 ms | 228.4 ms | 311.3 ms | n/a | 421.9 ms | 0.71 cores | kafka 49% · mongo 99% | 0.41 of 1 | mongo 0.99 | 1.83 | 98.1 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 5 s | - |
| r5-base | base | 1194.3/s | 1194.3/s | 47772 | 0 | 84.0 ms | 257.0 ms | 356.4 ms | n/a | 528.4 ms | 0.70 cores | kafka 51% · mongo 99% | 0.41 of 1 | mongo 0.99 | 1.84 | 98.7 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 5 s | - |
| r5-head | head | 1191.9/s | 1191.9/s | 47676 | 0 | 73.7 ms | 251.9 ms | 440.3 ms | n/a | 639.0 ms | 0.71 cores | kafka 49% · mongo 98% | 0.42 of 1 | mongo 0.98 | 1.84 | 101.2 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 4 s | - |
| e1-base | base | 1191.9/s | 1191.9/s | 47676 | 0 | 71.2 ms | 232.4 ms | 356.4 ms | n/a | 585.7 ms | 0.73 cores | kafka 50% · mongo 99% | 0.39 of 1 | mongo 0.99 | 1.86 | 98.7 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 4 s | - |
| e1-head | head | 1191.9/s | 1191.9/s | 47676 | 0 | 184.3 ms | 528.4 ms | 704.5 ms | n/a | 897.0 ms | 0.78 cores | kafka 52% · mongo 99% | 0.43 of 1 | mongo 0.99 | 1.92 | 96.3 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 16 s | - |
| e2-head | head | 1191.9/s | 1191.9/s | 47676 | 0 | 115.7 ms | 403.5 ms | 598.0 ms | n/a | 753.7 ms | 0.73 cores | kafka 53% · mongo 99% | 0.40 of 1 | mongo 0.99 | 1.88 | 99.0 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 12 s | - |
| e2-base | base | 1191.9/s | 1191.9/s | 47676 | 0 | 86.0 ms | 282.6 ms | 368.6 ms | n/a | 471.0 ms | 0.75 cores | kafka 47% · mongo 100% | 0.40 of 1 | mongo 1.00 | 1.86 | 98.6 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 8 s | - |
| e3-base | base | 1191.9/s | 1191.9/s | 47676 | 0 | 80.9 ms | 241.7 ms | 331.8 ms | n/a | 434.2 ms | 0.73 cores | kafka 50% · mongo 100% | 0.40 of 1 | mongo 1.00 | 1.88 | 98.9 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 8 s | - |
| e3-head | head | 1191.9/s | 1191.9/s | 47676 | 0 | 83.5 ms | 272.4 ms | 413.7 ms | n/a | 651.3 ms | 0.73 cores | kafka 52% · mongo 99% | 0.41 of 1 | mongo 0.99 | 1.88 | 99.7 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 6 s | - |
| e4-head | head | 1191.9/s | 1191.9/s | 47676 | 0 | 78.8 ms | 299.0 ms | 450.6 ms | n/a | 602.1 ms | 0.71 cores | kafka 49% · mongo 99% | 0.40 of 1 | mongo 0.99 | 1.85 | 100.6 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 10 s | - |
| e4-base | base | 1191.9/s | 1191.9/s | 47676 | 0 | 70.7 ms | 235.5 ms | 370.7 ms | n/a | 573.4 ms | 0.68 cores | kafka 49% · mongo 99% | 0.41 of 1 | mongo 0.99 | 1.82 | 98.3 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 3 s | - |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 0.998 | 1.071 | 1.172 | 1.167 | - | 1.024 | 1.010 | 1.016 |
| 2 | 0.998 | 1.040 | 1.047 | 1.051 | - | 0.930 | 1.022 | 1.014 |
| 3 | 0.998 | 1.493 | 1.454 | 1.295 | - | 1.219 | 1.024 | 1.008 |
| 4 | 0.998 | 1.170 | 1.354 | 1.618 | - | 1.641 | 1.015 | 1.028 |
| 5 | 0.998 | 0.878 | 0.980 | 1.236 | - | 1.209 | 1.000 | 1.025 |

Pairs at one rate for both arms, 1191.9/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 2.590 | 2.273 | 1.977 | - | 1.531 | 1.033 | 0.975 |
| 2 | 1.000 | 1.345 | 1.428 | 1.622 | - | 1.600 | 1.010 | 1.004 |
| 3 | 1.000 | 1.032 | 1.127 | 1.247 | - | 1.500 | 1.002 | 1.008 |
| 4 | 1.000 | 1.116 | 1.270 | 1.215 | - | 1.050 | 1.014 | 1.023 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e1-mongo-uniform@100k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 +44.2% (±25.0%) |
| e1-mongo-uniform@100k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 +31.6% (±25.0%) |
| e1-mongo-uniform@100k | r5-head | steady | **unstable** | second half against first, beyond the default limits: p50 +37.8% (±25.0%) |
| e1-mongo-uniform@100k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 +89.8% (±25.0%) |
| e1-mongo-uniform@100k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -26.1% (±25.0%) |


