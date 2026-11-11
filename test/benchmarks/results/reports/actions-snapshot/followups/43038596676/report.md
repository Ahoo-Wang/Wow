# V10 end-to-end benchmark (D224) on GitHub Actions

Head: `f85650f560adbac4dcd80a71619e52121bfafc00` f85650f560adbac4dcd80a71619e52121bfafc00; V10 baseline: none; harness and load generator from head.

Tier: custom; coverage: e1-mongo-uniform@100k.

Each suite ran on one runner of its own (a sessions suite of several sweeps, each sweep in fresh sessions on one of its own), so only its ratios compare across suites; absolute numbers are that runner's. Ratios are b/a per pair (head/base): median (min–max, n pairs). Lower is better except throughput.

## Anomalies

Checks: a partition paused (D243), a tail read the store after a gap or an overflow (D249), more than 1% errors, a measured second below half the rate, p99 above 10× p50 (when p99 ≥ 50 ms), a head allocating more than 5.0% more bytes per operation than its baseline. Errors, dips and tails are not checked where a run went past what the node keeps up with by design (probes, cliff steps that did not keep up).

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e1-mongo-uniform@100k | r1-a | steady | **unstable** | second half against first, beyond the A/A noise: p50 +86.8% (±19.9%) |
| e1-mongo-uniform@100k | r1-b | steady | **unstable** | second half against first, beyond the A/A noise: p50 +83.4% (±19.9%) |
| e1-mongo-uniform@100k | r2-b | steady | **unstable** | second half against first, beyond the A/A noise: p50 +61.9% (±19.9%) |
| e1-mongo-uniform@100k | r2-a | steady | **unstable** | second half against first, beyond the A/A noise: p50 +22.5% (±19.9%) |
| e1-mongo-uniform@100k | r3-a | steady | **unstable** | second half against first, beyond the A/A noise: p50 +67.4% (±19.9%) |
| e1-mongo-uniform@100k | r3-b | steady | **unstable** | second half against first, beyond the A/A noise: p50 +48.7% (±19.9%) |
| e1-mongo-uniform@100k | r4-b | steady | **unstable** | second half against first, beyond the A/A noise: p50 +44.5% (±19.9%) |
| e1-mongo-uniform@100k | r4-a | steady | **unstable** | second half against first, beyond the A/A noise: p50 +53.2% (±19.9%) |
| e1-mongo-uniform@100k | e1-b | steady | **unstable** | second half against first, beyond the A/A noise: p50 -34.5% (±19.9%) |
| e1-mongo-uniform@100k | e2-b | steady | **unstable** | second half against first, beyond the A/A noise: p50 +29.8% (±19.9%) |
| e1-mongo-uniform@100k | e2-a | steady | **unstable** | second half against first, beyond the A/A noise: p50 +52.9% (±19.9%) |
| e1-mongo-uniform@100k | e3-a | steady | **unstable** | second half against first, beyond the A/A noise: p50 +22.4% (±19.9%) |
| e1-mongo-uniform@100k | e4-b | steady | **unstable** | second half against first, beyond the A/A noise: p50 +22.0% (±19.9%) |
| e1-mongo-uniform@100k | e4-a | steady | **unstable** | second half against first, beyond the A/A noise: p50 +21.1% (±19.9%) |

## A/A noise

The head against itself at one rate, 4 rounds or more: each metric's threshold is the half-width of the 95% bootstrap interval of its ratio, at least 2%. The report writes these as noise.json; commit it as test/benchmarks/noise.json to make them the thresholds of later runs.

| Suite | throughput | p50 | p99 |
| --- | --- | --- | --- |
| e1-mongo-uniform@100k | ±2.0% | ±24.1% | ±16.2% |

## Runners

| Suite | CPU | Cores | Memory | JDK | CPU layout |
| --- | --- | --- | --- | --- | --- |
| e1-mongo-uniform@100k | Intel(R) Xeon(R) 6973P-C | 4 | 16 GB | Eclipse Adoptium 25.0.5+8-LTS | generator 3 · node 0 · store 1 · Kafka 2 |

## Saturation points (closed loop)

| Suite | Arm | Saturation | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- | --- |
| e1-mongo-uniform@100k | a | 2319.6/s | 256 | 1855.7/s | 80% |
| e1-mongo-uniform@100k | b | 2183.1/s | 512 | 1746.5/s | 80% |

## Paired ratios

`calibrated`: each arm at its own calibrated rate, so throughput and CPU per operation compare but the latencies are at **unequal loads**. `equal rate`: both arms at the lower of the two calibrated rates; read the latency ratios from these rows.

| Suite | b/a | Rounds | Saturation | Throughput | p50 | p95 | p99 | p999 | Worst-second p99 | CPU ms/op | Alloc/op |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| e1-mongo-uniform@100k | b/a | calibrated (unequal load) | 0.941 | 0.941 (0.941–0.941, n=4) | 0.630 (0.543–0.765, n=4) | 0.763 (0.537–1.079, n=4) | 0.836 (0.563–1.250, n=4) | - | 0.802 (0.578–1.075, n=4) | 1.047 (1.038–1.050, n=4) | 1.021 (1.018–1.037, n=4) |
| e1-mongo-uniform@100k | b/a | equal rate 1746.5/s | 0.941 | 1.000 (1.000–1.000, n=4) | 1.041 (0.846–1.535, n=4) | 1.092 (0.961–1.717, n=4) | 1.253 (0.951–2.056, n=4) | - | 1.348 (0.842–1.747, n=4) | 1.000 (0.979–1.014, n=4) | 0.998 (0.991–1.011, n=4) |

---

### e1-mongo-uniform@100k (A/A: head against head)

E1 write on MongoDB, uniform keys: the head against the V10 baseline (regression guard), throughput and CPU at each calibrated rate, latency at one rate.

E1, uniform keys over 100000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 4 round(s). Runner: Intel(R) Xeon(R) 6973P-C, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (39–43 s), then waited until the nodes were quiet again (3–4 s).

- `b`: wow.access.capacity=1000000

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| a | 2319.6/s | 256 | 1855.7/s | 80% |
| b | 2183.1/s | 512 | 1746.5/s | 80% |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-a | a | 1855.7/s | 1855.7/s | 37113 | 0 | 75.8 ms | 315.4 ms | 442.4 ms | n/a | 561.2 ms | 0.69 cores | kafka 43% · mongo 98% | 0.38 of 1 | mongo 0.98 | 1.13 | 97.8 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 7 s | - |
| probe1-b | b | 1746.5/s | 1746.5/s | 34930 | 0 | 60.7 ms | 268.3 ms | 489.5 ms | n/a | 749.6 ms | 0.72 cores | kafka 42% · mongo 97% | 0.43 of 1 | mongo 0.97 | 1.21 | 99.4 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 4 s | - |

Profile of a (profile-a, not a timed round):

`hot-methods`:

```
                                                               Java Methods that Execute the Most
Method                                                                                                                                          Samples Percent
----------------------------------------------------------------------------------------------------------------------------------------------- ------- -------
java.util.HashMap.getNode(Object)                                                                                                                   212   2.89%
java.util.HashMap.putVal(int, Object, Object, boolean, boolean)                                                                                     154   2.10%
com.mongodb.internal.connection.ByteBufferBsonOutput.writeOnArrayAscii(String, byte[], int, boolean)                                                112   1.53%
kotlin.coroutines.jvm.internal.ContinuationImpl.<init>(Continuation)                                                                                 93   1.27%
kotlin.coroutines.CombinedContext.get(CoroutineContext$Key)                                                                                          87   1.19%
reactor.core.publisher.InnerOperator.currentContext()                                                                                                85   1.16%
kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith(Object)                                                                               85   1.16%
java.lang.String.charAt(int)                                                                                                                         78   1.06%
java.util.regex.Pattern$BmpCharProperty.match(Matcher, int, CharSequence)                                                                            76   1.04%
org.apache.kafka.clients.producer.KafkaProducer.send(ProducerRecord, Callback)                                                                       71   0.97%
java.util.Objects.equals(Object, Object)                                                                                                             68   0.93%
java.util.concurrent.ConcurrentHashMap.get(Object)                                                                                                   67   0.91%
java.util.TreeMap.getEntry(Object)                                                                                                                   61   0.83%
```

`allocation-by-class`:

```
                                                                      Allocation by Class
Object Type                                                                                                                                 Allocation Pressure
------------------------------------------------------------------------------------------------------------------------------------------- -------------------
byte[]                                                                                                                                                    8.12%
java.lang.Object[]                                                                                                                                        6.66%
java.util.LinkedHashMap$Entry                                                                                                                             4.33%
java.util.HashMap$Node[]                                                                                                                                  4.14%
java.util.LinkedHashMap                                                                                                                                   3.36%
java.util.ArrayList                                                                                                                                       2.36%
jdk.internal.vm.StackChunk                                                                                                                                2.19%
java.lang.String                                                                                                                                          2.15%
org.bson.internal.ChildCodecRegistry                                                                                                                      2.07%
org.bson.codecs.Codec[]                                                                                                                                   1.60%
kotlin.coroutines.CombinedContext                                                                                                                         1.43%
org.bson.BsonBinaryWriter$Context                                                                                                                         1.38%
org.bson.internal.CodecCache$CodecCacheKey                                                                                                                1.35%
```

`contention-by-site`:

```
                                                                       Contention by Site
StackTrace                                                                                                                                Count    Avg.    Max.
----------------------------------------------------------------------------------------------------------------------------------------- ----- ------- -------
tools.jackson.databind.ser.SerializerCache._makeReadOnlyLookupMap()                                                                           1 81.0 ms 81.0 ms
me.ahoo.wow.runtime.subscription.SubscriptionWorker.drain(AggregateRef, SubscriptionWorker$Lane, Continuation)                                2 37.1 ms 64.0 ms
tools.jackson.databind.ser.SerializerCache.addAndResolveNonTypedSerializer(Class, JavaType, ValueSerializer, SerializationContext)            1 55.1 ms 55.1 ms
java.util.zip.ZipFile.getMetaInfVersions(String)                                                                                              1 51.6 ms 51.6 ms
jdk.internal.loader.BuiltinClassLoader.loadClassOrNull(String, boolean)                                                                       1 32.3 ms 32.3 ms
jdk.internal.loader.BuiltinClassLoader.loadClassOrNull(String, boolean)                                                                       1 27.4 ms 27.4 ms
org.apache.kafka.common.requests.ApiVersionsRequest$Builder.build(short)                                                                      1 23.3 ms 23.3 ms
me.ahoo.wow.runtime.wait.Waiting.carry(CommandResult)                                                                                         7 13.6 ms 19.5 ms
sun.nio.ch.EPollSelectorImpl.clearInterrupt()                                                                                                21 12.8 ms 16.8 ms
kotlinx.coroutines.internal.ThreadSafeHeap.remove(ThreadSafeHeapNode)                                                                         1 15.6 ms 15.6 ms
org.apache.kafka.clients.producer.internals.RecordAccumulator.partitionReady(...)                                                             1 15.1 ms 15.1 ms
jdk.internal.loader.BuiltinClassLoader.loadClassOrNull(String, boolean)                                                                       1 14.1 ms 14.1 ms
tools.jackson.databind.ser.SerializerCache.addAndResolveNonTypedSerializer(Class, JavaType, ValueSerializer, SerializationContext)            1 14.0 ms 14.0 ms
```

`gc-pauses`:

```
GC Pauses
---------
Total Pause Time: 5.47 s
Number of Pauses: 86
Minimum Pause Time: 0.0182 ms
Median Pause Time: 68.6 ms
Average Pause Time: 63.6 ms
P90 Pause Time: 93.8 ms
P95 Pause Time: 99.7 ms
P99 Pause Time: 129 ms
P99.9% Pause Time: 129 ms
Maximum Pause Time: 129 ms
```

`safepoints`:

```
                                                                            Safepoints
Start Time                  Duration                                     State Syncronization                  JNI Critical Threads                  Total Threads
--------------------------- --------------------------- ------------------------------------- ------------------------------------- ------------------------------
15:55:45                    Indefinite                                                    N/A                                     0                             11
15:55:45                    0.192 ms                                                      N/A                                     0                             11
15:55:46                    17.7 ms                                                       N/A                                     0                             12
15:55:46                    22.2 ms                                                       N/A                                     0                             12
15:55:46                    6.97 ms                                                       N/A                                     0                             12
15:55:46                    0.295 ms                                                      N/A                                     0                             12
15:55:48                    31.1 ms                                                       N/A                                     0                             13
15:55:48                    21.0 ms                                                       N/A                                     0                             13
15:55:48                    3.03 ms                                                       N/A                                     0                             13
15:55:48                    18.7 ms                                                       N/A                                     0                             13
15:55:48                    7.73 ms                                                       N/A                                     0                             13
15:55:48                    0.112 ms                                                      N/A                                     0                             13
15:55:50                    42.2 ms                                                       N/A                                     0                             22
```

`latencies-by-type`:

```
                                                                       Latencies by Type
Event Type                                                                                                             Count Average    P 99  Longest     Total
-------------------------------------------------------------------------------------------------------------------- ------- ------- ------- -------- ---------
Java Thread Park                                                                                                     165,863 14.5 ms 97.6 ms 3 m 52 s 39 m 58 s
Socket Read                                                                                                           55,846 25.4 ms  102 ms   15.4 s 23 m 39 s
Java Monitor Wait                                                                                                         10  25.2 s 1 m 0 s  1 m 0 s  4 m 12 s
Java Thread Sleep                                                                                                         23  10.0 s  10.0 s   10.0 s  3 m 50 s
Socket Write                                                                                                           2,230 2.86 ms 14.9 ms   100 ms    6.37 s
Java Monitor Blocked                                                                                                      43 18.7 ms 81.0 ms  81.0 ms    803 ms
File Read                                                                                                                  8 3.05 ms 5.61 ms  5.61 ms   24.4 ms
File Write                                                                                                                11 2.19 ms 4.95 ms  4.95 ms   24.1 ms
```

`socket-reads-by-host`:

```
                                                                      Socket Reads by Host
Host                                                                                                                                           Reads Total Read
--------------------------------------------------------------------------------------------------------------------------------------------- ------ ----------
localhost                                                                                                                                     55,831   877.2 kB
10.1.0.91                                                                                                                                         15     1.0 kB
```

Profile of b (profile-b, not a timed round):

`hot-methods`:

```
                                                               Java Methods that Execute the Most
Method                                                                                                                                          Samples Percent
----------------------------------------------------------------------------------------------------------------------------------------------- ------- -------
java.util.HashMap.getNode(Object)                                                                                                                   323   4.31%
java.util.HashMap.putVal(int, Object, Object, boolean, boolean)                                                                                     136   1.81%
kotlin.coroutines.jvm.internal.ContinuationImpl.<init>(Continuation)                                                                                102   1.36%
reactor.core.publisher.InnerOperator.currentContext()                                                                                                93   1.24%
kotlin.coroutines.CombinedContext.get(CoroutineContext$Key)                                                                                          85   1.13%
org.apache.kafka.clients.producer.KafkaProducer.send(ProducerRecord, Callback)                                                                       83   1.11%
com.mongodb.internal.connection.ByteBufferBsonOutput.writeOnArrayAscii(String, byte[], int, boolean)                                                 79   1.05%
kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith(Object)                                                                               79   1.05%
java.util.regex.Pattern$BmpCharPropertyGreedy.match(Matcher, int, CharSequence)                                                                      74   0.99%
java.util.regex.Pattern$BmpCharProperty.match(Matcher, int, CharSequence)                                                                            68   0.91%
kotlin.jvm.internal.Intrinsics.throwParameterIsNullNPE(String)                                                                                       66   0.88%
java.util.Objects.equals(Object, Object)                                                                                                             62   0.83%
java.util.concurrent.ConcurrentHashMap.get(Object)                                                                                                   60   0.80%
```

`allocation-by-class`:

```
                                                                      Allocation by Class
Object Type                                                                                                                                 Allocation Pressure
------------------------------------------------------------------------------------------------------------------------------------------- -------------------
byte[]                                                                                                                                                    7.49%
java.lang.Object[]                                                                                                                                        6.75%
java.util.HashMap$Node[]                                                                                                                                  4.40%
java.util.LinkedHashMap$Entry                                                                                                                             4.35%
java.util.LinkedHashMap                                                                                                                                   3.36%
jdk.internal.vm.StackChunk                                                                                                                                2.40%
java.lang.String                                                                                                                                          2.24%
java.util.ArrayList                                                                                                                                       2.10%
org.bson.internal.ChildCodecRegistry                                                                                                                      1.94%
org.bson.codecs.Codec[]                                                                                                                                   1.63%
kotlin.coroutines.CombinedContext                                                                                                                         1.53%
org.bson.BsonBinaryWriter$Context                                                                                                                         1.51%
org.bson.internal.CodecCache$CodecCacheKey                                                                                                                1.48%
```

`contention-by-site`:

```
                                                                       Contention by Site
StackTrace                                                                                                                                Count    Avg.    Max.
----------------------------------------------------------------------------------------------------------------------------------------- ----- ------- -------
tools.jackson.databind.ser.SerializerCache.addAndResolveNonTypedSerializer(Class, JavaType, ValueSerializer, SerializationContext)            1  101 ms  101 ms
org.apache.kafka.common.requests.ApiVersionsRequest$Builder.build(short)                                                                      1 28.0 ms 28.0 ms
jdk.internal.loader.BuiltinClassLoader.loadClassOrNull(String, boolean)                                                                       1 25.9 ms 25.9 ms
sun.nio.ch.EPollSelectorImpl.clearInterrupt()                                                                                                15 14.2 ms 24.0 ms
tools.jackson.databind.ser.SerializerCache.addAndResolveNonTypedSerializer(Class, JavaType, ValueSerializer, SerializationContext)            1 22.3 ms 22.3 ms
tools.jackson.databind.ser.SerializerCache.addAndResolveNonTypedSerializer(Class, JavaType, ValueSerializer, SerializationContext)            1 19.1 ms 19.1 ms
me.ahoo.wow.runtime.wait.CommandResultsKt.reachedResult(Command, FunctionInfo, Hop, long)                                                     1 18.2 ms 18.2 ms
org.apache.kafka.clients.NetworkClient.parseResponse(ByteBuffer, RequestHeader)                                                               1 16.7 ms 16.7 ms
me.ahoo.wow.runtime.wait.Waiting.carry(CommandResult)                                                                                         4 12.8 ms 16.5 ms
jdk.internal.loader.BuiltinClassLoader.loadClassOrNull(String, boolean)                                                                       1 13.9 ms 13.9 ms
me.ahoo.wow.kernel.ProcessorContextsKt.contextOf(...)                                                                                         1 13.3 ms 13.3 ms
jdk.internal.loader.BuiltinClassLoader.loadClassOrNull(String, boolean)                                                                       1 13.3 ms 13.3 ms
me.ahoo.wow.runtime.subscription.SubscriptionWorker.drain(AggregateRef, SubscriptionWorker$Lane, Continuation)                                5 11.2 ms 13.0 ms
```

`gc-pauses`:

```
GC Pauses
---------
Total Pause Time: 5.65 s
Number of Pauses: 85
Minimum Pause Time: 0.0110 ms
Median Pause Time: 72.3 ms
Average Pause Time: 66.5 ms
P90 Pause Time: 97.9 ms
P95 Pause Time: 102 ms
P99 Pause Time: 124 ms
P99.9% Pause Time: 124 ms
Maximum Pause Time: 124 ms
```

`safepoints`:

```
                                                                            Safepoints
Start Time                  Duration                                     State Syncronization                  JNI Critical Threads                  Total Threads
--------------------------- --------------------------- ------------------------------------- ------------------------------------- ------------------------------
16:00:05                    Indefinite                                                    N/A                                     0                             11
16:00:05                    0.180 ms                                                      N/A                                     0                             11
16:00:05                    23.3 ms                                                       N/A                                     0                             12
16:00:06                    11.1 ms                                                       N/A                                     0                             12
16:00:06                    3.52 ms                                                       N/A                                     0                             12
16:00:06                    0.0998 ms                                                     N/A                                     0                             12
16:00:08                    38.6 ms                                                       N/A                                     0                             13
16:00:08                    16.7 ms                                                       N/A                                     0                             13
16:00:08                    18.6 ms                                                       N/A                                     0                             13
16:00:08                    7.74 ms                                                       N/A                                     0                             13
16:00:08                    0.103 ms                                                      N/A                                     0                             13
16:00:10                    37.1 ms                                                       N/A                                     0                             22
16:00:10                    38.6 ms                                                       N/A                                     0                             22
```

`latencies-by-type`:

```
                                                                       Latencies by Type
Event Type                                                                                                              Count Average    P 99 Longest     Total
--------------------------------------------------------------------------------------------------------------------- ------- ------- ------- ------- ---------
Java Thread Park                                                                                                      166,287 13.0 ms  102 ms 1 m 0 s  36 m 9 s
Socket Read                                                                                                            55,781 25.1 ms  104 ms  28.8 s 23 m 19 s
Java Monitor Wait                                                                                                          12  21.0 s 1 m 0 s 1 m 0 s  4 m 12 s
Java Thread Sleep                                                                                                          23  10.0 s  10.0 s  10.0 s  3 m 50 s
Socket Write                                                                                                            2,425 2.75 ms 12.3 ms  105 ms    6.67 s
Java Monitor Blocked                                                                                                       36 17.1 ms  101 ms  101 ms    615 ms
File Read                                                                                                                  10 3.90 ms 8.70 ms 8.70 ms   39.0 ms
File Write                                                                                                                  6 1.48 ms 1.82 ms 1.82 ms   8.85 ms
```

`socket-reads-by-host`:

```
                                                                      Socket Reads by Host
Host                                                                                                                                           Reads Total Read
--------------------------------------------------------------------------------------------------------------------------------------------- ------ ----------
localhost                                                                                                                                     55,767   874.1 kB
10.1.0.91                                                                                                                                         14     1.2 kB
```

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-a | a | 1855.7/s | 1855.7/s | 74227 | 0 | 120.3 ms | 413.7 ms | 536.6 ms | n/a | 712.7 ms | 0.72 cores | kafka 40% · mongo 99% | 0.32 of 1 | mongo 0.99 | 1.13 | 97.4 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 20 s | - |
| r1-b | b | 1746.5/s | 1746.5/s | 69860 | 0 | 67.6 ms | 248.8 ms | 329.7 ms | n/a | 426.0 ms | 0.70 cores | kafka 38% · mongo 97% | 0.31 of 1 | mongo 0.97 | 1.17 | 99.7 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 14 s | - |
| r2-b | b | 1746.5/s | 1746.5/s | 69860 | 0 | 67.6 ms | 301.1 ms | 456.7 ms | n/a | 614.4 ms | 0.70 cores | kafka 39% · mongo 97% | 0.32 of 1 | mongo 0.97 | 1.18 | 98.9 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 13 s | - |
| r2-a | a | 1855.7/s | 1855.7/s | 74227 | 0 | 96.8 ms | 325.6 ms | 432.1 ms | n/a | 610.3 ms | 0.70 cores | kafka 40% · mongo 99% | 0.33 of 1 | mongo 0.99 | 1.13 | 97.2 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 13 s | - |
| r3-a | a | 1855.7/s | 1855.7/s | 74227 | 0 | 77.3 ms | 260.1 ms | 360.4 ms | n/a | 548.9 ms | 0.70 cores | kafka 38% · mongo 98% | 0.32 of 1 | mongo 0.98 | 1.11 | 97.4 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 14 s | - |
| r3-b | b | 1746.5/s | 1746.5/s | 69860 | 0 | 59.1 ms | 280.6 ms | 450.6 ms | n/a | 589.8 ms | 0.69 cores | kafka 38% · mongo 97% | 0.31 of 1 | mongo 0.97 | 1.17 | 99.2 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 9 s | - |
| r4-b | b | 1746.5/s | 1746.5/s | 69860 | 0 | 48.6 ms | 205.8 ms | 327.7 ms | n/a | 442.4 ms | 0.69 cores | kafka 37% · mongo 97% | 0.31 of 1 | mongo 0.97 | 1.16 | 100.1 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 8 s | - |
| r4-a | a | 1855.7/s | 1855.7/s | 74227 | 0 | 89.6 ms | 383.0 ms | 581.6 ms | n/a | 766.0 ms | 0.69 cores | kafka 39% · mongo 98% | 0.32 of 1 | mongo 0.98 | 1.11 | 96.5 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 16 s | - |
| e1-a | a | 1746.5/s | 1746.5/s | 69860 | 0 | 55.0 ms | 195.6 ms | 290.8 ms | n/a | 473.1 ms | 0.68 cores | kafka 38% · mongo 96% | 0.32 of 1 | mongo 0.96 | 1.16 | 98.4 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 12 s | - |
| e1-b | b | 1746.5/s | 1746.5/s | 69860 | 0 | 84.5 ms | 335.9 ms | 598.0 ms | n/a | 782.3 ms | 0.70 cores | kafka 38% · mongo 98% | 0.31 of 1 | mongo 0.98 | 1.18 | 97.6 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 16 s | - |
| e2-b | b | 1746.5/s | 1746.5/s | 69860 | 0 | 60.4 ms | 274.4 ms | 532.5 ms | n/a | 790.5 ms | 0.68 cores | kafka 37% · mongo 97% | 0.31 of 1 | mongo 0.97 | 1.16 | 99.5 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 9 s | - |
| e2-a | a | 1746.5/s | 1746.5/s | 69860 | 0 | 66.6 ms | 257.0 ms | 366.6 ms | n/a | 452.6 ms | 0.71 cores | kafka 38% · mongo 97% | 0.32 of 1 | mongo 0.97 | 1.18 | 98.5 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 13 s | - |
| e3-a | a | 1746.5/s | 1746.5/s | 69860 | 0 | 64.8 ms | 234.5 ms | 311.3 ms | n/a | 387.1 ms | 0.69 cores | kafka 40% · mongo 97% | 0.31 of 1 | mongo 0.97 | 1.18 | 99.6 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 13 s | - |
| e3-b | b | 1746.5/s | 1746.5/s | 69860 | 0 | 54.8 ms | 225.3 ms | 327.7 ms | n/a | 403.5 ms | 0.68 cores | kafka 39% · mongo 97% | 0.31 of 1 | mongo 0.97 | 1.17 | 100.0 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 9 s | - |
| e4-b | b | 1746.5/s | 1746.5/s | 69860 | 0 | 64.0 ms | 266.2 ms | 360.4 ms | n/a | 479.2 ms | 0.69 cores | kafka 39% · mongo 97% | 0.31 of 1 | mongo 0.97 | 1.18 | 99.5 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 9 s | - |
| e4-a | a | 1746.5/s | 1746.5/s | 69860 | 0 | 54.5 ms | 238.6 ms | 378.9 ms | n/a | 569.3 ms | 0.70 cores | kafka 38% · mongo 97% | 0.32 of 1 | mongo 0.97 | 1.17 | 100.3 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 11 s | - |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput b/a | p50 b/a | p95 b/a | p99 b/a | p999 b/a | Worst-second p99 b/a | CPU ms/op b/a | Alloc/op b/a |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 0.941 | 0.562 | 0.601 | 0.615 | - | 0.598 | 1.038 | 1.024 |
| 2 | 0.941 | 0.698 | 0.925 | 1.057 | - | 1.007 | 1.045 | 1.018 |
| 3 | 0.941 | 0.765 | 1.079 | 1.250 | - | 1.075 | 1.050 | 1.018 |
| 4 | 0.941 | 0.543 | 0.537 | 0.563 | - | 0.578 | 1.049 | 1.037 |

Pairs at one rate for both arms, 1746.5/s (latencies compare; lower is better):

| Round | Throughput b/a | p50 b/a | p95 b/a | p99 b/a | p999 b/a | Worst-second p99 b/a | CPU ms/op b/a | Alloc/op b/a |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 1.535 | 1.717 | 2.056 | - | 1.654 | 1.014 | 0.992 |
| 2 | 1.000 | 0.908 | 1.068 | 1.453 | - | 1.747 | 0.979 | 1.011 |
| 3 | 1.000 | 0.846 | 0.961 | 1.053 | - | 1.042 | 0.993 | 1.004 |
| 4 | 1.000 | 1.174 | 1.116 | 0.951 | - | 0.842 | 1.006 | 0.991 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e1-mongo-uniform@100k | r1-a | steady | **unstable** | second half against first, beyond the default limits: p50 +86.8% (±25.0%) |
| e1-mongo-uniform@100k | r1-b | steady | **unstable** | second half against first, beyond the default limits: p50 +83.4% (±25.0%) |
| e1-mongo-uniform@100k | r2-b | steady | **unstable** | second half against first, beyond the default limits: p50 +61.9% (±25.0%) |
| e1-mongo-uniform@100k | r3-a | steady | **unstable** | second half against first, beyond the default limits: p50 +67.4% (±25.0%) |
| e1-mongo-uniform@100k | r3-b | steady | **unstable** | second half against first, beyond the default limits: p50 +48.7% (±25.0%) |
| e1-mongo-uniform@100k | r4-b | steady | **unstable** | second half against first, beyond the default limits: p50 +44.5% (±25.0%) |
| e1-mongo-uniform@100k | r4-a | steady | **unstable** | second half against first, beyond the default limits: p50 +53.2% (±25.0%) |
| e1-mongo-uniform@100k | e1-b | steady | **unstable** | second half against first, beyond the default limits: p50 -34.5% (±25.0%) |
| e1-mongo-uniform@100k | e2-b | steady | **unstable** | second half against first, beyond the default limits: p50 +29.8% (±25.0%) |
| e1-mongo-uniform@100k | e2-a | steady | **unstable** | second half against first, beyond the default limits: p50 +52.9% (±25.0%) |


