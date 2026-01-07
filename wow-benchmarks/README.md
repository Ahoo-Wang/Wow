# Benchmarks

## BloomFilterIdempotencyChecker

```kotlin
fun createBloomFilterIdempotencyChecker(): BloomFilterIdempotencyChecker {
    return BloomFilterIdempotencyChecker(Duration.ofMinutes(1)) {
        BloomFilter.create(
            Funnels.stringFunnel(Charsets.UTF_8),
            10_000_000,
            0.00001,
        )
    }
}
```

```
Benchmark                                      Mode  Cnt        Score         Error  Units
BloomFilterIdempotencyCheckerBenchmark.check  thrpt    4  6074504.775 ± 2015643.464  ops/s
```

## CommandDispatcher

```
Benchmark                                                          Mode  Cnt       Score        Error  Units
CommandDispatcherBenchmark.send                                   thrpt    4  120849.375 ± 504991.033  ops/s
CommandDispatcherBenchmark.sendAndWaitForProcessed                thrpt    4  117390.257 ± 125131.880  ops/s
CommandDispatcherBenchmark.sendAndWaitForSent                     thrpt    4  125327.973 ± 400123.678  ops/s
NoopCommandDispatcherBenchmark.send                               thrpt    4  570200.578 ± 146216.936  ops/s
NoopCommandDispatcherBenchmark.sendAndWaitForProcessed            thrpt    4  150242.579 ±   4349.189  ops/s
NoopCommandDispatcherBenchmark.sendAndWaitForSent                 thrpt    4  383721.758 ±  76240.773  ops/s
NoopEventStoreCommandDispatcherBenchmark.send                     thrpt    4  544627.541 ±  76768.585  ops/s
NoopEventStoreCommandDispatcherBenchmark.sendAndWaitForProcessed  thrpt    4  149703.118 ±   2681.636  ops/s
NoopEventStoreCommandDispatcherBenchmark.sendAndWaitForSent       thrpt    4  340881.654 ±  97695.782  ops/s
```

```
Benchmark                                                                    Mode  Cnt       Score   Error   Units
RedisCommandDispatcherBenchmark.send                                        thrpt    2  541853.070           ops/s
RedisCommandDispatcherBenchmark.send:async                                  thrpt              NaN             ---
RedisCommandDispatcherBenchmark.send:gc.alloc.rate                          thrpt    2    2075.027          MB/sec
RedisCommandDispatcherBenchmark.send:gc.alloc.rate.norm                     thrpt    2    4317.386            B/op
RedisCommandDispatcherBenchmark.send:gc.count                               thrpt    2      21.000          counts
RedisCommandDispatcherBenchmark.send:gc.time                                thrpt    2    1278.000              ms
RedisCommandDispatcherBenchmark.sendAndWaitForProcessed                     thrpt    2   13236.140           ops/s
RedisCommandDispatcherBenchmark.sendAndWaitForProcessed:async               thrpt              NaN             ---
RedisCommandDispatcherBenchmark.sendAndWaitForProcessed:gc.alloc.rate       thrpt    2     301.091          MB/sec
RedisCommandDispatcherBenchmark.sendAndWaitForProcessed:gc.alloc.rate.norm  thrpt    2   25264.218            B/op
RedisCommandDispatcherBenchmark.sendAndWaitForProcessed:gc.count            thrpt    2       3.000          counts
RedisCommandDispatcherBenchmark.sendAndWaitForProcessed:gc.time             thrpt    2       7.000              ms
RedisCommandDispatcherBenchmark.sendAndWaitForSent                          thrpt    2  385913.164           ops/s
RedisCommandDispatcherBenchmark.sendAndWaitForSent:async                    thrpt              NaN             ---
RedisCommandDispatcherBenchmark.sendAndWaitForSent:gc.alloc.rate            thrpt    2    2728.164          MB/sec
RedisCommandDispatcherBenchmark.sendAndWaitForSent:gc.alloc.rate.norm       thrpt    2    7908.164            B/op
RedisCommandDispatcherBenchmark.sendAndWaitForSent:gc.count                 thrpt    2      25.000          counts
RedisCommandDispatcherBenchmark.sendAndWaitForSent:gc.time                  thrpt    2    2063.000              ms
```

```
Benchmark                                                                    Mode  Cnt       Score   Error   Units
MongoCommandDispatcherBenchmark.send                                        thrpt    2  435566.941           ops/s
MongoCommandDispatcherBenchmark.send:async                                  thrpt              NaN             ---
MongoCommandDispatcherBenchmark.send:gc.alloc.rate                          thrpt    2    2083.852          MB/sec
MongoCommandDispatcherBenchmark.send:gc.alloc.rate.norm                     thrpt    2    5528.201            B/op
MongoCommandDispatcherBenchmark.send:gc.count                               thrpt    2      17.000          counts
MongoCommandDispatcherBenchmark.send:gc.time                                thrpt    2    1257.000              ms
MongoCommandDispatcherBenchmark.sendAndWaitForProcessed                     thrpt    2    8163.650           ops/s
MongoCommandDispatcherBenchmark.sendAndWaitForProcessed:async               thrpt              NaN             ---
MongoCommandDispatcherBenchmark.sendAndWaitForProcessed:gc.alloc.rate       thrpt    2     542.246          MB/sec
MongoCommandDispatcherBenchmark.sendAndWaitForProcessed:gc.alloc.rate.norm  thrpt    2   74865.353            B/op
MongoCommandDispatcherBenchmark.sendAndWaitForProcessed:gc.count            thrpt    2       3.000          counts
MongoCommandDispatcherBenchmark.sendAndWaitForProcessed:gc.time             thrpt    2      97.000              ms
MongoCommandDispatcherBenchmark.sendAndWaitForSent                          thrpt    2  306472.694           ops/s
MongoCommandDispatcherBenchmark.sendAndWaitForSent:async                    thrpt              NaN             ---
MongoCommandDispatcherBenchmark.sendAndWaitForSent:gc.alloc.rate            thrpt    2    2544.236          MB/sec
MongoCommandDispatcherBenchmark.sendAndWaitForSent:gc.alloc.rate.norm       thrpt    2    9730.032            B/op
MongoCommandDispatcherBenchmark.sendAndWaitForSent:gc.count                 thrpt    2      18.000          counts
MongoCommandDispatcherBenchmark.sendAndWaitForSent:gc.time                  thrpt    2    1298.000              ms
```