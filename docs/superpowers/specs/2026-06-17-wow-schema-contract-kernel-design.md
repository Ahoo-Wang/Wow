# Wow Schema 契约内核重构设计

## 状态

用户已于 2026-06-17 确认本设计方向。

## 背景

上一轮 `wow-openapi` 重构已经引入显式的路由契约内核，并保持了 RESTful API 兼容。下一层自然边界是 `wow-schema`，因为它负责生成 OpenAPI components、文档和下游 API 消费者看到的 schema 形状。

当前 `wow-schema` 能正常工作，但多个职责集中在少数类中：

- `SchemaGeneratorBuilder` 同时负责 VicTools 配置、模块注册、选项设置和 `TypeContext` 生命周期。
- `WowModule` 把所有 Wow 领域 typed definition provider 平铺注册在一个类里。
- `OpenAPISchemaBuilder` 同时处理 schema 生成、引用跟踪、JSON Schema 到 Swagger `Schema` 的转换，以及 definitions 收集。
- `SchemaMerger` 手动复制大量 Swagger `Schema` 字段，并残留了注释掉的 merge 行。
- `WowSchemaLoader` 从 `META-INF/wow-schema` 加载资源模板，这些模板实际上是契约资产，但目前还没有被当作独立子系统看待。

这次重构的核心目标是：**保持外部契约稳定，同时改善内部架构边界**。

## 目标

- 默认保持生成的 JSON Schema 和 OpenAPI Schema 输出兼容。
- 保持 `wow-schema`、`wow-openapi` 以及 RESTful route 生成的 public API 兼容。
- 让 schema 生成链路的职责更清晰、更容易测试。
- 减少 schema 生成路径中的隐藏可变状态和隐式生命周期。
- 本轮继续保持 `wow-schema` 为单一 Gradle 模块。
- 为后续 `wow-apiclient` 和 `wow-compiler` 重构留下清晰边界。

## 非目标

- 不把 `wow-schema` 拆成新的 Gradle 模块。
- 不改变 REST route、HTTP method、route ordering、operationId 或 WebFlux runtime routing。
- 不主动改变 schema 命名规则或资源模板内容。
- 不重新设计 Wow 领域注解或 metadata 格式。
- 不替换 VicTools 或 Swagger Core。
- 不把 `wow-schema` 合并进 `wow-openapi`。

## 架构设计

本轮重构只建立内部边界，保留现有 public entry point。

### Schema Generation Kernel

`SchemaGeneratorBuilder` 继续作为 public builder facade。内部可以把默认模块装配、options 和 VicTools config 创建委托给更聚焦的协作者。

职责：

- 每次 build 只创建一份 VicTools config。
- 创建并暴露与该 config 匹配的 `TypeContext`。
- 保留现有 builder 方法和默认值。
- 保持 schema generator 生命周期确定。

它不应该承载 Wow typed schema 规则，除了安装已配置的模块。

### Wow Schema Contributors

`WowModule` 继续作为 VicTools module 入口，但 provider 注册应收口到一个小型 registry 后面。

职责：

- 保持当前 provider 注册顺序。
- 按关注点分组 provider，例如 message、event stream、aggregate state、query、map/enum、web/SSE。
- 保持 `WowOption.IGNORE_COMMAND_ROUTE_VARIABLE` 行为不变。
- 让维护者容易看出哪个 provider 负责哪类 schema。

这是内部清理，不是 public extension API 重新设计。

### OpenAPI Schema Adapter

`OpenAPISchemaBuilder` 继续作为面向 OpenAPI 的 public builder。

职责：

- 通过 generation kernel 解析 JVM/Kotlin 类型。
- 生成 JSON Schema definitions。
- 将 JSON Schema node 转换成 Swagger `Schema`。
- 跟踪 schema reference，并把收集到的 definition 合并回已返回的 reference schema。

转换、引用跟踪、definition 收集可以拆到 private 或 internal 协作者中，但 `resolveType`、`generateSchema`、`build` 这三个外部方法必须保持兼容。

### Schema Resource Templates

`META-INF/wow-schema/*.json` 文件是契约模板。`WowSchemaLoader` 继续保持现有资源路径，但需要补足围绕模板查找和转换的测试与错误行为。

职责：

- 保持资源名称和查找路径不变。
- 缺失模板时给出清晰失败。
- typed provider 继续负责把领域值应用到模板上。
- 除非单独确认 bugfix，否则不修改模板 JSON 内容。

## 数据流

目标数据流保持不变：

```text
Kotlin 或 Java Type
  -> Schema Generation Kernel
  -> VicTools JSON Schema
  -> OpenAPI Schema Adapter
  -> wow-openapi components.schemas
  -> Swagger UI 和 API 消费者
```

route contract 流程不属于本轮重构范围。

## 兼容性护栏

兼容性是本轮最重要的约束。

### JSON Schema 快照

使用现有 `wow-schema` e2e 资源作为基线，重点包括：

- `wow-schema/src/test/resources/META-INF/wow-schema-e2e/`
- `wow-schema/src/test/resources/META-INF/wow-schema/`

除非用户单独确认 bugfix，否则重构后不应产生 snapshot diff。

### OpenAPI Contract 快照

schema 改动后必须运行 `wow-openapi` contract 测试。重点关注以下 schema 表面：

- command message body
- aggregate state schema
- snapshot schema
- event stream schema
- query condition schema
- Server-Sent Events schema

### RESTful API 兼容

本轮重构不得影响：

- route path
- HTTP method
- operationId
- route sort priority
- handler key
- WebFlux runtime route registration

## 实施阶段

### 阶段 1：兼容性基线

- 运行 `./gradlew :wow-schema:check`。
- 运行 `./gradlew :wow-openapi:check`。
- 明确当前 snapshot 基线，在基线干净前不改生成逻辑。
- 如果发现基线不稳定，先稳定测试，不改变生成语义。

### 阶段 2：内部边界清理

按小步、可验证的方式重构：

- 清理 `SchemaGeneratorBuilder` config 创建流程，移除重复 build 行为。
- 为 `WowModule` 引入内部 provider registry。
- 从 `OpenAPISchemaBuilder` 抽出 OpenAPI 转换和 reference tracking helper。
- 澄清 `SchemaMerger` 行为，并移除没有解释的注释残留。
- 加强 `WowSchemaLoader` 关于资源查找和失败模式的测试。

每一步都必须保持 snapshot 和 public method signature 兼容。

### 阶段 3：验证与债务收口

- 运行 `./gradlew :wow-schema:check`。
- 运行 `./gradlew :wow-openapi:check`。
- 如果触碰 OpenAPI 渲染行为，运行 example server 并检查 Swagger UI 启动和渲染。
- 搜索过渡命名、未使用类、注释残留和 compatibility shim，避免留下新债。

## 风险

- `KotlinCustomDefinitionProvider` 使用 singleton mutable cache 避免递归 definition 问题。替换或局部化该状态时必须保留递归处理语义。
- `WowJacksonModule` 依赖 VicTools builder 提供的 ObjectMapper 生命周期。清理时必须保持 Jackson ignore 和 unwrapped property 行为。
- `SchemaMerger` 位于 reference merge 关键路径。机械替换可能改变 enum、examples、nullable 或组合 schema 行为。
- provider 注册顺序可能影响生成 schema 的顺序。必须保持顺序稳定。

## 测试

实现完成前必须运行：

```bash
./gradlew :wow-schema:check
./gradlew :wow-openapi:check
```

如果触碰 OpenAPI 渲染行为，再运行：

```bash
./gradlew :example-server:run
```

实施计划应优先在每个小步骤后运行窄测试，并在提交前运行完整模块检查。

## 审核重点

审核时重点看：

- public API 兼容性
- snapshot 稳定性
- provider 注册顺序
- 状态生命周期清理
- OpenAPI schema 转换与 reference merge 行为

任何行为变化都不应该隐藏在架构清理里。
