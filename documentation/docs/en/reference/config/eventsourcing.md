# Event Sourcing

## EventStoreProperties

- Configuration class: [EventStoreProperties](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/eventsourcing/store/EventStoreProperties.kt)
- Prefix: `wow.eventsourcing.store`

| Name       | Data Type           | Description | Default Value |
|-----------|---------------------|-------------|---------------|
| `storage` | `EventStoreStorage` | `EventStoreStorage` | mongo |

**YAML Configuration Example**

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

- Configuration class: [SnapshotProperties](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/eventsourcing/snapshot/SnapshotProperties.kt)
- Prefix: `wow.eventsourcing.snapshot`

| Name             | Data Type         | Description | Default Value |
|------------------|-------------------|-------------|---------------|
| `enabled`        | `Boolean`         | Whether to enable | `true`  |
| `strategy`       | `Strategy`        | Snapshot strategy | `all`   |
| `version-offset` | `Int`             | Version offset | `5`     |
| `storage`        | `SnapshotStorage` | Snapshot storage | `mongo` |

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

- Configuration class: [StateProperties](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/eventsourcing/state/StateProperties.kt)
- Prefix: `wow.eventsourcing.state`

| Name           | Data Type               | Description | Default Value |
|---------------|------------------------|-------------|---------------|
| `bus`         | `BusProperties`         | [BusProperties](./basic#busproperties) |  |

**YAML Configuration Example**

```yaml
wow:
  eventsourcing:
    state:
      bus:
        type: kafka
        local-first:
          enabled: true
```