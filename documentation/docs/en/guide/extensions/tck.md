---
title: Technology Compatibility Kit
description: TCK test cases for verifying that interface implementations conform to specifications.
---

# Technology Compatibility Kit

The Technology Compatibility Kit (_TCK_) is a set of test cases used to verify that specific interface implementations conform to specifications.

Through _TCK_, developers can ensure that extensions run correctly in different scenarios, providing convenience and correctness guarantees for custom extensions.
This standardized verification method not only simplifies extension development and reduces potential error risks, but also ensures the consistency and stability of the entire ecosystem.

## Installation
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

## Redis Extension Example

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
