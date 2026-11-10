---
title: 升级部署
description: 用滚动更新升级服务，哪些可以并行运行，格式、状态修订号、事件和 Manifest 怎样让升级保持安全，以及主版本会改变什么。
outline: deep
---

# 升级部署

服务通过滚动更新升级：新节点在旧节点旁边启动，分区移到它们上面，然后旧节点停止。有一段时间，两个版本同时运行，面对同一个数据库和同样的主题。本页说明是什么让这件事保持安全，无论是升级 Wow 还是升级你自己的代码。

## 两个版本并行 {#two-versions-side-by-side}

在同一主版本内，Wow 相邻的次版本可以在一个服务内、也可以跨服务并行运行。它们的内部协议（集群通道、等待信号、线上形式）以及存储格式保持兼容：

- **存储格式**按结构而不是按行做版本：每张日志、快照和定时器表都在 `wow_format` 中记录它的格式。在同一主版本内格式不变；同一格式内新增的列，会在下次检查时加到已有的表上，读者忽略它们不认识的东西。
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

## 发布期间的订阅 {#subscriptions-during-a-rollout}

分区连同它的检查点移到新节点上，新节点的订阅从那里继续。新版本中新增的处理函数按它的种类开始（[订阅](../concepts/subscriptions#replay-and-backfill)）：投影从开头开始；读取自己上下文日志的反应器或 Saga 从末尾开始；反应器或 Saga 读取另一个上下文事件流的处理函数，从它的服务加入这条流的地方开始，服务里还没有别的订阅读过它时从开头开始，否则从末尾开始。`@OnEvent(start = ...)` 或 `wow.subscriptions.[<context>.<processor.function>].start` 可以覆盖它。

订阅的名字就是它的身份，所以上线前要检查改名：

- **改了名的处理器或处理函数是一个新订阅。** 它按同样的规则开始，就像新的一样：Saga 或反应器从末尾开始，跳过旧名字还没处理到的内容；投影从开头开始，重建它的读模型。旧名字的检查点和停放条目留在原处，不再有人读取。
- **要保留位置，就钉住旧名字**：在改了名的类或函数上用 `@Name`。
- **新节点在首次启动时警告**已没有订阅的名字下的检查点；`GET /wow/ops/subscriptions` 会列出它们（[ops 服务与控制台](./ops-console#rename-and-rewind)）。
- **换一个名字是唯一的回卷**：检查点从不往回设。用新名字和更早的 `start` 部署处理函数，就是再读一次流的办法。

## 不能改变的设置 {#settings-that-must-not-change}

`wow.log.partitions` 在日志有了条目之后就固定了，一个服务的节点共用它们的存储布局（[配置服务](./configuration#what-must-match-across-nodes)）。

## 主版本 {#a-major-version}

新的主版本可能改变格式和协议。它的节点不会与上一个主版本的节点并行运行：它附带一次迁移，在切换之前或切换期间运行，就像 V10 从 9.x 迁移那样（[从 9.x 迁移](../migrate/)）。

## 从 10.0.0 预发布版升级 {#from-a-10-0-0-pre-release}

10.0.0 的预发布版（`alpha`、`beta`、`rc`）对它存储的数据不做兼容承诺：之后版本的存储格式可能不同。本版本不打开预发布版以更早格式写下的存储：启动时格式检查拒绝它，并说出是哪个结构、以什么格式存储。在 PostgreSQL 上，这是预发布版写下的每张日志表、预留表和定时器表（它们的键现在按码点比较，日志的版本键以租户开头）。请全新开始这样的存储：用新的数据库，或者删掉它的表；从 9.x 迁移的服务用 `migrate-data` 重新导入它的历史（[迁移数据](../migrate/data)）。预发布版写下的数据既不迁移，也不读取。

导入时没有访问属性的流（从 9.x 导入可能这样写入），在每个聚合类型的访问遍历为它们盖章之前没有访问属性，这段时间里有些读取不包含它们：

- **隐藏什么。** 在访问策略下，快照查询不包含该类型未盖章的快照，事件查询不包含它未盖章的流。没有访问策略时，以所有者身份或在某个空间里行事的调用方，其事件查询不包含未盖章的流。整个租户的调用方仍能读到它们；状态读取，以及按版本、按时间和 trace 的读取不受影响。
- **为什么。** 读取按记录所带的访问属性判定：策略比较的属性，或者在事件上比较聚合现在的所有者和空间。每条流都记着它们，所以转移之后，聚合的历史跟着聚合走（[谁能看到什么](../guide/event-queries#who-sees-what)）。不带访问属性的记录什么也不授予，而不是对所有调用方都显示。
- **怎样看它完成。** 新版本的第一个节点会自行开始遍历。某个类型的遍历完成之前，它的 `wow.access.walk.pending` 为 1，完成后为 0；`wow.access.walked` 计数已经遍历的聚合；完成时，执行遍历的节点记录日志 `The access walk of <type> is completed`（[重建与重放](./replay#access-attributes)）。

如果数据集的行属于有访问策略的聚合，每一行按 ClickHouse Sink 的 `_state` 中该聚合最新一次提交的属性读取（[数据集](../guide/datasets#rows-of-an-aggregate-with-an-access-policy)）。遍历盖章的是日志，不是数仓，所以有一段时间，对受策略限制的每个调用方，有些行是隐藏的：

- **最后一次由预发布版写入的聚合的行。** 它在 `_state` 中最新的一行不带属性（`access` 为空），所以它的行一直隐藏，直到它的下一次提交。
- **某个版本新声明了访问属性之后。** 每个聚合最新的一行没有这个属性，所以对它的比较看不到任何值，直到该聚合的下一次提交。

要在那之前让它们显示，就从日志重新填充数仓：重置每个这类聚合的 Sink 订阅 `wow.clickhouse-sink.<aggregate>`，它会用日志现在记录的属性把每次提交重新写一遍，`_state` 保留每个聚合最新的一行（[重建与重放](./replay#access-attributes)、[重建数仓](./clickhouse#rebuild-the-warehouse)）。

`wow_catalog` 新增 `access` 列：该聚合的访问策略是否决定它的行（[wow_catalog](./clickhouse#wow-catalog)）。`wow.clickhouse.ddl: auto` 的 Sink 在第一次检查时补上它；`validate` 的 Sink 在这一列存在之前拒绝写入：`ALTER TABLE <database>.wow_catalog ADD COLUMN IF NOT EXISTS access LowCardinality(String) AFTER columns_hash`。较早版本的 Sink 写下的行没有说明是否有策略，所以分析目录按有策略处理：基于这些表的目录数据集转为需要修订，查询答 `409 wow.query.dataset-unavailable`，直到拥有该聚合的服务运行这个版本，它的 Sink 在启动后第一次检查时重新写出这些行。不需要重放。聚合有策略的表仍然被拒绝：它的数据集改由拥有该聚合的服务提供（[数据集](../data/datasets#define-a-dataset)）。

失败账本的条目新增修订号（revision），它决定一个条目上并发的写入哪些生效（[失败处理](../guide/failures)）：`wow.ddl: auto` 时，PostgreSQL 在下一次检查时为 `wow_failure_ledger` 表补上 `revision` 列，已停放的条目都为 0（`validate` 时请先执行 `ALTER TABLE wow_failure_ledger ADD COLUMN IF NOT EXISTS revision bigint NOT NULL DEFAULT 0`）；MongoDB 把没有修订号的条目读作 0。已停放的条目保持不变。beta 与这个版本的节点并行运行期间，beta 的写入不推进修订号，所以运维人员对停放条目的操作仍可能被 beta 节点的排期掩盖，直到发布完成；那时若条目仍在停放，请再执行一次。

从 10.0.0 起，分析目录把数据集某个版本的视图命名为 `ds_<context>_<name>_<digest>_v<N>`，digest 取自数据集的精确 id（[发布与版本](../data/datasets#publish-and-version)）。之前发布的版本保留发布时的视图：回滚、退役和重新编译读取每个版本记录的名字，不会改名。预发布版会给 id 只在大小写、`-`、`_` 或 context 结束位置上不同的两个数据集（`shop.a-b` 与 `shop.a_b`，`a_b.c` 与 `a.b_c`）同一个视图，后发布的那个替换了先发布的。如果你发布过这样的一对，请为每个各发布一个新版本：每个都会得到自己的视图。

TypeScript 包随之改变：`@ahoo-wang/wow-client` 不再有 `ApplyTags`、`TagsApplied` 与 `Tags`，记录的 `tags` 改为 `access`，`wow-generator` 生成的客户端为每个聚合的访问属性给出类型。请重新生成客户端；步骤见[命令](../typescript/commands#lifecycle-commands-scope-and-space)。

从 10.0.0 起，对调用方作用域之外的聚合的命令得到 `404 wow.aggregate.not-found`，与读取它的回答一致；预发布版回答 `403 wow.aggregate.forbidden`，现在只有指明这样的聚合的创建命令仍得到它（[内核检查什么](../guide/ownership#what-the-kernel-checks)）。按状态码区分两者的客户端改为显示它的"未找到"回答。

OpenAPI 文档（`GET /wow/openapi`）按每个聚合声明的访问属性给出 `access` 的类型：单值为该值或 null，多值为数组，整数为 `int64`；事件流的 `access` 含聚合的 `owner` 与 `space`，在该流盖章之前为 null。预发布版的文档里是 `tags` 成员。由它生成的客户端需重新生成。

配置面在 10.0.0 之前一次定稿，不保留别名：改名或移除的键会使节点拒绝启动；拒绝消息列出该键，并指向迁移指南的配置页，那里给出每个旧键的新键或它被移除的原因。

### 基于 CoCache 8 的 wow-cocache {#wow-cocache-on-cocache-8}

`wow-cocache` 从 CoCache 6 改为基于 CoCache 8，后者既不读取也不写入 CoCache 6 存储的内容。经它共享状态的服务要一次性修改代码、配置与部署。

**代码。** Spring 应用无需改动：缓存由 CoCache starter 的 `CoCacheFactory` bean 创建。自己构建缓存的代码改为传入 CoCache 的 `CoCacheFactory`：

| 之前 | 现在 |
| --- | --- |
| `StateViewCaches(StateViewCaches.CacheFactory.of(runtime)) { queryApi }` | `StateViewCaches(CoCacheFactory(runtime)) { queryApi }` |
| 自己实现 `StateViewCaches.CacheFactory`，以修改缓存的策略 | `CoCacheFactory(runtime, layers)`，配合自己的 `CacheLayers` |
| `StateViewCaches.specOf(...)`：一个 `CacheSpec` | 一个 `VersionedCacheSpec` |
| `me.ahoo.cocache:cocache-coroutines` | 已移除：挂起视图在 `cocache-core` 中 |

**配置。** `cocache.caches.[<name>]`、`cocache.defaults` 与 `cocache.overrides` 下 CoCache 8 没有定义的键会导致启动失败，错误信息会说明这个键应写在哪里：例如 CoCache 6 的 `ttl-forever` 或 `load-async-timeout`，CoCache 6 的 `cocache.defaults.ttl`（现为 `cocache.defaults.cache.ttl`）或 `cocache.defaults.key-prefix`（现为 `cocache.key-prefix`），或者含点的缓存名没有写方括号（`cocache.caches.wow.eshop-ordering.order.ttl`）。`cocache` 下其它位置的这类键，例如 `cocache.redis.*` 下的，Spring Boot 会忽略且不给出警告。请检查每个 `cocache.*` 键：

| CoCache 6 | CoCache 8 |
| --- | --- |
| `cocache.defaults.<field>` | `cocache.defaults.cache.<field>`，只填补声明没有写出的字段；要强制更严格的值用 `cocache.overrides.cache.<field>` |
| 不带单位的时长（`ttl: 600`、`cocache.redis.command-timeout: 2000`） | 带单位（`ttl: 10m`、`command-timeout: 2s`）：裸数字会导致启动失败 |
| `ttl-forever: true` | `ttl: forever` |
| `load-async-timeout` | `load-timeout` |
| `load-max-concurrent: 0` | `load-max-concurrent: unlimited` |
| `cocache.defaults.key-prefix` | `cocache.key-prefix` |
| `cocache.async-executor`、`cocache.blocking-executor` | `cocache.executor` |
| `fill-in-transaction`、`invalidate-fills-on-rollback` | 移到 `cocache.defaults.cache.*` 或 `cocache.caches.[<name>].*` 下 |
| 状态视图缓存上的 `refresh-window`：启动时拒绝 | 由 Wow 的声明关闭，`cocache.defaults.cache.refresh-window` 不作用于它；`cocache.caches.[wow.<context>.<aggregate>].refresh-window` 为单个缓存开启 |

`cocache.caches` 下没有对应缓存的条目，或缓存类型没有的键，同样会导致启动失败；`cocache.caches-validation: warn` 改为只记录警告。其余变化（包括 Redis ACL）见 [CoCache 的升级指南](https://cocache.ahoo.me/guide/upgrade)。

**删除状态。** CoCache 的端点以 `evict` 删除状态视图的键，保留其版本；`?purge=true` 连版本一起删除，需要 `management.endpoint.cocache.allow-purge`（[从缓存中删除状态](../guide/extensions/cocache#drop-a-state-from-the-cache)）。

**切换。** 属主与读取其状态的每个服务要在同一个发布窗口内切换：CoCache 6 的节点与 CoCache 8 的节点不能共用一个缓存，CoCache 6 缓存中的内容也不会带过来。状态视图是派生数据，因此不会丢失任何东西：

1. 停止运行 CoCache 6 的每个节点，包括属主与每个读方。
2. 在 Redis 集群的每个主节点上删除状态视图的键：`redis-cli --scan --pattern 'cocache:wow.*' | xargs redis-cli unlink`（用了自己的键前缀时换成那个前缀）。键中仍是 CoCache 6 的值时，属主对它的写入会失败，直到键到期或被读方的读取替换，在此期间它的 sink 订阅会重试并搁置。
3. 以新版本启动属主与读方。缓存从空开始：属主的提交重新写入状态，读方未命中时向属主读取。

回滚按相反方向执行同样的步骤。

### wow-cosec 升级到 CoSec 8.0.0-alpha.3

`wow-cosec` 跟随 CoSec 的预发布版本，已从 8.0.0-alpha.2 升级到 8.0.0-alpha.3。网关模式的服务（`wow.cosec.mode: gateway`）不持有 CoSec 的密钥、文档或 Redis，代码和配置键都不用改。要改的是它前面的 CoSec 网关，以及用 Redis 中的文档自己做决定的服务。

**CoSec 的 Redis 中的数据。** CoSec 8.0.0-alpha.3 构建在 CoCache 8 之上，而 CoCache 8 读不了 alpha.2 通过 CoCache 6 写入的内容。仍是旧格式的文档或吊销记录会按拒绝处理：请求得到 503，被吊销的令牌也一样。在所有 CoSec 实例停止后，二选一：用 `cosec-migrate cocache8 --redis <uri> --key-prefix <prefix> --out <dir>` 迁移数据（先加 `--dry-run` 试运行），或者清空这个 Redis，再用 `cosec-migrate publish --to-redis` 重新发布文档。

**统一的键前缀。** 节点若仍设置下面任何一个键，就不会启动，错误信息会列出要迁移的键族。放在其它前缀下的吊销记录和 API Key 不会再被读取，请先把它们复制到新前缀下：

| CoSec 8.0.0-alpha.2 | CoSec 8.0.0-alpha.3 |
| --- | --- |
| `cosec.authorization.snapshot.key-prefix` | `cosec.key-prefix` |
| `cosec.authorization.cache.key-prefix` | `cosec.key-prefix` |
| `cosec.api-key.key-prefix` | `cosec.key-prefix` |

**网关**（[配置 CoSec Gateway](./security#configure-the-cosec-gateway)）：

- 通过 `http://` 或 `https://` 指向自有服务的路由需要元数据 `cosec-identity: true`，否则服务读不到调用方。指向 `lb://` 的路由仍像之前一样转发身份。
- 凭据默认不再向下游转发：`Authorization` 请求头、API Key 请求头、`authorization` 查询参数和 `authorization` Cookie。网关模式的服务不读取其中任何一个；自己读取它们的服务要设置 `cosec.gateway.identity.forward-authorization: true`，并位于会收到身份的路由之后。
- 网关在应用端口上暴露 `health` 以外的 Actuator 端点时不会启动。

**行为变化。**

- `cosec.authorization.snapshot.revalidate-interval` 必须在 1 秒到 5 分钟之间，否则节点不会启动。
- 含未配对 UTF-16 代理项的 ID 会被拒绝：出现在令牌中是 401，出现在控制 API 中是 400。
- 文档读取的请求头如果以不同的值重复发送，回答 400。
- 超出 CoSec 成本上限的路径模式无法编译（控制面回答 422）。
- 自己实现的 `SnapshotDocumentStore` 必须产生 32 字节的 SHA-256 摘要。

[CoSec 的升级指南](https://cosec.ahoo.me/operations/upgrading#from-8-0-0-alpha-2)列出了其余内容，包括控制面的变化。
