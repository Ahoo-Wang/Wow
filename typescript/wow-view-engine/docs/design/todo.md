# TODO

规则，先读再加：

- 只记**已经决定、但还没做**的事。尚无结论的产品问题不在这里，在 [decisions.md#搁置待议](decisions.md#搁置待议)。
- 每条都要写清 **为什么** / **做完的判据** / **落点**（design 里的哪一页，或哪个文件）。三者缺一就不是一条 TODO，是一句抱怨。
- 做完就**删掉**这一条，不打勾、不留归档。历史在 git 里。
- 改行为之前先看这里有没有对应项；有就接着做，别另起一条。

## 首发前

npm 首发（`9.2.0-rc.0`）之前按下面的顺序做，同时只做一件。审查里「现在做还是以后做」默认现在做：公开面（[D29](decisions.md) 快照）上的破坏性改动趁首发前一次改到位，不为发布前的形态留兼容层（用户 2026-09-24）。

### 1. 第二轮全面审查，由用户明确通过

- **审查过门**（用户 2026-09-24）：架构质量（职责清晰、高内聚、低耦合、扩展性、可维护性）、企业级产品体验（UI 视觉、UX 交互）、功能的可用性、可访问性与易用性。
  - 为什么：这是一次重大发布；公开面一旦上 npm 就要背兼容性。
  - 判据：下面的已知项逐条修掉，或由用户拍板推迟并写进 [decisions.md](decisions.md)；审查报告交给用户并得到**明确通过**；此前不发布。首发时 `docs/compat-debt.md` 里没有本包因发布前形态欠下的条目。
  - 落点：审查报告；Wow 仓的 `typescript/RELEASING.md`「首发清单」。

下面是这一轮要裁定的已知项，默认现在修。

#### 可访问性

走查记录与逐条符合性表在文档站「视图引擎的可访问性」（`documentation/docs/{zh,en}/guide/typescript/view-engine-accessibility.md`）。真人读屏走查已推迟到首发后（[D73](decisions.md#d73-真人读屏走查与多浏览器视觉回归放到首发之后2026-09-29)）：首发前由用户用 VoiceOver 抽查记录工作台与分析编辑区的撤销提示，声明写明读屏软件实测待做。

## 首发后再议

用户已定推迟到首发之后；排进某个版本时各自成为带判据的条目。

- **真人读屏走查（VoiceOver／NVDA）**（[D73](decisions.md#d73-真人读屏走查与多浏览器视觉回归放到首发之后2026-09-29)）：VoiceOver + Safari、NVDA + Firefox／Chrome 各走一遍记录工作台、分析、仪表盘与两种嵌入的核心任务，照 [screen-reader-walkthrough.md](screen-reader-walkthrough.md) 交回结果。判据：每个任务一行「能否完成 / 实际念出的话 / 与预期的差异」，差异修掉或各成一条；声明的「评估方法」补上读屏软件与版本。落点：文档站「视图引擎的可访问性」。
- **Firefox／WebKit 的视觉回归基线与 PR 任务**（D73）：首发前已在本机走查过一次（2026-09-30，没有视觉回归，查出的缺陷已修）。首发后两种引擎各立一套截图基线，PR 上加 Firefox／WebKit 的交互测试任务。判据：两种引擎各有一套基线、PR 上各有一个任务，差异修掉或记下原因。落点：Storybook 的截图脚本、`.github/workflows/typescript-storybook.yml`。
- **H：补偿控制台「每天各种结局」画成一张多序列图，由后端支持**（用户 2026-09-27）：Wow 在展开元素的聚合里够不到根字段 `createTime`；后端加「元素作用域里按上层（根）字段分组」（MongoDB 几乎不用改，Elasticsearch 要把根字段的分组放到 `nested` 外并与 composite 分页相容），TCK 两个后端都加用例，之后 TS 镜像、本包放开 `analysis.field.outside-scope`。判据：一次查询出「按天 × 事件名」，两个后端的 TCK 都过；控制台「补偿活动」板的结局是一张四条线的图。落点：`wow-query` 的 `QueryResolver`、`wow-mongo`／`wow-elasticsearch` 的聚合编译、本包 `analysis/`、控制台 `src/views/overview.ts`。
- **E：看板布局不用手算坐标**：面板位置是手写的 `x/y/w/h`，插一个面板要重算后面的 `y`。加按行排布的辅助，或 `y` 省略时自动接在上一行后。判据：控制台的三块板改用它后布局不变。落点：`src/dashboard/layout.ts`。
- **G：枚举字段宽容 `EQ`**：字段从字符串改成枚举时，已存的 `EQ` 条件全部失效。写的时候对枚举上的 `EQ` 按一个值的 `IN` 读。判据：枚举字段上存下的 `EQ` 条件照常准入与编译。落点：`src/filter/kinds/enum.ts`。
- **与上一期整段、去年同期对比（Q59）与注释**：要第二条查询与运行时路径；注释要随视图存下（视图存储已有，要在配置里加成员）。D71 里「对比上一期一步生成两个指标」一并在此。落点：`src/analysis/`、运行时（第二条查询）、[ui/analysis.md](ui/analysis.md)。
- **Storybook 审查（2026-09-26）的 P2**：P0、P1 已全部处置。落点：`typescript/storybook/docs/review-2026-09-26.md`。
- **搜索在真服务端上命中**：示例服务端的快照在 MongoDB 上，没有全文能力，端到端只验证了 `SEARCH` 被拒且如实报出。判据：契约作业有了 Elasticsearch 快照后，搜索用例改为断言命中。落点：Wow 仓端到端的 `view-engine/recordView` 用例（`typescript/integration-test/`）。
- **Wow 框架：路径里的 owner／tenant 为空白时不得回退到请求头**（用户 2026-09-30：9.2.0 发版后处理）：`wow-webflux` 的 `getOwnerId()`／`getTenantId()`（`route/command/AggregateRequest.kt`）把空白的路径值（如 `%20`、`%09`、`%E3%80%80`）当作没给，改读 `Command-Owner-Id`／`Command-Tenant-Id` 请求头，两者都没有时 owner 为空、跳过 owner 检查；凡路由带 `{ownerId}`／`{tenantId}` 的聚合都如此。view-store 已在自己的过滤器里拒绝空白与不可见的范围值并剥掉这两个头（#3795），框架本身未改。判据：路由声明了 owner／tenant 路径变量时，空白值答 400、不再读请求头；请求头只用于路径里没有该变量的路由；发版说明写明这一 REST 行为变化（与 `spaced` 同属契约修正）。落点：`wow-webflux` 的命令与查询路由取值、对应测试。
- **Wow 框架：`spaced`／`owner` 改在聚合层声明**（用户 2026-10-01：9.2.0 发版后处理，与上一条同批）：#3791 起 `spaced` 是聚合的核心元数据，`owner` 也被核心的 owner 检查使用，两者却仍写在路由注解 `@AggregateRoute(spaced, owner)` 上。改为在聚合层声明（与静态租户同处，如 `@BoundedContext.Aggregate(spaced = …, owner = …)` 或一个聚合注解），`@AggregateRoute` 上的两个属性标为弃用、照旧读取。判据：两处都声明时以聚合层为准、不一致时启动报错；只写旧处的聚合行为不变（混跑 9.1 与 9.2 一致）；元数据与文档改为聚合层的写法。落点：`wow-api` 注解、`wow-compiler` 元数据解析、`wow-core` 聚合元数据、文档站。
- **阶段 7：文档站**（用户 2026-09-29：首发后再维护）：视图引擎在文档站（`documentation/docs/{zh,en}`）只有「视图引擎」指南与可访问性声明两页。补齐面向使用者的一套：入门（从零接入一个业务对象）、概念（定义、视图、看板、系统视图与用户视图、存储）、指南（宿主接入、声明式操作、Wow 存储后端与 CoSec 路径规则、主题、CSP、可访问性）、API 参考，中英两版。
