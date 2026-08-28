---
title: WebFlux
description: 将 Wow 运行时路由合同物化为 Spring WebFlux 函数式端点。
---

# WebFlux

`wow-webflux` 根据 `wow-openapi` 的运行时 route contracts 创建命令、状态、快照、事件和查询 Handler。需要 Wow 声明式 HTTP API 时使用；已有自定义 controller 且不需要自动路由时无需引入。

模块负责响应式 handler、请求提取、等待策略和错误映射；Spring WebFlux/Netty 负责监听端口、连接、codec、资源和网络超时，应用负责认证授权与网关策略。

## 安装

```kotlin
implementation("me.ahoo.wow:wow-spring-boot-starter") {
    capabilities { requireCapability("me.ahoo.wow:webflux-support") }
}
```

直接依赖 `wow-webflux` 只获得 API/实现类，不会获得 Starter 的 `WebFluxAutoConfiguration` 和属性绑定。

## 自动路由注册

自动配置合并 ordered `WebFluxRouteModule` 与额外 `HttpRouteHandlerFunctionFactory`，再用 `RouterSpecs` 构建一个 `RouterFunction`。只有已加载元数据和实际注册的 contributor 会产生路由；模块存在不等于某个业务端点存在。

### 路由模式

路径由 aggregate/command route metadata 决定，并按 tenant、owner、space 需要增加作用域。只有应用另行安装与当前 Spring WebFlux 匹配的 Springdoc WebFlux starter 时，才可用候选 runtime `/v3/api-docs` 验证；仅有 `webflux-support` 时应检查 `RouterSpecs.toRouteCatalog()` 或等价 route catalog 诊断，不手写猜测生产路径。

#### 聚合路由模式

`AggregateRoute.Owner.AGGREGATE_ID` 等 metadata 决定 owner 与 aggregate ID 是否合并；命令 handler 仍从 path/header/body 创建 `CommandMessage`。

#### 拥有者路由模式

owner/tenant/space 路由只表达资源作用域，不自动认证请求者。安全层必须把主体与这些值绑定并拒绝越权。

### HTTP 方法映射

`@CreateAggregate` 和 `@CommandRoute` 生成的 method/path 来自编译元数据；WebFlux 只执行合同。修改注解或生成元数据后必须重新构建并检查 OpenAPI。

## 配置

最小配置只有 capability；默认 `wow.webflux.enabled=true`。常用默认值：

```yaml
wow:
  webflux:
    global-error:
      enabled: true
    batch:
      concurrency: 1
      prefetch: 1
    query:
      max-list-size: 1000
      max-page-size: 100
      max-page-window: 10000
      max-condition-nodes: 64
      max-condition-values: 1000
      allow-expensive-operators: true
      idle-timeout: 10s
```

数值上限为 `0` 时关闭对应 HTTP guard；`idle-timeout=0s` 关闭 idle timeout。不要复制后端已负责的字段类型、mapping 或唯一性校验。`HttpQueryGuardFilter` 只保护带 WebFlux request context 的 HTTP 查询，程序内查询保持公共 service 行为。

## 聚合查询路由

Snapshot 与 EventStream 都接收 `AggregationQuery`：

- `.../snapshot/aggregation` 聚合快照模型；
- `.../event/aggregation` 聚合事件流模型，并与 event/list、event/paged、event/count 使用相同的基础、tenant、owner 路由规则。

普通 JSON 会先收集动态行数组，`Accept: text/event-stream` 则逐行流式返回。query guards 同样限制 condition、values、limit、Elements、metric sort 和高成本表达式。

EventStream 的根 filter 作用于事件流文档；使用 `elements = [{"path":"body"}]` 展开事件数组后，group 和 metric 字段相对事件项。Elasticsearch 当前不索引 `body.body` payload，因此跨后端聚合范围是事件流 envelope 与 `body` 事件元数据；需要 payload 聚合时必须先单独设计 mapping 与历史数据重建。

## 等待计划集成

命令请求头选择等待阶段与 timeout；handler 把命令交给 `CommandGateway`/`WaitCoordinator`。HTTP 连接断开、timeout 或 runtime shutdown 都可能在业务仍有后续处理时终止等待。

### 支持的等待计划

支持 `SENT`、`PROCESSED`、`SNAPSHOT`、`PROJECTED`、`EVENT_HANDLED`、`SAGA_HANDLED`。阶段表示对应 notifier 条件完成，不是任意跨服务事务提交。详见[命令网关](../command-gateway.md#等待计划)。

## 错误处理

默认 `RequestExceptionHandler` 把框架错误转换为 `ErrorInfo` 响应，并写 `Wow-Error-Code`；全局 `WebExceptionHandler` 默认启用。已验证映射包括参数/状态错误 400、not found 404、wait timeout 408、未知错误 500。自定义 error strategy 时必须保留原异常失败路径，避免空响应吞掉错误。

## OpenAPI 集成

OpenAPI 由运行时 metadata 和 route contracts 组装。schema refresh 只刷新接收请求实例的 query schema，失败保留旧 cache；它不广播、不修改后端 mapping。将刷新 route 与普通查询分开授权。

## 性能优化

先观察 event-loop 阻塞、序列化、handler latency、等待连接数和 query guard rejection，再调整 batch 或 server 设置。

### 响应式处理

内置路径返回 `Mono`/`Flux`，不在核心 handler 中阻塞。自定义 controller 也必须保持这一边界；阻塞 cache source 或驱动调用应隔离到合适 scheduler，并用负载测试验证。

## 监控和调试

记录 route ID、request ID、error code、HTTP status、latency 和 cancellation。需要框架 span 时加入 `opentelemetry-support`；WebFlux capability 不配置 exporter。

### 请求日志

可临时开启 `me.ahoo.wow.webflux=DEBUG`，但不要记录命令机密、认证 header 或完整敏感 payload。诊断后恢复生产日志级别。

## 最佳实践

- 已安装匹配 Springdoc WebFlux starter 时用候选 runtime OpenAPI 验证路由，否则检查 `RouterSpecs` route catalog；
- 在 WebFlux 之前完成认证，在 query/command route 上执行授权；
- 保持响应式链路非阻塞，显式测试取消和 timeout；
- 只按风险调整 HTTP guard，不用 `0` 作为默认逃生开关。

聚焦检查：

```bash
./gradlew :wow-webflux:check
```

该命令不证明目标网关、安全策略或真实路由已部署。下一步阅读 [OpenAPI](../open-api.md)和[数据权限](../data-access.md)。
