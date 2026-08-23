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

`SchemaGeneratorBuilder` 通过流式属性 setter 进行配置（没有 `standard()` 工厂方法）。
每个 builder 构建一个 `com.github.victools.jsonschema.generator.SchemaGenerator`：

```kotlin
val generator = SchemaGeneratorBuilder()
    .build()

// victools SchemaGenerator.generateSchema 直接返回 JsonNode
val jsonNode: JsonNode = generator.generateSchema(CreateOrder::class.java)
```

builder 已预配置 Wow 模块（Jackson、Jakarta Validation、Swagger2、Kotlin、Joda Money、Wow 命名）
以及合理默认值（`openapi31 = true`、`DRAFT_7`、`PLAIN_JSON` 预设）。可在调用 `build()` 前
覆盖任意属性。

### 生成 OpenAPI Schema

`OpenAPISchemaBuilder` 生成 OpenAPI 的 `io.swagger.v3.oas.models.media.Schema` 引用，
并将其收集到 `components/schemas` 下。使用 `generateSchema(...)` 生成单个类型的引用，
使用 `build()`（无参）收集所有被引用的 Schema：

```kotlin
val openApiBuilder = OpenAPISchemaBuilder(defaultSchemaNamePrefix = "")
// 单个类型的引用（或内联）Schema
val schema: Schema<*> = openApiBuilder.generateSchema(CreateOrder::class.java)
// 目前累积的所有 Schema，以组件名作为键
val components: Map<String, Schema<*>> = openApiBuilder.build()
```

构造函数第一个参数是 `defaultSchemaNamePrefix`（被 `SchemaNamingModule` 用于为组件名加前缀），
并非上下文名称。

## 支持的类型

Wow 提供了专门的 `TypedCustomDefinitionProvider` 实现与模块，为框架类型及 Kotlin/Joda 类型提供 Schema：

| 类型 / 模块 | Schema 处理 |
|------|------------|
| `AggregateId`、`DomainEventStream`、`FilterExpression` 查询模型 | 通过 `WowSchemaLoader` 从内置 JSON Schema 资源加载（复杂对象，非展平的基础类型） |
| `Map<K, V>`（`MapDefinitionProvider`） | 带附加属性的对象 |
| `CharRange` / `IntRange` / `LongRange`（`KotlinModule`） | 带 `start` 和 `end` 属性的对象 |
| `CurrencyUnit`（`JodaMoneyModule`） | 带 `format: currency` 的字符串 |
| `Money`（`JodaMoneyModule`） | 包含 `currency` 和 `amount` 的结构化对象 |
| 枚举（Jackson） | 字符串枚举定义（`FLATTENED_ENUMS_FROM_JSONVALUE`/`JSONPROPERTY`） |
| 可空的 Kotlin 类型（`KotlinNullableCheck`） | 在类型联合中加入 `null` |
| `@Summary` / `@Description` | 解析为 Schema 的 `title` / `description` 元数据 |

查询契约统一使用 v2 `FilterExpression` Schema。

## OpenAPI 模块如何使用 Schema

`wow-openapi` 模块通过 `OpenAPIComponentContext` 将 Schema 生成接入 OpenAPI 规范。
在上下文启动时，它会构建一个由 `SchemaGeneratorBuilder` 支撑的 `OpenAPISchemaBuilder`，
并为限界上下文中注册的每个命令体、事件负载和快照状态调用 `generateSchema(type)`。
生成的 `io.swagger.v3.oas.models.media.Schema` 实例填充 OpenAPI 的 `components/schemas` 部分，
Swagger UI 会渲染它，`wow-apiclient` 也会消费它来构建类型安全的 REST 客户端。

```kotlin
// wow-openapi 内部的简化装配（OpenAPIComponentContext.of）
val schemaGeneratorBuilder = SchemaGeneratorBuilder().schemaVersion(SchemaVersion.DRAFT_2020_12)
val schemaBuilder = OpenAPISchemaBuilder(
    defaultSchemaNamePrefix = "",
    schemaGeneratorBuilder = schemaGeneratorBuilder,
)
// 在构建路由时遇到的每个命令/事件类型：
val commandSchema: Schema<*> = schemaBuilder.generateSchema(CreateOrder::class.java)
```

通常你无需直接调用它——应用 `wow-compiler`（KSP）并向服务端添加 `wow-openapi` 即可。
Schema 生成在运行时自动完成。

## 自定义生成器

`SchemaGeneratorBuilder` 暴露流式属性 setter。在调用 `build()` 前覆盖任意属性：

```kotlin
val generator = SchemaGeneratorBuilder()
    .schemaVersion(SchemaVersion.DRAFT_2020_12)   // 默认 DRAFT_7
    .openapi31(false)                              // 默认 true
    .customizer {                                  // 添加 victools Option/Module 调整
        it.without(Option.SCHEMA_VERSION_INDICATOR)
    }
    .build()
```

| Builder 属性 | 默认值 | 用途 |
|---|---|---|
| `openapi31` | `true` | 输出 OpenAPI 3.1 兼容结构（`nullable` 作为类型联合成员） |
| `schemaVersion` | `DRAFT_7` | JSON Schema 方言关键字解析 |
| `optionPreset` | `PLAIN_JSON` | 包含哪些成员（字段、getter 等） |
| `jacksonModule` | Wow Jackson 模块 | 遵循 `@JsonProperty`/`@JsonIgnore`/枚举展平 |
| `jakartaValidationModule` | 启用 | 将 `@NotNull`/`@Size`/`@Min` 呈现为 Schema 约束 |
| `swagger2Module` | 启用 | 将 `@Schema` 注解呈现为 OpenAPI 元数据 |

## 框架类型 Schema

无法通过反射推导的框架类型（`AggregateId`、`DomainEventStream`、查询模型）以 JSON Schema 资源形式
打包在 `META-INF/wow-schema/<TypeName>.json` 下，由 `WowSchemaLoader` 加载。这使得生成的 Schema
在 Wow 版本间保持稳定，即使内存表示发生变化。
