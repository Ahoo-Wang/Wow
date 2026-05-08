---
title: JSON Schema
description: 基于 jsonschema-generator 自动从 Wow 领域模型生成 JSON Schema 和 OpenAPI Schema。
---

# JSON Schema

Schema 模块基于 [jsonschema-generator](https://github.com/victools/jsonschema-generator) 自动从 Wow 领域模型（Command、Event、Snapshot、AggregateId 和查询模型）生成 JSON Schema 和 OpenAPI Schema。

支持 Jackson 注解、Jakarta Validation、Swagger 注解和 Kotlin 类型系统的集成。

## 特性

- 从 Command、Event、Snapshot 类型自动生成 JSON Schema
- 支持 Jackson、Jakarta Validation 和 Swagger 注解
- Kotlin 特有类型处理（nullable、Range 等）
- OpenAPI 3.x Schema 输出
- Joda Money 类型支持

## 安装

添加 `wow-schema` 依赖：

=== "Gradle (Kotlin)"

```kotlin
implementation("me.ahoo.wow:wow-schema")
```

## 使用

### 生成 JSON Schema

```kotlin
val generator = SchemaGeneratorBuilder.standard()
    .build()

val schema: JsonSchema = generator.generateSchema(CreateOrder::class.java)
val jsonNode: ObjectNode = schema.toJsonNode()
```

### 生成 OpenAPI Schema

```kotlin
val openApiBuilder = OpenAPISchemaBuilder(contextName)
val schema = openApiBuilder.build(CreateOrder::class.java)
```

## 支持的类型

| 类型 | Schema 处理 |
|------|------------|
| `AggregateId` | UUID 格式的字符串 |
| `DomainEventStream` | 事件流包装 |
| `Map<K, V>` | 带附加属性的对象 |
| `CharRange` / `IntRange` / `LongRange` | 基于范围的约束 |
| `CurrencyUnit` / `Money` | Joda Money 类型 |
| 枚举 | 字符串枚举定义 |
