---
title: 读取状态
description: 经 HTTP 或在 Kotlin 中读取聚合的状态：某个版本或某个时刻的状态、追溯它的历史、用 ETag 缓存，以及写后即可查。
outline: deep
---

# 读取状态

每个聚合的状态都可以按 ID 读取，不需要任何配置：最新状态、任意较早版本或时刻的状态，以及导致它的提交历史。本页经 HTTP 和在 Kotlin 中读取状态。要按字段查找聚合，则查询快照：见[查询快照](./queries)。

## 经 HTTP {#over-http}

| 请求 | 返回 |
| --- | --- |
| `GET /<context>/<aggregate>/{id}/state` | 最新状态 |
| `GET /<context>/<aggregate>/state` | ID 即所有者 ID 的聚合中，调用方自己那个的最新状态 |
| `GET …/state?version=n` | 版本 `n` 的状态 |
| `GET …/state?at=<instant>` | 在某个 ISO-8601 时刻或之前最后一次提交之后的状态（`+` 要写成 `%2B`） |
| `GET …/state?minVersion=n` | 版本 `n` 或更新的最新状态：写后即可查 |
| `GET …/state/trace?from=&to=&limit=` | 从版本 `from`（1）到 `to`（最后一个）的提交，至多 `limit` 个，每个带着它的事件和它之后的状态 |

ID 即所有者 ID 的聚合的每种读取，包括 `?version=`、`?at=`、`?minVersion=` 和 `/state/trace`，也都可以不带 ID：它的 ID 是调用方读取所在作用域的所有者。没有所有者的调用方要指明 ID；不指明时得到 `400 wow.query.malformed`，未认证的调用方得到 `401 wow.auth.unauthenticated`（[每个所有者一个聚合](./ownership#one-aggregate-per-owner)）。

状态返回它的聚合的 `ref` 和 `version`、它的 `scope`（所有者和空间）、它的 `deleted` 标记、它的 `tags`，以及 `state` 本身。轨迹返回 `{"ref", "commits"}`：每次提交的版本、提交时间、命令 ID 和请求 ID、命令类型、事件，以及提交之后的聚合。轨迹的下一页从最后一次提交的版本之后开始。

最新状态就是快照；在聚合的第一个快照之前，则是重放它的日志。已删除聚合的状态与其他状态一样可读，只是设置了 `deleted`。

## 谁能看到状态 {#who-sees-a-state}

状态按它的调用方所看到的样子读取：

- 租户是调用方的租户，或者对被允许跨作用域行事的调用方，是 `Wow-Scope` 指明的租户。
- 不在调用方作用域之内的状态，或者快照查询的策略对调用方隐藏的状态（开启时包括基于属性的访问），都返回 `404 wow.aggregate.not-found`，与不存在的状态完全一样。只有审计知道原因。对 `Ownership.OWNER` 的聚合，作用域是调用方的所有者和空间；对属于租户的聚合，只是它的空间（[谁能读取它](./ownership#who-reads-it)）。直接拒绝调用方的策略，例如角色检查，改为返回 `403 wow.auth.forbidden`。
- 每个状态，以及轨迹中的每个事件，都像快照查询那样脱敏。

## 用 ETag 缓存 {#cache-with-etags}

状态返回 `ETag: "<version>-<digest>"`，digest 是调用方所读到的响应体的哈希。带着指明这个标签的 `If-None-Match` 的请求，返回 `304 Not Modified`。只要调用方读到的内容变了，标签就会变，即使版本相同：例如快照重建、升级或脱敏规则改变之后。

## 写后即可查 {#read-your-own-write}

发出命令后想看到它效果的客户端，用命令结果给出的版本来读取：`GET …/state?minVersion=7`。节点在状态达到这个版本后返回最新状态，必要时用日志把落后的快照追上来。在 `wow.query.state.min-version-wait` 之内没有提交到这个版本时，返回 `409 wow.query.min-version-not-reached`，带上聚合当前的 `version` 和 `Retry-After`。

等待快照的命令（即默认的等待）在快照包含它的提交之后才返回，所以紧接着的读取就能看到它（[等待结果](../concepts/waits)）。

## 重放有上限 {#replaying-is-bounded}

只有最新的状态保存在快照里。读取较旧的版本、某个时刻或轨迹，要从日志的第一个版本开始重建它回答的状态，因为快照已在它之后。这样的读取在聚合日志存储的查询舱壁下运行（满了时返回 `429 wow.query.busy`），并且最多重放 `wow.query.state.max-replay` 次提交，默认 10,000：更远的状态返回 `400 wow.query.invalid`，violation 为 `replay-too-long`。轨迹默认每页返回 100 次提交，最多 1,000 次。在重放任何东西之前，先对最新状态检查调用方。

- **为什么有上限。** 否则一次对长寿聚合旧状态的读取，就会在提供它的节点上重放它的全部历史。
- **何时调高。** 需要读取长历史中很早状态的部署（例如查看某个聚合第一万个版本的审计视图），调高 `wow.query.state.max-replay`；每次这样的读取最多付出那么多次提交的代价。
- **它不限制什么。** 事件：一个聚合的事件历史无论多长，都经事件查询分页读取（[查询事件流](./event-queries)）。受限的只有较旧版本或某个时刻的状态，以及带有每次提交之后状态的轨迹。

## 在 Kotlin 中 {#in-kotlin}

宿主提供一个 `QueryApi` Bean。用 `of(caller)` 选择调用方，然后按 ID 读取状态：

<!-- sample: examples/eshop/e2e/src/integrationTest/kotlin/me/ahoo/wow/example/eshop/e2e/EshopClientTest.kt#read -->

```kotlin
// The ordering api's generated view of the order's state: the read finds the aggregate from it.
val orders = basket.bean<QueryApi>().of(alice).snapshots<OrderStateView>()
val state = orders.state("client-alice-1")!!
state.scope.ownerId.assert().isEqualTo("alice")
state.state.items.single().productId.assert().isEqualTo("mug")
state.state.totalAmount.assert().isEqualByComparingTo("17.00")
// The ordering on the memory backend serves its snapshot queries too: alice lists her one order.
orders.list(ListQuery.of(MatchAllFilter)).toList().size.assert().isEqualTo(1)
// A projection leaves out fields the view requires: it reads the rows as JSON.
val statuses = listQuery { projection { include(EshopOrderingContract.Order.STATE__STATUS) } }
val projected = orders.dynamic<ObjectNode>().list(statuses).toList().single()["state"]
projected.propertyNames().asSequence().toList().assert().containsExactly("status")
// Bob, another buyer, sees none of it.
val bob = Caller.of(scope = Scope("bob"), subject = "bob")
basket.bean<QueryApi>().of(bob).snapshots<OrderStateView>().state("client-alice-1").assert().isNull()
```

- `snapshots<OrderStateView>()` 读取另一个上下文的聚合，状态解码为它 api 生成的视图（`<State>View`，状态的字段；自由结构的内容为 `ObjectNode`）；`snapshots<ProductState>()` 读取本服务的类型，状态解码为这个类。`snapshots(type)` 按名字读取一个类型，状态为 JSON。
- 类型化的读取读整个状态。投影字段的查询用 `dynamic<ObjectNode>()`（或 `Map`，或投影字段组成的类）读取它的行：投影会去掉视图要求的字段。
- 对调用方不能看到的状态，`state(id)` 返回 `null`，与不存在的状态一样。`state(id, minVersion)` 用于写后即可查，版本没有及时达到时抛出 `MinVersionNotReachedException`。
- `trusted()` 以受信任的代码读取：不受任何租户、所有者或空间限制，读取调用方的策略（例如角色检查）也不适用。把它用于系统任务，绝不要用于有调用方的请求。
- 列在 `wow.peers` 下的对端，用同样的调用经 HTTP 读取（[调用其他服务](./calling-services#configure-a-peer-over-http)）。
- 在 Saga、投影或反应器中，`context.queries` 读取正在处理的这次提交所在的整个租户：不受所有者、空间、属性或角色限制。

同一个 `QueryApi` 也执行快照查询：见[查询快照](./queries)。
