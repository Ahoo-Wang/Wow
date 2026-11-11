# 配置面定稿（R5 批 4）

日期：2026-10-26。基线：`origin/main`（安全讨论 D295～D302 之后）。状态：已实施（D303：用户 2026-10-26 按推荐定 CQ1～CQ5，并定配置原则「低维护成本，能自动的尽可能自动」）；本文留作设计依据：命名规则 K1～K9、未知键的检查、自动推导、规则的放行清单与 CQ1～CQ5 的理由。§3.1～3.7 的逐键对照表与 §5 的落地安排已删（见 `2c08f318ea`），节号不变。

上位文档：[00 目标架构](00-architecture.md) §9（R5 审查的不变式 G4～G6）、[安全讨论](2026-10-26-security-review-design.md)、[决策台账](decisions.md)（D288、D299、D300、D302）。

## 1. 为什么一次定稿

配置键是 GA 后按破坏性变更管理的契约。分几次改，用户的配置就要跟着改几次。D302 之后未知键会拒绝启动，所以改名后，旧键在启动时就会报出来，不会静默失效。这正是一次定稿、不留别名（D1）的前提。

## 2. 命名规则（定稿后由测试守住）

| 编号 | 规则 | 例 |
| --- | --- | --- |
| K1 | **一个概念一个键、一种类型、一个读者**：键只由属性 bean 绑定，模块与平台服务读同一个 bean，不再 `Environment.getProperty` 加一份默认值 | 定稿前 `wow.cosec.*` 有两个读者，`wow.tenant.default` 有 6 个 |
| K2 | **覆盖链**：全局键 → `wow.stores.<store>.<同名叶子>` → `wow.aggregates.[<ctx>.<agg>].<同名叶子>`，叶子名逐级相同 | `wow.ddl` → `wow.stores.pg.ddl` |
| K3 | **设置组用单数，按名字的映射用复数** | `wow.subscription.*`（默认）与 `wow.subscriptions.[name].*`；`wow.peer.*` 与 `wow.peers.<ctx>.*` |
| K4 | **功能开关以 `.enabled` 结尾；策略开关用动词短语**（`require-*`、`allow-*`、`trust-*`） | `wow.http.client-facts.enabled`、`wow.query.require-authenticated-scope` |
| K5 | **重试统一用语**：`max-attempts`（含第一次）、`initial-backoff`、`max-backoff`、`multiplier`、`jitter` | `wow.peer.retry.initial-backoff`（原 `backoff`） |
| K6 | **后端专有的键只在后端自己的组里**：全局默认 `wow.<backend>.*`，单个存储 `wow.stores.<store>.options.<同名叶子>`，只作用于该类型的存储 | Mongo 读偏好不在 `wow.query.*` 里 |
| K7 | **已实测、默认开启的优化不留总开关**；只保留有部署差异的容量参数，内部常量不做成键（D299） | 删 `wow.log.append-queue.*` |
| K8 | **每个键都有元数据与描述**，包括平台服务的键；默认值只在属性类里写一次 | `wow.analytics.catalog.*` 补元数据 |
| K9 | **能推导的不配置**（用户 2026-10-26：低维护成本，能自动的尽可能自动）：默认值从已有的事实推导（应用名、类路径上的契约、连接池与堆的大小、已配置的后端与 Kafka、部署前提 D295），键只作覆盖；推导出的值在启动时各记一行 INFO，便于排查 | 对端地址由契约里的服务名推导；网关后的转发头策略由 Wow 默认打开 |

## 3. 定稿表

逐键的定稿前后对照（§3.1～3.7：DDL、对端、内部端口与调用方、查询、日志与运行时选项、传输与 Sink、其余的布尔与读者）已删，见 `2c08f318ea`。现行的键以[配置参考](../docs/zh/reference/configuration.md)为准；9.x 的键到 V10 的对照在迁移指南的[配置页](../docs/zh/migrate/configuration.md)。

### 3.8 未知键（D302）

`ConfigurationKeys` 对所有未知的 `wow.*` 键拒绝启动。平台服务的 `wow.analytics.*`、`wow.view-store.*`、`wow.platform.*` 补上元数据，作为它们模块自有的前缀登记，以免被误拒。错误消息指向迁移指南的配置页，不设别名：旧键仍然拒绝启动。

**落地**（D324 ②，#4972、#4975）：检查合并类路径上所有模块的元数据；不引入 Spring Boot 的模块自带普通属性类与元数据（wow-cosid）。环境变量里组为 Wow 配置组的 `WOW_<组>_…` 一并检查，其他 `WOW_*` 不查。

### 3.9 自动推导（K9）

| 值 | 推导自 | 覆盖键 |
| --- | --- | --- |
| 服务名 | `spring.application.name` | `wow.service` |
| 节点 ID、对外公布的地址、内部端口的绑定地址 | 每次启动生成；本机地址 | `wow.node.id`、`wow.node.address`、`wow.internal.host` |
| 状态缓存容量 | 堆大小（B.O-3） | `wow.state-cache.capacity` |
| 每个存储的查询并发 | 连接池大小 | `wow.query.max-concurrent`、`wow.stores.<n>.query.max-concurrent` |
| Kafka 主题的分区、副本数 | 日志的分区数；broker 的默认 | `wow.kafka.topics.*` |
| 后端、传输 | 已有的 DataSource、MongoClient；是否设置了 Kafka | `wow.backend`、`wow.transport` |
| 快照存放 | 日志所在的存储 | `wow.snapshot.stores` |
| **对端的内部地址** | 参照 Elasticsearch 的发现：种子由 DNS SRV 查询得到（地址与端口），来源可插拔（`PeerDiscovery`：`dns`、`static`）；解析出的多个节点在客户端轮询，连接失败或带 Wow 下线、待机错误码的 503 时换下一个（D305、D325） | `wow.peer.discovery`、`wow.peers.<ctx>.url` |
| **网关后的客户端地址** | 部署前提 D295、D296：公开端口前总有网关，Wow 以默认属性源提供 `server.forward-headers-strategy=framework`（同 `ShutdownTimeoutEnvironmentPostProcessor` 的做法），取代 D297 的启动告警 | `server.forward-headers-strategy` |

### 3.10 规则的放行清单（#4972 落地，测试守住）

每一项都写明理由；清单里的键或声明不再存在时测试失败。

| 规则 | 测试 | 放行项与理由 |
| --- | --- | --- |
| K1 每个键一个读者 | `ConfigurationReadersTest.ALLOWED` | `PortProviders`：提供者自己的 `wow.<type>.*` 与 `wow.stores.<name>.*` 以映射交给提供者；`WowCosIdAutoConfiguration`、`WowCoCacheAutoConfiguration`：模块不引入 Spring Boot（D118），读自己的键，默认只在各自的属性类 |
| K1 平台服务读宿主设置 | `InternalOptInTest.HOST_SETTING_READERS` | 分析目录的 `datasetReferenceActions` 与 `manifestPeers` 读 `wow.peers.<ctx>.contract`（没有公开类型给出） |
| K4 布尔以 `.enabled` 结尾 | `KeyNamingTest.BOOLEANS` | `wow.health.topics.required`（健康检查的严格程度）、`wow.node.standby`（启动状态）、`wow.query.budgets.*.unindexed-sort-expensive`（预算的计法）、`wow.query.count-cache.paged-totals`（计数缓存的覆盖面）、`wow.query.sensitivity.display-comparable`（敏感字段的策略）；`wow.ops.publish.enabled` 是三态（D324 ⑤，`KeyNamingTest.SWITCHES`） |
| K5 重试的叶子名 | `KeyNamingTest.RETRIES` | 叶子只能是 `max-attempts`、`initial-backoff`、`max-backoff`、`multiplier`、`jitter`、`max-retry-after`；`wow.subscription.parked.max-retries` 数的是停放之后的重试 |

## 4. 用户的决定（D303）

| 编号 | 问题 | 选项 | 推荐与理由 |
| --- | --- | --- | --- |
| CQ1 | Kafka 主题的 DDL 默认 | ① 跟随 `wow.ddl`（默认 `auto`：缺主题就按日志的分区数创建）；② 保留 Kafka 单独默认 `validate` | **①**：一个默认、一条覆盖链（G4、D299）；生产环境要求只检查时设一次 `wow.ddl=validate`，所有结构都一致 |
| CQ2 | `validate` 下缺查询索引 | ① 拒绝启动（与其他结构一致）；② 只告警（现状） | **①**：`validate` 只有一种含义；缺索引的查询会扫全表，在启动时发现比上线后慢查询更便宜 |
| CQ3 | 传输的选择 | ① `wow.transport` 与 `wow.backend` 同一规则；② 保持现状（是否设置 Kafka 加 `wow.topics.in-memory`） | **①**：存储与传输对称（G6，B-5）；生产环境不会因为漏配 Kafka 而静默用上内存主题 |
| CQ4 | 对端与调用方的命名 | `wow.peer.*`、`wow.peers.<ctx>.*`（我调谁）与 `wow.callers.<service>.*`（谁调我） | **按此做**：两个方向各一处，不再分 `calls`、`clients`、`http.services` 三处 |
| CQ5 | 删除的总开关与内部常量（追加队列、交接日志、自适应并发） | 删除 | **按此做**：都已实测并默认开启（D248 ④、D268～D281 的基准）；需要时再作为加法加键 |
