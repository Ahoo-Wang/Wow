---
title: 升级部署
description: 用滚动更新升级服务，哪些可以并行运行，格式、状态修订号、事件和 Manifest 怎样让升级保持安全，以及主版本会改变什么。
outline: deep
---

# 升级部署

服务通过滚动更新升级：新节点在旧节点旁边启动，分区移到它们上面，然后旧节点停止。有一段时间，两个版本同时运行，面对同一个数据库和同样的主题。本页说明是什么让这件事保持安全，无论是升级 Wow 还是升级你自己的代码。

## 两个版本并行 {#two-versions-side-by-side}

在同一主版本内，Wow 相邻的次版本可以在一个服务内、也可以跨服务并行运行。它们的内部协议（集群通道、等待信号、线上形式）以及存储格式保持兼容：

- **存储格式**按结构而不是按行做版本：每张日志、快照和定时器表都在 `wow_format` 中记录它的格式。在同一主版本内格式不变，读者忽略它们不认识的东西。同一格式内新增的列或字段，在今天会这样做的存储里就地加上：ClickHouse 的 `_state`（[状态 schema 变化时](./clickhouse#when-the-state-schema-changes)）和 Elasticsearch 的映射（[后来映射的字段](./elasticsearch#field-mapped-later)）。
- **线上形式**（Kafka 信封、命令、回执、等待信号）也带着它们的格式。新的可选字段先由一个次版本开始读取，下一个次版本才开始写入。
- **枚举的新值**（例如结果的状态、跳的阶段、Manifest 的 `creation`）遵循同样的顺序：先读者，后写者。比写者旧的读者会宽容地读取它不认识的值：Wow 的客户端和节点把未知的状态、阶段或函数种类读作 `UNKNOWN`，Manifest 的读者略去未知值所属的部分（未知 `creation` 的命令、未知 `ownership` 的聚合、未知种类或 start 的订阅），把错误中未知的 `retry` 读作 none，并记录日志。服务端仍会拒绝请求中的未知值。
- **改变自身某部分含义的 Manifest** 在 `requires` 中列出一个标记。不理解该标记的读者会拒绝这份 Manifest，而不是误读它。
- **不兼容的改变**要等下一个主版本，由它的迁移来处理。

所以一次只升一个次版本，并让每次发布完成之后再开始下一次。

## 你的代码：事件 {#your-code-events}

事件的类在你服务的两个版本之间可能改变，而发布期间两个版本都会读取日志：

- 新增一个带默认值的可选字段，什么都不需要。
- 其他任何改变都要提高事件的修订号，并提供一个从旧修订号开始的升级函数（[演进事件](../guide/event-evolution)）。先部署读取的一方：每个节点都能读修订号 `n + 1` 之后，再部署写入它的代码。否则旧节点可能读到一条它没有升级函数的记录。
- 另一个服务中的消费者，在它的 api 升级之前，会把新修订号读成 `PublishedEvent`，也不会为它调用接收这个事件类的处理函数（[发布与消费事件流](../guide/published-streams#events-the-subscriber-has-no-class-for)）。

## 你的代码：状态 {#your-code-state}

状态结构的改变要提高 `@AggregateRoot(stateRevision = n)`。发布期间，新节点以新修订号声明快照表并原地重建它，而旧节点继续以旧修订号写入；存储会阻止旧节点覆盖已重建的快照。回滚时，只有在不再剩下任何更高修订号的节点之后，才会把声明降回去（[重建与重放](./replay#snapshots-raise-the-state-revision)）。

数仓的展平视图遵循同样的规则：写入者只会用更新的定义替换它，所以重启的旧节点不会撤销新节点的视图（[ClickHouse](./clickhouse#schema-management)）。

## 你的代码：契约 {#your-code-the-contract}

服务暴露的命令、事件、状态和错误，是它与客户端和对端之间的契约。Gradle 插件把每次构建的 Manifest 与提交的基线比较，会让破坏客户端的改变失败：删除命令或事件、新增必填字段、删除字段或改变字段类型。用 `updateWowManifestBaseline` 记录有意的改变，并与代码一起评审（[测试服务](../guide/testing-services#check-the-contract)）。在基线的兼容线（服务的主版本号）之内，更新会拒绝破坏性改变：它需要服务的下一个主版本（同时记录这次破坏和新的兼容线），或者在旧命令旁边新增一条命令。
或者在旧命令旁边新增一条命令。

**为命令新增属性时的发布顺序。** 服务会拒绝给出其命令没有的属性的请求体（`unknown-property`），因此服务的节点要先于发送新属性的客户端升级；调用另一个服务的服务，要在被调用的服务之后升级。同一服务的节点之间会忽略该属性：旧版本的节点读取新节点转发的或日志中的命令（包括其 saga 派生的命令）时不带它。

## 发布期间的订阅 {#subscriptions-during-a-rollout}

分区连同它的检查点移到新节点上，新节点的订阅从那里继续。新版本中新增的处理函数按它的种类开始（[订阅](../concepts/subscriptions#replay-and-backfill)）：投影从开头开始；读取自己上下文日志的反应器或 Saga 从末尾开始；反应器或 Saga 读取另一个上下文事件流的处理函数，从它的服务加入这条流的地方开始，服务里还没有别的订阅读过它时从开头开始，否则从末尾开始。`@OnEvent(start = ...)` 或 `wow.subscriptions.[<context>.<processor.function>].start` 可以覆盖它。

订阅的名字就是它的身份，所以上线前要检查改名：

- **改了名的处理器或处理函数是一个新订阅。** 它按同样的规则开始，就像新的一样：Saga 或反应器从末尾开始，跳过旧名字还没处理到的内容；投影从开头开始，重建它的读模型。旧名字的检查点和停放条目留在原处，不再有人读取。
- **要保留位置，就钉住旧名字**：在改了名的类或函数上用 `@Name`。
- **新节点在首次启动时警告**已没有订阅的名字下的检查点；`GET /wow/ops/subscriptions` 会列出它们（[ops 服务与控制台](./ops-console#rename-and-rewind)）。
- **换一个名字是唯一的回卷**：检查点从不往回设。用新名字和更早的 `start` 部署处理函数，就是再读一次流的办法。

## 节点离开时的调用 {#calls-while-a-node-goes}

发布停止的节点是有通知地离开的：它在内部端口仍然应答的时候移交分区、排空在途工作，然后关闭连接。这期间到达它的 saga 调用会得到回答；之后来的调用连接失败，在同一次尝试中转到服务的下一个节点，付出的至多是连接超时（`wow.peer.connect-timeout`，2 秒）。

没有通知就失去的节点代价更高，例如被强制删除的 pod 或消失的机器：在通向它的连接上发出的调用得不到回答，在它的等待加一段宽限后结束（默认 12 秒），然后由它的订阅重试。在服务的节点列表重新读取之前（至多 30 秒），这个节点的地址仍在其中，所以落到通向它的另一个连接上的调用付出同样的代价。

## 跨服务的固定租户 {#a-fixed-tenant-across-services}

类型的固定租户（`wow.aggregates.[<context>.<aggregate>].tenant`）是它所在服务自己的配置。发往另一个服务的命令（saga 的、定时器的，或经命令主题转发的调用方的）会带上发送方代行的租户（`actingTenant`，见[主题上的命令](../reference/protocols#commands-on-a-topic)），目标服务据此决定自己类型的租户（见[固定的租户](../guide/ownership#a-fixed-tenant)）。10.0.0-beta.12 及更早的版本不带这个字段，它们的命令入口也不应用固定租户。所以只要这类命令的任何一端还有这些版本的节点在运行：

- **发送方旧、目标新。** 命令没有代行租户，它的 ref 里的租户就算指名：发往目标固定在另一个租户的类型的命令，会以 `wow.command.malformed` 被拒绝。saga 的 `@OnRejected` 处理器会收到它，没有处理器时，订阅会挂起派生它的聚合；不会有命令落到固定租户之外。等发送方的节点全部升级后，重试挂起的条目：命令会带着代行租户重新派生，并落在固定租户。
- **发送方新、目标旧。** 旧的命令入口读不到代行租户，也不应用固定租户：它在 ref 指名的租户里执行命令，目标服务的其他节点不会去那里找它。先升级固定了某个类型租户的服务，再升级向这个类型发送命令的服务。
- **在同一个服务内**，旧节点停止之前，仍按以前的方式在执行所在的租户里派生、触发它的命令。

**ACCEPTED 与 COMMITTED 的 ref。** 经命令主题转发的命令，由发送它的节点回答 `ACCEPTED`，带着发送方给出的 ref，即发送方的租户。它的 `COMMITTED` 由目标的命令入口发出信号，指名目标决定的租户。客户端如果只等待发往另一个服务的固定类型的命令的 `ACCEPTED`，读到的是发送方的租户；要读到命令落在哪里，就等待 `COMMITTED`。

## 发布的事件流里的敏感值 {#sensitive-values-on-a-published-stream}

10.0.0-beta.14 及更早版本按提交时的样子发布每个值，`@Sensitive` 的也一样。之后的版本在写入时脱敏，每个 `DISPLAY` 值也脱敏，除非属主声明这个事件流揭示它们（[敏感值](../guide/published-streams#sensitive-values)）。之前发布的内容仍然留着：

- **主题上。** 旧节点写的记录保留它们的值，直到主题的保留期删掉它们；transport sink 不会重新发布它们。要更早清掉，就新建一个主题（`@AggregateRoot(topic)`，这是契约的破坏性变更），等消费者都读新主题后删掉旧的。新主题只带从那以后发布的内容，所以从它开头重建的投影也从那时开始。
- **发布期间。** 属主的旧节点在停下之前一直发布原值，所以这段时间消费者可能同时收到原值和掩码的记录。
- **需要 `DISPLAY` 原值的消费者**在属主升级后拿到的是掩码，除非属主声明这个事件流揭示它们。在属主开始遮盖它们的那个版本里一并声明，消费者就永远看不到掩码。

## 更长的聚合 ID {#longer-aggregate-ids}

10.0.0-beta.16 及更早的版本拒绝百分号编码后长于 256 个字符的聚合 ID，也就是 28 个中文、日文或韩文字符。之后的版本接受 UTF-8 编码后至多 512 字节的 ID，也就是 170 个这样的字符（[聚合 ID](../reference/http#aggregate-ids)）。只有两端都接受时，ID 才能比以前更长：

- **先升级网关。** CoSec 8.0.0-beta.5 及更早的版本拒绝按收到时（编码后）长于 256 个字符的路径段，在服务收到请求之前就回答 400。先把网关升级到 CoSec 8.0.0-beta.6 或更新的版本（它按解码后计数路径段），再升级服务。
- **再升级每个节点。** 服务还有旧节点在运行时，旧节点以 `wow.command.malformed` 拒绝更长的 ID，转发调用的旧对端也一样。新节点接受的创建之后，发给这个聚合的命令可能被旧节点拒绝。Saga 发给旧目标的命令也同样被拒绝。在这个服务、以及向它发送命令的每个服务的每个节点都运行新版本之前，不要让客户端或 Saga 以更长的 ID 创建聚合。
- **路由。** Manifest 里按 ID 的路由除 ID 之外长于 512 个字符时，构建会拒绝它，并指出这条路由和要缩短的名字。
- **Elasticsearch。** 文档的 ID 由租户和聚合 ID 组成。UTF-8 编码后至多 512 字节时，它保持原样，所以索引不需要重建。更长的（长租户之后的长 ID，以前的版本写不进去）由它的前缀加上整体的 SHA-256 组成。

## 不能改变的设置 {#settings-that-must-not-change}

`wow.log.partitions` 在日志有了条目之后就固定了，一个服务的节点共用它们的存储布局（[配置服务](./configuration#what-must-match-across-nodes)）。

## 主版本 {#a-major-version}

新的主版本可能改变格式和协议。它的节点不会与上一个主版本的节点并行运行：它附带一次迁移，在切换之前或切换期间运行，就像 V10 从 9.x 迁移那样（[从 9.x 迁移](../migrate/)）。

## 从 10.0.0 预发布版升级 {#from-a-10-0-0-pre-release}

10.0.0 的预发布版（`alpha`、`beta`、`rc`）对它存储的数据与它的 API 都不做兼容承诺。它的存储不会带过来：请在空的存储上启动本版本。预发布版写入的 MongoDB 日志没有按租户限定的访问键，所以在一次盖章重写这些流的属性之前，对 owner 或 space 的相等查不到它们。从 9.x 迁移的服务请按 [从 9.x 迁移](../migrate/) 进行。

列出了快照存储的预发布版，要在启动本版本之前修改配置。配置项 `wow.snapshot.stores`、`wow.snapshot.primary`，以及聚合的 `snapshot.stores` 和 `snapshot.primary` 已经移除，每一个都会拒绝启动，并指出它自己。用 `wow.snapshot.store` 或聚合的 `snapshot.store` 为每个类型指定它唯一的存储。保留了检索副本的部署（`snapshot.stores: [main, search]` 加 `query.snapshot.store: search`）去掉 `snapshot.stores`、保留 `query.snapshot.store`：副本的订阅名不变，仍是 `wow.snapshot:search`，所以它的索引和检查点都保留，也不会重新读取任何东西。

预发布版本拒绝把 Elasticsearch 存储作为聚合类型自己的快照存储；这个版本接受它（[Elasticsearch](./elasticsearch#only-snapshot-store)）。快照留在数据库、在 Elasticsearch 上另有副本的部署无需改动。想让类型的状态只存一份的部署，在类型的 `snapshot.store` 中指定 Elasticsearch 存储。这个存储随后从日志回填，期间该类型答 `rebuilding`（[重建与重放](./replay)）。

本版本使用 springdoc-openapi 3.2.1，它基于 swagger-core 3.0 和 Jackson 3：

- **工件改名。** 应用自己声明了 swagger 工件的，要改名：`io.swagger.core.v3:swagger-annotations-jakarta`、`swagger-models-jakarta` 和 `swagger-core-jakarta` 改为 `swagger-annotations`、`swagger-models` 和 `swagger-core`，版本 3.0.0，由 springdoc 的 BOM 管理。修改过 `Json.mapper()` 或 `Json31.mapper()` 的代码，改用 `ObjectMapperFactory.addCustomizer` 定制 swagger 的 mapper：Jackson 3 的 mapper 不可变。
- **应用自己的 schema。** springdoc 从应用的类推导出的 schema 可能有变化：以缩写开头的 getter 保留其大小写（`getETag()` 是 `ETag`，不再是 `etag`）；Kotlin 类的非空属性列为 `required`；唯一公开构造函数的、不对应任何属性的参数，成为 `writeOnly` 属性。由 `/v3/api-docs` 生成的客户端会看到这些变化。Wow 路由的文档不变，`/v3/api-docs` 中和 `GET /wow/openapi` 中都一样。
- **Jackson。** swagger-core 3 要求 Jackson 3.2，高于 Spring Boot 4.2.0 管理的 3.1。在 Boot parent 之下的 Maven 构建，以及使用 Spring 依赖管理插件的 Gradle 构建，都解析为 Boot 的版本，springdoc 自己的构建也是这样。用 `platform(...)` 引入 Boot BOM 的 Gradle 构建，在类路径上有 springdoc 的地方会解析为较高的 3.2，于是它的服务与其他模块运行的是不同的 Jackson。Wow 的构建让每条类路径都保持 Boot 的版本：对 `tools.jackson:jackson-bom` 使用 `enforcedPlatform`，版本取自 Boot 的 BOM；Gradle 应用可以照此保持只有一个 Jackson。

本版本使用 CoSec 8.0.0-beta.6 和 CoCache 8.2.0。全部变化见 CoSec 的[升级：从 8.0.0-beta.5](https://cosec.ahoo.me/operations/upgrading-previews#from-8-0-0-beta-5)；Wow 的部署会遇到的是：

- **CoCache 8.1.0 在 Redis Cluster 上的缺陷。** Wow 10.0.0-beta.13 到 beta.16 运行 Spring Boot 4.2，依赖 CoCache 8.1.0。在 Spring Boot 4.2 的 Lettuce 之下，CoCache 8.1.0 的 Redis 后端（`cocache-redis`）连接 Redis Cluster 时，拒绝发往每个主节点的第一条命令：`Currently not connected. Commands are rejected.` 通过 `wow-cocache`（[CoCache](../guide/extensions/cocache)）在 Redis Cluster 上共享状态的服务，缓存最初的几次写入和读取会失败。单机 Redis 和 Sentinel 不受影响。CoCache 8.2.0 先连接每个主节点：`wow-cocache` 现在依赖它；自己声明了 CoCache 的 starter 和 `cocache-redis` 的应用，也把它们升到 8.2.0，修复就在其中。8.2.0 之下，解码为 `null` 的条目读取失败，而不再当作不存在；Wow 不写这样的条目。
- **十七个 `cosec.*` 键已移除**：各阶段的超时，现在是 `cosec.pipeline.timeout` 的份额；缓存、队列和快照的上限，现在是堆的份额。设了其中任何一个的服务或网关不会启动，并指出这个键和取代它的东西。Wow 一个也没设；把你的配置里设了的删掉。
- **转发头策略的键是 `server.request.header.forwarded.strategy`**，这是 Spring Boot 4.2 的键，其中 `native` 即 `web-server`。除非应用设了它或已弃用的 `server.forward-headers-strategy`，宿主把它设为 `web-server`；两个键都设时 Spring Boot 不启动（[记录客户端地址](./security#record-the-client-address)）。设了弃用键的应用，取值照旧生效，宿主的启动日志会指出应改用的键。
- **前面的 CoSec 部署。** 网关模式的 Wow 服务不持有文档、Redis 和控制面，所以除了版本号没有要改的。网关和控制面按 CoSec 的那一页升级：先升级每个控制面实例，再升级网关，网关的状态记录现在分成两部分；数据面的 Redis 用户需要新的 resident 键；网关保留多少文档取决于它的堆（[配置 CoSec 网关](./security#configure-the-cosec-gateway)）。
- **网关模式服务的测试。** 启动这种服务的测试，用 `cosec-testkit` 的 `IdentityHeader.ONLY_GATEWAY_CAN_REACH` 声明边界（`@SpringBootTest(properties = [IdentityHeader.ONLY_GATEWAY_CAN_REACH])`），不再自己写一份测试配置。
