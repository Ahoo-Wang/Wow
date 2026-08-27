---
title: 序列化
description: Wow 的 Jackson 3 序列化入口、模块注册、多态缺失类型回退与兼容性边界。
---

# 序列化

Wow 使用 Jackson 3 处理命令、事件流、快照、状态聚合和查询模型的 JSON 表示。框架提供两个互补入口：

- `JsonSerializer`：预配置的全局 `ObjectMapper`，供 Wow 运行时和应用辅助代码直接使用。
- `WowModule`：Jackson 模块，为 Wow 框架类型注册 serializer、deserializer 和缺失类型处理器。

序列化格式可能同时影响 HTTP 合同、消息传输和持久化数据。自定义 Mapper 或修改类型注解前，应分别评估源码、二进制与 wire 兼容性。

## 选择入口

| 场景 | 推荐入口 | 注册行为 |
|---|---|---|
| Wow Spring Boot 应用 | 注入 Spring 管理的 `ObjectMapper` | Starter 自动提供 `WowModule` Bean |
| Wow 运行时或应用内辅助转换 | `JsonSerializer` 与扩展函数 | 自动发现 Kotlin 和 Wow Jackson 模块 |
| 自行构建完整 Mapper | 注册 `WowModule` | 获得全部 Wow 类型序列化支持 |
| 只依赖 `wow-api` | 注册 `MissingTypeImplProblemHandler` | 只获得 `@MissingTypeImpl` 缺失类型回退 |

裸 `ObjectMapper` 或 `JsonMapper` 不会自动具有 Wow 的配置和缺失类型回退。

## JsonSerializer

`JsonSerializer` 位于 `wow-core`，由 Kotlin `jsonMapper` 构建，并启用以下 Wow 默认值：

- 所有属性访问器使用字段可见性 `ANY`。
- 允许写入 final 字段。
- 忽略未知 JSON 属性。
- 无类型浮点数读取为 `BigDecimal`。
- 忽略 parser 的 undefined token。
- 通过 SPI 自动发现 Jackson 模块，其中包括 `WowModule`。

常用扩展函数共享同一个 Mapper：

```kotlin
import me.ahoo.wow.serialization.deepCopy
import me.ahoo.wow.serialization.toJsonString
import me.ahoo.wow.serialization.toLinkedHashMap
import me.ahoo.wow.serialization.toObject
import me.ahoo.wow.serialization.toObjectNode
import java.math.BigDecimal

data class OrderView(val id: String, val amount: BigDecimal)

val source = OrderView("order-1", BigDecimal("12.50"))
val json = source.toJsonString()
val decoded = json.toObject<OrderView>()
val tree = json.toObjectNode()
val copied = decoded.deepCopy()
val properties = decoded.toLinkedHashMap()
```

| API | 用途 |
|---|---|
| `toJsonString()` / `toPrettyJson()` | 写入紧凑或格式化 JSON |
| `String.toObject<T>()` | 将 JSON 读取为具体类型 |
| `toJsonNode()` / `toObjectNode()` | 在对象与 Jackson tree model 之间转换 |
| `convert<T>()` | 按 Jackson 属性映射转换对象 |
| `deepCopy()` | 通过 `convertValue` 创建同类型副本 |
| `toLinkedHashMap()` | 将对象转换为保持属性顺序的 Map |

这些函数使用 Wow 的全局配置，不等价于调用方自行创建的 Mapper。

## WowModule

`WowModule` 为下列框架类型注册专用序列化器：

- `AggregateId`
- `CommandMessage`
- `DomainEventStream` 与 `DomainEvent`
- `StateAggregate`
- `Snapshot`
- `StateEvent`

它还注册 `MissingTypeImplProblemHandler`。不要单独复制这些 serializer 注册；直接使用模块：

```kotlin
import me.ahoo.wow.serialization.WowModule
import tools.jackson.module.kotlin.jsonMapper

val mapper = jsonMapper {
    addModule(WowModule())
}
```

### 自动注册

Spring Boot Starter 在 Jackson 自动配置前提供 `WowModule` Bean。`wow-core` 同时通过
`META-INF/services/tools.jackson.databind.JacksonModule` 发布该模块，`JsonSerializer.findAndAddModules()`
会自动发现它。

Spring 管理的 Mapper 继续使用 Spring Boot 自身的 feature 配置；`WowModule` 只增加 Wow serializer、
deserializer 与 Handler，不会复制 `JsonSerializer` 的全部全局 feature。

如果应用完全替换 Spring 的 `ObjectMapper` 或禁用模块发现，需要自行注册 `WowModule`。

## 缺失多态类型回退

`MissingTypeImpl` 与 `MissingTypeImplProblemHandler` 位于公共的 `wow-api` 模块。注解只声明默认实现；
只有注册 Handler 后，缺少 Jackson type id 的 JSON 才会使用该实现。

```kotlin
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import me.ahoo.wow.api.serialization.MissingTypeImpl

@MissingTypeImpl(Expression.Field::class)
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(Expression.Field::class, name = "FIELD"),
    JsonSubTypes.Type(Expression.Constant::class, name = "CONSTANT"),
)
sealed interface Expression {
    data class Field(val field: String) : Expression
    data class Constant(val value: Double) : Expression
}
```

注册公共 Handler：

```kotlin
import me.ahoo.wow.api.serialization.MissingTypeImplProblemHandler
import tools.jackson.databind.json.JsonMapper

val mapper = JsonMapper.builder()
    .addHandler(MissingTypeImplProblemHandler())
    .build()
```

该 builder 示例只展示 Handler 注册；调用方仍需添加自己的 Kotlin 或其他数据类型模块。

### 精确语义

| 输入 | 结果 |
|---|---|
| `{"field":"amount"}` | base type 有 `@MissingTypeImpl` 且 Handler 已注册时，读取为 `Field` |
| `{"type":"CONSTANT","value":10}` | 继续使用 Jackson 的已知 subtype 解析 |
| `{"type":"UNKNOWN"}` | Handler 不接管，遵循调用方的 `FAIL_ON_INVALID_SUBTYPE` 配置 |
| 缺少 type 且 base type 没有注解 | 保留 Jackson 原生缺失类型错误 |

Handler 只覆盖 `handleMissingTypeId`，不会处理未知 type id，也不会修改任何全局 Jackson 特性。

`@MissingTypeImpl` 是直接契约，不沿类或接口层级继承。反序列化的具体 base type 必须显式标注；
这避免了接口多继承优先级不明确，或父类型的默认实现并非子类型实现。默认实现还必须是 base type
的合法 subtype，否则 Jackson 无法构造 specialized type。

## AggregationExpression 兼容边界

`AggregationExpression` 使用 `@MissingTypeImpl(AggregationExpression.Field::class)`。通过
`WowModule` 或显式 Handler，旧请求仍可省略 type：

```json
{"field":"amount"}
```

新调用方应发送显式判别器：

```json
{"type":"FIELD","field":"amount"}
```

裸 Mapper 会拒绝第一种形式。由于 `JsonTypeInfo.defaultImpl` 已移除，生成的 OpenAPI Schema 也将
`type` 标记为必填；运行时兼容旧 JSON 不代表生成合同仍将 type 描述为可选。

## 持久化与测试

事件流和快照是长期 wire 合同。更换 Mapper、模块或类型注解前，至少验证：

1. 当前生产 Mapper 能读取真实历史事件与快照。
2. 已知多态类型可以序列化后再反序列化。
3. 需要兼容的缺失 type JSON 使用实际注册路径测试。
4. 未知 type 仍按应用的失败策略处理，而不是静默回退。
5. JSON Schema 与 OpenAPI 的判别器、required 字段和递归引用符合目标合同。

相关内容：

- [事件演进](./event-evolution)
- [JSON Schema](./schema)
- [Kafka 扩展](../extensions/kafka)

源码：

- [`JsonSerializer.kt`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/serialization/JsonSerializer.kt)
- [`WowModule.kt`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/serialization/WowModule.kt)
- [`MissingTypeImpl.kt`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-api/src/main/kotlin/me/ahoo/wow/api/serialization/MissingTypeImpl.kt)
- [`SerializationAutoConfiguration.kt`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/serialization/SerializationAutoConfiguration.kt)
