# Redis

The _Redis_ extension provides support for Redis, implementing `CommandBus`, `DomainEventBus`, `StateEventBus`, `EventStore`, `SnapshotRepository`, and `PrepareKey`.

## Installation

::: code-group
```kotlin [Gradle(Kotlin)]
implementation("me.ahoo.wow:wow-redis")
```
```groovy [Gradle(Groovy)]
implementation 'me.ahoo.wow:wow-redis'
```
```xml [Maven]
<dependency>
    <groupId>me.ahoo.wow</groupId>
    <artifactId>wow-redis</artifactId>
    <version>${wow.version}</version>
</dependency>
```
:::

## Configuration

- Configuration class: [RedisProperties](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/redis/RedisProperties.kt)
- Prefix: `wow.redis.`

| Name                  | Data Type               | Description | Default Value |
|---------------------|-----------------------|-------------|---------------|
| `enabled`           | `Boolean`             | Whether to enable | `true` |

**YAML Configuration Example**

```yaml
wow:
  command:
    bus:
      type: redis
  event:
    bus:
      type: redis
  eventsourcing:
    state:
      bus:
        type: redis
```