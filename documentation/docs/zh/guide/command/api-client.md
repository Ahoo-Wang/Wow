---
title: API Client
description: 使用 wow-apiclient 的 CoApi 命令网关调用全局命令门面，并明确当前协议能力边界。
outline: deep
---

# API Client

`wow-apiclient` 提供手工维护的 CoApi 命令接口，用于 Kotlin 服务调用远程 Wow 应用。当前实现只调用全局 `POST /wow/command/send`，并返回一个最终 `CommandResult`。

## 能力边界

`ReactiveRestCommandGateway` 与 `SyncRestCommandGateway` 都是 HTTP 传输适配器，不是本地 `CommandGateway` 的等价替身：

- 只调用 `/wow/command/send`，不选择生成的聚合专用命令路由；
- 只解包最终结果，不暴露阶段流；
- 把 `CommandRequest` 字段映射到全局门面的请求头和请求体；
- 在交换前只执行命令体自身的 `CommandValidator`，服务端仍负责完整 Gateway 检查与聚合处理。

调用方必须按远程 HTTP、服务发现、授权、超时和幂等边界设计失败处理。

## 安装与 CoApi 注册

```kotlin [Gradle(Kotlin)]
implementation("me.ahoo.wow:wow-apiclient")
implementation("me.ahoo.coapi:coapi-spring-boot-starter")
```

只注册实际使用的接口：

```kotlin
@EnableCoApi(clients = [ReactiveRestCommandGateway::class])
@SpringBootApplication
class ClientApplication
```

同步应用改为注册 `SyncRestCommandGateway::class`。两个接口都已声明 `@CoApi` 和 `@LoadBalanced`；无需为一次调用再建工厂或包装层。

## CommandRequest

`CommandRequest` 以 `body` 为必填字段，并把其余值映射为目的地或 HTTP 请求头：

```kotlin
val request = CommandRequest(
    body = createOrder,
    aggregateId = "order-1",
    requestId = "create-order-1",
    serviceUri = "http://order-service:8080",
    waitPlan = CommandRequest.WaitPlan(
        waitStage = CommandStage.PROCESSED,
        waitTimeout = 5_000,
    ),
)
```

`type` 默认使用 `body::class.java.name`。`WaitPlan` 默认等待 `PROCESSED`，并只包含 `waitStage`、`waitContext`、`waitProcessor` 和毫秒值 `waitTimeout`。`aggregateId`、`aggregateVersion`、`tenantId`、`ownerId`、`spaceId`、`requestId`、`localFirst`、`context` 与 `aggregate` 都会作为全局门面的路由或消息头发送。

## 目标服务解析

`CommandRequest.sendUri` 的当前计算规则是：

```text
(serviceUri ?: "http://" + serviceId) + "/wow/command/send"
```

`serviceId` 优先使用显式 `context`，否则由 `MetadataSearcher` 根据 `commandType` 查找 context。设置 `serviceUri` 可固定远程地址；不设置时，`@LoadBalanced` 客户端必须能解析 context 对应的服务 host。

`send(CommandRequest)` 传入绝对 URI，因此 `@CoApi(baseUrl)` 不决定命令目的地。不要用虚假的 `context` 绕过缺失 metadata；它同时参与命令目标和默认服务发现。

## 响应式调用

```kotlin
@Service
class OrderService(
    private val commands: ReactiveRestCommandGateway,
) {
    fun create(request: CommandRequest): Mono<CommandResult> =
        commands.send(request)
}
```

响应式网关返回 `Mono<CommandResult>`。它等待服务端返回所选阶段的最终 JSON 结果，不提供 `Flux` 或 SSE 进度流。

## 同步调用

```kotlin
@EnableCoApi(clients = [SyncRestCommandGateway::class])
class ClientConfiguration

val result: CommandResult = syncGateway.send(request)
```

同步网关阻塞调用线程并返回 `CommandResult`。只在明确采用同步 I/O 的应用路径中使用；不要从 Reactor event loop 或 Wow 核心响应式处理链调用。

## 错误映射

响应式网关把 `WebClientResponseException` 映射为 `RestCommandGatewayException`；同步网关在 `send(CommandRequest)` 中做相同映射。若响应体能解码为 `CommandResult` 或 `DefaultErrorInfo`，异常会保留请求、错误码、消息和绑定错误：

```kotlin
commands.send(request)
    .doOnError(RestCommandGatewayException::class.java) { error ->
        log.warn("Command failed: {}", error.errorCode)
    }
```

空白或未知错误体仍会包装为 `RestCommandGatewayException`，原始 HTTP 异常作为 cause。不要按错误消息文本分支，也不要在没有稳定 `requestId` 的情况下盲目重试命令。

## 当前不支持的协议能力

当前 API Client 没有暴露全局 OpenAPI 已具备的全部命令请求头，也不提供聚合路由的流式能力：

- **SSE：** 两个网关都只请求并解包最终 JSON `CommandResult`；没有 `sendAndWaitStream` 对应方法。
- **函数名匹配：** `CommandRequest.WaitPlan` 有 `waitContext` 和 `waitProcessor`，但没有 `Command-Wait-Function` 对应字段，不能按具体函数名收窄等待。
- **Saga 链尾字段：** 没有 `Command-Wait-Tail-Stage`、`Context`、`Processor` 或 `Function` 字段，不能表达 Saga 等待链的链尾目标。

需要这些能力时，直接采用目标服务生成且声明相应合同的 HTTP 路由，或在应用内使用本地 `CommandGateway`；不要宣称现有 REST 客户端与本地 Gateway 等价。
