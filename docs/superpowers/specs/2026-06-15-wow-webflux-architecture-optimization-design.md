# wow-webflux 架构优化设计

## 背景

`wow-webflux` 是 Wow 运行时能力的 HTTP/WebFlux 适配层。它把 OpenAPI `RouteSpec`
元数据映射成 WebFlux `HandlerFunction<ServerResponse>`，把 HTTP 请求转换成 Wow
运行时输入，再把运行时输出转换成 JSON、SSE 和错误响应。

最近的 WebFlux 基准工作让这个模块的结构更容易观察，也暴露出几个架构压力点：

- `Responses.kt` 实际上是一个隐式响应策略中心。非 SSE 的 `Flux<T>` 当前会通过
  `collectList()` 先物化成列表，而 SSE 路径按条流式输出。
- Aggregate tracing 已经修掉了重复前缀重放带来的高复杂度，但生产路由默认仍然读取整段事件历史并返回所有 tracing 状态。
- 批处理操作在 handler 中直接使用 `flatMapSequential`，没有显式的并发、prefetch 或错误聚合策略。
- `WebFluxAutoConfiguration` 手工声明所有 route factory，route family 增长会持续集中到一个很大的 Spring 配置类里。
- 请求解析、错误映射、响应塑形和操作策略散落在多个 handler 中，没有被表达成清晰的 adapter 边界。

这次目标是架构清理，不是小范围局部优化。内部实现可以激进演进，但外部 HTTP API 必须保持兼容。

## 目标

- 保持现有 HTTP path、method、请求体、成功响应 JSON shape、SSE event shape 和 `ERROR_CODE` 响应头默认兼容。
- 把 `wow-webflux` 收敛成职责清楚的 adapter 层，显式表达 route registration、response protocol、request context、operation policy 和 error boundary。
- 响应策略优先使用 Reactor-first 模型。`Flux<T>` 默认应保持流式，不应先 `collectList()` 物化，除非结果明确有界或协议要求聚合。
- 实施过程中允许临时兼容 facade，但完成时必须清掉 facade 中隐藏的策略逻辑。
- 保留用户自定义 route handler 和请求定制扩展点。
- 保持模块边界：API contract 留在 `wow-api`，运行时行为留在 `wow-core`，OpenAPI route 描述留在 `wow-openapi`，HTTP/WebFlux 关注点留在 `wow-webflux` 和 `wow-spring-boot-starter` wiring 中。

## 非目标

- 不重新设计 Wow command、query、event sourcing 或 snapshot 的运行时语义。
- 不修改公开 route path 或成功响应 schema。
- 不让存储模块依赖 WebFlux。
- 不在 WebFlux 请求路径中引入阻塞代码。
- 不在新 response strategy 旁边永久保留第二套响应体系。

## 架构总览

目标架构包含 5 个内部单元：

1. `Route Registration`
   从 OpenAPI `RouteSpec` 元数据和 route-family module 构建 WebFlux 路由。
2. `Response Strategy`
   负责 JSON、streaming JSON array、command response、SSE 和序列化行为。
3. `Request Context`
   一次性提取 HTTP 派生事实，并向 handler 暴露语义化请求数据。
4. `Operation Policies`
   承载 adapter 层决策，例如 batch execution、tracing window、query response mode、command wait timeout 和 remote wait notification。
5. `Error Boundary`
   一致地把异常转换为 HTTP error、SSE error event 和 stream failure 行为。

Handler 的目标形态应变成薄编排：

```text
ServerRequest
  -> WowWebRequestContext
  -> operation handler / runtime dependency
  -> WebFluxResponseStrategy
  -> ServerResponse
```

## Route Registration

现有 `RouteHandlerFunctionFactory<R : RouteSpec>` 的方向是正确的：它是 OpenAPI route spec 和 WebFlux handler 的核心扩展点。需要清理的是 factory 的分组和注册方式。

### 目标状态

- 引入内部 route-family module，例如：
  - `CommandRouteModule`
  - `QueryRouteModule`
  - `EventRouteModule`
  - `SnapshotRouteModule`
  - `StateRouteModule`
  - `GlobalRouteModule`
  - `WaitRouteModule`
- 每个 module 拥有一个 route family 的 factory 列表，以及该 family 所需的依赖组合。
- `WebFluxAutoConfiguration` 退化成小型 assembler。它暴露共享 strategy/policy bean，收集 route module 或 factory，并构建最终的 `RouterFunction<ServerResponse>`。
- `RouteHandlerFunctionRegistrar` 在启动期构建不可变索引：`Class<out RouteSpec>` -> `RouteHandlerFunctionFactory<*>`。
- 同一个 `RouteSpec` 出现多个 factory 时行为必须确定。优先使用显式 ordering 和清晰诊断，不要静默替换。
- 找不到 factory 时，错误信息应包含 route path、method、spec class 和 route family。

### 兼容性

- 现有 `RouteHandlerFunctionFactory` bean 继续可用。
- 可能被外部覆盖的现有 bean name，在迁移期应保留或提供 alias。

## Reactor-first Response Strategy

`Responses.kt` 不应继续作为隐藏的策略中心。响应行为应移动到专门的 `WebFluxResponseStrategy`，或等价的响应协议层。现有 extension function 可以在迁移期作为 facade 存在，但它们必须委托给 strategy，不能继续保留隐藏分支逻辑。

### 目标状态

- `Mono<T>` 映射成单对象 JSON 响应。
- `Flux<T>` 默认映射成 streaming JSON array 响应。
- `Flux<CommandResult>` 映射成 command response 语义：
  - 非 SSE 保持兼容的单个 command result shape；
  - SSE 保持现有 id/event/data shape 输出 command stage event。
- `Flux<ServerSentEvent<String>>` 映射成 SSE 响应语义。
- Error response 和 SSE error event 都通过同一个 error strategy 生成。

### Streaming JSON Array

成功的 `Flux<T>` JSON 响应必须保持外部 JSON array shape：

```json
[{"item":1},{"item":2}]
```

内部应以 encoded chunk 的流式方式写出响应，而不是先把所有 item 收集成 `List`。实现可以使用 WebFlux encoder，也可以显式写 `DataBuffer`，但必须保留背压语义并避免阻塞。

### `collectList()` 规则

`wow-webflux` 中的 `collectList()` 默认视为架构异味。只有以下情况允许保留：

- 结果天然是单值、小结果或明确有界；
- 协议显式要求聚合；
- 临时迁移 facade 仍在委托旧行为，并且有明确的清理步骤。

任何保留的 `collectList()` 都应容易被搜索到，并能说明原因。

### 基准

`WebFluxResponseBenchmark` 继续作为响应层基线。需要新增或保留这些行：

- 旧 list materialization response；
- streaming JSON array response；
- command result 非 SSE response；
- command result SSE response；
- SSE event mapping。

分配敏感优化以 `gc.alloc.rate.norm` 作为主信号。

## Request Context

当前 handler 直接从 `ServerRequest` 读取 tenant、owner、aggregate id、header、path variable、principal、wait option 和 SSE mode。这些逻辑应集中到 `WowWebRequestContext`。

### 目标状态

`WowWebRequestContext` 暴露 HTTP 派生事实和 route metadata：

- route spec 和 aggregate metadata；
- tenant id、owner id、space id、aggregate id；
- request id 和 aggregate version；
- principal/operator；
- wait plan 和 wait timeout；
- SSE mode；
- 必要时暴露原始 path variables 和 headers；
- 日志和诊断所需的 request identity 数据。

Context 不应包含操作决策。它只表示从 HTTP request 提取出的事实；决策属于 policy。

## Operation Policies

Handler 中硬编码的行为应移动到显式 adapter policy。

### BatchExecutionPolicy

适用于 snapshot regeneration 和 state-event resend。

- 定义 `flatMapSequential` 或其替代实现的 concurrency 与 prefetch。
- 定义单项失败处理和 batch error aggregation。
- 默认值保持当前串行化行为，除非显式配置。

### TracingPolicy

适用于 aggregate tracing。

- 定义可选的 `headVersion`、`tailVersion` 和 `limit/window` 语义。
- 默认外部行为返回完整 tracing history。
- Windowed tracing 必须保持状态正确：先重放所需前缀，再发出请求窗口内的输出。
- 响应输出应尽量使用 streaming JSON array。

### QueryResponsePolicy

适用于 list query route。

- `Flux` 结果默认使用 streaming JSON array。
- 只在必要场景允许明确有界的 materialization。
- 保持现有 request 和 response schema。

### CommandWaitPolicy

适用于 command route 和 wait route。

- 定义默认 timeout 和 request override 规则。
- 保持现有 wait/SSE 外部行为兼容。
- 明确 command wait response 的选择，避免逻辑分散在 handler 和 response extension 中。

### RemoteWaitNotifyPolicy

适用于 `WebClientCommandWaitNotifier`。

- 定义 retry 行为、scheduler 选择和 endpoint validation。
- 避免在 notifier 内硬编码 `Schedulers.boundedElastic()`，除非 policy 显式选择它。
- 保持 local wait notification fast-path。

## Error Boundary

最终架构应有一个 error strategy，被 functional route、全局 WebFlux exception handling、SSE error event 和 streaming JSON array failure 共享。

### 目标状态

- `Throwable -> ErrorInfo -> HTTP status/header/body` 只在一个地方定义。
- `RequestExceptionHandler` 可以作为兼容 facade 保留，但必须委托给共享 error strategy。
- `GlobalExceptionHandler` 使用同一套映射和日志格式。
- 请求日志包含 method、URI、已知 route spec、已知 aggregate identity 和可用 request id。

### Streaming Error 语义

- 如果错误发生在 body 开始写出前，返回兼容的 `ErrorInfo` JSON body 和 `ERROR_CODE` header。
- 如果错误发生在 streaming JSON array 已经开始写出之后，终止 stream 并记录日志，不尝试追加不兼容的 JSON error body。
- SSE 响应保留现有 error event 方式。

## 迁移策略

迁移过程可以使用中间 facade，但目标架构必须保持清洁。

1. 增加 response strategy 和 error strategy，并提供 facade 兼容。
2. 将 `Responses.kt` 逻辑迁移到 strategy，让 `Flux<T>` 默认使用 streaming JSON array。
3. 增加 `WowWebRequestContext`，迁移 command、query 和 state 中重复读取 request 字段的路径。
4. 按以下顺序增加 operation policy：
   - batch execution；
   - tracing range/window；
   - command wait；
   - remote wait notification。
5. 拆分 route-family module，瘦身 `WebFluxAutoConfiguration`。
6. 清理旧 facade 中的过渡逻辑。完成时不能存在两套相互独立的 response system。

## 兼容性契约

外部兼容性必须保持：

- route path 不变；
- HTTP method 不变；
- request body 和 path/header variable 语义不变；
- 成功响应 JSON object/array shape 不变；
- command SSE id/event/data shape 不变；
- error header name 和正常写出前 error body shape 不变。

内部兼容性不需要保持：

- handler class name 和 package structure 可以变化；
- factory construction 可以移动到 route module 后面；
- response 内部可以从 materialized list 改成 streamed array；
- request parsing 可以移动到 context object；
- hard-coded policy 可以移动到 configuration-backed policy object。

## 验证计划

- 为 response strategy、error strategy、request context 和 operation policy 增加单元测试。
- 现有 route 测试必须继续证明外部 HTTP API 兼容。
- 新增 streaming JSON array 成功路径和写出前错误路径测试。
- Aggregate tracing 测试覆盖默认 full history 和新的 range/window 语义。
- Batch policy 测试覆盖默认串行行为和可配置并发。
- 使用 `benchmarkQuickWebFlux` 做基准验证，分配以 `gc.alloc.rate.norm` 为主信号。

## 完成标准

- `WebFluxAutoConfiguration` 不再以一个巨大的平铺列表手工声明所有 route factory。
- `Responses.kt` 被移除，或退化为薄委托 facade。
- 默认 `Flux<T>` JSON 响应不使用 `collectList()`。
- `RequestExceptionHandler` 和 `GlobalExceptionHandler` 共享同一个 error strategy。
- Batch、tracing、command wait 和 remote wait notify 决策都由显式 policy 表达。
- 公开 HTTP 兼容性测试通过。
- WebFlux benchmark 覆盖 response strategy、aggregate tracing 和 command adapter 路径。
- 过渡 facade 不隐藏业务逻辑或协议决策。
