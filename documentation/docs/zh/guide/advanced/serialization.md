---
title: 序列化
description: Wow 的 Jackson 3 Mapper、框架模块、事件类型解析与 wire 兼容边界。
outline: deep
---

# 序列化

Wow 使用 Jackson 3 序列化命令、事件流、状态事件、快照和状态聚合。序列化配置可能同时影响 HTTP、消息与持久化数据，因此 Mapper 不是纯内部实现细节。

## 两个入口

| 入口 | 所有者 | 适用场景 |
| --- | --- | --- |
| `JsonSerializer` | `wow-core` 的预配置全局 `ObjectMapper` | Wow 内部及应用辅助转换 |
| `WowModule` | 可注册到任意 Jackson Mapper 的模块 | Spring Boot Mapper 或应用自建 Mapper |

`JsonSerializer` 通过 Kotlin `jsonMapper` 创建，设置字段可见性、忽略未知属性、允许 final 字段写入、将无类型浮点数读取为 `BigDecimal`，并调用 `findAndAddModules()`。它与应用随手创建的裸 `ObjectMapper` 不等价。

常用辅助函数都委托给同一个 Mapper：

```kotlin
val json = order.toJsonString()
val decoded = json.toObject<Order>()
val tree = json.toObjectNode()
val copied = order.deepCopy()
```

## WowModule 拥有的框架格式

`WowModule` 注册下列框架类型的 serializer/deserializer：

- `AggregateId`
- `CommandMessage`
- `DomainEventStream` 与 `DomainEvent`
- `StateAggregate`
- `Snapshot`
- `StateEvent`

它还安装 `MissingTypeImplProblemHandler`。Spring Boot Starter 在 Jackson 自动配置前提供一个 `WowModule` Bean；这只添加 Wow 模块，不会把 `JsonSerializer` 的全部 feature 复制到 Spring 管理的 Mapper。

应用若完全替换 Spring Mapper、禁用模块发现或自行创建 Mapper，必须显式注册所需 Kotlin 模块与 `WowModule`，并用真实路径测试。

## 事件记录如何解析类型

持久化事件记录同时保存稳定业务标识和 JVM 类型提示，包括 context、aggregate、event `name`、`revision`、`bodyType` 与 body。

反序列化顺序是：

1. `EventUpgraderFactory` 转换旧记录；
2. `EventTypeRegistry` 用 `(context, aggregate, name, revision)` 查找当前元数据类型；
3. 未命中时尝试记录中的 `bodyType`；
4. `bodyType` 类也不存在时，保留为 `JsonDomainEvent`，body 为 JSON tree。

这条 fallback 让未知历史类型仍可被表示，但不等于当前聚合能够正确重放它。若 sourcing 依赖具体事件类型，仍需提供可解析类型或[事件 Upgrader](../domain/event-evolution.md)。

一个 `DomainEventStream` 反序列化时会按 body 列表位置重新得到事件 `sequence` 与 `isLast`。不要把 JSON 数组外的自定义字段顺序当成事件顺序合同。

## 缺失多态 type 的回退

`@MissingTypeImpl` 只在 JSON **缺少** type id 时声明一个默认 subtype：

```kotlin
@MissingTypeImpl(Expression.Field::class)
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
sealed interface Expression
```

只有 Mapper 注册 `MissingTypeImplProblemHandler` 后，缺失 type 才会使用该实现。

| 输入 | 行为 |
| --- | --- |
| 缺少 type，base type 有注解 | 构造注解声明的 subtype |
| 已知 type | 使用 Jackson 正常 subtype 解析 |
| 未知 type | Handler 不接管，保留 Mapper 的未知 type 策略 |
| 缺少 type，base type 无注解 | 保留 Jackson 缺失 type 错误 |

注解不是“所有旧 JSON 都兼容”的开关。默认类必须是当前 base type 的合法 subtype，且这个运行时 fallback 不会自动把 OpenAPI/JSON Schema 中的 discriminator 改成可选。

## 三种兼容性分开评估

| 范围 | 例子 | 需要的验证 |
| --- | --- | --- |
| Source | Kotlin 属性或构造函数改变 | 重新编译调用方 |
| Binary | 已编译调用方加载新 class/JAR | 二进制兼容检查或真实消费方运行 |
| Wire | JSON 字段、类型、revision、默认值改变 | 历史事件/快照/消息/HTTP contract 测试 |

一次 Kotlin 编译成功不能证明历史事件和快照可读；旧事件可读也不能证明旧二进制可加载。只实现发布实际需要的兼容范围。

## 自定义 Mapper 前的最小验证

1. 用最终运行时 Mapper 读取真实或脱敏历史事件与快照。
2. 对已知命令、事件流、StateEvent 和 Snapshot 做 round trip。
3. 验证 type 缺失、已知 type、未知 type 三种多态输入。
4. 验证 event type registry 命中与 `bodyType` fallback。
5. 用完整聚合回放验证最终状态，而不只比较 JSON 文本。
6. 单独验证生成的 OpenAPI/Schema 合同。

```bash
./gradlew :wow-core:test --tests "me.ahoo.wow.serialization.JsonSerializerMapperTest"
./gradlew :wow-core:test --tests "me.ahoo.wow.serialization.JsonSerializerEventTest"
./gradlew :wow-api:test --tests "me.ahoo.wow.api.serialization.MissingTypeImplProblemHandlerTest"
```

## 源码与相关页面

- [`JsonSerializer`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/serialization/JsonSerializer.kt)
- [`WowModule`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/serialization/WowModule.kt)
- [`DomainEventRecord`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/serialization/event/DomainEventRecord.kt)
- [`MissingTypeImpl`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-api/src/main/kotlin/me/ahoo/wow/api/serialization/MissingTypeImpl.kt)
- [JSON Schema](./schema.md) / [OpenAPI](../open-api.md)：生成合同边界
