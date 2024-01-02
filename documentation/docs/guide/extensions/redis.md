# Redis

_Redis_ 扩展提供了对 Redis 的支持，实现了 `CommandBus`、`DomainEventBus` 、 `StateEventBus`、`EventStore` 、 `SnapshotRepository` 和 `PrepareKey`.

## 安装

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

## 配置

- 配置类：[RedisProperties](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/redis/RedisProperties.kt)
- 前缀：`wow.redis.`

| 名称                  | 数据类型                  | 说明          | 默认值    |
|---------------------|-----------------------|-------------|--------|
| `enabled`           | `Boolean`             | 是否启用        | `true` |

**YAML 配置样例**

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