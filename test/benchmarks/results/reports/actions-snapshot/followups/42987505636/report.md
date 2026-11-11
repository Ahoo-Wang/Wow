# V10 end-to-end benchmark (D224) on GitHub Actions

Head: `main` 44eafe932db53b2a3bfe38861ea92f8cc2ef2a4f; V10 baseline: `v10.0.0-beta.1` 22f8ac19cb50b73d90613d7d401257600b3a914f; harness and load generator from head.

Tier: custom; coverage: e1-mongo-uniform@100k, e3-mongo@100k.

Each suite ran on one runner of its own (a sessions suite of several sweeps, each sweep in fresh sessions on one of its own), so only its ratios compare across suites; absolute numbers are that runner's. Ratios are b/a per pair (head/base): median (min–max, n pairs). Lower is better except throughput.

## Anomalies

Checks: a partition paused (D243), a tail read the store after a gap or an overflow (D249), more than 1% errors, a measured second below half the rate, p99 above 10× p50 (when p99 ≥ 50 ms), a head allocating more than 5.0% more bytes per operation than its baseline. Errors, dips and tails are not checked where a run went past what the node keeps up with by design (probes, cliff steps that did not keep up).

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e1-mongo-uniform@100k | r3-base | steady | **unstable** | second half against first, beyond the A/A noise: p50 -59.9% (±19.9%) |
| e1-mongo-uniform@100k | r3-head | steady | **unstable** | second half against first, beyond the A/A noise: p50 +67.3% (±19.9%) |
| e1-mongo-uniform@100k | r4-base | steady | **unstable** | second half against first, beyond the A/A noise: p50 -39.2% (±19.9%) |
| e1-mongo-uniform@100k | r5-head | steady | **unstable** | second half against first, beyond the A/A noise: p50 +25.7% (±19.9%) |
| e1-mongo-uniform@100k | e1-base | steady | **unstable** | second half against first, beyond the A/A noise: p50 +23.1% (±19.9%) |
| e1-mongo-uniform@100k | e1-head | steady | **unstable** | second half against first, beyond the A/A noise: p50 -53.6% (±19.9%) |
| e3-mongo@100k | r1-base | steady | **unstable** | second half against first, beyond the A/A noise: p50 +34.0% (±18.4%) |
| e3-mongo@100k | r2-head | steady | **unstable** | second half against first, beyond the A/A noise: p50 +39.7% (±18.4%) |
| e3-mongo@100k | r3-head | steady | **unstable** | second half against first, beyond the A/A noise: p50 +50.4% (±18.4%) |
| e3-mongo@100k | e4-head | steady | **unstable** | second half against first, beyond the A/A noise: p50 +27.2% (±18.4%) |

## Significance

Beyond noise: 4 of 6 ratios; 2 of 2 suites have an A/A noise threshold (test/benchmarks/noise.json). A ratio counts when its 95% bootstrap interval (over the measured seconds) clears the suite's threshold; without one it is `uncalibrated`.

| Suite | b/a | Metric | Rounds | Ratio [95% CI] | Noise | Verdict |
| --- | --- | --- | --- | --- | --- | --- |
| e1-mongo-uniform@100k | head/base | throughput | calibrated | 1.094 [1.094–1.094] | ±2.0% | **higher** |
| e1-mongo-uniform@100k | head/base | p50 | equal | 1.628 [1.274–1.719] | ±19.9% | **higher** |
| e1-mongo-uniform@100k | head/base | p99 | equal | 1.805 [1.201–1.660] | ±12.1% | **higher** |
| e3-mongo@100k | head/base | throughput | calibrated | 0.959 [0.958–0.959] | ±2.0% | **lower** |
| e3-mongo@100k | head/base | p50 | equal | 1.169 [0.887–1.335] | ±18.4% | within noise |
| e3-mongo@100k | head/base | p99 | equal | 1.062 [0.983–1.268] | ±9.1% | within noise |

## Runners

| Suite | CPU | Cores | Memory | JDK | CPU layout |
| --- | --- | --- | --- | --- | --- |
| e1-mongo-uniform@100k | AMD EPYC 7763 64-Core Processor | 4 | 16 GB | Eclipse Adoptium 25.0.5+8-LTS | generator 3 · node 0 · store 1 · Kafka 2 |
| e3-mongo@100k | Intel(R) Xeon(R) Platinum 8370C CPU @ 2.80GHz | 4 | 16 GB | Eclipse Adoptium 25.0.5+8-LTS | generator 3 · node 0 · store 1 · Kafka 2 |

## Saturation points (closed loop)

| Suite | Arm | Saturation | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- | --- |
| e1-mongo-uniform@100k | base | 1502.5/s | 256 | 1202.0/s | 80% |
| e1-mongo-uniform@100k | head | 1643.1/s | 1024 | 1314.5/s | 80% |
| e3-mongo@100k | base | 507.4/s | 256 | 405.9/s | 80% |
| e3-mongo@100k | head | 486.4/s | 128 | 389.1/s | 80% |

## Paired ratios

`calibrated`: each arm at its own calibrated rate, so throughput and CPU per operation compare but the latencies are at **unequal loads**. `equal rate`: both arms at the lower of the two calibrated rates; read the latency ratios from these rows.

| Suite | b/a | Rounds | Saturation | Throughput | p50 | p95 | p99 | p999 | Worst-second p99 | CPU ms/op | Alloc/op |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| e1-mongo-uniform@100k | head/base | calibrated (unequal load) | 1.094 | 1.094 (1.094–1.094, n=5) | 1.589 (1.018–2.178, n=5) | 1.438 (0.643–1.740, n=5) | 1.294 (0.699–1.660, n=5) | - | 1.109 (0.770–1.569, n=5) | 0.921 (0.917–0.936, n=5) | 0.995 (0.989–1.019, n=5) |
| e1-mongo-uniform@100k | head/base | equal rate 1202.0/s | 1.094 | 1.000 (1.000–1.000, n=4) | 1.537 (1.053–1.903, n=4) | 1.579 (1.190–2.243, n=4) | 1.611 (1.129–2.662, n=4) | - | 1.298 (0.922–2.727, n=4) | 1.014 (0.989–1.018, n=4) | 1.007 (0.993–1.021, n=4) |
| e3-mongo@100k | head/base | calibrated (unequal load) | 0.959 | 0.959 (0.959–0.959, n=5) | 0.782 (0.718–1.107, n=5) | 0.892 (0.730–1.069, n=5) | 0.896 (0.675–1.135, n=5) | - | 0.907 (0.736–1.156, n=5) | 1.039 (1.030–1.067, n=5) | 1.051 (1.046–1.074, n=5) |
| e3-mongo@100k | head/base | equal rate 389.1/s | 0.959 | 1.000 (1.000–1.000, n=4) | 1.100 (0.931–1.303, n=4) | 1.038 (0.837–1.345, n=4) | 1.048 (0.829–1.153, n=4) | - | 1.043 (0.977–1.175, n=4) | 1.011 (1.000–1.030, n=4) | 1.028 (1.017–1.059, n=4) |

---

### e1-mongo-uniform@100k

E1 write on MongoDB, uniform keys: the head against the V10 baseline (regression guard), throughput and CPU at each calibrated rate, latency at one rate.

E1, uniform keys over 100000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 4 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 7763 64-Core Processor, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (67–86 s), then waited until the nodes were quiet again (3–5 s).

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 1502.5/s | 256 | 1202.0/s | 80% |
| head | 1643.1/s | 1024 | 1314.5/s | 80% |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 1202.0/s | 1202.0/s | 24040 | 0 | 94.7 ms | 383.0 ms | 602.1 ms | n/a | 749.6 ms | 0.70 cores | kafka 48% · mongo 99% | 0.59 of 1 | mongo 0.99 | 1.81 | 98.4 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 7 s | - |
| probe1-head | head | 1314.5/s | 1314.5/s | 26290 | 0 | 256.0 ms | 733.2 ms | 933.9 ms | n/a | 1073.2 ms | 0.77 cores | kafka 53% · mongo 98% | 0.66 of 1 | mongo 0.98 | 1.74 | 98.8 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 12 s | - |

Profile of base (profile-base, not a timed round):

`hot-methods`:

```
                                                               Java Methods that Execute the Most
Method                                                                                                                                          Samples Percent
----------------------------------------------------------------------------------------------------------------------------------------------- ------- -------
java.util.HashMap.getNode(Object)                                                                                                                   435   3.28%
java.util.HashMap.putVal(int, Object, Object, boolean, boolean)                                                                                     221   1.67%
org.apache.kafka.clients.producer.KafkaProducer.send(ProducerRecord, Callback)                                                                      193   1.46%
kotlin.coroutines.jvm.internal.ContinuationImpl.<init>(Continuation)                                                                                181   1.37%
com.mongodb.internal.connection.ByteBufferBsonOutput.writeOnArrayAscii(String, byte[], int, boolean)                                                165   1.24%
java.util.Objects.equals(Object, Object)                                                                                                            163   1.23%
java.util.concurrent.ConcurrentHashMap.get(Object)                                                                                                  158   1.19%
kotlin.coroutines.CombinedContext.get(CoroutineContext$Key)                                                                                         134   1.01%
kotlinx.coroutines.DispatchedTask.run()                                                                                                             122   0.92%
kotlin.jvm.internal.Intrinsics.throwParameterIsNullNPE(String)                                                                                      117   0.88%
java.util.regex.Pattern$BmpCharProperty.match(Matcher, int, CharSequence)                                                                           114   0.86%
kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith(Object)                                                                              111   0.84%
java.nio.ByteBuffer.position(int)                                                                                                                   104   0.78%
```

`allocation-by-class`:

```
                                                                      Allocation by Class
Object Type                                                                                                                                 Allocation Pressure
------------------------------------------------------------------------------------------------------------------------------------------- -------------------
byte[]                                                                                                                                                    8.02%
java.lang.Object[]                                                                                                                                        6.81%
java.util.HashMap$Node[]                                                                                                                                  4.46%
java.util.LinkedHashMap$Entry                                                                                                                             4.21%
java.util.LinkedHashMap                                                                                                                                   3.53%
jdk.internal.vm.StackChunk                                                                                                                                2.44%
java.lang.String                                                                                                                                          2.41%
org.bson.internal.ChildCodecRegistry                                                                                                                      2.08%
java.util.ArrayList                                                                                                                                       2.03%
org.bson.internal.CodecCache$CodecCacheKey                                                                                                                1.68%
org.bson.codecs.Codec[]                                                                                                                                   1.61%
org.bson.BsonBinaryWriter$Context                                                                                                                         1.58%
org.bson.AbstractBsonWriter$State[]                                                                                                                       1.25%
```

`contention-by-site`:

```
                                                                       Contention by Site
StackTrace                                                                                                                                Count    Avg.    Max.
----------------------------------------------------------------------------------------------------------------------------------------- ----- ------- -------
org.apache.kafka.clients.NetworkClient.parseResponse(ByteBuffer, RequestHeader)                                                               1  180 ms  180 ms
tools.jackson.databind.ser.SerializerCache.addAndResolveNonTypedSerializer(Class, JavaType, ValueSerializer, SerializationContext)            1  141 ms  141 ms
org.apache.kafka.clients.producer.internals.RecordAccumulator.partitionReady(...)                                                             2 66.8 ms  123 ms
me.ahoo.wow.runtime.subscription.SubscriptionWorker.drain(AggregateRef, SubscriptionWorker$Lane, Continuation)                                3 47.0 ms  115 ms
tools.jackson.databind.ser.SerializerCache.addAndResolveNonTypedSerializer(Class, JavaType, ValueSerializer, SerializationContext)            1 57.1 ms 57.1 ms
tools.jackson.databind.ser.SerializerCache.addAndResolveNonTypedSerializer(Class, JavaType, ValueSerializer, SerializationContext)            1 46.8 ms 46.8 ms
org.apache.kafka.common.requests.ApiVersionsRequest$Builder.build(short)                                                                      1 40.4 ms 40.4 ms
sun.nio.ch.EPollSelectorImpl.clearInterrupt()                                                                                                48 13.4 ms 35.8 ms
org.apache.kafka.common.requests.AbstractResponse.parseResponse(ApiKeys, Readable, short)                                                     1 22.3 ms 22.3 ms
jdk.internal.loader.BuiltinClassLoader.loadClassOrNull(String, boolean)                                                                       1 20.0 ms 20.0 ms
me.ahoo.wow.runtime.wait.Waiting.carry(CommandResult)                                                                                        11 13.0 ms 18.2 ms
jdk.internal.loader.BuiltinClassLoader.loadClassOrNull(String, boolean)                                                                       1 18.0 ms 18.0 ms
jdk.internal.loader.BuiltinClassLoader.loadClassOrNull(String, boolean)                                                                       1 17.7 ms 17.7 ms
```

`gc-pauses`:

```
GC Pauses
---------
Total Pause Time: 6.06 s
Number of Pauses: 80
Minimum Pause Time: 0.0240 ms
Median Pause Time: 84.7 ms
Average Pause Time: 75.7 ms
P90 Pause Time: 109 ms
P95 Pause Time: 123 ms
P99 Pause Time: 168 ms
P99.9% Pause Time: 168 ms
Maximum Pause Time: 168 ms
```

`safepoints`:

```
                                                                            Safepoints
Start Time                  Duration                                     State Syncronization                  JNI Critical Threads                  Total Threads
--------------------------- --------------------------- ------------------------------------- ------------------------------------- ------------------------------
10:50:45                    Indefinite                                                    N/A                                     0                             11
10:50:45                    0.229 ms                                                      N/A                                     0                             11
10:50:46                    13.4 ms                                                       N/A                                     0                             12
10:50:47                    30.3 ms                                                       N/A                                     0                             12
10:50:47                    5.52 ms                                                       N/A                                     0                             12
10:50:47                    0.633 ms                                                      N/A                                     0                             12
10:50:50                    55.8 ms                                                       N/A                                     0                             13
10:50:50                    22.3 ms                                                       N/A                                     0                             13
10:50:50                    27.4 ms                                                       N/A                                     0                             13
10:50:50                    16.2 ms                                                       N/A                                     0                             13
10:50:50                    2.28 ms                                                       N/A                                     0                             13
10:50:52                    114 ms                                                        N/A                                     0                             24
10:50:53                    2.18 ms                                                       N/A                                     0                             24
```

`latencies-by-type`:

```
                                                                       Latencies by Type
Event Type                                                                                                             Count Average    P 99  Longest     Total
-------------------------------------------------------------------------------------------------------------------- ------- ------- ------- -------- ---------
Java Thread Park                                                                                                     215,276 17.9 ms  118 ms 5 m 52 s   1 h 4 m
Socket Read                                                                                                           73,088 38.3 ms  156 ms   28.1 s 46 m 41 s
Java Monitor Wait                                                                                                         14  26.6 s 1 m 0 s  1 m 0 s  6 m 12 s
Java Thread Sleep                                                                                                         36  10.0 s  10.0 s   10.0 s   6 m 0 s
Socket Write                                                                                                           5,698 2.89 ms 12.6 ms   107 ms    16.5 s
Java Monitor Blocked                                                                                                      80 21.1 ms  180 ms   180 ms    1.69 s
File Read                                                                                                                 12 3.35 ms 6.02 ms  6.02 ms   40.2 ms
File Write                                                                                                                 9 2.32 ms 4.82 ms  4.82 ms   20.9 ms
```

`socket-reads-by-host`:

```
                                                                      Socket Reads by Host
Host                                                                                                                                           Reads Total Read
--------------------------------------------------------------------------------------------------------------------------------------------- ------ ----------
localhost                                                                                                                                     73,070     1.1 MB
10.1.0.134                                                                                                                                        18     1.7 kB
```

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 1202.0/s | 1202.0/s | 48080 | 0 | 86.0 ms | 268.3 ms | 385.0 ms | n/a | 532.5 ms | 0.74 cores | kafka 50% · mongo 100% | 0.41 of 1 | mongo 1.00 | 1.87 | 97.3 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 6 s | - |
| r1-head | head | 1314.5/s | 1314.5/s | 52580 | 0 | 182.3 ms | 466.9 ms | 639.0 ms | n/a | 835.6 ms | 0.78 cores | kafka 51% · mongo 99% | 0.44 of 1 | mongo 0.99 | 1.73 | 96.8 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 10 s | - |
| r2-head | head | 1314.5/s | 1314.5/s | 52580 | 0 | 200.7 ms | 548.9 ms | 716.8 ms | n/a | 835.6 ms | 0.77 cores | kafka 49% · mongo 99% | 0.43 of 1 | mongo 0.99 | 1.71 | 96.3 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 8 s | - |
| r2-base | base | 1202.0/s | 1202.0/s | 48080 | 0 | 92.2 ms | 448.5 ms | 626.7 ms | n/a | 753.7 ms | 0.73 cores | kafka 51% · mongo 100% | 0.40 of 1 | mongo 1.00 | 1.86 | 97.4 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 9 s | - |
| r3-base | base | 1202.0/s | 1202.0/s | 48080 | 0 | 169.0 ms | 745.5 ms | 938.0 ms | n/a | 1138.7 ms | 0.74 cores | kafka 51% · mongo 100% | 0.41 of 1 | mongo 1.00 | 1.87 | 96.2 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 14 s | - |
| r3-head | head | 1314.5/s | 1314.5/s | 52580 | 0 | 172.0 ms | 479.2 ms | 655.4 ms | n/a | 876.5 ms | 0.78 cores | kafka 50% · mongo 99% | 0.43 of 1 | mongo 0.99 | 1.73 | 97.3 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 9 s | - |
| r4-head | head | 1314.5/s | 1314.5/s | 52580 | 0 | 154.6 ms | 430.1 ms | 532.5 ms | n/a | 639.0 ms | 0.76 cores | kafka 51% · mongo 100% | 0.42 of 1 | mongo 1.00 | 1.72 | 98.6 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 18 s | - |
| r4-base | base | 1202.0/s | 1202.0/s | 48080 | 0 | 97.3 ms | 299.0 ms | 411.6 ms | n/a | 589.8 ms | 0.71 cores | kafka 50% · mongo 100% | 0.42 of 1 | mongo 1.00 | 1.84 | 96.7 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 9 s | - |
| r5-base | base | 1202.0/s | 1202.0/s | 48080 | 0 | 114.7 ms | 305.2 ms | 407.6 ms | n/a | 569.3 ms | 0.77 cores | kafka 51% · mongo 100% | 0.41 of 1 | mongo 1.00 | 1.89 | 97.0 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 13 s | - |
| r5-head | head | 1314.5/s | 1314.5/s | 52580 | 0 | 173.1 ms | 483.3 ms | 655.4 ms | n/a | 778.2 ms | 0.77 cores | kafka 52% · mongo 99% | 0.43 of 1 | mongo 0.99 | 1.74 | 96.2 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 10 s | - |
| e1-base | base | 1202.0/s | 1202.0/s | 48080 | 0 | 84.5 ms | 264.2 ms | 374.8 ms | n/a | 544.8 ms | 0.71 cores | kafka 52% · mongo 100% | 0.41 of 1 | mongo 1.00 | 1.85 | 97.5 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 5 s | - |
| e1-head | head | 1202.0/s | 1202.0/s | 48080 | 0 | 154.6 ms | 493.6 ms | 745.5 ms | n/a | 897.0 ms | 0.76 cores | kafka 51% · mongo 99% | 0.41 of 1 | mongo 0.99 | 1.89 | 97.9 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 13 s | - |
| e2-head | head | 1202.0/s | 1202.0/s | 48080 | 0 | 81.9 ms | 278.5 ms | 378.9 ms | n/a | 501.8 ms | 0.75 cores | kafka 50% · mongo 100% | 0.39 of 1 | mongo 1.00 | 1.87 | 100.2 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 9 s | - |
| e2-base | base | 1202.0/s | 1202.0/s | 48080 | 0 | 77.8 ms | 216.1 ms | 307.2 ms | n/a | 528.4 ms | 0.70 cores | kafka 52% · mongo 100% | 0.41 of 1 | mongo 1.00 | 1.84 | 98.2 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 8 s | - |
| e3-base | base | 1202.0/s | 1202.0/s | 48080 | 0 | 74.2 ms | 210.9 ms | 284.7 ms | n/a | 360.4 ms | 0.74 cores | kafka 51% · mongo 99% | 0.40 of 1 | mongo 0.99 | 1.86 | 99.3 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 7 s | - |
| e3-head | head | 1202.0/s | 1202.0/s | 48080 | 0 | 141.3 ms | 473.1 ms | 757.8 ms | n/a | 983.0 ms | 0.77 cores | kafka 51% · mongo 100% | 0.40 of 1 | mongo 1.00 | 1.89 | 98.6 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 13 s | - |
| e4-head | head | 1202.0/s | 1202.0/s | 48080 | 0 | 122.9 ms | 358.4 ms | 485.4 ms | n/a | 581.6 ms | 0.74 cores | kafka 49% · mongo 100% | 0.41 of 1 | mongo 1.00 | 1.85 | 98.3 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 8 s | - |
| e4-base | base | 1202.0/s | 1202.0/s | 48080 | 0 | 98.8 ms | 301.1 ms | 430.1 ms | n/a | 630.8 ms | 0.76 cores | kafka 50% · mongo 99% | 0.40 of 1 | mongo 0.99 | 1.87 | 97.5 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 7 s | - |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.094 | 2.119 | 1.740 | 1.660 | - | 1.569 | 0.928 | 0.995 |
| 2 | 1.094 | 2.178 | 1.224 | 1.144 | - | 1.109 | 0.921 | 0.989 |
| 3 | 1.094 | 1.018 | 0.643 | 0.699 | - | 0.770 | 0.921 | 1.011 |
| 4 | 1.094 | 1.589 | 1.438 | 1.294 | - | 1.083 | 0.936 | 1.019 |
| 5 | 1.094 | 1.509 | 1.584 | 1.608 | - | 1.367 | 0.917 | 0.992 |

Pairs at one rate for both arms, 1202.0/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 1.830 | 1.868 | 1.989 | - | 1.647 | 1.018 | 1.005 |
| 2 | 1.000 | 1.053 | 1.289 | 1.233 | - | 0.950 | 1.015 | 1.021 |
| 3 | 1.000 | 1.903 | 2.243 | 2.662 | - | 2.727 | 1.014 | 0.993 |
| 4 | 1.000 | 1.244 | 1.190 | 1.129 | - | 0.922 | 0.989 | 1.009 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e1-mongo-uniform@100k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -59.9% (±25.0%) |
| e1-mongo-uniform@100k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 +67.3% (±25.0%) |
| e1-mongo-uniform@100k | r4-base | steady | **unstable** | second half against first, beyond the default limits: p50 -39.2% (±25.0%) |
| e1-mongo-uniform@100k | r5-head | steady | **unstable** | second half against first, beyond the default limits: p50 +25.7% (±25.0%) |
| e1-mongo-uniform@100k | e1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -53.6% (±25.0%) |


### e3-mongo@100k

E3 saga chain on MongoDB (completion: the target's Entry stored): head against baseline.

E3, uniform keys over 100000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 4 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: Intel(R) Xeon(R) Platinum 8370C CPU @ 2.80GHz, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (65–71 s), then waited until the nodes were quiet again (4–5 s).

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 507.4/s | 256 | 405.9/s | 80% |
| head | 486.4/s | 128 | 389.1/s | 80% |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 405.9/s | 405.9/s | 8118 | 0 | 121.9 ms | 340.0 ms | 466.9 ms | n/a | 565.2 ms | 0.62 cores | kafka 46% · mongo 98% | 0.32 of 1 | mongo 0.98 | 5.08 | 266.3 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| probe1-head | head | 389.1/s | 389.1/s | 7782 | 0 | 192.5 ms | 905.2 ms | 1028.1 ms | n/a | 1220.6 ms | 0.70 cores | kafka 51% · mongo 98% | 0.35 of 1 | mongo 0.98 | 5.64 | 271.9 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 5 s | - |

Profile of base (profile-base, not a timed round):

`hot-methods`:

```
                                                               Java Methods that Execute the Most
Method                                                                                                                                          Samples Percent
----------------------------------------------------------------------------------------------------------------------------------------------- ------- -------
java.util.HashMap.getNode(Object)                                                                                                                   414   3.51%
java.util.HashMap.putVal(int, Object, Object, boolean, boolean)                                                                                     203   1.72%
com.mongodb.internal.connection.ByteBufferBsonOutput.writeOnArrayAscii(String, byte[], int, boolean)                                                197   1.67%
kotlin.coroutines.jvm.internal.ContinuationImpl.<init>(Continuation)                                                                                181   1.54%
java.util.concurrent.ConcurrentHashMap.get(Object)                                                                                                  175   1.49%
kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith(Object)                                                                              123   1.04%
org.apache.kafka.clients.producer.KafkaProducer.send(ProducerRecord, Callback)                                                                      119   1.01%
kotlin.jvm.internal.Intrinsics.throwParameterIsNullNPE(String)                                                                                      115   0.98%
reactor.core.publisher.InnerOperator.currentContext()                                                                                                98   0.83%
kotlin.coroutines.CombinedContext.get(CoroutineContext$Key)                                                                                          93   0.79%
kotlinx.coroutines.DispatchedTask.run()                                                                                                              86   0.73%
java.util.regex.Pattern$BmpCharPropertyGreedy.match(Matcher, int, CharSequence)                                                                      85   0.72%
java.lang.invoke.Invokers$Holder.invokeExact_MT(Object, Object, Object)                                                                              85   0.72%
```

`allocation-by-class`:

```
                                                                      Allocation by Class
Object Type                                                                                                                                 Allocation Pressure
------------------------------------------------------------------------------------------------------------------------------------------- -------------------
byte[]                                                                                                                                                    7.98%
java.lang.Object[]                                                                                                                                        6.97%
java.util.HashMap$Node[]                                                                                                                                  4.41%
java.util.LinkedHashMap$Entry                                                                                                                             4.20%
java.util.LinkedHashMap                                                                                                                                   3.60%
java.lang.String                                                                                                                                          2.43%
java.util.ArrayList                                                                                                                                       2.38%
jdk.internal.vm.StackChunk                                                                                                                                2.32%
org.bson.internal.ChildCodecRegistry                                                                                                                      2.14%
org.bson.codecs.Codec[]                                                                                                                                   1.78%
org.bson.BsonBinaryWriter$Context                                                                                                                         1.65%
org.bson.internal.CodecCache$CodecCacheKey                                                                                                                1.64%
org.bson.AbstractBsonWriter$State[]                                                                                                                       1.37%
```

`contention-by-site`:

```
                                                                       Contention by Site
StackTrace                                                                                                                                Count    Avg.    Max.
----------------------------------------------------------------------------------------------------------------------------------------- ----- ------- -------
tools.jackson.databind.ser.SerializerCache.addAndResolveNonTypedSerializer(Class, JavaType, ValueSerializer, SerializationContext)            1  126 ms  126 ms
jdk.internal.loader.BuiltinClassLoader.loadClassOrNull(String, boolean)                                                                       1 79.9 ms 79.9 ms
org.apache.kafka.clients.NetworkClient.parseResponse(ByteBuffer, RequestHeader)                                                               1 53.4 ms 53.4 ms
org.apache.kafka.common.requests.ApiVersionsRequest$Builder.build(short)                                                                      1 32.6 ms 32.6 ms
tools.jackson.databind.ser.SerializerCache._makeReadOnlyLookupMap()                                                                           1 28.5 ms 28.5 ms
tools.jackson.databind.ser.SerializerCache.addAndResolveNonTypedSerializer(Class, JavaType, ValueSerializer, SerializationContext)            1 26.2 ms 26.2 ms
sun.nio.ch.EPollSelectorImpl.clearInterrupt()                                                                                                34 12.6 ms 20.6 ms
jdk.internal.loader.BuiltinClassLoader.loadClassOrNull(String, boolean)                                                                       1 19.5 ms 19.5 ms
me.ahoo.wow.runtime.subscription.SubscriptionWorker.drain(AggregateRef, SubscriptionWorker$Lane, Continuation)                                6 12.2 ms 19.0 ms
me.ahoo.wow.runtime.wait.Waiting.carry(CommandResult)                                                                                        14 12.0 ms 18.1 ms
me.ahoo.wow.runtime.subscription.SubscriptionWorker.runBatch(SubscriptionWorker$Item$Live, SubscriptionWorker$Lane, Continuation)             1 13.3 ms 13.3 ms
me.ahoo.wow.runtime.subscription.PartitionPause.isPaused()                                                                                    1 12.6 ms 12.6 ms
me.ahoo.wow.runtime.snapshot.SnapshotWrites.save(SnapshotRecord, Continuation)                                                                1 12.4 ms 12.4 ms
```

`gc-pauses`:

```
GC Pauses
---------
Total Pause Time: 5.65 s
Number of Pauses: 77
Minimum Pause Time: 0.0273 ms
Median Pause Time: 82.2 ms
Average Pause Time: 73.3 ms
P90 Pause Time: 109 ms
P95 Pause Time: 121 ms
P99 Pause Time: 153 ms
P99.9% Pause Time: 153 ms
Maximum Pause Time: 153 ms
```

`safepoints`:

```
                                                                            Safepoints
Start Time                  Duration                                     State Syncronization                  JNI Critical Threads                  Total Threads
--------------------------- --------------------------- ------------------------------------- ------------------------------------- ------------------------------
10:45:18                    Indefinite                                                    N/A                                     0                             11
10:45:18                    0.219 ms                                                      N/A                                     0                             11
10:45:19                    12.9 ms                                                       N/A                                     0                             12
10:45:19                    27.4 ms                                                       N/A                                     0                             12
10:45:19                    0.863 ms                                                      N/A                                     0                             12
10:45:19                    6.49 ms                                                       N/A                                     0                             12
10:45:19                    0.140 ms                                                      N/A                                     0                             12
10:45:22                    23.2 ms                                                       N/A                                     0                             13
10:45:22                    18.1 ms                                                       N/A                                     0                             13
10:45:22                    0.0941 ms                                                     N/A                                     0                             13
10:45:22                    16.7 ms                                                       N/A                                     0                             13
10:45:22                    8.35 ms                                                       N/A                                     0                             13
10:45:22                    0.115 ms                                                      N/A                                     0                             13
```

`latencies-by-type`:

```
                                                                       Latencies by Type
Event Type                                                                                                              Count Average    P 99 Longest     Total
--------------------------------------------------------------------------------------------------------------------- ------- ------- ------- ------- ---------
Java Thread Park                                                                                                      231,310 14.2 ms 99.4 ms  59.9 s 54 m 47 s
Socket Read                                                                                                            70,223 35.7 ms  168 ms  27.5 s 41 m 49 s
Java Thread Sleep                                                                                                          34  9.98 s  9.98 s  9.98 s  5 m 39 s
Java Monitor Wait                                                                                                          11  28.3 s  59.9 s  59.9 s  5 m 11 s
Socket Write                                                                                                            4,377 2.96 ms 13.6 ms 85.9 ms    13.0 s
Java Monitor Blocked                                                                                                       70 16.2 ms  126 ms  126 ms    1.14 s
File Read                                                                                                                  10 3.56 ms 8.27 ms 8.27 ms   35.6 ms
File Write                                                                                                                  5 1.84 ms 2.71 ms 2.71 ms   9.22 ms
```

`socket-reads-by-host`:

```
                                                                      Socket Reads by Host
Host                                                                                                                                           Reads Total Read
--------------------------------------------------------------------------------------------------------------------------------------------- ------ ----------
localhost                                                                                                                                     70,205     1.1 MB
10.1.0.191                                                                                                                                        18     1.8 kB
```

Profile of head (profile-head, not a timed round):

`hot-methods`:

```
                                                               Java Methods that Execute the Most
Method                                                                                                                                          Samples Percent
----------------------------------------------------------------------------------------------------------------------------------------------- ------- -------
java.util.HashMap.getNode(Object)                                                                                                                   360   2.74%
java.util.HashMap.putVal(int, Object, Object, boolean, boolean)                                                                                     244   1.86%
kotlin.coroutines.jvm.internal.ContinuationImpl.<init>(Continuation)                                                                                198   1.51%
kotlin.jvm.internal.Intrinsics.throwParameterIsNullNPE(String)                                                                                      148   1.13%
org.apache.kafka.clients.producer.KafkaProducer.send(ProducerRecord, Callback)                                                                      146   1.11%
kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith(Object)                                                                              137   1.04%
com.mongodb.internal.connection.ByteBufferBsonOutput.writeOnArrayAscii(String, byte[], int, boolean)                                                136   1.03%
kotlin.coroutines.CombinedContext.get(CoroutineContext$Key)                                                                                         129   0.98%
java.util.concurrent.ConcurrentHashMap.get(Object)                                                                                                  128   0.97%
java.lang.invoke.Invokers$Holder.invokeExact_MT(Object, Object, Object)                                                                             125   0.95%
java.util.Objects.equals(Object, Object)                                                                                                            114   0.87%
java.util.concurrent.TimeUnit.convert(long, TimeUnit)                                                                                               100   0.76%
java.util.regex.Pattern$BmpCharPropertyGreedy.match(Matcher, int, CharSequence)                                                                      98   0.75%
```

`allocation-by-class`:

```
                                                                      Allocation by Class
Object Type                                                                                                                                 Allocation Pressure
------------------------------------------------------------------------------------------------------------------------------------------- -------------------
byte[]                                                                                                                                                    7.85%
java.lang.Object[]                                                                                                                                        7.09%
java.util.LinkedHashMap$Entry                                                                                                                             4.25%
java.util.HashMap$Node[]                                                                                                                                  4.13%
java.util.LinkedHashMap                                                                                                                                   3.50%
java.lang.String                                                                                                                                          2.35%
jdk.internal.vm.StackChunk                                                                                                                                2.30%
java.util.ArrayList                                                                                                                                       2.16%
org.bson.internal.ChildCodecRegistry                                                                                                                      2.14%
org.bson.codecs.Codec[]                                                                                                                                   1.78%
org.bson.internal.CodecCache$CodecCacheKey                                                                                                                1.62%
org.bson.BsonBinaryWriter$Context                                                                                                                         1.53%
kotlin.coroutines.CombinedContext                                                                                                                         1.34%
```

`contention-by-site`:

```
                                                                       Contention by Site
StackTrace                                                                                                                                Count    Avg.    Max.
----------------------------------------------------------------------------------------------------------------------------------------- ----- ------- -------
tools.jackson.databind.ser.SerializerCache._makeReadOnlyLookupMap()                                                                           1  113 ms  113 ms
sun.nio.ch.EPollSelectorImpl.clearInterrupt()                                                                                                43 15.3 ms 84.8 ms
tools.jackson.databind.ser.SerializerCache.addAndResolveNonTypedSerializer(Class, JavaType, ValueSerializer, SerializationContext)            1 62.9 ms 62.9 ms
org.apache.kafka.clients.NetworkClient.parseResponse(ByteBuffer, RequestHeader)                                                               1 55.6 ms 55.6 ms
org.apache.kafka.common.requests.ApiVersionsRequest$Builder.build(short)                                                                      1 44.1 ms 44.1 ms
jdk.internal.loader.BuiltinClassLoader.loadClassOrNull(String, boolean)                                                                       1 29.9 ms 29.9 ms
me.ahoo.wow.runtime.wait.Waiting.carry(CommandResult)                                                                                         9 13.2 ms 21.0 ms
me.ahoo.wow.runtime.subscription.SubscriptionWorker.drain(AggregateRef, SubscriptionWorker$Lane, Continuation)                               10 12.7 ms 20.7 ms
tools.jackson.databind.ser.PropertyBuilder._constructPropertyWriter(...)                                                                      1 20.2 ms 20.2 ms
me.ahoo.wow.runtime.subscription.SubscriptionWorker$Ops.settle(List, boolean, SubscriptionWorker$Lane, List)                                  5 16.0 ms 19.6 ms
org.apache.kafka.clients.producer.internals.TransactionManager.maybeResolveSequences()                                                        1 16.2 ms 16.2 ms
jdk.internal.loader.BuiltinClassLoader.loadClassOrNull(String, boolean)                                                                       1 15.5 ms 15.5 ms
kotlinx.coroutines.internal.ThreadSafeHeap.remove(ThreadSafeHeapNode)                                                                         3 13.0 ms 15.2 ms
```

`gc-pauses`:

```
GC Pauses
---------
Total Pause Time: 7.07 s
Number of Pauses: 79
Minimum Pause Time: 0.0168 ms
Median Pause Time: 99.1 ms
Average Pause Time: 89.4 ms
P90 Pause Time: 131 ms
P95 Pause Time: 139 ms
P99 Pause Time: 168 ms
P99.9% Pause Time: 168 ms
Maximum Pause Time: 168 ms
```

`safepoints`:

```
                                                                            Safepoints
Start Time                  Duration                                     State Syncronization                  JNI Critical Threads                  Total Threads
--------------------------- --------------------------- ------------------------------------- ------------------------------------- ------------------------------
10:51:33                    Indefinite                                                    N/A                                     0                             11
10:51:33                    0.208 ms                                                      N/A                                     0                             11
10:51:33                    14.2 ms                                                       N/A                                     0                             12
10:51:34                    12.5 ms                                                       N/A                                     0                             12
10:51:34                    3.82 ms                                                       N/A                                     0                             12
10:51:34                    0.597 ms                                                      N/A                                     0                             12
10:51:37                    71.5 ms                                                       N/A                                     0                             13
10:51:37                    31.2 ms                                                       N/A                                     0                             13
10:51:37                    24.0 ms                                                       N/A                                     0                             13
10:51:38                    9.09 ms                                                       N/A                                     0                             13
10:51:38                    0.145 ms                                                      N/A                                     0                             13
10:51:40                    117 ms                                                        N/A                                     0                             22
10:51:40                    6.90 ms                                                       N/A                                     0                             22
```

`latencies-by-type`:

```
                                                                       Latencies by Type
Event Type                                                                                                              Count Average    P 99  Longest    Total
--------------------------------------------------------------------------------------------------------------------- ------- ------- ------- -------- --------
Java Thread Park                                                                                                      220,352 16.5 ms  112 ms 5 m 41 s  1 h 0 m
Socket Read                                                                                                            70,015 37.7 ms  171 ms   27.6 s 44 m 0 s
Java Thread Sleep                                                                                                          35  9.98 s  9.98 s   9.98 s 5 m 49 s
Java Monitor Wait                                                                                                          12  25.9 s  59.9 s   59.9 s 5 m 11 s
Socket Write                                                                                                            4,375 3.16 ms 14.4 ms   156 ms   13.8 s
Java Monitor Blocked                                                                                                       87 17.1 ms  113 ms   113 ms   1.49 s
File Read                                                                                                                  13 3.33 ms 6.06 ms  6.06 ms  43.3 ms
File Write                                                                                                                 14 2.72 ms 13.3 ms  13.3 ms  38.1 ms
```

`socket-reads-by-host`:

```
                                                                      Socket Reads by Host
Host                                                                                                                                           Reads Total Read
--------------------------------------------------------------------------------------------------------------------------------------------- ------ ----------
localhost                                                                                                                                     69,996     1.1 MB
10.1.0.191                                                                                                                                        19     1.5 kB
```

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 405.9/s | 405.9/s | 16236 | 0 | 152.6 ms | 407.6 ms | 569.3 ms | n/a | 671.7 ms | 0.63 cores | kafka 45% · mongo 99% | 0.16 of 1 | mongo 0.99 | 5.09 | 257.5 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 4 s | - |
| r1-head | head | 389.1/s | 389.1/s | 15563 | 0 | 169.0 ms | 387.1 ms | 510.0 ms | n/a | 733.2 ms | 0.66 cores | kafka 46% · mongo 99% | 0.18 of 1 | mongo 0.99 | 5.43 | 269.2 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 1 s | - |
| r2-head | head | 389.1/s | 389.1/s | 15563 | 0 | 177.2 ms | 475.1 ms | 618.5 ms | n/a | 729.1 ms | 0.65 cores | kafka 47% · mongo 99% | 0.20 of 1 | mongo 0.99 | 5.43 | 270.5 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 6 s | - |
| r2-base | base | 405.9/s | 405.9/s | 16236 | 0 | 174.1 ms | 444.4 ms | 544.8 ms | n/a | 630.8 ms | 0.65 cores | kafka 47% · mongo 100% | 0.19 of 1 | mongo 1.00 | 5.23 | 257.6 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 8 s | - |
| r3-base | base | 405.9/s | 405.9/s | 16236 | 0 | 200.7 ms | 456.7 ms | 565.2 ms | n/a | 749.6 ms | 0.66 cores | kafka 48% · mongo 99% | 0.19 of 1 | mongo 0.99 | 5.27 | 254.9 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 5 s | - |
| r3-head | head | 389.1/s | 389.1/s | 15563 | 0 | 154.6 ms | 407.6 ms | 514.0 ms | n/a | 679.9 ms | 0.66 cores | kafka 47% · mongo 99% | 0.17 of 1 | mongo 0.99 | 5.44 | 270.7 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 7 s | - |
| r4-head | head | 389.1/s | 389.1/s | 15563 | 0 | 132.1 ms | 305.2 ms | 387.1 ms | n/a | 524.3 ms | 0.64 cores | kafka 46% · mongo 99% | 0.16 of 1 | mongo 0.99 | 5.36 | 270.8 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 4 s | - |
| r4-base | base | 405.9/s | 405.9/s | 16236 | 0 | 169.0 ms | 417.8 ms | 573.4 ms | n/a | 712.7 ms | 0.62 cores | kafka 45% · mongo 100% | 0.17 of 1 | mongo 1.00 | 5.11 | 257.6 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 3 s | - |
| r5-base | base | 405.9/s | 405.9/s | 16236 | 0 | 199.7 ms | 466.9 ms | 593.9 ms | n/a | 770.0 ms | 0.66 cores | kafka 45% · mongo 100% | 0.19 of 1 | mongo 1.00 | 5.19 | 253.9 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 7 s | - |
| r5-head | head | 389.1/s | 389.1/s | 15563 | 0 | 143.4 ms | 401.4 ms | 528.4 ms | n/a | 626.7 ms | 0.63 cores | kafka 46% · mongo 99% | 0.19 of 1 | mongo 0.99 | 5.35 | 272.7 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 5 s | - |
| e1-base | base | 389.1/s | 389.1/s | 15563 | 0 | 147.5 ms | 426.0 ms | 573.4 ms | n/a | 761.9 ms | 0.63 cores | kafka 45% · mongo 99% | 0.16 of 1 | mongo 0.99 | 5.33 | 264.1 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 11 s | - |
| e1-head | head | 389.1/s | 389.1/s | 15563 | 0 | 137.2 ms | 356.4 ms | 475.1 ms | n/a | 745.5 ms | 0.64 cores | kafka 45% · mongo 99% | 0.15 of 1 | mongo 0.99 | 5.33 | 272.4 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 1 s | - |
| e2-head | head | 389.1/s | 389.1/s | 15563 | 0 | 113.7 ms | 325.6 ms | 462.8 ms | n/a | 741.4 ms | 0.61 cores | kafka 45% · mongo 99% | 0.15 of 1 | mongo 0.99 | 5.27 | 278.6 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 2 s | - |
| e2-base | base | 389.1/s | 389.1/s | 15563 | 0 | 119.8 ms | 337.9 ms | 464.9 ms | n/a | 630.8 ms | 0.61 cores | kafka 45% · mongo 99% | 0.16 of 1 | mongo 0.99 | 5.26 | 263.0 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 4 s | - |
| e3-base | base | 389.1/s | 389.1/s | 15563 | 0 | 121.9 ms | 303.1 ms | 454.7 ms | n/a | 720.9 ms | 0.61 cores | kafka 45% · mongo 99% | 0.15 of 1 | mongo 0.99 | 5.26 | 265.5 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 5 s | - |
| e3-head | head | 389.1/s | 389.1/s | 15563 | 0 | 158.7 ms | 407.6 ms | 524.3 ms | n/a | 704.5 ms | 0.68 cores | kafka 45% · mongo 98% | 0.18 of 1 | mongo 0.98 | 5.42 | 271.9 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 4 s | - |
| e4-head | head | 389.1/s | 389.1/s | 15563 | 0 | 145.4 ms | 385.0 ms | 512.0 ms | n/a | 716.8 ms | 0.64 cores | kafka 45% · mongo 99% | 0.17 of 1 | mongo 0.99 | 5.35 | 271.0 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 7 s | - |
| e4-base | base | 389.1/s | 389.1/s | 15563 | 0 | 116.2 ms | 346.1 ms | 464.9 ms | n/a | 647.2 ms | 0.60 cores | kafka 45% · mongo 99% | 0.18 of 1 | mongo 0.99 | 5.24 | 266.4 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 3 s | - |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 0.959 | 1.107 | 0.950 | 0.896 | - | 1.091 | 1.067 | 1.046 |
| 2 | 0.959 | 1.018 | 1.069 | 1.135 | - | 1.156 | 1.039 | 1.050 |
| 3 | 0.959 | 0.770 | 0.892 | 0.909 | - | 0.907 | 1.032 | 1.062 |
| 4 | 0.959 | 0.782 | 0.730 | 0.675 | - | 0.736 | 1.050 | 1.051 |
| 5 | 0.959 | 0.718 | 0.860 | 0.890 | - | 0.814 | 1.030 | 1.074 |

Pairs at one rate for both arms, 389.1/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 0.931 | 0.837 | 0.829 | - | 0.978 | 1.000 | 1.031 |
| 2 | 1.000 | 0.949 | 0.964 | 0.996 | - | 1.175 | 1.001 | 1.059 |
| 3 | 1.000 | 1.303 | 1.345 | 1.153 | - | 0.977 | 1.030 | 1.024 |
| 4 | 1.000 | 1.251 | 1.112 | 1.101 | - | 1.108 | 1.022 | 1.017 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e3-mongo@100k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 +34.0% (±25.0%) |
| e3-mongo@100k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 +39.7% (±25.0%) |
| e3-mongo@100k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 +50.4% (±25.0%) |
| e3-mongo@100k | e4-head | steady | **unstable** | second half against first, beyond the default limits: p50 +27.2% (±25.0%) |


