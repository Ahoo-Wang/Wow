# 事件源

## EventStoreProperties

- 配置类：[EventStoreProperties](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/eventsourcing/store/EventStoreProperties.kt)
- 前缀：`wow.eventsourcing.store`

| 名称        | 数据类型                | 说明                  | 默认值   |
|-----------|---------------------|---------------------|-------|
| `storage` | `EventStoreStorage` | `EventStoreStorage` | mongo |

**YAML 配置样例**

```yaml
wow:
  eventsourcing:
    store:
      storage: mongo
```

### EventStoreStorage

```kotlin
enum class EventStoreStorage {
    MONGO,
    REDIS,
    R2DBC,
    IN_MEMORY,
    DELAY
    ;
}
```

## SnapshotProperties

- 配置类：[SnapshotProperties](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/eventsourcing/snapshot/SnapshotProperties.kt)
- 前缀：`wow.eventsourcing.snapshot`

| 名称               | 数据类型              | 说明    | 默认值     |
|------------------|-------------------|-------|---------|
| `enabled`        | `Boolean`         | 是否启用  | `true`  |
| `strategy`       | `Strategy`        | 快照策略  | `all`   |
| `version-offset` | `Int`             | 版本偏移量 | `5`     |
| `storage`        | `SnapshotStorage` | 快照存储  | `mongo` |

```yaml
wow:
  eventsourcing:
    snapshot:
      enabled: true
      strategy: all
      storage: mongo
```

### Strategy

```kotlin
enum class Strategy {
    ALL,
    VERSION_OFFSET,
    ;
}
```

### SnapshotStorage

```kotlin
enum class SnapshotStorage {
    MONGO,
    REDIS,
    R2DBC,
    ELASTICSEARCH,
    IN_MEMORY,
    DELAY
    ;
}
```

## StateProperties

- 配置类：[StateProperties](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/eventsourcing/state/StateProperties.kt)
- 前缀：`wow.eventsourcing.state`

| 名称            | 数据类型                    | 说明                                     | 默认值 |
|---------------|-------------------------|----------------------------------------|-----|
| `bus`         | `BusProperties`         | [BusProperties](./basic#busproperties) |     |

**YAML 配置样例**

```yaml
wow:
  eventsourcing:
    state:
      bus:
        type: kafka
        local-first:
          enabled: true
```