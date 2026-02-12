# ID 生成器

Wow 框架的*消息ID*、*聚合根ID*生成依赖于 [CosId](https://github.com/Ahoo-Wang/CosId) 提供的强大支持。

## 全局 ID 生成器

*全局ID生成器*主要用于生成消息ID(`Command` 、`DomainEvent` 、`DomainEventStream`)。

默认情况下*全局ID生成器*将从*CosId*的*ID生成器容器*(`IdGeneratorProvider`)中获取以 `cosid` 为名称的ID生成器。

### 通过 SPI 自定义全局 ID 生成器

开发者可以通过 `GlobalIdGeneratorFactory` SPI 扩展点自定义全局 ID 生成器。

1. 实现 `GlobalIdGeneratorFactory` 接口
2. 在 `META-INF/services/me.ahoo.wow.id.GlobalIdGeneratorFactory` 文件中添加实现类的全限定类名

```kotlin
@Order(ORDER_LAST)
class TestGlobalIdGeneratorFactory : GlobalIdGeneratorFactory {
    companion object {
        private val log = LoggerFactory.getLogger(TestGlobalIdGeneratorFactory::class.java)
        private const val TEST_MACHINE_ID: Int = 1048575
    }

    override fun create(): CosIdGenerator {
        val idGenerator = Radix62CosIdGenerator(TEST_MACHINE_ID)
        val clockSyncCosIdGenerator = ClockSyncCosIdGenerator(idGenerator)
        if (log.isInfoEnabled) {
            log.info("Create - [$clockSyncCosIdGenerator].")
        }
        return clockSyncCosIdGenerator
    }
}
```

## 聚合 ID 生成器

*聚合ID生成器*主要用于生成聚合根ID。
用户可以通过定义聚合根元数据中的ID名称，来从 _CosId_ 的*ID生成器容器*(`IdGeneratorProvider`)中获取对应的ID生成器。

```kotlin
@BoundedContext(
    name = SERVICE_NAME,
    alias = SERVICE_ALIAS,
    aggregates = [
        Aggregate(
            name = ORDER_AGGREGATE_NAME,
            id = "<You customize the ID name>", // [!code focus]
            packageScopes = [CreateOrder::class]
        ),
    ],
)
object ExampleService {
    const val SERVICE_NAME = "example-service"
    const val SERVICE_ALIAS = "example"
    const val ORDER_AGGREGATE_NAME = "order"
}
```

1. 首先获取元数据中的ID名称，如果未定义则使用聚合根名称作为ID名称。
2. 获取到该名称之后再从*ID生成器容器*中获取对应的ID生成器。
3. 如果未获取到则创建新的 `Radix62CosIdGenerator` 实例，其使用全局ID生成器的 `machineId` 作为它的 `machineId`。

### 自定义 ID 生成器

开发者可以通过 `AggregateIdGeneratorFactory` SPI 扩展点自定义 ID 生成器。

1. 实现 `AggregateIdGeneratorFactory` 接口
2. 在 `META-INF/services/me.ahoo.wow.id.AggregateIdGeneratorFactory` 文件中添加实现类的全限定类名

```kotlin
@Order(ORDER_LAST)
class CosIdAggregateIdGeneratorFactory(
    private val idProvider: IdGeneratorProvider = DefaultIdGeneratorProvider.INSTANCE
) :
    AggregateIdGeneratorFactory {
    companion object {
        private val log = LoggerFactory.getLogger(CosIdAggregateIdGeneratorFactory::class.java)
    }

    override fun create(namedAggregate: NamedAggregate): IdGenerator {
        val idGenName = MetadataSearcher.metadata
            .contexts[namedAggregate.contextName]
            ?.aggregates
            ?.get(namedAggregate.aggregateName)
            ?.id
            ?: namedAggregate.aggregateName

        val idGeneratorOp = idProvider.get(idGenName)
        if (idGeneratorOp.isPresent) {
            val idGenerator = idGeneratorOp.get()
            if (log.isInfoEnabled) {
                log.info("Create $idGenerator to $namedAggregate from DefaultIdGeneratorProvider[$idGenName].")
            }
            return idGenerator
        }

        val idGenerator = Radix62CosIdGenerator(GlobalIdGenerator.machineId)
        val clockSyncCosIdGenerator = ClockSyncCosIdGenerator(idGenerator)
        if (log.isInfoEnabled) {
            log.info("Create $clockSyncCosIdGenerator to $namedAggregate.")
        }
        return clockSyncCosIdGenerator
    }
}
```