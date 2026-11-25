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

**Java record 命令的聚合 ID。** 当 `@JsonProperty` 或 `@JsonNaming` 为记录的 `@AggregateId` 组件改名时，Manifest 的 `idProperty` 现在是这个名称，即 Jackson 读取的字段；此前它是组件的名称。由 Manifest 生成的客户端按 `idProperty` 指明聚合 ID：请重新生成。此前记录的基线写的是组件的名称，因此比较 `idProperty` 的检查会把聚合 ID 读作改由另一个字段给出，这是破坏性的改变。

**从这个版本起检查读法不同的改变。** 命令的聚合 ID 改由命令体的另一个字段给出，或不再由命令体给出（`idProperty`），会像删除字段一样让检查失败：客户端用这个字段指明聚合。与其他破坏性改变一样，它需要服务的下一个主版本，或者在旧命令旁边新增一条命令。检查不再把以下改变报告为破坏性的：重命名命令或事件并把旧名称（简单名或全名）保留为别名（新增性的）；把别名在简单名和全名之间改写（没有改变）；把多态字段改为可选（新增性的）。备选项有重叠且保持原样的 `anyOf` 或 `oneOf` 没有改变，而此前会把其中一个备选项报告为新增或破坏性的。Wow 预发布版记录的基线若以组件名称写着 Java record 命令的聚合 ID（见上一段：这个版本改为按 Jackson 写出的名称），会读作聚合 ID 改由另一个字段给出：运行一次 `./gradlew updateWowManifestBaseline -Pwow.manifest.allowBreaking`，它在兼容线内记录新的 `idProperty`，并记在 `build/wow/manifest-baseline.txt` 中，然后评审并提交基线（服务处于其主版本首个版本的预发布版，例如 `1.0.0-beta.1` 时，无需该标志即可记录）。

**与契约成员同名的聚合。** 名称的帕斯卡命名形式为 `NAME`、`Errors` 或 `Wait` 的聚合，在其上下文生成的契约中是 `<Name>Aggregate`，Kotlin 与 TypeScript 一致：名为 `errors` 的聚合是 `ErrorsAggregate`，名为 `wait` 的聚合是 `WaitAggregate`。聚合 `wait` 的 TypeScript 客户端读取 `<Context>Contract.WaitAggregate`，此前读取 `<Context>Contract.Wait`；在没有共享错误码的上下文中，聚合 `errors` 的 Kotlin 对端读取 `<Context>Contract.ErrorsAggregate`，此前读取 `<Context>Contract.Errors`：重新生成客户端，或重新构建 api 模块，并按编译器报告的位置改名。

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
- **路由。** Manifest 里按 ID 的路由除 ID 之外长于 512 个字符，或有一段长于 256 个字符时，构建会拒绝它，并指出这条路由和要缩短的名字。
- **Elasticsearch。** 文档的 ID 由租户和聚合 ID 组成。UTF-8 编码后至多 512 字节时，它保持原样，所以索引不需要重建。更长的（长租户之后的长 ID，以前的版本写不进去）由它的前缀加上整体的 SHA-256 组成。

## 不能改变的设置 {#settings-that-must-not-change}

`wow.log.partitions` 在日志有了条目之后就固定了，一个服务的节点共用它们的存储布局（[配置服务](./configuration#what-must-match-across-nodes)）。

## Jackson {#jackson}

Wow 的 JSON 运行在 Jackson 3.1 和 3.2 上，也就是它的测试运行的两个次版本。3.1 是 Spring Boot 4.2 管理的版本，在 Boot parent 之下的 Maven 构建和使用 Spring 依赖管理插件的 Gradle 构建解析到它。3.2 是用 `platform(...)` 引入 Boot BOM 的 Gradle 构建在类路径上有 springdoc 时解析到的版本，因为 swagger-core 3 要求它。在两者之一上、且 Jackson 的各模块版本一致时，不需要做任何事。

在其他 Jackson 上，Wow 在第一次读取之前就说明要改什么：

- **构建时。** `checkWowJackson` 属于 `check`：当 `jackson-core`、`jackson-databind` 或 `jackson-module-kotlin` 解析到 Wow 的 JSON 没有验证过的次版本，或分属两个次版本时，它会失败，写明各版本、是谁要求的，以及要改什么。它检查领域模块的测试类路径（`me.ahoo.wow`），以及服务的运行时与测试类路径（`me.ahoo.wow.service`）。
- **启动时。** Spring Boot 宿主在这样的 Jackson 上不会启动，并写明它找到的版本和要改什么。这覆盖 Maven 构建，以及不使用 Wow Gradle 插件的构建。宿主从每个模块的 jar 自带的 Maven 元数据（`META-INF/maven/.../pom.properties`）读取其版本，所以重新打包时去掉了这些元数据的 jar（shaded 或精简过的 jar）不会被看到：这样的构建依靠构建时的检查，或自己保证。

只有应用自己使用的 Jackson 模块（数据格式、数据类型）由应用自己保持一致：Wow 检查的是它自己的 JSON 运行所用的模块。

要在 Gradle 9 构建的每条类路径上只保留一个 Jackson，即 Spring Boot 的那个，就把 Jackson 的 BOM 放进一个依赖作用域（dependency scope），让每个可解析的配置都继承它，版本从 Boot 的 BOM 读出，而不是抄写：

```kotlin
// One Jackson, Spring Boot's, on every class path this build resolves. A dependency scope: nothing is published.
val bootBom = "org.springframework.boot:spring-boot-dependencies:4.2.0"
val jacksonPlatform = configurations.dependencyScope("jacksonPlatform") {
    withDependencies {
        val databind = "tools.jackson.core:jackson-databind"
        val bootJackson = project.configurations
            .detachedConfiguration(project.dependencies.platform(bootBom), project.dependencies.create(databind))
            .incoming.resolutionResult.allComponents.mapNotNull { it.moduleVersion }
            .single { "${it.group}:${it.name}" == databind }.version
        add(project.dependencies.enforcedPlatform("tools.jackson:jackson-bom:$bootJackson"))
    }
}
configurations.matching { it.isCanBeResolved && !it.isCanBeConsumed && it.name != jacksonPlatform.name }
    .configureEach { extendsFrom(jacksonPlatform.get()) }
```

依赖作用域永远不会被发布，所以模块的 POM 和 Gradle 元数据里不会出现 enforced platform。版本随 `bootBom` 变化，所以升级 Spring Boot 的那次更新会同时升级 Jackson。如果改为声明在 `implementation` 上，enforced platform 会随模块发布，Gradle 拒绝写出含有它的发布元数据；声明在 `testRuntimeClasspath` 上则立刻被拒绝，因为不能在可解析的配置上声明依赖。

## 主版本 {#a-major-version}

新的主版本可能改变格式和协议。它的节点不会与上一个主版本的节点并行运行：它附带一次迁移，在切换之前或切换期间运行，就像 V10 从 9.x 迁移那样（[从 9.x 迁移](../migrate/)）。

## 从 10.0.0 预发布版升级 {#from-a-10-0-0-pre-release}

10.0.0 的预发布版（`alpha`、`beta`、`rc`）对它存储的数据与它的 API 都不做兼容承诺。它的存储不会带过来：请在空的存储上启动本版本。预发布版写入的 MongoDB 日志没有按租户限定的访问键，所以在一次盖章重写这些流的属性之前，对 owner 或 space 的相等查不到它们。从 9.x 迁移的服务请按 [从 9.x 迁移](../migrate/) 进行。

`ddl: validate` 或 `ddl: none` 下的存储（`wow.ddl`，或某个存储的 `wow.stores.<name>.ddl`）会把每个日志或定时器的表或集合记录在登记表 `wow_log_aggregate` 或 `wow_timer_aggregate` 中，缺少这条记录就不再继续。在 PostgreSQL 上，`validate` 下账户无权插入缺失记录的节点在启动时被拒绝，并指明表、登记表和那一行；`none` 下，这张表第一次追加、读取或定时时同样被拒绝。在 MongoDB 上，这样的集合第一次追加或第一次定时会失败，并指明要插入的文档。预发布版则照常启动并丢失投递：别的节点的尾部读不到这张表，它的定时器也永远不会到期。为存储的账户授予两个登记表的 `INSERT`（MongoDB 上是 `insert` 和 `find`），或者插入拒绝信息指明的每条记录；由 `ddl: auto` 创建的 schema 已经有这些记录（[PostgreSQL](./postgresql#manage-the-schema)、[MongoDB](./mongodb#the-collections)）。MongoDB 存储还会在 `ddl: validate` 和 `ddl: auto` 下，把每个与 Wow 的索引同名的索引（`version_key`、`request_key`、`timer_due` 等）与本版本创建的定义比较：手工或由工具建立、键或键的顺序不同、缺少 `unique`、稀疏、部分过滤条件不同或带排序规则的索引会让启动失败，并指明每处差异。删掉它并以 `ddl: auto` 启动一次，或者按消息给出的定义创建它；删掉一个并不唯一的唯一键之前，先检查集合中被它放进来的重复项。

节点的端口监听在哪里、何时运行于开发模式，取决于你写下的配置，而不是机器（[配置服务](./configuration#development-and-production)）：

- **在开发 profile 中**（`wow.dev-profiles`），内部端口绑定 `127.0.0.1`，除非 `wow.internal.host` 或 `wow.node.address` 指定了地址。公开端口不变：它监听在 `server.address` 指定的地址上；从请求头读取调用方的节点，在这个地址不是回环网卡时会在启动时说明。在 Spring Boot 没有检测到云平台时把内部端口绑定到回环网卡的 profile（`spring.config.activate.on-cloud-platform: none` 加 `wow.internal.host: 127.0.0.1`）可以删去。需要从别的机器访问的开发节点要指定它的地址。
- **在开发 profile 之外**，内部端口和以前一样绑定本机名字解析到的地址，但这个地址是公网地址时节点拒绝启动：这个端口不要求任何凭据。用 `wow.internal.host` 指定内网地址；你指定的地址按原样使用。
- **本机名字解析到回环地址**（Debian 的 `/etc/hosts` 给出 `127.0.1.1`）不再使节点处于开发模式。这样的节点不在开发 profile 中就是部署：没有持久后端或没有 `CallerResolver` 时，它拒绝启动，而以前它会在内存中运行并以匿名调用方提供服务。指定 `wow.internal.host`，并给它后端和解析器，或者有意激活一个开发 profile。

节点只在 `wow.dev-profiles` 中的某个 profile（`dev`、`local`、`test`、`demo`）中才运行于开发模式。回环的 `wow.internal.host` 或 `wow.node.address` 不再让节点成为开发节点：这样的节点在没有 `CallerResolver` 时把公开端口上的每个请求都当作匿名调用方处理，没有数据库时退回内存，绑定在回环上、位于 sidecar 之后的部署也是如此。没有开发 profile 时，它现在拒绝任何部署都会拒绝的东西：没有持久后端、没有 `CallerResolver`、主题在内存中。依赖回环内部端口的本地运行改为激活 `dev`；部署要给节点提供数据库和 `CallerResolver`（或 CoSec）。没有开发 profile 的 `wowServer` 测试同样会被拒绝：给它 `"dev"`（`wowServer<App>("dev")`），或者部署需要的东西（显式指定 `wow.backend=memory`、一个 `CallerResolver`）。`wowServer(…, topics = …)` 需要开发 profile。

Wow 的运维命令（定时作业的命令、快照重建、对停放条目的操作及其读取）只有在某个授权器决定它们时，才在公开端口和运维路由上被允许（[授权命令](./security#authorize-commands)）。应用自己的 `CommandAuthorizer` 若是因为不反对而放行了它们，或在 `action.isOperational` 的 `when` 分支里裁决它们，现在会被拒绝（403 `wow.auth.forbidden`），宿主在启动时会说明。把这条规则移到它自己的 Bean：`CommandAuthorizer.operational { caller, action -> … }`，它只被问到这些命令；裁决它们的授权器类把 `decidesOperational` 覆写为 `true`。由 CoSec 决定运维人员的服务（`wow.cosec.authorizer.enabled`）无需改动。调用方的角色是 `caller.roles` 和 `caller.hasRole(…)`：`DevelopmentHeaders.ROLES_ATTRIBUTE` 和 `CoSecCallerResolver.ROLES` 已移除，由 `Caller.ROLES` 代替，测试中的调用方用 `Caller.of(…, roles = listOf(…))` 给出角色。`CommandGateway.authorizes` 改为 `decidesOperational`。

在由 CoSec 自己决定请求的服务中（`cosec.pipeline.mode` 为 `enforce`）设置了 `wow.cosec.authorizer.enabled: true` 的，CoSec 的授权器现在真正参与授权：预发布版本一个也没有注册，因为它们的条件在 CoSec 的管道定义检查器之前就查找它。从这个版本起，CoSec 的策略以请求的主体身份决定每条命令和每次服务路由调用（在受理时，无论它经哪条路到达命令网关），以及 Wow 的运维命令；ops 服务和数据目录的角色为它们让路，正如它们的页面所写（[ops 服务与控制台](./ops-console)、[数据目录控制台](./analytics-catalog-console)）。动作没有任何策略授予的命令或调用会被拒绝（403 `wow.auth.forbidden`），只靠角色放行而没有策略授予的运维人员同样会被拒绝。升级之前，在 CoSec 的文档中授予它们所需的动作（[动作名称](./security#action-names)）：命令的 `<context>:<command>`，作用于 `<aggregate>/<id>`；服务路由的 `<context>:<route>`，作用于 `route/<route>`；定时作业的 `<context>:wow.schedule.EnableJob` 及其他作业命令，作用于 `schedule/<job>`；快照重建的 `<context>:wow.snapshot.RebuildSnapshot`，作用于 `snapshot/<aggregate>`；以及 ops 服务的 `ops:Retry`、`ops:Skip`、`ops:MarkRecoverable` 和 `ops:ApplyRetrySpec`，作用于 `parked/<id>`，连同其查询路由的读取。位于 CoSec 网关之后的服务（`wow.cosec.mode: gateway`）没有 CoSec 检查器，不受影响；未打开该开关的服务也不受影响。

服务身份只在内部端口上被接受。应用自己的 `CallerResolver` 若为公开端口的请求返回了服务身份（`Caller.ofService` 或 `service = true`），这个请求现在在公开路由、运维路由和订阅路由上被拒绝（403 `wow.auth.forbidden`），而以前它会被当作服务信任：访问策略、运维授权器和揭示原值的读取都信任它。改为返回带角色的普通调用方（`Caller.of(subject = …, roles = listOf(…))`）；调用另一个服务的服务经它的内部端口访问，在那里声明自己（[关闭内部端口](./security#close-the-internal-port)）。

服务路由的请求体是 JSON，按命令请求体的读法读取（[JSON 值](../reference/json-values)），路由在其处理函数运行之前检查请求的类型（[服务路由](../guide/service-routes#bodies)）。以 `application/json` 或 `application/<name>+json` 之外的类型发送的请求体，例如 `text/plain`，答以 `415`；不指明 `Content-Type` 的请求体照旧读取。请求体中某个属性给了两次，或给了路由请求类型没有的属性，答以 `400 wow.route.malformed`，以前保留最后一个值或忽略该属性。`Accept` 不接受 JSON 的请求，例如只有 `text/html`，答以 `406`；通配符照旧以 JSON 答复。请检查各路由的客户端：最可能受影响的是把整个表单对象连同请求类型没有的字段一起提交的前端。让它只发送请求的属性，或者给请求类型加上它发送的属性。

命令的服务端推送事件流（`Accept: text/event-stream`）在命令被接受后即以 `200` 回答，计划中列出的每一跳一到达就是一个事件（[HTTP API](../reference/http)）。被接受之前的拒绝仍是原来的 problem。被接受之后，在计划列出的第一跳之前超时的等待不再答以 `504 wow.wait.timeout`：它是流的最后一个事件，以 `wow.wait.timeout` 在到达的最后一跳上 FAILED，命令尚未落定时是 `accepted`。被接受之后执行失败不再是 `503` 或 `500` 的 problem，也不再是已发出事件后被切断的连接：它是在 `committed` 上 FAILED 的最后一个事件，带 `wow.storage.unavailable` 或 `wow.command.unknown`，以同一个 `Wow-Request-Id` 重试是安全的。把 `200` 之外的状态码当作流的结果的客户端，改为读取最后一个事件的结果。流还会立即写出一条注释 `: keep-alive`，之后每 15 秒写一条，服务端推送事件的读取方会跳过它；最后一个结果之后，流以另一个事件 `end` `{"reason":"complete"}` 结束：把每个事件都当作结果的读取方要跳过 `end`，并把没有它就结束的流当作结果未知。服务和它命令流的读取方要一起升级：更早版本的客户端会把 `end` 读成结果（Kotlin 客户端把这个流作为 `wow.command.unknown` 失败，TypeScript 客户端把它作为最后一个结果交出），而这个版本的客户端对着更早的服务收不到 `end`，会把每个流都作为 `wow.command.unknown` 结束。

wow-generator 生成的 TypeScript 客户端按 Kotlin 契约的命名给错误码常量命名（[生成客户端](../typescript/generate)）。单词切分不同、本地名以数字开头，或不以所在上下文名开头的错误码，得到另一个常量：`invalidURLValue` 原来是 `INVALID_URLVALUE`，现在是 `INVALID_URL_VALUE`；分析目录的 `ANALYTICS_CATALOG_DEFINITION_INVALID` 现在是 `ANALYTICS__CATALOG__DEFINITION_INVALID`。领域命令 `Gateway` 由 `gateway` 发送，原来是 `gatewayCommand`；按无 id 发送的方法名被另一条命令的名字占用时，改名的是命令本身（加 `Command`），而不只是那个方法。重新生成客户端，并改掉代码引用的名字；把 problem 的 `code` 与错误码文本或 `<Context>ErrorCode` 比较的代码无需改动。

分析目录不再转发数据集行所引用服务的内部命令（`@InternalCommand`）：行不提供任何内部命令，发送内部命令的请求答以 `400 wow.command.unhandled`，以前目录会把它发往该服务的内部端口并被执行。直接调用内部命令在该服务的公开端口上是 `404`，转发也绕不过这一点。经由目录发送过内部命令的控制台或脚本，改为从拥有该命令的服务发送，或者把该命令改为公开命令。

列出了快照存储的预发布版，要在启动本版本之前修改配置。配置项 `wow.snapshot.stores`、`wow.snapshot.primary`，以及聚合的 `snapshot.stores` 和 `snapshot.primary` 已经移除，每一个都会拒绝启动，并指出它自己。用 `wow.snapshot.store` 或聚合的 `snapshot.store` 为每个类型指定它唯一的存储。保留了检索副本的部署（`snapshot.stores: [main, search]` 加 `query.snapshot.store: search`）去掉 `snapshot.stores`、保留 `query.snapshot.store`：副本的订阅名不变，仍是 `wow.snapshot:search`，所以它的索引和检查点都保留，也不会重新读取任何东西。

提供者的选项，无论在 `wow.<type>` 下（`wow.kafka.*`、`wow.mongo.*`、对等发现的、数据集后端的），还是在 `wow.stores.<name>` 下属于某个存储自己的，现在都按 Spring 绑定的每一种写法读取，与配置键检查的读法一致：环境变量（`WOW_KAFKA_COMMANDINTAKE_ENABLED`）或驼峰写法（`commandIntake.enabled`）与短横线写法的键含义相同，两者同时出现时按环境的顺序决定。预发布版本只按某个文件中的短横线键读取提供者的选项：没有文件设置其短横线写法的环境变量或驼峰键能通过启动检查，却不会被读取。这样的设置现在会生效；请检查部署以这种方式设置了什么。Wow 自带的 eShop 支付服务的 chart 设置了 `WOW_KAFKA_COMMANDINTAKE_ENABLED: "true"`，预发布版本忽略了它：它的命令接收现在会运行。

`spring.codec.max-in-memory-size` 不再设置内部端口的消息上限：Spring Boot 4 只绑定 `spring.http.codecs.max-in-memory-size`，两个端口都遵循它。

预发布版启动时接受的两个设置，现在会拒绝启动，并说出其配置项和界限。`wow.health.liveness.stall-timeout` 必须长于 `wow.cluster.reconcile-timeout`（默认 `1m`），这样卡住的节点会在其存活检查失败之前离开成员：*wow.health.liveness.stall-timeout is 1m, not longer than wow.cluster.reconcile-timeout.* `wow.query.dataset.max-concurrent` 必须为 `0` 或更大，而预发布版把负值当作不设界限运行：不设界限请设为 `0`（*wow.query.dataset.max-concurrent is -1, below 0.*）。

服务的构建现在可以渲染它的 Kubernetes 清单：`wow { kubernetes { values.from(…); namespace = … } }` 让 `generateWowManifests` 进入 `assemble`，它把插件自带的 `wow-service` chart 渲染成纯 YAML 的 Kustomize base，即 `build/wow/k8s/`，供 `kubectl apply -k` 使用（[配合 Kustomize](./kubernetes#with-kustomize)）。没有任何破坏：安装 chart 的部署无需改动，没有写 values 的构建什么也不渲染。写了 values 的构建第一次渲染时把 Helm v4.3.1 获取到 Gradle 用户目录；`wow.helm.mirror` 与 `wow.helm.executable` 供无法访问 `get.helm.sh` 的构建使用。原先用 `helm template` 手工渲染 chart 的部署，可以改为在构建里写明同样的 values 文件。

预发布版本拒绝把 Elasticsearch 存储作为聚合类型自己的快照存储；这个版本接受它（[Elasticsearch](./elasticsearch#only-snapshot-store)）。快照留在数据库、在 Elasticsearch 上另有副本的部署无需改动。想让类型的状态只存一份的部署，在类型的 `snapshot.store` 中指定 Elasticsearch 存储。这个存储随后从日志回填，期间该类型答 `rebuilding`（[重建与重放](./replay)）。

在一次处理中向同一目标发多条同类型命令的 Saga，要给每条一个 `CommandRequest.requestId`，即它所涉及的业务键，例如它预留的商品；按事件类型逐个处理的处理函数只在一次调用发多条时才需要键（[向同一目标发多条命令](../guide/sagas#several-commands-to-one-target)）。没有键时，这次处理作为 Saga 自身的错误失败，它的聚合停放，消息会指出处理函数、命令类型与目标。升级之前，先在本版本上运行领域的 `sagaSpec` 与 `scheduleSpec` 测试；但规格只有覆盖到发多条命令的那次处理时才会暴露它，而这可能取决于数据：eShop 的到货作业只有在某次触发读到同一商品的两次到货时（例如补跑错过的早晨），才会向同一库存发两条入库命令。用 `call` 发多条这类命令的处理函数，在第二条被拒绝之前已经发出了第一条，所以加上键之后，第一条会以它的键再执行一次：要在升级之前修好这样的 Saga，而不是等它停放之后。仍然沿用了存储的部署会发现，按事件类型逐个处理的处理函数派生的 ID 变了，所以升级前处理过、升级后又被重新投递的提交，会把它的命令再发送一次。

决策函数中保留下来的调用，若在命令按更新的状态再次决策时请求了别的东西（同一位置换了目标、命令类型或命令体），现在会让命令以 `wow.command.failed` 失败，过去它被答以第一次调用的提交：撤销这样的调用并改用 `scope.callId()`，或者给它一个业务请求 ID。在 `onAppendFailure` 中撤销 Wow 调用，或作废以 `scope.command.requestId` 为键的第三方动作的决策函数，要用 `scope.callId()` 作为这个动作的键：`CommandRequest.requestId = scope.callId()`，或第三方的幂等键。否则下一次尝试或调用方的重试会被答以已被撤销的动作（[撤销外部动作](../guide/aggregates#undo-an-outside-act)）。

本版本使用 springdoc-openapi 3.2.1，它基于 swagger-core 3.0 和 Jackson 3：

- **工件改名。** 应用自己声明了 swagger 工件的，要改名：`io.swagger.core.v3:swagger-annotations-jakarta`、`swagger-models-jakarta` 和 `swagger-core-jakarta` 改为 `swagger-annotations`、`swagger-models` 和 `swagger-core`，版本 3.0.0，由 springdoc 的 BOM 管理。修改过 `Json.mapper()` 或 `Json31.mapper()` 的代码，改用 `ObjectMapperFactory.addCustomizer` 定制 swagger 的 mapper：Jackson 3 的 mapper 不可变。
- **应用自己的 schema。** springdoc 从应用的类推导出的 schema 可能有变化：以缩写开头的 getter 保留其大小写（`getETag()` 是 `ETag`，不再是 `etag`）；Kotlin 类的非空属性列为 `required`；唯一公开构造函数的、不对应任何属性的参数，成为 `writeOnly` 属性。由 `/v3/api-docs` 生成的客户端会看到这些变化。Wow 路由的文档不变，`/v3/api-docs` 中和 `GET /wow/openapi` 中都一样。
- **Jackson。** swagger-core 3 要求 Jackson 3.2，高于 Spring Boot 4.2.0 管理的 3.1；用 `platform(...)` 引入 Boot BOM 的 Gradle 构建，在类路径上有 springdoc 的地方会解析为 3.2。Wow 的 JSON 在两者上都能运行；[Jackson](#jackson) 说明构建和启动时检查什么，以及如何只保留一个 Jackson。

本版本使用 CoSec 8.0.0-beta.9 和 CoCache 8.2.0。从 8.0.0-beta.8 升级，全部变化见 CoSec 的[升级：从 8.0.0-beta.8](https://cosec.ahoo.me/operations/upgrading-previews#from-8-0-0-beta-8)；Wow 的部署会遇到的是：

- **先检查已存储的模式。** `[ ] ( ) | ! ^ $ \` 在 CoSec 的文档、路由映射和变更的每个模式中都是保留字符，用反斜杠把它写作字面字符：`http:path:/odata/\$metadata`。升级一个环境之前，趁它还在运行 8.0.0-beta.8，用 [8.0.0-beta.9 发布包](https://github.com/Ahoo-Wang/CoSec/releases/tag/v8.0.0-beta.9)里的 `cosec-migrate check-patterns --to-redis <uri>` 检查它的 CoSec 存储。它只读不写：指出已发布文档和未关闭变更中，本版本拒绝或会读出另一种含义的每个模式，并说明升级前和升级后各要发布什么。留有未转义保留字符的模式会让升级后的实例整份文档失效，读这份文档的每个请求都回答 503；含反斜杠的模式能编译，但含义变了。自己做决定的服务加载 Wow 依据它的 Manifest 生成的路由映射：上下文的名字和服务路由的路径段都不能含这些字符，Wow 自己的路由也一个都没有。
- **先控制面，再服务，最后网关。** 按 CoSec 给出的顺序升级，每一步完成后再做下一步：
  1. 控制面宿主，以及内嵌控制面的 IAM 服务。
  2. 签发 CoSec 令牌的服务（它的所有副本一起），以及每个网关模式（`wow.cosec.mode: gateway`）的 Wow 服务。
  3. CoSec 网关的镜像，升级到 8.0.0-beta.9，以及其他每个自己校验 CoSec 令牌的宿主，其中包括自己做决定（`cosec.pipeline.mode` 为 `enforce`）的 Wow 服务。

  8.0.0-beta.9 的网关在 `CoSec-Identity` 中写入一个新成员 `auth.kind`，使用 8.0.0-beta.8 的服务对它回答 400 `invalid-request`，所以网关模式的服务要先于网关升级，与升级到 8.0.0-beta.8 时一样。本版本的服务无论有没有这个成员都能读取这个请求头，并且现在会忽略它不认识的成员，除非请求头的 `crit` 指名了它，所以以后的新成员不再需要这样的顺序。
- **两步之间。** 在 8.0.0-beta.8 网关之后的人，或在第 2 步之前登录的人，会被升级后的宿主读作工作负载：请求照旧决定，但批准变更和紧急授权（break-glass）要等网关升级、此人重新登录之后才行。升级前已批准的变更，升级后要重新批准。Wow 不依据凭据属于谁做任何决定，它的调用方也不记载这一点。
- **每个凭据都说明它属于谁。** CoSec 的 `Identity.Credential` 在方法之后多了一个必填的 `kind`（`Identity.Credential.Kind.PERSON` 或 `WORKLOAD`），登录的 `TokenSubject` 在 `id` 之后、校验器的 `VerifiedToken` 在 `app` 之后也一样：你自己的 `Authenticator`、登录或 `TokenVerifier` 不说明是哪一种就编译不过。用户的登录属于人；机器出示的任何凭据属于工作负载。现在决定谁可以批准变更、成为控制面的信任根或使用紧急授权的，是这个 kind，而不是方法的名字。你自己的校验器还要拒绝 JWS `crit` 头里指名了它不理解的东西的令牌（`TokenNotUnderstoodException`，回答 503）。Wow 不构造这些对象。
- **自己写身份或签令牌的测试。** `wow-cosec` 的 `CoSecIdentity` 写入 `auth.kind` 为 `person`，`cosec-testkit` 的 `IdentityHeader.forSubject` 写人的、`forWorkload` 写工作负载的。自己写这个请求头的测试也要写 `auth.kind`，测试为网关签的令牌要带声明 `cosec.kind`：缺了它们，人会被读作工作负载，而且什么都不会失败。请求头里拼错的成员现在被当作不存在，而不再被拒绝。
- **`@ahoo-wang/cosec-fetcher` 8.0.0-beta.9。** 使用 CoSec 客户端的前端换到 `@beta`。刷新、注销或切换租户现在只发往客户端信任的端点（`isTrusted`，相对 Fetcher 的 `baseURL` 解析），并且不跟随重定向。通过一个 `baseURL` 就是认证服务的独立 Fetcher 发送的，如[认证](../typescript/authentication)的配置，不受影响；在已配置的 Fetcher 上发往另一个源的，需要把那个源加入 `isTrusted`。Wow 的控制台不做刷新，不受影响。
- **网关模式服务的请求头上限仍是 8 KB。** CoSec 现在为读取 `CoSec-Identity` 的宿主推导 `server.max-http-request-header-size`（16 KB），并在启动时说明，但网关模式来自 `wow.cosec.mode` 的 Wow 服务得不到它：CoSec 在 Wow 设置模式之前就做了决定，所以服务器保持 Spring Boot 的 8 KB，与本版本之前相同，尽管启动日志说推导了 16 KB。请求头合计超过 8 KB 的请求回答 431；本版本的网关最多写 6 KB 的身份。CoSec 的候选发布版会修复它。遇到这种情况的服务设置 `server.max-http-request-header-size: 16KB`，修复之后照样有效。

CoSec 8.0.0-beta.9 的其他变化涉及网关（默认最多写 6 KB 的身份，原来是 8 KB）、控制面和签发 CoSec 令牌的应用：这些按 CoSec 的那一页处理。从 8.0.0-beta.7 升级，全部变化见 CoSec 的[升级：从 8.0.0-beta.7](https://cosec.ahoo.me/operations/upgrading-previews#from-8-0-0-beta-7)；Wow 的部署会遇到的是：

- **先升级服务，再升级网关。** 先把每个网关模式（`wow.cosec.mode: gateway`）的 Wow 服务升级到本版本，之后再升级 CoSec 网关的镜像。8.0.0-beta.8 的网关总是在 `CoSec-Identity` 中写入一个新成员 `memberships`。使用 CoSec 8.0.0-beta.7 的服务严格读取这个请求头，对不认识的成员回答 400 `invalid-request`，所以在新网关之后，每个携带身份的请求都会失败，直到它的服务升级为止。本版本的服务无论有没有 `memberships` 都能读取这个请求头。控制面和其他依据转发身份做决定的宿主按 CoSec 的那一页升级。
- **主体的标识从不为空白。** CoSec 在服务的任何路由运行之前，就拒绝指明空标识或纯空白标识的凭据：`sub` 为空白的 `CoSec-Identity` 回答 400 `invalid-request`；你自己的认证器若用这样的标识构造 `PrincipalId`，它的请求以 503 失败，应改为回答 `Rejected`。升级之前，按 CoSec 那一页所示，找出空白主体的 API key 和绑定。
- **路由不带结尾的 `/`。** 自己做决定的服务（`cosec.pipeline.mode` 为 `enforce`）按 Manifest 的路由映射请求，这些路由没有一个以 `/` 结尾。对路径是某条路由加上一个 `/` 的请求，如 `POST /shop/cart/open-cart/`，CoSec 现在回答 400 `invalid-request`，而以前把它当作 `http` 动作来决定。每个路径都按 Manifest 写的那样发送，Wow 的客户端就是这样做的。网关模式服务前面的 CoSec 网关不加载路由映射，所以这一条不适用于它；本版本对网关自己的 DENY 规则和限流规则中以 `/` 结尾的路径做了什么改变，见 [CoSec 的那一页](https://cosec.ahoo.me/operations/upgrading-previews#from-8-0-0-beta-7)。
- **CoSec 开启时不能没有它的关卡。** 开启了 CoSec（`cosec.enabled`）的节点，若排除了 CoSec 的 `CoSecPipelineWebAutoConfiguration`，或用另一种类型的过滤器取代了它的过滤器，就不再启动。保留这个自动配置，或自己注册 CoSec 的过滤器，或以 `wow.cosec.mode: disabled` 运行节点以关闭 CoSec，Wow 的服务不带 `cosec` profile 时就是这样运行的。
- **自己构造 CoSec `Identity` 的测试。** 它的构造器新增了 `memberships`，在最后且有默认值，所以这样的测试针对本版本重新构建后无须改动即可编译。`IdentityHeader.forSubject` 把 `memberships` 写为空；`wow-cosec` 的 `CoSecIdentity` 不写它，网关模式的服务把这读作没有其他成员资格。

CoSec 8.0.0-beta.8 的其他变化涉及网关、控制面、签发 CoSec 令牌的应用，以及读取 CoSec Redis 的宿主的 `cosec.*` 设置，自己做决定的服务就是这样的宿主：这些按 CoSec 的那一页处理。Wow 不签发令牌，不调用控制面，也不设置那些键。从 8.0.0-beta.6 升级再无须做任何事；从 beta.5 升级，全部变化见 CoSec 的[升级：从 8.0.0-beta.5](https://cosec.ahoo.me/operations/upgrading-previews#from-8-0-0-beta-5)；Wow 的部署会遇到的是：

- **CoCache 8.1.0 在 Redis Cluster 上的缺陷。** Wow 10.0.0-beta.13 到 beta.16 运行 Spring Boot 4.2，依赖 CoCache 8.1.0。在 Spring Boot 4.2 的 Lettuce 之下，CoCache 8.1.0 的 Redis 后端（`cocache-redis`）连接 Redis Cluster 时，拒绝发往每个主节点的第一条命令：`Currently not connected. Commands are rejected.` 通过 `wow-cocache`（[CoCache](../guide/extensions/cocache)）在 Redis Cluster 上共享状态的服务，缓存最初的几次写入和读取会失败。单机 Redis 和 Sentinel 不受影响。CoCache 8.2.0 先连接每个主节点：`wow-cocache` 现在依赖它；自己声明了 CoCache 的 starter 和 `cocache-redis` 的应用，也把它们升到 8.2.0，修复就在其中。8.2.0 之下，解码为 `null` 的条目读取失败，而不再当作不存在；Wow 不写这样的条目。
- **十七个 `cosec.*` 键已移除**：各阶段的超时，现在是 `cosec.pipeline.timeout` 的份额；缓存、队列和快照的上限，现在是堆的份额。设了其中任何一个的服务或网关不会启动，并指出这个键和取代它的东西。Wow 一个也没设；把你的配置里设了的删掉。
- **转发头策略的键是 `server.request.header.forwarded.strategy`**，这是 Spring Boot 4.2 的键，其中 `native` 即 `web-server`。除非应用设了它或已弃用的 `server.forward-headers-strategy`，宿主把它设为 `web-server`；两个键都设时 Spring Boot 不启动（[记录客户端地址](./security#record-the-client-address)）。设了弃用键的应用，取值照旧生效，宿主的启动日志会指出应改用的键。
- **前面的 CoSec 部署。** 网关模式的 Wow 服务不持有文档、Redis 和控制面，所以除了版本号没有要改的。网关和控制面按 CoSec 的那一页升级：先升级每个控制面实例，再升级网关，网关的状态记录现在分成两部分；数据面的 Redis 用户需要新的 resident 键；网关保留多少文档取决于它的堆（[配置 CoSec 网关](./security#configure-the-cosec-gateway)）。
- **网关模式服务的测试。** 启动这种服务的测试，用 `cosec-testkit` 的 `IdentityHeader.ONLY_GATEWAY_CAN_REACH` 声明边界（`@SpringBootTest(properties = [IdentityHeader.ONLY_GATEWAY_CAN_REACH])`），不再自己写一份测试配置。接受属性表的启动方式，如 `wow-test-spring-boot` 的 `wowServer`，从 CoSec 8.0.0-beta.7 起只取它的键：`mapOf(IdentityHeader.ONLY_GATEWAY_CAN_REACH_KEY to "true")`。

失败账本的停放条目改用有界的形式命名和访问，所以任何可能存在的聚合的条目，ops 服务都记录得下，运维人员也都能经网关访问到（[运维控制台](./ops-console)）：

- **ops 服务的 `parked` 聚合以哈希命名。** 条目的 id 是 `p` 加 32 位十六进制数字，即它的服务、订阅与聚合 SHA-256 的前 128 位，原先这些字段是拼接起来的。服务、订阅、租户与 id 仍是条目的字段，与以前一样。ops 服务只在字段算出的那个 id 之下记录条目。本版本的 ops 服务从空库开始，和预发布版本的所有存储一样：记录在旧 id 之下的条目会一直未决，因为服务之后的记录都去了新 id。各服务自己的账本保留着条目，下一次扫描会重新发布它们。
- **服务的运维路由在请求体里指明条目。** 每条都是 `/wow/ops/parked` 之下、路径不含数据的 `POST`：`list`（请求体里是 `subscription`、`after`、`limit`）、`entry`、`retry`、`skip`、`mark-recoverable` 与 `apply-retry-spec`，条目由 `subscription`、`context`、`aggregate`、`tenantId` 与 `id` 指明。拼过 `GET /wow/ops/parked?…`、`GET /wow/ops/parked/{subscription}/{context}/{aggregate}/{tenant}/{id}` 或其 `POST …/retry` 路径的脚本、Webhook 接收方或网关策略，都改用这些路由。
- **`Ledger.parkedId` 返回哈希**，签名不变；解析过它旧形式的代码，改读条目的字段。

所属服务按操作所指的那一次停放答复运维人员的操作：对它已不再持有的停放所做的重试、跳过、标记或重试规格，只对那一次停放发布为已解决，所以 ops 服务让更晚的停放保持未解决。让条目保持原样的答复（例如再次应用未变的重试规格）现在会结束 ops 控制台显示为待处理的请求。修复在两端：仍在旧运行时上的服务会发布不带日期的解决结果，并复用条目的请求 ID，所以除了 ops 服务，也要升级发布台账的各个服务（[ops 服务与控制台](./ops-console)）。

本版本可以把 CoSec 的拒绝写成 Wow 的 problem，默认关闭：`wow.cosec.problems: wow`（[CoSec 的拒绝](./security#cosec-refusals)）。保持 `cosec` 时，每个回答与以前逐字节相同。它基于 CoSec 的 `ProblemRenderer`，那在 CoSec 8 里是试验性的，形状可能随 CoSec 的 RC 改变。新增两个码：`wow.request.rate-limited`（429）与 `wow.auth.unavailable`（503），只有这些拒绝会用到它们。

`wow-service` chart 拒绝更多预发布版能渲染的 value（[Kubernetes](./kubernetes)）。chart 自己设置的键，在 `settings` 和 `env` 中也会被拒绝，就像在 `config` 中一样，Spring 能绑定的任何写法都算：停止的上限（`wow.wait.max-timeout`、`wow.cluster.drain-timeout`、`wow.subscription.stop-timeout`：设置 `shutdown.*`）、端口（`server.port`、`wow.internal.port`：设置 `ports.*`）、节点的身份和地址、`POD_NAME` 和 `POD_IP`，以及 `env` 中的 `SPRING_APPLICATION_JSON`。开启 `networkPolicy.public.onlyGateway`（默认）时，`networkPolicy.public.from` 中不按标签指明 Pod 的条目（`podSelector` 为空或没有、`ipBlock`）会被拒绝：指明网关的 Pod，或设置 `onlyGateway: false`。生成的 values 把代码调用的对端写作 `service.contractPeers`，chart 按服务把你的 `service.peers` 与它们合并：重新生成 values（`generateWowHelmValues`，`assemble` 会运行它），你的文件给某个对端写了命名空间时，内部策略也会重新放行每个对端。预发布版生成的、带 `service.peers` 的 values 文件仍能渲染，按部署自己的对端来读。

`wow-service` chart 的 `autoscaling.keda.minReplicas` 至少是 1，和 HPA 的一样：设为 0 的 values 文件会被 schema 拒绝。ScaledObject 唯一的触发器是服务自己的 Pod 上报的积压，所以被 KEDA 缩到零的服务再也回不来（[Kubernetes](./kubernetes)）。

`@ahoo-wang/wow-client` 以命令声明的等待再加 5 秒为界约束它的整个应答，与 Kotlin 客户端一样；没有声明的命令以客户端选项的 `defaultWaitMs` 再加 5 秒为界，没有给出时是一分钟再加 5 秒（[命令](../typescript/commands#when-a-command-fails)）：服务默认等待超过一分钟的部署，设置 `defaultWaitMs`，或者让每条命令声明它的等待。超过这个界限，请求被中止，调用以 `wow.command.unknown` reject，过去一个沉默的节点会让调用一直挂着。应答或事件流在开始之后丢失或无法读取的命令也以同样的方式 reject，过去它以网络自己的错误 reject：只对 `retry` 允许重试的 `WowProblemError` 重试的调用方，现在也会以请求 ID 重试这些情况，这是安全的。对聚合 `.` 或 `..` 的命令或状态读取（以及空 id 的状态读取）在发送之前就以 `TypeError` 拒绝，过去 URL 会把它送到另一个路由；续读查询流时得到的 `Retry-After` 超过 `resume.maxDelayMs`（默认 5 秒）时，流立即以那个 problem 失败，而不是等它过去。

`@ahoo-wang/wow-react` 的 `useCursorQuery` 只把某一页的游标交给答出那一页的查询与请求选项（[React](../typescript/react)）：`autoExecute: false` 时，查询或请求选项变了之后的 `fetchNext()` 读取当前查询的第一页；过去它从旧查询的游标继续读，并把那一页追加到旧的行后面。`useStreamQuery` 的 `abort()` 保留中止前读到的每一行和最后一行的位置；过去它只保留已经显示出来的行。

`@ahoo-wang/wow-view-store` 的 `WowViewStore` 对小于 1 或不是整数的 `pageSize` 抛出 `RangeError`，过去这样的存储会无止境地列出视图。它还从一次写入第一次尝试发出的地方发出它的重试，所以视图移走之后，已经落地的保存会被当作重复请求来回答。改过又改回的存储系统视图，按它现在的版本写入，过去答以 `CONFLICT`。对它从未发出过的修订写入偏好，答以 `CONFLICT`，过去答以 `UNAVAILABLE`。

视图存储服务拒绝没有 `view-store.shared-writer` 的用户共享个人视图（`ShareView`），答以 `403 wow.auth.forbidden`；过去它接受任何用户的共享，并把视图发布到租户的共享列表（[存储](../typescript/view-engine/storage)）。创建共享视图和认领共享视图原本就需要这个角色。把它授予要共享视图的人，并按它回答前端的 `permissions.changeAudience`，如 `@ahoo-wang/wow-view-store` 的 README 所说。

### Kafka 上的发布流 {#published-streams-on-kafka}

发布流的记录现在发往它的聚合在上下文日志中的分区，并在 `wow-partitions` 头中注明日志的分区数（[分区](./kafka#partitions)）。预发布版按键的哈希把它放在主题的各分区之间。发布的节点在它的某个主题的分区少于 `wow.log.partitions` 时也拒绝启动，无论 `wow.kafka.ddl` 怎么设。

升级前后，一个聚合的下一条记录可能落在与它之前的记录不同的分区。订阅者于是可能先于一条它尚未读到的较早记录处理它，或越过一条它停放着的记录处理它：停放聚合的分区只拦住放在该分区的记录。Wow 无从分辨，因为版本号的空缺不是证据：一次所有事件都已退役的提交没有记录。对每个在升级中保留其主题的发布服务：

1. 停止发往该服务的命令，或停止它的节点。
2. 等到每个消费服务中读取其主题的每个订阅都已追上，且没有任何停放：对主题的每个分区，`wow.subscription.backlog` 为 0，`wow.subscription.progress` 为 1，并且 `wow.subscription.parked.aggregates` 为 0；[ops 服务与控制台](./ops-console)显示的也一样。先重试或跳过每个停放的条目（[失败处理](../guide/failures)）。
3. 把它分区少于 `wow.log.partitions` 的主题增加到这个数：`kafka-topics.sh --alter --topic <topic> --partitions <count>`。这期间没有发布，所以没有记录移动。
4. 升级该服务。

主题与存储一样随本版本新建的部署无需做任何事。

### 从 9.x 迁移 {#a-migration-from-9-x}

migrate-data 的 `compare` 和 `export` 发现差异时以 `4` 退出（以前是 `3`），`3` 现在是 JVM 因 `OutOfMemoryError` 退出时的退出码。把 `3` 读作差异的脚本必须改读 `4`；从之前的 `deploy/migrate-data/job/job.yaml` 复制的 Job 清单必须在 `1`、`2`、`4` 时立即失败，而不是 `1`、`2`、`3`：这个列表随镜像而定。migrate-data 在某个存储或网络不可用或超时时还会以 `5` 退出（以前是 `1`）。配方中的 Job 对每个退出码的处理如下（[migrate-data CLI](../reference/migrate-data#exit-codes)）：

| 退出码 | 含义 | Job |
| --- | --- | --- |
| `0` | 完成。 | 完成。 |
| `1` | 失败：工具的拒绝、读不了的配置，或缺陷。 | 立即失败。 |
| `2` | 用法错误。 | 立即失败。 |
| `3` | JVM 因 `OutOfMemoryError` 退出（镜像设置的 `-XX:+ExitOnOutOfMemoryError`）。 | 从检查点重新运行。 |
| `4` | `compare` 或 `export` 发现了差异。 | 立即失败。 |
| `5` | 某个存储或网络不可用或超时。 | 从检查点重新运行。 |

`CUTOVER` 行给出导入在哪里结束：什么也没导入的一轮让切换点保持原处，而不是把它移到日志的头部。

已迁移服务自身上下文的 Saga 或 Reactor 无论怎样启动，都会略过其日志中切换点之前导入的历史，而不只是在首次从日志末尾启动时如此，正如它略过对端发布为已导入的记录；投影仍从中构建，补偿对已导入条目的停放重试仍会处理。首次切换看不到任何差别。在通过导出回退之后再次切换时，第二次导入追加的历史只由 9.x 的 Saga 处理：为回退而停止 V10 之前，让每个订阅的积压（`wow.subscription.backlog`）降到零，包括服务自身的 Saga（[切换](../migrate/cutover)）。

### 查询 {#queries}

内存查询参考实现和测试 DSL 的 `memoryQueries` 按码点给文本排序，与 PostgreSQL 和 MongoDB 一致。文本中混有 U+E000..U+FFFF 的字符和 U+FFFF 以上的字符时，按它排序的列表或分页、对它的范围（`gt`、`gte`、`lt`、`lte`、`between`）以及按文本指标排序的聚合，会以另一种顺序回答：升序时 `Ａ` 现在排在 `😀` 前面。断言旧顺序的规格要改为期望新的顺序。在 Elasticsearch 上，按文本指标取前 N（对文本的 FIRST、LAST 或 ANY 排序并带 `limit`）保留的分组与 PostgreSQL 和 MongoDB 相同。

忽略大小写的过滤条件在每个后端都按 Unicode 简单大小写折叠比较两边的文本（[查询语言](../reference/query-language)）：`ſ` 包含 `s`，`λόγος` 包含 `σ`，`5µm` 包含 `μ`，而在 PostgreSQL 和 Elasticsearch 上 `İstanbul` 不再以 `is` 开头。原来只折叠 ASCII 的 `C` locale PostgreSQL 数据库会折叠所有字母，土耳其语 locale 的数据库不再把 `I` 与 `ı` 折叠。在 MongoDB 上，模式超过 32,764 字节的 `value` 答以 `storage-unsupported`，过去是服务器报错。测试 DSL 的 `memoryQueries` 与参考实现一致。

内存查询参考实现（`memoryQueries` 规格和使用内存存储的节点读取的就是它）现在拒绝两种原来会回答的查询，与 PostgreSQL 和 MongoDB 的拒绝一致（[查询语言](../reference/query-language#the-capability-descriptor)）。按日期字符串比较、排序或分组，例如 `state.placedAt > "2026-01-01T00:00:00Z"`、按 `Instant` 排序，或基于它的日期直方图，得到 `unsupported-capability`：改为按纪元时间字段（支持所有能力的数字）或按系统时间字段比较、排序或分组。按两个相互独立的数组字段排序，得到 `parallel-array-sort`：只按其中一个排序。依赖其中任何一种的规格，以前在它的节点失败的地方通过；现在它像节点一样失败。普通字段的 `sum`、`avg`、`min` 和 `max` 在这里按存储的十进制数计算：0.1 与 0.2 之和是 `0.3`，而不是 `0.30000000000000004`；把存储的 `30` 之和与双精度的文本 `30.0` 比较的规格，现在看到的是 `30`。

**PostgreSQL 上的计算值也是双精度。** `expression` 过滤、基于表达式的指标或分组以及派生指标都按 `float8` 计算，与 MongoDB、Elasticsearch、ClickHouse 和内存后端一致。处在十进制数边缘的比较可能与以前的答案不同：`amount * 3 > 0.3` 会匹配 `amount` 为 0.1 的记录。需要精确的地方（如金额），比较或聚合存储的字段。超出双精度范围的计算值以 `400 wow.query.out-of-range` 拒绝。

### 测试工具 {#the-test-kit}

用测试 DSL 编写的规格会按节点的处理方式检查命令。请针对这个版本重新构建测试：`aggregateSpec` 和 `aggregateSpecTests` 新增了 `policy` 参数。
- **为规格给出聚合的访问策略**，只要服务为该聚合注册了策略：`aggregateSpec(model, caller = …, policy = …)`。这时命令按其调用方的声明来决定。
- **指定了 `scope` 的命令要由该作用域中的调用方发出**：`caller = Caller.owner("alice")`，或 `Caller.of(scope = …, subject = …)`。没有调用方的命令属于匿名调用方，网关不允许它指定作用域。
- **超过长度限制的 ID 按格式错误失败**，即长度超过 256 个字符的请求 ID、租户、所有者或空间的 ID。
- **Saga 规格像节点那样为定时的命令命名**：按目标的模型（用 `givenAggregate` 给出），或测试类路径上目标的契约。若规格向两者都没有描述的目标定时发送命令，就给出其中之一。

测试用的假对端（`fakePeer`，`wow-test-spring-boot`）像真实对端一样回答调用：被调用方的 `wow.callers.<service>.commands` 不允许的命令回答 403 `wow.call.command-forbidden`，不给时所有系统命令（`wow.*`）都被拒绝；不是 JSON 或读不成命令的命令体回答 400 `wow.command.malformed`。Saga 调用对端系统命令的测试要写明对端的许可：`fakePeer(context, callers = mapOf("<调用方服务>" to listOf("wow.lifecycle.DeleteAggregate")))`，与对端的配置必须写明一样。`fakePeer` 以带默认值的第二个参数接收这个映射，Kotlin 源码无需改动即可编译；按旧签名编译的代码需要重新编译。

用测试 DSL 编写的规格现在像节点那样发送并结算定时器。定时的命令在为它定时的那次提交的操作人和作用域中执行，且只带派生命令会继承的请求事实。决策抛出异常的定时命令会让定时器保持待处理，十次尝试后停放，而不再是已触发。若一个聚合的规格中的决策为同一上下文中另一个聚合定时命令，规格会在 `advanceTime` 时失败：请在那个聚合自己的规格中测试这条命令。下游一直施加背压的处理会在第二十次尝试时停放。无论服务如何配置，规格都使用节点的默认重试。

用 `context.ids` 中的 ID 为要创建的聚合命名的 Saga，在提交每次被再处理时（重试或重新投递之后）都会再创建一个聚合，因为派生请求的 ID 取自目标的 ID。改为发出不带 ID 的创建请求：目标的准入会把第一次尝试创建的聚合交给它。在 `sagaSpec` 中，`expectDeterministic()` 和 `whenRedelivered()` 现在像节点一样生成新的 ID，因此这样的 Saga 无法通过它们。原本期望第一次之后的处理再次得到 `id-1` 或 `<aggregate>-1` 的规格，现在会得到下一个 ID。

针对这个版本重新构建服务的代码：编译器生成的绑定现在会传入每个处理函数所绑定的上下文（`ProcessorBinding.saga`、`.project` 和 `.react` 接受 `context`）。手写的绑定只要使用具名参数，不改也能编译，也可以给出它的 `context`。用测试 DSL 编写的规格随节点一起改变：
- 处理函数读到的状态从 JSON 解码而来，各自一份，并处于它所绑定的上下文中；
- 被拒绝的回执带着命令的作用域；
- 同步调用的补偿被拒绝时会停放；
- 另一个上下文的命令的回执不再按 Saga 的 `@Retry` 重试；
- 应答客户端的 `stream` 会发出被拒绝和失败的结果。

领域、处理器和服务测试所用的内存日志，以及宿主的内存存储，现在拒绝 PostgreSQL 和 MongoDB 拒绝的内容。版本有间隔或跨两个上下文的组（`appendAll`）在写入任何内容之前抛出持久日志抛出的 `IllegalArgumentException`，过去它的前几个流已提交、之后才是冲突；宿主的内存存储拒绝在 `openLog(context)` 给出的日志上追加别的上下文的流，过去会提交它。这样的测试在生产中本来就会遇到这个异常：每个日志只追加一个上下文，组中同一聚合的版本要连续。
