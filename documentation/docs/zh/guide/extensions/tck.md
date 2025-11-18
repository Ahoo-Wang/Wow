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