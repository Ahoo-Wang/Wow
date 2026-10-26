---
title: 术语表
description: Wow 的术语，每条一句定义，附英文原词和解释它的页面。
---

# 术语表

每个术语在这些页面里只有一种含义。中文页面一律使用第二列的译名；表格按英文原词排序，便于与英文页面对照。

| English | 中文 | 定义 |
| --- | --- | --- |
| `ACCEPTED` | 已接收 | 命令通过准入时达到的等待阶段；只有命令写进了 Kafka 命令主题，它才是持久的。见[等待结果](../concepts/waits)。 |
| Admission | 准入 | 把请求变成命令的步骤：找到命令类型、确定身份、授权、解码并校验。见[写](../concepts/write-path#admission)。 |
| After hook | after 钩子 | 在命令函数之后、在同一状态上运行的函数，可以往同一个决定结果里追加事件。见[决定与演化](../concepts/programming-model#after-hooks)。 |
| Aggregate | 聚合 | 一组有自己身份和版本的领域状态，只由它自己的命令改变。见[决定与演化](../concepts/programming-model)。 |
| Aggregate reference | 聚合引用 | 标识一个聚合的那一个值：上下文、聚合类型、租户和 ID，写作 `context.aggregate/tenant/id`。见[身份](../concepts/identity#an-aggregate-is-addressed-by-one-value)。 |
| Aggregate type | 聚合类型 | 一类聚合在它的上下文里的名字，写作 `context.aggregate`。 |
| Alias | 别名 | 命令或事件以前的名字，保留下来，让以这个名字记录的历史仍然可读。见[决定与演化](../concepts/programming-model#events-change-over-time)。 |
| Analytics catalog | 分析目录 | 定义、审批和发布数据集的平台服务。见[读模型](../concepts/read-models#three-query-models)。 |
| API jar | api jar | 限界上下文发布的模块，包含它的命令、事件和状态类，以及对端读取的契约。见[一份契约](../concepts/contract#contracts-between-services)。 |
| Backpressure | 背压 | 系统某部分负载高的信号，例如繁忙拒绝或等待超时；订阅对它的回应是放慢，而不是暂停或停放。见[订阅](../concepts/subscriptions#failures-park-one-aggregate)。 |
| Bounded context | 限界上下文 | 领域的一部分，有自己的模型、自己的日志和自己的公开语言。见[上下文之间](../concepts/cross-context)。 |
| Bulkhead | 舱壁 | 带短队列的并发上限，防止查询用光写入需要的连接。见[读模型](../concepts/read-models#reads-must-not-slow-down-writes)。 |
| Caller | 调用方 | 发出请求的一方，即宿主认证的结果：租户、作用域、主体标识、属性，以及它是不是服务；只存活于请求期间。见[身份](../concepts/identity#who-the-caller-is)。 |
| Causation | 因果 | Saga 产生的命令提交时记录的根命令和直接原因。 |
| Chain | 等待链 | 由多跳组成、可能跨上下文的等待计划，只有 Saga 那一跳后面才能接别的跳。见[等待结果](../concepts/waits#waiting-for-a-chain)。 |
| Checkpoint | 检查点 | 订阅已处理完一个分区中所有条目的那个位置。见[订阅](../concepts/subscriptions#how-subscriptions-read-the-log)。 |
| Cluster channel | 集群通道 | 一个服务的节点之间的内部通道，把命令转发给其分区的属主。见[写](../concepts/write-path#the-cluster-channel)。 |
| Command | 命令 | 改变一个聚合的请求，只承载业务数据；它的身份来自准入。 |
| Command function | 命令函数 | 聚合针对一种命令类型的决定函数。 |
| Command topic | 命令主题 | Kafka 主题 `wow.<context>.commands`，命令经它异步到达一个上下文。见[上下文之间](../concepts/cross-context#commands-to-another-context)。 |
| `COMMITTED` | 已提交 | 命令的事件已追加，或命令没有追加就有了结论时达到的等待阶段。见[等待结果](../concepts/waits)。 |
| Contract | 契约 | 上下文的 Manifest 中其他服务需要的部分，以 `META-INF/wow/contract.json` 放在它的 api jar 里。见[一份契约](../concepts/contract#contracts-between-services)。 |
| CoSec | CoSec | Wow 集成的认证与授权框架，用于调用方、路由级授权和动作级授权。见[身份](../concepts/identity#access)。 |
| Creation rule | 创建语义 | 命令必须创建它的聚合、可以创建它，还是只作用于已存在的聚合。 |
| Cross-scope caller | 跨作用域调用方 | 被允许在另一个租户或作用域中行事的调用方，它在 `Wow-Scope` 请求头里指明那个租户或作用域。 |
| Dataset | 数据集 | 在发布的数据之上定义、由 ClickHouse 提供的宽表，是第三个查询模型。见[读模型](../concepts/read-models#three-query-models)。 |
| Decide | 决定 | 给定当前状态和一条命令，决定发生哪些事件、什么都不发生，或者拒绝这条命令的函数。见[决定与演化](../concepts/programming-model#decide-then-evolve)。 |
| Decide scope | 决定作用域 | 一次决定的上下文，决定函数通过它预留键、登记补偿、定时命令和返回额外结果。见[决定与演化](../concepts/programming-model#what-decide-can-reach)。 |
| Decider DSL | Decider DSL | 用 Kotlin 代码而不是注解来写聚合模型的方式；它编译成同一个模型。 |
| Decision | 决定结果 | 决定函数返回的值：事件、无变化或拒绝。 |
| Declared service | 声明的服务 | 发往内部端口的请求在 `Wow-Service` 请求头里指明的服务，即调用方的 `wow.service`。它是声明，不是凭据：边界是网络。见[身份](../concepts/identity#the-internal-port)。 |
| Derived command | 派生命令 | Saga 在处理一次提交时发出的命令；它的请求 ID 由那次提交推出，所以只提交一次。 |
| Duplicate | 重复请求 | 请求 ID 已经提交过的请求；用第一次的结果回答，不再决定。见[写](../concepts/write-path#duplicates-are-found-before-deciding)。 |
| Error code | 错误码 | 拒绝或失败的稳定名字，框架的错误码是 `wow.*`；在 Manifest 中连同 HTTP 状态一起声明。 |
| Event | 事件 | 命令产生的一条事实，由它的名字和修订号标识。 |
| Event log | 事件日志 | 一个限界上下文的事实所在的那一份分区、有序、只追加的存储，也是它唯一的事实来源。见[日志就是总线](../concepts/event-log)。 |
| Event stream | 事件流 | 一条命令产生的事件，加上它们的版本、请求和周围的事实：日志的一个条目。见[日志就是总线](../concepts/event-log#one-log-per-bounded-context)。 |
| Evolve | 演化 | 把一个事件应用到状态上、不做 I/O 的函数。见[决定与演化](../concepts/programming-model#decide-then-evolve)。 |
| Execution id | 执行 ID | 一次处理函数调用的确定 ID，由订阅、聚合和版本构成，用作幂等键。见[订阅](../concepts/subscriptions#at-least-once-and-idempotent)。 |
| Failure ledger | 失败台账 | 存在服务自己的存储里，记录它的订阅停放了哪些聚合、为什么停放。见[订阅](../concepts/subscriptions#failures-park-one-aggregate)。 |
| Format version | 格式版本 | Wow 存储结构或线上结构的版本；增加可选字段不改变它。见[日志就是总线](../concepts/event-log#formats)。 |
| Fragment | 片段 | 一个模块产出的那部分 Manifest；各片段合并成服务的 Manifest。见[一份契约](../concepts/contract#how-it-is-built)。 |
| `HANDLED` | 已处理 | 某个指定的订阅处理完一次提交时达到的等待阶段。见[等待结果](../concepts/waits)。 |
| Hand-over | 交接 | 扩缩容、升级或关闭期间，分区从一个节点有序地移到另一个节点。见[写](../concepts/write-path#the-cluster-channel)。 |
| Hop | 跳 | 等待计划的一步：一个阶段，带一个上下文，`HANDLED` 还要带一个订阅。 |
| Internal port | 内部端口 | 节点上承载集群通道、等待信号和来自其他服务的调用的端口，绝不暴露到集群之外。见[身份](../concepts/identity#the-internal-port)。 |
| Lane | 车道 | 一个聚合的命令逐条执行的队列。见[写](../concepts/write-path#execution)。 |
| Lease | 租约 | 节点对拥有一个分区的声明，保存在存储里并不断续期。 |
| Lifecycle command | 生命周期命令 | 每个聚合不用写代码就能处理的命令：删除、恢复、转移所有者或空间、打标签。见[决定与演化](../concepts/programming-model#lifecycle-commands)。 |
| Manifest | Manifest | 编译器构建的、对服务唯一的描述，路由、OpenAPI、客户端、视图和契约都从它推出。见[一份契约](../concepts/contract)。 |
| Operator | 操作人 | 提交以谁的名义记录：调用方的主体标识、它的租户，以及它是否在别处行事。见[身份](../concepts/identity#who-the-caller-is)。 |
| Owner | 所有者 | 在租户之内，聚合所属的主体。 |
| Ownership | 归属 | 一个聚合类型属于所有者（默认）还是属于整个租户。见[身份](../concepts/identity#who-an-aggregate-belongs-to)。 |
| Park | 停放 | 重试用尽后，在一个订阅里停止处理一个聚合，把它记进失败台账，其他聚合照常推进。见[订阅](../concepts/subscriptions#failures-park-one-aggregate)。 |
| Partition | 分区 | 日志固定数量的组成部分之一；一个聚合总是落在同一个分区。见[日志就是总线](../concepts/event-log#partitions)。 |
| Partitioned routing | 分区路由 | 默认的路由方式，在聚合所在分区的属主上执行每条命令。见[写](../concepts/write-path#routing)。 |
| Position | 位置 | 条目在分区内的序号，按提交变得可见的顺序分配。见[日志就是总线](../concepts/event-log#positions)。 |
| Processor | 处理器 | 一类订阅处理函数所在的类：投影、Saga 或反应器。 |
| Projection | 投影 | 从提交构建读模型的处理器。 |
| Published stream | 发布的事件流 | 上下文发布一个聚合的状态事件、供其他上下文读取的 Kafka 主题。见[上下文之间](../concepts/cross-context#published-streams)。 |
| Query model | 查询模型 | 查询读取的三类记录之一：`snapshot`、`events` 和 `dataset`。见[读模型](../concepts/read-models#three-query-models)。 |
| Reactor | 反应器 | 为每次提交执行一个效果、不发命令的处理器。 |
| Receipt | 回执 | 来自另一个上下文的命令被拒绝时（或应要求在成功时），目标上下文发回到发送方回执主题上的消息。见[上下文之间](../concepts/cross-context#commands-to-another-context)。 |
| Rejection | 拒绝 | 决定函数拒绝了命令的结果，带错误码、消息和可选的字段 violations；什么都不写。 |
| Request facts | 请求事实 | 准入随命令记录的关于请求的事实，例如已认证的应用。见[身份](../concepts/identity#request-facts)。 |
| Request id | 请求 ID | 一个业务请求的幂等键；同一请求 ID 在每个租户和聚合类型下至多提交一次。 |
| Reserved key | 预留键 | 决定函数在日志所在存储里跨聚合预留的唯一键，例如用户名。 |
| Retired event | 退役事件 | 类已被删除、读取时跳过的事件类型。见[决定与演化](../concepts/programming-model#events-change-over-time)。 |
| Revision | 修订号 | 事件结构的版本；升级函数把旧修订号升到下一个。 |
| Saga | Saga | 把提交变成命令（发往自己的上下文或其他上下文）的处理器。 |
| Scope | 作用域 | 聚合的所有者和空间，或调用方所代表的所有者和空间。见[身份](../concepts/identity#who-an-aggregate-belongs-to)。 |
| Service identity | 服务身份 | 内部端口上同步调用或读的身份：它声明的服务，主体为 `service:<name>`。 |
| Sink | Sink | 把提交复制到服务之外（Kafka、ClickHouse 或 Elasticsearch）的订阅。见[订阅](../concepts/subscriptions#sinks)。 |
| Slot | 槽位 | 聚合哈希到的 4,096 个固定桶之一；槽位映射到分区。见[日志就是总线](../concepts/event-log#partitions)。 |
| Snapshot | 快照 | 一个聚合最新的已提交状态及其元数据；默认读模型。见[读模型](../concepts/read-models)。 |
| `SNAPSHOT` | `SNAPSHOT` | `HANDLED(snapshot)` 的简写，默认的等待阶段。见[等待结果](../concepts/waits#the-default-is-the-snapshot)。 |
| Space | 空间 | 租户之下的进一步划分，例如一家店或一个团队，聚合和调用方都可以属于它。 |
| State | 状态 | 聚合的数据，按顺序演化它的事件得到。 |
| State event | 状态事件 | 一次提交的事件流，连同提交之后聚合的状态：处理函数收到的、发布的事件流承载的都是它。 |
| State revision | 状态修订号 | 状态存储结构的版本；提高它会从日志重建快照。见[读模型](../concepts/read-models#snapshots-are-a-cache)。 |
| State view | 状态视图 | 一个版本提交时序列化一次的状态，快照和订阅读它，而不是活的实例。 |
| Subscription | 订阅 | 一个名字、一个过滤器和一个处理函数，从检查点开始读日志或发布的事件流。见[订阅](../concepts/subscriptions)。 |
| Synchronous call | 同步调用 | Saga 发往另一个服务内部端口、返回目标结果的命令。见[上下文之间](../concepts/cross-context#synchronous-calls)。 |
| Systemic failure | 系统性故障 | 不是某个聚合的错的故障，例如存储不可用；它暂停一个分区，而不是停放聚合。 |
| Tags | 标签 | 聚合上的键值列表，基于属性的访问控制拿它来匹配。 |
| Tenant | 租户 | 数据最上层的所有者；每个聚合身份的一部分。 |
| Tenant-level caller | 租户级调用方 | 作用域里没有所有者、读写它租户所有聚合的调用方。 |
| Transport data | 传输段 | 只为帮助投递而随条目或消息传递的数据，例如一个未结束的等待；从不是事实的一部分。见[日志就是总线](../concepts/event-log#one-log-per-bounded-context)。 |
| Upgrade function | 升级函数 | 读取时把事件的 JSON 从一个修订号升到下一个的函数。 |
| Version | 版本 | 一个聚合的提交次数；从 1 开始，每次提交加一。 |
| Wait plan | 等待计划 | 调用方发出命令后等待的东西：一个阶段或一条由跳组成的链，以及超时。见[等待结果](../concepts/waits)。 |
| Wait signal | 等待信号 | 一跳直接发往命令来源节点、推进它的等待的消息。见[等待结果](../concepts/waits#how-a-wait-works)。 |
