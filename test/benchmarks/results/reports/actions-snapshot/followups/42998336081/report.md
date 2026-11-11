# V10 end-to-end benchmark (D224) on GitHub Actions

Head: `main` 669861fb45a5444eb11e9eb1ad24198a3ad67bd6; V10 baseline: `v10.0.0-beta.1` 22f8ac19cb50b73d90613d7d401257600b3a914f; harness and load generator from head.

Tier: custom; coverage: e1-mongo-uniform@100k, e3-mongo@100k, e3-pg@100k.

Each suite ran on one runner of its own (a sessions suite of several sweeps, each sweep in fresh sessions on one of its own), so only its ratios compare across suites; absolute numbers are that runner's. Ratios are b/a per pair (head/base): median (min–max, n pairs). Lower is better except throughput.

## Anomalies

Checks: a partition paused (D243), a tail read the store after a gap or an overflow (D249), more than 1% errors, a measured second below half the rate, p99 above 10× p50 (when p99 ≥ 50 ms), a head allocating more than 5.0% more bytes per operation than its baseline. Errors, dips and tails are not checked where a run went past what the node keeps up with by design (probes, cliff steps that did not keep up).

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e1-mongo-uniform@100k | r1-base | steady | **unstable** | second half against first, beyond the A/A noise: p50 +47.2% (±19.9%) |
| e1-mongo-uniform@100k | r2-head | steady | **unstable** | second half against first, beyond the A/A noise: p50 -25.6% (±19.9%) |
| e1-mongo-uniform@100k | r3-base | steady | **unstable** | second half against first, beyond the A/A noise: p50 -26.2% (±19.9%) |
| e1-mongo-uniform@100k | r3-head | steady | **unstable** | second half against first, beyond the A/A noise: p50 +78.3% (±19.9%) |
| e1-mongo-uniform@100k | r4-base | steady | **unstable** | second half against first, beyond the A/A noise: p50 +47.7% (±19.9%) |
| e1-mongo-uniform@100k | r5-base | steady | **unstable** | second half against first, beyond the A/A noise: p50 -26.6% (±19.9%) |
| e1-mongo-uniform@100k | r5-head | steady | **unstable** | second half against first, beyond the A/A noise: p50 +20.6% (±19.9%) |
| e1-mongo-uniform@100k | e1-base | steady | **unstable** | second half against first, beyond the A/A noise: p50 -25.6% (±19.9%) |
| e1-mongo-uniform@100k | e1-base | steady | **error spike** | 3081 of 71684 failed: generator-overload 3081; generator-overload: 2000 in flight at most (per-target ceiling) |
| e1-mongo-uniform@100k | e1-head | steady | **unstable** | second half against first, beyond the A/A noise: p50 +62.9% (±19.9%) |
| e1-mongo-uniform@100k | e2-base | steady | **unstable** | second half against first, beyond the A/A noise: p50 +123.4% (±19.9%) |
| e3-mongo@100k | r1-base | steady | **unstable** | second half against first, beyond the A/A noise: p50 +90.2% (±18.4%) |
| e3-mongo@100k | r1-head | steady | **unstable** | second half against first, beyond the A/A noise: p50 +42.3% (±18.4%) |
| e3-mongo@100k | r2-head | steady | **unstable** | second half against first, beyond the A/A noise: p50 +44.4% (±18.4%) |
| e3-mongo@100k | r2-base | steady | **unstable** | second half against first, beyond the A/A noise: p50 +30.6% (±18.4%) |
| e3-mongo@100k | r3-head | steady | **unstable** | second half against first, beyond the A/A noise: p50 +120.9% (±18.4%) |
| e3-mongo@100k | r4-base | steady | **unstable** | second half against first, beyond the A/A noise: p50 +269.1% (±18.4%) |
| e3-mongo@100k | r5-head | steady | **unstable** | second half against first, beyond the A/A noise: p50 +32.3% (±18.4%) |
| e3-mongo@100k | e1-base | steady | **unstable** | second half against first, beyond the A/A noise: p50 +35.4% (±18.4%) |
| e3-mongo@100k | e1-head | steady | **unstable** | second half against first, beyond the A/A noise: p50 +22.3% (±18.4%) |
| e3-mongo@100k | e2-base | steady | **unstable** | second half against first, beyond the A/A noise: p50 -19.8% (±18.4%) |
| e3-mongo@100k | e3-base | steady | **unstable** | second half against first, beyond the A/A noise: p50 -25.3% (±18.4%) |
| e3-mongo@100k | e4-head | steady | **unstable** | second half against first, beyond the A/A noise: p50 +68.1% (±18.4%) |
| e3-mongo@100k | e4-base | steady | **unstable** | second half against first, beyond the A/A noise: p50 -32.7% (±18.4%) |
| e3-pg@100k | head against base | cost | **more allocation** | head allocates ×1.05 the bytes per operation of base (median of the equal-rate pairs) |
| e3-pg@100k | r1-base | steady | **p99 > 10× p50** | p50 21.5 ms, p99 434.2 ms |
| e3-pg@100k | r1-head | steady | **p99 > 10× p50** | p50 41.2 ms, p99 540.7 ms |
| e3-pg@100k | r2-head | steady | **unstable** | second half against first, beyond the A/A noise: p50 -39.3% (±37.3%) |
| e3-pg@100k | r2-base | steady | **p99 > 10× p50** | p50 15.7 ms, p99 157.7 ms |
| e3-pg@100k | r3-head | steady | **unstable** | second half against first, beyond the A/A noise: p50 -42.0% (±37.3%) |
| e3-pg@100k | r3-head | steady | **p99 > 10× p50** | p50 57.1 ms, p99 913.4 ms |
| e3-pg@100k | r4-head | steady | **p99 > 10× p50** | p50 102.9 ms, p99 1269.8 ms |
| e3-pg@100k | r5-base | steady | **unstable** | second half against first, beyond the A/A noise: p50 +51.1% (±37.3%) |
| e3-pg@100k | r5-head | steady | **unstable** | second half against first, beyond the A/A noise: p50 -39.7% (±37.3%) |
| e3-pg@100k | r5-head | steady | **p99 > 10× p50** | p50 30.6 ms, p99 397.3 ms |
| e3-pg@100k | e1-base | steady | **unstable** | second half against first, beyond the A/A noise: p50 +46.1% (±37.3%) |
| e3-pg@100k | e1-head | steady | **p99 > 10× p50** | p50 19.8 ms, p99 272.4 ms |
| e3-pg@100k | e3-base | steady | **unstable** | second half against first, beyond the A/A noise: p50 +44.3% (±37.3%) |
| e3-pg@100k | e3-head | steady | **unstable** | second half against first, beyond the A/A noise: p50 -40.3% (±37.3%) |
| e3-pg@100k | e3-head | steady | **p99 > 10× p50** | p50 35.1 ms, p99 378.9 ms |
| e3-pg@100k | e4-head | steady | **p99 > 10× p50** | p50 21.9 ms, p99 432.1 ms |

## Significance

Beyond noise: 3 of 9 ratios; 3 of 3 suites have an A/A noise threshold (test/benchmarks/noise.json). A ratio counts when its 95% bootstrap interval (over the measured seconds) clears the suite's threshold; without one it is `uncalibrated`.

| Suite | b/a | Metric | Rounds | Ratio [95% CI] | Noise | Verdict |
| --- | --- | --- | --- | --- | --- | --- |
| e1-mongo-uniform@100k | head/base | throughput | calibrated | 1.081 [1.079–1.083] | ±2.0% | **higher** |
| e1-mongo-uniform@100k | head/base | p50 | equal | 1.503 [1.047–1.756] | ±19.9% | within noise |
| e1-mongo-uniform@100k | head/base | p99 | equal | 1.096 [0.971–1.405] | ±12.1% | within noise |
| e3-mongo@100k | head/base | throughput | calibrated | 1.054 [1.054–1.055] | ±2.0% | **higher** |
| e3-mongo@100k | head/base | p50 | equal | 1.049 [0.801–1.255] | ±18.4% | within noise |
| e3-mongo@100k | head/base | p99 | equal | 1.176 [0.928–1.333] | ±9.1% | within noise |
| e3-pg@100k | head/base | throughput | calibrated | 1.122 [1.122–1.122] | ±2.0% | **higher** |
| e3-pg@100k | head/base | p50 | equal | 1.238 [1.045–1.671] | ±37.3% | within noise |
| e3-pg@100k | head/base | p99 | equal | 2.718 [1.245–1.759] | ±39.5% | within noise |

## Runners

| Suite | CPU | Cores | Memory | JDK | CPU layout |
| --- | --- | --- | --- | --- | --- |
| e1-mongo-uniform@100k | AMD EPYC 9V74 80-Core Processor | 4 | 16 GB | Eclipse Adoptium 25.0.5+8-LTS | generator 3 · node 0 · store 1 · Kafka 2 |
| e3-mongo@100k | AMD EPYC 9V74 80-Core Processor | 4 | 16 GB | Eclipse Adoptium 25.0.5+8-LTS | generator 3 · node 0 · store 1 · Kafka 2 |
| e3-pg@100k | AMD EPYC 7763 64-Core Processor | 4 | 16 GB | Eclipse Adoptium 25.0.5+8-LTS | generator 2-3 · node 0-1 |

## Saturation points (closed loop)

| Suite | Arm | Saturation | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- | --- |
| e1-mongo-uniform@100k | base | 2240.1/s | 2048 | 1792.1/s | 80% |
| e1-mongo-uniform@100k | head | 2426.8/s (probed again; first 670.9/s at concurrency 8) | 2048 | 1941.4/s | 80% |
| e3-mongo@100k | base | 482.0/s | 256 | 385.6/s | 80% |
| e3-mongo@100k | head | 508.1/s | 32 | 406.5/s | 80% |
| e3-pg@100k | base | 633.3/s | 128 | 380.0/s | 60% |
| e3-pg@100k | head | 710.5/s | 512 | 426.3/s | 60% |

## Paired ratios

`calibrated`: each arm at its own calibrated rate, so throughput and CPU per operation compare but the latencies are at **unequal loads**. `equal rate`: both arms at the lower of the two calibrated rates; read the latency ratios from these rows.

| Suite | b/a | Rounds | Saturation | Throughput | p50 | p95 | p99 | p999 | Worst-second p99 | CPU ms/op | Alloc/op |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| e1-mongo-uniform@100k | head/base | calibrated (unequal load) | 1.083 | 1.083 (1.077–1.084, n=5) | 1.750 (1.300–2.199, n=5) | 1.146 (1.040–1.813, n=5) | 1.067 (0.946–1.781, n=5) | - | 0.992 (0.915–1.982, n=5) | 0.946 (0.913–0.956, n=5) | 0.995 (0.971–1.004, n=5) |
| e1-mongo-uniform@100k | head/base | equal rate 1792.1/s | 1.083 | 0.998 (0.997–1.045, n=4) | 1.416 (0.519–2.773, n=4) | 0.963 (0.563–2.626, n=4) | 1.096 (0.557–2.758, n=4) | - | 1.463 (0.608–2.734, n=4) | 0.989 (0.949–1.022, n=4) | 1.016 (0.983–1.077, n=4) |
| e3-mongo@100k | head/base | calibrated (unequal load) | 1.054 | 1.054 (1.054–1.054, n=5) | 1.419 (0.682–2.129, n=5) | 1.371 (0.580–1.855, n=5) | 1.194 (0.708–1.825, n=5) | - | 1.287 (0.824–1.728, n=5) | 0.998 (0.920–1.012, n=5) | 1.007 (0.982–1.048, n=5) |
| e3-mongo@100k | head/base | equal rate 385.6/s | 1.054 | 1.000 (1.000–1.000, n=4) | 1.095 (0.803–1.159, n=4) | 1.209 (0.648–1.526, n=4) | 1.300 (0.574–1.757, n=4) | - | 1.288 (0.569–1.708, n=4) | 0.994 (0.936–1.007, n=4) | 1.029 (1.017–1.066, n=4) |
| e3-pg@100k | head/base | calibrated (unequal load) | 1.122 | 1.122 (1.122–1.122, n=5) | 2.057 (1.917–5.784, n=5) | 3.924 (1.316–13.771, n=5) | 3.804 (1.245–11.754, n=5) | - | 2.943 (1.204–8.372, n=5) | 0.906 (0.900–0.911, n=5) | 1.023 (1.022–1.042, n=5) |
| e3-pg@100k | head/base | equal rate 380.0/s | 1.122 | 1.000 (1.000–1.000, n=4) | 1.283 (1.148–1.957, n=4) | 3.126 (1.376–4.642, n=4) | 2.665 (1.060–4.088, n=4) | - | 1.868 (0.877–3.294, n=4) | 1.006 (0.997–1.012, n=4) | 1.052 (1.037–1.061, n=4) |

---

### e1-mongo-uniform@100k

E1 write on MongoDB, uniform keys: the head against the V10 baseline (regression guard), throughput and CPU at each calibrated rate, latency at one rate.

E1, uniform keys over 100000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 4 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 9V74 80-Core Processor, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (50–60 s), then waited until the nodes were quiet again (3–4 s).

- `head`: wow.access.capacity=1000000, wow.access.walk-rate=1

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 2240.1/s | 2048 | 1792.1/s | 80% |
| head | 2426.8/s (probed again; first 670.9/s at concurrency 8) | 2048 | 1941.4/s | 80% |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 1792.1/s | 1792.1/s | 35842 | 0 | 282.6 ms | 802.8 ms | 991.2 ms | n/a | 1097.7 ms | 0.76 cores | kafka 47% · mongo 97% | 0.56 of 1 | mongo 0.97 | 1.23 | 93.7 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 16 s | - |
| probe1-head | head | 1941.4/s | 1937.4/s | 38748 | 80 | 469.0 ms | 1028.1 ms | 1212.4 ms | n/a | 1335.3 ms | 0.76 cores | kafka 40% · mongo 96% | 0.64 of 1 | mongo 0.96 | 1.10 | 94.8 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 18 s | generator-overload 80 |

Profile of base (profile-base, not a timed round):

`hot-methods`:

```
                                                               Java Methods that Execute the Most
Method                                                                                                                                          Samples Percent
----------------------------------------------------------------------------------------------------------------------------------------------- ------- -------
java.util.HashMap.getNode(Object)                                                                                                                   367   4.19%
java.util.HashMap.putVal(int, Object, Object, boolean, boolean)                                                                                     212   2.42%
org.apache.kafka.clients.producer.KafkaProducer.send(ProducerRecord, Callback)                                                                      155   1.77%
com.mongodb.internal.connection.ByteBufferBsonOutput.writeOnArrayAscii(String, byte[], int, boolean)                                                126   1.44%
kotlin.coroutines.jvm.internal.ContinuationImpl.<init>(Continuation)                                                                                124   1.42%
kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith(Object)                                                                              118   1.35%
kotlin.coroutines.CombinedContext.get(CoroutineContext$Key)                                                                                          98   1.12%
java.util.regex.Pattern$BmpCharProperty.match(Matcher, int, CharSequence)                                                                            84   0.96%
java.util.regex.Pattern$BmpCharPropertyGreedy.match(Matcher, int, CharSequence)                                                                      81   0.93%
reactor.core.publisher.InnerOperator.currentContext()                                                                                                80   0.91%
java.util.Objects.equals(Object, Object)                                                                                                             79   0.90%
java.util.concurrent.ConcurrentHashMap.get(Object)                                                                                                   72   0.82%
org.bson.codecs.BsonDocumentCodec.encode(BsonWriter, BsonDocument, EncoderContext)                                                                   68   0.78%
```

`allocation-by-class`:

```
                                                                      Allocation by Class
Object Type                                                                                                                                 Allocation Pressure
------------------------------------------------------------------------------------------------------------------------------------------- -------------------
byte[]                                                                                                                                                    8.18%
java.lang.Object[]                                                                                                                                        7.10%
java.util.HashMap$Node[]                                                                                                                                  4.40%
java.util.LinkedHashMap$Entry                                                                                                                             4.02%
java.util.LinkedHashMap                                                                                                                                   3.56%
java.lang.String                                                                                                                                          2.58%
jdk.internal.vm.StackChunk                                                                                                                                2.12%
org.bson.internal.ChildCodecRegistry                                                                                                                      2.11%
java.util.ArrayList                                                                                                                                       2.08%
org.bson.BsonBinaryWriter$Context                                                                                                                         1.59%
org.bson.internal.CodecCache$CodecCacheKey                                                                                                                1.51%
org.bson.codecs.Codec[]                                                                                                                                   1.41%
org.bson.AbstractBsonWriter$State[]                                                                                                                       1.25%
```

`contention-by-site`:

```
                                                                       Contention by Site
StackTrace                                                                                                                                Count    Avg.    Max.
----------------------------------------------------------------------------------------------------------------------------------------- ----- ------- -------
tools.jackson.databind.ser.SerializerCache.addAndResolveNonTypedSerializer(Class, JavaType, ValueSerializer, SerializationContext)            1 93.1 ms 93.1 ms
jdk.internal.loader.AbstractClassLoaderValue$Memoizer.get()                                                                                   1 85.7 ms 85.7 ms
jdk.internal.loader.BuiltinClassLoader.loadClassOrNull(String, boolean)                                                                       1 56.0 ms 56.0 ms
me.ahoo.wow.runtime.wait.Waiting.carry(CommandResult)                                                                                         4 21.9 ms 55.1 ms
org.apache.kafka.common.requests.ApiVersionsRequest$Builder.build(short)                                                                      1 44.4 ms 44.4 ms
tools.jackson.databind.ser.SerializerCache.addAndResolveNonTypedSerializer(Class, JavaType, ValueSerializer, SerializationContext)            1 36.9 ms 36.9 ms
org.apache.kafka.clients.NetworkClient.parseResponse(ByteBuffer, RequestHeader)                                                               1 34.9 ms 34.9 ms
jdk.internal.loader.BuiltinClassLoader.loadClassOrNull(String, boolean)                                                                       1 22.8 ms 22.8 ms
sun.nio.ch.EPollSelectorImpl.clearInterrupt()                                                                                                21 13.5 ms 19.3 ms
jdk.internal.loader.BuiltinClassLoader.loadClassOrNull(String, boolean)                                                                       1 17.9 ms 17.9 ms
me.ahoo.wow.runtime.snapshot.SnapshotWrites.save(SnapshotRecord, Continuation)                                                                3 14.6 ms 17.3 ms
me.ahoo.wow.runtime.subscription.SubscriptionWorker.drain(AggregateRef, SubscriptionWorker$Lane, Continuation)                                4 13.2 ms 16.1 ms
jdk.internal.loader.BuiltinClassLoader.loadClassOrNull(String, boolean)                                                                       1 15.0 ms 15.0 ms
```

`gc-pauses`:

```
GC Pauses
---------
Total Pause Time: 5.27 s
Number of Pauses: 84
Minimum Pause Time: 0.0113 ms
Median Pause Time: 69.1 ms
Average Pause Time: 62.7 ms
P90 Pause Time: 87.0 ms
P95 Pause Time: 103 ms
P99 Pause Time: 133 ms
P99.9% Pause Time: 133 ms
Maximum Pause Time: 133 ms
```

`safepoints`:

```
                                                                            Safepoints
Start Time                  Duration                                     State Syncronization                  JNI Critical Threads                  Total Threads
--------------------------- --------------------------- ------------------------------------- ------------------------------------- ------------------------------
11:52:20                    Indefinite                                                    N/A                                     0                             11
11:52:20                    0.244 ms                                                      N/A                                     0                             11
11:52:21                    26.8 ms                                                       N/A                                     0                             12
11:52:22                    14.4 ms                                                       N/A                                     0                             12
11:52:22                    3.31 ms                                                       N/A                                     0                             12
11:52:22                    0.0928 ms                                                     N/A                                     0                             12
11:52:24                    22.0 ms                                                       N/A                                     0                             13
11:52:24                    21.1 ms                                                       N/A                                     0                             13
11:52:24                    12.6 ms                                                       N/A                                     0                             13
11:52:24                    6.63 ms                                                       N/A                                     0                             13
11:52:24                    0.0342 ms                                                     N/A                                     0                             13
11:52:26                    49.6 ms                                                       N/A                                     0                             24
11:52:26                    38.9 ms                                                       N/A                                     0                             24
```

`latencies-by-type`:

```
                                                                       Latencies by Type
Event Type                                                                                                              Count Average    P 99 Longest     Total
--------------------------------------------------------------------------------------------------------------------- ------- ------- ------- ------- ---------
Java Thread Park                                                                                                      164,424 16.3 ms  127 ms 1 m 0 s 44 m 43 s
Socket Read                                                                                                            55,702 32.5 ms  158 ms  28.4 s  30 m 9 s
Java Thread Sleep                                                                                                          28  10.0 s  10.1 s  10.1 s  4 m 40 s
Java Monitor Wait                                                                                                          10  25.2 s 1 m 0 s 1 m 0 s  4 m 12 s
Socket Write                                                                                                            3,153 2.80 ms 12.5 ms 92.6 ms    8.84 s
Java Monitor Blocked                                                                                                       51 19.6 ms 93.1 ms 93.1 ms    1.00 s
File Write                                                                                                                 10 13.1 ms 84.1 ms 84.1 ms    131 ms
File Read                                                                                                                   7 3.61 ms 7.64 ms 7.64 ms   25.3 ms
```

`socket-reads-by-host`:

```
                                                                      Socket Reads by Host
Host                                                                                                                                           Reads Total Read
--------------------------------------------------------------------------------------------------------------------------------------------- ------ ----------
localhost                                                                                                                                     55,685   878.1 kB
10.1.1.48                                                                                                                                         17     1.4 kB
```

Profile of head (profile-head, not a timed round):

`hot-methods`:

```
                                                               Java Methods that Execute the Most
Method                                                                                                                                          Samples Percent
----------------------------------------------------------------------------------------------------------------------------------------------- ------- -------
java.util.HashMap.getNode(Object)                                                                                                                   470   5.20%
java.util.HashMap.putVal(int, Object, Object, boolean, boolean)                                                                                     209   2.31%
org.apache.kafka.clients.producer.KafkaProducer.send(ProducerRecord, Callback)                                                                      144   1.59%
com.mongodb.internal.connection.ByteBufferBsonOutput.writeOnArrayAscii(String, byte[], int, boolean)                                                128   1.42%
kotlin.coroutines.CombinedContext.get(CoroutineContext$Key)                                                                                         121   1.34%
kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith(Object)                                                                              121   1.34%
kotlin.coroutines.jvm.internal.ContinuationImpl.<init>(Continuation)                                                                                113   1.25%
java.util.Objects.equals(Object, Object)                                                                                                            110   1.22%
java.util.concurrent.ConcurrentHashMap.get(Object)                                                                                                   88   0.97%
java.util.regex.Pattern$BmpCharProperty.match(Matcher, int, CharSequence)                                                                            83   0.92%
reactor.core.publisher.InnerOperator.currentContext()                                                                                                82   0.91%
java.util.regex.Pattern$BmpCharPropertyGreedy.match(Matcher, int, CharSequence)                                                                      78   0.86%
java.lang.String.charAt(int)                                                                                                                         76   0.84%
```

`allocation-by-class`:

```
                                                                      Allocation by Class
Object Type                                                                                                                                 Allocation Pressure
------------------------------------------------------------------------------------------------------------------------------------------- -------------------
byte[]                                                                                                                                                    7.73%
java.lang.Object[]                                                                                                                                        6.76%
java.util.LinkedHashMap$Entry                                                                                                                             4.37%
java.util.HashMap$Node[]                                                                                                                                  4.22%
java.util.LinkedHashMap                                                                                                                                   3.31%
java.lang.String                                                                                                                                          2.47%
java.util.ArrayList                                                                                                                                       2.10%
jdk.internal.vm.StackChunk                                                                                                                                2.10%
org.bson.internal.ChildCodecRegistry                                                                                                                      1.88%
org.bson.codecs.Codec[]                                                                                                                                   1.55%
kotlin.coroutines.CombinedContext                                                                                                                         1.50%
org.bson.internal.CodecCache$CodecCacheKey                                                                                                                1.41%
org.bson.BsonBinaryWriter$Context                                                                                                                         1.35%
```

`contention-by-site`:

```
                                                                       Contention by Site
StackTrace                                                                                                                                Count    Avg.    Max.
----------------------------------------------------------------------------------------------------------------------------------------- ----- ------- -------
tools.jackson.databind.ser.SerializerCache.addAndResolveNonTypedSerializer(Class, JavaType, ValueSerializer, SerializationContext)            1  104 ms  104 ms
sun.nio.ch.EPollSelectorImpl.clearInterrupt()                                                                                                18 16.4 ms 86.2 ms
org.apache.kafka.clients.producer.internals.RecordAccumulator.partitionReady(...)                                                             4 26.5 ms 72.8 ms
tools.jackson.databind.ser.SerializerCache.addAndResolveNonTypedSerializer(Class, JavaType, ValueSerializer, SerializationContext)            1 45.4 ms 45.4 ms
org.apache.kafka.clients.NetworkClient.parseResponse(ByteBuffer, RequestHeader)                                                               1 36.8 ms 36.8 ms
tools.jackson.databind.ser.SerializerCache.addAndResolveNonTypedSerializer(Class, JavaType, ValueSerializer, SerializationContext)            1 33.0 ms 33.0 ms
jdk.internal.loader.BuiltinClassLoader.loadClassOrNull(String, boolean)                                                                       1 29.0 ms 29.0 ms
org.apache.kafka.common.requests.ApiVersionsRequest$Builder.build(short)                                                                      1 26.1 ms 26.1 ms
jdk.internal.loader.BuiltinClassLoader.loadClassOrNull(String, boolean)                                                                       1 22.3 ms 22.3 ms
me.ahoo.wow.runtime.wait.Waiting.carry(CommandResult)                                                                                         7 14.1 ms 16.4 ms
java.util.concurrent.ConcurrentHashMap.computeIfAbsent(Object, Function)                                                                      1 13.9 ms 13.9 ms
io.netty.util.concurrent.DefaultPromise.checkNotifyWaiters()                                                                                  1 12.9 ms 12.9 ms
me.ahoo.wow.runtime.subscription.SubscriptionWorker.drain(AggregateRef, SubscriptionWorker$Lane, Continuation)                                3 11.7 ms 12.8 ms
```

`gc-pauses`:

```
GC Pauses
---------
Total Pause Time: 5.85 s
Number of Pauses: 85
Minimum Pause Time: 0.0133 ms
Median Pause Time: 70.0 ms
Average Pause Time: 68.8 ms
P90 Pause Time: 109 ms
P95 Pause Time: 121 ms
P99 Pause Time: 159 ms
P99.9% Pause Time: 159 ms
Maximum Pause Time: 159 ms
```

`safepoints`:

```
                                                                            Safepoints
Start Time                  Duration                                     State Syncronization                  JNI Critical Threads                  Total Threads
--------------------------- --------------------------- ------------------------------------- ------------------------------------- ------------------------------
11:57:27                    Indefinite                                                    N/A                                     0                             11
11:57:27                    0.192 ms                                                      N/A                                     0                             11
11:57:28                    16.2 ms                                                       N/A                                     0                             12
11:57:28                    10.5 ms                                                       N/A                                     0                             12
11:57:28                    9.05 ms                                                       N/A                                     0                             12
11:57:28                    3.92 ms                                                       N/A                                     0                             12
11:57:31                    37.8 ms                                                       N/A                                     0                             13
11:57:31                    11.5 ms                                                       N/A                                     0                             13
11:57:31                    13.7 ms                                                       N/A                                     0                             13
11:57:31                    7.23 ms                                                       N/A                                     0                             13
11:57:31                    0.0990 ms                                                     N/A                                     0                             13
11:57:33                    57.8 ms                                                       N/A                                     0                             22
11:57:33                    9.68 ms                                                       N/A                                     0                             22
```

`latencies-by-type`:

```
                                                                       Latencies by Type
Event Type                                                                                                             Count Average    P 99  Longest     Total
-------------------------------------------------------------------------------------------------------------------- ------- ------- ------- -------- ---------
Java Thread Park                                                                                                     158,624 20.8 ms  168 ms 4 m 55 s  55 m 0 s
Socket Read                                                                                                           58,272 30.6 ms  198 ms   28.6 s 29 m 44 s
Java Monitor Wait                                                                                                         12  26.0 s 1 m 0 s  1 m 0 s  5 m 12 s
Java Thread Sleep                                                                                                         30  10.0 s  10.0 s   10.0 s   5 m 0 s
Socket Write                                                                                                           2,948 2.78 ms 11.6 ms   118 ms    8.20 s
Java Monitor Blocked                                                                                                      45 20.1 ms  104 ms   104 ms    905 ms
File Write                                                                                                                17 14.0 ms  117 ms   117 ms    239 ms
File Read                                                                                                                 13 3.09 ms 6.02 ms  6.02 ms   40.1 ms
```

`socket-reads-by-host`:

```
                                                                      Socket Reads by Host
Host                                                                                                                                           Reads Total Read
--------------------------------------------------------------------------------------------------------------------------------------------- ------ ----------
localhost                                                                                                                                     58,253   924.1 kB
10.1.1.48                                                                                                                                         19     1.8 kB
```

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 1792.1/s | 1792.1/s | 71684 | 0 | 286.7 ms | 671.7 ms | 901.1 ms | n/a | 1081.3 ms | 0.70 cores | kafka 44% · mongo 97% | 0.42 of 1 | mongo 0.97 | 1.18 | 93.5 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 16 s | - |
| r1-head | head | 1941.4/s | 1941.4/s | 77656 | 0 | 372.7 ms | 766.0 ms | 913.4 ms | n/a | 1073.2 ms | 0.72 cores | kafka 43% · mongo 100% | 0.45 of 1 | mongo 1.00 | 1.11 | 93.8 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 20 s | - |
| r2-head | head | 1941.4/s | 1929.9/s | 77195 | 461 | 362.5 ms | 995.3 ms | 1335.3 ms | n/a | 1851.4 ms | 0.70 cores | kafka 40% · mongo 96% | 0.45 of 1 | mongo 0.96 | 1.07 | 92.6 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 19 s | generator-overload 461 |
| r2-base | base | 1792.1/s | 1792.1/s | 71684 | 0 | 164.9 ms | 548.9 ms | 749.6 ms | n/a | 933.9 ms | 0.69 cores | kafka 43% · mongo 97% | 0.42 of 1 | mongo 0.97 | 1.17 | 95.4 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 10 s | - |
| r3-base | base | 1792.1/s | 1792.1/s | 71684 | 0 | 212.0 ms | 618.5 ms | 999.4 ms | n/a | 1318.9 ms | 0.69 cores | kafka 43% · mongo 95% | 0.42 of 1 | mongo 0.95 | 1.15 | 94.9 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 18 s | - |
| r3-head | head | 1941.4/s | 1934.8/s | 77392 | 264 | 376.8 ms | 925.7 ms | 1187.8 ms | n/a | 1630.2 ms | 0.72 cores | kafka 44% · mongo 97% | 0.45 of 1 | mongo 0.97 | 1.10 | 93.7 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 22 s | generator-overload 264 |
| r4-head | head | 1941.4/s | 1941.4/s | 77656 | 0 | 376.8 ms | 835.6 ms | 1048.6 ms | n/a | 1286.1 ms | 0.73 cores | kafka 40% · mongo 98% | 0.43 of 1 | mongo 0.98 | 1.09 | 93.8 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 16 s | - |
| r4-base | base | 1792.1/s | 1791.3/s | 71653 | 31 | 268.3 ms | 729.1 ms | 983.0 ms | n/a | 1400.8 ms | 0.67 cores | kafka 44% · mongo 93% | 0.43 of 1 | mongo 0.93 | 1.14 | 94.3 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 11 s | generator-overload 31 |
| r5-base | base | 1792.1/s | 1792.1/s | 71684 | 0 | 213.0 ms | 725.0 ms | 978.9 ms | n/a | 1253.4 ms | 0.67 cores | kafka 41% · mongo 94% | 0.42 of 1 | mongo 0.94 | 1.13 | 94.6 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 17 s | - |
| r5-head | head | 1941.4/s | 1941.4/s | 77656 | 0 | 372.7 ms | 753.7 ms | 925.7 ms | n/a | 1146.9 ms | 0.70 cores | kafka 41% · mongo 97% | 0.45 of 1 | mongo 0.97 | 1.07 | 95.0 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 10 s | - |
| e1-base | base | 1792.1/s | 1715.1/s | 68603 | 3081 | 540.7 ms | 1294.3 ms | 1572.9 ms | n/a | 1859.6 ms | 0.61 cores | kafka 42% · mongo 86% | 0.41 of 1 | mongo 0.86 | 1.10 | 88.7 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 24 s | generator-overload 3081 |
| e1-head | head | 1792.1/s | 1792.1/s | 71684 | 0 | 280.6 ms | 729.1 ms | 876.5 ms | n/a | 1130.5 ms | 0.68 cores | kafka 40% · mongo 94% | 0.42 of 1 | mongo 0.94 | 1.13 | 95.6 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 17 s | - |
| e2-head | head | 1792.1/s | 1792.1/s | 71684 | 0 | 230.4 ms | 606.2 ms | 888.8 ms | n/a | 1163.3 ms | 0.68 cores | kafka 40% · mongo 95% | 0.42 of 1 | mongo 0.95 | 1.13 | 94.6 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 18 s | - |
| e2-base | base | 1792.1/s | 1792.1/s | 71684 | 0 | 223.2 ms | 749.6 ms | 905.2 ms | n/a | 1097.7 ms | 0.67 cores | kafka 41% · mongo 94% | 0.41 of 1 | mongo 0.94 | 1.13 | 92.8 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 22 s | - |
| e3-base | base | 1792.1/s | 1792.1/s | 71684 | 0 | 128.5 ms | 333.8 ms | 448.5 ms | n/a | 569.3 ms | 0.69 cores | kafka 41% · mongo 97% | 0.42 of 1 | mongo 0.97 | 1.15 | 94.9 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 11 s | - |
| e3-head | head | 1792.1/s | 1786.1/s | 71445 | 239 | 356.4 ms | 876.5 ms | 1237.0 ms | n/a | 1556.5 ms | 0.65 cores | kafka 40% · mongo 91% | 0.42 of 1 | mongo 0.91 | 1.09 | 93.3 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 19 s | generator-overload 239 |
| e4-head | head | 1792.1/s | 1786.3/s | 71451 | 233 | 331.8 ms | 782.3 ms | 1081.3 ms | n/a | 1941.5 ms | 0.66 cores | kafka 44% · mongo 92% | 0.43 of 1 | mongo 0.92 | 1.13 | 94.5 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 19 s | generator-overload 233 |
| e4-base | base | 1792.1/s | 1792.1/s | 71684 | 0 | 184.3 ms | 700.4 ms | 892.9 ms | n/a | 1040.4 ms | 0.68 cores | kafka 45% · mongo 96% | 0.42 of 1 | mongo 0.96 | 1.16 | 93.4 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 17 s | - |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.083 | 1.300 | 1.140 | 1.014 | - | 0.992 | 0.939 | 1.004 |
| 2 | 1.077 | 2.199 | 1.813 | 1.781 | - | 1.982 | 0.913 | 0.971 |
| 3 | 1.080 | 1.778 | 1.497 | 1.189 | - | 1.236 | 0.956 | 0.988 |
| 4 | 1.084 | 1.405 | 1.146 | 1.067 | - | 0.918 | 0.956 | 0.995 |
| 5 | 1.083 | 1.750 | 1.040 | 0.946 | - | 0.915 | 0.946 | 1.004 |

Pairs at one rate for both arms, 1792.1/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.045 | 0.519 | 0.563 | 0.557 | - | 0.608 | 1.022 | 1.077 |
| 2 | 1.000 | 1.032 | 0.809 | 0.982 | - | 1.060 | 1.003 | 1.019 |
| 3 | 0.997 | 2.773 | 2.626 | 2.758 | - | 2.734 | 0.949 | 0.983 |
| 4 | 0.997 | 1.800 | 1.117 | 1.211 | - | 1.866 | 0.976 | 1.012 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e1-mongo-uniform@100k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 +47.2% (±25.0%) |
| e1-mongo-uniform@100k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -25.6% (±25.0%) |
| e1-mongo-uniform@100k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -26.2% (±25.0%) |
| e1-mongo-uniform@100k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 +78.3% (±25.0%) |
| e1-mongo-uniform@100k | r4-base | steady | **unstable** | second half against first, beyond the default limits: p50 +47.7% (±25.0%) |
| e1-mongo-uniform@100k | r5-base | steady | **unstable** | second half against first, beyond the default limits: p50 -26.6% (±25.0%) |
| e1-mongo-uniform@100k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -25.6% (±25.0%) |
| e1-mongo-uniform@100k | e1-base | steady | **error spike** | 3081 of 71684 failed: generator-overload 3081; generator-overload: 2000 in flight at most (per-target ceiling) |
| e1-mongo-uniform@100k | e1-head | steady | **unstable** | second half against first, beyond the default limits: p50 +62.9% (±25.0%) |
| e1-mongo-uniform@100k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 +123.4% (±25.0%) |


### e3-mongo@100k

E3 saga chain on MongoDB (completion: the target's Entry stored): head against baseline.

E3, uniform keys over 100000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 4 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 9V74 80-Core Processor, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (53–66 s), then waited until the nodes were quiet again (3–4 s).

- `head`: wow.access.capacity=1000000, wow.access.walk-rate=1

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 482.0/s | 256 | 385.6/s | 80% |
| head | 508.1/s | 32 | 406.5/s | 80% |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 385.6/s | 385.6/s | 7712 | 0 | 160.8 ms | 675.8 ms | 921.6 ms | n/a | 1097.7 ms | 0.55 cores | kafka 47% · mongo 87% | 0.32 of 1 | mongo 0.87 | 4.88 | 264.6 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 6 s | - |
| probe1-head | head | 406.5/s | 406.5/s | 8130 | 0 | 104.4 ms | 393.2 ms | 495.6 ms | n/a | 618.5 ms | 0.55 cores | kafka 47% · mongo 86% | 0.31 of 1 | mongo 0.86 | 4.63 | 283.2 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 3 s | - |

Profile of base (profile-base, not a timed round):

`hot-methods`:

```
                                                               Java Methods that Execute the Most
Method                                                                                                                                          Samples Percent
----------------------------------------------------------------------------------------------------------------------------------------------- ------- -------
java.util.HashMap.getNode(Object)                                                                                                                   322   4.12%
java.util.concurrent.ConcurrentHashMap.get(Object)                                                                                                  158   2.02%
java.util.HashMap.putVal(int, Object, Object, boolean, boolean)                                                                                     143   1.83%
org.apache.kafka.clients.producer.KafkaProducer.send(ProducerRecord, Callback)                                                                      126   1.61%
com.mongodb.internal.connection.ByteBufferBsonOutput.writeOnArrayAscii(String, byte[], int, boolean)                                                115   1.47%
kotlin.coroutines.jvm.internal.ContinuationImpl.<init>(Continuation)                                                                                103   1.32%
kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith(Object)                                                                               98   1.25%
java.util.Objects.equals(Object, Object)                                                                                                             79   1.01%
kotlin.coroutines.CombinedContext.get(CoroutineContext$Key)                                                                                          77   0.98%
org.bson.codecs.BsonDocumentCodec.encode(BsonWriter, BsonDocument, EncoderContext)                                                                   72   0.92%
java.util.regex.Pattern$BmpCharProperty.match(Matcher, int, CharSequence)                                                                            69   0.88%
kotlin.jvm.internal.Intrinsics.throwParameterIsNullNPE(String)                                                                                       66   0.84%
java.util.regex.Pattern$BmpCharPropertyGreedy.match(Matcher, int, CharSequence)                                                                      64   0.82%
```

`allocation-by-class`:

```
                                                                      Allocation by Class
Object Type                                                                                                                                 Allocation Pressure
------------------------------------------------------------------------------------------------------------------------------------------- -------------------
byte[]                                                                                                                                                    7.72%
java.lang.Object[]                                                                                                                                        7.19%
java.util.LinkedHashMap$Entry                                                                                                                             4.49%
java.util.HashMap$Node[]                                                                                                                                  4.37%
java.util.LinkedHashMap                                                                                                                                   3.56%
java.lang.String                                                                                                                                          2.56%
jdk.internal.vm.StackChunk                                                                                                                                2.32%
java.util.ArrayList                                                                                                                                       2.20%
org.bson.internal.ChildCodecRegistry                                                                                                                      2.07%
org.bson.BsonBinaryWriter$Context                                                                                                                         1.69%
org.bson.codecs.Codec[]                                                                                                                                   1.60%
org.bson.internal.CodecCache$CodecCacheKey                                                                                                                1.60%
org.bson.AbstractBsonWriter$State[]                                                                                                                       1.37%
```

`contention-by-site`:

```
                                                                       Contention by Site
StackTrace                                                                                                                                Count    Avg.    Max.
----------------------------------------------------------------------------------------------------------------------------------------- ----- ------- -------
tools.jackson.databind.ser.SerializerCache.addAndResolveNonTypedSerializer(Class, JavaType, ValueSerializer, SerializationContext)            1  100 ms  100 ms
tools.jackson.databind.ser.SerializerCache.addAndResolveNonTypedSerializer(Class, JavaType, ValueSerializer, SerializationContext)            1 72.7 ms 72.7 ms
org.apache.kafka.clients.NetworkClient.parseResponse(ByteBuffer, RequestHeader)                                                               1 54.9 ms 54.9 ms
org.apache.kafka.common.requests.ApiVersionsRequest$Builder.build(short)                                                                      1 40.5 ms 40.5 ms
tools.jackson.databind.ser.SerializerCache.addAndResolveNonTypedSerializer(Class, JavaType, ValueSerializer, SerializationContext)            1 33.3 ms 33.3 ms
sun.nio.ch.EPollSelectorImpl.clearInterrupt()                                                                                                16 12.9 ms 22.0 ms
jdk.internal.loader.BuiltinClassLoader.loadClassOrNull(String, boolean)                                                                       1 20.0 ms 20.0 ms
me.ahoo.wow.runtime.wait.Waiting.carry(CommandResult)                                                                                         7 12.3 ms 17.2 ms
jdk.internal.loader.BuiltinClassLoader.loadClassOrNull(String, boolean)                                                                       2 14.8 ms 16.6 ms
me.ahoo.wow.runtime.subscription.SubscriptionWorker.runBatch(SubscriptionWorker$Item$Live, SubscriptionWorker$Lane, Continuation)             3 13.9 ms 15.3 ms
jdk.internal.loader.BuiltinClassLoader.loadClassOrNull(String, boolean)                                                                       1 14.9 ms 14.9 ms
me.ahoo.wow.runtime.wait.WaitState.offer(CommandResult)                                                                                       1 14.5 ms 14.5 ms
jdk.internal.loader.BuiltinClassLoader.loadClassOrNull(String, boolean)                                                                       1 14.0 ms 14.0 ms
```

`gc-pauses`:

```
GC Pauses
---------
Total Pause Time: 4.29 s
Number of Pauses: 76
Minimum Pause Time: 0.0156 ms
Median Pause Time: 59.4 ms
Average Pause Time: 56.4 ms
P90 Pause Time: 82.9 ms
P95 Pause Time: 99.4 ms
P99 Pause Time: 167 ms
P99.9% Pause Time: 167 ms
Maximum Pause Time: 167 ms
```

`safepoints`:

```
                                                                            Safepoints
Start Time                  Duration                                     State Syncronization                  JNI Critical Threads                  Total Threads
--------------------------- --------------------------- ------------------------------------- ------------------------------------- ------------------------------
11:49:33                    Indefinite                                                    N/A                                     0                             11
11:49:33                    0.121 ms                                                      N/A                                     0                             11
11:49:34                    12.4 ms                                                       N/A                                     0                             12
11:49:35                    15.3 ms                                                       N/A                                     0                             12
11:49:35                    3.52 ms                                                       N/A                                     0                             12
11:49:35                    0.0986 ms                                                     N/A                                     0                             12
11:49:37                    41.6 ms                                                       N/A                                     0                             13
11:49:37                    23.4 ms                                                       N/A                                     0                             13
11:49:37                    0.00566 ms                                                    N/A                                     0                             13
11:49:37                    18.6 ms                                                       N/A                                     0                             13
11:49:37                    6.37 ms                                                       N/A                                     0                             13
11:49:37                    0.0994 ms                                                     N/A                                     0                             13
11:49:39                    50.8 ms                                                       N/A                                     0                             24
```

`latencies-by-type`:

```
                                                                       Latencies by Type
Event Type                                                                                                             Count Average    P 99  Longest     Total
-------------------------------------------------------------------------------------------------------------------- ------- ------- ------- -------- ---------
Java Thread Park                                                                                                     196,038 15.9 ms  119 ms 4 m 42 s 51 m 50 s
Socket Read                                                                                                           61,238 28.5 ms  160 ms   28.0 s  29 m 4 s
Java Thread Sleep                                                                                                         29  10.0 s  10.0 s   10.0 s  4 m 50 s
Java Monitor Wait                                                                                                         10  25.2 s 1 m 0 s  1 m 0 s  4 m 12 s
Socket Write                                                                                                           2,582 3.00 ms 12.9 ms  69.4 ms    7.76 s
Java Monitor Blocked                                                                                                      44 18.4 ms  100 ms   100 ms    811 ms
File Read                                                                                                                 14 3.09 ms 6.02 ms  6.02 ms   43.3 ms
File Write                                                                                                                 7 2.17 ms 3.13 ms  3.13 ms   15.2 ms
```

`socket-reads-by-host`:

```
                                                                      Socket Reads by Host
Host                                                                                                                                           Reads Total Read
--------------------------------------------------------------------------------------------------------------------------------------------- ------ ----------
localhost                                                                                                                                     61,219   961.2 kB
10.1.0.64                                                                                                                                         19     1.8 kB
```

Profile of head (profile-head, not a timed round):

`hot-methods`:

```
                                                               Java Methods that Execute the Most
Method                                                                                                                                          Samples Percent
----------------------------------------------------------------------------------------------------------------------------------------------- ------- -------
java.util.HashMap.getNode(Object)                                                                                                                   279   3.14%
java.util.HashMap.putVal(int, Object, Object, boolean, boolean)                                                                                     174   1.96%
org.apache.kafka.clients.producer.KafkaProducer.send(ProducerRecord, Callback)                                                                      145   1.63%
java.util.concurrent.ConcurrentHashMap.get(Object)                                                                                                  117   1.32%
java.util.Objects.equals(Object, Object)                                                                                                            116   1.30%
kotlin.coroutines.jvm.internal.ContinuationImpl.<init>(Continuation)                                                                                114   1.28%
kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith(Object)                                                                              100   1.12%
java.nio.ByteBuffer.position(int)                                                                                                                    87   0.98%
kotlin.coroutines.CombinedContext.get(CoroutineContext$Key)                                                                                          83   0.93%
java.util.regex.Pattern$BmpCharProperty.match(Matcher, int, CharSequence)                                                                            82   0.92%
com.mongodb.internal.connection.ByteBufferBsonOutput.writeOnArrayAscii(String, byte[], int, boolean)                                                 81   0.91%
java.util.regex.Pattern$BmpCharPropertyGreedy.match(Matcher, int, CharSequence)                                                                      77   0.87%
kotlinx.coroutines.JobSupport.removeNode$kotlinx_coroutines_core(JobNode)                                                                            69   0.78%
```

`allocation-by-class`:

```
                                                                      Allocation by Class
Object Type                                                                                                                                 Allocation Pressure
------------------------------------------------------------------------------------------------------------------------------------------- -------------------
byte[]                                                                                                                                                    7.71%
java.lang.Object[]                                                                                                                                        7.11%
java.util.HashMap$Node[]                                                                                                                                  4.30%
java.util.LinkedHashMap$Entry                                                                                                                             4.27%
java.util.LinkedHashMap                                                                                                                                   3.66%
java.lang.String                                                                                                                                          2.37%
jdk.internal.vm.StackChunk                                                                                                                                2.32%
java.util.ArrayList                                                                                                                                       2.22%
org.bson.internal.ChildCodecRegistry                                                                                                                      2.11%
org.bson.codecs.Codec[]                                                                                                                                   1.77%
org.bson.internal.CodecCache$CodecCacheKey                                                                                                                1.61%
org.bson.BsonBinaryWriter$Context                                                                                                                         1.44%
kotlin.coroutines.CombinedContext                                                                                                                         1.40%
```

`contention-by-site`:

```
                                                                       Contention by Site
StackTrace                                                                                                                                Count    Avg.    Max.
----------------------------------------------------------------------------------------------------------------------------------------- ----- ------- -------
tools.jackson.databind.ser.SerializerCache.addAndResolveNonTypedSerializer(Class, JavaType, ValueSerializer, SerializationContext)            1  133 ms  133 ms
sun.nio.ch.EPollSelectorImpl.clearInterrupt()                                                                                                30 15.1 ms 70.0 ms
tools.jackson.databind.ser.SerializerCache.addAndResolveNonTypedSerializer(Class, JavaType, ValueSerializer, SerializationContext)            1 45.4 ms 45.4 ms
org.apache.kafka.clients.NetworkClient.parseResponse(ByteBuffer, RequestHeader)                                                               1 39.7 ms 39.7 ms
tools.jackson.databind.ser.SerializerCache.addAndResolveNonTypedSerializer(Class, JavaType, ValueSerializer, SerializationContext)            1 39.1 ms 39.1 ms
org.apache.kafka.common.requests.ApiVersionsRequest$Builder.build(short)                                                                      1 26.1 ms 26.1 ms
jdk.internal.loader.BuiltinClassLoader.loadClassOrNull(String, boolean)                                                                       1 21.4 ms 21.4 ms
org.apache.kafka.clients.producer.internals.RecordAccumulator.partitionReady(...)                                                             3 15.8 ms 19.9 ms
me.ahoo.wow.runtime.wait.Waiting.carry(CommandResult)                                                                                         6 13.8 ms 19.3 ms
kotlinx.coroutines.EventLoopImplBase$DelayedTask.scheduleTask(long, EventLoopImplBase$DelayedTaskQueue, EventLoopImplBase)                    1 15.3 ms 15.3 ms
me.ahoo.wow.runtime.subscription.SubscriptionWorker$Ops.settle(List, boolean, SubscriptionWorker$Lane, List)                                  5 12.9 ms 15.2 ms
org.apache.kafka.clients.Metadata.timeToNextUpdate(long)                                                                                      1 14.8 ms 14.8 ms
io.netty.util.concurrent.DefaultPromise.checkNotifyWaiters()                                                                                  1 14.0 ms 14.0 ms
```

`gc-pauses`:

```
GC Pauses
---------
Total Pause Time: 5.26 s
Number of Pauses: 79
Minimum Pause Time: 0.0142 ms
Median Pause Time: 69.4 ms
Average Pause Time: 66.6 ms
P90 Pause Time: 101 ms
P95 Pause Time: 112 ms
P99 Pause Time: 169 ms
P99.9% Pause Time: 169 ms
Maximum Pause Time: 169 ms
```

`safepoints`:

```
                                                                            Safepoints
Start Time                  Duration                                     State Syncronization                  JNI Critical Threads                  Total Threads
--------------------------- --------------------------- ------------------------------------- ------------------------------------- ------------------------------
11:54:48                    Indefinite                                                    N/A                                     0                             11
11:54:48                    0.159 ms                                                      N/A                                     0                             11
11:54:49                    15.9 ms                                                       N/A                                     0                             12
11:54:49                    10.9 ms                                                       N/A                                     0                             12
11:54:49                    4.35 ms                                                       N/A                                     0                             12
11:54:49                    0.101 ms                                                      N/A                                     0                             12
11:54:52                    43.7 ms                                                       N/A                                     0                             13
11:54:52                    11.8 ms                                                       N/A                                     0                             13
11:54:52                    19.0 ms                                                       N/A                                     0                             13
11:54:52                    5.73 ms                                                       N/A                                     0                             13
11:54:52                    0.0946 ms                                                     N/A                                     0                             13
11:54:54                    53.2 ms                                                       N/A                                     0                             22
11:54:54                    45.2 ms                                                       N/A                                     0                             22
```

`latencies-by-type`:

```
                                                                       Latencies by Type
Event Type                                                                                                              Count Average    P 99 Longest     Total
--------------------------------------------------------------------------------------------------------------------- ------- ------- ------- ------- ---------
Java Thread Park                                                                                                      196,408 17.3 ms  130 ms 5 m 4 s 56 m 46 s
Socket Read                                                                                                            65,082 31.4 ms  186 ms  28.0 s  34 m 1 s
Java Monitor Wait                                                                                                          11  28.3 s 1 m 0 s 1 m 0 s  5 m 12 s
Java Thread Sleep                                                                                                          31  10.0 s  10.0 s  10.0 s  5 m 10 s
Socket Write                                                                                                            3,157 2.93 ms 13.8 ms 89.5 ms    9.26 s
Java Monitor Blocked                                                                                                       65 17.6 ms  133 ms  133 ms    1.14 s
File Read                                                                                                                  11 4.08 ms 8.22 ms 8.22 ms   44.9 ms
File Write                                                                                                                  8 2.82 ms 10.3 ms 10.3 ms   22.6 ms
```

`socket-reads-by-host`:

```
                                                                      Socket Reads by Host
Host                                                                                                                                           Reads Total Read
--------------------------------------------------------------------------------------------------------------------------------------------- ------ ----------
localhost                                                                                                                                     65,063  1023.7 kB
10.1.0.64                                                                                                                                         19     1.8 kB
```

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 385.6/s | 385.6/s | 15423 | 0 | 143.4 ms | 536.6 ms | 712.7 ms | n/a | 872.4 ms | 0.49 cores | kafka 41% · mongo 85% | 0.13 of 1 | mongo 0.85 | 4.55 | 266.5 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 17 s | - |
| r1-head | head | 406.5/s | 406.5/s | 16260 | 0 | 217.1 ms | 995.3 ms | 1286.1 ms | n/a | 1507.3 ms | 0.49 cores | kafka 40% · mongo 81% | 0.14 of 1 | mongo 0.81 | 4.18 | 261.7 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 13 s | - |
| r2-head | head | 406.5/s | 406.5/s | 16260 | 0 | 109.6 ms | 610.3 ms | 938.0 ms | n/a | 1056.8 ms | 0.52 cores | kafka 40% · mongo 88% | 0.16 of 1 | mongo 0.88 | 4.44 | 276.8 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 9 s | - |
| r2-base | base | 385.6/s | 385.6/s | 15423 | 0 | 110.6 ms | 329.7 ms | 514.0 ms | n/a | 766.0 ms | 0.48 cores | kafka 41% · mongo 82% | 0.13 of 1 | mongo 0.82 | 4.44 | 268.6 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 9 s | - |
| r3-base | base | 385.6/s | 385.6/s | 15423 | 0 | 102.9 ms | 436.2 ms | 634.9 ms | n/a | 798.7 ms | 0.47 cores | kafka 40% · mongo 85% | 0.13 of 1 | mongo 0.85 | 4.46 | 268.0 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 12 s | - |
| r3-head | head | 406.5/s | 406.5/s | 16260 | 0 | 219.1 ms | 598.0 ms | 757.8 ms | n/a | 1028.1 ms | 0.49 cores | kafka 40% · mongo 83% | 0.16 of 1 | mongo 0.83 | 4.22 | 267.5 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 17 s | - |
| r4-head | head | 406.5/s | 406.5/s | 16260 | 0 | 107.5 ms | 385.0 ms | 614.4 ms | n/a | 843.8 ms | 0.51 cores | kafka 43% · mongo 88% | 0.14 of 1 | mongo 0.88 | 4.47 | 279.1 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 6 s | - |
| r4-base | base | 385.6/s | 385.6/s | 15423 | 0 | 157.7 ms | 663.6 ms | 868.4 ms | n/a | 1024.0 ms | 0.48 cores | kafka 39% · mongo 83% | 0.15 of 1 | mongo 0.83 | 4.42 | 266.3 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 19 s | - |
| r5-base | base | 385.6/s | 385.6/s | 15423 | 0 | 103.9 ms | 374.8 ms | 581.6 ms | n/a | 897.0 ms | 0.51 cores | kafka 42% · mongo 87% | 0.15 of 1 | mongo 0.87 | 4.66 | 270.2 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 11 s | - |
| r5-head | head | 406.5/s | 406.5/s | 16260 | 0 | 147.5 ms | 458.8 ms | 684.0 ms | n/a | 946.2 ms | 0.57 cores | kafka 42% · mongo 90% | 0.16 of 1 | mongo 0.90 | 4.65 | 272.1 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 9 s | - |
| e1-base | base | 385.6/s | 385.6/s | 15423 | 0 | 92.2 ms | 436.2 ms | 634.9 ms | n/a | 860.2 ms | 0.50 cores | kafka 40% · mongo 87% | 0.13 of 1 | mongo 0.87 | 4.58 | 275.5 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 11 s | - |
| e1-head | head | 385.6/s | 385.6/s | 15423 | 0 | 101.9 ms | 282.6 ms | 364.5 ms | n/a | 489.5 ms | 0.51 cores | kafka 40% · mongo 86% | 0.14 of 1 | mongo 0.86 | 4.60 | 280.2 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 7 s | - |
| e2-head | head | 385.6/s | 385.6/s | 15423 | 0 | 97.8 ms | 434.2 ms | 753.7 ms | n/a | 958.5 ms | 0.48 cores | kafka 42% · mongo 87% | 0.14 of 1 | mongo 0.87 | 4.57 | 283.7 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 11 s | - |
| e2-base | base | 385.6/s | 385.6/s | 15423 | 0 | 90.1 ms | 333.8 ms | 532.5 ms | n/a | 839.7 ms | 0.55 cores | kafka 44% · mongo 90% | 0.15 of 1 | mongo 0.90 | 4.88 | 274.6 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 4 s | - |
| e3-base | base | 385.6/s | 385.6/s | 15423 | 0 | 88.6 ms | 299.0 ms | 421.9 ms | n/a | 528.4 ms | 0.50 cores | kafka 40% · mongo 88% | 0.15 of 1 | mongo 0.88 | 4.62 | 275.0 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 6 s | - |
| e3-head | head | 385.6/s | 385.6/s | 15423 | 0 | 71.2 ms | 333.8 ms | 499.7 ms | n/a | 757.8 ms | 0.48 cores | kafka 40% · mongo 86% | 0.13 of 1 | mongo 0.86 | 4.54 | 293.1 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 3 s | - |
| e4-head | head | 385.6/s | 385.6/s | 15423 | 0 | 89.6 ms | 389.1 ms | 622.6 ms | n/a | 766.0 ms | 0.48 cores | kafka 40% · mongo 85% | 0.14 of 1 | mongo 0.85 | 4.47 | 286.7 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 5 s | - |
| e4-base | base | 385.6/s | 385.6/s | 15423 | 0 | 77.3 ms | 255.0 ms | 354.3 ms | n/a | 448.5 ms | 0.45 cores | kafka 42% · mongo 84% | 0.14 of 1 | mongo 0.84 | 4.44 | 279.6 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 5 s | - |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.054 | 1.514 | 1.855 | 1.805 | - | 1.728 | 0.920 | 0.982 |
| 2 | 1.054 | 0.991 | 1.851 | 1.825 | - | 1.380 | 1.000 | 1.031 |
| 3 | 1.054 | 2.129 | 1.371 | 1.194 | - | 1.287 | 0.945 | 0.998 |
| 4 | 1.054 | 0.682 | 0.580 | 0.708 | - | 0.824 | 1.012 | 1.048 |
| 5 | 1.054 | 1.419 | 1.224 | 1.176 | - | 1.055 | 0.998 | 1.007 |

Pairs at one rate for both arms, 385.6/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 1.106 | 0.648 | 0.574 | - | 0.569 | 1.004 | 1.017 |
| 2 | 1.000 | 1.085 | 1.301 | 1.415 | - | 1.141 | 0.936 | 1.033 |
| 3 | 1.000 | 0.803 | 1.116 | 1.184 | - | 1.434 | 0.983 | 1.066 |
| 4 | 1.000 | 1.159 | 1.526 | 1.757 | - | 1.708 | 1.007 | 1.026 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e3-mongo@100k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 +90.2% (±25.0%) |
| e3-mongo@100k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 +42.3% (±25.0%) |
| e3-mongo@100k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 +44.4% (±25.0%) |
| e3-mongo@100k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 +30.6% (±25.0%) |
| e3-mongo@100k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 +120.9% (±25.0%) |
| e3-mongo@100k | r4-base | steady | **unstable** | second half against first, beyond the default limits: p50 +269.1% (±25.0%) |
| e3-mongo@100k | r5-head | steady | **unstable** | second half against first, beyond the default limits: p50 +32.3% (±25.0%) |
| e3-mongo@100k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 +35.4% (±25.0%) |
| e3-mongo@100k | e3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -25.3% (±25.0%) |
| e3-mongo@100k | e4-head | steady | **unstable** | second half against first, beyond the default limits: p50 +68.1% (±25.0%) |
| e3-mongo@100k | e4-base | steady | **unstable** | second half against first, beyond the default limits: p50 -32.7% (±25.0%) |


### e3-pg@100k

E3 saga chain on PostgreSQL: head against baseline.

E3, uniform keys over 100000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 4 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 7763 64-Core Processor, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (67–81 s), then waited until the nodes were quiet again (3–4 s).

- `head`: wow.access.capacity=1000000, wow.access.walk-rate=1

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 633.3/s | 128 | 380.0/s | 60% |
| head | 710.5/s | 512 | 426.3/s | 60% |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 506.6/s | 501.1/s | 10022 | 110 | 2023.4 ms | 4030.5 ms | 4554.8 ms | n/a | 4882.4 ms | 0.96 cores | kafka 45% · postgres 59% | 0.38 of 2 | server 0.96 | 3.98 | 251.4 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 15 s | generator-overload 110 |
| probe2-base | base | 380.0/s | 380.0/s | 7600 | 0 | 124.4 ms | 1048.6 ms | 1228.8 ms | n/a | 1392.6 ms | 0.83 cores | kafka 53% · postgres 63% | 0.38 of 2 | server 0.83 | 5.24 | 243.0 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 6 s | - |
| probe1-head | head | 568.4/s | 522.8/s | 10455 | 913 | 3047.4 ms | 4522.0 ms | 5013.5 ms | n/a | 5537.8 ms | 1.11 cores | kafka 45% · postgres 44% | 0.37 of 2 | server 1.11 | 3.82 | 231.5 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 18 s | generator-overload 913 |
| probe2-head | head | 426.3/s | 426.3/s | 8525 | 0 | 157.7 ms | 440.3 ms | 639.0 ms | n/a | 753.7 ms | 0.88 cores | kafka 43% · postgres 66% | 0.37 of 2 | server 0.88 | 4.63 | 256.8 KB | - | datasets 8 of 8 · default 5 of 5, lowered 0, pressed 1 s | - |

Profile of base (profile-base, not a timed round):

`hot-methods`:

```
                                                               Java Methods that Execute the Most
Method                                                                                                                                          Samples Percent
----------------------------------------------------------------------------------------------------------------------------------------------- ------- -------
java.util.HashMap.getNode(Object)                                                                                                                   462   3.18%
kotlin.coroutines.jvm.internal.ContinuationImpl.<init>(Continuation)                                                                                335   2.31%
kotlin.jvm.internal.Intrinsics.throwParameterIsNullNPE(String)                                                                                      231   1.59%
org.apache.kafka.clients.producer.KafkaProducer.send(ProducerRecord, Callback)                                                                      187   1.29%
java.util.regex.Pattern$BmpCharProperty.match(Matcher, int, CharSequence)                                                                           186   1.28%
kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith(Object)                                                                              177   1.22%
kotlin.coroutines.CombinedContext.get(CoroutineContext$Key)                                                                                         175   1.21%
java.util.HashMap.putVal(int, Object, Object, boolean, boolean)                                                                                     165   1.14%
tools.jackson.databind.ser.BeanPropertyWriter.serializeAsProperty(Object, JsonGenerator, SerializationContext)                                      153   1.05%
java.util.regex.Pattern$BmpCharPropertyGreedy.match(Matcher, int, CharSequence)                                                                     137   0.94%
reactor.core.publisher.InnerOperator.currentContext()                                                                                               129   0.89%
java.lang.invoke.Invokers$Holder.invokeExact_MT(Object, Object, Object)                                                                             129   0.89%
java.lang.Class.cast(Object)                                                                                                                        125   0.86%
```

`allocation-by-class`:

```
                                                                      Allocation by Class
Object Type                                                                                                                                 Allocation Pressure
------------------------------------------------------------------------------------------------------------------------------------------- -------------------
byte[]                                                                                                                                                   13.83%
java.lang.Object[]                                                                                                                                        5.33%
java.lang.String                                                                                                                                          2.72%
jdk.internal.vm.StackChunk                                                                                                                                2.53%
java.util.ArrayList                                                                                                                                       2.47%
int[]                                                                                                                                                     2.36%
java.util.HashMap$Node[]                                                                                                                                  1.50%
java.util.ArrayList$Itr                                                                                                                                   1.46%
tools.jackson.core.json.ReaderBasedJsonParser                                                                                                             1.35%
tools.jackson.core.json.JsonWriteContext                                                                                                                  1.24%
java.util.LinkedHashMap$Entry                                                                                                                             1.23%
org.apache.kafka.common.TopicPartition                                                                                                                    1.21%
kotlin.coroutines.CombinedContext                                                                                                                         1.20%
```

`contention-by-site`:

```
                                                                       Contention by Site
StackTrace                                                                                                                                Count    Avg.    Max.
----------------------------------------------------------------------------------------------------------------------------------------- ----- ------- -------
tools.jackson.databind.ser.SerializerCache.addAndResolveNonTypedSerializer(Class, JavaType, ValueSerializer, SerializationContext)            1  133 ms  133 ms
sun.nio.ch.EPollSelectorImpl.clearInterrupt()                                                                                                33 19.9 ms  117 ms
tools.jackson.databind.ser.SerializerCache.addAndResolveNonTypedSerializer(Class, JavaType, ValueSerializer, SerializationContext)            1 97.1 ms 97.1 ms
org.apache.kafka.clients.producer.internals.RecordAccumulator.partitionReady(...)                                                             2 28.5 ms 45.2 ms
tools.jackson.databind.ser.SerializerCache.addAndResolveNonTypedSerializer(Class, JavaType, ValueSerializer, SerializationContext)            1 40.7 ms 40.7 ms
jdk.internal.loader.BuiltinClassLoader.loadClassOrNull(String, boolean)                                                                       1 40.0 ms 40.0 ms
tools.jackson.core.json.JsonFactory._createGenerator(ObjectWriteContext, IOContext, Writer)                                                   1 36.9 ms 36.9 ms
jdk.internal.loader.BuiltinClassLoader.loadClassOrNull(String, boolean)                                                                       1 32.9 ms 32.9 ms
org.apache.kafka.common.requests.ApiVersionsRequest$Builder.build(short)                                                                      1 32.2 ms 32.2 ms
org.apache.kafka.clients.NetworkClient.parseResponse(ByteBuffer, RequestHeader)                                                               1 29.5 ms 29.5 ms
jdk.internal.loader.BuiltinClassLoader.loadClassOrNull(String, boolean)                                                                       1 20.8 ms 20.8 ms
jdk.internal.loader.BuiltinClassLoader.loadClassOrNull(String, boolean)                                                                       1 18.6 ms 18.6 ms
me.ahoo.wow.runtime.wait.Waiting.carry(CommandResult)                                                                                         5 14.7 ms 17.7 ms
```

`gc-pauses`:

```
GC Pauses
---------
Total Pause Time: 3.30 s
Number of Pauses: 60
Minimum Pause Time: 0.0124 ms
Median Pause Time: 46.5 ms
Average Pause Time: 55.0 ms
P90 Pause Time: 110 ms
P95 Pause Time: 121 ms
P99 Pause Time: 148 ms
P99.9% Pause Time: 148 ms
Maximum Pause Time: 148 ms
```

`safepoints`:

```
                                                                            Safepoints
Start Time                  Duration                                     State Syncronization                  JNI Critical Threads                  Total Threads
--------------------------- --------------------------- ------------------------------------- ------------------------------------- ------------------------------
12:11:21                    Indefinite                                                    N/A                                     0                             11
12:11:21                    0.148 ms                                                      N/A                                     0                             11
12:11:22                    10.1 ms                                                       N/A                                     0                             12
12:11:22                    8.44 ms                                                       N/A                                     0                             12
12:11:22                    3.91 ms                                                       N/A                                     0                             12
12:11:22                    0.116 ms                                                      N/A                                     0                             12
12:11:25                    48.8 ms                                                       N/A                                     0                             13
12:11:25                    33.1 ms                                                       N/A                                     0                             13
12:11:25                    41.0 ms                                                       N/A                                     0                             13
12:11:25                    11.1 ms                                                       N/A                                     0                             13
12:11:25                    0.120 ms                                                      N/A                                     0                             13
12:11:27                    40.3 ms                                                       N/A                                     0                             24
12:11:27                    5.72 ms                                                       N/A                                     0                             24
```

`latencies-by-type`:

```
                                                                       Latencies by Type
Event Type                                                                                                              Count Average    P 99 Longest     Total
--------------------------------------------------------------------------------------------------------------------- ------- ------- ------- ------- ---------
Java Thread Park                                                                                                      293,462 11.7 ms 88.3 ms 1 m 0 s  57 m 2 s
Java Monitor Wait                                                                                                          18  37.3 s 1 m 0 s 1 m 0 s 11 m 12 s
Socket Read                                                                                                            79,878 5.53 ms 42.3 ms  9.41 s  7 m 22 s
Socket Write                                                                                                            6,145 2.55 ms 10.6 ms 23.1 ms    15.7 s
Java Monitor Blocked                                                                                                       64 22.6 ms  133 ms  133 ms    1.44 s
Java Thread Sleep                                                                                                           9 30.4 ms 31.8 ms 31.8 ms    274 ms
File Write                                                                                                                 30 2.95 ms 13.7 ms 13.7 ms   88.6 ms
File Read                                                                                                                  13 2.33 ms 3.92 ms 3.92 ms   30.3 ms
```

`socket-reads-by-host`:

```
                                                                      Socket Reads by Host
Host                                                                                                                                           Reads Total Read
--------------------------------------------------------------------------------------------------------------------------------------------- ------ ----------
localhost                                                                                                                                     79,862    12.3 MB
10.1.0.129                                                                                                                                        16     1.8 kB
```

`postgres-stats`:

```
# Top statements by total execution time (pg_stat_statements, since the run's warm-up began)
 calls | total_ms | mean_ms | max_ms | rows  |                                                                    query                                                                     
-------+----------+---------+--------+-------+----------------------------------------------------------------------------------------------------------------------------------------------
 24491 |   4449.7 |   0.182 |    6.6 | 24491 | INSERT INTO "wow_snapshot__transfer__account" AS stored (tenant_id, aggregate_id, version, schema_fingerprint, state, owner_id, space_id, de
    63 |   4394.8 |  69.759 |  147.7 | 32256 | INSERT INTO wow_checkpoint AS c (subscription_group, subscription, partition, position) VALUES ($1, $2, $3, $4), ($5, $6, $7, $8), ($9, $10,
 21097 |   3926.3 |   0.186 |    5.4 | 21097 | WITH current AS ( SELECT coalesce(max(version), 0) AS version FROM "wow_log__transfer__account" WHERE aggregate_id = $1 AND tenant_id = $2 )
  5229 |   1789.4 |   0.342 |    7.0 | 10458 | INSERT INTO "wow_snapshot__transfer__account" AS stored (tenant_id, aggregate_id, version, schema_fingerprint, state, owner_id, space_id, de
  2963 |   1784.7 |   0.602 |    9.4 |  5926 | WITH input (ord, branch, part, tenant_id, aggregate_id, version, command_id, request_id, command_type, commit_time, events, owner_id, space_
  1749 |   1400.3 |   0.801 |   17.5 |  5247 | WITH input (ord, branch, part, tenant_id, aggregate_id, version, command_id, request_id, command_type, commit_time, events, owner_id, space_
  2516 |   1184.4 |   0.471 |    7.6 |  7548 | INSERT INTO "wow_snapshot__transfer__account" AS stored (tenant_id, aggregate_id, version, schema_fingerprint, state, owner_id, space_id, de
  1216 |   1096.3 |   0.902 |    8.0 |  4864 | WITH input (ord, branch, part, tenant_id, aggregate_id, version, command_id, request_id, command_type, commit_time, events, owner_id, space_
   787 |    898.7 |   1.142 |   12.4 |  3935 | WITH input (ord, branch, part, tenant_id, aggregate_id, version, command_id, request_id, command_type, commit_time, events, owner_id, space_
  1390 |    809.3 |   0.582 |    6.6 |  5560 | INSERT INTO "wow_snapshot__transfer__account" AS stored (tenant_id, aggregate_id, version, schema_fingerprint, state, owner_id, space_id, de
   546 |    778.4 |   1.426 |   10.8 |  3276 | WITH input (ord, branch, part, tenant_id, aggregate_id, version, command_id, request_id, command_type, commit_time, events, owner_id, space_
   428 |    770.8 |   1.801 |   15.0 |  2996 | WITH input (ord, branch, part, tenant_id, aggregate_id, version, command_id, request_id, command_type, commit_time, events, owner_id, space_
   866 |    635.2 |   0.734 |    7.2 |  4330 | INSERT INTO "wow_snapshot__transfer__account" AS stored (tenant_id, aggregate_id, version, schema_fingerprint, state, owner_id, space_id, de
   291 |    592.1 |   2.035 |   16.6 |  2328 | WITH input (ord, branch, part, tenant_id, aggregate_id, version, command_id, request_id, command_type, commit_time, events, owner_id, space_
   204 |    461.8 |   2.264 |   13.6 |  1836 | WITH input (ord, branch, part, tenant_id, aggregate_id, version, command_id, request_id, command_type, commit_time, events, owner_id, space_
(15 rows)

# Checkpoints (pg_stat_checkpointer)
 num_timed | num_requested | restartpoints_timed | restartpoints_req | restartpoints_done | write_time | sync_time | buffers_written |          stats_reset          
-----------+---------------+---------------------+-------------------+--------------------+------------+-----------+-----------------+-------------------------------
         0 |             1 |                   0 |                 0 |                  0 |         20 |         2 |             923 | 2026-11-11 12:11:13.428674+00
(1 row)

# Waits now (pg_stat_activity)
 wait_event_type |     wait_event      | count 
-----------------+---------------------+-------
 Client          | ClientRead          |    11
 Activity        | WalWriterMain       |     1
 Activity        | LogicalLauncherMain |     1
                 |                     |     1
 Activity        | CheckpointerMain    |     1
 Activity        | BgwriterMain        |     1
 Activity        | AutovacuumMain      |     1
(7 rows)


```

Profile of head (profile-head, not a timed round):

`hot-methods`:

```
                                                               Java Methods that Execute the Most
Method                                                                                                                                          Samples Percent
----------------------------------------------------------------------------------------------------------------------------------------------- ------- -------
java.util.HashMap.getNode(Object)                                                                                                                   342   2.29%
kotlin.coroutines.jvm.internal.ContinuationImpl.<init>(Continuation)                                                                                339   2.27%
kotlin.jvm.internal.Intrinsics.throwParameterIsNullNPE(String)                                                                                      265   1.78%
java.util.HashMap.putVal(int, Object, Object, boolean, boolean)                                                                                     192   1.29%
java.lang.invoke.Invokers$Holder.invokeExact_MT(Object, Object, Object)                                                                             187   1.25%
java.util.regex.Pattern$BmpCharPropertyGreedy.match(Matcher, int, CharSequence)                                                                     172   1.15%
java.util.regex.Pattern$BmpCharProperty.match(Matcher, int, CharSequence)                                                                           165   1.11%
kotlin.coroutines.CombinedContext.get(CoroutineContext$Key)                                                                                         164   1.10%
kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith(Object)                                                                              158   1.06%
org.apache.kafka.clients.producer.KafkaProducer.send(ProducerRecord, Callback)                                                                      149   1.00%
kotlin.jvm.internal.Intrinsics.areEqual(Object, Object)                                                                                             146   0.98%
reactor.core.publisher.InnerOperator.currentContext()                                                                                               143   0.96%
java.util.concurrent.ConcurrentHashMap.get(Object)                                                                                                  137   0.92%
```

`allocation-by-class`:

```
                                                                      Allocation by Class
Object Type                                                                                                                                 Allocation Pressure
------------------------------------------------------------------------------------------------------------------------------------------- -------------------
byte[]                                                                                                                                                   14.31%
java.lang.Object[]                                                                                                                                        5.12%
java.lang.String                                                                                                                                          2.66%
java.util.ArrayList                                                                                                                                       2.42%
int[]                                                                                                                                                     2.36%
jdk.internal.vm.StackChunk                                                                                                                                2.33%
kotlin.coroutines.CombinedContext                                                                                                                         1.81%
java.util.HashMap$Node[]                                                                                                                                  1.69%
java.util.LinkedHashMap                                                                                                                                   1.51%
java.util.LinkedHashMap$Entry                                                                                                                             1.40%
tools.jackson.core.json.ReaderBasedJsonParser                                                                                                             1.36%
org.apache.kafka.common.TopicPartition                                                                                                                    1.24%
java.util.ArrayList$Itr                                                                                                                                   1.22%
```

`contention-by-site`:

```
                                                                       Contention by Site
StackTrace                                                                                                                                Count    Avg.    Max.
----------------------------------------------------------------------------------------------------------------------------------------- ----- ------- -------
tools.jackson.databind.ser.SerializerCache.addAndResolveNonTypedSerializer(Class, JavaType, ValueSerializer, SerializationContext)            1  150 ms  150 ms
org.apache.kafka.clients.Metadata.timeToNextUpdate(long)                                                                                      1  103 ms  103 ms
sun.nio.ch.EPollSelectorImpl.clearInterrupt()                                                                                                39 15.0 ms 58.7 ms
org.apache.kafka.clients.producer.internals.RecordAccumulator.partitionReady(...)                                                             3 31.3 ms 57.1 ms
tools.jackson.databind.ser.SerializerCache.addAndResolveNonTypedSerializer(Class, JavaType, ValueSerializer, SerializationContext)            1 54.7 ms 54.7 ms
tools.jackson.databind.ser.SerializerCache.addAndResolveNonTypedSerializer(Class, JavaType, ValueSerializer, SerializationContext)            1 52.7 ms 52.7 ms
org.apache.kafka.common.requests.ApiVersionsRequest$Builder.build(short)                                                                      1 37.5 ms 37.5 ms
org.apache.kafka.clients.NetworkClient.parseResponse(ByteBuffer, RequestHeader)                                                               1 33.8 ms 33.8 ms
jdk.internal.loader.BuiltinClassLoader.loadClassOrNull(String, boolean)                                                                       1 27.3 ms 27.3 ms
jdk.internal.loader.BuiltinClassLoader.loadClassOrNull(String, boolean)                                                                       1 26.6 ms 26.6 ms
jdk.internal.loader.BuiltinClassLoader.loadClassOrNull(String, boolean)                                                                       1 24.9 ms 24.9 ms
jdk.internal.loader.BuiltinClassLoader.loadClassOrNull(String, boolean)                                                                       1 17.3 ms 17.3 ms
me.ahoo.wow.runtime.subscription.SubscriptionWorker.drain(AggregateRef, SubscriptionWorker$Lane, Continuation)                                2 14.2 ms 17.1 ms
```

`gc-pauses`:

```
GC Pauses
---------
Total Pause Time: 4.20 s
Number of Pauses: 62
Minimum Pause Time: 0.0200 ms
Median Pause Time: 56.8 ms
Average Pause Time: 67.8 ms
P90 Pause Time: 118 ms
P95 Pause Time: 154 ms
P99 Pause Time: 273 ms
P99.9% Pause Time: 273 ms
Maximum Pause Time: 273 ms
```

`safepoints`:

```
                                                                            Safepoints
Start Time                  Duration                                     State Syncronization                  JNI Critical Threads                  Total Threads
--------------------------- --------------------------- ------------------------------------- ------------------------------------- ------------------------------
12:17:13                    Indefinite                                                    N/A                                     0                             11
12:17:13                    0.164 ms                                                      N/A                                     0                             11
12:17:14                    11.2 ms                                                       N/A                                     0                             12
12:17:14                    14.0 ms                                                       N/A                                     0                             12
12:17:14                    2.77 ms                                                       N/A                                     0                             12
12:17:14                    0.123 ms                                                      N/A                                     0                             12
12:17:17                    53.3 ms                                                       N/A                                     0                             13
12:17:17                    32.5 ms                                                       N/A                                     0                             13
12:17:17                    58.0 ms                                                       N/A                                     0                             13
12:17:17                    9.85 ms                                                       N/A                                     0                             13
12:17:17                    0.312 ms                                                      N/A                                     0                             13
12:17:19                    41.4 ms                                                       N/A                                     0                             24
12:17:20                    6.38 ms                                                       N/A                                     0                             24
```

`latencies-by-type`:

```
                                                                       Latencies by Type
Event Type                                                                                                              Count Average    P 99 Longest     Total
--------------------------------------------------------------------------------------------------------------------- ------- ------- ------- ------- ---------
Java Thread Park                                                                                                      283,112 12.1 ms  101 ms 1 m 0 s  57 m 8 s
Java Monitor Wait                                                                                                          18  37.3 s 1 m 0 s 1 m 0 s 11 m 12 s
Socket Read                                                                                                            79,549 5.67 ms 44.7 ms  15.4 s  7 m 31 s
Socket Write                                                                                                            6,456 2.68 ms 11.1 ms  144 ms    17.3 s
Java Monitor Blocked                                                                                                       65 21.4 ms  150 ms  150 ms    1.39 s
Java Thread Sleep                                                                                                           9 31.1 ms 32.8 ms 32.8 ms    279 ms
File Write                                                                                                                 35 3.17 ms 15.8 ms 15.8 ms    111 ms
File Read                                                                                                                   6 3.88 ms 8.03 ms 8.03 ms   23.3 ms
```

`socket-reads-by-host`:

```
                                                                      Socket Reads by Host
Host                                                                                                                                           Reads Total Read
--------------------------------------------------------------------------------------------------------------------------------------------- ------ ----------
localhost                                                                                                                                     79,537    12.9 MB
10.1.0.129                                                                                                                                        12     1.5 kB
```

`postgres-stats`:

```
# Top statements by total execution time (pg_stat_statements, since the run's warm-up began)
 calls | total_ms | mean_ms | max_ms | rows  |                                                                    query                                                                     
-------+----------+---------+--------+-------+----------------------------------------------------------------------------------------------------------------------------------------------
    59 |   4804.6 |  81.434 |  134.8 | 33984 | INSERT INTO wow_checkpoint AS c (subscription_group, subscription, partition, position) VALUES ($1, $2, $3, $4), ($5, $6, $7, $8), ($9, $10,
 21530 |   4111.5 |   0.191 |    7.3 | 21530 | INSERT INTO "wow_snapshot__transfer__account" AS stored (tenant_id, aggregate_id, version, schema_fingerprint, state, owner_id, space_id, de
 16749 |   3443.3 |   0.206 |    6.2 | 16749 | WITH current AS ( SELECT coalesce(max(version), 0) AS version FROM "wow_log__transfer__account" WHERE aggregate_id = $1 AND tenant_id = $2 )
  2411 |   1741.9 |   0.722 |   11.0 |  4822 | WITH input (ord, branch, part, tenant_id, aggregate_id, version, command_id, request_id, command_type, commit_time, events, owner_id, space_
  4009 |   1454.4 |   0.363 |    5.3 |  8018 | INSERT INTO "wow_snapshot__transfer__account" AS stored (tenant_id, aggregate_id, version, schema_fingerprint, state, owner_id, space_id, de
  1357 |   1245.0 |   0.917 |    9.3 |  4071 | WITH input (ord, branch, part, tenant_id, aggregate_id, version, command_id, request_id, command_type, commit_time, events, owner_id, space_
   918 |   1032.2 |   1.124 |    7.7 |  3672 | WITH input (ord, branch, part, tenant_id, aggregate_id, version, command_id, request_id, command_type, commit_time, events, owner_id, space_
  1947 |    949.2 |   0.488 |    6.7 |  5841 | INSERT INTO "wow_snapshot__transfer__account" AS stored (tenant_id, aggregate_id, version, schema_fingerprint, state, owner_id, space_id, de
   654 |    793.3 |   1.213 |    7.8 |  3270 | WITH input (ord, branch, part, tenant_id, aggregate_id, version, command_id, request_id, command_type, commit_time, events, owner_id, space_
  1146 |    710.5 |   0.620 |   10.5 |  4584 | INSERT INTO "wow_snapshot__transfer__account" AS stored (tenant_id, aggregate_id, version, schema_fingerprint, state, owner_id, space_id, de
   476 |    687.3 |   1.444 |    9.2 |  2856 | WITH input (ord, branch, part, tenant_id, aggregate_id, version, command_id, request_id, command_type, commit_time, events, owner_id, space_
   738 |    610.5 |   0.827 |   10.1 |  3690 | INSERT INTO "wow_snapshot__transfer__account" AS stored (tenant_id, aggregate_id, version, schema_fingerprint, state, owner_id, space_id, de
   324 |    544.8 |   1.681 |   11.6 |  2268 | WITH input (ord, branch, part, tenant_id, aggregate_id, version, command_id, request_id, command_type, commit_time, events, owner_id, space_
   487 |    461.6 |   0.948 |    6.7 |  2922 | INSERT INTO "wow_snapshot__transfer__account" AS stored (tenant_id, aggregate_id, version, schema_fingerprint, state, owner_id, space_id, de
   198 |    460.3 |   2.325 |   10.1 |  1782 | WITH input (ord, branch, part, tenant_id, aggregate_id, version, command_id, request_id, command_type, commit_time, events, owner_id, space_
(15 rows)

# Checkpoints (pg_stat_checkpointer)
 num_timed | num_requested | restartpoints_timed | restartpoints_req | restartpoints_done | write_time | sync_time | buffers_written |          stats_reset          
-----------+---------------+---------------------+-------------------+--------------------+------------+-----------+-----------------+-------------------------------
         0 |             1 |                   0 |                 0 |                  0 |         14 |         2 |             923 | 2026-11-11 12:17:05.764423+00
(1 row)

# Waits now (pg_stat_activity)
 wait_event_type |     wait_event      | count 
-----------------+---------------------+-------
 Client          | ClientRead          |    11
 Activity        | WalWriterMain       |     1
 Activity        | LogicalLauncherMain |     1
                 |                     |     1
 Activity        | CheckpointerMain    |     1
 Activity        | BgwriterMain        |     1
 Activity        | AutovacuumMain      |     1
(7 rows)


```

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 380.0/s | 380.0/s | 15200 | 0 | 21.5 ms | 272.4 ms | 434.2 ms | n/a | 561.2 ms | 0.76 cores | kafka 51% · postgres 72% | 0.18 of 2 | server 0.76 | 5.23 | 242.6 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 4 s | - |
| r1-head | head | 426.3/s | 426.3/s | 17051 | 0 | 41.2 ms | 358.4 ms | 540.7 ms | n/a | 675.8 ms | 0.80 cores | kafka 49% · postgres 72% | 0.18 of 2 | server 0.80 | 4.71 | 251.2 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 4 s | - |
| r2-head | head | 426.3/s | 426.3/s | 17051 | 0 | 32.3 ms | 196.6 ms | 299.0 ms | n/a | 407.6 ms | 0.79 cores | kafka 49% · postgres 73% | 0.21 of 2 | server 0.79 | 4.71 | 252.3 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 1 s | - |
| r2-base | base | 380.0/s | 380.0/s | 15200 | 0 | 15.7 ms | 86.5 ms | 157.7 ms | n/a | 242.7 ms | 0.79 cores | kafka 48% · postgres 69% | 0.19 of 2 | server 0.79 | 5.18 | 246.9 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| r3-base | base | 380.0/s | 380.0/s | 15200 | 0 | 21.2 ms | 90.6 ms | 162.8 ms | n/a | 266.2 ms | 0.79 cores | kafka 52% · postgres 69% | 0.18 of 2 | server 0.79 | 5.24 | 244.1 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| r3-head | head | 426.3/s | 426.3/s | 17051 | 0 | 57.1 ms | 585.7 ms | 913.4 ms | n/a | 1073.2 ms | 0.79 cores | kafka 50% · postgres 72% | 0.19 of 2 | server 0.79 | 4.72 | 249.8 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 5 s | - |
| r4-head | head | 426.3/s | 426.3/s | 17051 | 0 | 102.9 ms | 860.2 ms | 1269.8 ms | n/a | 1474.6 ms | 0.80 cores | kafka 51% · postgres 71% | 0.19 of 2 | server 0.80 | 4.73 | 248.2 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 6 s | - |
| r4-base | base | 380.0/s | 380.0/s | 15200 | 0 | 17.8 ms | 62.5 ms | 108.0 ms | n/a | 176.1 ms | 0.78 cores | kafka 51% · postgres 69% | 0.17 of 2 | server 0.78 | 5.19 | 242.9 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| r5-base | base | 380.0/s | 380.0/s | 15200 | 0 | 15.4 ms | 53.8 ms | 104.4 ms | n/a | 178.2 ms | 0.77 cores | kafka 51% · postgres 69% | 0.17 of 2 | server 0.77 | 5.20 | 243.9 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| r5-head | head | 426.3/s | 426.3/s | 17051 | 0 | 30.6 ms | 210.9 ms | 397.3 ms | n/a | 524.3 ms | 0.81 cores | kafka 50% · postgres 71% | 0.19 of 2 | server 0.81 | 4.71 | 254.0 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 1 s | - |
| e1-base | base | 380.0/s | 380.0/s | 15200 | 0 | 17.3 ms | 50.4 ms | 102.4 ms | n/a | 212.0 ms | 0.78 cores | kafka 52% · postgres 69% | 0.17 of 2 | server 0.78 | 5.22 | 245.4 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| e1-head | head | 380.0/s | 380.0/s | 15200 | 0 | 19.8 ms | 109.1 ms | 272.4 ms | n/a | 417.8 ms | 0.80 cores | kafka 50% · postgres 68% | 0.16 of 2 | server 0.80 | 5.20 | 257.0 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 1 s | - |
| e2-head | head | 380.0/s | 380.0/s | 15200 | 0 | 20.7 ms | 76.8 ms | 145.4 ms | n/a | 240.6 ms | 0.78 cores | kafka 50% · postgres 71% | 0.17 of 2 | server 0.78 | 5.23 | 260.2 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| e2-base | base | 380.0/s | 380.0/s | 15200 | 0 | 16.1 ms | 55.8 ms | 137.2 ms | n/a | 274.4 ms | 0.78 cores | kafka 51% · postgres 69% | 0.14 of 2 | server 0.78 | 5.20 | 245.3 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| e3-base | base | 380.0/s | 380.0/s | 15200 | 0 | 17.9 ms | 57.6 ms | 92.7 ms | n/a | 174.1 ms | 0.79 cores | kafka 50% · postgres 69% | 0.17 of 2 | server 0.79 | 5.21 | 246.0 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| e3-head | head | 380.0/s | 380.0/s | 15200 | 0 | 35.1 ms | 235.5 ms | 378.9 ms | n/a | 573.4 ms | 0.81 cores | kafka 51% · postgres 69% | 0.19 of 2 | server 0.81 | 5.28 | 255.0 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 2 s | - |
| e4-head | head | 380.0/s | 380.0/s | 15200 | 0 | 21.9 ms | 288.8 ms | 432.1 ms | n/a | 585.7 ms | 0.79 cores | kafka 49% · postgres 70% | 0.16 of 2 | server 0.79 | 5.20 | 257.9 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 4 s | - |
| e4-base | base | 380.0/s | 380.0/s | 15200 | 0 | 17.2 ms | 62.2 ms | 161.8 ms | n/a | 331.8 ms | 0.77 cores | kafka 49% · postgres 70% | 0.18 of 2 | server 0.77 | 5.17 | 244.1 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.122 | 1.917 | 1.316 | 1.245 | - | 1.204 | 0.900 | 1.035 |
| 2 | 1.122 | 2.057 | 2.272 | 1.896 | - | 1.679 | 0.909 | 1.022 |
| 3 | 1.122 | 2.687 | 6.463 | 5.610 | - | 4.031 | 0.900 | 1.023 |
| 4 | 1.122 | 5.784 | 13.771 | 11.754 | - | 8.372 | 0.911 | 1.022 |
| 5 | 1.122 | 1.992 | 3.924 | 3.804 | - | 2.943 | 0.906 | 1.042 |

Pairs at one rate for both arms, 380.0/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 1.148 | 2.162 | 2.660 | - | 1.971 | 0.997 | 1.047 |
| 2 | 1.000 | 1.291 | 1.376 | 1.060 | - | 0.877 | 1.007 | 1.061 |
| 3 | 1.000 | 1.957 | 4.089 | 4.088 | - | 3.294 | 1.012 | 1.037 |
| 4 | 1.000 | 1.276 | 4.642 | 2.671 | - | 1.765 | 1.005 | 1.057 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e3-pg@100k | head against base | cost | **more allocation** | head allocates ×1.05 the bytes per operation of base (median of the equal-rate pairs) |
| e3-pg@100k | r1-base | steady | **p99 > 10× p50** | p50 21.5 ms, p99 434.2 ms |
| e3-pg@100k | r1-head | steady | **p99 > 10× p50** | p50 41.2 ms, p99 540.7 ms |
| e3-pg@100k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -39.3% (±25.0%) |
| e3-pg@100k | r2-base | steady | **p99 > 10× p50** | p50 15.7 ms, p99 157.7 ms |
| e3-pg@100k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 -42.0% (±25.0%) |
| e3-pg@100k | r3-head | steady | **p99 > 10× p50** | p50 57.1 ms, p99 913.4 ms |
| e3-pg@100k | r4-head | steady | **unstable** | second half against first, beyond the default limits: p50 +28.6% (±25.0%) |
| e3-pg@100k | r4-head | steady | **p99 > 10× p50** | p50 102.9 ms, p99 1269.8 ms |
| e3-pg@100k | r5-base | steady | **unstable** | second half against first, beyond the default limits: p50 +51.1% (±25.0%) |
| e3-pg@100k | r5-head | steady | **unstable** | second half against first, beyond the default limits: p50 -39.7% (±25.0%) |
| e3-pg@100k | r5-head | steady | **p99 > 10× p50** | p50 30.6 ms, p99 397.3 ms |
| e3-pg@100k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 +46.1% (±25.0%) |
| e3-pg@100k | e1-head | steady | **p99 > 10× p50** | p50 19.8 ms, p99 272.4 ms |
| e3-pg@100k | e3-base | steady | **unstable** | second half against first, beyond the default limits: p50 +44.3% (±25.0%) |
| e3-pg@100k | e3-head | steady | **unstable** | second half against first, beyond the default limits: p50 -40.3% (±25.0%) |
| e3-pg@100k | e3-head | steady | **p99 > 10× p50** | p50 35.1 ms, p99 378.9 ms |
| e3-pg@100k | e4-head | steady | **p99 > 10× p50** | p50 21.9 ms, p99 432.1 ms |


