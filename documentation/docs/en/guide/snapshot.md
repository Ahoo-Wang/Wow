# 快照

快照是事件溯源架构中的重要优化机制，通过保存聚合根状态的检查点来减少事件重放的数量，从而提高性能。

## 快照机制

在事件溯源中，聚合根的状态是通过重放所有历史事件来重建的。随着事件数量的增加，重放所有事件会变得越来越慢。快照机制通过定期保存聚合根的当前状态来解决这个问题。

```kotlin
interface Snapshot<S : Any> : ReadOnlyStateAggregate<S>, SnapshotTimeCapable

data class SimpleSnapshot<S : Any>(
    override val delegate: ReadOnlyStateAggregate<S>,
    override val snapshotTime: Long = System.currentTimeMillis()
) : Snapshot<S>
```

## 快照策略

快照策略决定了何时创建快照。Wow 框架提供了多种内置策略：

### 版本偏移策略 (VersionOffset)

当聚合根版本与上次快照的版本差达到指定阈值时创建快照。

```kotlin
class VersionOffsetSnapshotStrategy(
    private val snapshotRepository: SnapshotRepository,
    private val versionOffset: Int = 5
) : SnapshotStrategy
```

### 全部策略 (All)

为每个状态事件创建快照。

```kotlin
class SimpleSnapshotStrategy(
    private val snapshotRepository: SnapshotRepository
) : SnapshotStrategy
```

### 无操作策略 (NoOp)

不创建任何快照。

```kotlin
object NoOp : SnapshotStrategy {
    override fun <S : Any> shouldSnapshot(stateEvent: StateEvent<S>): Boolean = false
}
```

## 快照仓库

快照仓库负责存储和检索快照。

```kotlin
interface SnapshotRepository : Named, AggregateIdScanner {
    fun <S : Any> load(aggregateId: AggregateId): Mono<Snapshot<S>>
    fun <S : Any> save(snapshot: Snapshot<S>): Mono<Void>
    fun getVersion(aggregateId: AggregateId): Mono<Int>
}
```

### 内存实现

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

## 快照处理流程

1. **状态事件发布**: 当聚合根状态发生变化时，发布状态事件
2. **策略评估**: 快照策略评估是否需要创建快照
3. **快照创建**: 如果需要，创建当前状态的快照
4. **快照存储**: 将快照保存到快照仓库

## 配置

```yaml
wow:
  eventsourcing:
    snapshot:
      enabled: true  # 是否启用快照
      strategy: all  # 快照策略 (all, version_offset)
      storage: mongo  # 快照存储 (mongo, redis, r2dbc, elasticsearch, in_memory, delay)
      version-offset: 5  # 版本偏移量 (仅在version_offset策略时有效)
```

## 聚合加载优化

快照极大地优化了聚合根的加载性能：

```kotlin
class EventSourcingOrderRepository(
    private val eventStore: EventStore,
    private val snapshotRepository: SnapshotRepository
) : OrderRepository {

    override fun load(orderId: String): Mono<OrderState> {
        val aggregateId = AggregateId("order", orderId)

        return snapshotRepository.load<OrderState>(aggregateId)
            .flatMap { snapshot ->
                // 只重放快照版本之后的事件
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
                // 没有快照，加载所有事件
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

## 性能影响

- **启用快照**: 聚合加载时间与快照间隔成正比，而不是与总事件数成正比
- **禁用快照**: 每次加载都需要重放所有历史事件
- **存储成本**: 需要额外的存储空间来保存快照数据

## 最佳实践

1. **选择合适的快照策略**: 根据业务场景选择合适的快照频率
2. **监控快照效果**: 定期检查快照是否显著提高了加载性能
3. **快照清理**: 定期清理过期的快照以节省存储空间
4. **快照一致性**: 确保快照与事件流的版本一致性