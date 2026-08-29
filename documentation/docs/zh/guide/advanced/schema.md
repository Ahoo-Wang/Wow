---
title: JSON Schema
description: 从领域类型生成 JSON Schema 与 OpenAPI components，并明确区分编译元数据和运行时查询模型 Schema。
---

# JSON Schema

Wow 中的“Schema”可能指不同产物，不能相互混用：

| 产物 | 生成方 | 消费方 | 用途 |
|---|---|---|---|
| `META-INF/wow-metadata.json` | `wow-compiler` KSP | `MetadataSearcher` | 限界上下文、聚合、命令、事件、路由 |
| JSON Schema | `SchemaGeneratorBuilder` / 内置资源 | 校验、工具、OpenAPI 转换 | Java/Kotlin 类型的线协议形状 |
| OpenAPI components | `OpenAPISchemaBuilder` | `RouterSpecs`、Springdoc、生成器 | HTTP operation 引用的 Schema |
| 查询模型 Schema | 查询 Schema sources + 后端适配器 | 查询 resolver 与 `snapshot/schema`、`event/schema` 路由 | 逻辑字段及后端已证明能力 |

KSP 还会为聚合状态导航生成 `*Properties` 路径常量。这些常量不会枚举运行时后端能力，也不能替代查询模型 Schema。

## 特性

- 从命令、事件、快照及应用类型生成 JSON Schema。
- 读取 Jackson、Jakarta Validation、Swagger、Kotlin 与 Joda Money 元数据。
- 支持 OpenAPI 3.1 兼容的 nullable 形状。
- 对不应依赖反射实现细节的框架类型使用稳定内置定义。
- 把累积 definitions 转换为 OpenAPI `Schema` components 与引用。

Schema 生成描述序列化形状，不会注册路由、创建数据库映射、授权字段，也不能证明 MongoDB/Elasticsearch 能执行某个操作符。

## 安装

```kotlin [Gradle(Kotlin)]
implementation("me.ahoo.wow:wow-schema")
```

应用通常通过相关 Wow capability 间接获得该模块。只有应用代码直接调用 Builder 或消费其类型时才需要显式添加。

## 使用

### 生成 JSON Schema

`SchemaGeneratorBuilder` 是流式 Builder，不存在 `standard()` 工厂：

```kotlin
val generator = SchemaGeneratorBuilder().build()
val schema: JsonNode = generator.generateSchema(CreateOrder::class.java)
```

默认 Builder 使用 `SchemaVersion.DRAFT_7`、`OptionPreset.PLAIN_JSON` 与 `openapi31 = true`，并安装 Wow 的 Jackson、Jakarta Validation、Swagger2、Kotlin、Joda Money、命名和框架模块。读取 `requiredTypeContent` 前必须先调用 `build()`。

生成过程基于运行时类型与已注册序列化器。KSP 元数据 JSON 不是该调用的输入。

### 生成 OpenAPI Schema

`OpenAPISchemaBuilder.generateSchema(...)` 返回引用（配置 inline 时返回内联 Schema），同时记录所需 definitions。调用无参数 `build()` 收集 components：

```kotlin
val builder = OpenAPISchemaBuilder(defaultSchemaNamePrefix = "example.")

val requestSchema: Schema<*> = builder.generateSchema(CreateOrder::class.java)
val components: Map<String, Schema<*>> = builder.build()
```

`defaultSchemaNamePrefix` 只为 component name 添加前缀，不是限界上下文选择器。`definitionPath` 默认为 `components/schemas`，生成的 `$ref` 会重定基准到该位置。

应用管线不要每生成一个类型就调用一次 `build()`。应让所有路由贡献者先请求 Schema，再统一完成 component context，确保引用一致合并。

## 支持的类型

| 类型 / 模块 | Schema 行为 |
|---|---|
| `AggregateId`、消息、事件流、快照、状态聚合 | 通过 `WowSchemaLoader` 加载内置框架定义 |
| `FilterExpression` | 以 `META-INF/wow-schema/FilterExpression.json` 打包的规范 v2 查询 Schema |
| `AggregationExpression`、`QuerySemanticType` | 带显式 `type` 判别字段的多态 Schema |
| `Map<K, V>` | 带 additional properties 的 object |
| `CharRange`、`IntRange`、`LongRange` | 包含 `start` 和 `end` 的 object |
| `CurrencyUnit` | `currency` format 的 string |
| `Money` | 包含 currency 与 amount 的 object |
| Kotlin nullable 类型 | 在 Schema union 中包含 null |
| Jackson enum | 配置后生成扁平 string enum |
| `@Summary`、`@Description`、Swagger `@Schema` | title、description、discriminator 与 composition 元数据 |

规范 single/list/cursor/paged/count JSON 文件位于 `schema/query/v2`。只有 `FilterExpression` 会复制到 `wow-schema`，作为反射生成期间使用的自定义框架定义。

## OpenAPI 模块如何使用 Schema

`OpenAPIComponentContext.default(...)` 使用 Draft 2020-12 创建用于 OpenAPI component 的 `SchemaGeneratorBuilder`。路由贡献者向该 Context 请求命令、事件、快照、状态、查询、响应、请求头与请求体 Schema；`RouterSpecs` 把引用渲染进 OpenAPI 3.1，并合并完成后的 component maps。

```kotlin
val context = OpenAPIComponentContext.default(
    inline = false,
    defaultSchemaNamePrefix = currentContext.getContextAliasPrefix(),
)
val commandSchema = context.schema(CreateOrder::class.java)
```

Spring Boot `OpenAPIAutoConfiguration` 会提供该 Context；Springdoc 存在时还会提供 `WowOpenApiCustomizer`。WebFlux runtime 消费同一路由目录，但 Schema Builder 不创建 Handler function。

OpenAPI 查询发布包含两个静态层次，以及之后的一个运行时层次：

1. 通用 component schemas 定义 `FilterExpression`、`SingleQuery`、`ListQuery`、`CursorQuery`、`PagedQuery` 与 `AggregationQuery` 的规范 JSON 形状。
2. 每个聚合专用 request-body component 引用相应通用 Schema，并增加 `x-wow-query-fields`。该扩展引用一个静态 enum，其中包含 system fields 与 `JsonQuerySchemaSource` 从聚合状态推断出的字段；它不包含后端 binding 或已证明能力。
3. `GET /{aggregate}/snapshot/schema` 与 `GET /{aggregate}/event/schema` 分别返回 Snapshot、EventStream 的运行时 `QueryModelSchemaMetadata`：所有已配置查询 Schema sources 合并后，再由所选后端适配器解析能力；对应的 `/refresh` 刷新该运行时视图。

静态字段扩展让 OpenAPI 工具能够看到聚合字段，但不会改变通用请求 JSON 形状，也不能与运行时 Schema 等同。

客户端生成是更下游的消费者。Fetcher 或其他生成器读取已发布 OpenAPI 文档；修改 Kotlin 类型、discriminator、component name 或路由都可能改变生成客户端。KSP 元数据生成本身不会生成这些客户端。

## 自定义生成器

在 `build()` 前覆盖 Builder 属性：

```kotlin
val generator = SchemaGeneratorBuilder()
    .schemaVersion(SchemaVersion.DRAFT_2020_12)
    .openapi31(false)
    .customizer { config ->
        config.without(Option.SCHEMA_VERSION_INDICATOR)
    }
    .build()
```

| Builder 属性 | 默认值 | 作用 |
|---|---|---|
| `openapi31` | `true` | OpenAPI 3.1 兼容 nullable 处理 |
| `schemaVersion` | `DRAFT_7` | JSON Schema 关键字方言 |
| `optionPreset` | `PLAIN_JSON` | 基础 field/getter 纳入规则 |
| `jacksonModule` | Wow Jackson module | Jackson 名称、忽略、enum 值、顺序 |
| `jakartaValidationModule` | 启用 | Jakarta 约束 |
| `swagger2Module` | 启用 | Swagger Schema 注解 |
| `kotlinModule` | 启用 | Kotlin nullability、required/read-only/write-only |
| `jodaMoneyModule` | 启用 | Joda Money 线类型 |
| `wowModule` | 启用 | 框架定义与查询判别字段处理 |

传入 `null` 会禁用可选模块。这会改变生成合同；发布前应使用 Schema snapshot 或聚焦断言覆盖自定义配置。

## 框架类型 Schema

`WowSchemaLoader` 读取 `META-INF/wow-schema/{TypeName}.json`。内置文件为框架 wrapper 保持稳定公共形状，其内部类图并不是线协议合同。

这种稳定性只覆盖 Schema 资源及其序列化合同，并不承诺每个实现类的源码或二进制兼容性。Schema 资源变化时应验证：

1. `wow-schema` 生成测试；
2. `wow-openapi` component 与路由快照；
3. 真实 JSON 序列化/反序列化；
4. 存在 OpenAPI 消费方时的下游客户端生成。

对于聚合字段，应区分反射 JSON 形状与运行时查询模型。属性可能存在于 JSON Schema，却没有 `SORT`、`RANGE`、`FULL_TEXT` 或聚合能力，因为所选后端无法证明兼容物理绑定。
