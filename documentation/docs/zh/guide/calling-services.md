---
title: 调用其他服务
description: 用类型化的命令客户端把命令对象发往任意上下文：进程内、经命令主题或经 HTTP；配置对端、service 与 user 模式，并按名字调用命令。
outline: deep
---

# 调用其他服务

发送命令的应用代码，例如控制器、定时任务或集成代码，使用命令客户端。它接收命令对象和命令所针对的聚合，无论聚合在哪里提供服务都能到达：在本服务里、经命令主题到对端上下文，或经 HTTP 到对端。三种情况下代码都一样。本页说明怎样发送、怎样配置对端的位置，以及会返回什么。Saga 则是派生它的命令：见[编写 Saga](./sagas)。

## 发送命令 {#send-a-command}

宿主提供一个 `CommandClient` Bean。用 `of(caller)` 选择调用方，然后把命令对象发往聚合的 ref：

<!-- sample: example/eshop/e2e/src/integrationTest/kotlin/me/ahoo/wow/example/eshop/e2e/EshopClientTest.kt#send -->

```kotlin
val ref = EshopOrderingContract.Order.ref(Caller.DEFAULT_TENANT, "client-alice-1")
val submitted = basket.bean<CommandClient>().of(alice).wait(SubmitOrder(items), ref)
submitted.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
submitted.ref.assert().isEqualTo(ref)
```

ref 来自上下文生成的契约 `EshopOrderingContract.Order.ref(tenant, id)`，位于 ordering 的 api 模块中。命令不指明路径，也不指明类型名：客户端从类路径上的契约找出命令的类型。

| 调用 | 返回 |
| --- | --- |
| `send(command, ref)` | 命令被接受后的结果；对端的命令，则是在它的主题确认收到之后 |
| `wait(command, ref)` | 命令的等待计划的结果，除非选项指定了计划，否则是服务端的默认计划 |
| `stream(command, ref)` | 等待中每一跳结果的 flow，最后一个就是 `wait` 返回的结果；收集这个 flow 才会发送命令 |

`SendOptions.of(...)` 设置其余选项：`wait` 计划及其 `timeout`、让命令幂等的 `requestId`（不写时生成新的）、聚合的 `expectedVersion`，以及对被允许在另一个作用域行事的调用方，`targetTenant` 和 `targetScope`。

命令总有一个可追责的调用方：在 `of` 给出别的调用方之前，客户端以匿名调用方发送，命令没有受信任模式。系统发送方传入它自己的服务调用方。

## 返回什么 {#what-comes-back}

`send` 和 `wait` 只返回成功的结果。否则抛出异常：

| 异常 | 何时 | 重试 |
| --- | --- | --- |
| `CommandRejectedException` | 聚合拒绝了命令，或者准入拒绝了它（未知、格式错误或无效的命令，被禁止的调用方）。`code` 是错误码 | 否：同一个请求会再次被拒绝 |
| `WowProblemException` | 技术故障：节点繁忙或正在关闭、存储或 broker 不可用、等待超时。`code` 和 `status` 说明是哪一种 | 按它的 `retryHint`：是否重试、是否用同一个请求 ID，以及多久之后 |

`stream` 发出每个结果，无论状态如何。按错误码捕获拒绝的例子，见[拒绝与错误码](./rejections#what-the-caller-receives)。

## 每条命令发往哪里 {#where-each-command-goes}

| 聚合 | 客户端怎样到达它 |
| --- | --- |
| 本服务的 | 在进程内，不编码命令 |
| 对端上下文的，默认 | 经对端的命令主题 `wow.<context>.commands`；主题收到后 `send` 就返回 |
| 列在 `wow.clients.contexts` 下的对端的 | 经 HTTP 发往对端的服务 |

客户端从对端 api jar 携带的契约认识对端，所以应用只需依赖对端的 `*-api` 模块。

## 配置经 HTTP 访问的对端 {#configure-a-peer-over-http}

列在 `wow.clients.contexts.<context>` 下的对端经 HTTP 访问，有两种模式：

| 模式 | 访问 | 身份 |
| --- | --- | --- |
| `service`，默认 | 对端的内部端口，地址是 `internal-url` | 本服务，带它的服务凭据，代表它发送时指明的调用方 |
| `user` | 对端的公开路由，地址是 `url` | 终端用户，转发当前正在处理的请求的请求头（`wow.clients.forwarded-headers`，默认是 `Authorization`） |

在 service 模式下，客户端出示服务令牌 `wow.calls.token`，对端在它的 `wow.http.services` 中为本服务列出这个令牌；于是对端信任本服务指明的调用方。`internal-url` 默认取 Saga 同步调用所用的地址 `wow.calls.contexts.<context>.url`。basket 服务就是这样访问 ordering 的内部端口的：

<!-- sample: example/eshop/basket/basket-server/src/main/resources/application.yaml#calls -->

```yaml
# The checkout saga submits the order to the ordering service synchronously (decision D193), through its command
# gateway on its internal port (wow.internal.port there; its public port refuses a call), with this service's
# credential, which the ordering service trusts (wow.http.services.eshop-basket-service.tokens there). The token below is for trying the shop out on one machine:
# a deployment sets ESHOP_BASKET_SERVICE_TOKEN, a secret of at least 32 characters, and rotates it there and here.
calls:
  contexts:
    eshop-ordering:
      url: http://127.0.0.1:9093
  token: ${ESHOP_BASKET_SERVICE_TOKEN:eshop-basket-local-service-token-not-a-secret}
```

`wow.clients.timeout` 限制每个请求的时间（默认 30 秒，等待更长的命令会更长），`wow.clients.retry.*` 重试那些重试能够越过的故障。对端接受哪些服务身份，见[保护部署](../operate/security#close-the-internal-port)。

## 按名字调用命令 {#call-a-command-by-name}

控制台、网关或工具可以在没有对端 api jar 的情况下调用对端。它用 `wow.clients.contexts.<context>.contract: manifest` 列出对端，并发送一个 `NamedCommand`：命令的完整类型名 `context.aggregate.Command` 及其 JSON 命令体。客户端从 `GET /wow/manifest` 读取对端的 Manifest，在发送任何东西之前拒绝 Manifest 没有声明的命令。

## 在 Wow 宿主之外 {#outside-a-wow-host}

不是 Wow 服务的应用用 `WowClient.builder()` 自己构建客户端：每个上下文的服务在哪里（`endpoints`）、模式和凭据（`service` 或 `user`），以及超时。`client.commands` 是同一个 `CommandClient`，对端的契约来自类路径上的 api jar。

## 在 Java 中 {#from-java}

客户端的函数是 `suspend` 函数。Java 代码用 `JavaCommandClient` 包装客户端，它的调用是阻塞的，或者是返回 `CompletableFuture` 的异步调用。在你自己的线程里调用阻塞形式，绝不要在 Netty 或 WebFlux 的事件循环里调用。
