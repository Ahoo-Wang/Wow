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