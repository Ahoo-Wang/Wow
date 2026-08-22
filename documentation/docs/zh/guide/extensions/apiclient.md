---
title: API 客户端
description: 基于 CoApi 的 RESTful API 客户端，提供响应式和同步的命令发送与快照查询接口。
---

# API 客户端

API 客户端模块基于 [CoApi](https://github.com/Ahoo-Wang/CoApi) 提供声明式 RESTful 客户端，支持响应式和同步两种接口模式。

## 特性

- **响应式与同步 API** — 可选择基于 `Mono` 的响应式接口或阻塞式同步接口
- **服务发现** — 通过 `@CoApi` 和 `@LoadBalanced` 注解内置服务发现支持
- **命令网关** — 通过 REST 端点发送命令，支持等待计划
- **快照查询** — 单条、列表、分页和计数查询接口

## 安装

添加 `wow-apiclient` 依赖和 CoApi Spring Boot Starter（自动注册所需）：

```kotlin [Gradle(Kotlin)]
implementation("me.ahoo.wow:wow-apiclient")
implementation("me.ahoo.coapi:coapi-spring-boot-starter")
```

还需在应用类上启用 CoApi 客户端扫描：

```kotlin
@EnableCoApi(clients = [OrderCommandClient::class, CartQueryClient::class])
@SpringBootApplication
class ExampleServer
```

## 快速开始

### 1. 声明查询客户端

创建一个继承 `ReactiveSnapshotQueryApi<S>`（或 `SynchronousSnapshotQueryApi<S>` 用于阻塞调用）的
`@CoApi` 接口。`@HttpExchange` 注解将客户端绑定到特定聚合的快照端点：

```kotlin
import me.ahoo.coapi.api.CoApi
import me.ahoo.wow.apiclient.query.ReactiveSnapshotQueryApi
import me.ahoo.wow.example.api.cart.CartData
import org.springframework.web.service.annotation.HttpExchange

@CoApi(baseUrl = "http://order-service:8080")
@HttpExchange("cart") // 聚合名 = 快照端点的基础路径
interface CartQueryClient : ReactiveSnapshotQueryApi<CartData>
```

你可以覆盖单个方法以自定义 `@RequestBody` 注解，或直接继承所有默认实现
（single、list、paged、count 及其 state/dynamic 变体）。

### 2. 声明命令客户端

命令客户端直接继承 `ReactiveRestCommandGateway` 或 `SyncRestCommandGateway`：

```kotlin
@CoApi(baseUrl = "http://order-service:8080")
interface OrderCommandClient : ReactiveRestCommandGateway
```

### 3. 注入并使用

CoApi 自动将客户端配置为 Spring Bean —— 直接注入即可：

```kotlin
@Service
class CartService(
    private val queryClient: CartQueryClient,
    private val commandClient: OrderCommandClient,
) {
    fun getCart(cartId: String): Mono<CartData> {
        return queryClient.getStateById(cartId) // Mono<CartData>
    }

    fun placeOrder(orderId: String, items: List<CreateOrder.Item>, address: ShippingAddress): Mono<CommandResult> {
        val request = CommandRequest(
            body = CreateOrder(items = items, address = address, fromCart = false),
            aggregateId = orderId,
            waitPlan = CommandRequest.WaitPlan(waitStage = CommandStage.PROCESSED),
        )
        return commandClient.send(request) // Mono<CommandResult>
    }
}
```

### 服务发现

`ReactiveRestCommandGateway` 和 `SyncRestCommandGateway` 标注了 `@LoadBalanced`，
因此可以使用服务注册中心的 URL 而非固定主机：

```kotlin
@CoApi(baseUrl = "http://order-service") // 由 Spring Cloud LoadBalancer / Nacos 等解析
interface OrderCommandClient : ReactiveRestCommandGateway
```

::: tip CommandRequest serviceUri
对于 `send(CommandRequest)`，命令网关从 `CommandRequest.serviceUri` 或命令元数据的上下文名称构建发送 URI —— 它**不会**使用 `@CoApi(baseUrl)` 发送命令。要将命令发送到固定主机，设置
`CommandRequest(serviceUri = "http://localhost:8080", ...)`。
:::

## 命令网关

`ReactiveRestCommandGateway` 与 `SyncRestCommandGateway` 是具体的 `@CoApi` 接口
（无额外类型参数）。声明你自己的 `@CoApi` 接口并继承其中之一，即可获得
`send(CommandRequest)` 方法。

### 响应式命令网关

```kotlin
@CoApi
interface OrderCommandGateway : ReactiveRestCommandGateway
```

`send(request)` 返回 `Mono<CommandResult>`：

```kotlin
val request = CommandRequest(
    body = CreateOrder(orderId = "order-001", items = listOf(...)),
    waitPlan = CommandRequest.WaitPlan(
        waitStage = CommandStage.PROJECTED,
        waitContext = "order",
        waitProcessor = "OrderProjector",
    ),
)
val result: CommandResult = orderCommandGateway.send(request).block()
```

### 同步命令网关

```kotlin
@CoApi
interface OrderCommandGateway : SyncRestCommandGateway
```

`send(request)` 直接返回 `CommandResult`（阻塞）。`WebClientResponseException`
会被解包为 `RestCommandGatewayException`，其中携带 `CommandResult` / `ErrorInfo` 响应体。

## 快照查询

### 响应式查询 API

```kotlin
@CoApi
interface OrderQueryApi : ReactiveSnapshotQueryApi<OrderState>
```

`ReactiveSnapshotQueryApi<S>` 组合了单条、列表、分页与计数操作，全部返回 `Mono`/`Flux`：

```kotlin
// 单条查询：返回 Mono<MaterializedSnapshot<OrderState>>（未找到时为空）
val snapshot = queryApi.getById("order-001").block()
// 使用 getStateById 直接获取状态：Mono<OrderState>
val state = queryApi.getStateById("order-001").block()

// 分页查询：接收 IPagedQuery（Pagination 从 1 开始）；返回 Mono<PagedList<...>>
val paged = queryApi.paged(
    PagedQuery(
        filter = MatchAllFilter,
        pagination = Pagination(index = 1, size = 10),
    ),
).block()

// 计数：接收 FilterExpression；返回 Mono<Long>
val total = queryApi.count(MatchAllFilter).block()
```

### 同步查询 API

```kotlin
@CoApi
interface OrderQueryApi : SynchronousSnapshotQueryApi<OrderState>
```

同步版本与响应式 API 对应，但直接返回值（阻塞）。

## 错误处理

`RestCommandGatewayException` 封装命令错误并携带完整的请求上下文：

```kotlin
try {
    orderCommandGateway.send(request).block()
} catch (ex: RestCommandGatewayException) {
    println("Command failed: ${ex.message}")
}
```
