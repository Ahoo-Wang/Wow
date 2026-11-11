# V10 end-to-end benchmark (D224) on GitHub Actions

Head: `16a04c9e3e1f42f3d6fe7b27d5939b8d8e2b2c3c` 16a04c9e3e1f42f3d6fe7b27d5939b8d8e2b2c3c; V10 baseline: `v10.0.0-beta.1` 22f8ac19cb50b73d90613d7d401257600b3a914f; harness and load generator from head.

Tier: custom; coverage: e1-mongo-uniform@100k.

Each suite ran on one runner of its own (a sessions suite of several sweeps, each sweep in fresh sessions on one of its own), so only its ratios compare across suites; absolute numbers are that runner's. Ratios are b/a per pair (head/base): median (min–max, n pairs). Lower is better except throughput.

## Anomalies

Checks: a partition paused (D243), a tail read the store after a gap or an overflow (D249), more than 1% errors, a measured second below half the rate, p99 above 10× p50 (when p99 ≥ 50 ms), a head allocating more than 5.0% more bytes per operation than its baseline. Errors, dips and tails are not checked where a run went past what the node keeps up with by design (probes, cliff steps that did not keep up).

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e1-mongo-uniform@100k | r1-base | steady | **unstable** | second half against first, beyond the A/A noise: p50 +24.2% (±19.9%) |
| e1-mongo-uniform@100k | r1-head | steady | **unstable** | second half against first, beyond the A/A noise: p50 +23.0% (±19.9%) |
| e1-mongo-uniform@100k | r3-base | steady | **unstable** | second half against first, beyond the A/A noise: p50 +42.0% (±19.9%) |
| e1-mongo-uniform@100k | e1-base | steady | **unstable** | second half against first, beyond the A/A noise: p50 +27.0% (±19.9%) |
| e1-mongo-uniform@100k | e3-base | steady | **unstable** | second half against first, beyond the A/A noise: p50 +27.8% (±19.9%) |
| e1-mongo-uniform@100k | e4-head | steady | **unstable** | second half against first, beyond the A/A noise: p50 -40.9% (±19.9%) |

## Significance

Beyond noise: 1 of 3 ratios; 1 of 1 suites have an A/A noise threshold (test/benchmarks/noise.json). A ratio counts when its 95% bootstrap interval (over the measured seconds) clears the suite's threshold; without one it is `uncalibrated`.

| Suite | b/a | Metric | Rounds | Ratio [95% CI] | Noise | Verdict |
| --- | --- | --- | --- | --- | --- | --- |
| e1-mongo-uniform@100k | head/base | throughput | calibrated | 0.826 [0.826–0.826] | ±2.0% | **lower** |
| e1-mongo-uniform@100k | head/base | p50 | equal | 1.406 [1.135–1.796] | ±19.9% | within noise |
| e1-mongo-uniform@100k | head/base | p99 | equal | 1.180 [1.087–1.488] | ±12.1% | within noise |

## Runners

| Suite | CPU | Cores | Memory | JDK | CPU layout |
| --- | --- | --- | --- | --- | --- |
| e1-mongo-uniform@100k | AMD EPYC 7763 64-Core Processor | 4 | 16 GB | Eclipse Adoptium 25.0.5+8-LTS | generator 3 · node 0 · store 1 · Kafka 2 |

## Saturation points (closed loop)

| Suite | Arm | Saturation | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- | --- |
| e1-mongo-uniform@100k | base | 1660.5/s | 2048 | 1328.4/s | 80% |
| e1-mongo-uniform@100k | head | 1371.3/s | 512 | 1097.0/s | 80% |

## Paired ratios

`calibrated`: each arm at its own calibrated rate, so throughput and CPU per operation compare but the latencies are at **unequal loads**. `equal rate`: both arms at the lower of the two calibrated rates; read the latency ratios from these rows.

| Suite | b/a | Rounds | Saturation | Throughput | p50 | p95 | p99 | p999 | Worst-second p99 | CPU ms/op | Alloc/op |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| e1-mongo-uniform@100k | head/base | calibrated (unequal load) | 0.826 | 0.826 (0.826–0.826, n=5) | 0.492 (0.389–0.507, n=5) | 0.570 (0.466–0.626, n=5) | 0.587 (0.472–0.782, n=5) | - | 0.612 (0.482–0.995, n=5) | 1.167 (1.154–1.213, n=5) | 1.076 (1.066–1.081, n=5) |
| e1-mongo-uniform@100k | head/base | equal rate 1097.0/s | 0.826 | 1.000 (1.000–1.000, n=4) | 1.407 (1.159–1.497, n=4) | 1.318 (0.995–1.365, n=4) | 1.195 (0.700–1.380, n=4) | - | 1.073 (0.611–1.249, n=4) | 1.020 (1.013–1.032, n=4) | 1.016 (1.000–1.020, n=4) |

---

### e1-mongo-uniform@100k

E1 write on MongoDB, uniform keys: the head against the V10 baseline (regression guard), throughput and CPU at each calibrated rate, latency at one rate.

E1, uniform keys over 100000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 4 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 7763 64-Core Processor, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (65–74 s), then waited until the nodes were quiet again (3–5 s).

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 1660.5/s | 2048 | 1328.4/s | 80% |
| head | 1371.3/s | 512 | 1097.0/s | 80% |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 1328.4/s | 1328.4/s | 26568 | 0 | 110.1 ms | 389.1 ms | 581.6 ms | n/a | 704.5 ms | 0.75 cores | kafka 48% · mongo 98% | 0.62 of 1 | mongo 0.98 | 1.67 | 96.6 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 3 s | - |
| probe1-head | head | 1097.0/s | 1097.0/s | 21940 | 0 | 73.2 ms | 240.6 ms | 327.7 ms | n/a | 419.8 ms | 0.69 cores | kafka 48% · mongo 97% | 0.53 of 1 | mongo 0.97 | 1.95 | 101.0 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 2 s | - |

Profile of base (profile-base, not a timed round):

`hot-methods`:

```
                                                               Java Methods that Execute the Most
Method                                                                                                                                          Samples Percent
----------------------------------------------------------------------------------------------------------------------------------------------- ------- -------
java.util.HashMap.getNode(Object)                                                                                                                   465   3.61%
java.util.HashMap.putVal(int, Object, Object, boolean, boolean)                                                                                     214   1.66%
kotlin.coroutines.jvm.internal.ContinuationImpl.<init>(Continuation)                                                                                210   1.63%
org.apache.kafka.clients.producer.KafkaProducer.send(ProducerRecord, Callback)                                                                      203   1.57%
com.mongodb.internal.connection.ByteBufferBsonOutput.writeOnArrayAscii(String, byte[], int, boolean)                                                183   1.42%
kotlin.coroutines.CombinedContext.get(CoroutineContext$Key)                                                                                         139   1.08%
kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith(Object)                                                                              137   1.06%
java.util.Objects.equals(Object, Object)                                                                                                            131   1.02%
java.util.regex.Pattern$BmpCharPropertyGreedy.match(Matcher, int, CharSequence)                                                                     128   0.99%
kotlin.jvm.internal.Intrinsics.throwParameterIsNullNPE(String)                                                                                      121   0.94%
reactor.core.publisher.InnerOperator.currentContext()                                                                                               103   0.80%
kotlinx.coroutines.scheduling.CoroutineScheduler$Worker.findAnyTask(boolean)                                                                        103   0.80%
java.util.concurrent.ConcurrentHashMap.get(Object)                                                                                                   98   0.76%
```

`allocation-by-class`:

```
                                                                      Allocation by Class
Object Type                                                                                                                                 Allocation Pressure
------------------------------------------------------------------------------------------------------------------------------------------- -------------------
byte[]                                                                                                                                                    8.08%
java.lang.Object[]                                                                                                                                        6.72%
java.util.HashMap$Node[]                                                                                                                                  4.46%
java.util.LinkedHashMap$Entry                                                                                                                             4.12%
java.util.LinkedHashMap                                                                                                                                   3.39%
jdk.internal.vm.StackChunk                                                                                                                                2.61%
java.lang.String                                                                                                                                          2.34%
java.util.ArrayList                                                                                                                                       2.13%
org.bson.internal.ChildCodecRegistry                                                                                                                      1.96%
org.bson.codecs.Codec[]                                                                                                                                   1.70%
org.bson.BsonBinaryWriter$Context                                                                                                                         1.65%
org.bson.internal.CodecCache$CodecCacheKey                                                                                                                1.55%
org.bson.AbstractBsonWriter$State[]                                                                                                                       1.25%
```

`contention-by-site`:

```
                                                                       Contention by Site
StackTrace                                                                                                                                Count    Avg.    Max.
----------------------------------------------------------------------------------------------------------------------------------------- ----- ------- -------
tools.jackson.databind.ser.SerializerCache.addAndResolveNonTypedSerializer(Class, JavaType, ValueSerializer, SerializationContext)            1  144 ms  144 ms
sun.nio.ch.EPollSelectorImpl.clearInterrupt()                                                                                                43 15.3 ms  112 ms
org.apache.kafka.clients.NetworkClient.parseResponse(ByteBuffer, RequestHeader)                                                               1 93.4 ms 93.4 ms
tools.jackson.databind.ser.SerializerCache.addAndResolveNonTypedSerializer(Class, JavaType, ValueSerializer, SerializationContext)            1 56.4 ms 56.4 ms
java.util.zip.ZipFile.getEntry(String)                                                                                                        1 30.6 ms 30.6 ms
jdk.internal.loader.BuiltinClassLoader.loadClassOrNull(String, boolean)                                                                       1 29.6 ms 29.6 ms
org.apache.kafka.common.requests.ApiVersionsRequest$Builder.build(short)                                                                      1 28.8 ms 28.8 ms
org.apache.kafka.clients.NetworkClient.handleApiVersionsResponse(List, NetworkClient$InFlightRequest, long, ApiVersionsResponse)              1 28.1 ms 28.1 ms
jdk.jfr.internal.MetadataRepository.register(Class)                                                                                           1 25.3 ms 25.3 ms
jdk.internal.loader.BuiltinClassLoader.loadClassOrNull(String, boolean)                                                                       1 20.7 ms 20.7 ms
org.apache.kafka.clients.producer.internals.RecordAccumulator.partitionReady(...)                                                             4 14.4 ms 19.1 ms
jdk.internal.loader.BuiltinClassLoader.loadClassOrNull(String, boolean)                                                                       1 17.2 ms 17.2 ms
me.ahoo.wow.runtime.wait.Waiting.carry(CommandResult)                                                                                         9 12.8 ms 16.1 ms
```

`gc-pauses`:

```
GC Pauses
---------
Total Pause Time: 5.67 s
Number of Pauses: 78
Minimum Pause Time: 0.0158 ms
Median Pause Time: 80.5 ms
Average Pause Time: 72.7 ms
P90 Pause Time: 113 ms
P95 Pause Time: 129 ms
P99 Pause Time: 146 ms
P99.9% Pause Time: 146 ms
Maximum Pause Time: 146 ms
```

`safepoints`:

```
                                                                            Safepoints
Start Time                  Duration                                     State Syncronization                  JNI Critical Threads                  Total Threads
--------------------------- --------------------------- ------------------------------------- ------------------------------------- ------------------------------
19:57:12                    Indefinite                                                    N/A                                     0                             11
19:57:12                    0.212 ms                                                      N/A                                     0                             11
19:57:12                    14.5 ms                                                       N/A                                     0                             12
19:57:13                    13.8 ms                                                       N/A                                     0                             12
19:57:13                    3.78 ms                                                       N/A                                     0                             12
19:57:13                    0.102 ms                                                      N/A                                     0                             12
19:57:16                    24.0 ms                                                       N/A                                     0                             13
19:57:16                    28.6 ms                                                       N/A                                     0                             13
19:57:16                    30.9 ms                                                       N/A                                     0                             13
19:57:16                    14.9 ms                                                       N/A                                     0                             13
19:57:16                    0.107 ms                                                      N/A                                     0                             13
19:57:19                    45.8 ms                                                       N/A                                     0                             24
19:57:19                    0.258 ms                                                      N/A                                     0                             24
```

`latencies-by-type`:

```
                                                                       Latencies by Type
Event Type                                                                                                             Count Average    P 99  Longest     Total
-------------------------------------------------------------------------------------------------------------------- ------- ------- ------- -------- ---------
Java Thread Park                                                                                                     217,339 16.8 ms  116 ms 5 m 47 s   1 h 1 m
Socket Read                                                                                                           73,773 35.1 ms  146 ms   28.0 s 43 m 11 s
Java Monitor Wait                                                                                                         13  28.6 s 1 m 0 s  1 m 0 s  6 m 12 s
Java Thread Sleep                                                                                                         35  10.0 s  10.0 s   10.0 s  5 m 50 s
Socket Write                                                                                                           4,881 3.03 ms 13.2 ms   136 ms    14.8 s
Java Monitor Blocked                                                                                                      82 18.2 ms  144 ms   144 ms    1.49 s
File Read                                                                                                                 19 3.19 ms 6.23 ms  6.23 ms   60.5 ms
File Write                                                                                                                15 2.25 ms 7.39 ms  7.39 ms   33.7 ms
```

`socket-reads-by-host`:

```
                                                                      Socket Reads by Host
Host                                                                                                                                           Reads Total Read
--------------------------------------------------------------------------------------------------------------------------------------------- ------ ----------
localhost                                                                                                                                     73,757     1.1 MB
10.1.1.115                                                                                                                                        16     1.7 kB
```

Profile of head (profile-head, not a timed round):

`hot-methods`:

```
                                                               Java Methods that Execute the Most
Method                                                                                                                                          Samples Percent
----------------------------------------------------------------------------------------------------------------------------------------------- ------- -------
java.util.HashMap.getNode(Object)                                                                                                                   378   2.99%
java.util.HashMap.putVal(int, Object, Object, boolean, boolean)                                                                                     266   2.10%
kotlin.coroutines.jvm.internal.ContinuationImpl.<init>(Continuation)                                                                                219   1.73%
org.apache.kafka.clients.producer.KafkaProducer.send(ProducerRecord, Callback)                                                                      198   1.56%
com.mongodb.internal.connection.ByteBufferBsonOutput.writeOnArrayAscii(String, byte[], int, boolean)                                                191   1.51%
java.util.concurrent.ConcurrentHashMap.get(Object)                                                                                                  181   1.43%
java.util.regex.Pattern$BmpCharProperty.match(Matcher, int, CharSequence)                                                                           134   1.06%
kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith(Object)                                                                              119   0.94%
kotlin.jvm.internal.Intrinsics.throwParameterIsNullNPE(String)                                                                                      114   0.90%
kotlinx.coroutines.DispatchedTask.run()                                                                                                             113   0.89%
kotlin.coroutines.CombinedContext.get(CoroutineContext$Key)                                                                                         104   0.82%
tools.jackson.databind.ser.BeanPropertyWriter.serializeAsProperty(Object, JsonGenerator, SerializationContext)                                       94   0.74%
reactor.core.publisher.InnerOperator.currentContext()                                                                                                92   0.73%
```

`allocation-by-class`:

```
                                                                      Allocation by Class
Object Type                                                                                                                                 Allocation Pressure
------------------------------------------------------------------------------------------------------------------------------------------- -------------------
byte[]                                                                                                                                                    7.52%
java.lang.Object[]                                                                                                                                        6.99%
java.util.HashMap$Node[]                                                                                                                                  4.18%
java.util.LinkedHashMap$Entry                                                                                                                             4.06%
java.util.LinkedHashMap                                                                                                                                   3.50%
jdk.internal.vm.StackChunk                                                                                                                                2.52%
java.lang.String                                                                                                                                          2.21%
org.bson.internal.ChildCodecRegistry                                                                                                                      2.19%
java.util.ArrayList                                                                                                                                       2.16%
org.bson.codecs.Codec[]                                                                                                                                   1.79%
org.bson.BsonBinaryWriter$Context                                                                                                                         1.53%
org.bson.internal.CodecCache$CodecCacheKey                                                                                                                1.51%
kotlin.coroutines.CombinedContext                                                                                                                         1.30%
```

`contention-by-site`:

```
                                                                       Contention by Site
StackTrace                                                                                                                                Count    Avg.    Max.
----------------------------------------------------------------------------------------------------------------------------------------- ----- ------- -------
tools.jackson.databind.ser.SerializerCache._makeReadOnlyLookupMap()                                                                           1  143 ms  143 ms
sun.nio.ch.EPollSelectorImpl.clearInterrupt()                                                                                                36 20.3 ms  133 ms
org.apache.kafka.clients.producer.internals.RecordAccumulator.partitionReady(...)                                                             5 41.7 ms 95.3 ms
tools.jackson.databind.ser.SerializerCache.addAndResolveNonTypedSerializer(Class, JavaType, ValueSerializer, SerializationContext)            1 52.4 ms 52.4 ms
tools.jackson.databind.ser.SerializerCache.addAndResolveNonTypedSerializer(Class, JavaType, ValueSerializer, SerializationContext)            1 46.9 ms 46.9 ms
org.apache.kafka.common.requests.ApiVersionsRequest$Builder.build(short)                                                                      1 35.3 ms 35.3 ms
org.apache.kafka.clients.NetworkClient.parseResponse(ByteBuffer, RequestHeader)                                                               1 33.3 ms 33.3 ms
jdk.internal.loader.BuiltinClassLoader.loadClassOrNull(String, boolean)                                                                       1 23.2 ms 23.2 ms
jdk.internal.loader.BuiltinClassLoader.loadClassOrNull(String, boolean)                                                                       1 22.9 ms 22.9 ms
me.ahoo.wow.runtime.wait.Waiting.carry(CommandResult)                                                                                         9 14.4 ms 22.2 ms
jdk.internal.loader.BuiltinClassLoader.loadClassOrNull(String, boolean)                                                                       1 13.9 ms 13.9 ms
tools.jackson.databind.ser.BasicSerializerFactory.findSerializerByPrimaryType(...)                                                            1 13.8 ms 13.8 ms
me.ahoo.wow.runtime.subscription.SubscriptionWorker.drain(AggregateRef, SubscriptionWorker$Lane, Continuation)                                5 11.9 ms 13.7 ms
```

`gc-pauses`:

```
GC Pauses
---------
Total Pause Time: 6.38 s
Number of Pauses: 78
Minimum Pause Time: 0.0177 ms
Median Pause Time: 88.5 ms
Average Pause Time: 81.8 ms
P90 Pause Time: 130 ms
P95 Pause Time: 140 ms
P99 Pause Time: 145 ms
P99.9% Pause Time: 145 ms
Maximum Pause Time: 145 ms
```

`safepoints`:

```
                                                                            Safepoints
Start Time                  Duration                                     State Syncronization                  JNI Critical Threads                  Total Threads
--------------------------- --------------------------- ------------------------------------- ------------------------------------- ------------------------------
20:03:38                    Indefinite                                                    N/A                                     0                             11
20:03:38                    0.216 ms                                                      N/A                                     0                             11
20:03:39                    13.9 ms                                                       N/A                                     0                             12
20:03:40                    23.8 ms                                                       N/A                                     0                             12
20:03:40                    6.71 ms                                                       N/A                                     0                             12
20:03:40                    0.0998 ms                                                     N/A                                     0                             12
20:03:43                    24.7 ms                                                       N/A                                     0                             13
20:03:43                    20.6 ms                                                       N/A                                     0                             13
20:03:43                    26.2 ms                                                       N/A                                     0                             13
20:03:43                    14.7 ms                                                       N/A                                     0                             13
20:03:43                    3.11 ms                                                       N/A                                     0                             13
20:03:45                    60.5 ms                                                       N/A                                     0                             22
20:03:46                    0.122 ms                                                      N/A                                     0                             22
```

`latencies-by-type`:

```
                                                                       Latencies by Type
Event Type                                                                                                             Count Average    P 99  Longest     Total
-------------------------------------------------------------------------------------------------------------------- ------- ------- ------- -------- ---------
Java Thread Park                                                                                                     209,015 17.1 ms  120 ms 5 m 39 s 59 m 39 s
Socket Read                                                                                                           72,959 34.3 ms  146 ms   28.1 s 41 m 44 s
Java Thread Sleep                                                                                                         34  10.0 s  10.0 s   10.0 s  5 m 40 s
Java Monitor Wait                                                                                                         11  28.3 s 1 m 0 s  1 m 0 s  5 m 12 s
Socket Write                                                                                                           4,867 2.95 ms 13.5 ms   128 ms    14.3 s
Java Monitor Blocked                                                                                                      74 22.0 ms  143 ms   143 ms    1.63 s
File Read                                                                                                                 14 3.52 ms 9.03 ms  9.03 ms   49.2 ms
File Write                                                                                                                 5 3.16 ms 9.17 ms  9.17 ms   15.8 ms
```

`socket-reads-by-host`:

```
                                                                      Socket Reads by Host
Host                                                                                                                                           Reads Total Read
--------------------------------------------------------------------------------------------------------------------------------------------- ------ ----------
localhost                                                                                                                                     72,942     1.1 MB
10.1.1.115                                                                                                                                        17     1.7 kB
```

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 1328.4/s | 1328.4/s | 53136 | 0 | 186.4 ms | 497.7 ms | 647.2 ms | n/a | 757.8 ms | 0.77 cores | kafka 53% · mongo 100% | 0.45 of 1 | mongo 1.00 | 1.73 | 94.9 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 10 s | - |
| r1-head | head | 1097.0/s | 1097.0/s | 43880 | 0 | 74.8 ms | 311.3 ms | 505.9 ms | n/a | 753.7 ms | 0.70 cores | kafka 49% · mongo 100% | 0.38 of 1 | mongo 1.00 | 2.00 | 101.2 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 8 s | - |
| r2-head | head | 1097.0/s | 1097.0/s | 43880 | 0 | 60.2 ms | 209.9 ms | 303.1 ms | n/a | 395.3 ms | 0.70 cores | kafka 50% · mongo 98% | 0.39 of 1 | mongo 0.98 | 2.00 | 103.4 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 4 s | - |
| r2-base | base | 1328.4/s | 1328.4/s | 53136 | 0 | 122.4 ms | 405.5 ms | 610.3 ms | n/a | 819.2 ms | 0.77 cores | kafka 50% · mongo 100% | 0.44 of 1 | mongo 1.00 | 1.71 | 96.1 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 5 s | - |
| r3-base | base | 1328.4/s | 1328.4/s | 53136 | 0 | 106.5 ms | 395.3 ms | 585.7 ms | n/a | 770.0 ms | 0.74 cores | kafka 45% · mongo 100% | 0.41 of 1 | mongo 1.00 | 1.65 | 96.7 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 8 s | - |
| r3-head | head | 1097.0/s | 1097.0/s | 43880 | 0 | 54.0 ms | 225.3 ms | 344.1 ms | n/a | 471.0 ms | 0.72 cores | kafka 50% · mongo 98% | 0.37 of 1 | mongo 0.98 | 2.00 | 103.8 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 6 s | - |
| r4-head | head | 1097.0/s | 1097.0/s | 43880 | 0 | 59.6 ms | 207.9 ms | 297.0 ms | n/a | 436.2 ms | 0.67 cores | kafka 48% · mongo 99% | 0.36 of 1 | mongo 0.99 | 1.95 | 103.4 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 6 s | - |
| r4-base | base | 1328.4/s | 1328.4/s | 53136 | 0 | 121.3 ms | 350.2 ms | 471.0 ms | n/a | 667.6 ms | 0.75 cores | kafka 47% · mongo 100% | 0.43 of 1 | mongo 1.00 | 1.67 | 95.9 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 11 s | - |
| r5-base | base | 1328.4/s | 1328.4/s | 53136 | 0 | 141.3 ms | 421.9 ms | 585.7 ms | n/a | 774.1 ms | 0.73 cores | kafka 50% · mongo 100% | 0.43 of 1 | mongo 1.00 | 1.68 | 95.4 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 12 s | - |
| r5-head | head | 1097.0/s | 1097.0/s | 43880 | 0 | 55.0 ms | 196.6 ms | 276.5 ms | n/a | 423.9 ms | 0.69 cores | kafka 49% · mongo 99% | 0.36 of 1 | mongo 0.99 | 1.98 | 103.2 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 6 s | - |
| e1-base | base | 1097.0/s | 1097.0/s | 43880 | 0 | 48.6 ms | 200.7 ms | 389.1 ms | n/a | 589.8 ms | 0.67 cores | kafka 51% · mongo 99% | 0.38 of 1 | mongo 0.99 | 1.98 | 101.0 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 6 s | - |
| e1-head | head | 1097.0/s | 1097.0/s | 43880 | 0 | 67.6 ms | 199.7 ms | 272.4 ms | n/a | 360.4 ms | 0.72 cores | kafka 50% · mongo 98% | 0.37 of 1 | mongo 0.98 | 2.00 | 103.0 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 3 s | - |
| e2-head | head | 1097.0/s | 1097.0/s | 43880 | 0 | 65.3 ms | 248.8 ms | 360.4 ms | n/a | 493.6 ms | 0.71 cores | kafka 51% · mongo 99% | 0.38 of 1 | mongo 0.99 | 2.01 | 102.2 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 4 s | - |
| e2-base | base | 1097.0/s | 1097.0/s | 43880 | 0 | 56.3 ms | 182.3 ms | 261.1 ms | n/a | 395.3 ms | 0.66 cores | kafka 50% · mongo 99% | 0.39 of 1 | mongo 0.99 | 1.96 | 101.0 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 5 s | - |
| e3-base | base | 1097.0/s | 1097.0/s | 43880 | 0 | 47.1 ms | 172.0 ms | 255.0 ms | n/a | 387.1 ms | 0.67 cores | kafka 48% · mongo 99% | 0.38 of 1 | mongo 0.99 | 1.95 | 100.7 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 5 s | - |
| e3-head | head | 1097.0/s | 1097.0/s | 43880 | 0 | 67.1 ms | 233.5 ms | 319.5 ms | n/a | 469.0 ms | 0.71 cores | kafka 50% · mongo 100% | 0.38 of 1 | mongo 1.00 | 2.01 | 102.6 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 7 s | - |
| e4-head | head | 1097.0/s | 1097.0/s | 43880 | 0 | 68.6 ms | 216.1 ms | 290.8 ms | n/a | 376.8 ms | 0.71 cores | kafka 50% · mongo 99% | 0.38 of 1 | mongo 0.99 | 2.00 | 103.3 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 3 s | - |
| e4-base | base | 1097.0/s | 1097.0/s | 43880 | 0 | 45.8 ms | 169.0 ms | 256.0 ms | n/a | 403.5 ms | 0.68 cores | kafka 51% · mongo 99% | 0.36 of 1 | mongo 0.99 | 1.98 | 103.3 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 4 s | - |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 0.826 | 0.401 | 0.626 | 0.782 | - | 0.995 | 1.154 | 1.066 |
| 2 | 0.826 | 0.492 | 0.518 | 0.497 | - | 0.482 | 1.167 | 1.076 |
| 3 | 0.826 | 0.507 | 0.570 | 0.587 | - | 0.612 | 1.213 | 1.073 |
| 4 | 0.826 | 0.492 | 0.594 | 0.630 | - | 0.653 | 1.165 | 1.078 |
| 5 | 0.826 | 0.389 | 0.466 | 0.472 | - | 0.548 | 1.178 | 1.081 |

Pairs at one rate for both arms, 1097.0/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 1.389 | 0.995 | 0.700 | - | 0.611 | 1.013 | 1.020 |
| 2 | 1.000 | 1.159 | 1.365 | 1.380 | - | 1.249 | 1.028 | 1.012 |
| 3 | 1.000 | 1.424 | 1.357 | 1.253 | - | 1.212 | 1.032 | 1.020 |
| 4 | 1.000 | 1.497 | 1.279 | 1.136 | - | 0.934 | 1.013 | 1.000 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e1-mongo-uniform@100k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 +42.0% (±25.0%) |
| e1-mongo-uniform@100k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 +27.0% (±25.0%) |
| e1-mongo-uniform@100k | e3-base | steady | **unstable** | second half against first, beyond the default limits: p50 +27.8% (±25.0%) |
| e1-mongo-uniform@100k | e4-head | steady | **unstable** | second half against first, beyond the default limits: p50 -40.9% (±25.0%) |


