---
title: ID 生成器
description: 区分消息 ID 与聚合 ID 的生成、SPI 选择、缓存和部署责任。
outline: deep
---

# ID 生成器

Wow 基于 CosId 提供两条 ID 路径：全局 ID 用于消息等运行时信封，聚合 ID 按命名聚合选择生成器。二者共享基础设施，但拥有不同的选择与缓存边界。

## 两条路径

| 路径 | API | 主要用途 | 选择键 |
| --- | --- | --- | --- |
| 全局 ID | `generateGlobalId()` / `GlobalIdGenerator` | Command、DomainEvent、DomainEventStream、等待与补偿记录的消息 ID | 系统属性 `wow.cosid`，默认 CosId 的 `cosid` 名称 |
| 聚合 ID | `NamedAggregate.generateId()` / `AggregateIdGeneratorRegistrar` | 命令未提供 ID 时创建聚合 ID | 聚合元数据 `id`，为空时使用 aggregate name |

消息 ID 与业务聚合 ID 不应混用。业务已经拥有自然 ID 时，可以在命令中显式提供；框架生成器不是强制所有聚合改用同一种标识。

## 全局生成器

`GlobalIdGenerator` 是惰性单例。首次使用时，它通过 Java `ServiceLoader` 查找 `GlobalIdGeneratorFactory`，按 Wow `@Order` 排序，并采用第一个返回非空 `CosIdGenerator` 的工厂。若没有工厂能够创建实例，会抛出 `NotInitializedGlobalIdGeneratorError`。

内置 `CosIdGlobalIdGeneratorFactory` 从 `IdGeneratorProvider` 读取配置名称。自定义工厂需要实现接口并注册：

```text
META-INF/services/me.ahoo.wow.id.GlobalIdGeneratorFactory
```

不要在请求处理中重复创建全局生成器；选择只在惰性初始化时发生。

## 聚合生成器

`AggregateIdGeneratorRegistrar` 以 materialized `NamedAggregate` 为 key 缓存生成器。首次请求时，同样按 `@Order` 调用 `AggregateIdGeneratorFactory`，使用第一个非空结果。

内置 `CosIdAggregateIdGeneratorFactory` 的选择顺序是：

1. 从 `META-INF/wow-metadata.json` 读取聚合的 `id` 生成器名称；
2. 未声明时使用 aggregate name；
3. 若 `IdGeneratorProvider` 中存在同名生成器，直接使用；
4. 否则以全局生成器的 machine ID 创建 `Radix62CosIdGenerator`，并包装为 `ClockSyncCosIdGenerator`。

这个 fallback 依赖可用的全局生成器和它的 machine ID；它不会为部署自动分配 machine ID。

## 部署责任

CosId 的具体算法、时钟处理和 machine ID 分配由 CosId 配置拥有。Wow 只选择并调用生成器。多实例部署必须验证：

- 每个实例取得的 machine ID 满足所选 CosId 生成器的要求；
- 重启、扩缩容与租约回收不会复用活跃实例的 machine ID；
- 时钟回拨、Provider 不可用和配置缺失时的启动/生成行为；
- 生成 ID 的长度、字符集与下游数据库/API 合同兼容。

单元测试中的“非空”或单 JVM 递增结果不能证明跨节点唯一性、全局排序或生产容量。生产配置流程见[配置指南](../configuration.md)与当前 CosId 版本的配置元数据。

## 自定义选择

只有不同聚合确实需要不同格式或 Provider 时，才增加 `AggregateIdGeneratorFactory`。工厂可以对不负责的聚合返回 `null`，让后续工厂继续选择；不要用一个全局分支表复制元数据已有的 `id` 映射。

```kotlin
@Order(100)
class InvoiceIdGeneratorFactory : AggregateIdGeneratorFactory {
    override fun create(namedAggregate: NamedAggregate): IdGenerator? =
        if (namedAggregate.aggregateName == "invoice") invoiceGenerator else null
}
```

并注册：

```text
META-INF/services/me.ahoo.wow.id.AggregateIdGeneratorFactory
```

工厂与 Registrar 都可能被并发访问；自定义生成器本身必须满足调用方所需的并发合同。

## 验证

```bash
./gradlew :wow-core:test --tests "me.ahoo.wow.id.GlobalIdGeneratorTest"
./gradlew :wow-core:test --tests "me.ahoo.wow.id.AggregateIdGeneratorRegistrarTest"
./gradlew :wow-core:test --tests "me.ahoo.wow.id.CosIdAggregateIdGeneratorFactoryTest"
```

应用还应在与生产同构的 machine ID 分配环境运行多实例冲突与重启测试；仓库单元测试不覆盖该证据。

## 源码与相关页面

- [`GlobalIdGenerator`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/id/GlobalIdGenerator.kt)
- [`AggregateIdGeneratorRegistrar`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/id/AggregateIdGenerator.kt)
- [`CosIdAggregateIdGeneratorFactory`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/id/CosIdAggregateIdGeneratorFactory.kt)
- [编译器](./compiler.md)：聚合 `id` 元数据的来源
- [核心概念](../core-concepts.md#限界上下文与聚合标识)：完整 AggregateId 边界
