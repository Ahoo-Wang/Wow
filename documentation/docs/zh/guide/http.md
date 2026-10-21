---
title: 提供和调用 HTTP API
description: 服务从 Manifest 提供的路由、调用方是谁、发送命令并选择等待多久、重试、流式结果，以及错误。
outline: deep
---

# 提供和调用 HTTP API

服务提供一个从它的 Manifest 推出的 HTTP API：每条命令一个路由、每个聚合的状态、快照查询和事件查询、服务的数据集，以及 Manifest 和它的 OpenAPI 文档。不需要写控制器。本页说明客户端怎样使用它：认证、发送命令、等待结果、安全地重试，以及读取错误。每个路由、请求头和字段，见 [HTTP API](../reference/http)。

## 路由 {#the-routes}

| 路由 | 提供 |
| --- | --- |
| `POST /<context>/<aggregate>/{id}/<command>` | 发往一个聚合的命令 |
| `POST /<context>/<aggregate>/<command>` | 由服务端分配聚合 ID 的创建命令 |
| `DELETE /<context>/<aggregate>/{id}`、`POST …/{id}/wow.lifecycle.<command>` | 生命周期命令 |
| `GET /<context>/<aggregate>/{id}/state`、`…/state/trace` | 聚合的状态和历史（[读取状态](./reading-state)） |
| `POST /<context>/<aggregate>/snapshot/query`、`…/events/query` | 查询模型（[查询快照](./queries)、[查询事件流](./event-queries)） |
| `POST /wow/dataset/<context>/<dataset>/query` | 服务的一个数据集 |
| `GET /wow/manifest`、`GET /wow/openapi` | Manifest，以及这些路由的 OpenAPI 3.1 文档 |

这些路由在公开端口上。内部端口为集群和其他服务提供服务，从不为客户端提供。

## 调用方是谁 {#who-the-caller-is}

每个路由都通过应用的 `CallerResolver` Bean 认证它的请求，把请求的凭据变成一个调用方：它的租户、所有者和空间、主体标识和属性。eShop 的 ordering 服务读取两个请求头，代替身份提供方：

<!-- sample: example/eshop/ordering/ordering-server/src/main/kotlin/me/ahoo/wow/example/eshop/ordering/server/OrderingCallers.kt#resolver -->

```kotlin
@Bean
fun orderingCallerResolver(): CallerResolver = CallerResolver { request ->
    val headers = request.headers()
    val user = headers.firstHeader(USER)?.trim()?.takeIf { it.isNotEmpty() } ?: return@CallerResolver Caller.ANONYMOUS
    val employee = headers.firstHeader(ROLE)?.trim() == EMPLOYEE
    Caller.of(
        tenantId = Caller.DEFAULT_TENANT,
        scope = if (employee) Scope.NONE else Scope(ownerId = user),
        subject = user,
        attributes = if (employee) mapOf(ROLES to listOf(EMPLOYEE)) else emptyMap(),
    )
}
```

- 买家是**所有者级**调用方：它的作用域有所有者，所以只能接触它自己的聚合。
- 员工是**租户级**调用方：没有所有者，所以能接触它租户的每个聚合，由动作级授权决定它能做什么。
- 返回 `null` 的解析器对应 `401 wow.auth.unauthenticated`。没有解析器时，每个请求都是默认租户的匿名调用方。

有 `wow-cosec` 时，CoSec 的解析器从已认证的主体及其角色完成这件事（[保护部署](../operate/security#authenticate-callers)）。租户、所有者和空间总是来自调用方，从不来自路径或请求体；被允许跨作用域行事的调用方，在 `Wow-Scope` 中指明另一个作用域。

## 发送命令 {#send-a-command}

```bash
curl -X POST localhost:8083/eshop-ordering/order/order-1/pay-order \
  -H 'Content-Type: application/json' -H 'X-User: alice' \
  -H 'Wow-Request-Id: 01JB2T6Q0Z' \
  -d '{"paymentId":"p-1","amount":29.00}'
```

请求体是命令的 JSON。成功时以 `200` 返回命令的结果：它的 `status`（`SUCCEEDED`）、提交之后聚合的 `ref` 和 `version`，以及决定附加的 `results`。`If-Match: "n"` 只在聚合处于版本 `n` 时发送命令，否则返回 `412`。

## 选择等待多久 {#choose-how-long-to-wait}

默认情况下，命令在它的快照包含这次提交后才回答，所以紧接着的读取能看到它。`Wow-Wait` 以一条由跳组成的链要求另一个时点：

| `Wow-Wait` | 何时回答 |
| --- | --- |
| `accepted` | 命令通过了准入（`202`） |
| `committed` | 它的事件已追加 |
| `snapshot` | 它的快照包含这次提交（默认） |
| `committed > handled(OrderProcessSaga.onOrderAwaitingValidation) > committed(eshop-inventory)` | Saga 派生出它的命令，并且库存提交了这些命令 |

等待会跨服务跟随 Saga：在 Saga 的 `handled` 那一跳之后，后面的跳适用于它派生的命令。`Wow-Wait-Timeout` 限制等待的时长（默认 `10s`，最长 `wow.wait.max-timeout`）。模型见[等待结果](../concepts/waits)。

## 安全地重试 {#retry-safely}

给每条命令一个 `Wow-Request-Id`。用同一个 ID 重试，会被当作已经完成来回答，带上 `duplicate: true` 和原来的版本，绝不会决定两次。所以：

- `504 wow.wait.timeout`：命令**可能已经提交**。用同一个请求 ID 重试。
- `503 wow.command.unknown`、`wow.storage.unavailable`、`wow.node.closing`：用同一个请求 ID 重试。
- `429 wow.command.busy`：在 `Retry-After` 之后重试。
- 拒绝（带领域错误码的 `4xx`）：不要重试；同一个请求会再次被拒绝。

不带 ID 发送的创建命令会得到一个 ID；用同一个请求 ID 重试，得到的是同一个 ID。

## 流式获取结果 {#stream-the-outcome}

带 `Accept: text/event-stream` 时，命令每到达一跳就以一个服务端推送事件回答，事件以这一跳的阶段命名，数据是这一跳的结果，直到等待结束。客户端不用轮询就能显示进度：已接收、已提交，然后是链经过的每个服务。

快照 `list` 以同样的方式流式返回它的行，可以用 `Last-Event-ID` 续读（[查询快照](./queries#over-http)）。

## 读取错误 {#read-an-error}

每个错误都是一个 RFC 9457 problem，即 `application/problem+json`：它的 `type` 是 `urn:wow:error:<code>`，`code` 和 `status` 重复给出错误码和状态，`violations` 逐个字段列出请求违反了什么，命令的 problem 还在 `result` 下带着它完整的结果。客户端依据 `code` 分支。领域错误码的状态是它的 `@ErrorCode(status)`（[拒绝与错误码](./rejections)）；框架的错误码见[错误码](../reference/error-codes)。

## 生成的客户端 {#generated-clients}

客户端很少手写这些请求：TypeScript 客户端从 Manifest 生成，Kotlin 服务之间通过类型化的命令客户端互相调用（[调用其他服务](./calling-services)）。
