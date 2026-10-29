# V10 读平面与契约

日期：2026-10-11。基线：9.8.0（`v9` 上的 `b5813af56f`）。状态：已实现（实现以代码为准，偏离记在决策的「实现」说明里；2026-10-22 复核分支与路径）。上位文档：[00 目标架构](00-architecture.md)（A9、A11）。相关决定：D4、D16、D17、D18、D125～D131、D150～D152。

## 1. 读平面

9.x 的查询协议与数据集设计基本保留（现行规则见 D125～D131、D235、D238、D254、D259），它们已经是从第一性原理推出的。V10 只做四件事：

- 查询协议独立为 `wow-query-api`，只保留 `FilterExpression`，删除 Condition 全链路。
- 快照是默认读模型（[03](03-log-and-snapshot.md)），自定义投影只补快照满足不了的需求；查询后端（Mongo、ES、PostgreSQL、ClickHouse）只是它们的存储。
- 事件流可按聚合、按时间查询，用于审计与时间线。
- 数据集源表由 ClickHouse Sink 写入，与企业自己的表一视同仁。

**M6 的定稿**（D125～D131）：

- **模块**：`:query-api` 是 9.x `api.query` 的纯数据移植，包名不变、不带 Jackson，查询字段注解也在这里（D125）；`:query` 是 9.x `wow-query` 的协程移植，身份是显式的 `QueryCaller`，schema 由 Manifest 的 `$defs` 加 `x-wow-*` 关键字编出，准入、预算、游标与残余窗口不变，数据集模型也在这里（D126）。
- **后端**（D131）：M6 只做 PostgreSQL 与 MongoDB 的快照查询、限一个聚合的 `events` 模型（按版本或时间范围，由 `EventLog.read` 提供；**D235 撤销这一限制**：`events` 收进统一引擎，记录是事件流，能力由后端声明，按事件分析用 `expand("events")`）与 ClickHouse 数据集后端。Elasticsearch 是 M6 之后的 6.7；自定义投影的查询模型、跨聚合的时间线与 ClickHouse 作快照查询后端推迟。
- **调用方**（D165）：命令与查询共用 `Caller` 与一个 `CallerResolver`；查询的 `QueryCaller` 只多执行上下文（入口、选择）。操作人的显示名由前端展示层按 `operator.id` 自行解析。
- **操作人**（D163）：快照的系统字段恢复 `operator` 与 `firstOperator`（9.x 同名），可查询、可排序。
- **归属**（D189）：`TENANT` 聚合（如商品）的查询不追加调用方的所有者条件，只按租户与空间；`OWNER` 聚合照常按调用方的作用域过滤。租户级调用方（作用域无所有者）读本租户全部聚合，动作由 CoSec 的动作级授权保护。`QueryCaller` 多一个 `scope`（准入追加的可信作用域过滤），`caller = null` 表示受信的进程内调用（D165 实现）。
- **作用域**（D129）：准入总是追加租户（`TenantPolicy`）、调用方的所有者与空间、默认只查未删除的；ABAC 以 `abac.*` 调用方属性匹配快照 `tags`，在 `:query` 里通用实现，默认关闭，不依赖 CoSec。
- **计数与性能**（D150～D152）：`count` 与分页总数默认有界（`{count, exact}`，分页 `total` 默认 `bounded`），每个查询带预算给出的执行超时（`wow.query.timeout`）；快照表自带覆盖作用域谓词的部分索引，状态字段的索引由 `@QueryIndex` → `x-wow-index` 声明、后端生成；计数结果以快照写入检查点为校验值缓存（6.2d）。`count` 只承担带过滤、有界的计数，固定维度的统计走投影计数器或 ClickHouse 数据集。

## 2. Manifest

`wow-compiler` 在编译期生成 Manifest，服务端在运行时发布同一份（D17）。一个服务的领域往往分散在多个 Gradle 模块与 jar 里，KSP 只能看到当前模块（R6）：所以每个模块产出一个 Manifest **片段**与绑定索引，放在固定的资源路径下；运行时启动时汇总全部片段，同一上下文、聚合、命令或事件类型名重复声明即启动失败。内容：限界上下文、聚合、命令与事件的 schema 及 revision、每个命令可能产出的事件类型（由签名推出，编译期校验 decide 只产出声明过的事件）、路由、错误码、查询模型与路由（不含能力，D128）、订阅与等待阶段、发布的事件流。

```text
注解 / DSL ─▶ wow-compiler ─▶ Manifest ─┬▶ HTTP 路由（运行时）
                                       ├▶ OpenAPI（投影）
                                       ├▶ wow-generator ─▶ TS 客户端
                                       ├▶ view-engine 的能力与默认视图
                                       └▶ Agent Skills
```

**读者**：运行时（命令按类型名解码、HTTP 路由、等待链校验、错误码、查询路由）、OpenAPI / TS 生成器 / view-engine、其他上下文（发布的 `StateEvent` 是公开语言，D23、D58）、迁移工具与 Skill（事件名、revision、别名）、兼容性门禁。

**结构**（以 `wow-manifest` 的数据类为准，下列为主要字段）：

```text
{
  "format": 1,
  "service": { "name", "version" },
  "contexts": [{
    "name", "description",
    "aggregates": [{
      "name", "description", "ownership", "retired", "schedules",
      "state":    { "schema": "#/$defs/…", "fingerprint" },
      "snapshot": { "enabled", "stateRevision" },
      "commands": [{ "name", "aliases", "schema", "creation", "produces": [事件名…],
                     "routes": [{ "method", "path" }], "mounted", "transformsTo" }],
      "events":   [{ "name", "aliases", "revision", "schema" }],
      "lifecycle":[启用的系统命令],
      "queries":  [{ "model": "snapshot | events", "routes" }]
    }],
    "subscriptions": [{ "name", "kind": "PROJECTION | REACTOR | SAGA",
                        "source": { "context", "aggregates", "events", "nullableState" },
                        "function": FunctionInfo, "derives": [命令名…], "calls": [命令名…],
                        "rejected", "committed", "start", "retry", "schedules", "job" }],
    "published": [{ "topic", "aggregates", "includeState" }],
    "errors":    [{ "code", "status", "description" }]
  }],
  "$defs": { JSON Schema 2020-12 }
}
```

错误码登记在上下文的 `errors`，命令不各自列出。

- **schema 按 Jackson 的实际行为生成**（D73）：KSP 产出结构与类型清单（片段）；构建期的 Gradle 任务加载编译后的类，用 Jackson 自己的序列化器内省出 JSON Schema。schema 因此就是线上的真实形状（命名策略、`@JsonProperty`、自定义序列化器、Kotlin 默认值都由 Jackson 决定，不在 KSP 里重新实现）；状态 schema 指纹（快照，[03](03-log-and-snapshot.md) §3）也由它计算。任务由 `wow-gradle-plugin` 提供（D83），生成器用 victools 加其 Jackson 模块，自定义序列化器由显式覆盖配置补上（D84）。内省只在构建期，运行时仍不反射（D37）。
- **组装与发布**（D74）：每个模块的片段放在 `META-INF/wow/manifest/<模块>.json`，运行时启动时合并，名字冲突拒绝启动；Gradle 任务同时合并出服务的 `manifest.json` 供离线工具使用。服务在公开端口发布 `GET /wow/manifest`，按普通 API 鉴权；跨上下文的下游从上游的构建产物或该端点取得，构建期生成类型化的消费代码。
- **v0 的实现要点**（Ahoo-Wang/Wow#4259）：
  - `wow-manifest` 是不带依赖的纯数据模型，合并函数一次报出全部冲突：同一上下文出现在两个片段、聚合重复、命令或事件全名重复（别名也算）、订阅名重复、同一 `$defs` 键的 schema 不同、片段格式不同。
  - KSP 在 `META-INF/wow/manifest/<模块>.json` 写出片段，schema 先以类的二进制名占位。上下文由 KSP 选项 `wow.context` 指定，缺省取模块里唯一的 `@BoundedContext`，都没有时只告警、不写片段。签名推不出产出哪些事件时（如 `Decision`、`Any`）省略 `produces`。
  - 插件 `me.ahoo.wow` 提供两个任务：`generateWowManifestFragment` 补全片段 schema，`mergeWowManifest` 合并出 `build/wow/manifest.json`（暂不挂到 `assemble`）。内省代码在 `wow-manifest-tools`，由用户项目里的 `wowManifestTools` 配置解析，只进隔离 Worker 的类路径，不进构建脚本的类路径（D83）。
  - Jackson 写出来不是对象的类型（自定义序列化器、`@JsonValue`），要在 `schemaOverrides` 显式给出 schema，否则任务失败，不猜（D84）。`$defs` 的键是类的二进制名，只引用一次的类型内联。
  - 状态 schema 指纹调用内核的 `EventCodec.stateSchemaFingerprint`，与运行时一致。限制：运行时若定制了 ObjectMapper，构建期的值会不同。
- **查询**（D128，修订上面草案原先的上下文级、带 `capabilities` 的 `queries`）：`queries` 在聚合下，编译器在开启快照时写 `snapshot`，另加 `events`；能力不进 Manifest，因为它取决于按类路径选择的后端（D110），由查询路由的 `GET` 返回（D127）。可查询的快照字段：`tenantId`、`aggregateId`、`version`、`ownerId`、`spaceId`、`deleted`、`tags`、`firstEventTime`、`lastEventTime`、`state.*`；9.x 的 `eventId`、`snapshotTime` 去掉（`operator`、`firstOperator` 由 D163 恢复，见 §1）。
- **对端契约**（D139，由 D234 修订）：上游 domain 的编译器片段筛出对端需要的部分，装进上游 `*-api` 的 jar（`META-INF/wow/contract.json`）；下游只依赖上游的 api，运行时从类路径加载进 `ContractDirectory`，用于发送前的命令校验、跨上下文的链校验与命令类型名；`wowPeers` 删除。
- **数据集不并入**（D75）：数据集由 analytics 目录服务（`platform/analytics/catalog`，D238、D251）在运行时提供，不进 Manifest（D267 ⑦）；Manifest 只描述编译期的代码契约，view-engine 两者都读。
- **兼容性门禁**（D76）：CI 比较本次构建与上一个已发布版本的 Manifest，把差异分为加法（新增可选字段、新增命令）与破坏（删除命令或事件、新增必填字段、事件 schema 变化却没有升 revision 或没有升级函数）。主版本内出现破坏即失败（D48、D59、R5-1）；线上契约的兼容性由它守，不靠 ABI 转储。


## 3. REST（D16）

M5 的定稿（D92～D118）：准入顺序与扩展点（D92）、`Caller` 与 CoSec（D93、D95～D97）、`Wow-Wait` 语法与 `accepted`（D98～D100）、problem+json 与状态表（D101～D103）、路由（D104、D112、D113）。状态表：`wow.auth.unauthenticated` 401；`wow.auth.forbidden`、`wow.aggregate.forbidden` 403；`wow.command.malformed`、`wow.command.invalid`、`wow.wait.invalid`、`wow.command.unhandled` 400；`wow.aggregate.not-found` 404；`wow.aggregate.exists`、`wow.aggregate.version-mismatch`（带 `If-Match` 时 412）、`wow.command.request-id-reused`、`wow.command.contended` 409；`wow.aggregate.deleted` 410；`wow.command.busy` 429；`wow.command.unknown`、`wow.node.closing` 503；`wow.subscription.parked` 500；`wow.wait.timeout` 504；领域拒绝默认 422。M6 的查询（D130）：`wow.query.malformed`、`wow.query.invalid` 400（每条 violation 带 9.x 的码，如 `UNKNOWN_FIELD`），`wow.query.failed` 500。 此后新增的框架错误码（`wow.command.failed`、`wow.command.busy`、`wow.storage.unavailable`、`wow.schedule.not-due`、`wow.schedule.job-invalid`、`wow.query.timeout`、`wow.call.command-forbidden` 等）以 `FrameworkErrors.ALL` 与 Manifest 的框架错误为准：每个码在目录里声明状态、`retry`（`none` \| `same-request` \| `new-request`）与 `retryAfter`，每个 problem 带 `retry` 成员，可重试时带 `Retry-After`（D366 ②）；传输层自己的失败也映射为目录里的码，如节点不可达为 `wow.peer.unavailable`（503，`new-request`）。本表不再手工维护。

- **身份来自主体，不来自路径**：租户、所有者、空间取自认证主体的作用域；管理员跨作用域时用唯一的作用域请求头。9.x 按租户、所有者变体复制的路由不再复制。
- 命令：`POST /{context}/{aggregate}/{id}/{command}`；创建：`POST /{context}/{aggregate}/{command}`。
- 等待：`Wow-Wait` 与 `Wow-Wait-Timeout` 请求头，默认 `snapshot`（D69）；链的写法与流式等待（SSE）见 [04](04-subscriptions.md) §6.1。
- 错误：RFC 9457 `application/problem+json`，`type` 指向 Manifest 中的错误码。
- 查询（D127）：`POST /{context}/{aggregate}/{model}/query`，`model` 为 `snapshot` 或 `events`（投影名推迟，D131）；请求体的 `kind` 为 `list`、`paged`、`cursor`、`single`、`count`、`aggregate` 之一，`Accept: text/event-stream` 时流式返回 `list`；同一路径的 `GET` 返回能力描述并带 `ETag`。HTTP 把 `Caller`（`crossScope` 时加 `Wow-Scope`）映射为 `QueryCaller`，路由级授权仍由 CoSec WebFilter 负责（D93、D129）。 `events` 模型按聚合模型当前的读法返回事件：用当前名、升级到当前 revision 的 body，退役事件不返回；未知名字或比模型更新的 revision 原样返回（审查 A4-16），与生成的 TS 事件联合类型一致。
- 历史状态（D40，[03](03-log-and-snapshot.md) §4，Ahoo-Wang/Wow#4317）：
  - `GET /{context}/{aggregate}/{id}/state?version=`、`?at=<ISO-8601>` 或 `?minVersion=N`（三者互斥；`minVersion` 要求最新状态不低于 N，否则有界等待 `wow.query.state.min-version-wait` 后 409 `wow.query.min-version-not-reached`，D267 ①）；`ETag` 为 `"<版本>-<响应摘要>"`，支持 304。按时刻读以提交时间随版本递增为前提。
  - 逐事件追溯 `GET …/{id}/state/trace?from=&to=&limit=`，`limit` 默认 100、最大 1000，超过即拒绝；返回 `{ref, commits[]}`。
  - 参数不合法：400 `wow.query.invalid`（回放过长带 `REPLAY_TOO_LONG`，上限 `wow.query.state.max-replay`，默认 10000）；在第一次提交之前或区间为空，或状态、某次提交调用方看不到：404 `wow.aggregate.not-found`（D254 ④，原因只进审计）。
  - 读路由经 `WowJson` 输出精确的数字（D122）。

## 4. TypeScript

- `wow-client` 只保留 `FilterExpression` 与 Manifest 驱动的客户端，删除 `/legacy` 与 fetcher 旧名。
- `wow-generator` 以 Manifest 为主输入，生成类型化的命令、查询与状态客户端；也可读任意 OpenAPI 3 文档生成模型与装饰器客户端（OpenAPI 路径不带 Wow 语义）。
- springdoc 桥接（D233）：有 springdoc 的 Spring Boot 宿主把 Manifest 的 OpenAPI 合并进 springdoc 的默认文档并另设 `wow` 分组；冲突即启动失败；securitySchemes 由宿主按调用方解析器填写；只含公开端口的路由。
- view-engine 从 Manifest 派生默认视图。

## 5. 待细化

- Manifest 字段的定稿（5a.1）与 JSON Schema 内省对 Kotlin 特性（值类、sealed 层级、默认值）的覆盖。
- OpenAPI 投影的保真度（是否保留 `x-wow-*` 供第三方工具）。
- view-engine 的哪些能力描述改由 Manifest 承担。
