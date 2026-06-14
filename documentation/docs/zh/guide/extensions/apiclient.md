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

添加 `wow-apiclient` 依赖：

```kotlin [Gradle(Kotlin)]
implementation("me.ahoo.wow:wow-apiclient")
```

## 命令网关

### 响应式命令网关

```kotlin
@CoApi
interface OrderCommandGateway : ReactiveRestCommandGateway<Mono<CommandResult>, CommandResult>
```

发送命令：

```kotlin
val request = CommandRequest(
    body = CreateOrder(orderId = "order-001", items = listOf(...)),
    waitPlan = CommandRequest.WaitPlan(
        waitStage = CommandStage.PROJECTED,
        waitContext = "order",
        waitProcessor = "OrderProjector",
    )
)
val result = orderCommandGateway.send(request).block()
```

### 同步命令网关

```kotlin
@CoApi
interface OrderCommandGateway : SyncRestCommandGateway<CommandResult, CommandResult>
```

## 快照查询

### 响应式查询 API

```kotlin
@CoApi
interface OrderQueryApi : ReactiveSnapshotQueryApi<OrderState>
```

提供单条、列表、分页和计数查询方法：

```kotlin
val order = queryApi.getById("order-001").block()
val paged = queryApi.paged(PagedQuery(pageIndex = 0, pageSize = 10)).block()
val count = queryApi.count().block()
```

### 同步查询 API

```kotlin
@CoApi
interface OrderQueryApi : SynchronousSnapshotQueryApi<OrderState>
```

## 错误处理

`RestCommandGatewayException` 封装命令错误并携带完整的请求上下文：

```kotlin
try {
    orderCommandGateway.send(request).block()
} catch (ex: RestCommandGatewayException) {
    println("Command failed: ${ex.message}")
}
```
