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
- **E：看板布局不用手算坐标**：面板位置是手写的 `x/y/w/h`，插一个面板要重算后面的 `y`。加按行排布的辅助，或 `y` 省略时自动接在上一行后。判据：控制台的三块板改用它后布局不变。落点：`src/dashboard/layout.ts`。
- **G：枚举字段宽容 `EQ`**：字段从字符串改成枚举时，已存的 `EQ` 条件全部失效。写的时候对枚举上的 `EQ` 按一个值的 `IN` 读。判据：枚举字段上存下的 `EQ` 条件照常准入与编译。落点：`src/filter/kinds/enum.ts`。
- **与上一期整段、去年同期对比（Q59）与注释**：要第二条查询与运行时路径；注释要随视图存下（视图存储已有，要在配置里加成员）。D71 里「对比上一期一步生成两个指标」一并在此。落点：`src/analysis/`、运行时（第二条查询）、[ui/analysis.md](ui/analysis.md)。
- **Storybook 审查（2026-09-26）的 P2**：P0、P1 已全部处置。落点：`typescript/storybook/docs/review-2026-09-26.md`。
- **搜索在真服务端上命中**：示例服务端的快照在 MongoDB 上，没有全文能力，端到端只验证了 `SEARCH` 被拒且如实报出。判据：契约作业有了 Elasticsearch 快照后，搜索用例改为断言命中。落点：Wow 仓端到端的 `view-engine/recordView` 用例（`typescript/integration-test/`）。
- **视图列表被截断时说出来**（用户 2026-10-01：首发后；R2-26 余项）：Wow 的视图存储每个受众最多列 1000 个视图（`LIST_LIMIT`，不排序），多出的静默丢掉。#3829 已让地址里不在列表中的 id 先 `store.get` 再判定；列表上的提示没做，因为 `ViewStore` 端口没有办法说「截断了」，要给端口加成员（只能是可选成员，[extension.md「宿主实现的接口怎样长」](extension.md#宿主实现的接口怎样长)）。判据：截断时工作台的视图列表与「管理视图」写明「只显示了前 N 个」（或报一条 issue），conformance 套件有截断的用例；未截断时行为不变。落点：`ViewStore` 端口、`typescript/wow-view-store/src/wowViewStore.ts`、`test/conformance/viewStoreConformance.ts`、`src/ui/workbench/address.ts`。
- **「指定日期」切换时自动打开日历；看板筛选条吸顶**（R2-41 余项）：#3849 已做「指定日期」预填当前生效日、选一天即关上日历并把焦点交回日期按钮。没做的两点按 #3849 的理由：预填之后看板不再等日期，自动打开不再必要；吸顶是超出本项的布局改动。判据：真人走查（D73）或宿主反馈仍有「多一步」「选完看不到筛选条」时再做；做时切到「指定日期」即打开日历，筛选条滚动时吸顶，在 390 宽与键盘下不遮住面板与焦点。落点：[ui/dashboard.md](ui/dashboard.md)、`src/ui/dashboard/FilterBar.tsx`、`src/ui/filter/inputs/date.tsx`。
- **打开者已经不在时，关闭交还的焦点不落到 `<body>`**（#3828 记下的缺口）：#3828 只修了操作对话框——批量命令先完成、选择栏（打开者）先消失时，`finalFocus` 改落到状态行的控件。`useLanding` 与操作面只在待命的那次渲染里落焦点；其他关闭时交还焦点的窗口、菜单、弹层，打开者若已卸载，焦点同样掉到 `<body>`（WCAG 2.4.3）。判据：盘点交还焦点的弹层，每一个在打开者卸载后关闭时都落到确定的位置；加一个通用测试（卸载打开者后关闭，`document.activeElement` 不是 `body`）。落点：`src/ui/kit/focus.ts`、`src/ui/kit` 的弹层、[host-integration.md](host-integration.md)。
- **内部可维护性**（R2-94）：文件夹内的值导入环（`analysis/` 的 `timeAxis`／`drill`／`chartSlots`／`validateChart`／`validateLevels`，`record/compile` ⇄ `summaryCurrency`，`ui/kit` 的弹层，`ui/filter` 的 `ConditionPill` ⇄ `GroupBlock`，`ui/charts` 的 `reading` ⇄ `readingStatistics`）；函数长度没有绊线；图型族散在约 14 个 `switch` 里；约 30 个多余的 export；主题文档数据放在 `src/ui/theme/*Docs.ts`。推迟：都不是公开面，也不违反层规则，只是以后的修改成本。判据：辅助函数移到叶子文件，`test/architecture.test.ts` 加「文件夹内无值环」；加 `max-lines-per-function` 绊线；下一个图型族加入时收成一处图型族描述；去掉多余 export；主题文档数据移到 `theme-check/`。落点：上述文件、`eslint.config.js`。
- **`runtime/` 的失败类收进 `runtime/failure/`**（R2-95）：`issues`、`issueReport`、`failures`、`queryFailure`、`sourceReason`、`unavailable` 平铺在 `runtime/`。推迟：内部布局，不影响宿主。判据：六个文件移进 `runtime/failure/`，层规则与公开面快照不变。`ExportCancelled`、`ActionRefused` 要不要改名为 `*Error` 是公开面的问题，见 [decisions.md](decisions.md#搁置待议) Q67。落点：`src/runtime/`。
- **放宽 React 的 peer 下限**（R2-96）：peer 下限是 `^19.3.0`，react-router 的 7.0 下限没测过；React 19.0～19.2 的宿主会得到 peer 警告。推迟：要先有低版本的冒烟。判据：在 React 19.0 与 react-router 7.0 上构建并跑过冒烟后，下限放到实测过的最低版本，兼容性页同步。落点：`pnpm-workspace.yaml` 的 catalog、文档站 `guide/typescript/compatibility.md`、[host-integration.md](host-integration.md)。
- **记录详情的上一条／下一条**（R2-97）：逐单处理要关抽屉 → ↓ → Enter。推迟：有替代路径，是新功能。判据：抽屉头有「上一条／下一条」与 ↑／↓（或 J／K），在当前页内移动并同步 `?id=`；到页首页尾时停用并说明。落点：[ui/record.md](ui/record.md#记录详情把一条读全)。
- **工作台的「放进仪表盘…」「设为共享」「复制链接」；看板的日期与筛选进地址**（R2-98）：「更多视图操作」只有「另存为」，分析师要绕到看板编辑态再找视图；看板筛选在 history state（`src/ui/workbench/address.ts`），运营不能把某一天的日报作为链接发出去。推迟：P3，有替代路径。判据：标题栏菜单有这三项；宿主选项打开时看板的筛选序列化进查询串，链接打开得到同一份筛选。落点：`src/ui/workbench/`、[host-integration.md](host-integration.md) 4.2。
- **可访问性的 P3**（R2-99）：翻页的播报不念页码；字段搜索框按 ↓ 进不了列表；图型选择器两列网格里 ↓ 是「下一个」。推迟：都能完成，只是不顺手。判据：翻页播报带「第 2／3 页」；搜索框 ↓ 进列表首项；图型选择器的方向键照 APG 网格的行为并有测试。落点：`src/ui/workbench/RecordParts.tsx` 的查询播报、`src/ui/filter/FieldChecklist.tsx`、`src/ui/analysis/ChartPicker.tsx`。
- **UX 小摩擦**（R2-100，各自 S，各有替代路径）：演示数据给已取消的单也写「已经发出」的拒绝原因（`RetailOrders.stories.tsx`）；字段搜索只剩一项时 Enter 不勾选；加了未填、未应用的条件就显示「已修改」；柱图「显示」页同一句说明重复四次；省份 GMV 图的说明写了订单数、图上没有也无提示；售后场景没有操作且记录与分析视图混在一个列表；离开守卫没有「保存并离开」；没有 ⌘S、「/」快捷键，记录与分析编辑只有整体「还原」；「管理视图」里设为共享／设为个人的图标按钮读不出方向；结果未知时的话没说写的是什么、可能已经成功、「放弃」会保留编辑。判据：逐条修掉或在本条写明不做的理由。落点：Storybook 的零售场景、`src/ui/filter/`、`src/ui/workbench/`、`src/ui/analysis/`。
- **视觉的 P3**（R2-101，外观问题，不影响读数）：图表卡顶部留白、轴标题方向不一致；无数据或无迷你图的指标卡留大块空白；单数值分析视图偏左；雷达等极坐标图在 390 宽截断轴名；演示应用两种涨跌色约定并存；打印保留了交互控件。判据：逐条修掉，截图基线随之更新。落点：`src/ui/charts/`、`src/ui/dashboard/`、[ui/theme.md](ui/theme.md#打印与强制颜色)、Storybook 演示应用。
- **绑定与准入的静默分歧**（R2-102）：`bind()` 一个拼错的定义 id 被静默忽略，路由与操作都丢了（`src/ui/workbench/ViewEngineProvider.tsx`、`bindings.ts`）；`admit` 与引擎启动的检查不一致，`admit` 报不出 `definition.source.unregistered`（`src/runtime/admission.ts` 与 `src/runtime/definitions.ts`）；控制台执行页不走引擎的地址处理，指向已删视图的 `?view=` 显示「打不开」而不是回到默认视图。推迟：P3，现有宿主都拼对了。判据：未知 id 经 `onIssue` 报 `binding.definition.unknown`；`admit` 拿到资源时也检查来源，两处都校验原始定义；执行页的偏离要么收回、要么写进控制台文档。落点：上述文件、`compensation/dashboard/src/features/Executions/ExecutionsPage.tsx`。
- **测试覆盖的缺口**（R2-103，不是已知错误，是回归的探测缺口）：conformance 未覆盖的端口行为；`src/ui/workbench/address.ts` 的失败分支；控制台 e2e 不触发存储失败与 409；控制台覆盖率阈值低；Playwright 在 CI 上重试 2 次；日期编辑器在 `environment.timeZone` 与浏览器时区不同时的偏移时刻；时区计算散在五处（`src/analysis/drill.ts`、`src/analysis/timeCharts.ts`、`src/ui/charts/periodSpan.ts`、`src/ui/filter/inputs/daterange.tsx` 等）。判据：各缺口有用例；时区计算收成一个带缓存的分区函数；控制台阈值提高、Playwright 重试降到 0 或写明原因。落点：`test/conformance/viewStoreConformance.ts`、上述文件、`compensation/dashboard/` 的 `vitest.config.ts`、`playwright.config.ts`、`e2e/support/viewStoreService.ts`。
- **阶段 7：文档站**（用户 2026-09-29：首发后再维护）：视图引擎在文档站（`documentation/docs/{zh,en}`）只有「视图引擎」指南与可访问性声明两页。补齐面向使用者的一套：入门（从零接入一个业务对象）、概念（定义、视图、看板、系统视图与用户视图、存储）、指南（宿主接入、声明式操作、Wow 存储后端与 CoSec 路径规则、主题、CSP、可访问性）、API 参考，中英两版。

### Wow 服务端（Kotlin）

视图引擎之外、要改 Wow 服务端的事。10.0 才能动的线格式不在这里，在 [`docs/compat-debt.md`「Held Until v10」](../../../../docs/compat-debt.md#held-until-v10)。

- **H：补偿控制台「每天各种结局」画成一张多序列图，由后端支持**（用户 2026-09-27）：Wow 在展开元素的聚合里够不到根字段 `createTime`；后端加「元素作用域里按上层（根）字段分组」（MongoDB 几乎不用改，Elasticsearch 要把根字段的分组放到 `nested` 外并与 composite 分页相容），TCK 两个后端都加用例，之后 TS 镜像、本包放开 `analysis.field.outside-scope`。判据：一次查询出「按天 × 事件名」，两个后端的 TCK 都过；控制台「补偿活动」板的结局是一张四条线的图。落点：`wow-query` 的 `QueryResolver`、`wow-mongo`／`wow-elasticsearch` 的聚合编译、本包 `analysis/`、控制台 `src/views/overview.ts`。
- **Kotlin ABI 基线**（R2-91；R2-45、R2-51 的余项）：9.2 的二进制破坏都是读 diff 找出来的（#3827 恢复了 `BindingError`、`Terms`、`Histogram` 的 9.1.5 构造器，#3836 让准入按 `breaking-change` 标签拦补丁），下一个 minor 仍只能靠人。判据：以 v9.1.5（或已发的 9.2.0）为基线把 binary-compatibility-validator 的 `apiCheck` 接进 CI，公开模块有 `api/*.api` 存档；改动公开签名的 PR 要更新存档，并按 AGENTS.md 标 Breaking。落点：`build-logic`、各公开模块的 `api/`、Kotlin 的 CI 工作流。
- **ES 上按指标排序的 Top-N 不读全部分组**（R2-92）：`ElasticsearchQuerySchemaAdapter` 把 `topN`／`having` 标为残余，按指标排序的前 N 要流过全部 composite 页，超过 `maxResidualGroups`（10,000）组即失败；MongoDB 能做。推迟：P2，控制台的系统视图不触发。判据：单个 TERMS 分组、无 HAVING、按指标排序时编译成带 `order` 与 `size` 的 `terms`（设 `shard_size`，或在描述符标为近似）；其余情况在描述符里公布残余上限，让准入提前告警；TCK 有超过一万组的用例。落点：`wow-elasticsearch` 的 `ElasticsearchQuerySchemaAdapter.kt` 与聚合编译、`wow-query` 的 `AggregationShape.kt`、`QueryBudget.kt`。
- **ES 的时间分组、派生指标与表达式不跑逐文档脚本**（R2-58 余项）：#3847 只让 UTC、单值、顶层、整型 epoch-millis 字段的 `DATE_HISTOGRAM` 直接按字段分组（ES 拒绝数值字段上的任何 `time_zone`）；其他时区、`DATE_PART`、`DATE_DIFF` 与表达式（分组、指标、指标条件）仍每文档跑 Painless，限制脚本或 `allow_expensive_queries=false` 的集群会拒绝，ES 指南已写明。建议的 `date` 映射没做。判据：策划的 mapping 与存量模板把 Wow 的时间字段映射为 `date`（`format: epoch_millis`，`_source` 不变，不是存储格式变化）；映射是 `date` 时时间分组与日期部件直接用字段并带 `time_zone`；旧索引保留脚本路径；TCK 两条路径都过。落点：`wow-elasticsearch` 的 `ElasticsearchAggregationSources.kt`、`ElasticsearchAggregationScripts.kt`、索引模板，文档站 `guide/extensions/elasticsearch.md`。
- **后端之间的过滤语义差异**（R2-93）：`IS_EMPTY` 对 null 与 `[]`、`EXPRESSION` 的 double 精度、前导通配、无字段的 `SEARCH`，ES 与 MongoDB 不一致，目前只在 TCK 里记下并跳过（`ElasticsearchSnapshotQueryBackendTest.kt`）。推迟：控制台的系统视图一个都不用。判据：后端差异文档逐条写明；描述符为每一项加能力标志，准入据此拒绝而不是给出不同结果。落点：`wow-elasticsearch` 的过滤编译、`wow-query` 的描述符、文档站 ES 指南。
- **Wow 框架：路径里的 owner／tenant 为空白时不得回退到请求头**（用户 2026-09-30：9.2.0 发版后处理）：`wow-webflux` 的 `getOwnerId()`／`getTenantId()`（`route/command/AggregateRequest.kt`）把空白的路径值（如 `%20`、`%09`、`%E3%80%80`）当作没给，改读 `Command-Owner-Id`／`Command-Tenant-Id` 请求头，两者都没有时 owner 为空、跳过 owner 检查；凡路由带 `{ownerId}`／`{tenantId}` 的聚合都如此。view-store 已在自己的过滤器里拒绝空白与不可见的范围值并剥掉这两个头（#3795），框架本身未改。判据：路由声明了 owner／tenant 路径变量时，空白值答 400、不再读请求头；请求头只用于路径里没有该变量的路由；发版说明写明这一 REST 行为变化（与 `spaced` 同属契约修正）。落点：`wow-webflux` 的命令与查询路由取值、对应测试。
- **Wow 框架：`spaced`／`owner` 改在聚合层声明**（用户 2026-10-01：9.2.0 发版后处理，与上一条同批）：#3791 起 `spaced` 是聚合的核心元数据，`owner` 也被核心的 owner 检查使用，两者却仍写在路由注解 `@AggregateRoute(spaced, owner)` 上。改为在聚合层声明（与静态租户同处，如 `@BoundedContext.Aggregate(spaced = …, owner = …)` 或一个聚合注解），`@AggregateRoute` 上的两个属性标为弃用、照旧读取。判据：两处都声明时以聚合层为准、不一致时启动报错；只写旧处的聚合行为不变（混跑 9.1 与 9.2 一致）；元数据与文档改为聚合层的写法。落点：`wow-api` 注解、`wow-compiler` 元数据解析、`wow-core` 聚合元数据、文档站。
