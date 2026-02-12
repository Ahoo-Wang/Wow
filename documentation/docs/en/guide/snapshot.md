# Snapshot

Snapshot is an important optimization mechanism in event sourcing architecture that improves performance by saving checkpoints of aggregate root state to reduce the number of event replays.

## Snapshot Mechanism

In event sourcing, the state of an aggregate root is reconstructed by replaying all historical events. As the number of events increases, replaying all events becomes slower and slower. The snapshot mechanism solves this problem by periodically saving the current state of the aggregate root.

```kotlin
interface Snapshot<S : Any> : ReadOnlyStateAggregate<S>, SnapshotTimeCapable

data class SimpleSnapshot<S : Any>(
    override val delegate: ReadOnlyStateAggregate<S>,
    override val snapshotTime: Long = System.currentTimeMillis()
) : Snapshot<S>
```

## Snapshot Strategies

Snapshot strategies determine when to create snapshots. The Wow framework provides multiple built-in strategies:

### Version Offset Strategy (VersionOffset)

Creates a snapshot when the difference between the aggregate root version and the last snapshot version reaches a specified threshold.

```kotlin
class VersionOffsetSnapshotStrategy(
    private val snapshotRepository: SnapshotRepository,
    private val versionOffset: Int = 5
) : SnapshotStrategy
```

### All Strategy (All)

Creates a snapshot for every state event.

```kotlin
class SimpleSnapshotStrategy(
    private val snapshotRepository: SnapshotRepository
) : SnapshotStrategy
```

### No Operation Strategy (NoOp)

Does not create any snapshots.

```kotlin
object NoOp : SnapshotStrategy {
    override fun <S : Any> shouldSnapshot(stateEvent: StateEvent<S>): Boolean = false
}
```

## Snapshot Repository

The snapshot repository is responsible for storing and retrieving snapshots.

```kotlin
interface SnapshotRepository : Named, AggregateIdScanner {
    fun <S : Any> load(aggregateId: AggregateId): Mono<Snapshot<S>>
    fun <S : Any> save(snapshot: Snapshot<S>): Mono<Void>
    fun getVersion(aggregateId: AggregateId): Mono<Int>
}
```

### In-Memory Implementation

```kotlin
class InMemorySnapshotRepository : SnapshotRepository {
    private val aggregateIdMapSnapshot = ConcurrentHashMap<AggregateId, String>()

    override fun <S : Any> load(aggregateId: AggregateId): Mono<Snapshot<S>> =
        Mono.fromCallable {
            aggregateIdMapSnapshot[aggregateId]?.toObject<Snapshot<S>>()
        }

    override fun <S : Any> save(snapshot: Snapshot<S>): Mono<Void> =
        Mono.fromRunnable {
            aggregateIdMapSnapshot[snapshot.aggregateId] = snapshot.toJsonString()
        }
}
```

## Snapshot Processing Flow

1. **State Event Publishing**: When aggregate root state changes, publish state events
2. **Strategy Evaluation**: Snapshot strategy evaluates whether a snapshot needs to be created
3. **Snapshot Creation**: If needed, create a snapshot of the current state
4. **Snapshot Storage**: Save the snapshot to the snapshot repository

## Configuration

```yaml
wow:
  eventsourcing:
    snapshot:
      enabled: true  # Whether to enable snapshots
      strategy: all  # Snapshot strategy (all, version_offset)
      storage: mongo  # Snapshot storage (mongo, redis, r2dbc, elasticsearch, in_memory, delay)
      version-offset: 5  # Version offset (only valid for version_offset strategy)
```

## Aggregate Loading Optimization

Snapshots greatly optimize aggregate root loading performance:

```kotlin
class EventSourcingOrderRepository(
    private val eventStore: EventStore,
    private val snapshotRepository: SnapshotRepository
) : OrderRepository {

    override fun load(orderId: String): Mono<OrderState> {
        val aggregateId = AggregateId("order", orderId)

        return snapshotRepository.load<OrderState>(aggregateId)
            .flatMap { snapshot ->
                // Only replay events after the snapshot version
                eventStore.load(aggregateId, snapshot.version + 1)
                    .collectList()
                    .map { eventStreams ->
                        val state = snapshot.state
                        eventStreams.forEach { stream ->
                            stream.events.forEach { event ->
                                state.apply(event)
                            }
                        }
                        state
                    }
            }
            .switchIfEmpty(
                // No snapshot, load all events
                eventStore.load(aggregateId)
                    .collectList()
                    .map { eventStreams ->
                        val state = OrderState(orderId)
                        eventStreams.forEach { stream ->
                            stream.events.forEach { event ->
                                state.apply(event)
                            }
                        }
                        state
                    }
            )
    }
}
```

## Performance Impact

- **Snapshots Enabled**: Aggregate loading time is proportional to snapshot interval, not total event count
- **Snapshots Disabled**: Every load requires replaying all historical events
- **Storage Cost**: Requires additional storage space to save snapshot data

## Best Practices

1. **Choose Appropriate Snapshot Strategy**: Select appropriate snapshot frequency based on business scenarios
2. **Monitor Snapshot Effectiveness**: Regularly check if snapshots significantly improve loading performance
3. **Snapshot Cleanup**: Regularly clean up expired snapshots to save storage space
4. **Snapshot Consistency**: Ensure snapshot version consistency with event streams