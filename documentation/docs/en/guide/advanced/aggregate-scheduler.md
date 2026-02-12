# Aggregate Scheduler

The aggregate scheduler provides a dedicated Reactor Scheduler for each aggregate, used to control concurrent execution and resource allocation.

## Scheduler Supplier

The aggregate scheduler supplier provides or creates a dedicated scheduler for each aggregate.

```kotlin
fun interface AggregateSchedulerSupplier {
    fun getOrInitialize(namedAggregate: NamedAggregate): Scheduler
}
```

### Default Implementation

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