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
| `GET /wow/manifest` | 服务合并后的 Manifest，其中包括内部命令，标注为 `internal`；公开端口只在 `wow.http.manifest.enabled: true` 时提供（默认关闭），内部端口总是提供给集群中的服务 |
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
| `Wow-Scope` | 每个路由 | 对被允许跨作用域行事的调用方：要在其中行事的租户、所有者和空间，一个 RFC 8941 字典，`tenant="t1", owner="u1", space="s1"`。它总是指明一个作用域：不写的所有者或空间就是没有，所以只写 `tenant="t1"` 在 `t1` 里行事、不检查所有者与空间；只写了租户的请求，Wow 的客户端会写出调用方自己的作用域，从而保留它。在查询路由上，打开 `wow.query.allow-cross-tenant` 时，`tenant=*` 或 `tenant=("t1" "t2")` 可以跨租户读取。它的多行用逗号连成一个字典；同一个成员给出两次是格式错误（`wow.command.malformed`，读取时为 `wow.query.malformed`） |
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
| 路径中的矩阵参数（`/shop/cart/c-1;x=1/add-item`），包括不带参数名的 `;`（`c-1;`、`c-1;=v`） | `400 wow.request.malformed`：没有路由接收它们 |
| 类型不是 `application/json` 或 `application/<name>+json` 的请求体，参数不计（`*/*`、`application/*`、`image/x+json`） | `415` |
| [ID 规则](#aggregate-ids)拒绝的聚合 ID：含控制字符（`a%00b`）、两端有空白（`%20c-1`）、`.` 或 `..`、含 `/`、`;` 或 `\`（`a%2Fb`）、长于 256 个字符，或者 UTF-8 编码后长于 512 字节 | `400 wow.command.malformed`。Unicode ID（`%E8%B4%AD`）按原样接收：客户端会编码它发送的 ID。长到使请求行超过其上限的 ID 会先被拒绝：`414`（[路由之前](#server-refusals)） |
| 状态读取上的空白 ID（`/shop/cart/%20/state`） | `400 wow.query.malformed` |
| 状态读取或 trace 不接收的参数（`?verison=1`），或给出两次的参数（`?version=1&version=9`） | `400 wow.query.invalid`，违规项指出它 |
| 不是不带符号、不带前导零的 ASCII 数字的 `version`、`minVersion`、`from`、`to` 或 `limit`（`%2B1`、`001`、`١`） | `400 wow.query.invalid`，违规项指出它 |
| 服务路由的数字参数不是 JSON 写法的数字（`NaN`、`Infinity`、空格、`+1`、`01`；整数带小数或指数） | `400 wow.route.malformed`，违规项指出它 |

### 服务器在路由之前拒绝什么 {#server-refusals}

服务器有四个上限，在路由读取请求之前就会遇到。每个回答都是只有状态的 Problem，没有 `code`（[Problem](#problems)）；它的 `detail` 指出上限和设置它的配置项。

| 请求 | 回答 | 上限：配置项，默认值 |
| --- | --- | --- |
| 请求行（方法、带查询的路径、版本）长于上限 | `414`，连接随之关闭 | `server.netty.max-initial-line-length`，4 KB |
| 请求头块大于上限 | `431`，连接随之关闭 | `server.max-http-request-header-size`，8 KB；CoSec 的[网关模式](../operate/security#run-services-in-gateway-mode)下为 16 KB |
| 服务器无法作为 HTTP 读取的请求，例如两个不一致的 `Content-Length` 头 | `400`，连接随之关闭 | |
| 请求体大于编解码器在内存中接收的大小 | `413` | `spring.http.codecs.max-in-memory-size`，256 KB |

内部端口对前三种同样回答，按 Reactor Netty 自己的上限：4,096 和 8,192 字节，没有配置项设置它们。

超过编解码器上限的请求体，会按它的 `Content-Length` 在读取任何内容之前被拒绝：发送了 `Expect: 100-continue` 的客户端收到 `413` 而不是 `100 Continue`，于是不发送请求体。没有长度的请求体会在读取超过上限时被拒绝。当请求体还没读完就给出了回答、而连接随后关闭时（`Connection: close`、HTTP/1.0，或上面的某个拒绝），服务器先关闭自己这一端，并继续读取客户端仍在发送的内容，最多 5 秒，然后才关闭：回答会到达客户端，而不是被重置掉。

### 聚合 ID {#aggregate-ids}

一条规则决定哪些聚合 ID 可以存在，命令走的每条路径都用它：公开端口和内部端口、进程内的 `CommandClient`、对端的调用、上下文的命令主题、Saga 和定时器发出的命令、ID 生成器给出的 ID，以及测试 DSL。它拒绝这样的 ID：

| ID | 原因 |
| --- | --- |
| 长于 256 个字符，按 UTF-16 码元计（一个 emoji 记 2 个） | 网关的路径检查拒绝更长的路径段：CoSec 8 按解码后计数，至多 256。也并非每个存储的索引键都接受更长的 |
| 含控制字符（`\u0000` 到 `\u001f`、`\u007f` 到 `\u009f`） | 并非每个存储都保留它 |
| 两端有空白 | 读的人看不出来：它和去掉空白的那个是不同的聚合 |
| `.` 或 `..` | 规范化路径的代理会把它解析掉 |
| 含 `/`、`;` 或 `\` | 网关的路径检查拒绝路径中编码后的它们：CoSec 8 在服务收到请求之前就以 400 回答 `%2F`、`%3B` 和 `%5C` |
| UTF-8 编码后长于 512 字节 | 同一个检查拒绝按收到时（百分号编码后）长于 2,048 个字符的路径。按 ID 的路由给 ID 留 1,536 个字符，而客户端每个字节至多写 3 个字符（`%E8`），所以 512 字节的 ID 不论怎样编码都放得下。这相当于大多数文字的 256 个字符（`x`、`é`），中文、日文或韩文的 170 个（`购`；171 个不行），以及 128 个 emoji |

所以每个聚合都能经由每条按 ID 指明它的路由到达，在这样的网关之后也是如此。按 ID 的路由其余部分（上下文、聚合、命令和分隔符）百分号编码后至多占其余的 512 个字符，其中每一段按网关的计法至多 256 个字符：Manifest 里有更长的路由或路径段时，构建会拒绝它，并指出这条路由和要缩短的名字。一个聚合最长的按 ID 路由，通常是生命周期命令的（`…/{id}/wow.lifecycle.transfer-space`）或状态追溯的（`…/{id}/state/trace`）。

网关从 CoSec 8.0.0-beta.6 起按解码后计数路径段；更早的版本拒绝按收到时长于 256 个字符的路径段，所以在它之后，多于 28 个 `购` 这样的字符的 ID 到达不了（[更长的聚合 ID](../operate/upgrades#longer-aggregate-ids)）。路由模式按收到时读取路径、不解码的网关，只匹配写出来（编码后）至多 256 个字符的路径段。其余的都可以是 ID：ID 中间的点或空格、`%`、`?`、`#`、`+`，以及任何 Unicode 字符，由客户端在路径中编码。

| ID 的来源 | 回答 |
| --- | --- |
| 端口上的请求、进程内的 `CommandClient`、对端的调用：路径或请求体中的 ID，或者对于 ID 即其所有者的聚合，调用方的所有者 | `400 wow.command.malformed`，指明 ID 违反的规则 |
| 上下文的主题投递的命令 | 以 `wow.command.malformed` 拒绝，需要时发回拒绝回执；聚合不会被挂起 |
| Saga 派生的命令、定时器的命令 | 以 `wow.command.malformed` 拒绝：由 Saga 的 `@OnRejected` 处理器接收；定时器的拒绝与其他拒绝一样被记录 |
| 聚合的 ID 生成器 | 命令失败：给出这类 ID 的生成器是服务的缺陷 |
| 测试 DSL | 规约失败，就像服务会拒绝它的命令一样 |

在这条规则生效之前已经以这类 ID 存储的聚合不会被改动：指明它的命令会被当作格式错误拒绝，前面没有网关时也一样，查询仍然会返回它。9.x 的存储在切换之前检查：`migrate-data check` 列出 ID 被这条规则拒绝的每个实例（[迁移数据](../migrate/data#_2-check-the-map)）。

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

不带上下文的跳在命令所在的上下文中。在 Saga 的 `handled` 那一跳之后，后面的跳适用于 Saga 派生的命令，在它们各自的上下文中。一个计划至多 `wow.wait.max-hops`（16）跳。长于 4,096 个字符或多于 32 跳的 `Wow-Wait`，在读任何一跳之前就以 `wow.wait.invalid` 拒绝。节点没有其 Manifest 的上下文中的跳，会在链到达那里时检查，除非 `wow.wait.unknown-contexts: refuse` 以 `wow.wait.invalid` 拒绝它。

## 响应 {#responses}

| 状态 | 何时 |
| --- | --- |
| `200` | 等待结束时命令的结果；一个状态；一个查询的回答 |
| `202` | 等待止于 `accepted` 的命令 |
| `304` | 请求指明了其 `ETag` 的状态或能力描述 |
| `4xx`、`5xx` | 一个 RFC 9457 problem（见下文）；稍后重试可能成功时带 `Retry-After` |

**服务端推送事件。** 带 `Accept: text/event-stream` 的命令在被接受后以 `200` 回答，然后计划中列出的每一跳一到达，就为每个结果发一个事件（多条派生命令到达的同一跳各发一个），`id` 是结果的序号，事件以这一跳的阶段命名，数据是结果，直到等待结束。被接受之前的拒绝则返回一个带自身状态码的 problem：准入时的拒绝、节点已满（`429 wow.command.busy`）、无效的计划、命令主题没有接收它。响应头发出之后，拒绝、超时或失败是流的最后一个结果，失败是带错误码（`wow.storage.unavailable`、`wow.command.unknown`）的 FAILED 结果；在计划之前就结束的链（`chainEnded`）也一样。最后一个结果之后还有一个事件：`end`，数据为 `{"reason":"complete"}`，没有 `id`，表示流是完整的；它不是结果。没有它就结束的流，无论结束得多干净（节点停止、连接被切断），命令的结果都是未知的：用同一个 `Wow-Request-Id` 再次发送。读取方不根据计划推断流是否结束。立即写出一条注释 `: keep-alive`，之后在流运行期间每 15 秒写一条，使网关的空闲超时不会关闭这个流；服务端推送事件的读取方会跳过它。流式的 `list` 每条记录发一个 `row` 事件，它的 `id` 是这一行位置的游标（排序无法续读的列表没有 `id`），然后是一个 `end` 事件 `{"reason", "resumable"}`：全部记录发完为 `complete`（`"resumable":false`），`wow.query.stream.max-hold` 之后为 `hold-expired`，节点停止为 `node-stopping`，存储没能及时给下一块查询许可为 `permit-reclaimed`。可续读的流中，除 `complete` 外都可以用 `Last-Event-ID` 续读。第一行之后的失败以一个携带 problem 的 `error` 事件结束流。

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
| `retry` | 每个 problem 都有：重试能做什么，`none`、`same-request`（用同一个 `Wow-Request-Id` 重发）或 `new-request`；不是 `none` 时带 `Retry-After`，`wow.auth.unavailable` 除外，它的响应头是 CoSec 的（见下） |
| `result` | 命令产生了结果时，命令完整的结果 |
| `version` | problem 所回答的聚合版本：拒绝的版本，或低于 `minVersion` 的状态读取时的当前版本 |
| 其他任何成员 | 拒绝的结构化扩展，按名字；与上面某个成员或 `instance` 同名的扩展会被略去 |

领域错误码的状态是它的 `@ErrorCode(status)`，默认 422，可以用 `wow.http.errors.[<code>].status` 按错误码覆盖。框架的错误码见[错误码](./error-codes)。其中框架不会回答的 `wow.` 下的错误码会拒绝启动，并列出最接近的框架错误码；服务的 Manifest 和类路径上的对等方契约都没有声明的应用错误码以一条警告启动，因为它仍可能是对的（没有 `@ErrorCode` 声明的拒绝错误码、`ProblemMapper` 的错误码、对等方的错误码）。应用自己的路由也以 problem 回答它们的失败（`wow.http.global-errors.enabled`，默认打开）。

CoSec 拒绝的请求在任何路由之前回答，用 CoSec 的状态、响应头和 problem（`https://cosec.ahoo.me/problems/<type>`，CoSec 的 `code`）。设为 `wow.cosec.problems: wow` 时，回答体是 Wow 的 problem：`wow.request.malformed`、`wow.auth.unauthenticated`、`wow.auth.forbidden`、`wow.request.rate-limited` 或 `wow.auth.unavailable`，CoSec 的内部错误是 `about:blank`，CoSec 的 `type` 与 `code` 在成员 `cosec` 里（[CoSec 的拒绝](../operate/security#cosec-refusals)）。响应头仍是 CoSec 的，所以 `wow.auth.unavailable`（503）的 `retry` 是 `new-request` 而没有 `Retry-After`：CoSec 对它不设这个头。目录给这个码的等待（一秒）是 Wow 的客户端在没有这个头时等待的默认值（[错误码](./error-codes)），不是响应里会出现的值。只有这些拒绝会带出 `wow.request.rate-limited` 与 `wow.auth.unavailable`。

### 应用自己的路由 {#application-routes}

在 Wow 的路由之外，应用自己提供的路由（注解式或函数式）上，Spring WebFlux 抛出的失败这样回答：

| 请求 | 回答 |
| --- | --- |
| 没有路由匹配 | 404 `about:blank`，detail 是方法与路径：`No route GET /orders.` |
| 用了路由不提供的方法 | 405 `about:blank`，带 `Allow` |
| 请求体的媒体类型路由不接受 | 415 `about:blank`，带 `Accept` |
| 路由给出的类型都不在 `Accept` 里 | 406 `about:blank` |
| 读不出：编解码器读不出的请求体，缺少或类型不对的参数、请求头、路径变量 | 400 `wow.request.malformed`，detail 是 Spring 给的原因，并带一条 violation 指出读不出的是什么：参数用它的名字，请求体用 `body`，code 为 `wow.request.malformed` |
| `@Valid` 请求体或方法的约束不满足 | 400 `wow.request.invalid`，每条被违反的约束一条 violation，形状与命令的相同：路径（请求体的字段 `items[0].quantity`；参数名，约束在元素上时带上下标或键 `ids[1]`；请求体本身或跨参数的约束为空），约束名的线上写法（`not-blank`；错误没有约束名时为 `invalid`），以及消息 |
| 它调用的服务抛出 `ConstraintViolationException` | 什么也不说的 500：服务方法的约束是它自己的契约，不是请求的。`ProblemMapper` 可以认领它 |
| 应用自己抛出 `ResponseStatusException` | 只有它的状态，`about:blank`，4xx 时以它的原因作 detail |

除了状态本身没有更多可说的，就回答为 `about:blank`，即 RFC 9457 所说的只有状态的 problem；Wow 自己的路由也是这样。关掉 `wow.http.global-errors.enabled` 后，应用自己的路由按 Spring 的方式回答。

### 应用自己的失败 {#mapped-failures}

框架不认识的失败（例如调用另一个系统失败）回答为一个 500，不说明原因，原因写进日志。应用通过 `ProblemMapper` bean（`me.ahoo.wow.api.problem`）用自己的错误码回答自己的失败：映射器拿到失败，返回用来回答的 `WowProblemException`；失败不是它的，就返回 null。共享库也这样提供映射器，通过它自己的自动配置。

```kotlin
@Bean
@Order(0)
fun catalogProblems(): ProblemMapper = ProblemMapper { failure ->
    (failure as? WebClientResponseException)?.let {
        WowProblemException("shop.catalog.unreachable", 502, RetryAdvice.SAME_REQUEST, null, "The catalog did not answer.")
    }
}
```

- **在哪里。** 应用自己的路由（`wow.http.global-errors.enabled`）、服务路由的处理函数、状态读取、命令路由和查询，包括流式列表的 `error` 事件。内部端口和运维路由不用它；命令的失败也绝不用它：决策函数抛出的异常是命令的结果 `wow.command.failed`（[拒绝与错误码](../guide/rejections)）。saga 的同步调用和命令客户端也不用它，它们收到的是另一个服务按自己的映射给出的回答。
- **映射器看到什么。** 只看到本来会回答为不说明原因的 500 的失败（查询的 `wow.query.failed`）：绝不会看到 `WowProblemException`、命令的拒绝、认证或准入的拒绝、不可用的存储，或超过编解码器上限的请求体，作为另一个失败的 cause 时也看不到。包着它们的应用自己的异常仍可以被映射。
- **顺序。** 按 bean 的顺序（`@Order`）询问映射器，先问失败本身，再依次问它的每个 cause；第一个给出 problem 的映射器作答。抛出异常的映射器被跳过，它的异常写进日志。
- **检查。** 映射出的 problem 如果用了框架保留的错误码（`wow.*`），状态不是 4xx 或 5xx 或者是 401（只有认证能回答 401），或者带着命令的结果，就不采用：失败回答为不说明原因的 500，映射器和错误码记一次日志。映射出的错误码的状态仍由 `wow.http.errors.[<code>].status` 决定。
- **目录。** 在上下文里用 `@ErrorCode` 声明映射出的错误码，或写进服务路由的 `errors`，让客户端和 Manifest 知道它。哪里都没列出的错误码照样作答，并警告一次。
- **日志。** 映射出的 5xx 连同失败记为警告，映射出的 4xx 记为 debug。

problem 的消息就是它的 `detail`，调用方会读到：不要在里面写调用方不该看到的东西。映射器可以把请求违反的字段列为 problem 的 `violations`（`WowProblemException(…, violations = listOf(Violation.of(path, code, message)))`），形状与命令的相同。
