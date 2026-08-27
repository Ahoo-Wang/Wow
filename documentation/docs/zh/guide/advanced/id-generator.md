---
title: ID 生成器
description: 基于 CosId 的消息 ID 和聚合根 ID 生成机制。
---

# ID 生成器

Wow 框架的*消息ID*、*聚合根ID*生成依赖于 [CosId](https://github.com/Ahoo-Wang/CosId) 提供的强大支持。

## 生产环境配置

CosId 使用基于 Snowflake 的算法，要求每个服务实例拥有唯一的 **machine ID**。
在生产环境中，你必须配置 machine ID 分配器以避免多实例间的冲突。

### 手动 Machine ID（单实例 / 开发环境）

除 Wow 依赖外，还需要 `cosid-spring-boot-starter`（以及 MongoDB 分配所需的 `cosid-mongo`）：

```kotlin
implementation("me.ahoo.cosid:cosid-spring-boot-starter")
implementation("me.ahoo.cosid:cosid-mongo") // 仅用于 MongoDB machine ID 分配
```

```yaml
cosid:
  machine:
    enabled: true
    distributor:
      type: manual
      manual:
        machine-id: 1
  generator:
    enabled: true
```

### MongoDB Machine ID（生产环境，多实例）

对于多实例生产部署，使用 MongoDB 作为 machine ID 分配器，使每个实例自动获得唯一的 machine ID：

```yaml
cosid:
  machine:
    enabled: true
    distributor:
      type: mongo
  generator:
    enabled: true
```

### 自定义按聚合 ID 生成器

要为特定聚合分配专用的 CosId 生成器（例如不同的 Snowflake provider），在 `@BoundedContext` 元数据中
定义生成器名称，然后在 CosId 中配置该生成器：

```yaml
cosid:
  snowflake:
    enabled: true
    provider:
      order:               # 与下方聚合的 `id` 字段对应
        converter:
          type: radix      # Radix62 URL 安全编码
```

```kotlin
@BoundedContext(
    name = "order-service",
    aggregates = [
        Aggregate(name = "order", id = "order"),  // ← 使用名为 "order" 的 CosId 生成器
    ],
)
object OrderService
```

如果在 CosId 的 `IdGeneratorProvider` 中找不到指定名称的生成器，Wow 会回退到使用
全局生成器 `machineId` 的 `Radix62CosIdGenerator`。

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
