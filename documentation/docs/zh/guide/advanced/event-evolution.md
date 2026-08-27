---
title: 事件演进
description: 使用 revision、EventUpgrader 与历史回放维护持久事件 wire 合同。
outline: deep
---

# 事件演进

持久化领域事件是长期 wire 合同。修改 Kotlin 类只改变新代码；历史 JSON 仍保留原来的事件名、类型、revision 和 body。Wow 在 `DomainEventRecord` 物化为当前事件对象前调用 `EventUpgraderFactory`：

```text
EventStore 记录
  → 按 context + aggregate + event name 查找 Upgrader
  → 按 @Order 依次转换记录
  → 按 type id + revision 解析当前事件类型
  → @OnSourcing
```

Upgrader 改变本次读取使用的 `ObjectNode`，不会把转换结果写回 EventStore。

## 两种版本

| 字段 | 含义 | 用途 |
| --- | --- | --- |
| `revision` | 事件载荷 Schema 修订，由 `@Event(revision = ...)` 声明，默认 `0.0.1` | 选择事件类型并驱动升级逻辑 |
| 聚合 `version` | 事件流在一个聚合历史中的位置 | 顺序、恢复和乐观并发 |

Schema 变化不能通过修改聚合 version 表达。一个事件 revision 也不会自动告诉框架如何转换；每个 `EventUpgrader` 都是应用显式提供的函数。

## 决定是否需要升级

| 变化 | 所需证据 |
| --- | --- |
| 增加可选字段或安全默认值 | 用真实旧记录验证当前 Mapper 可以读取，并验证重放状态 |
| 增加必填字段、改类型或改嵌套结构 | 提供 Upgrader，把旧 body 转为目标结构 |
| 改事件名或 JVM 类型 | 同时评估 `name`、`bodyType`、`revision` 和 body；验证类型注册 |
| 事件不再影响当前状态 | 只有证明重放不变量不变后，才可转为 `DroppedEvent` |
| 修复错误业务事实 | 设计可审计的数据修复/补偿；不要伪装成无害 Schema 升级 |

## 实现单步 Upgrader

```kotlin
@Order(100)
class OrderCreatedV2Upgrader : EventUpgrader {
    override val eventNamedAggregate =
        "sales.order".toNamedAggregate()
            .toEventNamedAggregate("order_created")

    override fun upgrade(record: DomainEventRecord): DomainEventRecord {
        if (record.revision != "0.0.1") return record

        return record.toMutableDomainEventRecord().apply {
            body.put("currency", "CNY")
            revision = "2.0.0"
        }
    }
}
```

`EventUpgraderFactory` 会执行该事件已注册的**所有** Upgrader，不会根据 revision 自动跳过任何一步。因此每一步必须检查自己的源 revision，并明确写出目标 revision。`eventNamedAggregate` 必须与历史记录的 context、aggregate 和 event name 完全一致。

## 注册与顺序

在最终运行时 classpath 中提供 ServiceLoader 文件：

```text
META-INF/services/me.ahoo.wow.event.upgrader.EventUpgrader
```

内容每行一个实现类：

```text
com.example.order.event.OrderCreatedV2Upgrader
```

工厂初始化时加载并按 Wow `@Order` 排序。多步升级可以写成：

```text
0.0.1 --order 100--> 1.0.0 --order 200--> 2.0.0
```

框架只执行排序后的函数列表；连续性、遗漏 revision 和输出合法性由应用测试负责。只要历史中仍可能存在旧 revision，就不能删除对应步骤。

## 重命名与丢弃

`MutableDomainEventRecord` 可修改 `name`、`bodyType`、`revision` 和 `body`。重命名后，目标 `(context, aggregate, name, revision)` 必须能解析到预期事件类型；不能只改 Kotlin 类名。

`toDroppedEventRecord()` 把 `bodyType`、`name` 和 body 改为框架的 dropped 记录，同时保留流中的聚合 version/sequence。它不是删除历史，也不会自动证明该事件对状态无影响。

::: danger
为了绕过反序列化错误而 drop 事件，可能让重放表面成功却产生错误状态。先证明所有后续状态和业务不变量不依赖该事件。
:::

## 验证矩阵

1. **函数级**：每个源 revision 的输入字段、目标 revision、name/type/body。
2. **注册级**：最终 artifact 能通过 ServiceLoader 找到实现，顺序符合 `@Order`。
3. **反序列化级**：升级后由 `EventTypeRegistry` 解析到预期类型。
4. **历史回放级**：从脱敏真实样本或完整夹具恢复聚合，并比较关键状态与不变量。
5. **下游级**：投影、Saga 与 BI 对旧/新事件的处理结果一致或按迁移设计变化。

仓库中的最窄检查：

```bash
./gradlew :wow-core:test --tests "me.ahoo.wow.event.upgrader.EventUpgraderFactoryTest"
./gradlew :wow-core:test --tests "me.ahoo.wow.serialization.JsonSerializerEventTest"
```

## 发布与回滚

- 发布前统计真实 revision 分布并验证备份恢复；
- 在隔离环境回放代表性与最长事件流；
- 滚动窗口中同时存在新旧实例时，确认旧实例能否读取新写 revision；
- 不能双向读取时，设计停写或分阶段兼容，而不是直接滚动；
- 回滚应用时也要保留它需要的 Upgrader 链。

本地测试通过只证明候选代码；不证明线上数据分布、并存窗口或恢复流程。

## 源码与相关页面

- [`DomainEventRecord`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/serialization/event/DomainEventRecord.kt)
- [`EventUpgraderFactory`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/event/upgrader/EventUpgraderFactory.kt)
- [`MutableDomainEventRecord`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/event/upgrader/MutableDomainEventRecord.kt)
- [序列化](./serialization.md)：Mapper 与事件类型解析
- [迁移](../migration.md)：发布、对账和回滚边界
