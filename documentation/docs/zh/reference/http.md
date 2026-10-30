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
| `POST /<context>/<aggregate>/<command>` | 一条创建命令，发往一个由服务端分配 ID 的新聚合 |
| `DELETE /<context>/<aggregate>/{id}` | `DeleteAggregate` |
| `POST /<context>/<aggregate>/{id}/wow.lifecycle.restore-aggregate` | `RestoreAggregate` |
| `POST /<context>/<aggregate>/{id}/wow.lifecycle.transfer-owner` | `TransferOwner`，请求体为 `{"ownerId": "…"}` |
| `POST /<context>/<aggregate>/{id}/wow.lifecycle.transfer-space` | `TransferSpace`，请求体为 `{"spaceId": "…"}` |
| `POST /<context>/<aggregate>/{id}/wow.lifecycle.apply-tags` | `ApplyTags`，请求体为 `{"tags": {"name": ["value"]}}`，替换已应用的标签 |
| `POST /<context>/<aggregate>/wow.snapshot.rebuild` | 重建 `{"ids": [...]}` 的快照 |
| `POST /<context>/wow.schedule/{job}/<command>` | 任务命令：`enable`、`reschedule`、`disable` |

只有聚合在 `@AggregateRoot(lifecycle)` 中列出的生命周期命令才有路由。命令的请求体是它的 JSON；空的命令体是 `{}`。快照重建和任务命令是 Wow 内置聚合的系统命令：没有命令授权器的服务在这个端口上拒绝它们（403 `wow.auth.forbidden`），默认从不放行（[授权命令](../operate/security#authorize-commands)）。

**读取**

| 方法和路径 | 返回 |
| --- | --- |
| `GET /<context>/<aggregate>/{id}/state` | 最新状态；`?version=n`、`?at=<instant>` 或 `?minVersion=n` |
| `GET /<context>/<aggregate>/{id}/state/trace` | 提交，`?from=`、`?to=`、`?limit=`（100，最多 1,000） |
| `POST /<context>/<aggregate>/snapshot/query` | 快照查询 |
| `POST /<context>/<aggregate>/events/query` | 事件查询 |
| 对任一查询路径发 `GET` | 模型的能力描述，版本作为 `ETag` |
| `POST /wow/dataset/<context>/<dataset>/query` 及它的 `GET` | 数据集查询，及它的能力描述 |
| `GET /wow/dataset` | 调用方能看到的数据集 |

**服务**

| 方法和路径 | 返回 |
| --- | --- |
| `GET /wow/manifest` | 服务合并后的 Manifest（`wow.http.manifest.enabled`） |
| `GET /wow/openapi` | 这些路由的 OpenAPI 3.1 文档（`wow.http.openapi.enabled`） |

某个上下文路径下的其他任何请求，都是一个 `404` problem。

## 请求头 {#request-headers}

| 请求头 | 用于 | 含义 |
| --- | --- | --- |
| `Wow-Request-Id` | 命令 | 调用方给请求的 ID：用同一个 ID 重试会被当作已完成来回答（`duplicate`），绝不会决定两次；同一个 ID 用于另一条命令时是 `wow.command.request-id-reused` |
| `Wow-Wait` | 命令 | 回答之前要等到哪里（见下文）；默认 `snapshot` |
| `Wow-Wait-Timeout` | 命令 | 等待多久：`500ms`、`10s`、`1m` 或 ISO-8601；默认 `wow.wait.default-timeout`（10 秒），最长 `wow.wait.max-timeout`（1 分钟） |
| `If-Match` | 命令 | 聚合必须所处的版本；否则 `412 wow.aggregate.version-mismatch`（[实体标签](./protocols#entity-tags)） |
| `If-None-Match` | 状态读取 | 状态上一次的 `ETag`；仍然有效时返回 `304`（[实体标签](./protocols#entity-tags)） |
| `Accept: text/event-stream` | 命令、`list` 查询 | 服务端推送事件（见下文） |
| `Last-Event-ID` | 流式的 `list` 查询 | 从哪个位置之后续读 |
| `Wow-Scope` | 每个路由 | 对被允许跨作用域行事的调用方：要在其中行事的租户、所有者和空间，一个 RFC 8941 字典，`tenant="t1", owner="u1", space="s1"`。在查询路由上，打开 `wow.query.allow-cross-tenant` 时，`tenant=*` 或 `tenant=("t1" "t2")` 可以跨租户读取 |
| `Wow-Space` | 每个路由 | 在调用方自己的空间中，要在其中行事的那个 |

带有 `Wow-Call`（服务同步调用的请求头）的请求，在公开端口上会以 `403 wow.auth.forbidden` 拒绝：调用只在内部端口上接收。`wow.http.request-facts` 列出的请求头会复制进命令，并写进它的事件流。

## Wow-Wait {#wow-wait}

```text
plan := hop (">" hop)*
hop  := "accepted" ["(" context ")"]
      | "committed" ["(" context ")"]
      | "snapshot" ["(" context ")"]
      | "handled(" [context "/"] subscription ")"
```

| 跳 | 何时到达 |
| --- | --- |
| `accepted` | 命令通过了准入；只有经 Kafka 路由的命令才是持久的 |
| `committed` | 它的事件已追加，或者它没有追加就有了结论 |
| `snapshot` | 快照包含这次提交 |
| `handled(sub)` | 订阅 `sub`（`processor.function`）处理了这次提交 |

不带上下文的跳在命令所在的上下文中。在 Saga 的 `handled` 那一跳之后，后面的跳适用于 Saga 派生的命令，在它们各自的上下文中。一个计划至多 `wow.wait.max-hops`（16）跳。节点没有其 Manifest 的上下文中的跳，会在链到达那里时检查，除非 `wow.wait.unknown-contexts: reject` 以 `wow.wait.invalid` 拒绝它。

## 响应 {#responses}

| 状态 | 何时 |
| --- | --- |
| `200` | 等待结束时命令的结果；一个状态；一个查询的回答 |
| `202` | 等待止于 `accepted` 的命令 |
| `304` | 请求指明了其 `ETag` 的状态 |
| `4xx`、`5xx` | 一个 RFC 9457 problem（见下文）；稍后重试可能成功时带 `Retry-After` |

**服务端推送事件。** 带 `Accept: text/event-stream` 的命令在准入后以 `200` 回答，然后每到达一跳发一个事件，事件以这一跳的阶段命名，数据是结果，直到等待结束；准入时被拒绝则返回一个 problem。流式的 `list` 每条记录发一个 `row` 事件，它的 `id` 是这一行的位置，然后是一个 `end` 事件：`{"reason":"complete"}`，或者在 `wow.query.stream.max-hold` 之后的 `{"reason":"hold-expired","resumable":true}`。

## Problem {#problems}

每个错误都是 `application/problem+json`：

| 成员 | 包含 |
| --- | --- |
| `type` | `urn:wow:error:<code>`；只属于 HTTP 本身的 problem（例如路由不存在）为 `about:blank` |
| `code` | 错误码，框架错误码（`wow.*`）或领域错误码；`about:blank` 的 problem 没有它 |
| `status` | HTTP 状态 |
| `title` | 给人看的错误码简短摘要，每次出现都相同：框架错误码的标题（[错误码](./error-codes)），领域错误码的 `@ErrorCode(description)` |
| `detail` | 给人看的、这次请求发生了什么：拒绝或失败的消息 |
| `violations` | 字段级的违规：`path`、`code`、`message`。命令约束的 `code` 是约束名的小写 kebab 形式：`NotBlank` 是 `not-blank`。 |
| `result` | 命令产生了结果时，命令完整的结果 |
| 其他任何成员 | 拒绝的结构化扩展，按名字 |

领域错误码的状态是它的 `@ErrorCode(status)`，默认 422，可以用 `wow.http.errors.[<code>].status` 按错误码覆盖。框架的错误码见[错误码](./error-codes)。应用自己的路由也以 problem 回答它们的失败（`wow.http.global-errors.enabled`，默认打开）。
