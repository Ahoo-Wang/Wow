# EventStream 聚合与查询 Schema 设计

## 背景

`AggregationQuery` 已由 Snapshot 查询链路、MongoDB 和 Elasticsearch 实现，但 `EventStreamQueryService` 明确不支持聚合。EventStream 的 single、list、paged 和 count 也直接把逻辑字段交给后端，不经过 `QueryModelSchemaProvider`。

只给 EventStream 后端调用现有聚合编译器并传入 `schema = null` 虽然能够执行简单查询，但会把逻辑字段直接当作物理字段，跳过字段类型、Element scope、聚合 capability 和 Elasticsearch mapping binding。这不构成与 Snapshot 对等的跨后端能力。

本设计为 EventStream 增加完整 Query Schema，并在同一条受策略保护的查询链路中开放 `AggregationQuery`。

## 目标

- `EventStreamQueryService`、`EventStreamQueryHandler` 和 Kotlin 查询扩展支持 `AggregationQuery`。
- MongoDB 与 Elasticsearch EventStream 服务执行相同的聚合合同。
- 新增 `QueryModel.EVENT_STREAM`，为 EventStream 固定 wire shape 提供系统 Schema。
- EventStream 的 single、list、paged、count 和 aggregation 统一经过同一个 Schema Provider。
- 默认 `COMPATIBLE` 模式保持未声明 payload 字段的现有透传行为；`STRICT` 模式要求字段和 capability 完整声明。
- 复用现有 Schema source、resolver、compiler 和 backend adapter，不复制平行实现。
- 现有第三方 `EventStreamQueryService` 与 `EventStreamQueryHandler` 实现仍可加载和编译。

## 非目标

- 不新增 EventStream HTTP、OpenAPI 或 Schema HTTP 端点。
- 不从聚合状态类型推断事件 payload，也不扫描或合并领域事件类型。
- 不改变 Elasticsearch EventStream 模板中 `body.body.enabled = false` 的约束。
- 不增加依赖、Gradle 模块、配置项、CI/CD 或发布逻辑。
- 不把 `aggregate` 提升到通用 `QueryService`/`QueryHandler`。
- 不增加独立 EventStream Schema Adapter、Schema 注册 SPI 或兼容桥。

## 公共合同与兼容性

`QueryModel` 增加常量：

```kotlin
val EVENT_STREAM = QueryModel("EVENT_STREAM")
```

`EventStreamQueryService` 增加：

```kotlin
fun aggregate(query: AggregationQuery): Flux<DynamicDocument> =
    Flux.error(UnsupportedOperationException("Event stream aggregation is not supported."))
```

默认实现避免要求已有第三方实现立即新增方法。`EventStreamQueryHandler` 使用相同策略增加默认方法，`DefaultEventStreamQueryHandler` 则把请求放入 `QueryType.AGGREGATION` 上下文。Spring 的 `EventStreamQueryServiceProxy` 必须显式转发至 handler，确保授权、query rewrite 和其他现有过滤器不能被注入式服务绕过。

`AggregationQuery.query(EventStreamQueryService)` 只作为现有 Snapshot 扩展的对称入口，不新增 DSL 模型。

`EventStreamQueryService.requiredQueryModelSchemaProvider()` 与 Snapshot 的现有扩展保持一致：需要读取 Schema 的低层调用方可以显式要求能力；普通查询调用方不感知 Provider。

## EventStream 系统 Schema

`SystemQuerySchemaSource` 按 `QueryModel` 返回不可变声明。`EVENT_STREAM` 声明以 `MessageSerializer` 和 `AbstractEventStreamJsonSerializer` 的实际 wire shape 为准：

| 字段 | 类型 | Cardinality | 说明 |
| --- | --- | --- | --- |
| `id` | STRING | SINGLE | EventStream 消息 ID |
| `contextName` | STRING | SINGLE | 限界上下文 |
| `aggregateName` | STRING | SINGLE | 聚合名 |
| `name` | STRING | SINGLE | EventStream 消息名 |
| `header` | OBJECT | SINGLE | 允许动态子字段 |
| `aggregateId` | STRING | SINGLE | 聚合 ID |
| `tenantId` | STRING | SINGLE | 租户 ID |
| `ownerId` | STRING | SINGLE | 所有者 ID |
| `spaceId` | STRING | SINGLE | 空间 ID |
| `commandId` | STRING | SINGLE | 命令 ID |
| `requestId` | STRING | SINGLE | 请求 ID |
| `version` | INTEGER | SINGLE | 事件流版本 |
| `createTime` | INTEGER + epoch milliseconds | SINGLE | 创建时间 |
| `body` | OBJECT | MANY | 事件数组，可作为 Element scope |
| `body.id` | STRING | SINGLE | 事件 ID |
| `body.name` | STRING | SINGLE | 事件名 |
| `body.revision` | STRING | SINGLE | 事件 revision |
| `body.bodyType` | STRING | SINGLE | 事件 payload 类型 |
| `body.body` | OBJECT | SINGLE | payload 容器，不允许未声明动态子字段 |

系统字段声明不包含 `body.body.*`。现有 `BeanQuerySchemaSource`、working-directory 和 classpath 约定已经按 `QuerySchemaContext(namedAggregate, model)` 精确匹配；调用方可为 `EVENT_STREAM` 显式声明允许查询的 payload 字段。

`JsonQuerySchemaSource` 默认只为 `QueryModel.SNAPSHOT` 推断聚合状态类型；收到其他 model 时返回空流。这样 EventStream 不会错误地出现 `state.*` 或把聚合状态字段投射到事件 payload。

## Schema 解析与兼容模式

EventStream 后端服务像 Snapshot 服务一样实现 `QueryModelSchemaProvider`，并覆盖 single、list、paged、count 和 aggregation 的 `resolve`：

1. Provider 合并系统声明与当前聚合、当前 model 的显式 sources。
2. Backend adapter 结合 MongoDB collection facts 或 Elasticsearch mapping 生成物理 bindings。
3. `QuerySchemaResolver` 校验并改写所有查询形态。
4. 后端只接收已经解析的查询；聚合编译器同时接收 resolved Schema。

默认 `QuerySchemaValidationMode.COMPATIBLE` 的现有规则保持不变：未声明字段是 `COMPATIBLE`，按原始路径透传；已声明但缺少所需 capability 的字段是 `INCOMPATIBLE` 并被拒绝。因此已有 MongoDB payload 查询不会仅因新增系统 Schema 而失效，严格治理可通过现有 `STRICT` 配置启用。

`body.body` 系统字段明确设置 `dynamicChildren = false`。显式 source 可以增加具体子字段；未声明 payload 字段在 `COMPATIBLE` 下仍沿用全局兼容语义，但在 `STRICT` 下被拒绝。

## 后端 Schema Adapter

不新增 EventStream 专用 Adapter。

`MongoQuerySchemaAdapter` 增加带默认值的 `model` 与 `FieldConverter` 构造参数。Snapshot 保持 `QueryModel.SNAPSHOT + SnapshotFieldConverter`；EventStream 使用 `QueryModel.EVENT_STREAM + EventStreamFieldConverter`，使逻辑 `id` 正确绑定 MongoDB `_id`。`QueryModelSchema.model` 使用传入 model，不再硬编码 Snapshot。

`ElasticsearchQuerySchemaAdapter` 增加带默认值的 `model`。EventStream 模板的字段路径与逻辑路径一致，`id` 也是 `_source` 中的 keyword，因此不需要新的 field converter。Adapter 继续从 mapping 识别 `body` 的 nested capability 和字段的 keyword/numeric/temporal capability，并把结果 model 设置为 `EVENT_STREAM`。

保留现有默认构造行为，避免破坏直接构造 Snapshot Adapter 的调用方和测试。

## 后端服务与工厂

MongoDB 和 Elasticsearch EventStream 工厂接收与 Snapshot 工厂相同的 `schemaSources` 和 `validationMode`，默认仍是 `emptyList()` 与 `COMPATIBLE`。Spring Boot 自动配置把已有 `List<QuerySchemaSource>` 与 `QueryProperties.schema.validationMode` 注入 EventStream 工厂；不增加新属性。

两种 EventStream 服务委托 Provider，并统一解析现有查询操作。聚合执行复用已有实现：

- MongoDB 使用 `MongoAggregationCompiler(EventStreamFilterConverter)`，传入 resolved Schema，并复用现有聚合结果规范化与空摘要逻辑。
- Elasticsearch 使用 `ElasticsearchAggregationCompiler(EventStreamFilterConverter)` 与 `ElasticsearchAggregationPager`，传入 EventStream index、batch size、keep-alive 和 resolved Schema。

现有编译器和 pager 从 Snapshot package 移到 backend-neutral 的 query aggregation package；不复制代码，不改变行为。

根 `filter` 作用于 EventStream 文档。`elements = [body]` 展开事件数组；Element filter、group 和 metric 字段相对该事件项，例如：

```kotlin
aggregation {
    element("body")
    terms("name", "eventName")
    count("count")
}
```

## payload 边界

MongoDB 保存可查询的 `body.body`，显式 EventStream Schema source 可以为 payload 字段提供能力。Elasticsearch 当前模板把 `body.body` 设置为 `enabled = false`，Adapter 因而不会为其子字段提供物理 capability；这些查询在严格解析时失败，不能伪装为空结果或降级为 `_source` 扫描。

本次跨后端保证范围是 EventStream envelope 与 `body` 事件元数据。若未来需要 Elasticsearch payload 聚合，应单独设计 mapping、索引迁移、历史数据重建和回滚方案。

## 错误处理

- 默认接口实现返回 `UnsupportedOperationException`，用于未升级的第三方后端。
- Schema 冲突、已声明字段 capability 不匹配和严格模式未知字段继续抛出现有 Query Schema 异常。
- `COMPATIBLE` 只沿用现有未知字段/Schema unavailable 回退规则，不新增 EventStream 特例。
- MongoDB pipeline、Elasticsearch mapping/PIT/aggregation 和结果解析错误直接传播。
- 无分组且没有匹配文档时，MongoDB 与 Elasticsearch 继续返回现有空摘要：Count 为 `0L`，其他 metric 为 `null`。
- 不捕获后端错误并返回空 Flux，不进行跨后端静默降级。

## 测试策略

### API 与 Schema

- `wow-api` 验证 `QueryModel.EVENT_STREAM` 值与 JSON 往返。
- `SystemQuerySchemaSourceTest` 验证完整 EventStream wire 字段、`body` MANY、`body.body` 非动态和 `createTime` epoch。
- `JsonQuerySchemaSourceTest` 验证 EventStream 不触发状态类型推断。
- Query Schema source 测试验证 `EVENT_STREAM` classpath/working-directory/bean 声明按 model 隔离。

### 查询链路

- EventStream handler 测试验证 `QueryType.AGGREGATION` 转发，不再在 Tail Filter 中拒绝。
- Spring proxy 测试验证 aggregate 必须经过 handler。
- EventStream Query DSL 测试验证扩展直接调用服务。
- NoOp 与第三方默认实现测试验证兼容错误合同。

### 后端与集成

- MongoDB Adapter 测试验证 `EVENT_STREAM` model 与 `id -> _id` binding。
- Elasticsearch Adapter 测试验证 `EVENT_STREAM` model、`body` nested 和事件 metadata capability。
- EventStream TCK 写入多个事件流，通过 `elements = [body]` 按 `name` 分组并 Count；MongoDB 与 Elasticsearch 运行同一测试。
- TCK 同时验证无分组摘要和根 filter，防止只覆盖 compiler 结构而未执行真实后端。
- EventStream service 集成测试验证 Schema model、系统字段和 refresh。
- Spring Boot 自动配置测试验证 sources 与 validation mode 传入 EventStream 工厂。

## 文档

公共 KDoc 说明 EventStream 聚合的 root/Element 相对路径语义，以及 Elasticsearch payload 限制。现阶段不新增 HTTP 使用文档或 OpenAPI 快照；若未来开放端点，再同步中英文 WebFlux 文档。

## 验证

实现阶段从最窄测试开始，完成前至少运行：

```bash
./gradlew \
  :wow-api:check \
  :wow-query:check \
  :wow-schema:check \
  :wow-spring:check \
  :wow-spring-boot-starter:check \
  :wow-mongo:check \
  :wow-elasticsearch:check

./gradlew \
  :wow-mongo:integrationTest \
  :wow-elasticsearch:integrationTest \
  --stacktrace
```

若生产文件触发 Detekt 范围，再运行根 `./gradlew detekt`。不以编译器 BSON/JSON 断言替代双后端真实 EventStream TCK。

## 完成条件

- EventStream 的所有查询形态使用同一 `EVENT_STREAM` Schema Provider。
- EventStream 聚合经 handler/proxy/filter 链执行，MongoDB 与 Elasticsearch 行为通过共享 TCK。
- 系统 Schema 与权威序列化 wire shape 一致，payload 不被自动推断。
- 默认兼容模式不收紧未声明 payload 字段；严格模式和 capability 错误保持 fail-closed。
- 不存在复制的 EventStream backend Schema Adapter 或聚合编译器。
- 未新增 EventStream HTTP/OpenAPI 路由、依赖、配置项或模块。
