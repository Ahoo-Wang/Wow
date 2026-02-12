# 聚合调度器

聚合调度器为每个聚合提供专用的 Reactor Scheduler，用于控制并发执行和资源分配。

## 调度器供应器

聚合调度器供应器为每个聚合提供或创建专用的调度器。

```kotlin
fun interface AggregateSchedulerSupplier {
    fun getOrInitialize(namedAggregate: NamedAggregate): Scheduler
}
```

### 默认实现

```kotlin
class DefaultAggregateSchedulerSupplier(
    override val name: String
) : AggregateSchedulerSupplier, Named {

    private val schedulers: MutableMap<MaterializedNamedAggregate, Scheduler> = ConcurrentHashMap()

    override fun getOrInitialize(namedAggregate: NamedAggregate): Scheduler =
        schedulers.computeIfAbsent(namedAggregate.materialize()) { _ ->
            Schedulers.newParallel("$name-${namedAggregate.aggregateName}")
        }
}
```