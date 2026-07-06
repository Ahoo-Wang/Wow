---
title: 兼容性测试套件
description: 用于验证接口实现是否符合规范的测试用例集。
---

# 兼容性测试套件

兼容性测试套件（_TCK_）是一组用于验证特定接口实现是否符合规范的测试用例。

通过 _TCK_，开发者能够确保扩展在不同情境下正确运行，为自定义扩展提供了便捷和正确性保障。
这种标准化验证方式不仅简化了扩展开发，降低了潜在错误风险，还确保了整个生态系统的一致性和稳定性。

## 安装
::: code-group
```kotlin [Gradle(Kotlin)]
testImplementation("me.ahoo.wow:wow-tck")
```
```groovy [Gradle(Groovy)]
testImplementation 'me.ahoo.wow:wow-tck'
```
```xml [Maven]
<dependency>
    <groupId>me.ahoo.wow</groupId>
    <artifactId>wow-tck</artifactId>
    <version>${wow.version}</version>
    <scope>test</scope>
</dependency>
```
:::

## Redis 扩展案例

### CommandBus

```kotlin
class RedisCommandBusTest : CommandBusSpec() {
    @JvmField
    @RegisterExtension
    val redis = RedisTestFixture()

    override fun createMessageBus(): CommandBus {
        return RedisCommandBus(redis.redisTemplate)
    }
}
```

### DomainEventBus

```kotlin
class RedisDomainEventBusTest : DomainEventBusSpec() {
    @JvmField
    @RegisterExtension
    val redis = RedisTestFixture()

    override fun createMessageBus(): DomainEventBus {
        return RedisDomainEventBus(redis.redisTemplate)
    }
}
```

### StateEventBus

```kotlin
class RedisStateEventBusTest : StateEventBusSpec() {
    @JvmField
    @RegisterExtension
    val redis = RedisTestFixture()

    override fun createMessageBus(): StateEventBus {
        return RedisStateEventBus(redis.redisTemplate)
    }
}
```

### EventStore

```kotlin
class RedisEventStoreTest : EventStoreSpec() {
    @JvmField
    @RegisterExtension
    val redis = RedisTestFixture()

    override fun createEventStore(): EventStore {
        return RedisEventStore(redis.redisTemplate)
    }

    override fun loadEventStreamByEventTime() = Unit
}
```

### SnapshotStore

```kotlin
class RedisSnapshotStoreTest : SnapshotStoreSpec() {
    @JvmField
    @RegisterExtension
    val redis = RedisTestFixture()

    override fun createSnapshotStore(): SnapshotStore {
        return RedisSnapshotStore(redis.redisTemplate)
    }
}
```

### RedisPrepareKey

```kotlin
class StringRedisPrepareKeyTest : RedisPrepareKeySpec<String>() {
    override val name: String
        get() = "string"
    override val valueType: Class<String>
        get() = String::class.java

    override fun generateValue(): String {
        return GlobalIdGenerator.generateAsString()
    }
}
```
