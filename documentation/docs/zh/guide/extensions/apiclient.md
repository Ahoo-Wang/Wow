---
title: API 客户端
description: 通过 CoApi 调用 Wow 命令与快照 HTTP 合同，并保持路由、等待阶段、查询 Schema 与服务端护栏边界。
---

# API 客户端

`wow-apiclient` 为 Wow 通用命令入口与快照查询路由提供手工维护的 CoApi 接口。它是传输适配器：

```text
CommandRequest / Query DTO
  -> CoApi HTTP exchange
  -> 生成的 WebFlux 路由
  -> 命令管线或受护栏约束的查询管线
```

它不会生成服务端路由、发现聚合专用字段、创建授权请求头，也不会把投影变成查询服务。运行服务的 OpenAPI 文档仍是路径和线协议的事实来源。

## 特性

- 使用 `Mono`、`Flux` 的响应式命令与查询 API。
- 为明确采用同步 I/O 的调用方提供阻塞式同步 API。
- 通过 `/wow/command/send` 发送通用命令并携带等待计划请求头。
- typed、仅状态和 dynamic 三种快照查询响应形状。
- single、list、paged、精确 count，以及显式选择的 aggregation 调用。
- 支持 CoApi load-balanced 命令网关。

`wow-apiclient` 不是 OpenAPI 代码生成器。Fetcher 或其他下游工具可以从 `/v3/api-docs` 生成另一套客户端；生成 diff 必须单独审阅。

## 安装

添加 Wow client 与 CoApi Spring Boot Starter：

```kotlin [Gradle(Kotlin)]
implementation("me.ahoo.wow:wow-apiclient")
implementation("me.ahoo.coapi:coapi-spring-boot-starter")
```

注册需要由 CoApi 物化的准确接口：

```kotlin
@EnableCoApi(
    clients = [
        ReactiveRestCommandGateway::class,
        CartQueryClient::class,
    ],
)
@SpringBootApplication
class ClientApplication
```

当 `CommandRequest.context` 与 `serviceUri` 都缺失时，客户端应用还必须携带解析命令 context 所需的 Wow 元数据。

## 快速开始

### 1. 声明查询客户端

把查询接口绑定到聚合路由基址。以下示例指向无作用域的 `/cart/...` 路由：

```kotlin
@CoApi(baseUrl = "http://order-service:8080")
@HttpExchange("cart")
interface CartQueryClient : ReactiveSnapshotQueryApi<CartData>
```

`ReactiveSnapshotQueryApi<S>` 组合 single、list、paged、count 接口。其继承的 `@PostExchange` 路径都相对 `@HttpExchange`：`snapshot/single`、`snapshot/list`、`snapshot/paged`、`snapshot/count`，以及仅状态变体。

当 CoApi 或应用约定需要具体泛型元数据时，应像仓库示例客户端一样，以具体返回类型和 `@RequestBody` 重新声明方法。不要在每个方法重复路由路径。

`@HttpExchange("cart")` 调用基础无作用域快照查询变体。调用 tenant/owner 作用域变体时，应通过应用自有接口或路由层绑定生成路径并提供所需值。基础路由必须被显式保护；选择作用域客户端路径并不等于授权。不要猜测带 context 前缀的 URL，应检查服务端 OpenAPI。

### 2. 声明命令客户端

内置网关已经带 `@CoApi` 与 `@LoadBalanced`，可以直接注册。应用专用命名接口是可选的：

```kotlin
@CoApi
interface OrderCommandClient : ReactiveRestCommandGateway
```

两种方式都调用通用命令入口，不会调用 OpenAPI 中的聚合专用命令路由。

### 3. 注入并使用

```kotlin
@Service
class CartApplicationService(
    private val carts: CartQueryClient,
    private val commands: ReactiveRestCommandGateway,
) {
    fun getCart(id: String): Mono<CartData> = carts.getStateById(id)

    fun createOrder(id: String, command: CreateOrder): Mono<CommandResult> =
        commands.send(
            CommandRequest(
                body = command,
                aggregateId = id,
                serviceUri = "http://order-service:8080",
                waitPlan = CommandRequest.WaitPlan(
                    waitStage = CommandStage.PROCESSED,
                ),
            ),
        )
}
```

`getStateById` 会把 HTTP 404 转换为空 `Mono`，其他查询错误继续传播。发送命令前会在本地校验实现 `CommandValidator` 的命令体。

### 服务发现

`CommandRequest.sendUri` 按以下方式计算：

```text
(serviceUri ?: "http://" + serviceId) + "/wow/command/send"
```

`serviceId` 是显式 `context`，否则通过 `MetadataSearcher` 根据命令类型解析。因为 `send(CommandRequest)` 会传入绝对 URI 参数，命令网关的 `@CoApi(baseUrl)` 不会选择命令目的地。

固定地址应设置 `serviceUri`。否则 `@LoadBalanced` 网关使用 context 推导的 service host，并要求应用的负载均衡集成可以解析它。

查询客户端不同：目标由 `@CoApi(baseUrl)` 与 `@HttpExchange` 基址决定。服务发现和路由作用域属于应用配置，不会从 Query DTO 推导。

## 命令网关

`CommandRequest` 携带命令体以及路由/消息请求头：

- `aggregateId`、`aggregateVersion`、`tenantId`、`ownerId`、`spaceId`；
- `requestId`、`localFirst`、`context`、`aggregate` 与可选线协议 `type`；
- 传输目的地 `serviceUri`；
- 服务端命令等待合同 `WaitPlan`。

`type` 默认为 `body::class.java.name`。`context` 同时影响命令元数据与默认服务发现。不要仅为了绕过缺失 KSP 元数据而设置 context 或 aggregate；这些值必须描述真实命令合同。

默认等待阶段是 `PROCESSED`。其他阶段为 `SENT`、`SNAPSHOT`、`PROJECTED`、`EVENT_HANDLED`、`SAGA_HANDLED`。`waitContext` 与 `waitProcessor` 用于收窄基于函数的阶段；`waitTimeout` 以毫秒发送。

等待结果表示所选 Wow 处理信号。`PROJECTED` 仅对注册在 Wow 中且在 Handler 返回链内完成的投影工作有意义；它不会等待脱离链路的 `subscribe()` 或无关外部管线。

### 响应式命令网关

```kotlin
val result: Mono<CommandResult> = commandGateway.send(
    CommandRequest(
        body = createOrder,
        aggregateId = "order-1",
        waitPlan = CommandRequest.WaitPlan(
            waitStage = CommandStage.PROJECTED,
            waitContext = "order-service",
            waitProcessor = "OrderSummaryProjector",
            waitTimeout = 5_000,
        ),
    ),
)
```

响应式网关返回 `Mono<CommandResult>`，并把命令 HTTP 错误转换为 `RestCommandGatewayException`。

### 同步命令网关

```kotlin
@EnableCoApi(clients = [SyncRestCommandGateway::class])
class ClientConfiguration

val result: CommandResult = syncGateway.send(request)
```

同步网关阻塞调用线程并返回 `CommandResult`。它只能用于阻塞式应用路径，不应从 Reactor event loop 或 Wow 核心响应式处理代码调用。

## 快照查询

快照数据查询、state-only/dynamic 结果、404 语义及独立聚合 API 见[查询 API 客户端](../query/query-api-client.md)。

## 错误处理

命令调用中，如果响应可以解码为 `CommandResult` 或 `DefaultErrorInfo`，`RestCommandGatewayException` 会保留 `CommandRequest`、错误码、消息与绑定错误：

```kotlin
commandGateway.send(request)
    .doOnError(RestCommandGatewayException::class.java) { error ->
        log.warn("Command failed: {}", error.errorCode)
    }
```

空白或未知错误体仍会转换为 `RestCommandGatewayException`，并以 HTTP 异常为 cause。查询客户端只会把 single 查询的 404 规范化为空/null；校验、授权、限流、超时与后端错误仍作为传输错误交给应用处理。

不要在 HTTP 层盲目重试命令。应复用稳定 request/command identity，并遵循命令幂等合同。查询也只应重试被应用策略认定为瞬态的错误；查询 Schema 校验或 HTTP 护栏拒绝不会因重复请求而变为合法。
