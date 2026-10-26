---
title: Glossary
description: The terms of Wow, each with a one-sentence definition, the Chinese term the Chinese pages use, and the page that explains it.
---

# Glossary

Each term has one meaning across these pages. The second column is the term the Chinese pages use for it.

| Term | 中文 | Definition |
| --- | --- | --- |
| `ACCEPTED` | 已接收 | The wait stage reached when a command has passed admission; durable only when the command went to a Kafka command topic. See [Waiting for outcomes](../concepts/waits). |
| Admission | 准入 | The step that turns a request into a command: it finds the command type, establishes the identity, authorizes, decodes and validates. See [Writing](../concepts/write-path#admission). |
| After hook | after 钩子 | A function that runs after a command function, on the same state, and may add events to the same decision. See [Deciding and evolving](../concepts/programming-model#after-hooks). |
| Aggregate | 聚合 | A cluster of domain state with its own identity and version, changed only by its commands. See [Deciding and evolving](../concepts/programming-model). |
| Aggregate reference | 聚合引用 | The one value that identifies an aggregate: context, aggregate type, tenant and id, written `context.aggregate/tenant/id`. See [Identity](../concepts/identity#an-aggregate-is-addressed-by-one-value). |
| Aggregate type | 聚合类型 | The name of a kind of aggregate within its context, written `context.aggregate`. |
| Alias | 别名 | A former name of a command or an event, kept so that history recorded under it still reads. See [Deciding and evolving](../concepts/programming-model#events-change-over-time). |
| Analytics catalog | 分析目录 | The platform service where datasets are defined, approved and published. See [Read models](../concepts/read-models#three-query-models). |
| API jar | api jar | The published module of a bounded context that holds its commands, events and state classes and the contract peers read. See [One contract](../concepts/contract#contracts-between-services). |
| Backpressure | 背压 | A sign that a part of the system is loaded, such as a busy refusal or a wait timeout, which subscriptions answer by slowing down rather than by pausing or parking. See [Subscriptions](../concepts/subscriptions#failures-park-one-aggregate). |
| Bounded context | 限界上下文 | A part of the domain with its own model, its own log and its own published language. See [Between contexts](../concepts/cross-context). |
| Bulkhead | 舱壁 | The concurrency limit, with a short queue, that keeps queries from using up the connections writes need. See [Read models](../concepts/read-models#reads-must-not-slow-down-writes). |
| Caller | 调用方 | Who sends a request, as the host authenticated it: tenant, scope, subject, attributes and whether it is a service; it lives only as long as the request. See [Identity](../concepts/identity#who-the-caller-is). |
| Causation | 因果 | The root command and the direct cause recorded with a commit that a saga's command produced. |
| Chain | 等待链 | A wait plan of several hops, possibly across contexts, where only a saga hop can be followed by another hop. See [Waiting for outcomes](../concepts/waits#waiting-for-a-chain). |
| Checkpoint | 检查点 | The position up to which a subscription has handled every entry of a partition. See [Subscriptions](../concepts/subscriptions#how-subscriptions-read-the-log). |
| Cluster channel | 集群通道 | The internal channel between the nodes of one service, which forwards commands to the owners of their partitions. See [Writing](../concepts/write-path#the-cluster-channel). |
| Command | 命令 | A request to change one aggregate, carrying business data only; its identity comes from admission. |
| Command function | 命令函数 | The decide function of an aggregate for one command type. |
| Command topic | 命令主题 | The Kafka topic `wow.<context>.commands` through which commands reach a context asynchronously. See [Between contexts](../concepts/cross-context#commands-to-another-context). |
| `COMMITTED` | 已提交 | The wait stage reached when a command's events are appended, or the command was settled without appending. See [Waiting for outcomes](../concepts/waits). |
| Contract | 契约 | The part of a context's Manifest that other services need, carried in its API jar as `META-INF/wow/contract.json`. See [One contract](../concepts/contract#contracts-between-services). |
| CoSec | CoSec | The authentication and authorization framework Wow integrates for callers, route-level and action-level authorization. See [Identity](../concepts/identity#access). |
| Creation rule | 创建语义 | Whether a command must create its aggregate, may create it, or applies only to one that exists. |
| Cross-scope caller | 跨作用域调用方 | A caller allowed to act in another tenant or scope, which it names in the `Wow-Scope` header. |
| Dataset | 数据集 | A wide table defined over published data and served from ClickHouse, the third query model. See [Read models](../concepts/read-models#three-query-models). |
| Decide | 决定 | The function that, given the current state and a command, decides which events happen, or that nothing happens, or rejects the command. See [Deciding and evolving](../concepts/programming-model#decide-then-evolve). |
| Decide scope | 决定作用域 | The context of one decision, through which decide reserves keys, registers compensations, schedules commands and returns extra results. See [Deciding and evolving](../concepts/programming-model#what-decide-can-reach). |
| Decider DSL | Decider DSL | The Kotlin way of writing an aggregate model as code instead of annotations; it compiles to the same model. |
| Decision | 决定结果 | The value decide returns: events, no change, or a rejection. |
| Declared service | 声明的服务 | The service a request to the internal port names in its `Wow-Service` header, the caller's `wow.service`. A statement, not a credential: the network is the boundary. See [Identity](../concepts/identity#the-internal-port). |
| Derived command | 派生命令 | A command a saga sends while handling a commit; its request id is derived from that commit, so it commits once. |
| Duplicate | 重复请求 | A request whose request id has already committed; it is answered with the first result and not decided again. See [Writing](../concepts/write-path#duplicates-are-found-before-deciding). |
| Error code | 错误码 | The stable name of a rejection or failure, `wow.*` for the framework's, declared in the Manifest with its HTTP status. |
| Event | 事件 | A fact a command produced, identified by its name and revision. |
| Event log | 事件日志 | The one partitioned, ordered, append-only store of a bounded context's facts, and its only source of truth. See [The log is the bus](../concepts/event-log). |
| Event stream | 事件流 | The events one command produced, with their version, request and the facts around them: one entry of the log. See [The log is the bus](../concepts/event-log#one-log-per-bounded-context). |
| Evolve | 演化 | The function that applies one event to the state, with no I/O. See [Deciding and evolving](../concepts/programming-model#decide-then-evolve). |
| Execution id | 执行 ID | The deterministic id of one handler call, from subscription, aggregate and version, used as an idempotency key. See [Subscriptions](../concepts/subscriptions#at-least-once-and-idempotent). |
| Failure ledger | 失败台账 | The record, in a service's own storage, of the aggregates its subscriptions have parked and why. See [Subscriptions](../concepts/subscriptions#failures-park-one-aggregate). |
| Format version | 格式版本 | The version of a Wow storage or wire structure; adding an optional field does not change it. See [The log is the bus](../concepts/event-log#formats). |
| Fragment | 片段 | The part of a Manifest one module produces; fragments are merged into the service's Manifest. See [One contract](../concepts/contract#how-it-is-built). |
| `HANDLED` | 已处理 | The wait stage reached when a named subscription has handled a commit. See [Waiting for outcomes](../concepts/waits). |
| Hand-over | 交接 | The orderly move of a partition from one node to another during scaling, an upgrade or a shutdown. See [Writing](../concepts/write-path#the-cluster-channel). |
| Hop | 跳 | One step of a wait plan: a stage, with a context and, for `HANDLED`, a subscription. |
| Internal port | 内部端口 | The port of a node that serves the cluster channel, wait signals and calls from other services, never exposed outside the cluster. See [Identity](../concepts/identity#the-internal-port). |
| Lane | 车道 | The queue in which one aggregate's commands run one at a time. See [Writing](../concepts/write-path#execution). |
| Lease | 租约 | A node's claim, kept in storage and renewed, to own a partition. |
| Lifecycle command | 生命周期命令 | A command every aggregate handles without code: delete, restore, transfer the owner or the space, apply tags. See [Deciding and evolving](../concepts/programming-model#lifecycle-commands). |
| Manifest | Manifest | The single description of a service, built by the compiler, from which routes, OpenAPI, clients, views and contracts are derived. See [One contract](../concepts/contract). |
| Operator | 操作人 | Who a commit is recorded under: the caller's subject, its tenant and whether it acted elsewhere. See [Identity](../concepts/identity#who-the-caller-is). |
| Owner | 所有者 | The principal an aggregate belongs to within its tenant. |
| Ownership | 归属 | Whether an aggregate type belongs to an owner (the default) or to the whole tenant. See [Identity](../concepts/identity#who-an-aggregate-belongs-to). |
| Park | 停放 | To stop handling one aggregate in one subscription after its retries are spent, recording it in the failure ledger while other aggregates go on. See [Subscriptions](../concepts/subscriptions#failures-park-one-aggregate). |
| Partition | 分区 | One of the fixed number of parts of a log; an aggregate always lands in the same one. See [The log is the bus](../concepts/event-log#partitions). |
| Partitioned routing | 分区路由 | The default routing, which runs each command on the owner of its aggregate's partition. See [Writing](../concepts/write-path#routing). |
| Position | 位置 | An entry's sequence number within its partition, in the order commits become visible. See [The log is the bus](../concepts/event-log#positions). |
| Processor | 处理器 | A class of subscription handlers: a projection, a saga or a reactor. |
| Projection | 投影 | A processor that builds a read model from commits. |
| Published stream | 发布的事件流 | The Kafka topic on which a context publishes an aggregate's state events for other contexts. See [Between contexts](../concepts/cross-context#published-streams). |
| Query model | 查询模型 | One of the three kinds of record queries read: `snapshot`, `events` and `dataset`. See [Read models](../concepts/read-models#three-query-models). |
| Reactor | 反应器 | A processor that performs an effect for each commit and sends no commands. |
| Receipt | 回执 | The message a context sends back on the sender's receipts topic when a command from another context is rejected, or on success when asked. See [Between contexts](../concepts/cross-context#commands-to-another-context). |
| Rejection | 拒绝 | The outcome of a command that decide refused, with an error code, a message and optional field violations; nothing is written. |
| Request facts | 请求事实 | The facts about a request admission records with the command, such as the authenticated application. See [Identity](../concepts/identity#request-facts). |
| Request id | 请求 ID | The idempotency key of one business request; the same request id commits at most once per tenant and aggregate type. |
| Reserved key | 预留键 | A unique key, such as a user name, that decide reserves across aggregates in the log's store. |
| Retired event | 退役事件 | An event type whose class was removed and that reading skips. See [Deciding and evolving](../concepts/programming-model#events-change-over-time). |
| Revision | 修订号 | The version of an event's structure; an upgrade function moves an old revision to the next. |
| Saga | Saga | A processor that turns commits into commands, in its own context or others. |
| Scope | 作用域 | The owner and space of an aggregate, or the owner and space a caller acts in. See [Identity](../concepts/identity#who-an-aggregate-belongs-to). |
| Service identity | 服务身份 | The identity a synchronous call or a read on the internal port has: the service it declares, subject `service:<name>`. |
| Sink | Sink | A subscription that copies commits out of the service, to Kafka, ClickHouse or Elasticsearch. See [Subscriptions](../concepts/subscriptions#sinks). |
| Slot | 槽位 | One of 4,096 fixed buckets an aggregate hashes to; a slot maps to a partition. See [The log is the bus](../concepts/event-log#partitions). |
| Snapshot | 快照 | The latest committed state of an aggregate with its metadata; the default read model. See [Read models](../concepts/read-models). |
| `SNAPSHOT` | `SNAPSHOT` | Short for `HANDLED(snapshot)`, the default wait stage. See [Waiting for outcomes](../concepts/waits#the-default-is-the-snapshot). |
| Space | 空间 | A further partition of a tenant, such as a shop or a team, that an aggregate and a caller can belong to. |
| State | 状态 | The data of an aggregate, built by evolving its events in order. |
| State event | 状态事件 | One commit's event stream together with the aggregate's state after it: what handlers receive and published streams carry. |
| State revision | 状态修订号 | The version of a state's stored structure; raising it rebuilds the snapshots from the log. See [Read models](../concepts/read-models#snapshots-are-a-cache). |
| State view | 状态视图 | The state of a version, serialized once when it commits, which snapshots and subscriptions read instead of the live instance. |
| Subscription | 订阅 | A name, a filter and one handler that read a log or a published stream from a checkpoint. See [Subscriptions](../concepts/subscriptions). |
| Synchronous call | 同步调用 | A saga's command sent to another service's internal port that returns the target's result. See [Between contexts](../concepts/cross-context#synchronous-calls). |
| Systemic failure | 系统性故障 | An outage that is not one aggregate's fault, such as an unavailable store, which pauses a partition instead of parking aggregates. |
| Tags | 标签 | Key-value lists on an aggregate that attribute-based access control matches against. |
| Tenant | 租户 | The top-level owner of data; part of every aggregate's identity. |
| Tenant-level caller | 租户级调用方 | A caller whose scope has no owner and who reads and writes every aggregate of its tenant. |
| Transport data | 传输段 | Data that travels with an entry or a message only to help delivery, such as an open wait; never part of the facts. See [The log is the bus](../concepts/event-log#one-log-per-bounded-context). |
| Upgrade function | 升级函数 | A function that moves an event's JSON from one revision to the next when it is read. |
| Version | 版本 | The number of commits an aggregate has; it starts at 1 and grows by one per commit. |
| Wait plan | 等待计划 | What a caller waits for after sending a command: a stage, or a chain of hops, with a timeout. See [Waiting for outcomes](../concepts/waits). |
| Wait signal | 等待信号 | The message a hop sends straight to the node a command came from, to move its wait on. See [Waiting for outcomes](../concepts/waits#how-a-wait-works). |
