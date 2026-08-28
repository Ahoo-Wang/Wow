# EventStream 聚合 REST API 设计

## 背景

EventStream 聚合已经由 `EventStreamQueryService`、`EventStreamQueryGateway`、Query Schema、MongoDB 和 Elasticsearch 实现，并经过后端共享 TCK 验证。当前缺口仅在 HTTP 边界：Event 路由已有 list、paged、count，但没有把现有聚合能力暴露为 REST API。

本设计在现有 Event 查询路由中增加 aggregation 端点。实现复用 Snapshot aggregation 已验证的请求提取、动态结果响应和异常链，同时保持 Event 路由自己的合同约定。

## 目标

- 为 EventStream 增加 `POST .../event/aggregation`。
- 复用 Event 查询现有的基础、tenant、owner 路由变体。
- 请求体使用公共 `AggregationQuery`。
- `Accept: application/json` 返回动态行数组。
- `Accept: text/event-stream` 逐行返回 SSE。
- 请求继续经过 tenant/owner rewrite、EventStream QueryGateway、Query Filter、HTTP guard、Schema 解析和后端聚合链。
- OpenAPI 与运行时路由来自同一个 route contract。
- 中英文 WebFlux 文档同步说明端点与 EventStream payload 能力边界。

## 非目标

- 不修改 `AggregationQuery`、Query DSL、`QueryService`、`EventStreamQueryGateway` 或后端聚合实现。
- 不新增 EventStream Schema HTTP 端点。
- 不扩展 `wow-apiclient`。
- 不新增 EventStream 专用 OpenAPI 请求组件或 `x-wow-query-fields`。
- 不重构 Snapshot aggregation handler，也不引入通用 aggregation handler 抽象。
- 不新增配置项、依赖、Gradle 模块、CI/CD 或发布逻辑。

## HTTP 合同

`EventRouteContributor` 使用现有 `tenantOwnerVariants`、`aggregatePath` 和 `eventRoute` 生成路由。新增合同的固定部分如下：

| 属性 | 值 |
| --- | --- |
| Method | `POST` |
| Path suffix | `event/aggregation` |
| Handler key | `wow.openapi.aggregate.event.aggregation` |
| Request body | 全局 `wow.AggregationQuery` 组件 |
| Accept | `application/json`、`text/event-stream` |
| Success response | 现有 aggregation `200` JSON/SSE 合同 |

实际完整路径继续由聚合 metadata 决定。对于适用的聚合，route contributor 与 event/list、event/paged、event/count 一样生成：

- 无作用域变体；
- tenant 变体；
- owner 变体。

OpenAPI 遵循其他 Event 查询路由的响应声明习惯，只在该 route contract 中显式声明 aggregation `200` 响应，不额外添加 Snapshot aggregation 当前声明的 `408`、`429` response refs。运行时已有 timeout、guard 与全局异常处理不因此改变。

## 组件设计

### Route contract

`BuiltInHttpRouteHandlerKeys.Event` 增加 `AGGREGATION`。`EventRouteContributor.queryRoutes` 在每个现有 tenant/owner variant 中增加 aggregation contract：

- `operation = "aggregation"`；
- `operationSummary = "Aggregate Event Stream"`；
- `resourceName = EVENT`；
- request body 使用 `aggregationQueryRequestBodyRef()`；
- response 使用 `aggregationResponse()`；
- streaming accept 与 Event list、Snapshot aggregation 保持一致。

不新增 EventStream 专用 request-body schema。运行时 EventStream Query Schema 仍负责字段解析与 capability 校验；OpenAPI 只表达公共 `AggregationQuery` wire contract。

### WebFlux handler

新增 `EventStreamAggregationHandlerFunction`，结构与现有 `SnapshotAggregationHandlerFunction` 对称，但依赖 `EventStreamQueryGateway`：

1. 使用现有 `AGGREGATION_QUERY_EXTRACTOR` 读取请求体；
2. 使用 `RewriteRequestFilter` 注入 route scope；
3. 调用 `EventStreamQueryGateway.aggregate(aggregateMetadata, query)`；
4. 使用 `writeRawRequest` 把当前 HTTP request 放入查询上下文；
5. 使用现有 `toServerResponse` 输出 JSON 或 SSE，并交给 `RequestExceptionHandler` 处理失败。

同文件提供 `EventStreamAggregationHandlerFunctionFactory`，绑定新增 handler key。保持独立 handler，避免为了两个调用点改造稳定的 Snapshot handler。

### Starter 注册

`QueryRouteModule` 把 `EventStreamAggregationHandlerFunctionFactory` 加入现有 Event 查询 factory 列表。无需新增 bean、条件注解或配置属性。

## 数据流

```text
POST .../event/aggregation
  -> Event route contract / RouterFunction
  -> AggregationQuery body extractor
  -> tenant/owner RewriteRequestFilter
  -> EventStreamQueryGateway.aggregate
  -> EventStream Query Filter chain + HTTP guard
  -> EventStream Query Schema resolution
  -> MongoDB or Elasticsearch aggregation
  -> JSON array or SSE rows
```

EventStream 聚合的字段语义保持现有服务合同：根 filter 作用于 EventStream 文档；`elements = [body]` 展开事件数组；后续 element/group/metric 字段相对当前 element。Elasticsearch 现有 `body.body` payload mapping 限制保持不变，REST API 不提供扫描或静默降级。

## 错误处理与安全边界

- JSON 反序列化、`AggregationQuery` 构造约束和公共 HTTP guard 继续拒绝非法输入。
- tenant/owner 只表达资源作用域；认证与主体绑定仍由应用安全层负责。
- Schema 冲突、字段 capability 不匹配、严格模式未知字段及后端异常沿现有查询链传播。
- 不捕获后端错误并返回空数组或空 SSE。
- 第三方 `EventStreamQueryService` 若继承默认不支持 aggregation 的实现，端点保留失败语义，不伪装为可用能力。
- JSON/SSE cancellation、idle timeout 和全局错误映射继续由现有 WebFlux 响应策略处理。

## 兼容性

该变更只新增 HTTP route、handler key 和 OpenAPI operation，不修改现有路径、DTO 或 JVM API。现有 Event list、paged、count、load、compensate 和 resend 路由保持不变。

新增 route 可能被应用现有的通配安全规则覆盖。应用升级时必须检查认证、授权、网关和限流策略是否按预期保护 `event/aggregation`；生成路由本身不构成授权证明。

## 文档

同步更新：

- `documentation/docs/zh/guide/extensions/webflux.md`
- `documentation/docs/en/guide/extensions/webflux.md`

文档说明 EventStream aggregation 与 Snapshot aggregation 使用相同的 JSON/SSE 响应方式，但请求字段来自 EventStream Schema；同时明确 Elasticsearch payload 聚合限制。

## 测试策略

### OpenAPI 与 route contract

- 验证基础、tenant、owner 三类 `event/aggregation` 路由。
- 验证 method、path suffix、route ID、handler key 和 aggregate handler metadata。
- 验证 request body 引用全局 `wow.AggregationQuery`。
- 验证 `200` 同时声明 JSON 数组和 SSE 响应。
- 更新并审查 OpenAPI 与 route-contract snapshots。

### WebFlux

- 新增一个聚焦的 `EventStreamAggregationHandlerFunctionTest`。
- 验证请求体被提取并传给 `EventStreamQueryGateway.aggregate`。
- 验证 route scope rewrite 后的 query 进入 gateway。
- 验证动态聚合行通过现有响应策略返回。
- 使用现有 route module/registrar 合同测试验证 factory 可物化；仅在现有覆盖不足时补最小断言。

### 不重复的验证

不新增 MongoDB/Elasticsearch 聚合集成用例。两种后端的 EventStream aggregation 已由共享 TCK 覆盖，本次数据和后端执行链未改变。

## 验证命令

```bash
./gradlew \
  :wow-openapi:check \
  :wow-webflux:check \
  :wow-spring-boot-starter:check

cd documentation
pnpm docs:build

git diff --check
```

## 完成条件

- 现有适用聚合生成基础、tenant、owner `event/aggregation` route contracts。
- WebFlux 能通过 EventStream QueryGateway 执行 `AggregationQuery`。
- JSON 与 SSE 响应合同通过 handler 和 OpenAPI 测试。
- OpenAPI 请求使用全局 `AggregationQuery`，不新增 Event 专用字段组件。
- Event 其他查询路由合同保持不变。
- 中英文文档同步且构建通过。
- 未修改后端、API client、依赖、配置或模块结构。
