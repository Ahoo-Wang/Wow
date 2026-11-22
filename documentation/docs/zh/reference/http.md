---
title: HTTP API
description: 服务公开端口上的每个路由、它的请求头、Wow-Wait 语法、响应、服务端推送事件和 problem。
outline: deep
---

# HTTP API

服务的公开端口提供这些路由，它们从服务的 Manifest 生成。怎样使用它们，见[提供和调用 HTTP API](../guide/http)；请求体和响应体的结构，见[线上协议](./protocols)。

## 路由 {#routes}

**命令**

| 方法和路径 | 发送 |
| --- | --- |
| `POST /<context>/<aggregate>/{id}/<command>` | 命令（kebab case），发往聚合 `{id}` |
| `POST /<context>/<aggregate>/<command>` | 一条创建命令，发往一个由服务端分配 ID 的新聚合；命令体指明了聚合的命令则发往那个聚合；挂载命令或 `@Unaddressed` 命令发往其转换器找到的聚合；ID 即所有者 ID 的聚合（`idSource = OWNER`）的任何命令发往调用方自己的那个聚合 |
| `DELETE /<context>/<aggregate>/{id}` | `DeleteAggregate` |
| `DELETE /<context>/<aggregate>` | 对 ID 即所有者 ID 的聚合：删除调用方自己的那个聚合（`DeleteAggregate`） |
| `POST /<context>/<aggregate>/{id}/wow.lifecycle.restore-aggregate` | `RestoreAggregate` |
| `POST /<context>/<aggregate>/{id}/wow.lifecycle.transfer-owner` | `TransferOwner`，请求体为 `{"ownerId": "…"}` |
| `POST /<context>/<aggregate>/{id}/wow.lifecycle.transfer-space` | `TransferSpace`，请求体为 `{"spaceId": "…"}` |
| `POST /<context>/<aggregate>/wow.snapshot.rebuild-snapshot` | 重建 `{"ids": [...]}` 的快照 |
| `POST /<context>/wow.schedule/{job}/<command>` | 任务命令：`enable-job`、`reschedule-job`、`disable-job` |

内部命令（`@InternalCommand`，Manifest 中的 `"internal": true`）的路由只在内部端口上，服务间的调用在那里送达它：在这个端口上请求它的路由，得到与任何未知路径相同的 `404` problem，`GET /wow/openapi` 也不含这条命令（[只在内部受理的命令](../guide/aggregates#keep-a-command-internal)）。只有聚合在 `@AggregateRoot(lifecycle)` 中列出的生命周期命令才有路由。命令的请求体是它的 JSON；空的命令体是 `{}`。命令体指明了聚合的命令（`@AggregateId`）只有不带 `{id}` 的路由，Manifest 列出它的 `idProperty`，即命令体中承载 ID 的属性：空白的 ID 是 `wow.command.malformed`（400），在命令体之外又指明另一个聚合的请求也是。快照重建和任务命令是 Wow 内置聚合的系统命令：没有命令授权器的服务在这个端口上拒绝它们（403 `wow.auth.forbidden`），默认从不放行（[授权命令](../operate/security#authorize-commands)）。

**读取**

| 方法和路径 | 返回 |
| --- | --- |
| `GET /<context>/<aggregate>/{id}/state` | 最新状态；`?version=n`、`?at=<instant>` 或 `?minVersion=n` |
| `GET /<context>/<aggregate>/{id}/state/trace` | 提交，`?from=`、`?to=`、`?limit=`（100，最多 1,000） |
| `GET /<context>/<aggregate>/state`、`…/state/trace` | 同上，对 ID 即所有者 ID 的聚合：调用方自己的那个聚合 |
| `POST /<context>/<aggregate>/snapshot/query` | 快照查询 |
| `POST /<context>/<aggregate>/events/query` | 事件查询 |
| 对任一查询路径发 `GET` | 模型的能力描述，版本作为 `ETag` |
| `POST /wow/dataset/<context>/<dataset>/query` 及它的 `GET` | 数据集查询，及它的能力描述 |
| `GET /wow/dataset` | 调用方能看到的数据集 |

**服务**

| 方法和路径 | 返回 |
| --- | --- |
| `GET /wow/manifest` | 服务合并后的 Manifest（`wow.http.manifest.enabled`），其中包括内部命令，标注为 `internal` |
| `GET /wow/openapi` | 这些路由的 OpenAPI 3.1 文档（`wow.http.openapi.enabled`）：其中没有内部命令；每个聚合的记录按它的 Manifest 给出类型，`state` 按状态的 schema，`access` 按它声明的访问属性 |

上下文自己的服务路由（它的代码声明的处理函数）按 Manifest 列出的样子提供。`wow.http.ops.enabled: true`（默认关闭）还提供运维人员的路由：`/wow/ops/parked` 下的停放条目，以及读取节点订阅的 `GET /wow/ops/subscriptions`（[运维控制台](../operate/ops-console)）。

某个上下文路径下的其他任何请求，都是一个 `404` problem。

## 请求头 {#request-headers}

| 请求头 | 用于 | 含义 |
| --- | --- | --- |
| `Wow-Request-Id` | 命令 | 调用方给请求的 ID：用同一个 ID 重试会被当作已完成来回答（`duplicate`），绝不会决定两次；同一个 ID 用于另一条命令时是 `wow.command.request-id-reused` |
| `Wow-Wait` | 命令 | 回答之前要等到哪里（见下文）；默认 `snapshot`，没有快照的聚合为 `committed`，在那里显式的 `snapshot` 是 `wow.wait.invalid` |
| `Wow-Wait-Timeout` | 命令 | 等待多久：`500ms`、`10s`、`1m`（单位紧跟数字）或 ISO-8601，至少 1 毫秒（`10 s` 和 `PT0.0001S` 是 `wow.wait.invalid`）；默认 `wow.wait.default-timeout`（10 秒）。更长的会被截到 `wow.wait.max-timeout`（1 分钟）：超时是客户端给自己的等待设的上限，节点的最大值再给它设上限 |
| `If-Match` | 命令 | 聚合必须所处的版本，写成强标签：`"3"`，或状态读取写出的 `"3-…"`，其中的摘要不做比较；聚合处于其他版本时返回 `412 wow.aggregate.version-mismatch`。不指版本的标签（`W/"3"`、`"03"`、`"2147483648"`）或指两个版本时返回 `400 wow.request.malformed`。它的多行是一个列表（[实体标签](./protocols#entity-tags)） |
| `If-None-Match` | 状态读取、能力描述 | 上一次的 `ETag`；仍然有效时返回 `304`。无法解析的会被忽略：缓存条件失败时放行（[实体标签](./protocols#entity-tags)） |
| `Accept: text/event-stream` | 命令、`list` 查询 | 服务端推送事件（见下文） |
| `Last-Event-ID` | 流式的 `list` 查询 | 从哪个位置之后续读 |
| `Wow-Scope` | 每个路由 | 对被允许跨作用域行事的调用方：要在其中行事的租户、所有者和空间，一个 RFC 8941 字典，`tenant="t1", owner="u1", space="s1"`。在查询路由上，打开 `wow.query.allow-cross-tenant` 时，`tenant=*` 或 `tenant=("t1" "t2")` 可以跨租户读取。它的多行用逗号连成一个字典；同一个成员给出两次是格式错误（`wow.command.malformed`，读取时为 `wow.query.malformed`） |
| `Wow-Space` | 每个路由 | 在调用方自己的空间中，要在其中行事的那个 |

`wow.http.request-facts` 列出的请求头会复制进命令，并写进它的事件流。

前缀 `Wow-` 属于 Wow。除了上面这些请求头，公开端口还接收 `Wow-Service`（它不读取）和开发身份请求头（`Wow-User`、`Wow-Owner`、`Wow-Roles`、`Wow-Tenant`、`Wow-App`，只在开发 profile 下读取）。先升级节点，再升级客户端：客户端发送更新版本 Wow 的 `Wow-` 请求头时，旧节点会拒绝它。内部端口仍然读取下一个次版本的对端发送的内容。

### 公开端口拒绝什么 {#refused}

客户端在请求体之外发送的内容，不会被当作别的东西读取，也不会被丢弃：

| 请求 | 回答 |
| --- | --- |
| 端口不接收的 `Wow-` 请求头（`Wow-Wiat`，`Wow-Wait` 的拼写错误） | `400 wow.request.malformed`，指出它 |
| 分两行或更多行给出的 Wow 请求头（`Wow-Request-Id: r1` 和 `Wow-Request-Id: r2`），`Wow-Scope` 除外 | `400 wow.request.malformed`，指出它：追加一行的代理不能决定取值 |
| 任何路由上的 `Wow-Call`（服务同步调用的请求头）：命令、查询、状态读取、服务路由 | `403 wow.auth.forbidden`，无论凭据如何：调用只在内部端口上接收 |
| 路径中的矩阵参数（`/shop/cart/c-1;x=1/add-item`） | `400 wow.request.malformed`：没有路由接收它们 |
| 类型不是 `application/json` 或 `application/<name>+json` 的请求体，参数不计（`*/*`、`application/*`、`image/x+json`） | `415` |
| 含控制字符（`a%00b`）、两端有空白（`%20c-1`），或者是 `.` 或 `..` 的聚合 ID | `400 wow.command.malformed`。含 `%2F` 的 ID 是 ID `a/b`，Unicode ID（`%E8%B4%AD`）按原样接收：客户端会编码它发送的 ID |
| 状态读取上的空白 ID（`/shop/cart/%20/state`） | `400 wow.query.malformed` |
| 状态读取或 trace 不接收的参数（`?verison=1`），或给出两次的参数（`?version=1&version=9`） | `400 wow.query.invalid`，违规项指出它 |
| 不是不带符号、不带前导零的 ASCII 数字的 `version`、`minVersion`、`from`、`to` 或 `limit`（`%2B1`、`001`、`١`） | `400 wow.query.invalid`，违规项指出它 |
| 服务路由的数字参数不是 JSON 写法的数字（`NaN`、`Infinity`、空格、`+1`、`01`；整数带小数或指数） | `400 wow.route.malformed`，违规项指出它 |

## Wow-Wait {#wow-wait}

```text
plan := hop (">" hop)*
hop  := "accepted" ["(" context ")"]
      | "committed" ["(" context ")"]
      | "snapshot" ["(" context ")"]
      | "queryable" ["(" context ")"]
      | "handled(" [context "/"] subscription ")"
```

| 跳 | 何时到达 |
| --- | --- |
| `accepted` | 命令通过了准入；只有经 Kafka 路由的命令才是持久的 |
| `committed` | 它的事件已追加，或者它没有追加就有了结论 |
| `snapshot` | 快照包含这次提交 |
| `queryable` | 聚合的快照查询所读的存储包含这次提交：查询读另一个存储（`query.snapshot.store`）时是它的副本，否则与 `snapshot` 相同 |
| `handled(sub)` | 订阅 `sub`（`processor.function`）处理了这次提交 |

不带上下文的跳在命令所在的上下文中。在 Saga 的 `handled` 那一跳之后，后面的跳适用于 Saga 派生的命令，在它们各自的上下文中。一个计划至多 `wow.wait.max-hops`（16）跳。节点没有其 Manifest 的上下文中的跳，会在链到达那里时检查，除非 `wow.wait.unknown-contexts: reject` 以 `wow.wait.invalid` 拒绝它。

## 响应 {#responses}

| 状态 | 何时 |
| --- | --- |
| `200` | 等待结束时命令的结果；一个状态；一个查询的回答 |
| `202` | 等待止于 `accepted` 的命令 |
| `304` | 请求指明了其 `ETag` 的状态或能力描述 |
| `4xx`、`5xx` | 一个 RFC 9457 problem（见下文）；稍后重试可能成功时带 `Retry-After` |

**服务端推送事件。** 带 `Accept: text/event-stream` 的命令在准入后以 `200` 回答，然后随着等待推进，每个结果发一个事件（多条派生命令到达的同一跳各发一个），`id` 是结果的序号，事件以这一跳的阶段命名，数据是结果，直到等待结束；准入时被拒绝则返回一个 problem。流式的 `list` 每条记录发一个 `row` 事件，它的 `id` 是这一行位置的游标（排序无法续读的列表没有 `id`），然后是一个 `end` 事件 `{"reason", "resumable"}`：全部记录发完为 `complete`（`"resumable":false`），`wow.query.stream.max-hold` 之后为 `hold-expired`，节点停止为 `node-stopping`，存储没能及时给下一块查询许可为 `permit-reclaimed`。可续读的流中，除 `complete` 外都可以用 `Last-Event-ID` 续读。第一行之后的失败以一个携带 problem 的 `error` 事件结束流。

## Problem {#problems}

每个错误都是 `application/problem+json`：

| 成员 | 包含 |
| --- | --- |
| `type` | `urn:wow:error:<code>`；只属于 HTTP 本身的 problem（例如路由不存在）为 `about:blank` |
| `code` | 错误码，框架错误码（`wow.*`）或领域错误码；`about:blank` 的 problem 没有它 |
| `status` | HTTP 状态 |
| `title` | 给人看的错误码简短摘要，每次出现都相同：框架错误码的标题（[错误码](./error-codes)），领域错误码的 `@ErrorCode(description)` |
| `detail` | 给人看的、这次请求发生了什么：拒绝或失败的消息 |
| `violations` | 字段级的违规：`path`、`code`、`message`。命令约束的 `code` 是约束名的小写 kebab 形式：`NotBlank` 是 `not-blank`。命令体不能读作其命令时（`wow.command.malformed`），给出一条违规：属性的路径，以及 `wrong-kind`、`out-of-range`、`invalid-format`、`not-in-enumeration`、`duplicate-property` 或 `unknown-property`（[JSON 中的值](./json-values)）。 |
| `retry` | 每个 problem 都有：重试能做什么，`none`、`same-request`（用同一个 `Wow-Request-Id` 重发）或 `new-request`；不是 `none` 时带 `Retry-After` |
| `result` | 命令产生了结果时，命令完整的结果 |
| `version` | problem 所回答的聚合版本：拒绝的版本，或低于 `minVersion` 的状态读取时的当前版本 |
| 其他任何成员 | 拒绝的结构化扩展，按名字；与上面某个成员或 `instance` 同名的扩展会被略去 |

领域错误码的状态是它的 `@ErrorCode(status)`，默认 422，可以用 `wow.http.errors.[<code>].status` 按错误码覆盖。框架的错误码见[错误码](./error-codes)。应用自己的路由也以 problem 回答它们的失败（`wow.http.global-errors.enabled`，默认打开）。

### 应用自己的路由 {#application-routes}

在 Wow 的路由之外，应用自己提供的路由（注解式或函数式）上，Spring WebFlux 抛出的失败这样回答：

| 请求 | 回答 |
| --- | --- |
| 没有路由匹配 | 404 `about:blank`，detail 是方法与路径：`No route GET /orders.` |
| 用了路由不提供的方法 | 405 `about:blank`，带 `Allow` |
| 请求体的媒体类型路由不接受 | 415 `about:blank`，带 `Accept` |
| 路由给出的类型都不在 `Accept` 里 | 406 `about:blank` |
| 读不出：编解码器读不出的请求体，缺少或类型不对的参数、请求头、路径变量 | 400 `wow.request.malformed`，detail 是 Spring 给的原因，并带一条 violation 指出读不出的是什么：参数用它的名字，请求体用 `body`，code 为 `wow.request.malformed` |
| `@Valid` 请求体或方法的约束不满足 | 400 `about:blank` |
| 应用自己抛出 `ResponseStatusException` | 只有它的状态，`about:blank`，4xx 时以它的原因作 detail |

除了状态本身没有更多可说的，就回答为 `about:blank`，即 RFC 9457 所说的只有状态的 problem；Wow 自己的路由也是这样。关掉 `wow.http.global-errors.enabled` 后，应用自己的路由按 Spring 的方式回答。

### 应用自己的失败 {#mapped-failures}

框架不认识的失败（例如调用另一个系统失败）回答为一个 500，不说明原因，原因写进日志。应用通过 `ProblemMapper` bean（`me.ahoo.wow.api.problem`）用自己的错误码回答自己的失败：映射器拿到失败，返回用来回答的 `WowProblemException`；失败不是它的，就返回 null。共享库也这样提供映射器，通过它自己的自动配置。

```kotlin
@Bean
@Order(0)
fun catalogProblems(): ProblemMapper = ProblemMapper { failure ->
    (failure as? WebClientResponseException)?.let {
        WowProblemException("shop.catalog.unreachable", 502, Retry.SAME_REQUEST, null, "The catalog did not answer.")
    }
}
```

- **在哪里。** 应用自己的路由（`wow.http.global-errors.enabled`）、服务路由的处理函数、状态读取、命令路由和查询，包括流式列表的 `error` 事件。内部端口和运维路由不用它；命令的失败也绝不用它：决策函数抛出的异常是命令的结果 `wow.command.failed`（[拒绝与错误码](../guide/rejections)）。saga 的同步调用和命令客户端也不用它，它们收到的是另一个服务按自己的映射给出的回答。
- **映射器看到什么。** 只看到本来会回答为不说明原因的 500 的失败（查询的 `wow.query.failed`）：绝不会看到 `WowProblemException`、命令的拒绝、认证或准入的拒绝、不可用的存储，或超过编解码器上限的请求体，作为另一个失败的 cause 时也看不到。包着它们的应用自己的异常仍可以被映射。
- **顺序。** 按 bean 的顺序（`@Order`）询问映射器，先问失败本身，再依次问它的每个 cause；第一个给出 problem 的映射器作答。抛出异常的映射器被跳过，它的异常写进日志。
- **检查。** 映射出的 problem 如果用了框架保留的错误码（`wow.*`），状态不是 4xx 或 5xx 或者是 401（只有认证能回答 401），或者带着命令的结果，就不采用：失败回答为不说明原因的 500，映射器和错误码记一次日志。映射出的错误码的状态仍由 `wow.http.errors.[<code>].status` 决定。
- **目录。** 在上下文里用 `@ErrorCode` 声明映射出的错误码，或写进服务路由的 `errors`，让客户端和 Manifest 知道它。哪里都没列出的错误码照样作答，并警告一次。
- **日志。** 映射出的 5xx 连同失败记为警告，映射出的 4xx 记为 debug。

problem 的消息就是它的 `detail`，调用方会读到：不要在里面写调用方不该看到的东西。
