# Wow v6 → v8 迁移契约

本 reference 是风险发现索引，不替代精确目标 tag 的源码、Release Notes 或目标应用证据。先固定目标版本，再应用对应条目。

## 目录

- [版本与平台](#版本与平台)
- [源码与生成契约](#源码与生成契约)
- [R2DBC 与 sharding 移除](#r2dbc-与-sharding-移除)
- [统一运行时](#统一运行时)
- [SnapshotStore](#snapshotstore)
- [Redis](#redis)
- [MongoDB](#mongodb)
- [切换与回滚](#切换与回滚)
- [验证矩阵](#验证矩阵)

## 版本与平台

| 条目 | v6 基线 | v8 目标 | 迁移动作 |
|---|---|---|---|
| Java | 17+ | 17+ | 核对 toolchain 与运行镜像，不需要为大版本本身升级 Java |
| Spring Boot | 3.x | 4.x | 同步迁移 Boot 模块、auto-configuration 与第三方 starter |
| Jackson | 2.x 生态 | 框架内部 Jackson 3 `tools.jackson` | 审计直接使用的 `ObjectMapper`、`JsonNode`、module、serializer 与 Spring 边界 |
| Kotlin/KSP | 取自应用实际解析结果 | 取自目标 Wow BOM/源码 | 对齐 Kotlin metadata 与 KSP 后再解释编译错误 |
| 相关平台 | v6 的 CosId/CoAPI/CoCache 等 | 取自目标 v8 BOM | 不单独强压旧主版本；检查公开类型和 starter 兼容性 |

当前仓库 v8.9.6 的基线位于 `gradle/libs.versions.toml`；迁移到其他 v8 小版本时读取对应 tag，禁止拿 current `main` 代替。

不要全局替换 Jackson import。当前 v8 框架的 core/databind/module 使用 `tools.jackson`，但注解仍使用 `com.fasterxml.jackson.annotation`；应用还可能在 Spring 或第三方库边界保留 `com.fasterxml` 类型。逐个核对目标 API、wire format 与 module 注册点。

## 源码与生成契约

对目标应用执行以下分类，而不是尝试维护一份静态的全部 API diff：

1. **公开 Wow API**：定位旧符号的目标定义、调用者、实现与测试；使用 compiler error 驱动迁移。
2. **内部 API**：Redis key converter、Lua script 常量、repository bean alias 等不承诺源码/JVM/行为兼容；迁回应使用 `EventStore`、`SnapshotStore` 与 `PrepareKey` 公开边界。
3. **测试 DSL**：只迁移实际旧调用，不要全量重写 DSL。Aggregate 使用 `whenCommand`，Saga 使用 `whenEvent`，`inject(service)` 使用 `inject { register(service) }`。当前 v8.9.6 仍支持 query `deleted(Boolean)`；除非精确目标 tag 已移除该重载，否则不要改写有效调用。
4. **Command wait**：检查旧 `ClientCommandExchange`、`WaitStrategy`、`WaitingFor*`、自定义 notifier/registrar 与 `sendAndWait` 调用；对照目标 tag 的 `WaitPlan`、`CommandWait` 和 HTTP wait header 行为。当前 v8 gateway 还拥有绝对等待 deadline，不能只做类型替换。
5. **Message subscription**：检查旧 `MessageBus.receive(Set<NamedAggregate>)`、dispatcher launcher、Reactor Context receiver-group helpers 和自定义 bus；迁到目标版本显式 `MessageSubscription` 后，重新验证 aggregate scope 与 consumer group 隔离。
6. **OpenAPI/WebFlux 扩展**：检查自定义 `RouteSpec`、`RouteSpecFactory`、`GlobalRouteSpecFactory`、`AggregateRouteSpecFactory` 与 `RouteHandlerFunctionFactory`；当前 v8 使用 `RouteContributor`、`HttpRouteContract` 与 `HttpRouteHandlerFunctionFactory`，必须重新生成和 golden-diff 路由契约。
7. **生成契约**：重新生成 KSP metadata、OpenAPI、JSON Schema 和 client；对 nullable、枚举、包名、title/description 与路由做 golden diff。

同时检查 Boot 4 的配置命名和 auto-configuration 包结构。特别审计 MongoDB、Elasticsearch、Jackson 配置以及 `spring.autoconfigure.exclude` 中写死的类名；YAML 层级配置不能只靠字符串替换，应与目标 Boot configuration metadata 对照。

静态扫描只能发现名称命中，无法证明调用语义、安全默认值、序列化 wire format 或消费者兼容。

## R2DBC 与 sharding 移除

当前 v8 已删除 v6 的 `wow-r2dbc` module、`r2dbc-support` feature、`wow.r2dbc.*` 配置、`me.ahoo.wow.r2dbc.*` 类型，以及 core `me.ahoo.wow.sharding.*`。这不是改包名即可完成的源码迁移。

若目标应用命中任一项：

1. 标记为迁移 blocker，不替用户猜测新 store。
2. 盘点 event stream、snapshot、shard mapping、transaction boundary、query/read model 与恢复流程。
3. 让用户选择受支持的目标 store 或维护独立 adapter，并明确数据转换、双写禁令、停机、验证与回滚方案。
4. 用目标 store TCK/集成测试和真实数据 rehearsal 证明迁移；仅让代码编译不能关闭该 blocker。

## 统一运行时

仅在目标版本包含统一 `WowRuntime` 且应用存在自定义生命周期时启用本 track。

- 让一个 `WowRuntime` 拥有全部 `RuntimeComponent`；不要保留每个 Dispatcher 的独立 launcher/owner。
- 在 Spring 中只保留一个 canonical `WowRuntimeLifecycle`。自定义 `WowRuntime` Bean 必须避免 Spring 根据 `AutoCloseable.close()` 推断第二个 destroy owner。
- 验证 prepare 全部完成后才 start、全局 admission、tail work、stable quiet period、共享 deadline、fatal failure 与 reverse-order cleanup。
- 把生命周期源码适配与数据迁移分开：它不改变 event、snapshot、message 格式。
- 使用目标 tag 的 runtime 文档和源码，因为该能力在 v8 小版本间仍可能演进。

当前仓库证据入口：

- `documentation/docs/zh/guide/migration/runtime-orchestration.md`
- `documentation/docs/zh/guide/advanced/runtime-lifecycle.md`
- `wow-core/src/main/kotlin/me/ahoo/wow/runtime/`
- `wow-spring/src/main/kotlin/me/ahoo/wow/spring/WowRuntimeLifecycle.kt`

## SnapshotStore

目标为当前 v8.9.6 时应用以下原子保存约束，并在其他目标 tag 上重新确认引入版本。版本化 checkpoint 是 v8.9.0 引入后又移除的能力；直接从纯 v6 升级通常不会命中，只有应用或中间迁移分支曾试用该能力时才执行对应清理：

- 版本化 snapshot checkpoint 已移除，且没有兼容层。仅在实际命中时删除 `VersionedSnapshotStore`、`VersionIntervalCheckpointStrategy`、`CompositeSnapshotStrategy`、相关 metrics/tracing decorator 与 `SnapshotCheckpointProperties` 的应用侧依赖。
- `wow.eventsourcing.snapshot.checkpoint.*` 不再生效；中间环境既有的 `*_snapshot_checkpoint` collection 不会被读取、写入、扫描或自动删除。
- `SnapshotStore.save()` 要求一次原子 compare-and-write：candidate version 大于或等于 stored version 时完整替换，较低时正常完成但不写入。同版本覆盖是 snapshot regeneration 的修复语义。
- 当前 `SnapshotRepository` 只是 Kotlin `typealias SnapshotStore`。它可以帮助 Kotlin 源码迁移，但不证明 Java 调用者或已编译扩展的 JVM 二进制兼容；这些消费者必须重新编译。
- 自定义 store 必须使用 CAS、条件更新、事务或等价原子原语。客户端先 `load()` 再无条件写入不满足契约。
- 当前 Mongo 实现依赖 MongoDB 5.2+ 的 MQL 表达式；部署前验证真实服务端版本。

快照格式不因原子保存契约而要求重写，但新旧 writer 并行仍可能破坏单调性。

## Redis

目标为 v8.9.0+ 时，把 Redis EventStore/SnapshotStore/PrepareKey 当成存储格式硬切换：

- v6/v8.6 legacy layout、v8.8 bucketed layout 与 canonical v2 不兼容。
- v8.9.0+ runtime 只读写 canonical v2；没有旧布局回退、双读、双写或内置迁移器。
- 同一 named aggregate 下的 `AggregateId.id` 必须跨 tenant 唯一。
- starter 启动守卫只检查可精确识别的旧哨兵，不替代离线全量 inventory；自定义 store、已移除 aggregate、snapshot-only route 和孤立 Key 可能不在守卫覆盖范围。
- resolved context alias 与 aggregate name 构成持久化 scope；alias 或 aggregate rename 需要显式 Key 迁移。
- canonical v2 request-ID SET 必须从已提交 event JSON 重建；禁止把 legacy shared SET 直接 fan-out 到所有 stream。
- 128 bucket 公式使用 `aggregateId.id.hashCode().mod(128)` 与 Java/Kotlin UTF-16 `String.hashCode`；迁移器必须独立校验 codec。
- 旧 snapshot 不会自动转成 v2 snapshot。迁移后显式执行 snapshot regeneration 并验证数量与版本，默认从完整 inventory 使用单 ID 路由。只有审计证明全部 ID 严格大于 `AggregateIdScanner.FIRST_ID`（`"(0)"`）时，batch 路由才可视为穷尽；否则它会漏掉小于或等于该 sentinel 的 ID。

迁移 manifest 至少记录 source Key、type、cardinality、target Key、source/target checksum、批次、cursor、状态与处置策略。第一次写入前要求 target scope 为空；恢复执行时 checksum 不一致必须失败关闭。

当前仓库证据入口：

- `documentation/docs/zh/guide/migration/v6-to-v8.md`
- `documentation/docs/zh/guide/extensions/redis.md`
- `wow-redis/src/main/kotlin/me/ahoo/wow/redis/eventsourcing/RedisEventStore.kt`
- `wow-redis/src/main/kotlin/me/ahoo/wow/redis/RedisKeyComponentCodec.kt`
- `wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/redis/RedisEventSourcingAutoConfiguration.kt`

## MongoDB

目标为当前 v8.9.6 时应用以下约束，并在其他目标 tag 上确认：

- collection 名仍以 aggregate name 为核心，但 database 新增 `wow_database_metadata` bounded-context ownership marker。
- 支持的布局是一个 Mongo database 只属于一个 bounded context。历史混写 database 必须先拆分，不能改 marker 绕过冲突。
- inventory 必须覆盖 event-stream、snapshot、PrepareKey database，以及 `*_event_stream`、`*_snapshot`、`*_snapshot_checkpoint`、`prepare_*` collection。
- prepare-only database 的旧文档没有 context metadata；必须在首个 v8 context 认领前人工确认归属。
- 受管索引缺失时会创建；key 顺序、unique、TTL、partial filter、collation、sparse 或 hidden 不兼容时应阻止启动并受控迁移。
- 自定义 `SnapshotStore` 或当前 Mongo snapshot 实现还需满足上一节的原子保存与服务端版本要求。

当前仓库证据入口：

- `documentation/docs/zh/guide/extensions/mongo.md`
- `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/MongoDatabaseContextGuard.kt`
- `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/MongoSnapshotStore.kt`

## 切换与回滚

- 禁止新旧 writer 混跑；使用停流、排空、离线迁移、单 v8 实例、硬切、仅扩容 v8 的顺序。
- 尚无 v8 生产写入时，回滚可重新连接完整保留的旧数据集并启动 v6。
- 已有 v8 生产写入时，先停止流量和全部 v8 writer，再反向迁移或重放新增写入；仅恢复切换点备份会丢数据。
- 优先使用独立 target database/namespace，让旧源在观察期只读保留。
- 不把应用启动、健康检查或单条读写当成全量数据门禁。

## 验证矩阵

| 层级 | 必要证据 |
|---|---|
| Dependency | resolution、冲突与第三方 starter 兼容报告 |
| Compile | 全部 domain、server、adapter、test fixtures 与 KSP processor consumers 编译 |
| Behavior | aggregate/saga/projection/query/command wait 的相关单元与集成测试 |
| Contract | metadata、OpenAPI、JSON Schema、SDK 的 regenerated golden diff |
| Storage | inventory、checksum、版本连续性、request-ID 集、ID index、代表性 replay |
| Runtime | startup、readiness、fatal、drain、quiet period、deadline、graceful shutdown |
| Cutover | 停流/排空证据、单实例 smoke、灰度监控与只扩容 v8 |
| Rollback | 无新写入与已有新写入两条路径的演练记录 |

任何一行缺少与其范围匹配的当前证据，都保持未完成状态。
