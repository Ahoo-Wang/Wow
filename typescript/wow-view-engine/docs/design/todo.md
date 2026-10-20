# TODO

规则，先读再加：

- 只记**已经决定、但还没做**的事。尚无结论的产品问题不在这里，在 [decisions.md#搁置待议](decisions.md#搁置待议)。
- 每条都要写清 **为什么** / **做完的判据** / **落点**（design 里的哪一页，或哪个文件）。三者缺一就不是一条 TODO，是一句抱怨。
- 做完就**删掉**这一条，不打勾、不留归档。历史在 git 里。
- 改行为之前先看这里有没有对应项；有就接着做，别另起一条。

## 首发前

npm 首发（`9.2.0-rc.0`）之前要做的事记在这里，按顺序做，同时只做一件。审查里「现在做还是以后做」默认现在做：公开面（[D29](decisions.md) 快照）上的破坏性改动趁首发前一次改到位，不为发布前的形态留兼容层（用户 2026-09-24）。

当前没有条目。

## 首发后再议

用户已定推迟到首发之后；排进某个版本时各自成为带判据的条目。服务端（Kotlin）的条目单列在后面。

### 视图引擎、控制台与 Storybook

- **真人读屏走查（VoiceOver／NVDA）**（[D73](decisions.md#d73-真人读屏走查与多浏览器视觉回归放到首发之后2026-09-29)）：VoiceOver + Safari、NVDA + Firefox／Chrome 各走一遍记录工作台、分析、仪表盘与两种嵌入的核心任务，照 [screen-reader-walkthrough.md](screen-reader-walkthrough.md) 交回结果。判据：每个任务一行「能否完成 / 实际念出的话 / 与预期的差异」，差异修掉或各成一条；声明的「评估方法」补上读屏软件与版本。落点：文档站「视图引擎的可访问性」。
- **Firefox／WebKit 的视觉回归基线与 PR 任务**（D73）：首发前已在本机走查过一次（2026-09-30，没有视觉回归，查出的缺陷已修）。首发后两种引擎各立一套截图基线，PR 上加 Firefox／WebKit 的交互测试任务。判据：两种引擎各有一套基线、PR 上各有一个任务，差异修掉或记下原因。落点：Storybook 的截图脚本、`.github/workflows/typescript-storybook.yml`。
- **分析的注释；「对比上一期」一步生成两个指标**（Q59 余项）：整段对比已由 D88（上一期、去年同期两种算出的线）与 D90（指标卡较去年同期）做完。剩两点：注释要随视图存下（视图存储已有，要在配置里加成员）；D71 里「对比上一期」一步生成两个带时间条件的指标与变化指标。落点：`src/analysis/`、[ui/analysis.md](ui/analysis.md)。
- **「指定日期」切换时自动打开日历；看板筛选条吸顶**（R2-41 余项）：#3849 已做「指定日期」预填当前生效日、选一天即关上日历并把焦点交回日期按钮。没做的两点按 #3849 的理由：预填之后看板不再等日期，自动打开不再必要；吸顶是超出本项的布局改动。判据：真人走查（D73）或宿主反馈仍有「多一步」「选完看不到筛选条」时再做；做时切到「指定日期」即打开日历，筛选条滚动时吸顶，在 390 宽与键盘下不遮住面板与焦点。落点：[ui/dashboard.md](ui/dashboard.md)、`src/ui/dashboard/FilterBar.tsx`、`src/ui/filter/inputs/date.tsx`。

- **V10 的有界总数**（7.3a，[view-store-backend.md](view-store-backend.md) 10.3）：V10 服务经 HTTP 拒绝 `EXACT` 总数，`viewSourceOf` 只能取 `BOUNDED`（超过预算答上限、`exact: false`）；端口的 `PagedList` 没有 `exact`，引擎把上限当确数显示。判据：`ViewSource.paged` 的结果可带可选的 `exact`，分页条与导出在它为 `false` 时说「至少 N 条」，`viewSourceOf` 传过来；9.x 源不变。落点：`src/runtime/source.ts`、记录视图的分页条与导出、`wow-view-store/src/source.ts`。

### Wow 服务端（Kotlin）

视图引擎之外、要改 Wow 服务端的事。

- **ES 的时间分组、派生指标与表达式不跑逐文档脚本**（R2-58 余项）：#3847 只让 UTC、单值、顶层、整型 epoch-millis 字段的 `DATE_HISTOGRAM` 直接按字段分组（ES 拒绝数值字段上的任何 `time_zone`）；其他时区、`DATE_PART`、`DATE_DIFF` 与表达式（分组、指标、指标条件）仍每文档跑 Painless，限制脚本或 `allow_expensive_queries=false` 的集群会拒绝，ES 指南已写明。建议的 `date` 映射没做。判据：策划的 mapping 与存量模板把 Wow 的时间字段映射为 `date`（`format: epoch_millis`，`_source` 不变，不是存储格式变化）；映射是 `date` 时时间分组与日期部件直接用字段并带 `time_zone`；旧索引保留脚本路径；TCK 两条路径都过。落点：`wow-elasticsearch` 的 `ElasticsearchAggregationSources.kt`、`ElasticsearchAggregationScripts.kt`、索引模板，文档站 `guide/extensions/elasticsearch.md`。
- **后端之间的过滤语义差异**（R2-93）：`IS_EMPTY` 对 null 与 `[]`、`EXPRESSION` 的 double 精度、前导通配、无字段的 `SEARCH`，ES 与 MongoDB 不一致，目前只在 TCK 里记下并跳过（`ElasticsearchSnapshotQueryBackendTest.kt`）。推迟：控制台的系统视图一个都不用。判据：后端差异文档逐条写明；描述符为每一项加能力标志，准入据此拒绝而不是给出不同结果。落点：`wow-elasticsearch` 的过滤编译、`wow-query` 的描述符、文档站 ES 指南。
- **Reactor Netty 1.3.7：复用的连接上服务端停止读取**（用户 2026-10-03：只在这里记下，不向上游报）：请求 A 的处理不在事件循环上，A 的响应已经发完、处理还没结束时，客户端立刻在同一条 keep-alive 连接上发请求 B。Reactor Netty 把 B 和它的请求结束标记排进队列，答完 B、清掉标记以后不再恢复读取（疑在 `HttpTrafficHandler.resumeRead`／`run`），这条连接上的请求 C 要等客户端超时关掉连接才被读到。CI 里表现为 `ViewStoreMongoTest` 偶发超时；#3867 加了诊断，超时时把连接事件与全部线程写进失败信息，并在 Linux epoll 下复现过一次。记下时 1.3.7 是 1.3.x 的最新版，由 Spring Boot 4.1.1 管理；任何跑在 Reactor Netty 上的服务端都可能碰到，很少见。判据：Reactor Netty 发出 1.3.7 之后的版本，或 #3867 的诊断在 CI 里再次抓到它时复查。新版本在 #3867 的复现上（Linux 容器里循环跑 `ViewStoreMongoTest`）不再停住，就删掉这一条；仍会停住，就评估绕过办法再定做不做，例如钉住修好的 Reactor Netty 版本、服务端在响应完成后补一次读、集成测试的客户端不复用连接（只绕过 CI，不解决生产）。落点：`gradle/libs.versions.toml`（Spring Boot 管理的 Reactor Netty 版本）、`view-store/wow-view-store-starter/src/integrationTest/` 的 `ViewStoreHostSpec.kt`、`ViewStoreTestClient.kt`。
