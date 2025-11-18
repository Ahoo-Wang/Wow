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
    protected lateinit var redisInitializer: RedisInitializer

    @BeforeEach
    fun setup() {
        redisInitializer = RedisInitializer()
    }

    override fun createMessageBus(): CommandBus {
        return RedisCommandBus(redisInitializer.redisTemplate)
    }
}
```

### DomainEventBus

```kotlin
class RedisDomainEventBusTest : DomainEventBusSpec() {
    protected lateinit var redisInitializer: RedisInitializer

    @BeforeEach
    fun setup() {
        redisInitializer = RedisInitializer()
    }

    override fun createMessageBus(): DomainEventBus {
        return RedisDomainEventBus(redisInitializer.redisTemplate)
    }
}
```

### StateEventBus

```kotlin
class RedisStateEventBusTest : StateEventBusSpec() {
    protected lateinit var redisInitializer: RedisInitializer

    @BeforeEach
    fun setup() {
        redisInitializer = RedisInitializer()
    }

    override fun createMessageBus(): StateEventBus {
        return RedisStateEventBus(redisInitializer.redisTemplate)
    }
}
```

### EventStore

```kotlin
class RedisEventStoreTest : EventStoreSpec() {
    protected lateinit var redisInitializer: RedisInitializer

    @BeforeEach
    override fun setup() {
        redisInitializer = RedisInitializer()
        super.setup()
    }

    @AfterEach
    fun destroy() {
        redisInitializer.close()
    }

    override fun createEventStore(): EventStore {
        return RedisEventStore(redisInitializer.redisTemplate)
    }

    override fun scanAggregateId() = Unit
}
```

### SnapshotRepository

```kotlin
class RedisSnapshotRepositoryTest : SnapshotRepositorySpec() {
    protected lateinit var redisInitializer: RedisInitializer

    @BeforeEach
    fun setup() {
        redisInitializer = RedisInitializer()
    }

    override fun createSnapshotRepository(): SnapshotRepository {
        return RedisSnapshotRepository(redisInitializer.redisTemplate)
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