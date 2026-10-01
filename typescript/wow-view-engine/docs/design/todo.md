# TODO

规则，先读再加：

- 只记**已经决定、但还没做**的事。尚无结论的产品问题不在这里，在 [decisions.md#搁置待议](decisions.md#搁置待议)。
- 每条都要写清 **为什么** / **做完的判据** / **落点**（design 里的哪一页，或哪个文件）。三者缺一就不是一条 TODO，是一句抱怨。
- 做完就**删掉**这一条，不打勾、不留归档。历史在 git 里。
- 改行为之前先看这里有没有对应项；有就接着做，别另起一条。

## 首发前

npm 首发（`9.2.0-rc.0`）之前按下面的顺序做，同时只做一件。审查里「现在做还是以后做」默认现在做：公开面（[D29](decisions.md) 快照）上的破坏性改动趁首发前一次改到位，不为发布前的形态留兼容层（用户 2026-09-24）。

### 1. Wow 存储后端（阶段 6）

- **用真服务端验证 `ViewStore`**（[D75](decisions.md#d75-首发前做-wow-存储后端用真服务端验证-viewstore2026-09-29)）：框架前置修复（没有开启 `spaced` 不写入 spaceId）、视图与偏好的 Wow 聚合、就地改受众、随后端发布的 TS 客户端。
  - 为什么：端口随首发公开；今天的两种实现都是单用户，端口没被多用户、鉴权与真实冲突验过。
  - 判据：端口一致性测试在内存实现与 Wow 实现上都全绿；`integration-test` 连示例服务端的端到端（个人与共享、租户与应用隔离、冲突、重试去重）在 CI 里过；补偿控制台换成 Wow 存储后的端到端照常通过。
  - 落点：[view-store-backend.md](view-store-backend.md) 第 8 节（V0～V3）。

### 2. 两个 skill（存储后端之后）

- **视图定义与宿主接入两个 skill**（用户 2026-09-29）：在 Wow 存储后端合并之后写（用户 2026-09-29：「Wow 存储后端 -> skills」）——那时引擎的公开面（含 `ViewStore` 的改受众）已定；第二轮审查若再改公开面，skill 随之修订。
  - 为什么：skill 教的是最终的公开面；引擎还在改时写，写完就过时。
  - 判据：按 [host-integration.md](host-integration.md) 第 6 节——改写 `wow-view-definition`（只讲判断，自检就是 `admit`），新增 `wow-view-host`（资源、**ViewHost**、`bind`、路由与「从命令到操作」）；智能体按 skill 从零给零售场景写一份定义与操作，一次通过 `admit`。
  - 落点：仓库 `skills/`；[host-integration.md](host-integration.md) 第 6 节。

### 3. 第二轮全面审查，由用户明确通过

- **审查过门**（用户 2026-09-24）：架构质量（职责清晰、高内聚、低耦合、扩展性、可维护性）、企业级产品体验（UI 视觉、UX 交互）、功能的可用性、可访问性与易用性。
  - 为什么：这是一次重大发布；公开面一旦上 npm 就要背兼容性。
  - 判据：下面的已知项逐条修掉，或由用户拍板推迟并写进 [decisions.md](decisions.md)；审查报告交给用户并得到**明确通过**；此前不发布、不开阶段 6（Wow 存储后端）。首发时 `docs/compat-debt.md` 里没有本包因发布前形态欠下的条目。
  - 落点：审查报告；Wow 仓的 `typescript/RELEASING.md`「首发清单」。

下面是这一轮要裁定的已知项，默认现在修。

#### 可访问性

走查记录与逐条符合性表在文档站「视图引擎的可访问性」（`documentation/docs/{zh,en}/guide/typescript/view-engine-accessibility.md`）。真人读屏走查已推迟到首发后（[D73](decisions.md#d73-真人读屏走查与多浏览器视觉回归放到首发之后2026-09-29)）：首发前由用户用 VoiceOver 抽查记录工作台与分析编辑区的撤销提示，声明写明读屏软件实测待做。

#### 界面与图表

- **查询失败时仍显示上一次的结果**：条件改了而新查询失败，表格留着旧条件的行。
  - 为什么：刷新失败时保留旧行是对的；条件变了时，要让人看出眼前是旧条件的结果。
  - 判据：逐个筛选模式在真浏览器里改条件后让查询失败，界面说清「显示的是改之前的结果」或不再显示旧行。
  - 落点：`src/react/useRecordTable.ts`、`src/ui/record/`。
- **对话框与弹层的函数式 `finalFocus`**：#3547 修了下拉菜单关闭时抢回已移走的焦点（Base UI 1.8 在 `finalFocus` 是函数时不看焦点是否已离开）；对话框、弹层也传函数时可能是同一个竞态。
  - 判据：逐个查过，同样处理或说明不受影响，并有一个回归故事。
  - 落点：`src/ui/kit/popups.tsx`、`src/ui/kit/ExportDialog.tsx`、`src/ui/record/RecordDetail.tsx`。
- **图上的字还有两处压盖**：长柱上「最低 …」比柱子宽时压到两旁柱身；多段堆叠柱多时栈顶合计仍每根都写。
  - 判据：各自定下做法并落地（如也只标峰谷），或写进 decisions 不做。
  - 落点：`src/ui/charts/cartesianMarks.ts`、`src/analysis/chartFamilies.ts`。
- **直角坐标图的拆分系列读选项的 `tone`**：饼已按语气取色（[ui/analysis.md](ui/analysis.md)「饼的类别穿选项的语气」），按带语气的枚举拆开的柱与线仍按次序取色位。
  - 为什么：同一个状态在饼上是红的、在柱上是某个色位，读者要重新对一遍图例。
  - 判据：按带 `tone` 的枚举拆分时系列颜色与徽标同语气，同语气的两条取色位。
  - 落点：`src/ui/charts/cartesianPlan.ts`、`src/ui/charts/timeOption.ts`（`toneColor`）。
- **分析视图的时间轴只补首尾之间的洞**：走势卡已补到自己的窗口（`cardWindow`，D39），柱、线、面积与热力图仍只补回来的首尾两桶之间——「近 30 天」前五天没有记录时，轴从第六天开始。
  - 判据：条件在时间轴字段上钉住窗口、且结果完整（`absenceReader` 能担保）时，这几种图补到窗口两端，可加的指标补 0 并标 `filled`；拆分与热力图按组合补。
  - 落点：`src/analysis/cartesian.ts`、`src/analysis/chart.ts`，[kernels.md](kernels.md)。
- **场景描述里的手写数字**：如运营日报的「约 82%」「11 张」，数据一变就说错。
  - 判据：照 `retail/guide.ts` 的做法从数据读出，或删掉数字。
  - 落点：`typescript/storybook/stories/view-engine/` 各场景的 docs 描述。
- **只读的板不挂拖动的触摸监听**：react-grid-layout 在拖动与缩放都关着时仍给每块面板挂两个非 passive 的 `touchstart`，触屏上从面板开始的滚动要等主线程。
  - 判据：读板时面板上没有 `touchstart`／`touchmove` 监听，摆放逐像素不变，进出搭建不重挂面板；截图基线不变。
  - 落点：`src/ui/dashboard/DashboardGrid.tsx`、[ui/dashboard.md](ui/dashboard.md)。
- **网格第一帧比容器宽**：改变窗口尺寸或从板子「返回」重新挂载时，网格短暂比容器宽、随即恢复。
  - 判据：复现并修掉，或确认复现不了后删掉这一条。
  - 落点：`src/ui/dashboard/DashboardGrid.tsx`。

#### 主题机制

- **主题机制的余项**（[ui/theme.md](ui/theme.md) 的角色与链接，[D43](decisions.md#d43-主题可以说面怎样分层控件怎样画2026-09-25)）：
  - 描边按钮也能填色（先让按钮在元素上说出 variant）；
  - porcelain 暗色的菜单高亮链了主色后是浅字配深底——面上缺「暗色下更深一档的品牌色」，要两全得加一个角色或派生；
  - 看板筛选芯片里打字的框必有 `input` 边：要无边先论证填色本身能当边界，再给配方一个角色；
  - 引擎的三个焦点配方（`FOCUS_ROW`、`FOCUS_CARD`、`FOCUS_INSET`）不读 `focus-width`／`focus-offset`，contrast 下是 1px；选中行没有颜色以外的标记角色；contrast 的菜单高亮不跟品牌色（要跟时加 `highlight-link`）；
  - 角色截图「提示框」（`ThemeRoles.test.stories.tsx` 的 `TooltipChip`）截到了故事外壳顶栏的提示框，应改截面内的触发器。
  - 判据：每条落地或写进 decisions 不做；每套 × 每种明暗过对比度矩阵与色板门，neutral 在默认密度、默认约定下像素不变。
  - 落点：[ui/theme.md](ui/theme.md)、`src/themes/`、`src/ui/theme/`。

## 首发后再议

用户已定推迟到首发之后；排进某个版本时各自成为带判据的条目。

- **真人读屏走查（VoiceOver／NVDA）**（[D73](decisions.md#d73-真人读屏走查与多浏览器视觉回归放到首发之后2026-09-29)）：VoiceOver + Safari、NVDA + Firefox／Chrome 各走一遍记录工作台、分析、仪表盘与两种嵌入的核心任务，照 [screen-reader-walkthrough.md](screen-reader-walkthrough.md) 交回结果。判据：每个任务一行「能否完成 / 实际念出的话 / 与预期的差异」，差异修掉或各成一条；声明的「评估方法」补上读屏软件与版本。落点：文档站「视图引擎的可访问性」。
- **Firefox／WebKit 的视觉回归基线**（D73）：视觉回归在 Firefox 与 WebKit 上各跑一次（要下载这两种浏览器）。判据：两种引擎各有一套基线，差异修掉或记下原因。落点：Storybook 的截图脚本。
- **H：补偿控制台「每天各种结局」画成一张多序列图，由后端支持**（用户 2026-09-27）：Wow 在展开元素的聚合里够不到根字段 `createTime`；后端加「元素作用域里按上层（根）字段分组」（MongoDB 几乎不用改，Elasticsearch 要把根字段的分组放到 `nested` 外并与 composite 分页相容），TCK 两个后端都加用例，之后 TS 镜像、本包放开 `analysis.field.outside-scope`。判据：一次查询出「按天 × 事件名」，两个后端的 TCK 都过；控制台「补偿活动」板的结局是一张四条线的图。落点：`wow-query` 的 `QueryResolver`、`wow-mongo`／`wow-elasticsearch` 的聚合编译、本包 `analysis/`、控制台 `src/views/overview.ts`。
- **E：看板布局不用手算坐标**：面板位置是手写的 `x/y/w/h`，插一个面板要重算后面的 `y`。加按行排布的辅助，或 `y` 省略时自动接在上一行后。判据：控制台的三块板改用它后布局不变。落点：`src/dashboard/layout.ts`。
- **G：枚举字段宽容 `EQ`**：字段从字符串改成枚举时，已存的 `EQ` 条件全部失效。写的时候对枚举上的 `EQ` 按一个值的 `IN` 读。判据：枚举字段上存下的 `EQ` 条件照常准入与编译。落点：`src/filter/kinds/enum.ts`。
- **与上一期整段、去年同期对比（Q59）与注释**：要第二条查询与运行时路径；注释要存储，随阶段 6。D71 里「对比上一期一步生成两个指标」一并在此。落点：`src/analysis/`、运行时（第二条查询）、[ui/analysis.md](ui/analysis.md)。
- **Storybook 审查（2026-09-26）的 P2**：P0、P1 已全部处置。落点：`typescript/storybook/docs/review-2026-09-26.md`。
- **搜索在真服务端上命中**：示例服务端的快照在 MongoDB 上，没有全文能力，端到端只验证了 `SEARCH` 被拒且如实报出。判据：契约作业有了 Elasticsearch 快照后，搜索用例改为断言命中。落点：Wow 仓端到端的 `view-engine/recordView` 用例（`typescript/integration-test/`）。
- **Wow 框架：路径里的 owner／tenant 为空白时不得回退到请求头**（用户 2026-09-30：9.2.0 发版后处理）：`wow-webflux` 的 `getOwnerId()`／`getTenantId()`（`route/command/AggregateRequest.kt`）把空白的路径值（如 `%20`、`%09`、`%E3%80%80`）当作没给，改读 `Command-Owner-Id`／`Command-Tenant-Id` 请求头，两者都没有时 owner 为空、跳过 owner 检查；凡路由带 `{ownerId}`／`{tenantId}` 的聚合都如此。view-store 已在自己的过滤器里拒绝空白与不可见的范围值并剥掉这两个头（#3795），框架本身未改。判据：路由声明了 owner／tenant 路径变量时，空白值答 400、不再读请求头；请求头只用于路径里没有该变量的路由；发版说明写明这一 REST 行为变化（与 `spaced` 同属契约修正）。落点：`wow-webflux` 的命令与查询路由取值、对应测试。
- **阶段 7：文档站**（用户 2026-09-29：首发后再维护）：视图引擎在文档站（`documentation/docs/{zh,en}`）只有「视图引擎」指南与可访问性声明两页。补齐面向使用者的一套：入门（从零接入一个业务对象）、概念（定义、视图、看板、系统视图与用户视图、存储）、指南（宿主接入、声明式操作、Wow 存储后端与 CoSec 路径规则、主题、CSP、可访问性）、API 参考，中英两版。

## 线索

尚未排期、也还没有判据；排进某个阶段时各自成为一条带判据的条目，或进 [decisions.md#搁置待议](decisions.md#搁置待议)。

- D22 标了「以后」的：联动筛选、卡片内筛选；按列的点击行为；整板 PDF；订阅、版本历史、验证、缓存要服务端，归阶段 6。
- 准入发现里的字段用 `field.name`（「给 status 一个值」）而不是显示名——整个包的惯例，要改是包级的决定。
- 「更多图型」折叠宿主扩展的图型：今天没有宿主扩展图型的入口，等有了再做。
- 透视表（Q8）；精确的 M（Q7）；分析表冻结列；STDDEV／VARIANCE 与去重计数在 ES 上的近似提示按后端能力声明。
