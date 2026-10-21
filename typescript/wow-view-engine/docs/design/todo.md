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
- **Firefox／WebKit 的视觉回归基线与 PR 任务**（D73）：首发前已在本机走查过一次（2026-09-30，没有视觉回归，查出的缺陷已修）。首发后两种引擎各立一套截图基线，PR 上加 Firefox／WebKit 的交互测试任务。判据：两种引擎各有一套基线、PR 上各有一个任务，差异修掉或记下原因。落点：Storybook 的截图脚本、`.github/workflows/ci.yml`。
- **分析的注释；「对比上一期」一步生成两个指标**（Q59 余项）：整段对比已由 D88（上一期、去年同期两种算出的线）与 D90（指标卡较去年同期）做完。剩两点：注释要随视图存下（视图存储已有，要在配置里加成员）；D71 里「对比上一期」一步生成两个带时间条件的指标与变化指标。落点：`src/analysis/`、[ui/analysis.md](ui/analysis.md)。
- **「指定日期」切换时自动打开日历；看板筛选条吸顶**（R2-41 余项）：#3849 已做「指定日期」预填当前生效日、选一天即关上日历并把焦点交回日期按钮。没做的两点按 #3849 的理由：预填之后看板不再等日期，自动打开不再必要；吸顶是超出本项的布局改动。判据：真人走查（D73）或宿主反馈仍有「多一步」「选完看不到筛选条」时再做；做时切到「指定日期」即打开日历，筛选条滚动时吸顶，在 390 宽与键盘下不遮住面板与焦点。落点：[ui/dashboard.md](ui/dashboard.md)、`src/ui/dashboard/FilterBar.tsx`、`src/ui/filter/inputs/date.tsx`。

- **V10 的有界总数**（7.3a，[view-store-backend.md](view-store-backend.md) 10.3）：V10 服务经 HTTP 拒绝 `EXACT` 总数，`viewSourceOf` 只能取 `BOUNDED`（超过预算答上限、`exact: false`）；端口的 `PagedList` 没有 `exact`，引擎把上限当确数显示。判据：`ViewSource.paged` 的结果可带可选的 `exact`，分页条与导出在它为 `false` 时说「至少 N 条」，`viewSourceOf` 传过来；9.x 源不变。落点：`src/runtime/source.ts`、记录视图的分页条与导出、`wow-view-store/src/source.ts`。

### Wow 服务端（Kotlin）

视图引擎之外、要改 Wow 服务端的事。

当前没有条目。2026-10-20 对照 V10 复核了 9.x 留下的三条，都已删去：ES 的时间分组与过滤语义差异两条落在 9.x 的 `wow-elasticsearch`、`wow-query` 上，V10 的 `:elasticsearch`（`backends/elasticsearch`）按 Manifest 的 schema 映射状态，时间字段映射为 `date` 时直接 `date_histogram`，各后端的过滤语义由 `:query-tck` 的 `FilterSemantics` 矩阵统一检查；Reactor Netty 1.3.7 停止读取一条与 [docs/todo.md](../../../../docs/todo.md) 里「Reactor Netty 1.3.8 进 BOM 后去掉 `KeepAliveReads`」是同一个缺陷，在那里跟进。
