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
Benchmark                                                                                            Mode  Cnt       Score   Error   Units
m.a.w.modeling.CommandDispatcherBenchmark.send                                                      thrpt    2  326620.365           ops/s
m.a.w.modeling.CommandDispatcherBenchmark.send:async                                                thrpt              NaN             ---
m.a.w.modeling.CommandDispatcherBenchmark.send:gc.alloc.rate                                        thrpt    2    4334.039          MB/sec
m.a.w.modeling.CommandDispatcherBenchmark.send:gc.alloc.rate.norm                                   thrpt    2   15029.677            B/op
m.a.w.modeling.CommandDispatcherBenchmark.send:gc.count                                             thrpt    2     160.000          counts
m.a.w.modeling.CommandDispatcherBenchmark.send:gc.time                                              thrpt    2    1301.000              ms
m.a.w.modeling.CommandDispatcherBenchmark.sendAndWaitForProcessed                                   thrpt    2  135709.022           ops/s
m.a.w.modeling.CommandDispatcherBenchmark.sendAndWaitForProcessed:async                             thrpt              NaN             ---
m.a.w.modeling.CommandDispatcherBenchmark.sendAndWaitForProcessed:gc.alloc.rate                     thrpt    2    2265.816          MB/sec
m.a.w.modeling.CommandDispatcherBenchmark.sendAndWaitForProcessed:gc.alloc.rate.norm                thrpt    2   19202.684            B/op
m.a.w.modeling.CommandDispatcherBenchmark.sendAndWaitForProcessed:gc.count                          thrpt    2      15.000          counts
m.a.w.modeling.CommandDispatcherBenchmark.sendAndWaitForProcessed:gc.time                           thrpt    2     579.000              ms
m.a.w.modeling.CommandDispatcherBenchmark.sendAndWaitForSent                                        thrpt    2  231489.346           ops/s
m.a.w.modeling.CommandDispatcherBenchmark.sendAndWaitForSent:async                                  thrpt              NaN             ---
m.a.w.modeling.CommandDispatcherBenchmark.sendAndWaitForSent:gc.alloc.rate                          thrpt    2    3811.580          MB/sec
m.a.w.modeling.CommandDispatcherBenchmark.sendAndWaitForSent:gc.alloc.rate.norm                     thrpt    2   18605.516            B/op
m.a.w.modeling.CommandDispatcherBenchmark.sendAndWaitForSent:gc.count                               thrpt    2      63.000          counts
m.a.w.modeling.CommandDispatcherBenchmark.sendAndWaitForSent:gc.time                                thrpt    2    1041.000              ms
m.a.w.modeling.NoopCommandDispatcherBenchmark.send                                                  thrpt    2  639539.709           ops/s
m.a.w.modeling.NoopCommandDispatcherBenchmark.send:async                                            thrpt              NaN             ---
m.a.w.modeling.NoopCommandDispatcherBenchmark.send:gc.alloc.rate                                    thrpt    2    7481.951          MB/sec
m.a.w.modeling.NoopCommandDispatcherBenchmark.send:gc.alloc.rate.norm                               thrpt    2   13463.081            B/op
m.a.w.modeling.NoopCommandDispatcherBenchmark.send:gc.count                                         thrpt    2      34.000          counts
m.a.w.modeling.NoopCommandDispatcherBenchmark.send:gc.time                                          thrpt    2     267.000              ms
m.a.w.modeling.NoopCommandDispatcherBenchmark.sendAndWaitForProcessed                               thrpt    2  149432.225           ops/s
m.a.w.modeling.NoopCommandDispatcherBenchmark.sendAndWaitForProcessed:async                         thrpt              NaN             ---
m.a.w.modeling.NoopCommandDispatcherBenchmark.sendAndWaitForProcessed:gc.alloc.rate                 thrpt    2    2356.847          MB/sec
m.a.w.modeling.NoopCommandDispatcherBenchmark.sendAndWaitForProcessed:gc.alloc.rate.norm            thrpt    2   18161.580            B/op
m.a.w.modeling.NoopCommandDispatcherBenchmark.sendAndWaitForProcessed:gc.count                      thrpt    2      10.000          counts
m.a.w.modeling.NoopCommandDispatcherBenchmark.sendAndWaitForProcessed:gc.time                       thrpt    2      21.000              ms
m.a.w.modeling.NoopCommandDispatcherBenchmark.sendAndWaitForSent                                    thrpt    2  462755.237           ops/s
m.a.w.modeling.NoopCommandDispatcherBenchmark.sendAndWaitForSent:async                              thrpt              NaN             ---
m.a.w.modeling.NoopCommandDispatcherBenchmark.sendAndWaitForSent:gc.alloc.rate                      thrpt    2    7045.290          MB/sec
m.a.w.modeling.NoopCommandDispatcherBenchmark.sendAndWaitForSent:gc.alloc.rate.norm                 thrpt    2   17567.579            B/op
m.a.w.modeling.NoopCommandDispatcherBenchmark.sendAndWaitForSent:gc.count                           thrpt    2      32.000          counts
m.a.w.modeling.NoopCommandDispatcherBenchmark.sendAndWaitForSent:gc.time                            thrpt    2      45.000              ms
m.a.w.modeling.NoopEventStoreCommandDispatcherBenchmark.send                                        thrpt    2  559871.911           ops/s
m.a.w.modeling.NoopEventStoreCommandDispatcherBenchmark.send:async                                  thrpt              NaN             ---
m.a.w.modeling.NoopEventStoreCommandDispatcherBenchmark.send:gc.alloc.rate                          thrpt    2    7110.615          MB/sec
m.a.w.modeling.NoopEventStoreCommandDispatcherBenchmark.send:gc.alloc.rate.norm                     thrpt    2   14637.444            B/op
m.a.w.modeling.NoopEventStoreCommandDispatcherBenchmark.send:gc.count                               thrpt    2      32.000          counts
m.a.w.modeling.NoopEventStoreCommandDispatcherBenchmark.send:gc.time                                thrpt    2      81.000              ms
m.a.w.modeling.NoopEventStoreCommandDispatcherBenchmark.sendAndWaitForProcessed                     thrpt    2  151343.235           ops/s
m.a.w.modeling.NoopEventStoreCommandDispatcherBenchmark.sendAndWaitForProcessed:async               thrpt              NaN             ---
m.a.w.modeling.NoopEventStoreCommandDispatcherBenchmark.sendAndWaitForProcessed:gc.alloc.rate       thrpt    2    2468.360          MB/sec
m.a.w.modeling.NoopEventStoreCommandDispatcherBenchmark.sendAndWaitForProcessed:gc.alloc.rate.norm  thrpt    2   18775.357            B/op
m.a.w.modeling.NoopEventStoreCommandDispatcherBenchmark.sendAndWaitForProcessed:gc.count            thrpt    2      11.000          counts
m.a.w.modeling.NoopEventStoreCommandDispatcherBenchmark.sendAndWaitForProcessed:gc.time             thrpt    2      24.000              ms
m.a.w.modeling.NoopEventStoreCommandDispatcherBenchmark.sendAndWaitForSent                          thrpt    2  374889.450           ops/s
m.a.w.modeling.NoopEventStoreCommandDispatcherBenchmark.sendAndWaitForSent:async                    thrpt              NaN             ---
m.a.w.modeling.NoopEventStoreCommandDispatcherBenchmark.sendAndWaitForSent:gc.alloc.rate            thrpt    2    5829.989          MB/sec
m.a.w.modeling.NoopEventStoreCommandDispatcherBenchmark.sendAndWaitForSent:gc.alloc.rate.norm       thrpt    2   17923.198            B/op
m.a.w.modeling.NoopEventStoreCommandDispatcherBenchmark.sendAndWaitForSent:gc.count                 thrpt    2      26.000          counts
m.a.w.modeling.NoopEventStoreCommandDispatcherBenchmark.sendAndWaitForSent:gc.time                  thrpt    2      45.000              ms
m.a.w.mongo.MongoCommandDispatcherBenchmark.send                                                    thrpt    2  474081.500           ops/s
m.a.w.mongo.MongoCommandDispatcherBenchmark.send:async                                              thrpt              NaN             ---
m.a.w.mongo.MongoCommandDispatcherBenchmark.send:gc.alloc.rate                                      thrpt    2    2202.128          MB/sec
m.a.w.mongo.MongoCommandDispatcherBenchmark.send:gc.alloc.rate.norm                                 thrpt    2    5321.941            B/op
m.a.w.mongo.MongoCommandDispatcherBenchmark.send:gc.count                                           thrpt    2      18.000          counts
m.a.w.mongo.MongoCommandDispatcherBenchmark.send:gc.time                                            thrpt    2    1266.000              ms
m.a.w.mongo.MongoCommandDispatcherBenchmark.sendAndWaitForProcessed                                 thrpt    2    8707.898           ops/s
m.a.w.mongo.MongoCommandDispatcherBenchmark.sendAndWaitForProcessed:async                           thrpt              NaN             ---
m.a.w.mongo.MongoCommandDispatcherBenchmark.sendAndWaitForProcessed:gc.alloc.rate                   thrpt    2     572.050          MB/sec
m.a.w.mongo.MongoCommandDispatcherBenchmark.sendAndWaitForProcessed:gc.alloc.rate.norm              thrpt    2   74733.243            B/op
m.a.w.mongo.MongoCommandDispatcherBenchmark.sendAndWaitForProcessed:gc.count                        thrpt    2       3.000          counts
m.a.w.mongo.MongoCommandDispatcherBenchmark.sendAndWaitForProcessed:gc.time                         thrpt    2      41.000              ms
m.a.w.mongo.MongoCommandDispatcherBenchmark.sendAndWaitForSent                                      thrpt    2  354730.437           ops/s
m.a.w.mongo.MongoCommandDispatcherBenchmark.sendAndWaitForSent:async                                thrpt              NaN             ---
m.a.w.mongo.MongoCommandDispatcherBenchmark.sendAndWaitForSent:gc.alloc.rate                        thrpt    2    2942.639          MB/sec
m.a.w.mongo.MongoCommandDispatcherBenchmark.sendAndWaitForSent:gc.alloc.rate.norm                   thrpt    2    9546.036            B/op
m.a.w.mongo.MongoCommandDispatcherBenchmark.sendAndWaitForSent:gc.count                             thrpt    2      20.000          counts
m.a.w.mongo.MongoCommandDispatcherBenchmark.sendAndWaitForSent:gc.time                              thrpt    2    1271.000              ms
m.a.w.redis.RedisCommandDispatcherBenchmark.send                                                    thrpt    2  609025.464           ops/s
m.a.w.redis.RedisCommandDispatcherBenchmark.send:async                                              thrpt              NaN             ---
m.a.w.redis.RedisCommandDispatcherBenchmark.send:gc.alloc.rate                                      thrpt    2    2256.571          MB/sec
m.a.w.redis.RedisCommandDispatcherBenchmark.send:gc.alloc.rate.norm                                 thrpt    2    4160.436            B/op
m.a.w.redis.RedisCommandDispatcherBenchmark.send:gc.count                                           thrpt    2      27.000          counts
m.a.w.redis.RedisCommandDispatcherBenchmark.send:gc.time                                            thrpt    2    1371.000              ms
m.a.w.redis.RedisCommandDispatcherBenchmark.sendAndWaitForProcessed                                 thrpt    2   13460.565           ops/s
m.a.w.redis.RedisCommandDispatcherBenchmark.sendAndWaitForProcessed:async                           thrpt              NaN             ---
m.a.w.redis.RedisCommandDispatcherBenchmark.sendAndWaitForProcessed:gc.alloc.rate                   thrpt    2     305.752          MB/sec
m.a.w.redis.RedisCommandDispatcherBenchmark.sendAndWaitForProcessed:gc.alloc.rate.norm              thrpt    2   25246.580            B/op
m.a.w.redis.RedisCommandDispatcherBenchmark.sendAndWaitForProcessed:gc.count                        thrpt    2       3.000          counts
m.a.w.redis.RedisCommandDispatcherBenchmark.sendAndWaitForProcessed:gc.time                         thrpt    2       7.000              ms
m.a.w.redis.RedisCommandDispatcherBenchmark.sendAndWaitForSent                                      thrpt    2  421133.647           ops/s
m.a.w.redis.RedisCommandDispatcherBenchmark.sendAndWaitForSent:async                                thrpt              NaN             ---
m.a.w.redis.RedisCommandDispatcherBenchmark.sendAndWaitForSent:gc.alloc.rate                        thrpt    2    2941.960          MB/sec
m.a.w.redis.RedisCommandDispatcherBenchmark.sendAndWaitForSent:gc.alloc.rate.norm                   thrpt    2    7720.249            B/op
m.a.w.redis.RedisCommandDispatcherBenchmark.sendAndWaitForSent:gc.count                             thrpt    2      27.000          counts
m.a.w.redis.RedisCommandDispatcherBenchmark.sendAndWaitForSent:gc.time                              thrpt    2    1561.000              ms
```



```
Benchmark                         Mode  Cnt      Score      Error  Units
MongoEventStoreBenchmark.append  thrpt    4  17603.362 ± 2155.153  ops/s
```

```
Benchmark                         Mode  Cnt      Score      Error  Units
RedisEventStoreBenchmark.append  thrpt    4  17551.479 ± 1103.835  ops/s
```

```
Benchmark                                                                                            Mode  Cnt       Score        Error   Units
m.a.w.modeling.InMemoryCommandDispatcherBenchmark.sendAndWaitForProcessed                           thrpt    4   81566.387 ± 135364.825   ops/s
m.a.w.modeling.InMemoryCommandDispatcherBenchmark.sendAndWaitForProcessed:async                     thrpt              NaN                  ---
m.a.w.modeling.InMemoryCommandDispatcherBenchmark.sendAndWaitForProcessed:gc.alloc.rate             thrpt    4    1413.925 ±   2432.540  MB/sec
m.a.w.modeling.InMemoryCommandDispatcherBenchmark.sendAndWaitForProcessed:gc.alloc.rate.norm        thrpt    4   19063.307 ±    333.514    B/op
m.a.w.modeling.InMemoryCommandDispatcherBenchmark.sendAndWaitForProcessed:gc.count                  thrpt    4      54.000               counts
m.a.w.modeling.InMemoryCommandDispatcherBenchmark.sendAndWaitForProcessed:gc.time                   thrpt    4    5152.000                   ms
m.a.w.modeling.NoopCommandDispatcherBenchmark.sendAndWaitForProcessed                               thrpt    4  115880.094 ±  49217.328   ops/s
m.a.w.modeling.NoopCommandDispatcherBenchmark.sendAndWaitForProcessed:async                         thrpt              NaN                  ---
m.a.w.modeling.NoopCommandDispatcherBenchmark.sendAndWaitForProcessed:gc.alloc.rate                 thrpt    4    1878.657 ±    920.858  MB/sec
m.a.w.modeling.NoopCommandDispatcherBenchmark.sendAndWaitForProcessed:gc.alloc.rate.norm            thrpt    4   17893.769 ±    181.773    B/op
m.a.w.modeling.NoopCommandDispatcherBenchmark.sendAndWaitForProcessed:gc.count                      thrpt    4      32.000               counts
m.a.w.modeling.NoopCommandDispatcherBenchmark.sendAndWaitForProcessed:gc.time                       thrpt    4     141.000                   ms
m.a.w.modeling.NoopEventStoreCommandDispatcherBenchmark.sendAndWaitForProcessed                     thrpt    4  106988.283 ±  98031.566   ops/s
m.a.w.modeling.NoopEventStoreCommandDispatcherBenchmark.sendAndWaitForProcessed:async               thrpt              NaN                  ---
m.a.w.modeling.NoopEventStoreCommandDispatcherBenchmark.sendAndWaitForProcessed:gc.alloc.rate       thrpt    4    1793.532 ±   1573.972  MB/sec
m.a.w.modeling.NoopEventStoreCommandDispatcherBenchmark.sendAndWaitForProcessed:gc.alloc.rate.norm  thrpt    4   18517.753 ±    351.075    B/op
m.a.w.modeling.NoopEventStoreCommandDispatcherBenchmark.sendAndWaitForProcessed:gc.count            thrpt    4      31.000               counts
m.a.w.modeling.NoopEventStoreCommandDispatcherBenchmark.sendAndWaitForProcessed:gc.time             thrpt    4     144.000                   ms
m.a.w.mongo.MongoCommandDispatcherBenchmark.sendAndWaitForProcessed                                 thrpt    4   11298.723 ±   4908.473   ops/s
m.a.w.mongo.MongoCommandDispatcherBenchmark.sendAndWaitForProcessed:async                           thrpt              NaN                  ---
m.a.w.mongo.MongoCommandDispatcherBenchmark.sendAndWaitForProcessed:gc.alloc.rate                   thrpt    4     771.038 ±   1179.250  MB/sec
m.a.w.mongo.MongoCommandDispatcherBenchmark.sendAndWaitForProcessed:gc.alloc.rate.norm              thrpt    4   75066.628 ±  94723.625    B/op
m.a.w.mongo.MongoCommandDispatcherBenchmark.sendAndWaitForProcessed:gc.count                        thrpt    4      15.000               counts
m.a.w.mongo.MongoCommandDispatcherBenchmark.sendAndWaitForProcessed:gc.time                         thrpt    4      73.000                   ms
m.a.w.redis.RedisCommandDispatcherBenchmark.sendAndWaitForProcessed                                 thrpt    4   18607.897 ±   7482.066   ops/s
m.a.w.redis.RedisCommandDispatcherBenchmark.sendAndWaitForProcessed:async                           thrpt              NaN                  ---
m.a.w.redis.RedisCommandDispatcherBenchmark.sendAndWaitForProcessed:gc.alloc.rate                   thrpt    4     428.985 ±   1448.394  MB/sec
m.a.w.redis.RedisCommandDispatcherBenchmark.sendAndWaitForProcessed:gc.alloc.rate.norm              thrpt    4   25060.143 ±  78008.893    B/op
m.a.w.redis.RedisCommandDispatcherBenchmark.sendAndWaitForProcessed:gc.count                        thrpt    4      12.000               counts
m.a.w.redis.RedisCommandDispatcherBenchmark.sendAndWaitForProcessed:gc.time                         thrpt    4      91.000                   ms
```

```
Benchmark                                                                                            Mode  Cnt       Score        Error   Units
m.a.w.modeling.InMemoryCommandDispatcherBenchmark.sendAndWaitForProcessed                           thrpt    4   95147.678 ± 159025.508   ops/s
m.a.w.modeling.InMemoryCommandDispatcherBenchmark.sendAndWaitForProcessed:async                     thrpt              NaN                  ---
m.a.w.modeling.InMemoryCommandDispatcherBenchmark.sendAndWaitForProcessed:gc.alloc.rate             thrpt    4    1665.520 ±   2896.498  MB/sec
m.a.w.modeling.InMemoryCommandDispatcherBenchmark.sendAndWaitForProcessed:gc.alloc.rate.norm        thrpt    4   19251.644 ±    168.551    B/op
m.a.w.modeling.InMemoryCommandDispatcherBenchmark.sendAndWaitForProcessed:gc.count                  thrpt    4      70.000               counts
m.a.w.modeling.InMemoryCommandDispatcherBenchmark.sendAndWaitForProcessed:gc.time                   thrpt    4    5276.000                   ms
m.a.w.modeling.NoopCommandDispatcherBenchmark.sendAndWaitForProcessed                               thrpt    4  158474.214 ±  56980.191   ops/s
m.a.w.modeling.NoopCommandDispatcherBenchmark.sendAndWaitForProcessed:async                         thrpt              NaN                  ---
m.a.w.modeling.NoopCommandDispatcherBenchmark.sendAndWaitForProcessed:gc.alloc.rate                 thrpt    4    2610.101 ±   1088.094  MB/sec
m.a.w.modeling.NoopCommandDispatcherBenchmark.sendAndWaitForProcessed:gc.alloc.rate.norm            thrpt    4   18167.602 ±    102.164    B/op
m.a.w.modeling.NoopCommandDispatcherBenchmark.sendAndWaitForProcessed:gc.count                      thrpt    4      46.000               counts
m.a.w.modeling.NoopCommandDispatcherBenchmark.sendAndWaitForProcessed:gc.time                       thrpt    4      98.000                   ms
m.a.w.modeling.NoopEventStoreCommandDispatcherBenchmark.sendAndWaitForProcessed                     thrpt    4  156687.586 ±  41899.244   ops/s
m.a.w.modeling.NoopEventStoreCommandDispatcherBenchmark.sendAndWaitForProcessed:async               thrpt              NaN                  ---
m.a.w.modeling.NoopEventStoreCommandDispatcherBenchmark.sendAndWaitForProcessed:gc.alloc.rate       thrpt    4    2670.397 ±    804.506  MB/sec
m.a.w.modeling.NoopEventStoreCommandDispatcherBenchmark.sendAndWaitForProcessed:gc.alloc.rate.norm  thrpt    4   18800.993 ±     76.361    B/op
m.a.w.modeling.NoopEventStoreCommandDispatcherBenchmark.sendAndWaitForProcessed:gc.count            thrpt    4      46.000               counts
m.a.w.modeling.NoopEventStoreCommandDispatcherBenchmark.sendAndWaitForProcessed:gc.time             thrpt    4     110.000                   ms
m.a.w.mongo.MongoCommandDispatcherBenchmark.sendAndWaitForProcessed                                 thrpt    4   11477.121 ±    677.404   ops/s
m.a.w.mongo.MongoCommandDispatcherBenchmark.sendAndWaitForProcessed:async                           thrpt              NaN                  ---
m.a.w.mongo.MongoCommandDispatcherBenchmark.sendAndWaitForProcessed:gc.alloc.rate                   thrpt    4     788.320 ±   1282.248  MB/sec
m.a.w.mongo.MongoCommandDispatcherBenchmark.sendAndWaitForProcessed:gc.alloc.rate.norm              thrpt    4   75242.578 ±  94418.820    B/op
m.a.w.mongo.MongoCommandDispatcherBenchmark.sendAndWaitForProcessed:gc.count                        thrpt    4      16.000               counts
m.a.w.mongo.MongoCommandDispatcherBenchmark.sendAndWaitForProcessed:gc.time                         thrpt    4     172.000                   ms
m.a.w.redis.RedisCommandDispatcherBenchmark.sendAndWaitForProcessed                                 thrpt    4   21617.870 ±   6169.782   ops/s
m.a.w.redis.RedisCommandDispatcherBenchmark.sendAndWaitForProcessed:async                           thrpt              NaN                  ---
m.a.w.redis.RedisCommandDispatcherBenchmark.sendAndWaitForProcessed:gc.alloc.rate                   thrpt    4     497.741 ±   1585.235  MB/sec
m.a.w.redis.RedisCommandDispatcherBenchmark.sendAndWaitForProcessed:gc.alloc.rate.norm              thrpt    4   25295.388 ±  78552.955    B/op
m.a.w.redis.RedisCommandDispatcherBenchmark.sendAndWaitForProcessed:gc.count                        thrpt    4      14.000               counts
m.a.w.redis.RedisCommandDispatcherBenchmark.sendAndWaitForProcessed:gc.time                         thrpt    4      64.000                   ms
```