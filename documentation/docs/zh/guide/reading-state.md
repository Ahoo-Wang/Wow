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
| `GET …/state?version=n` | 版本 `n` 的状态 |
| `GET …/state?at=<instant>` | 在某个 ISO-8601 时刻或之前最后一次提交之后的状态（`+` 要写成 `%2B`） |
| `GET …/state?minVersion=n` | 版本 `n` 或更新的最新状态：写后即可查 |
| `GET …/state/trace?from=&to=&limit=` | 从版本 `from`（1）到 `to`（最后一个）的提交，至多 `limit` 个，每个带着它的事件和它之后的状态 |

状态返回它的聚合的 `ref` 和 `version`、它的 `scope`（所有者和空间）、它的 `deleted` 标记、它的 `tags`，以及 `state` 本身。轨迹返回 `{"ref", "commits"}`：每次提交的版本、提交时间、命令 ID 和请求 ID、命令类型、事件，以及提交之后的聚合。轨迹的下一页从最后一次提交的版本之后开始。

最新状态就是快照；在聚合的第一个快照之前，则是重放它的日志。已删除聚合的状态与其他状态一样可读，只是设置了 `deleted`。

## 谁能看到状态 {#who-sees-a-state}

状态按它的调用方所看到的样子读取：

- 租户是调用方的租户，或者对被允许跨作用域行事的调用方，是 `Wow-Scope` 指明的租户。
- 不在调用方所有者或空间之内的状态，或者快照查询的策略对调用方隐藏的状态，都返回 `404 wow.aggregate.not-found`，与不存在的状态完全一样。只有审计知道原因。对于属于租户的聚合，不检查调用方的所有者。
- 每个状态，以及轨迹中的每个事件，都像快照查询那样脱敏。

## 用 ETag 缓存 {#cache-with-etags}

状态返回 `ETag: "<version>-<digest>"`，digest 是调用方所读到的响应体的哈希。带着指明这个标签的 `If-None-Match` 的请求，返回 `304 Not Modified`。只要调用方读到的内容变了，标签就会变，即使版本相同：例如快照重建、升级或脱敏规则改变之后。

## 写后即可查 {#read-your-own-write}

发出命令后想看到它效果的客户端，用命令结果给出的版本来读取：`GET …/state?minVersion=7`。节点在状态达到这个版本后返回最新状态，必要时用日志把落后的快照追上来。在 `wow.query.state.min-version-wait` 之内没有提交到这个版本时，返回 `409 wow.query.min-version-not-reached`，带上聚合当前的 `version` 和 `Retry-After`。

等待快照的命令（即默认的等待）在快照包含它的提交之后才返回，所以紧接着的读取就能看到它（[等待结果](../concepts/waits)）。

## 重放有上限 {#replaying-is-bounded}

读取较旧的版本、某个时刻或轨迹，会重放日志。这样的读取在聚合日志存储的查询舱壁下运行（满了时返回 `429 wow.query.busy`），并且重放的提交数有上限 `wow.query.state.max-replay`：更远的状态返回 `400 wow.query.invalid`，violation 为 `REPLAY_TOO_LONG`。轨迹默认每页返回 100 次提交，最多 1,000 次。在重放任何东西之前，先对最新状态检查调用方。

## 在 Kotlin 中 {#in-kotlin}

宿主提供一个 `QueryApi` Bean。用 `of(caller)` 选择调用方，然后按 ID 读取状态：

<!-- sample: examples/eshop/e2e/src/integrationTest/kotlin/me/ahoo/wow/example/eshop/e2e/EshopClientTest.kt#read -->

```kotlin
val queries = basket.bean<QueryApi>().of(alice).snapshots(EshopOrderingContract.Order.TYPE)
val state = queries.state("client-alice-1")!!
state.scope.ownerId.assert().isEqualTo("alice")
(state.state as ObjectNode)["items"].size().assert().isEqualTo(1)
// The ordering on the memory backend serves its snapshot queries too: alice lists her one order.
queries.list(ListQuery.of(MatchAllFilter)).toList().size.assert().isEqualTo(1)
// Bob, another buyer, sees none of it.
val bob = Caller.of(scope = Scope("bob"), subject = "bob")
basket.bean<QueryApi>().of(bob).snapshots(EshopOrderingContract.Order.TYPE).state("client-alice-1")
    .assert().isNull()
```

- `snapshots(type)` 读取另一个服务的聚合类型，状态为 JSON；`snapshots<ProductState>()` 读取本服务的类型，状态解码为这个类。
- 对调用方不能看到的状态，`state(id)` 返回 `null`，与不存在的状态一样。`state(id, minVersion)` 用于写后即可查，版本没有及时达到时抛出 `MinVersionNotReachedException`。
- `trusted()` 以受信任的代码读取：不受任何租户、所有者或空间限制。把它用于系统任务，绝不要用于有调用方的请求。
- 列在 `wow.peers` 下的对端，用同样的调用经 HTTP 读取（[调用其他服务](./calling-services#configure-a-peer-over-http)）。
- 在 Saga、投影或反应器中，`context.queries` 读取正在处理的这次提交所在的整个租户。

同一个 `QueryApi` 也执行快照查询：见[查询快照](./queries)。
