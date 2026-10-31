---
title: 调用其他服务
description: 用类型化的命令客户端把命令对象发往任意上下文：进程内、经命令主题或经 HTTP；配置对端、service 与 user 模式，并按名字调用命令。
outline: deep
---

# 调用其他服务

发送命令的应用代码，例如控制器、定时任务或集成代码，使用命令客户端。它接收命令对象和命令所针对的聚合，无论聚合在哪里提供服务都能到达：在本服务里、经命令主题到对端上下文，或经 HTTP 到对端。三种情况下代码都一样。本页说明怎样发送、怎样配置对端的位置，以及会返回什么。Saga 则是派生它的命令：见[编写 Saga](./sagas)。

## 发送命令 {#send-a-command}

宿主提供一个 `CommandClient` Bean。用 `of(caller)` 选择调用方，然后连同聚合的 id 发送命令对象：

<!-- sample: examples/eshop/e2e/src/integrationTest/kotlin/me/ahoo/wow/example/eshop/e2e/EshopClientTest.kt#send -->

```kotlin
val ref = EshopOrderingContract.Order.ref(Caller.DEFAULT_TENANT, "client-alice-1")
val submitted = basket.bean<CommandClient>().of(alice).wait(SubmitOrder(items), "client-alice-1")
submitted.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
submitted.ref.assert().isEqualTo(ref)
```

命令不指明路径，也不指明类型名：客户端根据命令的类，从服务的模型和类路径上的契约找出聚合类型。创建命令不指明 id，由服务端分配。

| 调用 | 返回 |
| --- | --- |
| `send(command, id)` | 命令被接受后的结果；对端的命令，则是在它的主题确认收到之后 |
| `wait(command, id)` | 命令的等待计划的结果，除非请求指定了计划，否则是服务端的默认计划 |
| `stream(command, id)` | 等待中每一跳结果的 flow，最后一个就是 `wait` 返回的结果；收集这个 flow 才会发送命令 |

每个调用也接受一个 `CommandRequest`，即 `CommandRequest.of(command, id, ...)` 或 `CommandRequest.to(ref, command)`，由它设置其余内容：聚合 `type`（`DeleteAggregate` 这类每个聚合都接受的生命周期命令总要指明它）、`wait` 计划及其 `timeout`、让命令幂等的 `requestId`（不写时生成新的）、聚合的 `expectedVersion`，以及对被允许在另一个作用域行事的调用方，`targetTenant` 和 `targetScope`。否则租户和作用域就是调用方自己的：与调用方相同的目标租户不改变任何东西。

命令总有一个可追责的调用方：在 `of` 给出别的调用方之前，客户端以匿名调用方发送，命令没有受信任模式。系统发送方传入它自己的服务调用方。

## 返回什么 {#what-comes-back}

`send` 和 `wait` 只返回成功的结果。否则抛出异常：

| 异常 | 何时 | 重试 |
| --- | --- | --- |
| `CommandRejectedException` | 聚合拒绝了命令，或者准入拒绝了它（未知、格式错误或无效的命令，被禁止的调用方）。`code` 是错误码 | 否：同一个请求会再次被拒绝 |
| `WowProblemException` | 技术故障：节点繁忙或正在关闭、存储或 broker 不可用、等待超时。`code` 和 `status` 说明是哪一种 | 按它的 `retry` 和 `retryAfter`：是否重试、是否用同一个请求 ID，以及多久之后 |

`stream` 发出每个结果，无论状态如何。按错误码捕获拒绝的例子，见[拒绝与错误码](./rejections#what-the-caller-receives)。

## 每条命令发往哪里 {#where-each-command-goes}

| 聚合 | 客户端怎样到达它 |
| --- | --- |
| 本服务的 | 在进程内，不编码命令 |
| 对端上下文的，默认 | 经对端的命令主题 `wow.<context>.commands`；主题收到后 `send` 就返回 |
| 列在 `wow.peers` 下的对端的 | 经 HTTP 发往对端的服务 |

客户端从对端 api jar 携带的契约认识对端，所以应用只需依赖对端的 `*-api` 模块。

## 配置经 HTTP 访问的对端 {#configure-a-peer-over-http}

列在 `wow.peers.<context>` 下的对端经 HTTP 访问，有两种模式：

| 模式 | 访问 | 身份 |
| --- | --- | --- |
| `service`，默认 | 对端的内部端口，地址是 `url` | 本服务，声明自己，代表它发送时指明的调用方 |
| `user` | 对端的公开路由，地址是 `public-url` | 终端用户，转发当前正在处理的请求的请求头（`wow.peer.forwarded-headers`，默认是 `Authorization`） |

在 service 模式下，客户端在 `Wow-Service` 请求头里声明本服务，即它的 `wow.service`。内部端口只有平台自己的 Pod 能访问，所以对端接受本服务指明的调用方；对端如果在它的 `wow.callers` 中为本服务列出了命令和模型，这些列表限制本服务可以调用和读取什么。Saga 的同步调用也发往这个 `url`，模式由 `wow.peer.mode`（或对端自己的 `mode`）决定。basket 服务就是这样访问 ordering 的内部端口的：

<!-- sample: examples/eshop/basket/basket-server/src/main/resources/application.yaml#calls -->

```yaml
# The checkout saga submits the order to the ordering service synchronously, through its command
# gateway on its internal port (wow.internal.port there; its public port refuses a call), naming this service
# (Wow-Service), which the ordering service lists the commands of (wow.callers there).
peers:
  eshop-ordering:
    url: http://127.0.0.1:9093
```

没有 `url` 时，客户端按 DNS 找到对端的节点（`wow.peer.discovery: dns`，默认）：按 `wow.peer.dns.name-template` 以对端的服务名查 SRV 记录，即 `_internal._tcp.{service}-cluster`，在 Kubernetes 上是其内部端口的 headless Service，`{service}` 是类路径上对端契约写明的服务，由它的域模块声明（`wow { service }`）；契约没有写明服务时，只能通过 `url` 访问对端。请求依次发往它的各个节点；不接受连接的节点，或回答自己正在关闭、处于待命的节点，会在同一次尝试中跳到下一个。记录每 30 秒重新查询一次，所有节点都不接受连接时立即重查。`wow.peer.discovery: static` 只用 `url`，社区提供的 `PeerDiscovery` 也可以用别的方式找到节点。Saga 的调用同样不必列出：契约在类路径上的上下文都按同样的方式找到。

`wow.peer.timeout` 限制每个请求的时间（默认 30 秒，等待更长的命令会更长），`wow.peer.retry.*` 重试那些重试能够越过的故障。对端从声明了自己的服务那里接受什么，见[保护部署](../operate/security#close-the-internal-port)。

## 按名字调用命令 {#call-a-command-by-name}

控制台、网关或工具可以在没有对端 api jar 的情况下调用对端。它用 `wow.peers.<context>.contract: manifest` 列出对端，并发送一个 `NamedCommand`：命令的完整类型名 `context.aggregate.Command` 及其 JSON 命令体。客户端从 `GET /wow/manifest` 读取对端的 Manifest，在发送任何东西之前拒绝 Manifest 没有声明的命令。

## 在 Wow 宿主之外 {#outside-a-wow-host}

不是 Wow 服务的应用用 `WowClient.builder()` 自己构建客户端：每个上下文的服务在哪里（`endpoints`）、模式（`service` 带上它以哪个服务的名字调用，或者 `user` 带上终端用户的凭据），以及超时。`client.commands` 是同一个 `CommandClient`，对端的契约来自类路径上的 api jar。

## 在 Java 中 {#from-java}

客户端的函数是 `suspend` 函数。Java 代码用 `JavaCommandClient` 包装客户端，它的调用是阻塞的，或者是返回 `CompletableFuture` 的异步调用。在你自己的线程里调用阻塞形式，绝不要在 Netty 或 WebFlux 的事件循环里调用。
