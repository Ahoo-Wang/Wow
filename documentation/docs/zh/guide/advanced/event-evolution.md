---
title: 事件演进
description: 使用事件 revision、EventUpgrader 和历史回放测试安全演进已持久化的领域事件。
outline: deep
---

# 事件演进

领域事件一旦持久化，就成为长期数据契约。修改 Kotlin 类并不能修改历史记录；Wow 会在历史事件反序列化之前调用 `EventUpgrader`，把旧记录转换成当前模型可读取的形态。

```text
EventStore 原始记录 → EventUpgrader 链 → 当前事件类型 → @OnSourcing
```

升级只改变本次读取使用的内存记录，不会原地重写事件存储。

## Revision 与聚合版本不是一回事

- **事件 `revision`**：事件载荷结构的版本，由 `@Event(revision = "...")` 声明；默认值是 `0.0.1`。
- **聚合 `version`**：事件在聚合流中的顺序，用于乐观并发和重放。

增加、删除或重命名字段时更新事件 `revision`，不要修改聚合 `version` 来表达 Schema 变化。

## 何时需要 Upgrader

| 变化 | 建议 |
| --- | --- |
| 增加带安全默认值的可选字段 | 可保持兼容，但仍应执行历史反序列化测试 |
| 增加必填字段、改变字段类型或嵌套结构 | 添加 `EventUpgrader` |
| 重命名事件或 JVM 类型 | 同时升级 `name`、`bodyType` 和 `body` |
| 事件已无业务意义 | 仅在回放语义允许时转换为 `DroppedEvent` |
| 只想修复错误历史数据 | 不要伪装成普通 Schema 升级；单独设计审计、备份、对账和回滚流程 |

## 示例：为旧事件补充字段

假设 `sales.order` 聚合中的 `order_created` 从 `0.0.1` 升级到 `2.0.0`，新模型要求 `currency`：

```kotlin
@Event(revision = "2.0.0")
data class OrderCreated(
    val customerId: String,
    val totalAmount: BigDecimal,
    val currency: String,
)
```

创建 Upgrader。`EventUpgraderFactory` 会对同一事件执行所有已注册 Upgrader，因此每个 Upgrader 都必须检查自己的源 revision；否则它也会错误处理已经升级或新写入的事件。

```kotlin
import me.ahoo.wow.api.annotation.Order
import me.ahoo.wow.event.upgrader.EventNamedAggregate
import me.ahoo.wow.event.upgrader.EventNamedAggregate.Companion.toEventNamedAggregate
import me.ahoo.wow.event.upgrader.EventUpgrader
import me.ahoo.wow.event.upgrader.MutableDomainEventRecord.Companion.toMutableDomainEventRecord
import me.ahoo.wow.modeling.toNamedAggregate
import me.ahoo.wow.serialization.event.DomainEventRecord

@Order(100)
class OrderCreatedV2Upgrader : EventUpgrader {
    override val eventNamedAggregate: EventNamedAggregate =
        "sales.order"
            .toNamedAggregate()
            .toEventNamedAggregate("order_created")

    override fun upgrade(domainEventRecord: DomainEventRecord): DomainEventRecord {
        if (domainEventRecord.revision != "0.0.1") {
            return domainEventRecord
        }

        return domainEventRecord.toMutableDomainEventRecord().apply {
            body.put("currency", "CNY")
            revision = "2.0.0"
        }
    }
}
```

`sales.order` 和 `order_created` 必须与存储记录中的上下文、聚合和事件名称完全一致。不要根据新的类名猜测这些值；应从选定版本的元数据或真实历史记录中确认。

## 注册 ServiceLoader

在包含 Upgrader 的运行时模块创建：

```text
src/main/resources/META-INF/services/me.ahoo.wow.event.upgrader.EventUpgrader
```

文件内容是实现类的完全限定名，每行一个：

```text
com.example.order.event.OrderCreatedV2Upgrader
```

Wow 在 `EventUpgraderFactory` 初始化时通过 Java `ServiceLoader` 加载这些实现。注册文件必须进入最终运行时 classpath；修改后需要重新构建并重启应用。

## 链式升级与顺序

长期运行的系统通常需要多步升级：

```text
0.0.1 --Order(100)--> 1.0.0 --Order(200)--> 2.0.0
```

- 较小的 `@Order` 先执行；
- 每一步只识别一个源 revision，并输出明确的目标 revision；
- 不要把多个历史版本判断塞进一个不断增长的函数；
- 新增升级步骤时，不要删除仍可能读取到的旧步骤。

框架对 ServiceLoader 与排序的验证见 [`EventUpgraderFactoryTest`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/test/kotlin/me/ahoo/wow/event/upgrader/EventUpgraderFactoryTest.kt)。

## 重命名与丢弃事件

`MutableDomainEventRecord` 允许修改 `name`、`bodyType`、`revision` 和 `body`。重命名时必须让这四个字段与目标事件类型保持一致，并执行完整回放测试。

确实不再参与当前状态的事件可以转换为 `DroppedEvent`：

```kotlin
import me.ahoo.wow.event.upgrader.DroppedEvent.toDroppedEventRecord

override fun upgrade(domainEventRecord: DomainEventRecord): DomainEventRecord {
    if (domainEventRecord.revision != "0.0.1") {
        return domainEventRecord
    }
    return domainEventRecord.toDroppedEventRecord()
}
```

::: danger 丢弃会改变回放语义
只有当后续状态和业务不变量完全不依赖该事件时才能丢弃。为了“让反序列化不再报错”而丢弃事件，会生成表面成功但错误的聚合状态。
:::

## 必须保留的测试证据

至少覆盖三个边界：

1. **单步转换**：用真实旧 revision 的记录验证字段、事件名、类型和目标 revision；
2. **注册与顺序**：验证 `EventUpgraderFactory.get(...)` 能发现实现，并按 `@Order` 排列；
3. **历史回放**：使用生产脱敏样本或完整历史夹具重建聚合，比较最终状态和关键业务不变量。

只对 Upgrader 函数做单元测试，不能证明 ServiceLoader 注册、链式顺序或真实事件反序列化正确。

## 发布与回滚门禁

1. 备份事件存储，并证明备份可恢复；
2. 在隔离环境扫描各 revision 的数量和异常值；
3. 使用候选代码回放代表性聚合及最长事件流；
4. 对比升级前后的聚合状态、投影结果和关键业务统计；
5. 滚动发布前确认旧实例能否读取新 revision；不能时必须停写或采用兼容的分阶段发布；
6. 保留旧应用和 Upgrader 链的回滚路径，确认回滚不会读取到它无法理解的新事件。

本地回放通过不等于可以安全切流。真实存储中的 revision 分布、部署并存窗口和恢复证据必须独立确认。

## 相关源码

- [`DomainEventRecord.toDomainEvent`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/serialization/event/DomainEventRecord.kt)
- [`EventUpgrader`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/event/upgrader/EventUpgrader.kt)
- [`EventUpgraderFactory`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/event/upgrader/EventUpgraderFactory.kt)
- [`MutableDomainEventRecord`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/event/upgrader/MutableDomainEventRecord.kt)
- [`DroppedEvent`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/event/upgrader/DroppedEvent.kt)
