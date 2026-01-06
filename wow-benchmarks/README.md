# Benchmarks


## CommandDispatcher

``` kotlin
jmh {
    includes.set(listOf(".*CommandDispatcher.*"))
    warmupIterations.set(1)
    iterations.set(2)
    resultFormat.set("json")
    fork.set(2)
}
```

### Slim No SendDomainEventStreamFilter

```
Benchmark                                                Mode  Cnt       Score        Error  Units
CommandDispatcherBenchmark.send                         thrpt    4   88281.176 ± 650103.720  ops/s
CommandDispatcherBenchmark.sendAndWaitForProcessed      thrpt    4   77924.727 ±  13752.275  ops/s
CommandDispatcherBenchmark.sendAndWaitForSent           thrpt    4  148323.036 ± 523277.215  ops/s
SlimCommandDispatcherBenchmark.send                     thrpt    4  683194.122 ±  82590.805  ops/s
SlimCommandDispatcherBenchmark.sendAndWaitForProcessed  thrpt    4   91003.625 ±  13953.030  ops/s
SlimCommandDispatcherBenchmark.sendAndWaitForSent       thrpt    4  524270.927 ±  69727.685  ops/s
```

### Slim With SendDomainEventStreamFilter

```
Benchmark                                                Mode  Cnt       Score        Error  Units
CommandDispatcherBenchmark.send                         thrpt    4   83285.694 ± 583938.459  ops/s
CommandDispatcherBenchmark.sendAndWaitForProcessed      thrpt    4   75759.901 ±  30028.019  ops/s
CommandDispatcherBenchmark.sendAndWaitForSent           thrpt    4   89760.009 ± 637309.000  ops/s
SlimCommandDispatcherBenchmark.send                     thrpt    4  708670.483 ±  99813.083  ops/s
SlimCommandDispatcherBenchmark.sendAndWaitForProcessed  thrpt    4   91055.512 ±   9354.977  ops/s
SlimCommandDispatcherBenchmark.sendAndWaitForSent       thrpt    4  529142.273 ±  24664.271  ops/s
```

#### 3 Threads

```kotlin
jmh {
    includes.set(listOf(".*CommandDispatcher.*"))
    warmupIterations.set(1)
    iterations.set(2)
    resultFormat.set("json")
    threads.set(3)
    fork.set(2)
}
```

```
Benchmark                                                Mode  Cnt       Score        Error  Units
CommandDispatcherBenchmark.send                         thrpt    4   96285.847 ± 714175.945  ops/s
CommandDispatcherBenchmark.sendAndWaitForProcessed      thrpt    4  132106.627 ± 149660.404  ops/s
CommandDispatcherBenchmark.sendAndWaitForSent           thrpt    4   99977.271 ± 560176.172  ops/s
SlimCommandDispatcherBenchmark.send                     thrpt    4  635241.154 ± 161553.222  ops/s
SlimCommandDispatcherBenchmark.sendAndWaitForProcessed  thrpt    4  177562.838 ±   1603.850  ops/s
SlimCommandDispatcherBenchmark.sendAndWaitForSent       thrpt    4  431255.142 ±  84366.167  ops/s
```

#### 5 Threads

> DEFAULT_PARALLELISM = (100 * Runtime.getRuntime().availableProcessors())

``` kotlin
jmh {
    includes.set(listOf(".*CommandDispatcher.*"))
    warmupIterations.set(1)
    iterations.set(2)
    resultFormat.set("json")
    threads.set(5)
    fork.set(2)
}
```

```
Benchmark                                                Mode  Cnt       Score        Error  Units
CommandDispatcherBenchmark.send                         thrpt    4  103148.431 ± 717721.380  ops/s
CommandDispatcherBenchmark.sendAndWaitForProcessed      thrpt    4  125318.630 ±  98510.743  ops/s
CommandDispatcherBenchmark.sendAndWaitForSent           thrpt    4  124923.525 ± 619647.270  ops/s
SlimCommandDispatcherBenchmark.send                     thrpt    4  570971.189 ±  33163.429  ops/s
SlimCommandDispatcherBenchmark.sendAndWaitForProcessed  thrpt    4  146931.242 ±   4098.642  ops/s
SlimCommandDispatcherBenchmark.sendAndWaitForSent       thrpt    4  408651.533 ±  64673.120  ops/s
```

##### DEFAULT_PARALLELISM = Runtime.getRuntime().availableProcessors()

```
Benchmark                                                Mode  Cnt       Score        Error  Units
CommandDispatcherBenchmark.send                         thrpt    4  146484.463 ± 981711.603  ops/s
CommandDispatcherBenchmark.sendAndWaitForProcessed      thrpt    4  117799.915 ±  99280.087  ops/s
CommandDispatcherBenchmark.sendAndWaitForSent           thrpt    4  104997.998 ± 556144.474  ops/s
SlimCommandDispatcherBenchmark.send                     thrpt    4  519369.852 ± 827521.927  ops/s
SlimCommandDispatcherBenchmark.sendAndWaitForProcessed  thrpt    4  143839.161 ±  13268.685  ops/s
SlimCommandDispatcherBenchmark.sendAndWaitForSent       thrpt    4  405821.413 ± 198200.781  ops/s
```

##### DEFAULT_PARALLELISM = 8 * Runtime.getRuntime().availableProcessors()

```
Benchmark                                                Mode  Cnt       Score        Error  Units
CommandDispatcherBenchmark.send                         thrpt    4  142801.121 ± 923214.550  ops/s
CommandDispatcherBenchmark.sendAndWaitForProcessed      thrpt    4  118501.913 ±  49404.764  ops/s
CommandDispatcherBenchmark.sendAndWaitForSent           thrpt    4  111990.148 ± 593036.263  ops/s
SlimCommandDispatcherBenchmark.send                     thrpt    4  595420.256 ± 121848.689  ops/s
SlimCommandDispatcherBenchmark.sendAndWaitForProcessed  thrpt    4  150609.500 ±   8365.146  ops/s
SlimCommandDispatcherBenchmark.sendAndWaitForSent       thrpt    4  425006.640 ± 235034.696  ops/s
```

##### DEFAULT_PARALLELISM = 32 * Runtime.getRuntime().availableProcessors()

```
Benchmark                                                Mode  Cnt       Score        Error  Units
CommandDispatcherBenchmark.send                         thrpt    4  114078.724 ± 753123.122  ops/s
CommandDispatcherBenchmark.sendAndWaitForProcessed      thrpt    4  128024.887 ±  63482.638  ops/s
CommandDispatcherBenchmark.sendAndWaitForSent           thrpt    4   99190.062 ± 620176.514  ops/s
SlimCommandDispatcherBenchmark.send                     thrpt    4  562982.555 ± 229139.201  ops/s
SlimCommandDispatcherBenchmark.sendAndWaitForProcessed  thrpt    4  148431.358 ±   9836.173  ops/s
SlimCommandDispatcherBenchmark.sendAndWaitForSent       thrpt    4  410443.996 ±  54783.428  ops/s
```

##### DEFAULT_PARALLELISM = 64 * Runtime.getRuntime().availableProcessors()

```
Benchmark                                                Mode  Cnt       Score         Error  Units
CommandDispatcherBenchmark.send                         thrpt    4  161958.754 ± 1082822.922  ops/s
CommandDispatcherBenchmark.sendAndWaitForProcessed      thrpt    4  115881.014 ±  147733.115  ops/s
CommandDispatcherBenchmark.sendAndWaitForSent           thrpt    4  152997.153 ±  136371.719  ops/s
SlimCommandDispatcherBenchmark.send                     thrpt    4  529037.184 ±  298950.545  ops/s
SlimCommandDispatcherBenchmark.sendAndWaitForProcessed  thrpt    4  132218.938 ±   22188.495  ops/s
SlimCommandDispatcherBenchmark.sendAndWaitForSent       thrpt    4  383182.471 ±  229221.357  ops/s
```

##### Schedulers.immediate()

```kotlin
class DefaultAggregateSchedulerSupplier(
    override val name: String,
    override val parallelism: Int = Schedulers.DEFAULT_POOL_SIZE
) : AggregateSchedulerSupplier,
    ParallelismCapable,
    Named {

    private val schedulers: MutableMap<MaterializedNamedAggregate, Scheduler> = ConcurrentHashMap()

    override fun getOrInitialize(namedAggregate: NamedAggregate): Scheduler =
        schedulers.computeIfAbsent(namedAggregate.materialize()) { _ ->
            Schedulers.immediate()
        }
}
```

```
Benchmark                                                Mode  Cnt       Score       Error  Units
CommandDispatcherBenchmark.send                         thrpt    4  164629.161 ± 99694.918  ops/s
CommandDispatcherBenchmark.sendAndWaitForProcessed      thrpt    4  118616.058 ± 36672.040  ops/s
CommandDispatcherBenchmark.sendAndWaitForSent           thrpt    4  116059.257 ± 75048.103  ops/s
SlimCommandDispatcherBenchmark.send                     thrpt    4  237312.603 ±  5845.890  ops/s
SlimCommandDispatcherBenchmark.sendAndWaitForProcessed  thrpt    4  161232.552 ±  7456.641  ops/s
SlimCommandDispatcherBenchmark.sendAndWaitForSent       thrpt    4  167484.502 ±  4939.888  ops/s
```

#### Schedulers.single()

```kotlin
class DefaultAggregateSchedulerSupplier(
    override val name: String,
    override val parallelism: Int = Schedulers.DEFAULT_POOL_SIZE
) : AggregateSchedulerSupplier,
    ParallelismCapable,
    Named {

    private val schedulers: MutableMap<MaterializedNamedAggregate, Scheduler> = ConcurrentHashMap()

    override fun getOrInitialize(namedAggregate: NamedAggregate): Scheduler =
        schedulers.computeIfAbsent(namedAggregate.materialize()) { _ ->
            Schedulers.single()
        }
}
```
```
Benchmark                                                Mode  Cnt       Score         Error  Units
CommandDispatcherBenchmark.send                         thrpt    4  165465.422 ± 1156388.827  ops/s
CommandDispatcherBenchmark.sendAndWaitForProcessed      thrpt    4  143460.060 ±  156802.508  ops/s
CommandDispatcherBenchmark.sendAndWaitForSent           thrpt    4  124674.055 ±  849633.058  ops/s
SlimCommandDispatcherBenchmark.send                     thrpt    4  246935.861 ± 1737522.133  ops/s
SlimCommandDispatcherBenchmark.sendAndWaitForProcessed  thrpt    4  197871.546 ±   54295.433  ops/s
SlimCommandDispatcherBenchmark.sendAndWaitForSent       thrpt    4  362466.719 ±  891147.073  ops/s
```

#### Schedulers.parallel()

```kotlin
class DefaultAggregateSchedulerSupplier(
    override val name: String,
    override val parallelism: Int = Schedulers.DEFAULT_POOL_SIZE
) : AggregateSchedulerSupplier,
    ParallelismCapable,
    Named {

    private val schedulers: MutableMap<MaterializedNamedAggregate, Scheduler> = ConcurrentHashMap()

    override fun getOrInitialize(namedAggregate: NamedAggregate): Scheduler =
        schedulers.computeIfAbsent(namedAggregate.materialize()) { _ ->
            Schedulers.parallel()
        }
}
```

```
Benchmark                                                Mode  Cnt       Score        Error  Units
CommandDispatcherBenchmark.send                         thrpt    4  128447.737 ± 887970.370  ops/s
CommandDispatcherBenchmark.sendAndWaitForProcessed      thrpt    4  124049.619 ±  83570.214  ops/s
CommandDispatcherBenchmark.sendAndWaitForSent           thrpt    4  103273.711 ± 528985.111  ops/s
SlimCommandDispatcherBenchmark.send                     thrpt    4  580170.792 ± 186107.600  ops/s
SlimCommandDispatcherBenchmark.sendAndWaitForProcessed  thrpt    4  151805.666 ±   5977.735  ops/s
SlimCommandDispatcherBenchmark.sendAndWaitForSent       thrpt    4  435818.388 ±  50340.081  ops/s
```