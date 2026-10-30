---
title: 线上协议
description: Wow 跨越进程、存储和版本边界写出的结构（事件流、状态事件、Kafka 信封、命令结果、等待信号和集群消息），以及它们的演进规则。
outline: deep
---

# 线上协议

本页列出 Wow 写入存储、写入 Kafka 以及在节点之间传递的结构：每个字段的含义，以及这些结构在版本之间怎样变化。当你从一个不是 Wow 服务的系统消费发布的事件流、自己解码命令结果，或者需要知道一次升级可能改变什么时，读这一页。

JSON 示例由 Wow 自己写出：一个测试对每个示例编码，线上形式一变它就失败。

## 原则 {#principles}

- **事实、视图和传输段分开保存。** 事实，即一次提交的事件流，只写一次，永不修改。视图，例如状态或快照，从事实派生，可以重建。传输段，例如一个未结束的等待或追踪上下文，只为投递服务：领域代码和查询永远看不到它，迁移和重放会丢弃它。
- **用名字，不用类。** 命令、事件和错误码由模型的稳定名字标识。类可以重命名或移动，不影响历史。
- **线上用 JSON。** 载荷是 JSON，它们的 schema 在 Manifest 里。数字保持精确的值和小数位：`2.50` 仍是 `2.50`。
- **每个结构都有格式版本。** 见[格式版本](#format-versions)。

## 名字 {#names}

| 名字 | 形式 | 例子 |
| --- | --- | --- |
| 聚合类型 | `context.aggregate` | `bank.account` |
| 聚合引用（文本形式） | `context.aggregate/tenant/id` | `bank.account/t1/alice` |
| 命令类型 | `context.aggregate.Command` | `bank.account.Deposit` |
| 事件类型 | `context.aggregate.Event` | `bank.account.Deposited` |
| 错误码 | 框架的是 `wow.*`；聚合自己的以其类型为前缀 | `wow.wait.timeout`、`bank.account.insufficient-balance` |
| 提交 ID | `<aggregate reference>@<version>` | `bank.account/t1/alice@2` |

命令类型默认是命令类的简单名，事件类型默认是事件类的简单名；`@Name` 可以覆盖两者。

## 常用值 {#common-values}

这些值出现在大多数结构里。

| 值 | JSON | 说明 |
| --- | --- | --- |
| 聚合引用 | `{"type": {"context", "name"}, "tenantId", "id"}` | 把 ID 留给服务端生成的创建命令，在服务端选定 ID 之前 `id` 为 null。`(none)` 永远不是 ID：写着它的引用会被拒绝。 |
| 作用域 | `{"ownerId", "spaceId"}` | 两者都可以为 null。在命令上是调用方的作用域；在提交或快照上是提交后聚合的作用域。 |
| 因果 | `{"correlationId", "causationId"}` | `correlationId` 是根命令的 ID；`causationId` 是直接原因，对 Saga 的命令是它处理的那次提交。调用方直接发出的命令没有因果，它自己就是根。 |
| 操作人 | `{"id", "tenantId", "crossScope"}` | 提交以谁的名义记录：调用方的主体标识和它自己的租户；它在另一个租户或作用域中行事时 `crossScope` 为 true。 |
| 跳 | `{"stage", "context", "subscription"}` | `stage` 是 `accepted`、`committed` 或 `handled`；只有 `handled` 才有 `subscription`；`snapshot` 指快照订阅。 |

时间都是纪元毫秒。

## 实体标签 {#entity-tags}

状态读取回答 `ETag: "<版本>-<摘要>"`：聚合的版本，接着是响应体 SHA-256 的前 64 位，写成 16 个十六进制数字。凡是改变调用方所读内容的变化都会改变标签，而不只是版本。

条件头是 `*`，或逗号分隔的实体标签列表，每个标签是 `"<opaque>"` 或弱标签 `W/"<opaque>"`（RFC 9110 §8.8.3）。其他写法返回 400 `wow.request.malformed`。

- **命令上的 `If-Match`** 用强比较（RFC 9110 §13.1.1）。强标签指一个版本：或者是状态读取给出的形式（`"3-0123456789abcdef"`），或者只写版本（`"3"`）。聚合处于该版本时命令才执行，否则返回 412 `wow.aggregate.version-mismatch`。一条命令只带一个期望版本：各标签指同一个版本时就是这个版本，指两个及以上不同版本时返回 400 `wow.request.malformed`。弱标签或不指版本的标签永远不匹配，所以只含这类标签的列表返回 412。`*` 用在总是创建聚合的命令上返回 412；用在其他命令上时，与 RFC 9110 不同，`*` 不增加条件，聚合不存在仍返回 404 `wow.aggregate.not-found`。
- **状态读取上的 `If-None-Match`** 用弱比较（§13.1.2）。忽略 `W/`，`*` 匹配任何状态，标签匹配时回答 304。

## 事件流 {#event-stream}

事件流就是事实：一条命令产生、由一次追加提交的事件。它是日志的一个条目，存储后端把它的字段排成列或文档字段。

| 字段 | 含义 |
| --- | --- |
| `ref` | 聚合。 |
| `version` | 这次提交之后聚合的版本：第一次提交为 1，之后每次加一。 |
| `commandId`、`requestId` | 产生它的命令，以及该命令的幂等键。 |
| `commandType` | 那条命令的类型名。请求 ID 用于另一种命令类型时被拒绝；用于同一类型时视为重试。 |
| `commitTime` | 提交的时间。 |
| `events` | 每个事件的 `name`、`revision` 和 `payload`，按顺序。设置或取消的定时也在其中，即系统事件 `wow.ScheduleSet` 和 `wow.ScheduleCancelled`。 |
| `scope` | 这次提交之后聚合的所有者和空间。 |
| `tags` | 这次提交之后聚合的标签。 |
| `causation` | 命令所属的链，如果它是 Saga 发出的。 |
| `headers` | 准入记录的请求事实，例如 `app_id`。 |
| `operator` | 提交以谁的名义记录；匿名调用方为 null。 |

在事实之外，日志条目还有一个**位置**，即它在分区内的序号，并且可能带有**传输段**：未结束的等待的等待上下文、要确认的预留、追踪上下文。

传输段无论到哪里都只有一种形式，即信封与集群通道嵌入的 JSON 对象 `{wait, reservations, trace, releases}`；没有等待、列表为空、追踪为空时都省略。日志把它当作节点写下的字节原样保存、原样返回，从不解读，所以新版本给它加字段时，任何后端都不用改。

## 状态事件 {#state-event}

状态事件是一次提交，连同提交之后聚合的状态。处理函数在进程内收到它，发布的事件流在 Kafka 上承载它。它的字段是事件流的字段，再加上：

| 字段 | 含义 |
| --- | --- |
| `state` | `{version, schemaFingerprint, state}`：这个版本提交时序列化一次的状态，以及它的 schema 指纹。聚合发布时不带状态则为 null。 |
| `deleted` | 这次提交之后聚合是否已删除。 |
| `imported` | `migrate-data` 从 9.x 导入、位于上下文切换点及之前的提交为 `true`，其他提交不写这个字段。另一个上下文的 Saga 或反应器会跳过它，因为 9.x 已经对它采取过行动。 |

事件在发布前升级到当前修订号。

## Kafka 信封 {#kafka-envelope}

Wow 写入 Kafka 的每条消息都是一个信封。

| 部分 | 内容 |
| --- | --- |
| 键 | 聚合引用的文本形式，所以一个聚合的消息留在同一个 Kafka 分区里，并保持顺序。 |
| 头 | `wow-format`、`wow-kind` 和 `wow-type`：信封的格式、种类和类型，不解析值就能读到。 |
| 值 | `{format, kind, id, type, payload, transport}`。 |

| `kind` | `id` | `type` | `payload` | 主题 |
| --- | --- | --- | --- | --- |
| `state-event` | 提交 ID `ref@version` | 聚合类型 | 一个[状态事件](#state-event) | `wow.<context>.<aggregate>.state`，除非聚合另行指定 |
| `command` | 命令 ID | 命令类型 | 一条[命令](#commands-on-a-topic) | `wow.<context>.commands` |
| `command-receipt` | 命令 ID | 命令类型 | 一张[回执](#receipts) | 发送方上下文的 `wow.<context>.receipts` |

消费者按 `id` 去重。`transport` 为 null，或者只包含 `wait` 上下文和 `trace` 传播字段。

一个发布的状态事件：

<!-- sample: core/runtime/src/test/resources/protocols/state-event.json -->

```json
{
  "format": 1,
  "kind": "state-event",
  "id": "bank.account/t1/alice@2",
  "type": "bank.account",
  "payload": {
    "ref": {
      "type": {
        "context": "bank",
        "name": "account"
      },
      "tenantId": "t1",
      "id": "alice"
    },
    "version": 2,
    "commandId": "c-2",
    "requestId": "r-2",
    "commandType": "bank.account.Deposit",
    "commitTime": 1760950800000,
    "events": [
      {
        "name": "bank.account.Deposited",
        "revision": 1,
        "payload": {
          "amount": 100
        }
      }
    ],
    "state": {
      "version": 2,
      "schemaFingerprint": "43bfa906ccc015f9dc85b5ad142a24dcb99668d69f98ebd9d1ae73471c97be4d",
      "state": {
        "owner": "Alice",
        "balance": 100
      }
    },
    "scope": {
      "ownerId": "u-42",
      "spaceId": null
    },
    "deleted": false,
    "tags": {},
    "causation": null,
    "headers": {
      "app_id": "teller-app"
    },
    "operator": {
      "id": "u-42",
      "tenantId": "t1",
      "crossScope": false
    }
  },
  "transport": null
}
```

事件载荷和状态是内嵌的 JSON 对象，而不是转义的字符串，所以消费者可以按路径读取值。

## 主题上的命令 {#commands-on-a-topic}

发往上下文命令主题的命令，和在服务节点之间转发的命令，线上形式相同：

| 字段 | 含义 |
| --- | --- |
| `type` | 命令类型；接收方按它、依据自己的 Manifest 解码 `body`。 |
| `id`、`requestId` | 命令的 ID 和幂等键。Saga 的命令的请求 ID 由它处理的那次提交推出。 |
| `ref` | 它寻址的聚合。 |
| `body` | 命令的业务数据。 |
| `expectedVersion` | 发送方期望聚合所处的版本，或 null。 |
| `scope`、`operator`、`headers`、`causation` | 准入为根命令确定的值；Saga 的命令继承它们。 |
| `replyTo` | 只出现在 Saga 发往另一个上下文的命令上：`{context, subscription, ref, version, committed}`，即要为之发回执的那次 Saga 提交；`committed` 要求成功时也发回执。 |

主题的分区就是目标上下文的日志分配给 `ref` 的那个分区，所以主题的分区数与那份日志相同。等待计划不随命令传递；未结束的等待的等待上下文放在 `transport` 里：

<!-- sample: core/runtime/src/test/resources/protocols/command.json -->

```json
{
  "format": 1,
  "kind": "command",
  "id": "c-3",
  "type": "bank.account.Withdraw",
  "payload": {
    "type": "bank.account.Withdraw",
    "id": "c-3",
    "requestId": "tr-9@1#0",
    "ref": {
      "type": {
        "context": "bank",
        "name": "account"
      },
      "tenantId": "t1",
      "id": "alice"
    },
    "body": {
      "amount": 30
    },
    "expectedVersion": null,
    "scope": {
      "ownerId": null,
      "spaceId": null
    },
    "headers": {},
    "causation": {
      "correlationId": "c-1",
      "causationId": "bank.transfer/t1/tr-9@1"
    },
    "operator": {
      "id": "u-42",
      "tenantId": "t1",
      "crossScope": false
    },
    "replyTo": {
      "context": "bank",
      "subscription": "TransferSaga.onRequested",
      "ref": {
        "type": {
          "context": "bank",
          "name": "transfer"
        },
        "tenantId": "t1",
        "id": "tr-9"
      },
      "version": 1,
      "committed": false
    }
  },
  "transport": {
    "wait": {
      "waitId": "w-7",
      "origin": {
        "node": "bank-1",
        "address": "10.0.0.5:9090",
        "service": "bank-service"
      },
      "remaining": [
        {
          "stage": "handled",
          "context": "bank",
          "subscription": "wow.snapshot"
        }
      ],
      "deadline": 1760950810000
    }
  }
}
```

等待上下文写明命令来自的节点（`origin`，带它的内部地址）、还要到达的跳，以及绝对截止时间。

## 回执 {#receipts}

跨上下文命令的目标在确认这条命令之前，把回执写进发送方上下文的回执主题。回执以 Saga 处理的那次提交的引用为键，所以一个 Saga 聚合的回执保持顺序；回执的 `id` 就是命令的 ID。

| 回执 | 载荷 |
| --- | --- |
| 拒绝 | `{commandId, commandType, ref, error, causation, executionId, operator, scope, headers, version}`，不含命令体；`version` 是拒绝的事件到达的版本，否则是决策所见的版本，创建命令或在任何决策前被拒绝的命令为 `null` |
| 成功（应要求时） | `{commandId, commandType, ref, version, result, causation, executionId, operator, scope, headers}`，其中 `result` 是 `COMMITTED` 那一跳的[命令结果](#command-result)，不含状态 |

`executionId` 是 `{subscription, ref, version}`：命令所来自的那次 Saga 处理函数调用。`headers` 只包含会传播的请求事实。

<!-- sample: core/runtime/src/test/resources/protocols/command-receipt.json -->

```json
{
  "format": 1,
  "kind": "command-receipt",
  "id": "c-3",
  "type": "bank.account.Withdraw",
  "payload": {
    "commandId": "c-3",
    "commandType": "bank.account.Withdraw",
    "ref": {
      "type": {
        "context": "bank",
        "name": "account"
      },
      "tenantId": "t1",
      "id": "alice"
    },
    "error": {
      "code": "bank.account.insufficient-balance",
      "message": "The balance is 20.",
      "violations": [],
      "extensions": {}
    },
    "causation": {
      "correlationId": "c-1",
      "causationId": "bank.transfer/t1/tr-9@1"
    },
    "executionId": {
      "subscription": "TransferSaga.onRequested",
      "ref": {
        "type": {
          "context": "bank",
          "name": "transfer"
        },
        "tenantId": "t1",
        "id": "tr-9"
      },
      "version": 1
    },
    "operator": {
      "id": "u-42",
      "tenantId": "t1",
      "crossScope": false
    },
    "scope": {
      "ownerId": "u-42",
      "spaceId": null
    },
    "headers": {},
    "version": 4
  },
  "transport": null
}
```

## 命令结果 {#command-result}

在内核之外，命令的结果到处都用同一个结构报告：HTTP 响应、服务端推送事件流的每个事件、等待信号、回执和客户端。

| 字段 | 含义 |
| --- | --- |
| `format` | 结构的格式版本。 |
| `commandId`、`requestId`、`correlationId` | 命令、它的幂等键，以及它所在链的根。 |
| `ref` | 聚合。 |
| `hop` | 这个结果报告的是等待计划的哪一跳。 |
| `function` | 由谁产生：`{kind, context, processor, name}`。`kind` 是 `command`、`project`、`react`、`saga`、`snapshot`、`reservation` 或 `timers`；对命令，`processor` 和 `name` 是聚合和命令；对订阅，是它的处理器和函数。 |
| `status` | `succeeded`、`rejected` 或 `failed`。 |
| `version` | 提交之后聚合的版本；什么都没追加时不变。 |
| `duplicate` | 这个请求已经提交过，没有再次决定。 |
| `error` | 用于 `rejected` 和 `failed`：`{code, message, violations, extensions}`，每个 violation 是 `{path, code, message}`。 |
| `results` | 决定函数返回的额外结果；只在第一次提交时出现，重复时绝不出现。 |
| `derived` | 用于 Saga 那一跳：它为这次提交发出的命令的 ID。 |
| `signalTime` | 结果产生的时间。 |
| `transformedFrom` | 准入时转换器改写了命令时：原命令的 `{command, aggregateId}`。 |
| `chainEnded`、`pending` | 因为 Saga 没有再发命令，等待在最后一跳之前结束：`chainEnded` 为 true，`pending` 按顺序列出没有到达的跳。 |

内核的结局在 `committed` 上怎样映射到结果：

| 结局 | `status` | 说明 |
| --- | --- | --- |
| 已提交 | `succeeded` | `version` 是新版本；`results` 即决定函数返回的结果 |
| 早已提交 | `succeeded` | `duplicate` 为 true；没有 `results` |
| 无变化 | `succeeded` | `version` 不变 |
| 被拒绝 | `rejected` | `error.code` 是拒绝的错误码；`version` 是拒绝的事件到达的版本，否则是决策所见的版本，创建命令没有；重试时 `duplicate` 为 true |
| 争用 | `failed` | `wow.command.contended`；用同一请求 ID 重试 |
| 未知 | `failed` | `wow.command.unknown`；用同一请求 ID 重试，它不可能提交两次 |
| 等待超时 | `failed` | `wow.wait.timeout`；等待者不再等待，并不表示命令失败 |

在 HTTP 上，成功的命令以 200 返回结果。拒绝或失败返回 `application/problem+json`（RFC 9457）：`type` 设为 `urn:wow:error:<code>`，扩展成员 `code`、`violations` 和 `version`（problem 所回答的聚合版本：拒绝的版本，或低于最小版本的状态读取时的当前版本；扩展不会占用这个名字），整个结果放在 `result` 下，所以客户端无论哪种情况都只解析一种形状。带 `Accept: text/event-stream` 时，每一跳的结果是一个事件，最后一跳之后关闭流。

## 等待信号 {#wait-signal}

完成的那一跳直接向命令来自的节点的内部端口发送信号。信号带着这一跳的结果：

<!-- sample: core/runtime/src/test/resources/protocols/wait-signal.json -->

```json
{
  "format": 1,
  "waitId": "w-7",
  "result": {
    "format": 1,
    "commandId": "c-2",
    "requestId": "r-2",
    "correlationId": "c-2",
    "ref": {
      "type": {
        "context": "bank",
        "name": "account"
      },
      "tenantId": "t1",
      "id": "alice"
    },
    "hop": {
      "stage": "handled",
      "context": "bank",
      "subscription": "wow.snapshot"
    },
    "function": {
      "kind": "snapshot",
      "context": "bank",
      "processor": "wow.snapshot",
      "name": "wow.snapshot"
    },
    "status": "succeeded",
    "version": 2,
    "duplicate": false,
    "error": null,
    "results": {},
    "derived": [],
    "signalTime": 1760950800042,
    "transformedFrom": null,
    "chainEnded": false,
    "pending": []
  }
}
```

一个节点可以把发往同一节点的几个信号放在一个 JSON 数组里发送。接收节点按等待 ID、跳，以及发出信号的上下文和订阅来对信号去重。

## 集群通道 {#cluster-channel}

一个服务的节点在各自的内部端口上互相转发命令，在 `Wow-Service` 请求头里声明自己的服务。节点只接受本服务节点发来的转发：没有这个请求头时返回 401 `wow.auth.unauthenticated`，来自另一个服务时返回 403 `wow.auth.forbidden`。

| 消息 | 字段 |
| --- | --- |
| 转发 | `{v, command, transport, deadline}`：线上形式的[命令](#commands-on-a-topic)、传输段（未结束的等待的等待上下文与追踪传播字段，两者都没有时为 null）和绝对截止时间。 |
| 回复 | `{v, result, reached, refused}`：要么是属主在 `COMMITTED` 上的 `result`，以及它当即 `reached` 的后续各跳；要么是 `refused`，取值为 `not-owner`、`expired`、`unknown-type`、`too-large` 之一。 |

被拒绝的转发由发送节点自己执行。已经发出、随后超时的转发则不会：发送方回答结果未知，调用方用同一个请求 ID 重试。

## 快照 {#snapshot}

快照是一个聚合的最新状态及其元数据，按快照存储保存的样子：

| 字段 | 含义 |
| --- | --- |
| `ref`、`version` | 聚合，以及并入状态的最后一次提交的版本。 |
| `state` | 序列化的状态。 |
| `schemaFingerprint` | 状态 schema 的指纹，与状态视图的相同。 |
| `stateRevision` | 写入快照时聚合的状态修订号。修订号较低的快照绝不替换修订号较高的快照。 |
| `scope`、`tags`、`deleted` | 截至最后一次提交，聚合的所有者和空间、标签和删除标记。 |
| `firstEventTime`、`lastEventTime` | 它第一次和最后一次提交的时间。 |
| `operator`、`firstOperator` | 它最后一次和第一次提交的操作人。 |
| `schedules` | 它保持的定时，每条规则按它的键。 |

这些也是 `snapshot` 查询模型的系统字段。

## 格式版本 {#format-versions}

带格式版本的结构有：日志条目的事实和传输段、快照记录、状态事件、命令的线上形式、命令结果、等待信号、Kafka 信封，以及集群通道的消息。

- 日志和快照存储按表记录格式版本。线上的每条消息都带着自己的格式版本：`format`，在集群通道上是 `v`。
- **增加不算格式变化。** 新增一个带默认值的可选字段，格式版本不变。读者忽略不认识的字段，缺失的字段填上默认值。
- **在同一主版本内**，新字段先由一个次版本开始读取，下一个次版本才开始写入，所以滚动升级期间相邻的两个次版本可以并存。
- **旧读者读不了的变化**要等下一个主版本，由它的迁移工具转换数据。

读者拒绝 `format` 不认识的信封，也拒绝 `kind` 不认识的信封。
