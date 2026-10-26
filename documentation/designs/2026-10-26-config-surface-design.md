# 配置面定稿（R5 批 4）

日期：2026-10-26。基线：`origin/main`（安全讨论 D295～D302 之后）。状态：**已采纳**（2026-10-26，用户按推荐定 CQ1～CQ5，并定配置原则「低维护成本，能自动的尽可能自动」，即 D303）。范围：R5.C-3、B-8、C-16、C-12、B-5 的配置部分、B-11，以及安全讨论的 D300（删除令牌键）、D302（未知键一律拒绝）。

上位文档：[R5 设计](2026-10-26-r5-ga-architecture-review-design.md) §3.5、[安全讨论](2026-10-26-security-review-design.md)、[决策台账](decisions.md)（D288、D299、D300、D302）。

## 1. 为什么一次定稿

配置键是 GA 后按破坏性变更管理的契约。分几次改，用户的配置就要跟着改几次。D302 之后未知键会拒绝启动，所以改名后，旧键在启动时就会报出来，不会静默失效。这正是一次定稿、不留别名（D1）的前提。

现状：元数据里有 240 个键，另有约 60 个映射子键与平台键。清单来自对代码的逐项盘点（`WowProperties`、`QueryProperties`、`KafkaProperties`、`ClickHouseProperties`、`ObservabilityProperties` 与直接读 `Environment` 的地方）。

## 2. 命名规则（定稿后由测试守住）

| 编号 | 规则 | 例 |
| --- | --- | --- |
| K1 | **一个概念一个键、一种类型、一个读者**：键只由属性 bean 绑定，模块与平台服务读同一个 bean，不再 `Environment.getProperty` 加一份默认值 | `wow.cosec.*` 现有两个读者，`wow.tenant.default` 有 6 个 |
| K2 | **覆盖链**：全局键 → `wow.stores.<store>.<同名叶子>` → `wow.aggregates.[<ctx>.<agg>].<同名叶子>`，叶子名逐级相同 | `wow.ddl` → `wow.stores.pg.ddl` |
| K3 | **设置组用单数，按名字的映射用复数** | `wow.subscription.*`（默认）与 `wow.subscriptions.[name].*`；`wow.peer.*` 与 `wow.peers.<ctx>.*` |
| K4 | **功能开关以 `.enabled` 结尾；策略开关用动词短语**（`require-*`、`allow-*`、`trust-*`） | `wow.http.client-facts.enabled`、`wow.query.require-authenticated-scope` |
| K5 | **重试统一用语**：`max-attempts`（含第一次）、`initial-backoff`、`max-backoff`、`multiplier`、`jitter` | `wow.peer.retry.initial-backoff`（原 `backoff`） |
| K6 | **后端专有的键只在后端自己的组里**：全局默认 `wow.<backend>.*`，单个存储 `wow.stores.<store>.options.<同名叶子>`，只作用于该类型的存储 | Mongo 读偏好不在 `wow.query.*` 里 |
| K7 | **已实测、默认开启的优化不留总开关**；只保留有部署差异的容量参数，内部常量不做成键（D299） | 删 `wow.log.append-queue.*` |
| K8 | **每个键都有元数据与描述**，包括平台服务的键；默认值只在属性类里写一次 | `wow.analytics.catalog.*` 补元数据 |
| K9 | **能推导的不配置**（用户 2026-10-26：低维护成本，能自动的尽可能自动）：默认值从已有的事实推导（应用名、类路径上的契约、连接池与堆的大小、已配置的后端与 Kafka、部署前提 D295），键只作覆盖；推导出的值在启动时各记一行 INFO，便于排查 | 对端地址由契约里的服务名推导；网关后的转发头策略由 Wow 默认打开 |

## 3. 定稿表

「变化」一栏：**删**＝删除不替代；**改**＝改名或改类型；**并**＝合并为一个键；空白＝不变（只列有变化的与有歧义的）。

### 3.1 DDL（C-3a）

现在有 5 个键、4 个同值枚举，`validate` 有四种含义（拒绝启动、只告警、按分区数检查、Elasticsearch 里「非 auto 即不管理」）。

| 概念 | 现在 | 定稿 | 类型 | 默认 | 变化 |
| --- | --- | --- | --- | --- | --- |
| 框架管理的表、集合、索引、主题 | `wow.ddl`、`wow.stores.<n>.ddl` | `wow.ddl`、`wow.stores.<n>.ddl` | `Ddl`（`:spi`，唯一的枚举：`auto`、`validate`、`none`） | `auto` | 枚举并为一个 |
| 查询索引（`x-wow-index`） | `wow.query.index.ddl`（`QueryIndexDdl`） | 跟随所在存储的 `ddl` | — | — | 删：索引是存储结构的一部分 |
| Kafka 主题 | `wow.kafka.topics.ddl`（`TopicDdl`，默认 `validate`） | `wow.kafka.ddl` | `Ddl` | 跟随 `wow.ddl`（见 CQ1） | 改 |
| ClickHouse 表 | `wow.clickhouse.sink.ddl`（`String`） | `wow.clickhouse.ddl` | `Ddl` | 跟随 `wow.ddl` | 改 |

`validate` 只有一种含义：**不创建，只检查；缺失或不兼容即拒绝启动**，消息写明缺什么、怎么修。`none` 是真的不检查（现在 Kafka 的 `none` 仍检查分区数）。查询索引在 `validate` 下缺失即拒绝启动（现在只告警，见 CQ2）。`wow.mongo.sharding` 是 MongoDB 的建表选项，不是 DDL 模式，留在 `wow.mongo.*`，只在 `ddl=auto` 时生效（不变）。

### 3.2 对端（C-3b，D300）

现在：`wow.calls`（内部端口、10 秒、令牌）与 `wow.clients`（公开或内部端口、30 秒、重试、模式）两处配，三个 URL 键，两个 HTTP 客户端。

| 概念 | 现在 | 定稿 | 类型 | 默认 | 变化 |
| --- | --- | --- | --- | --- | --- |
| 对端的内部端口地址 | `wow.calls.contexts.<ctx>.url`、`wow.clients.contexts.<ctx>.internal-url` | `wow.peer.discovery`（`dns`、`static` 或提供者类型）、`wow.peer.dns.name-template`；`wow.peers.<ctx>.url` 只用于 `static` 或个别覆盖 | `String`、`URI` | `dns`；模板 `_internal._tcp.{service}-cluster`（与随附部署清单的 headless Service 及其端口名一致），服务名取自类路径上该上下文契约的 `service`（D305） | 并、自动 |
| 对端的公开地址（用户模式） | `wow.clients.contexts.<ctx>.url` | `wow.peers.<ctx>.public-url` | `URI` | — | 改 |
| 调用模式 | `wow.clients.mode`、`…contexts.<ctx>.mode` | `wow.peer.mode`、`wow.peers.<ctx>.mode` | `service`/`user` | `service` | 改 |
| 契约来源 | `wow.clients.contexts.<ctx>.contract` | `wow.peers.<ctx>.contract` | `jar`/`manifest` | `jar` | 改 |
| 超时 | `wow.calls.timeout`（10 秒）、`wow.clients.timeout`（30 秒） | `wow.peer.timeout`、`wow.peers.<ctx>.timeout` | `Duration` | 30 秒（命令的等待另有截止时间） | 并 |
| 连接超时 | `wow.calls.connect-timeout`、`wow.clients.connect-timeout` | `wow.peer.connect-timeout` | `Duration` | 2 秒 | 并 |
| 重试 | `wow.clients.retry.{max-attempts,backoff,max-retry-after}` | `wow.peer.retry.{max-attempts,initial-backoff,max-retry-after}` | — | 3、0.2 秒、5 秒 | 改（K5） |
| 用户模式转发的请求头 | `wow.clients.forwarded-headers` | `wow.peer.forwarded-headers` | `List<String>` | `Authorization` | 改 |
| 调用方凭据 | `wow.calls.token` | — | — | — | 删（D300） |

一个 HTTP 客户端。被调用的一方（谁可以调我）另见 §3.3。

### 3.3 内部端口与调用方（D300）

| 概念 | 现在 | 定稿 | 类型 | 默认 | 变化 |
| --- | --- | --- | --- | --- | --- |
| 集群令牌 | `wow.internal.token` | — | — | — | 删（D300） |
| 被信任服务的令牌 | `wow.http.services.<svc>.tokens` | — | — | — | 删（D300） |
| CoSec 服务主体 | `wow.cosec.service.{roles,policies}` | — | — | — | 删（D300） |
| 调用方可调的命令、可读的模型 | `wow.http.services.<svc>.{commands,reads}` | `wow.callers.<service>.{commands,reads}` | `List<String>` | 不列即全部领域命令与模型（D298、D299） | 改：与 `wow.peers`（我调谁）对称，不再挂在 `wow.http` 下 |
| 开发 profile | `wow.internal.dev-profiles`、`wow.kafka.topics.dev-profiles` | `wow.dev-profiles` | `Set<String>` | `dev, local, test, demo` | 并（C-3e）；用于 `wow.backend=auto`、Kafka 持久性告警、D301、D297 与平台的头部身份开关 |
| 内部端口的绑定与地址 | `wow.internal.{host,port,connect-timeout,signal-queue-capacity}`、`wow.node.address` | 不变 | — | — | `wow.node.address` 是对外公布的地址，`wow.internal.host` 是绑定地址，KDoc 写清 |

### 3.4 查询（C-16、C-3c、C-3d）

| 概念 | 现在 | 定稿 | 类型 | 默认 | 变化 |
| --- | --- | --- | --- | --- | --- |
| 每个入口的预算（12 个叶子） | `wow.query.http.*`、`wow.query.in-process.*` | `wow.query.budgets.http.*`、`wow.query.budgets.in-process.*` | `Budget` | `http`＝`HTTP_DEFAULT`；`in-process`＝`IN_PROCESS_DEFAULT`（现在两者都读 `HTTP_DEFAULT`，是缺陷） | 改 |
| 去掉预算的受信查询的超时 | `wow.query.in-process-timeout` | `wow.query.unbounded-timeout` | `Duration` | 30 秒 | 改：与 `in-process.timeout` 只差一个点、含义不同 |
| 查询读偏好（MongoDB） | `wow.query.events.mongo-read-preference`、`wow.stores.<n>.query.read-preference` | `wow.mongo.query-read-preference`、`wow.stores.<n>.options.query-read-preference` | `String` | 主节点 | 改（K6）：对快照与 `events` 查询都生效 |
| 自适应查询并发 | `wow.query.adaptive.{enabled,floor,ceiling,interval,latency-factor,min-latency,connection-waits,periods}` 及每个存储的 `enabled`、`floor`、`ceiling` | `wow.query.adaptive.{floor,ceiling}`（可按存储覆盖） | `Int` | 1、`max-concurrent` | 删其余 6 个与开关（K7） |
| 计数缓存 | `wow.query.count-cache.{enabled,events-enabled,max-entries,paged-totals,ttl}` | `wow.query.count-cache.{enabled,events.enabled,max-entries,paged-totals,ttl}` | — | 不变 | 改 `events-enabled`（K4）；`enabled` 保留：它改变计数的新鲜度，是语义而不是纯优化 |

### 3.5 日志、执行与运行时选项（B-8、B-11）

| 概念 | 现在 | 定稿 | 变化 |
| --- | --- | --- | --- |
| 追加队列 | `wow.log.append-queue.{enabled,max-batch,merge,mergers,merge-max}`（可空，真实默认只在文字里） | — | 删（K7）：D248 ④ 已对所有后端默认开启并实测，参数按后端是常量 |
| 交接日志 | `wow.log.handoff.{enabled,capacity,gap-wait}` | — | 删（K7） |
| 执行器、等待、状态缓存 | `wow.executor.*`、`wow.wait.*`、`wow.state-cache.*` | 键不变 | `ServiceNodeOptions` 的 21 个平铺字段归入 `WaitOptions`、`ExecutorOptions`、`StateCacheOptions`（ABI，B-8） |
| 快照存放 | `wow.snapshot.store`（`String`）、`wow.snapshot.targets`（`List`）、`wow.snapshot.primary`；`wow.aggregates.[..].snapshot.store`（`List`）、`.primary` | `wow.snapshot.stores`、`wow.snapshot.primary`；`wow.aggregates.[..].snapshot.stores`、`.primary` | 并（K2）：一个概念、同一类型 |
| `ContextBackends` 的两种写法（B-11） | `snapshots` 与 `snapshotTargets` | 一种（随上一行） | 并 |
| 订阅的停放重试 | `wow.subscription.parked.min-backoff` | `wow.subscription.parked.initial-backoff` | 改（K5）；`max-retries` 保留：数的是停放之后的重试 |

### 3.6 传输、Sink 与集成（B-5 配置部分、C-12）

| 概念 | 现在 | 定稿 | 类型 | 默认 | 变化 |
| --- | --- | --- | --- | --- | --- |
| 传输 | `wow.topics.in-memory`，以及 `wow.kafka.bootstrap-servers` 是否设置 | `wow.transport` | `auto`、`memory`、`kafka` 或提供者的类型（`TransportProvider`） | `auto`：设置了 Kafka 即 `kafka`；否则只在开发 profile 或回环下用 `memory`，与 `wow.backend=auto` 同一规则 | 改（见 CQ3） |
| Kafka 命令摄入 | `wow.kafka.command-intake` | `wow.kafka.command-intake.enabled` | `Boolean` | `false` | 改（K4） |
| ClickHouse 按聚合的历史 | `wow.clickhouse.sink.aggregates.<ctx>.<agg>.history.*`（旧写法，仍在读）与 `wow.aggregates.[..].clickhouse.history.*` | 只留后者 | — | — | 删旧写法（D1） |
| Sink 开关 | 各 Sink 自己的 `enabled` | `wow.<provider>.sink.enabled`（`SinkProvider` 的约定） | `Boolean` | 提供者定 | 规则化 |
| CoSec | `wow.cosec.*` 由属性类声明，却由 `:cosec` 经 `Environment` 读（两个读者、两份默认） | 键不变（除 D300 删除的 `service.*`），只由宿主的属性 bean 读，平台服务不再复制常量 | — | — | K1（C-12） |
| 平台服务的头部身份 | `wow.platform.trust-identity-headers`（三处常量） | 不变，补元数据，只在开发 profile 或回环下允许（S-9） | `Boolean` | `false` | K1、K8 |

### 3.7 其余的布尔与读者（K1、K4）

| 现在 | 定稿 |
| --- | --- |
| `wow.standby` | `wow.node.standby`（节点的启动状态，归到节点组） |
| `wow.http.global-errors` | `wow.http.global-errors.enabled` |
| `wow.http.client-facts` | `wow.http.client-facts.enabled`（默认 `true`，D297） |
| `wow.analytics.catalog.watch` | `wow.analytics.catalog.watch.enabled` |
| `wow.health.enabled`、`wow.wait.max-timeout`、`wow.cluster.drain-timeout`、`wow.kafka.bootstrap-servers`、`wow.cosid.global-generator`、`wow.tenant.default`、`wow.clickhouse.database` | 键不变；各只一个读者、一份默认（现在 2～6 个） |
| `wow.opentelemetry.*` | 随 D289 的模块改名，改为 `wow.observability.*` |

### 3.8 未知键（D302）

`ConfigurationKeys` 对所有未知的 `wow.*` 键拒绝启动。平台服务的 `wow.analytics.*`、`wow.view-store.*`、`wow.platform.*` 补上元数据，作为它们模块自有的前缀登记，以免被误拒。错误消息对本表改名的键给出新键名，这只是一张静态对照表，不是别名：旧键仍然拒绝启动。

**落地**（D324 ②，#4972、#4975）：检查合并类路径上所有模块的元数据；不引入 Spring Boot 的模块自带普通属性类与元数据（wow-cosid）。环境变量里组为 Wow 配置组的 `WOW_<组>_…` 一并检查，其他 `WOW_*` 不查。改名与删除只在 `ConfigurationKeys.RENAMED` 一处，升级文档只写一句规则。

**规模**：约 300 个键减到约 255 个，删除约 35 个、合并约 10 个；改名的键都有静态对照，升级说明逐条列出。

### 3.9 自动推导（K9）

| 值 | 推导自 | 覆盖键 | 状态 |
| --- | --- | --- | --- |
| 服务名 | `spring.application.name` | `wow.service` | 已有 |
| 节点 ID、对外公布的地址、内部端口的绑定地址 | 每次启动生成；本机地址 | `wow.node.id`、`wow.node.address`、`wow.internal.host` | 已有 |
| 状态缓存容量 | 堆大小（B.O-3） | `wow.state-cache.capacity` | 已有 |
| 每个存储的查询并发 | 连接池大小 | `wow.query.max-concurrent`、`wow.stores.<n>.query.max-concurrent` | 已有 |
| Kafka 主题的分区、副本数 | 日志的分区数；broker 的默认 | `wow.kafka.topics.*` | 已有 |
| 后端、传输 | 已有的 DataSource、MongoClient；是否设置了 Kafka | `wow.backend`、`wow.transport` | 传输为新（CQ3） |
| 快照存放 | 日志所在的存储 | `wow.snapshot.stores` | 已有 |
| **对端的内部地址** | 参照 Elasticsearch 的发现：种子由 DNS SRV 查询得到（地址与端口），来源可插拔（`PeerDiscovery`：`dns`、`static`）；解析出的多个节点在客户端轮询，连接失败或带 Wow 下线、待机错误码的 503 时换下一个（D305、D325） | `wow.peer.discovery`、`wow.peers.<ctx>.url` | 新 |
| **网关后的客户端地址** | 部署前提 D295、D296：公开端口前总有网关，Wow 以默认属性源提供 `server.forward-headers-strategy=framework`（同 `ShutdownTimeoutEnvironmentPostProcessor` 的做法），取代 D297 的启动告警 | `server.forward-headers-strategy` | 新，修订 D297 的实现 |

### 3.10 规则的放行清单（#4972 落地，测试守住）

每一项都写明理由；清单里的键或声明不再存在时测试失败。

| 规则 | 测试 | 放行项与理由 |
| --- | --- | --- |
| K1 每个键一个读者 | `ConfigurationReadersTest.ALLOWED` | `PortProviders`：传输或 Sink 自己的 `wow.<type>.*` 以映射交给提供者；`WowCosIdAutoConfiguration`：模块不引入 Spring Boot（D118），读自己的键，默认只在 `CosIdProperties` |
| K1 平台服务读宿主设置 | `InternalOptInTest.HOST_SETTING_READERS` | 运维与分析目录的授权器读 `wow.cosec.authorizer.enabled`、`wow.tenant.default`（宿主不交出 CoSec 的命令授权器，后续改为注入）；`warehouseCatalog` 读 `wow.clickhouse.database`、`datasetReferenceActions` 与 `manifestPeers` 读 `wow.peers.<ctx>.contract`（都没有公开类型给出） |
| K4 布尔以 `.enabled` 结尾 | `KeyNamingTest.BOOLEANS` | `wow.health.topics.required`（健康检查的严格程度）、`wow.node.standby`（启动状态）、`wow.query.abac.match-missing-tag-key`（ABAC 的匹配规则）、`wow.query.budgets.*.unindexed-sort-expensive`（预算的计法）、`wow.query.count-cache.paged-totals`（计数缓存的覆盖面）、`wow.query.sensitivity.display-comparable`（敏感字段的策略）；`wow.ops.publish.enabled` 是三态（D324 ⑤） |
| K5 重试的叶子名 | `KeyNamingTest.RETRIES` | 叶子只能是 `max-attempts`、`initial-backoff`、`max-backoff`、`multiplier`、`jitter`、`max-retry-after`；`wow.subscription.parked.max-retries` 数的是停放之后的重试 |

## 4. 用户的决定（D303）

| 编号 | 问题 | 选项 | 推荐与理由 |
| --- | --- | --- | --- |
| CQ1 | Kafka 主题的 DDL 默认 | ① 跟随 `wow.ddl`（默认 `auto`：缺主题就按日志的分区数创建）；② 保留 Kafka 单独默认 `validate` | **①**：一个默认、一条覆盖链（G4、D299）；生产环境要求只检查时设一次 `wow.ddl=validate`，所有结构都一致 |
| CQ2 | `validate` 下缺查询索引 | ① 拒绝启动（与其他结构一致）；② 只告警（现状） | **①**：`validate` 只有一种含义；缺索引的查询会扫全表，在启动时发现比上线后慢查询更便宜 |
| CQ3 | 传输的选择 | ① `wow.transport` 与 `wow.backend` 同一规则；② 保持现状（是否设置 Kafka 加 `wow.topics.in-memory`） | **①**：存储与传输对称（G6，B-5）；生产环境不会因为漏配 Kafka 而静默用上内存主题 |
| CQ4 | 对端与调用方的命名 | `wow.peer.*`、`wow.peers.<ctx>.*`（我调谁）与 `wow.callers.<service>.*`（谁调我） | **按此做**：两个方向各一处，不再分 `calls`、`clients`、`http.services` 三处 |
| CQ5 | 删除的总开关与内部常量（§3.4、§3.5） | 按表删除 | **按此做**：都已实测并默认开启（D248 ④、D268～D281 的基准）；需要时再作为加法加键 |

其余各行按 §2 的规则直接改，不另问。

## 5. 落地

一个工作包（批 4），在批 2（宿主与 `:http` 的 C-2、S.1）合并之后派出，避免同文件冲突：属性类、元数据与描述、`ConfigurationKeys`、文档站的配置参考与升级说明（中英文）、部署清单与示例配置。每条规则由测试守住：K1 用「每个 `wow.*` 键只有一个读者」的架构规则，K4、K5 用元数据上的命名测试，K8 用「每个键都有描述」的现有测试。
