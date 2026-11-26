#7208 ## Upgrades paragraph
None: records only.

#7206 ## Upgrades paragraph
**English.** A state read and a query model's or a dataset's descriptor, the answers Wow tags with an `ETag`, now carry `Cache-Control: private, no-cache`, their 304 too. A shared cache in front of a service (a CDN, a caching proxy) stores none of them, since each depends on its caller. A client's own cache keeps them and revalidates with `If-None-Match`, a 304 when nothing changed. A deployment that cached state reads at its gateway stops doing so; it was answering one caller with another's.

**中文。** 状态读取，以及查询模型或数据集的描述符，即 Wow 带 `ETag` 的应答，现在都带 `Cache-Control: private, no-cache`，它们的 304 也带。服务前面的共享缓存（CDN、缓存代理）不再存储其中任何一个，因为每一个都取决于调用方。客户端自己的缓存可以保留它们，并用 `If-None-Match` 重新验证，没有变化时得到 304。在网关上缓存状态读取的部署不再缓存它们：那样做会把一个调用方的应答给了另一个调用方。

#7205 ## Upgrades paragraph
**English.** A `Wow-Scope` header that does not parse (a member given twice, a member with no value, several tenants on a route other than a query's) is now answered `400 wow.request.malformed` on every route. Before, it was `wow.command.malformed` on a command, `wow.query.malformed` on a read or a dataset route, and `wow.route.malformed` on a service route. What a well-formed `Wow-Scope` names and the type refuses keeps its route's code. A client that matched one of the old codes for this header matches `wow.request.malformed`.

**中文。** 无法解析的 `Wow-Scope` 请求头（同一个成员给出两次、成员没有值、在查询以外的路由上指明多个租户）现在在每个路由上都回答 `400 wow.request.malformed`。之前，命令上是 `wow.command.malformed`，读取或数据集路由上是 `wow.query.malformed`，服务路由上是 `wow.route.malformed`。格式正确的 `Wow-Scope` 指明了类型拒绝的内容时，仍保留所在路由的码。按旧码识别这个请求头的客户端，改为匹配 `wow.request.malformed`。

#7202 ## Upgrades paragraph
**English.** A request body that stalls, with nothing of it arriving for 30 seconds, is now answered `408` and its connection closed, on the public port and the internal port. So is a body still arriving after its request was answered (a `401`, a `404`) that stalls as long. `wow.http.read-timeout` sets the bound. It times the gap between two reads, not the whole request: an upload that keeps sending is not cut however long it takes, and neither is an answer, a query stream or a wait. A client that must pause its body for longer raises the key on the service. The timeout counts the time nothing of the body is read, whoever stops the reads: an application's own route that reads its body as a stream and asks for none of it for 30 seconds (subscribing late, pausing between pieces) is answered `408` too, while its client is still sending; raise the key, or read the body without pausing. A client that hangs up mid-request is no longer logged as an unexpected error.

**中文。** 停顿的请求体，即连续 30 秒没有任何内容到达，现在会得到 `408` 回答，连接随之关闭，公共端口和内部端口都是如此。请求已被回答（`401`、`404`）之后仍在到达、并停顿同样久的请求体也一样。`wow.http.read-timeout` 设置这个上限。它计时的是两次读取之间的间隔，而不是整个请求：持续发送的上传无论多久都不会被切断，回答、查询流和等待也不会。确实需要让请求体停顿更久的客户端，在服务上调高这个配置项。它计时的是请求体没有被读取的时间，不论是谁停止了读取：应用自己的路由如果以流的方式读取请求体、并在 30 秒里不再请求任何内容（晚订阅，或在两段之间暂停），也会得到 `408`，即使客户端仍在发送；这时应调高这个配置项，或者不停顿地读取请求体。在请求中途断开的客户端，不再被记录为意外错误。

#7203 ## Upgrades paragraph
**wow-view-engine: one word for each thing.**
- The host's resolver for remote candidates is `ViewEngineOptions.optionSource` (it was `resolveOptions`), as the engine's `ViewEngine.optionSource` is.
- A date column's bucket still under way is `AnalysisColumnView.unfinished` (it was `ongoing`). A metric card whose only period has not ended has `MetricPeriod.unfinished` (it was `partial`).
- `/react`:
  - read a view list again with `ViewListState.refresh` (`reload`, with `ViewListRefreshOptions`);
  - `destinationBoard` is `destinationDashboard`, `toBoard` is `toDashboard`, and `SaveCommands.boards` is `dashboards`.
- `/ui`: `RecordViewProps` is `RecordViewOptions`, `ExportWindowProps` is `ExportWindowOptions`, and `BUILT_IN_PRESETS`/`BuiltInPreset` are `BUILTIN_PRESETS`/`BuiltinPreset`.

**wow-view-engine：每样东西一个词。**
- 宿主提供远端候选来源的选项改为 `ViewEngineOptions.optionSource`（原为 `resolveOptions`），引擎上的 `ViewEngine.optionSource` 同名。
- 日期列里还没结束的那一桶改为 `AnalysisColumnView.unfinished`（原为 `ongoing`）；指标卡唯一的一期还没结束时，`MetricPeriod.unfinished`（原为 `partial`）。
- `/react`：
  - 重新读取视图列表用 `ViewListState.refresh`（原为 `reload`，选项类型 `ViewListRefreshOptions`）；
  - `destinationBoard` 改为 `destinationDashboard`，`toBoard` 改为 `toDashboard`，`SaveCommands.boards` 改为 `dashboards`。
- `/ui`：`RecordViewProps` 改为 `RecordViewOptions`，`ExportWindowProps` 改为 `ExportWindowOptions`，`BUILT_IN_PRESETS`／`BuiltInPreset` 改为 `BUILTIN_PRESETS`／`BuiltinPreset`。

#7201 [no Upgrades? paragraph]
#7200 [no Upgrades? paragraph]
#7199 [no Upgrades? paragraph]
#7198 ## Upgrades paragraph
None: the change is the page itself.

#7197 ## Upgrades paragraph
The `wow-service` chart now refuses a `config` key that names one of the keys it sets itself in any spelling Spring binds (such as `WOW_WAIT_MAX_TIMEOUT` or `server_port`), as it already refused them in `settings` and `env`; such a key used to be rendered and silently shadowed. Remove it, or set the chart's own value the message names.

`wow-service` chart 现在会拒绝 `config` 里以 Spring 能绑定的任何拼法写出的、它自己设置的键（例如 `WOW_WAIT_MAX_TIMEOUT` 或 `server_port`），与它此前在 `settings` 和 `env` 里的做法一样；这样的键以前会被渲染出来，却被悄悄遮蔽。删掉它，或者设置提示里写的 chart 自己的值。

#7195 ## Upgrades paragraph
With `spring.main.lazy-initialization=true`, a refused `wow.health` configuration (`parked-degraded-at` past `wow.node.max-parked`, a liveness stall timeout not longer than the cluster's reconcile timeout) now refuses the node's start, as it does without lazy initialization, instead of failing its first health probe.

开启 `spring.main.lazy-initialization=true` 时，被拒绝的 `wow.health` 配置（`parked-degraded-at` 超过 `wow.node.max-parked`、存活检测的停滞超时不长于集群的 reconcile 超时）现在会让节点启动失败，与不开懒加载时一样，而不是等到第一次健康探测才失败。

#7194 ## Upgrades paragraph
**English.** A record a service publishes on its state topics no longer carries the client's address or `User-Agent` (`remote_ip`, `user_agent`) among its `headers`. They are personal data, which stay with the service the request reached, in its log and its own stores. `app_id` and the request facts the host lists go on. A consumer in another context that read them reads them from the source service's own records instead, if it is allowed to.

**中文。** 服务发布到状态主题的记录，其 `headers` 不再带客户端的地址和 User-Agent（`remote_ip`、`user_agent`）。它们是个人数据，留在请求到达的那个服务里，在它的日志和它自己的存储中。`app_id` 和宿主列出的请求事实照常发布。原来读取它们的其他上下文的消费方，如有权限，改为从源服务自己的记录中读取。

#7189 ## Upgrades paragraph
The service plugin's task that renders the Kubernetes manifests is `generateWowKubernetes` (it was `generateWowManifests`): a build script or CI that names it changes the name. The constants of `WowPlugin`'s companion other than the plugin ids (`ID`, `API_ID`, `SERVICE_ID`) are internal; a build script names the tasks and configurations by their strings.

服务插件中渲染 Kubernetes 清单的任务改名为 `generateWowKubernetes`（原为 `generateWowManifests`）：点名它的构建脚本或 CI 需要改用新名字。`WowPlugin` 伴生对象中除插件 id（`ID`、`API_ID`、`SERVICE_ID`）以外的常量改为 internal；构建脚本用字符串点名任务与配置。

#7193 [no Upgrades? paragraph]
#7191 ## Upgrades paragraph
The internal port's call routes now refuse a path with matrix parameters, as the public port does: a call to `/shop/cart/c-1;x=1/add-item` is answered 400 `wow.request.malformed`. A Wow client is not affected.

内部端口的调用路由现在与公共端口一样，拒绝带矩阵参数的路径：对 `/shop/cart/c-1;x=1/add-item` 的调用会得到 400 `wow.request.malformed`。Wow 的客户端不受影响。

#7192 ## Upgrades paragraph
None: records only.

#7190 [no Upgrades? paragraph]
#7183 ## Upgrades paragraph
A subscription that reads another context's state topic now parks an aggregate when a record arrives above the version after the last one it saw, at the version missing, and holds the aggregate's later records behind it. Its automatic retries resolve it in order once a late record arrives. An operator's retry accepts the gap and goes on from the next version present, as for a commit the publishing service skipped; a skip drops the held records. The parked reason names `wow.subscription.version-gap`.

读取另一个上下文状态主题的订阅，现在在一条记录的版本高于它最后见到的版本加一时，把聚合停放在缺失的版本上，并把这个聚合之后的记录扣在后面。迟到的记录到达后，自动重试会按顺序解决它。运维人员的重试接受这个缺口，从下一个存在的版本继续，适用于发布方服务跳过的提交；跳过则丢弃扣下的记录。停放的原因写明 `wow.subscription.version-gap`。

#7185 ## Upgrades paragraph
**The internal port's readers compare the format of what they read.** A node refuses a forward of a format it does not read (`refused: "unknown-format"`) and one it cannot read at all (`refused: "malformed"`, where it answered 400), so its sender runs the command itself; it answers 400 to wait signals of another format and to a `Wow-Call` of another (`wow.command.malformed`), and reads a forward's reply of another format as an unknown outcome. The cluster channel's forward and reply name their format `format`, where they named it `v`, and `Wow-Call` carries one. A batch of wait signals refused as too large is sent again in halves instead of dropped. Every structure is format 1, so nothing changes between nodes of this version.

**内部端口的读者核对所读内容的格式。** 节点拒绝它不读的格式的转发（`refused: "unknown-format"`）与完全读不了的转发（`refused: "malformed"`，以前回 400），于是发送方自己执行该命令；对另一格式的等待信号与另一格式的 `Wow-Call` 回 400（`wow.command.malformed`），把另一格式的转发回复读作结果未知。集群通道的转发与回复把格式字段命名为 `format`（以前是 `v`），`Wow-Call` 也带上了格式。因过大被拒的一批等待信号改为对半拆开重发，不再整批丢弃。所有结构都是格式 1，所以本版本的节点之间没有任何变化。

#7186 ## Upgrades paragraph
**English.** The request ids Wow gives its own commands now start with `wow:`: a timer's (`wow:<timer>#<slot>`), a saga's derived commands and compensations, the calls a decide function sends, and the ledger's records. No caller's request id may: a `Wow-Request-Id` (or `CoSec-Request-Id`, or an in-process `CommandRequest.requestId`) that starts with `wow:` is refused as `400 wow.command.malformed`. Otherwise anyone could commit first under an id the framework will send, which settles the framework's command. A command handled again across the upgrade derives the new id, so a saga or a timer whose command an old node already sent would send it again under an id its target does not recognize. Let the sagas and the timers catch up before the rolling update, as for the previous derived-id change: no saga lag, and no timer due within the update.

**中文。** Wow 给自己的命令的请求 ID 现在以 `wow:` 开头：定时器的（`wow:<timer>#<slot>`）、Saga 派生的命令和补偿、决定函数发出的调用，以及台账的记录。调用方的请求 ID 都不能以它开头：以 `wow:` 开头的 `Wow-Request-Id`（或 `CoSec-Request-Id`，或进程内的 `CommandRequest.requestId`）会以 `400 wow.command.malformed` 拒绝。否则任何人都能抢先以框架将要发送的 ID 提交，使框架自己的命令被当作已处理。升级前后被再次处理的命令会派生出新的 ID：旧节点已经发出命令的 Saga 或定时器会以目标认不出的 ID 再发一次。滚动升级之前，先让 Saga 和定时器追上来，与上一次派生 ID 的变更一样：Saga 没有积压，升级期间也没有到期的定时器。

#7187 [no Upgrades? paragraph]
#7179 ## Upgrades paragraph
The runtime's options are named as the configuration keys that set them: `ClusterOptions.maxInFlightPerNode`, `SnapshotOptions.maintenanceInterval`, `rebuildPageSize`, `rebuildMaxPages`, `AccessStampOptions.stampBatch`, `TimerOptions.claimTtl`, `SourceTopics.partitionRefreshInterval` (also `SourceTopics.of`'s parameter), with their defaults. `wow-cosid`'s `CosIdProperties` is `WowCosIdProperties`. A backend's TCK test builds a stream it refuses with `stream.withEvents(...)`.

运行时的选项改为与设置它们的配置键同名：`ClusterOptions.maxInFlightPerNode`、`SnapshotOptions.maintenanceInterval`、`rebuildPageSize`、`rebuildMaxPages`、`AccessStampOptions.stampBatch`、`TimerOptions.claimTtl`、`SourceTopics.partitionRefreshInterval`（也是 `SourceTopics.of` 的参数），默认值常量随之改名。`wow-cosid` 的 `CosIdProperties` 改名为 `WowCosIdProperties`。后端的 TCK 测试用 `stream.withEvents(...)` 构造它拒绝的流。

#7184 [no Upgrades? paragraph]
#7182 [no Upgrades? paragraph]
#7180 ## Upgrades paragraph
**English.** A `wow.storage.unavailable` problem on the operator's parked-entry routes (`/wow/ops/parked/…`) and on the internal port's peer reads is titled "Storage unavailable", the code's title everywhere else, where it was "Service Unavailable". Its code, status, detail and `Retry-After` are unchanged, and a client keys on the code. The OpenAPI document now lists 414 and 431 on every operation, the server's own refusals of a request line or headers past its limits, and declares the `<context>` tag its service routes carry: a client generated from it gains those two responses.

**中文。** 运维停放条目路由（`/wow/ops/parked/…`）和内部端口的对端读取上的 `wow.storage.unavailable` problem，标题现在是“Storage unavailable”，与这个错误码在其他地方的标题相同，原来是“Service Unavailable”。它的错误码、状态、detail 和 `Retry-After` 不变，客户端按错误码判断。OpenAPI 文档现在在每个操作上列出 414 和 431（服务器对超过上限的请求行或请求头的拒绝），并声明服务路由所带的 `<context>` 标签：由它生成的客户端多出这两个应答。

#7181 ## Upgrades paragraph
None: the change is the page itself.

#7178 ## Upgrades paragraph
**`wow_catalog` names its closed sets in lower case, keeps a sensitivity level it does not know, and the ClickHouse Sink refuses an insert a table has no place for.** `ownership` is `owner` or `tenant`, a sensitive `level` `display` or `confidential`, `access` `policy` or `default`; a tool that reads `wow_catalog` itself reads those names. A sensitivity level a node does not know, a later version's, is kept in `wow_catalog` as written and masked as `CONFIDENTIAL`, never less. Every insert of the Sink refuses a field the table has no column or tuple element for, under every `ddl` mode, where ClickHouse dropped it: a pre-release table kept under `ddl: none` parks the aggregate until it is recreated (drop it and raise the Sink's generation). The parked entry's reason names ClickHouse's error code, and the node's warning of the park names the field.

**`wow_catalog` 以小写名称记录其取值并保留不认识的敏感级别，ClickHouse Sink 会拒绝表里没有位置的插入。** `ownership` 为 `owner` 或 `tenant`，敏感值的 `level` 为 `display` 或 `confidential`，`access` 为 `policy` 或 `default`；自行读取 `wow_catalog` 的工具应读取这些名称。节点不认识的敏感级别（更新版本的）会按原样保存在 `wow_catalog` 中，并按 `CONFIDENTIAL` 脱敏，绝不更少。Sink 的每次插入在任何 `ddl` 模式下都会拒绝表里没有对应列或元组元素的字段，而以前 ClickHouse 会把它丢掉：在 `ddl: none` 下保留的预发布版表会让聚合被搁置，直到重建（删掉它并提高 Sink 的代数）。搁置条目的原因写明 ClickHouse 的错误码，节点搁置时的警告日志写明字段。

#7177 ## Upgrades paragraph
**English.** This version runs CoSec 8.0.0, CoSec 8's release. From 8.0.0-rc.1 or rc.2 there is nothing to change and no order to upgrade in: no JVM source of CoSec changed since rc.1. A front end on `@ahoo-wang/cosec-fetcher` installs 8.0.0 without a tag, and may move to fetcher 8 or stay on 6.1. CoSec 8's images publish no `latest`, so pin the gateway by version or digest. `wow-cosec` stays experimental in 10.0: a 10.x minor release drops the marker.

**中文。** 本版本使用 CoSec 8 的正式版 CoSec 8.0.0。从 8.0.0-rc.1 或 rc.2 升级无需任何改动，也没有升级顺序：自 rc.1 以来 CoSec 的 JVM 源码没有变化。使用 `@ahoo-wang/cosec-fetcher` 的前端不带标签安装 8.0.0，可以换到 fetcher 8，也可以留在 6.1。CoSec 8 的镜像不发布 `latest`，所以按版本或摘要固定网关镜像。`wow-cosec` 在 10.0 中仍是实验性的：一个 10.x 的次版本会去掉这个标记。

#7176 ## Upgrades paragraph
**wow-view-engine: the names no host used leave the entries.** The root entry, `/react` and `/ui` no longer export 156 names that only the package itself used: its parts, its internal hooks and helpers, and the types only they named. The release notes list them. A host that imported one has three ways out. It can compose the documented components and hooks instead. It can copy the few lines it needs. Or it can open an issue for the name, which then comes back with a reference page. Import `ViewRouteOf` from the root entry, not `/ui`. Tell a reference-label timeout apart with `isReferenceLabelTimeoutError(error)`.

**wow-view-engine：没有宿主用的名字退出入口。** 根入口、`/react` 与 `/ui` 不再导出 156 个只有包自己在用的名字：它的内部部件、内部 hooks 与工具函数，以及只有它们引用的类型。发布说明列出了这些名字。用过其中某个名字的宿主有三条路：改用有文档的组件与 hooks，把需要的几行抄到自己的代码里，或者为这个名字提 issue，它会带着参考页回来。`ViewRouteOf` 改从根入口导入，不再从 `/ui` 导入。分辨引用名字的查询超时，用 `isReferenceLabelTimeoutError(error)`。

#7116 ## Upgrades paragraph
None: the drill's own files.

#7174 ## Upgrades paragraph
A node now refuses to start when a type's events queries would be served by a store that cannot keep the history's access attributes current, naming the type, the store and the missing capability. Wow's own backends all keep them current, so no deployment on them is affected. A log written outside Wow cannot keep them current in 10.0, so its store does not serve events queries: a node whose events model it would serve does not start.

当某个类型的事件查询要由无法让历史访问属性保持最新的存储提供时，节点现在拒绝启动，并点名类型、存储和所缺的能力。Wow 自带的后端都能保持它们最新，所以基于这些后端的部署不受影响。Wow 之外写的日志在 10.0 中做不到，所以它的存储不提供事件查询：事件模型要由它提供的节点不会启动。

#7173 ## Upgrades paragraph
**English.** A command's event stream now ends with `end` `{"reason":"complete","resumable":false}`, the shape of a streamed list's `end`, where it was `{"reason":"complete"}`. A reader that takes `end` by its name, or reads only its `reason`, as Wow's clients do, is unaffected. The OpenAPI document now lists `end` among the stream's events, and gives an aggregate ref's `id` as a string or null: null in the result of a creation refused before admission chose its id.

**中文。** 命令的事件流现在以 `end` `{"reason":"complete","resumable":false}` 结束，与流式 `list` 的 `end` 形状相同；原来是 `{"reason":"complete"}`。按名字识别 `end`、或只读取它的 `reason` 的读取方（Wow 的客户端就是这样）不受影响。OpenAPI 文档现在把 `end` 列为这个流的事件之一，并把聚合引用的 `id` 写成字符串或 null：在准入选定 ID 之前就被拒绝的创建命令，其结果中的 `id` 为 null。

#7171 ## Upgrades paragraph
**A snapshot table registry names who fills a table by its wire name.** `fill` is `subscription` or `backfill` in every snapshot store, where a pre-release wrote `SUBSCRIPTION`/`BACKFILL`; the stores a 10.0 pre-release wrote are recreated with the upgrade, as its other stores are. **Elasticsearch indices a 10.0 pre-release created are recreated with the upgrade.** The snapshot table registry is mapped `dynamic: false`, as the snapshot and events indices are, so a field a later version adds is kept rather than refused; an existing registry keeps the mapping it was created with. A snapshot document names its `stateRevision`, and an events document holding access attributes names the `accessVersion` they are of: a pre-release's document without them is refused rather than read with a default.

**快照表登记以线上名称记录由谁填充。** 每个快照存储里 `fill` 都是 `subscription` 或 `backfill`，预发布版写的是 `SUBSCRIPTION`/`BACKFILL`；10.0 预发布版写的存储随升级重建，与它的其他存储一样。**10.0 预发布版创建的 Elasticsearch 索引随升级重建。** 快照表登记索引的映射改为 `dynamic: false`，与快照索引和事件索引一致，之后版本新增的字段会被保留而不是拒绝；已存在的登记索引保持创建时的映射。快照文档必须带 `stateRevision`，带访问属性的事件文档必须带它们所属的 `accessVersion`：缺少这些字段的预发布版文档会被拒绝，而不是按默认值读取。

#7172 ## Upgrades paragraph
A state topic now has a record for every commit of a published aggregate: a commit whose events the aggregate all retired is published with no events, and a commit that only sets or cancels schedules with its schedule events. An aggregate's versions on its topic are so contiguous from its first record. A Wow subscription passes over a record with no events; an outside consumer reads `events` as possibly empty. Create a state topic written by a 10.0 pre-release again before a 10.0 release publishes to it.

状态主题现在为发布的聚合的每个提交都有一条记录：事件全部被聚合退役的提交以不含事件的记录发布，只设置或取消调度的提交带着它的调度事件发布。于是一个聚合在主题上的版本从它的第一条记录起连续。Wow 的订阅会略过不含事件的记录；外部消费者要把 `events` 当作可能为空来读。10.0 预发布版写过的状态主题，要在 10.0 正式版向它发布之前重新创建。

#7170 ## Upgrades paragraph
**wow-view-engine: one shape per kernel and option, and Dashboard names.**
- The compile and project kernels take a trailing options bag: `compileRecord(def, config, { kinds, context, page })`, `projectAnalysis(def, config, result, { totals, kinds, context, limits })`.
- Every `limits` option takes a partial `RuntimeLimits`. `validateDashboard`'s `definitions` is `lookup`.
- `actionHarness`'s `now` is a clock (`() => new Date(at)`).
- `fetchRecord` and a value candidate source's `search` take `{ signal }`.
- Renamed:
  - `refusedScopeFilter`, `scopeFilterIssues`, `removeFixed`, `SummaryRow.over`, `RecordExportExtent`;
  - the `Board…` types are `Dashboard…`, and the workbench's `boardOrigin` is `dashboardOrigin`;
  - a binding's `reading` is `detail`;
  - `DashboardWorkbench` takes `defaultFilters` and `defaultTab`;
  - `DatasetResourcesOptions`, `UseDatasetsOptions`;
  - `RuntimeLimits.maxExportRows` and `maxListedViews`;
  - `builtinFieldKindRegistry`.
- `isRecordRuntime` accepts any runtime, or none.

**wow-view-engine：每个内核与选项一种形状，看板类型统一叫 Dashboard。**
- 编译与投影内核改为末位的选项包：`compileRecord(def, config, { kinds, context, page })`、`projectAnalysis(def, config, result, { totals, kinds, context, limits })`。
- 所有 `limits` 选项都接收部分的 `RuntimeLimits`。`validateDashboard` 的 `definitions` 改为 `lookup`。
- `actionHarness` 的 `now` 改为时钟（`() => new Date(at)`）。
- `fetchRecord` 与取值候选源的 `search` 改为接收 `{ signal }`。
- 改名：
  - `refusedScopeFilter`、`scopeFilterIssues`、`removeFixed`、`SummaryRow.over`、`RecordExportExtent`；
  - `Board…` 类型改为 `Dashboard…`，工作台的 `boardOrigin` 改为 `dashboardOrigin`；
  - 绑定的 `reading` 改为 `detail`；
  - `DashboardWorkbench` 改为接收 `defaultFilters` 与 `defaultTab`；
  - `DatasetResourcesOptions`、`UseDatasetsOptions`；
  - `RuntimeLimits.maxExportRows` 与 `maxListedViews`；
  - `builtinFieldKindRegistry`。
- `isRecordRuntime` 接受任意运行时，也接受空值。

#7169 ## Upgrades paragraph
**English.** A command route now refuses any query parameter, on the public and the internal port: `400 wow.request.malformed`, naming it, and the command does not run. A command reads its body and its headers alone, and a parameter used to be dropped without a word. The case most likely to meet it is a client, or a proxy in front of the service, that appends a parameter of its own to a command's URL, such as a cache-buster or a request signature. Send that value as a header instead, or have the gateway strip it before it forwards the request. A service route likewise refuses a query parameter it does not declare, or a declared one given twice: `400 wow.route.malformed`, naming it, where the first value used to be read and the rest dropped.

**中文。** 命令路由现在拒绝任何查询参数，公共端口和内部端口都一样：返回 `400 wow.request.malformed` 并指明该参数，命令不会执行。命令只读取请求体和请求头；以前多出的参数会被悄悄丢掉。最可能遇到这一点的，是在命令 URL 后附加自己参数的客户端或服务前面的代理，例如防缓存参数或请求签名。改为用请求头发送这个值，或者让网关在转发请求之前去掉它。服务路由同样拒绝它没有声明的查询参数，以及给了两次的已声明参数：返回 `400 wow.route.malformed` 并指明该参数；以前会读第一个值，其余的丢掉。

#7168 [no Upgrades? paragraph]
#7167 ## Upgrades paragraph
**The ClickHouse Sink's tables name an event's data `body`.** `_state_events.events` is `Array(Tuple(name, revision, body))` and `_events` has `body`, where both said `payload`; a dataset or a query reads `body`, and the catalog's sensitive paths are `events.body.<path>` and `body.<path>`. The tables a 10.0 pre-release wrote are recreated with the upgrade, as its other stores are: the Sink refuses them, naming the column; drop them and raise the Sink's generation, which fills them from the log. A table may hold columns of its operator's own, which the Sink leaves as they are.

**ClickHouse Sink 的表把事件数据命名为 `body`。** `_state_events.events` 是 `Array(Tuple(name, revision, body))`，`_events` 有 `body` 列，原来都叫 `payload`；数据集与查询读取 `body`，目录中的敏感路径是 `events.body.<path>` 与 `body.<path>`。10.0 预发布版写的表随升级重建，与它的其他存储一样：Sink 会拒绝它们并指出列；删掉它们并提高 Sink 的代数，由新一代从日志重新填充。表里可以有运维自己的列，Sink 不会改动它们。

#7166 [no Upgrades? paragraph]
#7165 [no Upgrades? paragraph]
#7164 ## Upgrades paragraph
An application can now send a snapshot rebuild by its class and name its type in an authorizer: `RebuildSnapshot.of(ids)` and `SnapshotRebuilds.REBUILD_SNAPSHOT` (`me.ahoo.wow.spi.snapshot`), without opting in to internal API. A class that implements one of the SPI seams the host takes (`GlobalIdGenerator`, `AggregateIdGenerator`, `FailureClassifier`, `Observer`, `CommandCaller`, `QueryObserver`) opts in with `@OptIn(WowSpi::class)`, as the Spring Boot guide now says.

应用现在可以按类发送快照重建、在授权器里点名它的类型：`RebuildSnapshot.of(ids)` 与 `SnapshotRebuilds.REBUILD_SNAPSHOT`（`me.ahoo.wow.spi.snapshot`），无需选择使用内部 API。实现宿主取用的 SPI 扩展点（`GlobalIdGenerator`、`AggregateIdGenerator`、`FailureClassifier`、`Observer`、`CommandCaller`、`QueryObserver`）的类，用 `@OptIn(WowSpi::class)` 选择使用，Spring Boot 指南现已写明。

#7162 ## Upgrades paragraph
**English.** A rejection's or a service route refusal's extension named like a member of the problem (`type`, `title`, `status`, `detail`, `code`, `retry`, `violations`, `result`, `version`, `instance`) is never written, even on a problem without that member. Nor is one named `requestId`, `traceId` or `errors`, which Wow keeps for members of its own. A domain that used one of these names renames the extension. The command's `result` still carries it as written.

**中文。** 拒绝或服务路由拒绝的扩展，如果与问题的成员同名（`type`、`title`、`status`、`detail`、`code`、`retry`、`violations`、`result`、`version`、`instance`），一律不会写出，即使问题没有那个成员。名为 `requestId`、`traceId` 或 `errors` 的扩展也不会写出，这些名字由 Wow 留给自己的成员。用了这些名字的领域需要给扩展改名。命令的 `result` 仍然按原样携带它。

#7163 ## Upgrades paragraph
**A published state event names an event's data `body`.** In the record a Kafka envelope carries, each event is `{name, revision, body}` where it was `{name, revision, payload}`, as in the log and the events query model; the envelope's own `payload`, the record, is unchanged. The published state topics a 10.0 pre-release wrote are recreated with the upgrade, as its other stores are. A record of the old spelling that still reaches a subscription parks its aggregate, visibly: retry it once its publisher is upgraded, or skip it.

**发布的状态事件把事件数据命名为 `body`。** Kafka 信封承载的记录里，每个事件是 `{name, revision, body}`，原来是 `{name, revision, payload}`，与日志和事件查询模型一致；信封自身的 `payload`（即记录）不变。10.0 预发布版写的已发布状态主题随升级重建，与它的其他存储一样。仍到达订阅的旧写法记录会让其聚合被搁置，且可见：在其发布方升级后重试，或跳过。

#7161 ## Upgrades paragraph
**MongoDB stores are recreated.** The pre-release layout is not read: start from an empty database. The log stores an event's data as `events[].body`; timers and reservations name their aggregate `aggregate: {tenant_id, id}`; `wow_lease_member` is keyed by `service` and records each node's version of Wow (`wow_version`); the failure ledger names `aggregate`; stored enumerations are their lower-kebab names; a timer's null field is left out of its document.

**MongoDB 存储需要重建。** 预发布版的布局不会被读取：请从空数据库开始。日志把事件数据存为 `events[].body`；定时器与预留以 `aggregate: {tenant_id, id}` 指明其聚合；`wow_lease_member` 以 `service` 为键，并记录每个节点的 Wow 版本（`wow_version`）；失败台账使用 `aggregate`；存储的枚举值是它们的小写连字符名称；定时器值为 null 的字段不写入文档。

#7158 ## Upgrades paragraph
A subscription's `handle` or `process` span for an entry it skipped behind its aggregate's park, or as a copy a topic delivered again, now ends with `wow.outcome` `skipped` instead of `ok`. A dashboard or alert that reads `ok` on these spans as "handled" no longer counts those skips. Reference › Observability lists the outcomes of these spans.

订阅为停放之后被跳过的条目（或主题再次投递的副本）留下的 `handle` 或 `process` Span，结束时的 `wow.outcome` 现在是 `skipped` 而不是 `ok`。把这些 Span 上的 `ok` 当作「已处理」的看板或告警不再把这些跳过计入。参考 › 可观测性列出了这些 Span 的结果取值。

#7156 ## Upgrades paragraph
Nothing changes for an application. A backend that runs `StoreTck` no longer has a store left open by its options test.

应用无需任何改动。运行 `StoreTck` 的后端，不会再有选项测试遗留下来未关闭的存储。

#7152 [no Upgrades? paragraph]
#7160 ## Upgrades paragraph
None: records only.

#7154 [no Upgrades? paragraph]
#7153 [no Upgrades? paragraph]
#7150 ## Upgrades paragraph
A parked entry's last automatic retry is `lastRetryAt`, beside `nextRetryAt`: in the SPI (`ParkedAggregate`, `ParkedAmendment.Restate`), in the record a service publishes to ops, in the operator routes' JSON and in the ops console. The failure ledger stores it as `last_retry_at`, on MongoDB as on PostgreSQL (#7138). Upgrade the ops service with the services that publish to it: each refuses the other's record across the rename, and a service publishes its entries again once both run this version. A pre-release store is not carried over; to keep the value in a MongoDB one, rename it before starting this version: `db.wow_failure_ledger.updateMany({retry_at: {$exists: true}}, {$rename: {retry_at: "last_retry_at"}})`.

停放条目最近一次自动重试的开始时刻改名为 `lastRetryAt`，与 `nextRetryAt` 并列：SPI（`ParkedAggregate`、`ParkedAmendment.Restate`）、服务发给 ops 的记录、运维路由的 JSON 与 ops 控制台都随之改名。失败台账把它存为 `last_retry_at`，MongoDB 与 PostgreSQL（#7138）都一样。ops 服务与向它发布的服务一起升级：改名前后的双方互相拒绝对方的记录，两边都运行本版本后服务会重新发布它的条目。预发布版本的存储不带过来；要在已有的 MongoDB 存储里保留这个值，启动本版本之前先改名：`db.wow_failure_ledger.updateMany({retry_at: {$exists: true}}, {$rename: {retry_at: "last_retry_at"}})`。

#7148 ## Upgrades paragraph
**English.** This version runs CoSec 8.0.0-rc.1. From 8.0.0-beta.9 no order is needed: the services, the gateway and a control plane move in any order, as CoSec's [Upgrading: from 8.0.0-beta.9](https://cosec.ahoo.me/operations/upgrading-previews#from-8-0-0-beta-9) says. A service in gateway mode now takes request headers up to 16 KB however its mode is set: CoSec derives the limit from the identity's 8 KB plus 8 KB for the other headers. A service that set `server.max-http-request-header-size: 16KB` for it may remove the key. A request past the limit is answered 431 naming the limit the server applies. An application that builds a `TokenAuthenticator` itself, reads `ChangeEvent.change` or implements CoSec's control store follows CoSec's page; Wow's own code uses none of them. `@ahoo-wang/cosec-fetcher` moves to 8.0.0-rc.1 (`@rc`) with nothing else to change.

**中文。** 本版本使用 CoSec 8.0.0-rc.1。从 8.0.0-beta.9 升级不需要顺序：服务、网关和控制面可以按任意顺序升级，见 CoSec 的[升级：从 8.0.0-beta.9](https://cosec.ahoo.me/operations/upgrading-previews#from-8-0-0-beta-9)。网关模式的服务现在接受最多 16 KB 的请求头，无论它的模式是怎样设置的：CoSec 用身份的 8 KB 加上其他请求头的 8 KB 推导出这个上限。为此设置了 `server.max-http-request-header-size: 16KB` 的服务可以去掉这个键。超过上限的请求以 431 回答，并指明服务器实际采用的上限。自己构建 `TokenAuthenticator`、读取 `ChangeEvent.change` 或实现 CoSec 控制存储的应用，按 CoSec 的那一页处理；Wow 自己的代码一个都没有用到。`@ahoo-wang/cosec-fetcher` 升级到 8.0.0-rc.1（`@rc`），别的不用改。

#7149 ## Upgrades paragraph
An events index on Elasticsearch, or the ClickHouse warehouse, is filled again from the log by a new generation of its sink: empty it (delete the concrete index `<name>--1`, or drop the sink's tables), raise `wow.stores.<name>.sink.generation` (Elasticsearch) or `wow.clickhouse.sink.generation` (ClickHouse) by one, and roll the nodes. The setting is 1 by default, which keeps the sinks' names; only raise it. The old generation's checkpoints and parked entries stay, listed as `orphans` by the ops service and warned of at start; skip its parked entries.

Elasticsearch 上的事件索引或 ClickHouse 数仓，由其 Sink 的新一代从日志重新填满：清空它（删除具体索引 `<name>--1`，或删掉 Sink 的表），把 `wow.stores.<name>.sink.generation`（Elasticsearch）或 `wow.clickhouse.sink.generation`（ClickHouse）加一，再滚动重启节点。这项配置默认 1，保持 Sink 原有的名字；只能调高。旧一代的检查点和停放条目留着，ops 服务把它们列为 `orphans`，启动时也会告警；跳过它的停放条目。

#7147 [no Upgrades? paragraph]
#7138 ## Upgrades paragraph
**PostgreSQL stores are recreated.** The pre-release layout is not read: start from an empty database. The log stores an event's data as `events[].body`; timers and reservations name their aggregate `aggregate_tenant_id`/`aggregate_id`, and a timer's scope and operator are flat columns; `wow_lease_member` is keyed by `service` and records each node's version of Wow (`wow_version`); the failure ledger names `aggregate` and `last_retry_at`; stored enumerations are their lower-kebab names. A column Wow adds within the format later is added by `ddl: auto` and refused, named, by `validate`.

**PostgreSQL 存储需要重建。** 预发布版的布局不会被读取：请从空数据库开始。日志把事件数据存为 `events[].body`；定时器与预留以 `aggregate_tenant_id`/`aggregate_id` 指明其聚合，定时器的作用域与操作者是扁平的列；`wow_lease_member` 以 `service` 为键，并记录每个节点的 Wow 版本（`wow_version`）；失败台账使用 `aggregate` 与 `last_retry_at`；存储的枚举值是它们的小写连字符名称。之后 Wow 在同一格式内新增的列，由 `ddl: auto` 添加，`validate` 会指名拒绝。

#7146 ## Upgrades paragraph
**TypeScript packages: fetcher 8 accepted.** Wow's TypeScript packages accept fetcher `^5.1.5 || ^6.0.0 || ^8.0.0-rc.1` (`@ahoo-wang/fetcher`, `fetcher-decorator`, `fetcher-eventstream`). An application may move to fetcher 8 with them, from its first release candidate on, or stay on 5.1.5 or later, or on 6.x. Fetcher 8 removes `fetcher-cosec` and `fetcher-react`'s security hooks. An application that used them takes CoSec's own packages (`@ahoo-wang/cosec-fetcher`, `@ahoo-wang/cosec-react`).

**TypeScript 包：接受 fetcher 8。** Wow 的 TypeScript 包接受的 fetcher 范围为 `^5.1.5 || ^6.0.0 || ^8.0.0-rc.1`（`@ahoo-wang/fetcher`、`fetcher-decorator`、`fetcher-eventstream`）。应用可以从 fetcher 8 的第一个候选版本起与它们一起升到 8，也可以留在 5.1.5 及以上或 6.x。fetcher 8 去掉了 `fetcher-cosec` 与 `fetcher-react` 的安全 hooks，用到它们的应用改用 CoSec 自己的包（`@ahoo-wang/cosec-fetcher`、`@ahoo-wang/cosec-react`）。

#7145 [no Upgrades? paragraph]
#7144 ## Upgrades paragraph
A `ParkedNotifier` reads a `ParkedNotice`'s own properties: `ref`, `subscription`, `fromVersion`, `reason`, `failedAt`, `retries`, `maxRetries` and `nextRetryAt`. The ledger's records behind it, `notice.entry` and `notice.spec`, and `ParkedNotice.of` are now at the SPI's level (`@OptIn(WowSpi::class)`): a notifier that read `notice.entry.reason` reads `notice.reason`.

`ParkedNotifier` 读 `ParkedNotice` 自己的属性：`ref`、`subscription`、`fromVersion`、`reason`、`failedAt`、`retries`、`maxRetries` 与 `nextRetryAt`。它背后的台账记录 `notice.entry`、`notice.spec` 以及 `ParkedNotice.of` 现在属于 SPI 级别（`@OptIn(WowSpi::class)`）：原来读 `notice.entry.reason` 的通知器改读 `notice.reason`。

#7143 ## Upgrades paragraph
**wow-view-engine: the persisted summary and binding names, and names that say one thing.**
- In stored configs, write a record summary's function as `function` (it was `fn`) and a dashboard panel binding's field as `dashboardField` (it was `globalField`). Nothing migrates a stored config: re-save each affected view, or rewrite its stored JSON.
- In code:
  - `/react`'s `ViewListOptions` is `UseViewListOptions`.
  - The `ViewKind[]` options and members are `viewKinds`.
  - `ViewEngineOptions.newId` is `newRequestId`.
  - A query error's context says `code`.
  - `CreateInput.scope` is `audience`.
  - The workbench's board is `boardOrigin`, and `DashboardExtensionsOptions.board` is `runtime`.
  - `DashboardController.kinds` is `undefined` without a board.
  - `en` and `zhCN` are typed `Readonly<Record<MessageKey, string>>`.

**wow-view-engine：持久化的汇总与绑定改名，每个名字只说一件事。**
- 存储的配置里，记录汇总的函数写 `function`（原为 `fn`），仪表盘面板绑定的字段写 `dashboardField`（原为 `globalField`）。已存的配置没有任何迁移：重新保存受影响的视图，或改写存储的 JSON。
- 代码里：
  - `/react` 的 `ViewListOptions` 改为 `UseViewListOptions`。
  - `ViewKind[]` 的选项与成员改为 `viewKinds`。
  - `ViewEngineOptions.newId` 改为 `newRequestId`。
  - 查询错误的上下文改用 `code`。
  - `CreateInput.scope` 改为 `audience`。
  - 工作台的来源看板改为 `boardOrigin`，`DashboardExtensionsOptions.board` 改为 `runtime`。
  - 没有看板时 `DashboardController.kinds` 为 `undefined`。
  - `en` 与 `zhCN` 的类型为 `Readonly<Record<MessageKey, string>>`。

#7139 ## Upgrades paragraph
A dataset definition's case-insensitive text test, `containsIgnoreCase`, `startsWithIgnoreCase` or `endsWithIgnoreCase`, now compares both texts after Unicode's simple case folding, as a query's case-insensitive filters do on every store, and takes a literal text as its second operand: a definition that compares two expressions that way is refused when it is drafted or revised, naming the call. Rewrite it to compare with a text in quotes; a published dataset that does so is marked as needing revision at its next recompilation and serves its published version meanwhile. A published dataset whose test compares with a text in quotes gets the folded match at its next recompilation, by itself.

数据集定义里忽略大小写的文本判断 `containsIgnoreCase`、`startsWithIgnoreCase` 和 `endsWithIgnoreCase` 现在按 Unicode 的简单大小写折叠比较两个文本，与查询里忽略大小写的过滤在每个存储上一样，并且第二个操作数必须是字面文本：以这种方式比较两个表达式的定义在起草或修订时被拒绝，并指出那个调用。把它改为与引号里的文本比较；这样比较的已发布数据集会在下一次重新编译时被标为需要修订，其间照常提供已发布的版本。与引号里的文本比较的已发布数据集，会在下一次重新编译时自行得到折叠后的匹配。

#7142 ## Upgrades paragraph
None: records only.

#7140 ## Upgrades paragraph
`wow.query.require-explicit-entry` and `wow.query.require-authenticated-scope` are removed; delete them, as a node now refuses either at startup as an unknown key. Every query states where it comes from, and every query is already restricted to the tenant of the caller the service resolves from the request: the second key's check read a scope the query routes never set, so with it on every HTTP query of a built-in model was refused, and with it off its removal changes nothing. In code, `QueryEntry.UNSPECIFIED` and `QueryCaller.UNSPECIFIED` are gone: a `QueryCaller` runs in process unless it says otherwise (`QueryCaller.IN_PROCESS`), and the query error code `explicit-entry-required` is gone with them.

`wow.query.require-explicit-entry` 与 `wow.query.require-authenticated-scope` 已移除，删掉它们：节点启动时会把它们当作未知配置项拒绝。每个查询都说明了自己的来源，而且每个查询本来就限定在服务从请求解析出的调用方的租户内：第二个配置项的检查读的是查询路由从不设置的范围，打开它会拒绝每个查内置模型的 HTTP 查询，关着它，移除它什么都不变。代码里 `QueryEntry.UNSPECIFIED` 与 `QueryCaller.UNSPECIFIED` 已去掉：`QueryCaller` 除非另行说明，都在进程内运行（`QueryCaller.IN_PROCESS`），查询错误码 `explicit-entry-required` 也随之去掉。

#7137 [no Upgrades? paragraph]
#7141 [no Upgrades? paragraph]
#7135 [no Upgrades? paragraph]
#7136 [no Upgrades? paragraph]
#7134 ## Upgrades paragraph
**English.** CoSec's device id, `CoSec-Device-Id`, is no longer recorded in a command's request facts unless the service lists it in `wow.http.request-facts`. It is personal data that the immutable log keeps, so Wow records it only when the application asks. A service with CoSec turned off (`wow.cosec.mode: disabled`) reads no CoSec header, `CoSec-Request-Id` included: a client sends `Wow-Request-Id`.

**中文。** CoSec 的设备 ID `CoSec-Device-Id` 不再记入命令的请求事实，除非服务把它列在 `wow.http.request-facts` 里：它是不可删除的日志会保存的个人数据，只有应用要求时 Wow 才记录。关闭 CoSec 的服务（`wow.cosec.mode: disabled`）不读 CoSec 的任何头，`CoSec-Request-Id` 也不读：客户端发送 `Wow-Request-Id`。

#7128 ## Upgrades paragraph
**The public port no longer serves the Manifest by default.** `GET /wow/manifest` answers 404 on the public port unless `wow.http.manifest.enabled: true`; the internal port serves it to the cluster's services as before, and `GET /wow/openapi` stays on. The analytics catalog reads the owning services' Manifests from their internal ports (`wow.peer.mode: service`, the default; with `user`, each owning service sets the key). A TypeScript `CommandClient` given no Manifest, and the generator pointed at a service's URL, still read `GET /wow/manifest`: give them the generated Manifest or `manifest/baseline.json`, or set the key on the service. The eShop's end-to-end harness reads the internal port.

**公开端口默认不再提供 Manifest。** 除非设置 `wow.http.manifest.enabled: true`，公开端口上的 `GET /wow/manifest` 返回 404；内部端口仍像以前一样把它提供给集群中的服务，`GET /wow/openapi` 保持开启。分析目录从所属服务的内部端口读取它们的 Manifest（`wow.peer.mode: service`，默认值；设为 `user` 时，每个所属服务要打开这个键）。没有给定 Manifest 的 TypeScript `CommandClient`，以及指向服务 URL 的生成器，仍读取 `GET /wow/manifest`：请交给它们生成的 Manifest 或 `manifest/baseline.json`，或者在服务上打开这个键。eShop 的端到端测试工具读取内部端口。

#7132 ## Upgrades paragraph
A few declarations no application needs left the public API: `wow-client`'s `ClientMode`, `HttpCommandClient` and `HttpQueryApi` (use `WowClient`'s builder), the test DSL's spec constructors (use `aggregateSpec`, `sagaSpec`, `scheduleSpec`), `JobState`'s setters, the TCK suites' constants (`TAIL_TIMEOUT_MS` is `tailTimeout`), and, now internal to Wow, `CommandWire`, `CallerWire`, `ReplyTo`, `EnvelopeCodec` (the protocol page is the contract of a command topic) and `WowHttpContract`. `ServerCommandRequest` is built with `of`, its facts named: `ServerCommandRequest.of(command, ref, caller = caller, wait = plan)`. `WowContainers`' image tags are no longer constants.

应用用不到的几个声明离开了公开 API：`wow-client` 的 `ClientMode`、`HttpCommandClient` 与 `HttpQueryApi`（用 `WowClient` 的构建器）、测试 DSL 的 spec 构造函数（用 `aggregateSpec`、`sagaSpec`、`scheduleSpec`）、`JobState` 的 setter、TCK 套件的常量（`TAIL_TIMEOUT_MS` 改为 `tailTimeout`），以及改为 Wow 内部的 `CommandWire`、`CallerWire`、`ReplyTo`、`EnvelopeCodec`（命令主题以协议页为契约）与 `WowHttpContract`。`ServerCommandRequest` 用 `of` 构造，各项以命名参数给出：`ServerCommandRequest.of(command, ref, caller = caller, wait = plan)`。`WowContainers` 的镜像标签不再是常量。

#7133 ## Upgrades paragraph
None: records only.

#7130 ## Upgrades paragraph
**View store: a stored system board keeps the views it shows shared.**
- Claiming a shared view (making it personal) is refused when a stored system dashboard shows it, as when a shared dashboard does: `409 view-store.referenced-by-shared-dashboard`, the boards named in `boards`.
- `MemoryViewStore` refuses it too, and no longer refuses one a configured system board shows, which the service never checked.
- To make such a view personal, first remove it from the system board, or unpublish the board.

**视图存储：存储的系统看板让它展示的视图保持共享。**
- 存储的系统仪表盘展示着某个共享视图时，认领它（设为个人）会被拒绝，与共享仪表盘展示时一样：`409 view-store.referenced-by-shared-dashboard`，`boards` 列出这些看板。
- `MemoryViewStore` 同样拒绝；配置的系统看板展示着的视图则不再拒绝，服务从来不查它。
- 要把这样的视图设为个人，先把它从系统看板上移走，或者取消发布那块看板。

#7131 ## Upgrades paragraph
None: records only.

#7111 ## Upgrades paragraph
None: release automation only.

#7127 ## Upgrades paragraph
A MongoDB timer store whose account may read `wow_timer_aggregate` but not insert into it serves a timer collection already recorded there again, as before #6792; the account needs `insert` only to record a collection that has no record yet (or insert the record the refusal names). A log store's account still writes `wow_log_aggregate` under every `ddl`, where the log keeps its partition count.

MongoDB 定时器存储的账号能读 `wow_timer_aggregate` 而不能插入时，已在其中登记的定时器集合重新可用，与 #6792 之前一样；只有登记一个还没有记录的集合才需要 `insert`（或者插入拒绝信息写明的那条记录）。日志存储的账号在任何 `ddl` 下仍要写 `wow_log_aggregate`，日志的分区数记在那里。

#7117 ## Upgrades paragraph
None: the benchmark harness.

Refs #7109

#7112 ## Upgrades paragraph
Every public declaration of `wow-spi` and `wow-query` now says its level. Most are `@WowSpi`: a backend's, which a minor release may change and which asks for `@OptIn(WowSpi::class)`, as do the TCK suites; the rest are `@InternalWowApi`, Wow's own, among them `QueryGateway`, `HttpQueries`, `KernelOptions`, `ManifestMerger` and `ManifestFragment`. Build a `ContractDirectory` of a Manifest with `ContractDirectory.of`. `WowJson`, `ReadAuthorizer`, `MaskStrategyResolver` and `QueryCountCache` stay application API.

`wow-spi` 与 `wow-query` 的每个公开声明现在都标明了级别。大多数是 `@WowSpi`：后端用的，次版本可能改变，使用时需要 `@OptIn(WowSpi::class)`，TCK 的测试套件也一样；其余是 `@InternalWowApi`，Wow 自己的，其中有 `QueryGateway`、`HttpQueries`、`KernelOptions`、`ManifestMerger` 与 `ManifestFragment`。从一份 Manifest 构造 `ContractDirectory` 用 `ContractDirectory.of`。`WowJson`、`ReadAuthorizer`、`MaskStrategyResolver` 与 `QueryCountCache` 仍是应用 API。

#7126 ## Upgrades paragraph
**wow-view-store: wow-client's options, and no reserved scopes on the entry.**
- `WowViewStoreOptions` and `WowDatasetWordingsOptions` extend wow-client's `WowClientOptions`. A gateway's prefix goes into `basePath` instead of a fetcher that rewrites URLs, and a service with longer default waits sets `defaultWaitMs`. Their members are `readonly`.
- `SHARED_SCOPE` and `SYSTEM_SCOPE` are no longer exported. Write the `Wow-Scope` your own Wow client needs yourself: `{ ownerId: '(shared)' }`, or `{ tenantId: '(platform)', ownerId: '(system)' }`.
- `WowDatasetWordings.set` and `clear` refuse a revision the store never issued as `INVALID` before sending.

**wow-view-store：选项改用 wow-client 的选项，入口不再导出保留作用域。**
- `WowViewStoreOptions` 与 `WowDatasetWordingsOptions` 继承 wow-client 的 `WowClientOptions`。网关前缀改用 `basePath`，不必再用改写 URL 的 fetcher；服务的默认等待更长时，设置 `defaultWaitMs`。它们的成员都是 `readonly`。
- 不再导出 `SHARED_SCOPE` 与 `SYSTEM_SCOPE`。自己的 Wow 客户端需要这个 `Wow-Scope` 时自己写：`{ ownerId: '(shared)' }`，或 `{ tenantId: '(platform)', ownerId: '(system)' }`。
- `WowDatasetWordings.set` 与 `clear` 收到 store 从未发出过的修订时，在发送之前就以 `INVALID` 拒绝。

#7125 ## Upgrades paragraph
**wow-client: a command request names its aggregate's id `aggregateId`.**
- Rename `id` to `aggregateId` in a `CommandRequest` (`commands.wait({ command, body, aggregateId })`) and in a generated creation method's options (`createBrand(body, { aggregateId })`). This matches Kotlin's `CommandRequest.aggregateId`.
- Regenerate the generated clients.
- A by-id method's positional `id`, a state read's options and the HTTP requests are unchanged.

**wow-client：命令请求中聚合的 id 改名为 `aggregateId`。**
- 在 `CommandRequest`（`commands.wait({ command, body, aggregateId })`）和生成的创建方法的选项（`createBrand(body, { aggregateId })`）中，把 `id` 改名为 `aggregateId`，与 Kotlin 的 `CommandRequest.aggregateId` 一致。
- 重新生成生成的客户端。
- 按 id 调用的方法的位置参数 `id`、状态读取的选项和发出的 HTTP 请求都不变。

#7124 ## Upgrades paragraph
**`wow.dev-profiles` defaults to `dev, local`.** A `test` or `demo` profile no longer makes a node run for development (memory backend, callers from request headers, no durability warning): a node that relies on one refuses to start as a deployment. List the profile in `wow.dev-profiles`, or use `dev`.

**`wow.dev-profiles` 默认为 `dev, local`。** `test` 或 `demo` profile 不再让节点以开发模式运行（内存后端、从请求头取得调用方、不发出持久性警告）：依赖这类 profile 的节点会像部署一样拒绝启动。请在 `wow.dev-profiles` 中列出该 profile，或改用 `dev`。

#7123 ## Upgrades paragraph
**wow-generator: no tsconfig option, line ends that do not matter, and wow-client an optional peer.**
- Drop `-t`/`--ts-config-file-path` from the `generate` command and `tsConfigFilePath` from `GeneratorOptions`: the output never depended on them, and the CLI now refuses the option.
- A checkout that converts line ends (`core.autocrlf`) no longer fails `--check`.
- A regeneration removes the directories that removing old files leaves empty.
- A Manifest run warns when the configuration sets `apiClients`, which it never read.
- `@ahoo-wang/wow-client` is an optional peer of the generator.

**wow-generator：不再有 tsconfig 选项，行尾不再造成差异，wow-client 改为可选 peer 依赖。**
- 从 `generate` 命令中去掉 `-t`/`--ts-config-file-path`，从 `GeneratorOptions` 中去掉 `tsConfigFilePath`：输出从不依赖它们，CLI 现在会拒绝这个选项。
- 转换行尾的检出（`core.autocrlf`）不再让 `--check` 失败。
- 重新生成时，删掉旧文件后变空的目录也会一并删除。
- 配置中设置了 `apiClients` 时，Manifest 运行会告警，因为它从不读取这些配置。
- `@ahoo-wang/wow-client` 改为生成器的可选 peer 依赖。

#7122 ## Upgrades paragraph
**Every refused setting names its key.** `wow.log.partitions` and `wow.memory.topic-retention` out of range, a store's query settings, a `wow.subscriptions` entry's retry and parked settings, and `wow.query.budgets.in-process.default-list-size` now refuse the start naming the key that is set, where some named another key, the wrong group, or none.

**每个被拒绝的设置都指出它的键。** 越界的 `wow.log.partitions` 与 `wow.memory.topic-retention`、存储自己的查询设置、`wow.subscriptions` 条目的重试与停放设置，以及 `wow.query.budgets.in-process.default-list-size`，现在拒绝启动时指出所设置的键；此前有的指向另一个键、错误的组，或者不指出任何键。

#7121 ## Upgrades paragraph
**wow-generator: the generated clients' names follow Kotlin's, and the generator never guesses a value it does not know.** Regenerate every client with this version.
- A command client sends through `commands` where it had `client`.
- A context's wait hops are `<Context>Contract.Wait` (`EshopCatalogContract.Wait.committed()`) where they were `eshopCatalogWait`.
- An aggregate's descriptor has no `path`, and its command marks are keyed by full name.
- A lifecycle body is wow-client's `TransferOwner` or `TransferSpace`.
- A service route's method is camel-cased, as a command's is.
- A command body of no properties is `Record<string, never>`.
- `<Aggregate>StateField` holds the paths the Kotlin contract names.
- An aggregate, command or service route with a value the generator does not know (a newer Wow's) is left out, with a warning; generate with a newer generator.

**wow-generator：生成客户端的名字与 Kotlin 一致，生成器不再猜测不认识的值。** 用这个版本重新生成所有客户端。
- 命令客户端经由 `commands` 发送，原来是 `client`。
- 上下文的等待各跳在 `<Context>Contract.Wait`（`EshopCatalogContract.Wait.committed()`），原来是 `eshopCatalogWait`。
- 聚合描述不再有 `path`，命令的精确数字标记按全名作键。
- 生命周期请求体是 wow-client 的 `TransferOwner` 或 `TransferSpace`。
- 服务路由的方法与命令的方法一样用驼峰命名。
- 没有属性的命令请求体是 `Record<string, never>`。
- `<Aggregate>StateField` 列出 Kotlin 契约所列的路径。
- 带有生成器不认识的值（较新 Wow 的值）的聚合、命令或服务路由会被省掉，并给出告警；请用较新的生成器生成。

#7120 ## Upgrades paragraph
**A setting that would do nothing refuses the start.** A `wow.http.errors` code under `wow.` that the framework does not answer, `app_id`, `user_agent` or `remote_ip` in `wow.http.request-facts`, a `wow.ops.publish.context` that is no context name, an adaptive query `floor` above its store's `ceiling`, and a Kafka `topics.min-insync-replicas` above `topics.replication-factor` now refuse the start, naming the key; before, each was ignored. A `wow.http.errors` code of the application's that no Manifest on the class path declares starts with a warning.

**会什么也不做的设置拒绝启动。** `wow.http.errors` 中框架不会回答的 `wow.` 下的错误码、`wow.http.request-facts` 中的 `app_id`、`user_agent` 或 `remote_ip`、不是上下文名称的 `wow.ops.publish.context`、高于其存储 `ceiling` 的自适应查询 `floor`，以及高于 `topics.replication-factor` 的 Kafka `topics.min-insync-replicas`，现在都会拒绝启动并指出键；此前它们都被忽略。类路径上没有任何 Manifest 声明的应用 `wow.http.errors` 错误码以一条警告启动。

#7119 [no Upgrades? paragraph]
#7118 ## Upgrades paragraph
**Renamed configuration keys and values.** `wow.timers.claim-timeout` is `wow.timers.claim-ttl`, `wow.kafka.topics.partition-refresh` is `wow.kafka.topics.partition-refresh-interval`, `wow.subscription.read-retry.error-after` is `wow.subscription.read-retry.error-after-attempts`, and `wow.wait.unknown-contexts: reject` is `refuse`; the former names refuse the start. `0` turns off every bound that can be turned off: `wow.timers.expires-after`, `wow.timers.max-fire-rate` and the retries' `timeout` take it, and `reindex-requests-per-second` takes `0` for no limit instead of `-1`. A store's or transport's duration reads as the host's do (`5S`, `500ms`, `PT5S`); `1m 30s` and `1.5s` are refused.

**配置键与取值改名。** `wow.timers.claim-timeout` 改为 `wow.timers.claim-ttl`，`wow.kafka.topics.partition-refresh` 改为 `wow.kafka.topics.partition-refresh-interval`，`wow.subscription.read-retry.error-after` 改为 `wow.subscription.read-retry.error-after-attempts`，`wow.wait.unknown-contexts: reject` 改为 `refuse`；旧名称会拒绝启动。`0` 关闭所有可以关闭的上限：`wow.timers.expires-after`、`wow.timers.max-fire-rate` 和各重试的 `timeout` 接受 `0`，`reindex-requests-per-second` 以 `0` 而不再以 `-1` 表示不限速。存储或传输的时长与主机的键读法相同（`5S`、`500ms`、`PT5S`）；`1m 30s` 与 `1.5s` 会被拒绝。

#7115 ## Upgrades paragraph
**wow-client: Kotlin's names for the hops, the resume options and the retry type, one name for the exact-number marks, and fewer exports.**
- Build a wait chain's hops with `Hop.committed()` (and `accepted`, `snapshot`, `queryable`, `handled`) where `wait.committed()` was.
- A stream's `resume` takes `maxAttempts` (the first request included, so `maxRetries: n` is `maxAttempts: n + 1`), `backoffMs` and `maxRetryAfterMs`.
- A hand-written `CommandClient` takes its marks as `textNumbers`, where it took `bodyNumbers`. A dataset's marks are `dataset(context, dataset, textNumbers)`. `textNumberOr` is `textNumbersOr`.
- Import `CountQuery` and `CountResult` from `@ahoo-wang/wow-client/dsl`.
- The type `Retry` is `RetryAdvice`.
- `StateReadOptions` takes one of `version`, `at` and `minVersion`.
- `QueryViolation` extends `Violation`.
- These are no longer exported: `DEFAULT_RETRY`, `checkManifest`, `queryPath`, the clients' `client` member, and `StateClient`'s protected readers.
- Regenerate the generated clients with this version: their hop builders and command options use the new names.

**wow-client：跳、续读选项与重试类型用 Kotlin 的名字，精确数字的标记只用一个名字，导出更少。**
- 构建等待链的跳用 `Hop.committed()`（以及 `accepted`、`snapshot`、`queryable`、`handled`），代替 `wait.committed()`。
- 流的 `resume` 接受 `maxAttempts`、`backoffMs` 和 `maxRetryAfterMs`。`maxAttempts` 含第一次请求，所以 `maxRetries: n` 对应 `maxAttempts: n + 1`。
- 手写的 `CommandClient` 以 `textNumbers` 接受标记，代替 `bodyNumbers`。数据集的标记写作 `dataset(context, dataset, textNumbers)`。`textNumberOr` 改名 `textNumbersOr`。
- `CountQuery` 和 `CountResult` 从 `@ahoo-wang/wow-client/dsl` 导入。
- 类型 `Retry` 改名 `RetryAdvice`。
- `StateReadOptions` 在 `version`、`at`、`minVersion` 中只取一个。
- `QueryViolation` 扩展 `Violation`。
- 以下不再导出：`DEFAULT_RETRY`、`checkManifest`、`queryPath`、各客户端的 `client` 成员，以及 `StateClient` 的 protected 读取方法。
- 用这个版本重新生成客户端：生成的跳构建器和命令选项使用新名字。

#7114 ## Upgrades paragraph
**A setting the host cannot start with names its key.** A value out of range under `wow.node`, `wow.internal`, `wow.health`, `wow.ops`, `wow.peer`, `wow.log`, `wow.http`, `wow.query` or a store's or transport's options now refuses the start with Wow's failure analysis, naming the key or its group, instead of a bean's stack; a blank `wow.tenant.default` refuses the start in every case.

**主机无法启动的设置会指出它的键。** `wow.node`、`wow.internal`、`wow.health`、`wow.ops`、`wow.peer`、`wow.log`、`wow.http`、`wow.query` 下或存储、传输选项中越界的值，现在以 Wow 的失败分析拒绝启动并指出键或其所在的组，不再是一串 bean 的堆栈；空的 `wow.tenant.default` 在任何情况下都拒绝启动。

#7113 ## Upgrades paragraph
**wow-react: `problem` is the problem body, `StreamExecutor<Q, R>`, and what ends a run.** A hook's `problem` is the RFC 9457 body (`Problem`) the service answered with: its `code`, `status`, `detail` and `violations`. `error` is the `WowProblemError` itself, which still has the retry hint (`retryAfterMs`, `retry`). So code that read `problem.retryAfterMs` now reads `error.retryAfterMs` after checking `error instanceof WowProblemError`. `StreamExecutor` takes the query first, `StreamExecutor<Q, R>`. If the query or `requestOptions` changes while a request is in flight, that request is aborted, `autoExecute` or not. Only the run's own signal aborts a run: an `AbortError` from anywhere else, such as an executor's own timeout, makes the run fail. `useCursorQuery` gives `hasMore: false` while its controlled query is `undefined`.

**wow-react：`problem` 是 problem 体、`StreamExecutor<Q, R>`，以及什么会结束一次运行。** hook 的 `problem` 是服务应答的 RFC 9457 体（`Problem`）：它的 `code`、`status`、`detail` 和 `violations`。`error` 是 `WowProblemError` 本身，重试提示（`retryAfterMs`、`retry`）仍在它上面。所以，原来读 `problem.retryAfterMs` 的代码，现在先判断 `error instanceof WowProblemError`，再读 `error.retryAfterMs`。`StreamExecutor` 的查询参数放在前面：`StreamExecutor<Q, R>`。请求还在途中时，如果查询或 `requestOptions` 变了，这个请求会被中止，不论 `autoExecute`。只有运行自己的信号能中止它：从别处来的 `AbortError`（例如执行函数自己的超时）会让这次运行失败。受控查询为 `undefined` 时，`useCursorQuery` 的 `hasMore` 为 `false`。

#7110 ## Upgrades paragraph
**Every `wow.aggregates` entry names a served aggregate type.** An entry of `wow.aggregates.[<context>.<aggregate>]` that names no aggregate type the node serves now refuses the start, whichever of its settings it holds; before, its `query.events.unpinned` and its ClickHouse history were ignored without a word.

**`wow.aggregates` 的每个条目都必须指向所服务的聚合类型。** `wow.aggregates.[<context>.<aggregate>]` 中指向节点不服务的聚合类型的条目，无论其中设置了什么，现在都拒绝启动；此前它的 `query.events.unpinned` 与 ClickHouse 历史设置会被悄无声息地忽略。

#7108 [no Upgrades? paragraph]
#7106 ## Upgrades paragraph
**`wow.node.address` names its port.** An address without a port, with port 0 or past 65535 now refuses the start, naming the key: the other nodes reached such a node at port 80, or nowhere. Write `host:port`, an IPv6 host in brackets.

**`wow.node.address` 必须写明端口。** 没有端口、端口为 0 或超过 65535 的地址现在拒绝启动并指出该键：其他节点此前会在 80 端口访问这样的节点，或根本无法访问。写成 `host:port`，IPv6 主机放在方括号里。

#7107 [no Upgrades? paragraph]
#7105 ## Upgrades paragraph
The sort and projection DSLs name fields under a path with `"state".path { }`, as filters do; `nested(...)` is removed. `beforeNow` and `afterNow` take a `kotlin.time.Duration`. `@QueryReference`'s members are `aggregate` and `context`, and the Manifest's `x-wow-reference` carries `aggregate` and `context`: rebuild to regenerate it. A backend implementing the query SPI renames `SnapshotQueries` and `EventQueries` to `SnapshotStoreQueries` and `EventStoreQueries`, and a `QueryError` reads `message` and `violations`. A read a `ReadAuthorizer` refuses answers the authorizer's problem with no `violations`. The query codes only an aggregation query gives are experimental with it.

排序与投影 DSL 像过滤一样用 `"state".path { }` 指明路径之下的字段，`nested(...)` 已去掉。`beforeNow` 与 `afterNow` 接收 `kotlin.time.Duration`。`@QueryReference` 的成员改为 `aggregate` 与 `context`，Manifest 的 `x-wow-reference` 随之写作 `aggregate` 与 `context`：重新构建即重新生成。实现查询 SPI 的后端把 `SnapshotQueries`、`EventQueries` 改为 `SnapshotStoreQueries`、`EventStoreQueries`，`QueryError` 读 `message` 与 `violations`。被 `ReadAuthorizer` 拒绝的读取以授权器的 problem 应答，不带 `violations`。只有聚合查询会给出的查询错误码随聚合查询一起是实验性的。

#7104 [no Upgrades? paragraph]
#7099 ## Upgrades paragraph
**wow-generator: names as the Kotlin contract's, collisions refused, `idSource` in every descriptor.** Regenerate the clients (`src/generated/`) with this version: every `<Aggregate>Aggregate` descriptor now writes `idSource` (`'assigned'` included). A Manifest name with an upper-case run is spelled as the Kotlin contract spells it (`orderID` is `OrderID`, a command `CreateURLShortcut`'s method `createURLShortcut`). A Manifest two of whose names give one generated name in a context (`order-item` and `order_item`, or a context `order-command` holding an aggregate `order`) is refused with exit 4, naming both: rename one with `@Name`.

**wow-generator：名称与 Kotlin 契约一致、冲突即拒绝、每个描述都带 `idSource`。** 请用这个版本重新生成客户端（`src/generated/`）：每个 `<Aggregate>Aggregate` 描述现在都写出 `idSource`（包括 `'assigned'`）。含连续大写字母的 Manifest 名称按 Kotlin 契约的写法拼写（`orderID` 是 `OrderID`，命令 `CreateURLShortcut` 的方法是 `createURLShortcut`）。同一上下文中有两个名称生成同一个名字的 Manifest（`order-item` 与 `order_item`，或上下文 `order-command` 中的聚合 `order`）以退出码 4 拒绝并点出两者：请用 `@Name` 给其中一个改名。

#7102 ## Upgrades paragraph
**PostgreSQL and Elasticsearch: an operation timeout must bound something.** A store's `operation-timeout` (`wow.postgres.operation-timeout`, `wow.elasticsearch.operation-timeout`, `wow.stores.<name>.operation-timeout`) of zero or below now refuses the start, naming the key, as MongoDB's already did; on PostgreSQL so does one past about 24.8 days. A PostgreSQL timeout of zero left every call unbounded, and a negative one failed every call.

**PostgreSQL 与 Elasticsearch：操作超时必须有上限。** 存储的 `operation-timeout`（`wow.postgres.operation-timeout`、`wow.elasticsearch.operation-timeout`、`wow.stores.<name>.operation-timeout`）为零或负数时，现在拒绝启动并指出该键，与 MongoDB 一致；PostgreSQL 上超过约 24.8 天也拒绝。此前 PostgreSQL 的超时为零时每次调用都没有上限，为负数时每次调用都失败。

#7086 [no Upgrades? paragraph]
#7092 [no Upgrades? paragraph]
#7091 [no Upgrades? paragraph]
#7101 ## Upgrades paragraph
`CommandRequest.id` is `aggregateId` (the builder's `aggregateId(...)`, the `aggregateId` parameter of `toCommandRequest`, `send`, `wait`, `stream`, `call` and `schedule`), and a request names its target tenant and scope in one field, `targetScope: TargetScope?`: `targetScope(TargetScope.of(tenant, scope))`, or `targetTenant(tenant)` alone. A target that names only a tenant acts there in the caller's own scope on every path; the node's own command client acted in no scope, so name `Scope.NONE` where that is meant. An id and a ref are two overloads now, so a call naming both no longer compiles. `TargetScope.scope` may be null (the sender's own), `ServerCommandRequest.target` and `QueryCaller.target` are `targetScope`, the enum `me.ahoo.wow.api.problem.Retry` is `RetryAdvice` (its wire spellings unchanged), `Problem.retryAfter` is a `Duration`, `CommandTarget.of` takes the body before the aggregate id, `Origin` takes the service first, and the test DSL's `EventEnvelope` and the test server's `PeerCall` carry the aggregate's `ref`.

`CommandRequest.id` 改名 `aggregateId`（构建器的 `aggregateId(...)`，以及 `toCommandRequest`、`send`、`wait`、`stream`、`call`、`schedule` 的 `aggregateId` 参数）；请求以一个字段 `targetScope: TargetScope?` 指明目标租户与作用域：`targetScope(TargetScope.of(tenant, scope))`，或只写 `targetTenant(tenant)`。只写租户的目标在每条路径上都以调用方自己的作用域在那个租户里行事；节点自己的命令客户端原先不带作用域，需要不带作用域时请明写 `Scope.NONE`。ID 与 ref 现在是两个重载，同时给出两者的调用编译不过。`TargetScope.scope` 可以为空（保留发送方自己的），`ServerCommandRequest.target` 与 `QueryCaller.target` 改名 `targetScope`，枚举 `me.ahoo.wow.api.problem.Retry` 改名 `RetryAdvice`（线上的拼写不变），`Problem.retryAfter` 改为 `Duration`，`CommandTarget.of` 的命令体在聚合 ID 之前，`Origin` 的服务在最前，测试 DSL 的 `EventEnvelope` 与测试服务器的 `PeerCall` 带聚合的 `ref`。

#7100 [no Upgrades? paragraph]
#7094 ## Upgrades paragraph
A decide function that returns a `Mono` or a `Flux` and calls a Wow client inside `mono { … }` or `flux { … }`, or a `@Blocking` one that calls it inside `runBlocking { … }`, now sends each call that stands under the request id derived from the deciding request, as a suspending decide function does: a call decided again is answered as a repeat instead of running again, and a call decided again that asks something else fails the command (`wow.command.failed`). Undo a call that may change in `onAppendFailure` with `attemptKey()`, or give it a business request id.

返回 `Mono` 或 `Flux`、在 `mono { … }`、`flux { … }` 里调用 Wow 客户端的决定函数，以及在 `runBlocking { … }` 里调用它的 `@Blocking` 决定函数，现在像挂起的决定函数一样，以从正在决定的请求推导出的请求 ID 发送每个保留的调用：重新决定时的调用被当作重复回答，而不是再执行一次；重新决定时请求了别的东西的调用会让命令失败（`wow.command.failed`）。可能改变的调用在 `onAppendFailure` 中以 `attemptKey()` 撤销，或者给它一个业务请求 ID。

#7096 ## Upgrades paragraph
**PostgreSQL: the keys of Wow's tables are checked at start.** Under `wow.ddl: auto` and `validate`, a PostgreSQL store refuses a table that lacks a unique key of Wow's schema (a log table's version or request key, any table's primary key), naming the table and the key, and under `validate` a missing index of Wow's schema, naming it. A schema created by Wow passes; a schema applied by hand must keep every `PRIMARY KEY` and `UNIQUE` of Wow's statements.

**PostgreSQL：启动时检查 Wow 各表的键。** 在 `wow.ddl: auto` 与 `validate` 下，PostgreSQL 存储拒绝缺少 Wow schema 中唯一键的表（日志表的版本键或请求键、任何表的主键），并指出表和键；在 `validate` 下也拒绝缺少 Wow schema 中索引的表，并指出该索引。由 Wow 创建的 schema 照常通过；手工应用的 schema 必须保留 Wow 语句中的每个 `PRIMARY KEY` 与 `UNIQUE`。

#7095 ## Upgrades paragraph
**English.** A query's numeric operand has at most 16,383 digits after the point and 131,072 before it, PostgreSQL `numeric`'s limits. A wider one (`1e-500000000`) is refused with 400 `wow.query.invalid` (`storage-unsupported`) before any store is asked. A number compared with a ClickHouse dataset's `Float32` or `Float64` column must be a double: past a double's range, or rounding to a subnormal or zero, it is refused alike. A projection that names a field twice counts it once.

**中文。** 查询的数字操作数小数点后至多 16,383 位、小数点前至多 131,072 位，即 PostgreSQL `numeric` 的上限。更宽的（`1e-500000000`）在问任何存储之前就以 400 `wow.query.invalid`（`storage-unsupported`）拒绝。与 ClickHouse 数据集的 `Float32`、`Float64` 列比较的数字须是双精度能表示的：超出双精度范围、或舍入为次正规数或零的同样被拒绝。投影里同一字段写了两次只算一次。

#7093 ## Upgrades paragraph
**TypeScript view stores: `INVALID` for what a store never issued, the page bound, and fakes as the service.** `WowViewStore` and `MemoryViewStore` refuse a revision or a list cursor they never issued as `INVALID`, where `WowViewStore` answered `CONFLICT`. `WowViewStore` pages the system views, so no list page holds more than `pageSize`. `MemoryViewStore` and `memorySource` of `@ahoo-wang/wow-view-engine/testing` answer as the services do: `MemoryViewStore` refuses a config, a definition id or preferences the view store service refuses and spends no revision on a write that changes nothing; `memorySource` refuses a cursor it never issued (`wow.query.invalid`, `invalid-cursor`) and answers `total: 'none'` with no total. A test that relied on the fakes' leniency changes with them.

**TypeScript 视图存储：存储从未签发的内容为 `INVALID`、分页上限，以及与服务一致的替身。** `WowViewStore` 与 `MemoryViewStore` 以 `INVALID` 拒绝它们从未签发的 revision 或列表游标，此前 `WowViewStore` 应答 `CONFLICT`。`WowViewStore` 对系统视图分页，所以列表的任何一页都不超过 `pageSize`。`@ahoo-wang/wow-view-engine/testing` 的 `MemoryViewStore` 与 `memorySource` 像服务那样应答：`MemoryViewStore` 拒绝视图存储服务所拒绝的配置、定义 ID 与偏好，对什么也不改变的写入不耗费 revision；`memorySource` 拒绝它从未签发的游标（`wow.query.invalid`、`invalid-cursor`），对 `total: 'none'` 不给出总数。依赖替身宽松行为的测试需要随之调整。

#7090 [no Upgrades? paragraph]
#7089 [no Upgrades? paragraph]
#7088 [no Upgrades? paragraph]
#7087 ## Upgrades paragraph
**TypeScript: a query stream lost past its resumes, a `terms` key, and `/testing`'s problems.** A streamed query cut off, or whose connection is lost, past its resumes now rejects with a `WowProblemError` `wow.peer.unavailable` (the network's error its `cause`), as the Kotlin client's does, where it rejected with a `TypeError` or the network's error. An aggregation's `terms` key over a field the generated types say `string` of (`int64`, `bigint`, `decimal`) arrives as that string, so two groups past 2^53 stay apart. `@ahoo-wang/wow-client/testing`'s `problemOf` and `problemResponse` build what a service writes: the catalog's `retry` and title, and `Retry-After`.

**TypeScript：续读用尽后丢失的查询流、`terms` 键与 `/testing` 的问题。** 被截断或连接丢失、续读次数用尽的流式查询，现在以 `WowProblemError` `wow.peer.unavailable` 拒绝（网络的错误是它的 `cause`），与 Kotlin 客户端一样，此前以 `TypeError` 或网络的错误拒绝。聚合查询中以生成类型声明为 `string` 的字段（`int64`、`bigint`、`decimal`）分组的 `terms` 键，以该字符串到达，于是超出 2^53 的两个分组不再合并。`@ahoo-wang/wow-client/testing` 的 `problemOf` 与 `problemResponse` 构造服务写出的问题：错误目录的 `retry` 与标题，以及 `Retry-After`。

#7081 ## Upgrades paragraph
**The TypeScript client states every wait plan's timeout.** A command sent with a `wait` and no `within()` or `timeout`, and a `send` without `timeout`, now states 10 seconds as `Wow-Wait-Timeout`, as the Kotlin client always has, and is answered `504 wow.wait.timeout` past it; give such a plan the wait it needs, or send without a plan to keep the service's default. A request's `headers` may no longer carry a header the client writes itself (`Wow-Request-Id`, `Wow-Wait`, `Wow-Wait-Timeout`, `Wow-Scope`, `Wow-Space`, `If-Match`, `If-None-Match`, `Last-Event-ID`): set the request's own field instead.

**TypeScript 客户端为每个等待计划声明超时。** 带 `wait`、没有 `within()` 或 `timeout` 的命令，以及没有 `timeout` 的 `send`，现在都以 `Wow-Wait-Timeout` 声明 10 秒，与 Kotlin 客户端一向的做法一致，超过即应答 `504 wow.wait.timeout`；请给这样的计划设定它需要的等待，或者不带计划发送以沿用服务的默认等待。请求的 `headers` 不能再携带客户端自己写出的请求头（`Wow-Request-Id`、`Wow-Wait`、`Wow-Wait-Timeout`、`Wow-Scope`、`Wow-Space`、`If-Match`、`If-None-Match`、`Last-Event-ID`）：请改为设置请求自身的对应字段。

#7079 ## Upgrades paragraph
A decide function that keys an act it undoes in `onAppendFailure` with `scope.callId()` now writes `scope.attemptKey()`: the member is renamed, its meaning unchanged. An after hook (`@AfterCommand`, `afterCommand`) that calls `reject(...)`, or whose `require` or `check` fails, now rejects the command with that code, writing nothing and running the attempt's compensations, where it failed the command with `wow.command.failed`. A saga handler that sends, schedules, cancels or calls a command whose target's id it drew from `context.ids` in the same handling now fails unrecoverable and parks the aggregate; name the target by an id computed from the commit (the source aggregate's id, a business key of the event), or derive the creation without an id and send the commands that follow it from the `@OnCommitted` handler of its receipt. An id drawn into a command's body is not affected.

决策函数在 `onAppendFailure` 里撤销的动作，原先以 `scope.callId()` 为键的，现在写 `scope.attemptKey()`：成员改名，含义不变。after 钩子（`@AfterCommand`、`afterCommand`）调用 `reject(...)`，或其中的 `require`、`check` 失败时，现在以该码拒绝命令，什么都不写，本次尝试的补偿照常运行；原先命令以 `wow.command.failed` 失败。saga 的处理函数发出、调度、取消或调用的命令，若目标的 ID 是它在同一次处理里从 `context.ids` 取出的，现在以不可恢复的失败结束并停放该聚合；请用从提交算出的 ID 指定目标（源聚合的 ID、事件里的业务键），或者不带 ID 派生创建、在它回执的 `@OnCommitted` 处理函数里发出其后的命令。取来放进命令体的 ID 不受影响。

#7080 [no Upgrades? paragraph]
#7084 [no Upgrades? paragraph]
#7082 [no Upgrades? paragraph]
#7023 [no Upgrades? paragraph]
#7049 [no Upgrades? paragraph]
#7025 [no Upgrades? paragraph]
#7016 [no Upgrades? paragraph]
#7013 [no Upgrades? paragraph]
#7017 [no Upgrades? paragraph]
#7015 [no Upgrades? paragraph]
#7012 ## Upgrades paragraph
**A computed value is a double on PostgreSQL too.** An `expression` filter, a metric or a group over an expression and a derived metric are computed in `float8`, as on MongoDB, Elasticsearch, ClickHouse and memory. A comparison at the edge of a decimal can answer otherwise than before: `amount * 3 > 0.3` matches an `amount` of 0.1. Where exactness matters, as for money, compare or aggregate a stored field. A computed value beyond a double's range is refused with `400 wow.query.out-of-range`.

**PostgreSQL 上的计算值也是双精度。** `expression` 过滤、基于表达式的指标或分组以及派生指标都按 `float8` 计算，与 MongoDB、Elasticsearch、ClickHouse 和内存后端一致。处在十进制数边缘的比较可能与以前的答案不同：`amount * 3 > 0.3` 会匹配 `amount` 为 0.1 的记录。需要精确的地方（如金额），比较或聚合存储的字段。超出双精度范围的计算值以 `400 wow.query.out-of-range` 拒绝。

#7011 [no Upgrades? paragraph]
#7010 [no Upgrades? paragraph]
#7008 ## Upgrades paragraph
Rebuild a service's code against this version: the compiler's generated bindings now pass the context each handler is bound in (`ProcessorBinding.saga`, `.project` and `.react` take `context`). A binding written by hand compiles unchanged when it names its arguments, and may name its `context`. Specs written with the test DSL change in step with the node:
- a handler reads its state decoded from JSON, a copy of its own, in the context it is bound in;
- a rejected receipt carries the command's scope;
- the rejection of a call's compensation parks;
- a receipt of another context's command is not retried under the saga's `@Retry`;
- the answering client's `stream` emits rejected and failed results.

针对这个版本重新构建服务的代码：编译器生成的绑定现在会传入每个处理函数所绑定的上下文（`ProcessorBinding.saga`、`.project` 和 `.react` 接受 `context`）。手写的绑定只要使用具名参数，不改也能编译，也可以给出它的 `context`。用测试 DSL 编写的规格随节点一起改变：
- 处理函数读到的状态从 JSON 解码而来，各自一份，并处于它所绑定的上下文中；
- 被拒绝的回执带着命令的作用域；
- 同步调用的补偿被拒绝时会停放；
- 另一个上下文的命令的回执不再按 Saga 的 `@Retry` 重试；
- 应答客户端的 `stream` 会发出被拒绝和失败的结果。

#7007 [no Upgrades? paragraph]
#7005 ## Upgrades paragraph
English:

> A node runs for development only in a profile of `wow.dev-profiles` (`dev`, `local`, `test`, `demo`). A loopback `wow.internal.host` or `wow.node.address` no longer makes it one: such a node served every request to its public port as the anonymous caller when it had no `CallerResolver`, and fell back to memory without a database, a deployment bound to loopback behind a sidecar included. Without a development profile it now refuses what any deployment refuses: no durable backend, no `CallerResolver`, topics in memory. A local run that relied on its loopback internal port activates `dev`; a deployment gives the node its database and its `CallerResolver` (or CoSec). A `wowServer` test without a development profile is refused the same way: give it `"dev"` (`wowServer<App>("dev")`), or what a deployment needs (`wow.backend=memory` named, a `CallerResolver`). `wowServer(…, topics = …)` needs a development profile.

Chinese:

> 节点只在 `wow.dev-profiles` 中的某个 profile（`dev`、`local`、`test`、`demo`）中才运行于开发模式。回环的 `wow.internal.host` 或 `wow.node.address` 不再让节点成为开发节点：这样的节点在没有 `CallerResolver` 时把公开端口上的每个请求都当作匿名调用方处理，没有数据库时退回内存，绑定在回环上、位于 sidecar 之后的部署也是如此。没有开发 profile 时，它现在拒绝任何部署都会拒绝的东西：没有持久后端、没有 `CallerResolver`、主题在内存中。依赖回环内部端口的本地运行改为激活 `dev`；部署要给节点提供数据库和 `CallerResolver`（或 CoSec）。没有开发 profile 的 `wowServer` 测试同样会被拒绝：给它 `"dev"`（`wowServer<App>("dev")`），或者部署需要的东西（显式指定 `wow.backend=memory`、一个 `CallerResolver`）。`wowServer(…, topics = …)` 需要开发 profile。

#7002 ## Upgrades paragraph
For Operate › Upgrades, "Your code: the contract":

> **An aggregate named as a member of its contract.** An aggregate whose name, in Pascal case, is `NAME`, `Errors` or `Wait` is `<Name>Aggregate` in its context's generated contract, in Kotlin and in TypeScript alike: an aggregate `errors` is `ErrorsAggregate`, and `wait` is `WaitAggregate`. A TypeScript client of an aggregate `wait` reads `<Context>Contract.WaitAggregate` where it read `<Context>Contract.Wait`, and a Kotlin peer of an aggregate `errors` in a context with no shared error codes reads `<Context>Contract.ErrorsAggregate` where it read `<Context>Contract.Errors`: generate the client again, or rebuild the api module, and rename what the compiler reports.

> **与契约成员同名的聚合。** 名称的帕斯卡命名形式为 `NAME`、`Errors` 或 `Wait` 的聚合，在其上下文生成的契约中是 `<Name>Aggregate`，Kotlin 与 TypeScript 一致：名为 `errors` 的聚合是 `ErrorsAggregate`，名为 `wait` 的聚合是 `WaitAggregate`。聚合 `wait` 的 TypeScript 客户端读取 `<Context>Contract.WaitAggregate`，此前读取 `<Context>Contract.Wait`；在没有共享错误码的上下文中，聚合 `errors` 的 Kotlin 对端读取 `<Context>Contract.ErrorsAggregate`，此前读取 `<Context>Contract.Errors`：重新生成客户端，或重新构建 api 模块，并按编译器报告的位置改名。

#6999 [no Upgrades? paragraph]
#7004 [no Upgrades? paragraph]
#7003 ## Upgrades paragraph
Specs written with the test DSL now send and settle timers as a node does. A scheduled command runs in the operator and scope of the commit that scheduled it, and only with the request facts a derived command inherits. A scheduled command whose decision throws leaves its timer pending, then parked after ten attempts, instead of fired. A spec of one aggregate whose decision schedules a command for another aggregate of its context fails at `advanceTime`: test that command in a spec of its own aggregate. A handling whose downstream keeps pushing back parks at its twentieth attempt. Specs use a node's default retries whatever a service configures.

用测试 DSL 编写的规格现在像节点那样发送并结算定时器。定时的命令在为它定时的那次提交的操作人和作用域中执行，且只带派生命令会继承的请求事实。决策抛出异常的定时命令会让定时器保持待处理，十次尝试后停放，而不再是已触发。若一个聚合的规格中的决策为同一上下文中另一个聚合定时命令，规格会在 `advanceTime` 时失败：请在那个聚合自己的规格中测试这条命令。下游一直施加背压的处理会在第二十次尝试时停放。无论服务如何配置，规格都使用节点的默认重试。

#7000 [no Upgrades? paragraph]
#6998 [no Upgrades? paragraph]
#6996 ## Upgrades paragraph
Specs written with the test DSL hold commands to what a node does with them. Rebuild the tests against this version: `aggregateSpec` and `aggregateSpecTests` take a `policy`.
- **Give a spec its aggregate's access policy** wherever the service registers one: `aggregateSpec(model, caller = …, policy = …)`. Its commands are then decided on their callers' claims.
- **Send a command that names a `scope` with a caller in that scope**: `caller = Caller.owner("alice")`, or `Caller.of(scope = …, subject = …)`. A command without a caller is the anonymous caller's, which the gateway refuses a scope.
- **Ids past the limit fail as malformed.** That is a request id, tenant, owner or space id longer than 256 characters.
- **A saga spec names a scheduled command as a node does**: by its target's model, given with `givenAggregate`, or by the target's contract on the test class path. Give one where a spec schedules a command to a target it describes by neither.

用测试 DSL 编写的规格会按节点的处理方式检查命令。请针对这个版本重新构建测试：`aggregateSpec` 和 `aggregateSpecTests` 新增了 `policy` 参数。
- **为规格给出聚合的访问策略**，只要服务为该聚合注册了策略：`aggregateSpec(model, caller = …, policy = …)`。这时命令按其调用方的声明来决定。
- **指定了 `scope` 的命令要由该作用域中的调用方发出**：`caller = Caller.owner("alice")`，或 `Caller.of(scope = …, subject = …)`。没有调用方的命令属于匿名调用方，网关不允许它指定作用域。
- **超过长度限制的 ID 按格式错误失败**，即长度超过 256 个字符的请求 ID、租户、所有者或空间的 ID。
- **Saga 规格像节点那样为定时的命令命名**：按目标的模型（用 `givenAggregate` 给出），或测试类路径上目标的契约。若规格向两者都没有描述的目标定时发送命令，就给出其中之一。

#6995 [no Upgrades? paragraph]
#6994 ## Upgrades paragraph
English:

> A test's fake peer (`fakePeer`, `wow-test-spring-boot`) answers a call as the real peer does. A command the callee's `wow.callers.<service>.commands` do not permit is 403 `wow.call.command-forbidden`; with none given, every system command (`wow.*`) is refused. A body that is not JSON, or does not read as its command, is 400 `wow.command.malformed`. A test whose saga calls a peer's system command states the peer's permit, `fakePeer(context, callers = mapOf("<calling service>" to listOf("wow.lifecycle.DeleteAggregate")))`, as the peer's configuration must. `fakePeer` takes the map as a second parameter with a default, so Kotlin sources compile unchanged; recompile code built against the earlier signature.

Chinese:

> 测试用的假对端（`fakePeer`，`wow-test-spring-boot`）像真实对端一样回答调用：被调用方的 `wow.callers.<service>.commands` 不允许的命令回答 403 `wow.call.command-forbidden`，不给时所有系统命令（`wow.*`）都被拒绝；不是 JSON 或读不成命令的命令体回答 400 `wow.command.malformed`。Saga 调用对端系统命令的测试要写明对端的许可：`fakePeer(context, callers = mapOf("<调用方服务>" to listOf("wow.lifecycle.DeleteAggregate")))`，与对端的配置必须写明一样。`fakePeer` 以带默认值的第二个参数接收这个映射，Kotlin 源码无需改动即可编译；按旧签名编译的代码需要重新编译。

#6992 ## Upgrades paragraph
For the docs PR that collects the round's paragraphs into Operate › Upgrades.

**English:**

An owning service answers an operator's action for the parking the action names: a retry, a skip, a mark or a retry spec on a parking it no longer holds is published as resolved for that parking alone, so the ops service keeps a later parking open. An answer that leaves the entry as it was, such as a spec applied again unchanged, now ends the request the ops console shows as pending. The fix is on both sides: a service still on the earlier runtime publishes undated resolutions and reuses the entry's request id, so upgrade the services that publish their ledgers as well as the ops service ([The Ops Service and Console](../operate/ops-console)).

**中文：**

所属服务按操作所指的那一次停放答复运维人员的操作：对它已不再持有的停放所做的重试、跳过、标记或重试规格，只对那一次停放发布为已解决，所以 ops 服务让更晚的停放保持未解决。让条目保持原样的答复（例如再次应用未变的重试规格）现在会结束 ops 控制台显示为待处理的请求。修复在两端：仍在旧运行时上的服务会发布不带日期的解决结果，并复用条目的请求 ID，所以除了 ops 服务，也要升级发布台账的各个服务（[ops 服务与控制台](../operate/ops-console)）。

#6991 ## Upgrades paragraph
For Operate › Upgrades, "Your code: the contract", after its first paragraph:

> **What the check reads differently from this version.** A command whose aggregate id moves to another field of its body, or leaves it (`idProperty`), fails the check, as a removed field does: a client names the aggregate by that field. Like any break, it needs the service's next major version or a new command beside the old one. The check no longer reports as breaking a command or event renamed with its old name kept as an alias, simple or full (additive), an alias rewritten between its simple and its full name (no change), or a polymorphic field made optional (additive). An `anyOf` or `oneOf` whose alternatives overlap, left as it was, is no change, where one of its alternatives was reported added or breaking. A baseline that a Wow pre-release recorded with a Java record command's id under its component's name (#6952 names it as Jackson writes it) reads as the id moved: run `./gradlew updateWowManifestBaseline -Pwow.manifest.allowBreaking` once, which records the new `idProperty` within the line and logs it in `build/wow/manifest-baseline.txt`, then review and commit the baseline (a service on a pre-release of its major's first version, such as `1.0.0-beta.1`, records it without the flag).

> **从这个版本起检查读法不同的改变。** 命令的聚合 ID 改由命令体的另一个字段给出，或不再由命令体给出（`idProperty`），会像删除字段一样让检查失败：客户端用这个字段指明聚合。与其他破坏性改变一样，它需要服务的下一个主版本，或者在旧命令旁边新增一条命令。检查不再把以下改变报告为破坏性的：重命名命令或事件并把旧名称（简单名或全名）保留为别名（新增性的）；把别名在简单名和全名之间改写（没有改变）；把多态字段改为可选（新增性的）。备选项有重叠且保持原样的 `anyOf` 或 `oneOf` 没有改变，而此前会把其中一个备选项报告为新增或破坏性的。Wow 预发布版记录的基线若以组件名称写着 Java record 命令的聚合 ID（#6952 改为按 Jackson 写出的名称），会读作聚合 ID 改由另一个字段给出：运行一次 `./gradlew updateWowManifestBaseline -Pwow.manifest.allowBreaking`，它在兼容线内记录新的 `idProperty`，并记在 `build/wow/manifest-baseline.txt` 中，然后评审并提交基线（服务处于其主版本首个版本的预发布版，例如 `1.0.0-beta.1` 时，无需该标志即可记录）。

#6989 ## Upgrades paragraph
English:

> `spring.codec.max-in-memory-size` no longer sets the internal port's message limit: Spring Boot 4 binds only `spring.http.codecs.max-in-memory-size`, and both ports follow it.

Chinese:

> `spring.codec.max-in-memory-size` 不再设置内部端口的消息上限：Spring Boot 4 只绑定 `spring.http.codecs.max-in-memory-size`，两个端口都遵循它。

#6990 [no Upgrades? paragraph]
#6988 ## Upgrades paragraph
English:

> The view store service refuses a user's share of a personal view (`ShareView`) without `view-store.shared-writer`, `403 wow.auth.forbidden`, where it took it from any user and published the view to the tenant's shared list ([Storage](../typescript/view-engine/storage)). Creating a shared view and claiming one needed the role already. Grant it to whoever shares their views, and answer the front end's `permissions.changeAudience` by it, as `@ahoo-wang/wow-view-store`'s README says.

Chinese:

> 视图存储服务拒绝没有 `view-store.shared-writer` 的用户共享个人视图（`ShareView`），答以 `403 wow.auth.forbidden`；过去它接受任何用户的共享，并把视图发布到租户的共享列表（[存储](../typescript/view-engine/storage)）。创建共享视图和认领共享视图原本就需要这个角色。把它授予要共享视图的人，并按它回答前端的 `permissions.changeAudience`，如 `@ahoo-wang/wow-view-store` 的 README 所说。

#6981 ## Upgrades paragraph
A saga that names an aggregate it creates by an id from `context.ids` creates a second one each time its commit is handled again, after a retry or a redelivery, because a derived request id comes from its target's id. Send the creating request without an id: the target's admission gives it the aggregate its first attempt created. In `sagaSpec`, `expectDeterministic()` and `whenRedelivered()` now draw new ids as a node does, so they fail such a saga. A spec that expected a handling after the first to draw `id-1` or `<aggregate>-1` again now sees the next id.

用 `context.ids` 中的 ID 为要创建的聚合命名的 Saga，在提交每次被再处理时（重试或重新投递之后）都会再创建一个聚合，因为派生请求的 ID 取自目标的 ID。改为发出不带 ID 的创建请求：目标的准入会把第一次尝试创建的聚合交给它。在 `sagaSpec` 中，`expectDeterministic()` 和 `whenRedelivered()` 现在像节点一样生成新的 ID，因此这样的 Saga 无法通过它们。原本期望第一次之后的处理再次得到 `id-1` 或 `<aggregate>-1` 的规格，现在会得到下一个 ID。

#6952 ## Upgrades paragraph
For Operate › Upgrades, "Your code: the contract", after its first paragraph:

> **A Java record command's aggregate id.** When `@JsonProperty` or `@JsonNaming` renames a record's `@AggregateId` component, the Manifest's `idProperty` is now that name, the field Jackson reads, where it was the component's name. A client generated from the Manifest names the id by `idProperty`: generate it again. A baseline recorded before names the component, so the check that compares `idProperty` (#6842) reads the id as moved to another field, which is breaking.

> **Java record 命令的聚合 ID。** 当 `@JsonProperty` 或 `@JsonNaming` 为记录的 `@AggregateId` 组件改名时，Manifest 的 `idProperty` 现在是这个名称，即 Jackson 读取的字段；此前它是组件的名称。由 Manifest 生成的客户端按 `idProperty` 指明聚合 ID：请重新生成。此前记录的基线写的是组件的名称，因此比较 `idProperty` 的检查（#6842）会把聚合 ID 读作改由另一个字段给出，这是破坏性的改变。

#6987 [no Upgrades? paragraph]
#6986 ## Upgrades paragraph
For the docs PR that collects the round's paragraphs into Operate › Upgrades.

**English:**

**Breaking:** migrate-data's `compare` and `export` exit with `4` when they find differences, where they exited with `3`, which is now the JVM's exit on an `OutOfMemoryError`. A script that reads `3` as differences must read `4`, and a Job manifest copied from the earlier `deploy/migrate-data/job/job.yaml` must fail at once on `1`, `2` and `4`, not `1`, `2` and `3`: the list goes with the image. migrate-data also exits with `5` when a store or the network was unavailable or timed out, where it exited with `1`. The recipe's Job does with each code ([migrate-data CLI](../reference/migrate-data#exit-codes)):

| Exit code | Meaning | The Job |
| --- | --- | --- |
| `0` | Done. | Completes. |
| `1` | Failed: a refusal of the tool's, a configuration it cannot read, or a defect. | Fails at once. |
| `2` | Usage error. | Fails at once. |
| `3` | The JVM's, on an `OutOfMemoryError` (the image's `-XX:+ExitOnOutOfMemoryError`). | Runs it again from the checkpoint. |
| `4` | `compare` or `export` found differences. | Fails at once. |
| `5` | A store or the network was unavailable or timed out. | Runs it again from the checkpoint. |

The `CUTOVER` line says where the import ended: a pass that imports nothing leaves the cut-over where it was, instead of moving it to the log's head.

**中文：**

**不兼容变更：** migrate-data 的 `compare` 和 `export` 发现差异时以 `4` 退出（以前是 `3`），`3` 现在是 JVM 因 `OutOfMemoryError` 退出时的退出码。把 `3` 读作差异的脚本必须改读 `4`；从之前的 `deploy/migrate-data/job/job.yaml` 复制的 Job 清单必须在 `1`、`2`、`4` 时立即失败，而不是 `1`、`2`、`3`：这个列表随镜像而定。migrate-data 在某个存储或网络不可用或超时时还会以 `5` 退出（以前是 `1`）。配方中的 Job 对每个退出码的处理如下（[migrate-data CLI](../reference/migrate-data#exit-codes)）：

| 退出码 | 含义 | Job |
| --- | --- | --- |
| `0` | 完成。 | 完成。 |
| `1` | 失败：工具的拒绝、读不了的配置，或缺陷。 | 立即失败。 |
| `2` | 用法错误。 | 立即失败。 |
| `3` | JVM 因 `OutOfMemoryError` 退出（镜像设置的 `-XX:+ExitOnOutOfMemoryError`）。 | 从检查点重新运行。 |
| `4` | `compare` 或 `export` 发现了差异。 | 立即失败。 |
| `5` | 某个存储或网络不可用或超时。 | 从检查点重新运行。 |

`CUTOVER` 行给出导入在哪里结束：什么也没导入的一轮让切换点保持原处，而不是把它移到日志的头部。

#6985 [no Upgrades? paragraph]
#6978 [no Upgrades? paragraph]
#6982 ## Upgrades paragraph
English:

> `@ahoo-wang/wow-view-store`'s `WowViewStore` throws a `RangeError` for a `pageSize` below 1, or not a whole number, where such a store listed views without end. It also sends a write's retry from where its first attempt went, so a save that landed is answered as the duplicate it is after the view moved. It writes a stored system view that changed and changed back at its version now, where it answered `CONFLICT`. And it refuses preferences at a revision it never issued as `CONFLICT`, where it answered `UNAVAILABLE`.

Chinese:

> `@ahoo-wang/wow-view-store` 的 `WowViewStore` 对小于 1 或不是整数的 `pageSize` 抛出 `RangeError`，过去这样的存储会无止境地列出视图。它还从一次写入第一次尝试发出的地方发出它的重试，所以视图移走之后，已经落地的保存会被当作重复请求来回答。改过又改回的存储系统视图，按它现在的版本写入，过去答以 `CONFLICT`。对它从未发出过的修订写入偏好，答以 `CONFLICT`，过去答以 `UNAVAILABLE`。

#6979 [no Upgrades? paragraph]
#6954 ## Upgrades paragraph
For the docs PR that collects the round's paragraphs into Operate › Upgrades.

**English:**

A saga or a reactor of a migrated service's own context passes over its log's imported history up to the cut-over however it starts, not only on its first start at the log's end, as it passes over a peer's records published as imported; projections still build from it, and a compensation's parked retry of an imported entry is still handled. A first cut-over sees no difference. On a cut-over again after a rollback by export, the history the second import appends is acted on by 9.x's sagas alone: before stopping V10 for the rollback, let every subscription's backlog reach zero (`wow.subscription.backlog`), the service's own sagas included ([Cut Over](../migrate/cutover)).

**中文：**

已迁移服务自身上下文的 Saga 或 Reactor 无论怎样启动，都会略过其日志中切换点之前导入的历史，而不只是在首次从日志末尾启动时如此，正如它略过对端发布为已导入的记录；投影仍从中构建，补偿对已导入条目的停放重试仍会处理。首次切换看不到任何差别。在通过导出回退之后再次切换时，第二次导入追加的历史只由 9.x 的 Saga 处理：为回退而停止 V10 之前，让每个订阅的积压（`wow.subscription.backlog`）降到零，包括服务自身的 Saga（[切换](../migrate/cutover)）。

#6977 [no Upgrades? paragraph]
#6980 ## Upgrades paragraph
English:

> `@ahoo-wang/wow-react`'s `useCursorQuery` hands a page's cursor only to the query, and the request options, whose page answered it ([React](../typescript/react)): with `autoExecute: false`, `fetchNext()` after the query or the request options changed reads the first page of the query as it now is, where it read on from the old query's cursor and appended its page to the old rows. `useStreamQuery`'s `abort()` keeps every row read before it, and the position of the last, where it kept only the rows already shown.

Chinese:

> `@ahoo-wang/wow-react` 的 `useCursorQuery` 只把某一页的游标交给答出那一页的查询与请求选项（[React](../typescript/react)）：`autoExecute: false` 时，查询或请求选项变了之后的 `fetchNext()` 读取当前查询的第一页；过去它从旧查询的游标继续读，并把那一页追加到旧的行后面。`useStreamQuery` 的 `abort()` 保留中止前读到的每一行和最后一行的位置；过去它只保留已经显示出来的行。

#6950 [no Upgrades? paragraph]
#6953 [no Upgrades? paragraph]
#6942 [no Upgrades? paragraph]
#6955 [no Upgrades? paragraph]
#6951 [no Upgrades? paragraph]
#6949 ## Upgrades paragraph
For "From a 10.0.0 pre-release" on Operate › Upgrades. English:

The `wow-service` chart's `autoscaling.keda.minReplicas` is at least 1, as the HPA's: a values file that sets 0 is refused by the schema. The ScaledObject's only trigger is the backlog the service's own pods report, so a service KEDA scaled to zero never came back ([Kubernetes](./kubernetes)).

Chinese:

`wow-service` chart 的 `autoscaling.keda.minReplicas` 至少是 1，和 HPA 的一样：设为 0 的 values 文件会被 schema 拒绝。ScaledObject 唯一的触发器是服务自己的 Pod 上报的积压，所以被 KEDA 缩到零的服务再也回不来（[Kubernetes](./kubernetes)）。

#6938 [no Upgrades? paragraph]
#6948 [no Upgrades? paragraph]
#6947 [no Upgrades? paragraph]
#6944 [no Upgrades? paragraph]
#6946 [no Upgrades? paragraph]
#6941 [no Upgrades? paragraph]
#6943 [no Upgrades? paragraph]
#6940 [no Upgrades? paragraph]
#6939 [no Upgrades? paragraph]
#6924 [no Upgrades? paragraph]
#6883 [no Upgrades? paragraph]
#6923 [no Upgrades? paragraph]
#6922 [no Upgrades? paragraph]
#6886 [no Upgrades? paragraph]
#6885 [no Upgrades? paragraph]
#6884 [no Upgrades? paragraph]
#6878 [no Upgrades? paragraph]
#6877 [no Upgrades? paragraph]
#6876 [no Upgrades? paragraph]
#6881 [no Upgrades? paragraph]
#6875 [no Upgrades? paragraph]
#6873 [no Upgrades? paragraph]
#6874 [no Upgrades? paragraph]
#6871 [no Upgrades? paragraph]
#6870 [no Upgrades? paragraph]
#6862 [no Upgrades? paragraph]
#6866 [no Upgrades? paragraph]
#6868 [no Upgrades? paragraph]
#6867 [no Upgrades? paragraph]
#6865 [no Upgrades? paragraph]
#6864 [no Upgrades? paragraph]
#6861 [no Upgrades? paragraph]
#6847 [no Upgrades? paragraph]
#6846 [no Upgrades? paragraph]
#6827 [no Upgrades? paragraph]
#6826 [no Upgrades? paragraph]
#6824 [no Upgrades? paragraph]
#6820 [no Upgrades? paragraph]
#6822 [no Upgrades? paragraph]
#6823 [no Upgrades? paragraph]
#6802 [no Upgrades? paragraph]
#6821 [no Upgrades? paragraph]
#6805 [no Upgrades? paragraph]
#6804 [no Upgrades? paragraph]
#6803 [no Upgrades? paragraph]
#6799 [no Upgrades? paragraph]
#6801 [no Upgrades? paragraph]
#6800 [no Upgrades? paragraph]
#6798 [no Upgrades? paragraph]
#6792 [no Upgrades? paragraph]
#6791 [no Upgrades? paragraph]
#6790 [no Upgrades? paragraph]
#6788 [no Upgrades? paragraph]
#6789 [no Upgrades? paragraph]
#6784 [no Upgrades? paragraph]
#6781 [no Upgrades? paragraph]
#6780 [no Upgrades? paragraph]
#6779 [no Upgrades? paragraph]
#6778 [no Upgrades? paragraph]
#6777 [no Upgrades? paragraph]
#6775 [no Upgrades? paragraph]
#6771 [no Upgrades? paragraph]
#6770 [no Upgrades? paragraph]
#6766 [no Upgrades? paragraph]
#6769 [no Upgrades? paragraph]
#6765 [no Upgrades? paragraph]
#6764 [no Upgrades? paragraph]
#6762 [no Upgrades? paragraph]
#6753 [no Upgrades? paragraph]
