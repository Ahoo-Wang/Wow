---
title: 从其他服务调用 Wow
description: 不是 Wow 服务的应用如何调用 Wow 服务：从外部经网关，从 JVM 用 WowClient，从其他语言用 OpenAPI 客户端，或从发布的事件流异步获取；要带哪些请求头、有哪些错误码，以及为什么要消费事件流而不是写命令主题。
outline: deep
---

# 从其他服务调用 Wow

不是 Wow 服务的应用，例如为前端服务的后端、较早的服务、用别的语言写的任务或合作方的系统，有四种方式访问 Wow 服务。每种方式访问服务的一个端口，并以一种身份调用。本页说明哪种方式适合哪种调用方、每个请求带什么，以及会返回什么。Wow 服务调用另一个 Wow 服务时，改用它自己的 `CommandClient`：见[调用其他服务](./calling-services)。

## 选择入口 {#choose-the-way-in}

| 调用方 | 访问 | 身份 |
| --- | --- | --- |
| 平台之外：浏览器、移动应用、合作方 | 网关，再到服务的公开路由 | 终端用户，由网关认证；服务把网关断言的身份映射成调用方 |
| 平台内的 JVM 应用 | `WowClient`：user 模式访问公开路由，service 模式访问内部端口 | user 模式：终端用户，凭它转发的凭据。service 模式：它声明的服务，代它发送时的调用方行事 |
| 用其他语言写的应用 | 由服务的 OpenAPI 文档生成的客户端，经网关 | 终端用户，或网关认证的服务账号 |
| 对发生的事做出反应的应用 | 上下文在 Kafka 上发布的事件流 | 无身份：它以自己的消费组读取，不发送命令 |

公开端口只有网关能访问，内部端口只有平台自己的服务能访问（[保护部署](../operate/security)）。内部端口对调用方声明的服务名照单全收，因此只有平台的应用（像平台的服务一样部署和审查）才以 service 模式调用。其他所有应用都经网关，以它代为行事的终端用户的身份调用。

## 在 JVM 中 {#from-the-jvm}

`WowClient` 发送命令对象、读取状态，与 Wow 服务自己的客户端一样。它从对端的 api jar 里取对端的契约，因此应用依赖对端的 `*-api` 模块。构建时给出每个上下文的服务在哪里，以及它的模式：

<!-- sample: fragment -->

```kotlin
import me.ahoo.wow.client.EndpointResolver
import me.ahoo.wow.client.UserCredential
import me.ahoo.wow.client.WowClient
import java.net.URI

// In user mode, through the gateway, as the end user whose credential it forwards.
val asUser = WowClient.builder()
    .endpoints(EndpointResolver.of(mapOf("eshop-catalog" to URI.create("https://gateway.internal"))))
    .user(credential)
    .build()

// In service mode, at the internal port, as the service it names.
val asService = WowClient.builder()
    .endpoints(EndpointResolver.of(emptyMap(), mapOf("eshop-catalog" to URI.create("http://catalog.internal:9092"))))
    .service("storefront-app")
    .build()
```

`client.commands` 与 Wow 服务里的 `CommandClient` 是同一个，`client.queries` 与 `QueryApi` 是同一个。service 模式下，名字放在每个请求的 `Wow-Service` 请求头里，被调用的服务可以列出这个名字能调用什么（`wow.callers.<service>`）。

在 Spring Cloud 注册中心上，客户端可以把服务写作 `lb://<服务>`，经 Spring Cloud LoadBalancer 找到它的实例（[解析 `lb://`](../operate/spring-cloud#load-balancer)）。自己编写的 `EndpointResolver` 如果去查找实例（在注册中心、DNS 或 sidecar 里），还要重写 `reaches`，说明它服务哪些上下文：这样查找其中某个上下文时一个实例都没找到，调用会以 `wow.peer.unavailable` 失败，客户端会重试；而不是被当作没有 URL 的上下文，那是代码的错误，不会重试。Java 代码把客户端包进 `JavaCommandClient`（[在 Java 中](./calling-services#from-java)）。

## 在其他语言中 {#from-other-languages}

`GET /wow/openapi` 提供服务公开路由的 OpenAPI 3.1 文档，其中不含内部命令（[OpenAPI 与 springdoc](./openapi)）。用所用语言的 OpenAPI 工具由它生成客户端，并指向网关。TypeScript 应用则用 `wow-generator` 由 Manifest 生成客户端，保留 OpenAPI 表达不了的等待计划与查询字段（[生成的客户端](./http#generated-clients)）。

## 带上请求头 {#send-the-headers}

命令是把它的 JSON 体 `POST` 到它的路由 `/<context>/<aggregate>/{id}/<command>`。让重试安全、说明等待多久的请求头：

| 请求头 | 何时带 | 为什么 |
| --- | --- | --- |
| `Wow-Request-Id` | 每条命令都带，每条命令一个新的 UUID；再次发送同一条命令时用同一个 | 同一个 ID 的重试被当作已完成（`duplicate`）答复，绝不会决策两次 |
| `Wow-Wait` | 默认的等待 `snapshot` 不合适时 | 命令走到哪一步再答复：`accepted`、`committed`、`snapshot`，或它等待的订阅（`handled(<processor>.<function>)`） |
| `Wow-Wait-Timeout` | 默认的 10 秒不合适时 | 等待多久，至多 `wow.wait.max-timeout` |
| `If-Match` | 命令必须作用于你读到的版本时 | 作用于其他版本的命令以 412 拒绝 |
| `Authorization` | 网关认证所需的凭据 | 网关把它变成服务读取的身份 |

<!-- sample: fragment -->

```bash
curl -X POST https://gateway.example.com/bank/account/alice/deposit \
  -H 'Authorization: Bearer <token>' \
  -H 'Content-Type: application/json' \
  -H 'Wow-Request-Id: 01937c2e-7b1a-7c4e-9f0e-2d1b8a6c5e3f' \
  -H 'Wow-Wait: committed' \
  -H 'Wow-Wait-Timeout: 5s' \
  -d '{"amount": 100}'
```

丢失答复的客户端用同一个 `Wow-Request-Id` 再次发送命令。换一个新 ID 就成了另一条命令，会再执行一次。全部请求头见 [HTTP API](../reference/http#request-headers)；`WowClient` 会自己发送这些请求头。

## 读取答复 {#read-the-answer}

成功的命令答复 `200` 与它的结果（等待止于 `accepted` 时为 `202`）。其他答复都是 `application/problem+json` 的 problem，其中 `code` 说明发生了什么，`retry` 说明重试能做什么：

| `retry` | 怎么做 |
| --- | --- |
| `none` | 不要原样再发：修正请求，或告诉用户 |
| `same-request` | 在 `Retry-After` 之后，用同一个 `Wow-Request-Id` 再发 |
| `new-request` | 在 `Retry-After` 之后，作为新请求再发 |

调用方最常遇到的错误码：

| 错误码 | 状态 | 含义 |
| --- | --- | --- |
| `wow.command.invalid` | 400 | 命令未通过校验；`violations` 列出每个字段 |
| `wow.auth.unauthenticated`、`wow.auth.forbidden` | 401、403 | 没有有效身份，或身份不允许发送这条命令 |
| `wow.aggregate.not-found` | 404 | 没有该 ID 的聚合 |
| 领域错误码，例如 `bank.account.insufficient-balance` | 默认 422 | 聚合拒绝了命令；错误码是领域的契约 |
| `wow.aggregate.version-mismatch` | 409，带 `If-Match` 时 412 | 聚合不在命令期望的版本上 |
| `wow.command.busy` | 429 | 等待的命令太多；稍后用同一个请求 ID 重试 |
| `wow.node.closing`、`wow.storage.unavailable` | 503 | 节点正在关闭、存储不可达；用同一个请求 ID 重试 |
| `wow.wait.timeout` | 504 | 等待超时；命令可能已提交，因此用同一个请求 ID 重试 |

框架的全部错误码见[错误码](../reference/error-codes)，领域如何声明自己的错误码见[拒绝与错误码](./rejections)。

## 消费发布的事件流 {#consume-the-published-streams}

对上下文中发生的事做出反应的应用，读取该上下文发布的事件流：在 Kafka 上，`wow.<context>.<aggregate>.state` 每次提交一条记录，带着提交的事件与提交后的状态（[外部消费者](./published-streams#external-consumers)）。事件流是上下文契约的一部分，Manifest 的兼容性检查像守护命令一样守护它的字段。

要改变上下文中的东西，调用它的 HTTP API，而不是写它的命令主题 `wow.<context>.commands`：

- 命令主题上的记录不经准入就执行：服务不认证它，所以谁能写它，谁就能冒充任何操作者、租户与范围。这个主题只对平台自己的服务开放（[限制命令主题](../operate/security#restrict-the-command-topics)）。
- 记录得不到答复：没有结果、没有拒绝的错误码，也没有等待。
- 命令主题是平台自己的服务访问上下文的途径，不是给其他任何人的契约。
