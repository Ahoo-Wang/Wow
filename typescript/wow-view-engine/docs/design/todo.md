# TODO

规则，先读再加：

- 只记**已经决定、但还没做**的事。尚无结论的产品问题不在这里，在 [decisions.md#搁置待议](decisions.md#搁置待议)。
- 每条都要写清 **为什么** / **做完的判据** / **落点**（design 里的哪一页，或哪个文件）。三者缺一就不是一条 TODO，是一句抱怨。
- 做完就**删掉**这一条，不打勾、不留归档。历史在 git 里。
- 改行为之前先看这里有没有对应项；有就接着做，别另起一条。

## 分析视图：审查剩余（2026-09-23 迁移会话交接；顺序在仪表盘批 A 之后）

ECharts 迁移（D21）与两份「数据分析师视角」审查的 P0 已全部合并：#1800、#1817～#1829；交接的 P1 也已合并：#1834（指标卡最后一期＋环比、引擎时区）、#1835（组合图右轴、缺值、百分比堆叠）、#1836（托盘与分析表 13 条）、#1837（记录视图表头排序等应用）、#1838（表格永远跑得起来、图表提示说列标题）。下面是已定、未做的，按批次排；动手前先在最新 main 上复现，已顺带修掉的删掉。审查原文的要点都在这里，原报告不在仓库里。

- **P2：数值区间的一段，已应用条与菜单两种说法**（2026-09-23 审查 P2 的余项；日期桶那一半已做，见 [ui/analysis.md](ui/analysis.md) 的追问一节）：一段的条件是同一字段的 `GTE` 与 `LT`，已应用条上是两个 chip，菜单与从它开出去的视图的名字却说「单价 在 ¥0～500」。
  - 判据：从一段开出去的视图，「正在显示」与标题说同一句；有测试。
  - 落点：`src/filter/describe.ts`（同一字段的 `GTE`＋`LT` 合读成一段，像 `period` 那样交出）、`src/ui/summary.ts`。

## 阶段 3：仪表盘（设计已定为 [D22](decisions.md#d22-仪表盘与嵌入视图参照-metabase2026-09-23)，交互见 [ui/dashboard.md](ui/dashboard.md) 的定稿一节）

**顺序**（用户 2026-09-23 定）：批 A（缺陷）先收完 → 上面的「分析视图：审查剩余」→ 批 B→C→D → 阶段 4。批 A 已收完（#1831 运行时一路；界面一路：R3、空态、不可用面板的原因与出路、手柄命名、文案、窄于 md 单列），见 [ui/dashboard.md](ui/dashboard.md)。

批次按顺序；每批都做到可生产交付、真浏览器走过。起点的缺陷清单在会话记忆 `view-engine-dashboard-walk-2026-09-22`（U1～U11、R1～R18、G1～G10）。

- **批 B 怎么搭**（交互稿 A～E 屏）：**已做完**——B1 模型、校验、迁移与运行时（[model.md#dashboard-配置](model.md#dashboard-配置)、[runtime.md#dashboard](runtime.md#dashboard)）；B2 编辑模式、编辑条、添加、面板菜单、空板子的第一步、不可用面板的真按钮、面板图表提示说列标题；B3 在仪表盘里新建分析、「另存为视图…」、展示覆盖（「改这里的展示…」、面板头「此处改为〈图型〉」、「恢复为视图的样子」）、标签栏（只跑当前标签页、加／改名／排序／删除带确认、记住每人上次的标签、当前标签经 `onTabChange`／`initialTab` 交给宿主进地址），以及 B1 留下的两处运行时收口（只改画法的展示覆盖只重画不重跑；只跑当前标签页）。一份扩展接口 `DashboardEditExtensions`，见 [ui/dashboard.md](ui/dashboard.md)「搭板子」「扩展」。B2 留下的最后几件也已做完：面板菜单的「导出数据…」与「复制为共享视图并替换…」（#1872），窄屏调顺序与编辑中的撤销／重做（#1873）；整批的真浏览器走查并入阶段 3＋4 的联合审查。
  - 判据：从空仪表盘开始只用界面就能搭出首页那块运营看板；真浏览器逐控件走查。
  - 落点：`src/ui/dashboard/`、`src/ui/DashboardWorkbench.tsx`、[ui/dashboard.md](ui/dashboard.md)。
- **批 C 全局筛选**：已做完（C1 #1843 模型与运行时，C2 界面），见 [ui/dashboard.md](ui/dashboard.md)「筛选」。
- **批 C 之前的整板条件——固定范围的界面**（[D23](decisions.md#d23-搁置待议的六条拍板2026-09-23) Q16）：迁成默认值的那一半已做（`migrateDashboardConfig` 的 `intoDefaults`，[model.md#dashboard-配置](model.md#dashboard-配置)）。拆不开的存成独立成员 `fixed`（D26 Q31），今天在「正在显示」条上只读、注明是仪表盘的固定范围，读者拿不掉；但不在筛选条旁，也删不掉——要在筛选条旁注明为只读的「固定范围」，编辑模式里可以整体删掉。筛选条旁只读的那一枚随 [D27](decisions.md#d27-仪表盘不画正在显示条2026-09-24) 在 R7 做，编辑模式里整体删掉仍到 Wow 做。等阶段 4（筛选三态动 `FilterBar`）合并后做。
  - 判据：拆不开的那棵条件在筛选条旁读得到、编辑中删得掉；有测试与故事。
  - 落点：`src/ui/dashboard/FilterBar.tsx`、[ui/dashboard.md](ui/dashboard.md)「筛选」。

## 阶段 3＋4 联合审查的处置（2026-09-24）

四路只读审查（架构 A-、代码质量 Q-、UI 与可达性 U-、UX 与文档 X-）加主会话的视觉走查（V-）共 69 条，原报告在主会话 scratchpad `review-p34/`（不在仓库里，要点记在这里）。要拍板的 11 条已定为 [D26](decisions.md#d26-阶段-34-联合审查的十一条拍板2026-09-24)（Q30～Q40，2026-09-24 按推荐），Q31、Q35、Q39 已做完（R1b），R6 已做完，其余落在下面的 R7；R2～R5 不需要产品判断。批次按文件分开、可并行。每批：先在最新 main 上复现，修掉并补测试，文档按现状改，本地门禁全绿即合并，最后一批等完整 CI。

- **R7 界面的拍板**（R2 合并后）：Q34 仪表盘工作台说「仪表盘」；Q37「完成」改「保存」；Q38 手机上筛选条收成按钮与底部 `Sheet`；[D27](decisions.md#d27-仪表盘不画正在显示条2026-09-24) 仪表盘不画「正在显示」条、固定范围挪到筛选条那一行（只读）、模型去掉仪表盘的 `filter`、GlobalFilter 故事演示筛选条上的值。可与 R5 措辞一起做。
  - 落点：`src/ui/`、`src/ui/messages/`、[ui/dashboard.md](ui/dashboard.md)、[ui/record.md](ui/record.md)。
- **追问菜单与图表提示框叠在一起**（2026-09-24 R6 走查发现，main 上可复现：仪表盘「点击」故事点一根柱）：点柱后追问菜单打开，ECharts 的提示框（「华北 / 金额的总和 ¥2,450.00」）仍留在原处，压在菜单项上。
  - 判据：菜单打开时提示框收起（或菜单盖在提示框之上且不透出），分析工作台与仪表盘面板一致；有故事断言。
  - 落点：`src/ui/analysis/DrillMenu.tsx` 与图表组件（随 R7 做）。
- **R3 运行时与界面去重（界面一半）**：运行时一半已在 R3a 做完——`boardFindings` 一种读法、控制器的 `issues` 就是它；非搭建态拒绝编辑命令；`edit` 碰不到历史管的成员；跨部件规则收进 `BoardRules`，`dashboardRuntime.ts` 降到约 450 行；`panelsOf`／`tabsOf`／`presentationMembersOf` 进内核，`overlaid` 进 `model/json.ts`；`PanelFields` 收窄到 `DataPanelSource`、`RuntimeFor` 对联合给出 `AnyViewRuntime`，去掉了四处强转。剩下的都要动 `src/ui/` 或它的测试：
  - Q-01／A-07 接上：`DashboardWorkbench` 的 `carried`／`sameIssue` 与 `EmbeddedDashboard` 自己拆 `state.issues` 的那段都改读 `dashboard.issues`（按 severity 分 error 与 warning）；`namePanel`（栅格上方给面板发现起名）两处共用；可编辑档嵌入补一条测试：草稿里只在 `['panels', 0, …]` 的 warning 要显示。
  - Q-02 界面：`Board.tsx` 的 preload 续体里再读一次当前的 `dashboard.edit`，补一条界面测试（preload 挂起、先按取消再放行，板上不多面板）；`place` 也纳入非搭建态拒绝——先让 test/dashboardUi.test.tsx 的「places a panel and applies the placement」「placed by keyboard」「writes the geometry back once a drag ends」与 test/dashboardPlacement.test.tsx 的「placing a panel」在摆放前开始搭建，再删掉 `runtime/dashboard/editing.ts` 里 `draftFor` 对 `place` 的例外。
  - A-11（审查原文是 `PanelPresses` 自己判断点击生不生效、不读面板状态的 `click`）：口径不变——准入报过 warning 的点击按下去回到追问菜单并说为什么（[model.md](model.md) 的「点击」、D22 H）；改的是做法：面板状态同时带点击与它的准入结论，`PanelPresses` 只读状态、不再自己判（2026-09-24 主会话定，技术取舍、不涉产品口径）。test/dashboardPress.test.ts「warns of a filter this board no longer has, and a press falls back to the menu」照旧成立。
  - A-14／Q-06：工作台与嵌入共用一份搭建外壳（编辑按钮、完成／取消后焦点回「编辑」、离开守卫、`onTabChange`／`onFiltersChange` 两个上报）。
  - Q-05 界面：`DashboardPanel`（圈复杂度 50）标题行的六种标记抽成一个组件；`ClickForm`（49）的九个状态收拢、`wanted()` 下沉为内核纯函数；`panelCommands` 按「看」「改」拆开；`DashboardTabs.tsx`、`ExportDialog.tsx` 贴近 500 行。
  - Q-07 界面：`ui/dashboard/commands.ts` 的 `hasOwnLook`、`PresentationDialog.tsx` 的 `hasLook`、`PanelBodies.tsx` 的 `presentationMark` 改用内核的 `presentationMembersOf`。
  - Q-08 界面：`filterModes.ts` 与 `PresentationDialog.tsx` 两份语义不同的 `sameValue` 收成一份。
  - Q-09 可视化面板的两层与焦点跟随在 `PresentationDialog` 与 `AnalysisParts` 各写一份，`ignore`／`NO_ROWS` 也各一份；Q-10 三份原地改名输入框（面板菜单、标签栏、视图管理行）行为各异，收成一个；Q-11 六份拖拽可达性插件样板收成一个共用的插件工厂。
  - Q-12 界面：`ui/dashboard/history.ts` 的 `usable` 改成类型守卫（去掉 `node!`），`PresentationDialog.tsx` 的 `as unknown as Record<string, unknown>`。
  - Q-13：`useExportOffer` 自己取 `messages`／`display` 并交回 `columns`／`max`，`PanelExport`、`EmbeddedRecord`、`RecordParts` 只传 `runtime, table, filter, title`。
  - A-15：`ui/index.ts` 开头「只读控制器」的承诺改成「纯内核读法可以直接用，有状态的判断走控制器」，或把有状态的判断上移到 `/react`。
  - AGENTS.md 结构树的描述按 R3a 改：`json.ts`（`overlaid`）、内核 `panels.ts`（`panelsOf`、`tabsOf`、`presentationMembersOf`）、`wiring.ts`（`DataPanelSource`）、runtime 的 `commands.ts`（`BoardRules` 与只转一手的 `BoardCommands`）、`history.ts`（`outsideHistory`）、`panelRun.ts`（`boardPanels`、`boardHandOver`）、`panels.ts`（`boardFindings`）。
  - 判据：行为不变的重构由现有测试守住，新缺陷各有测试；`max-lines` 豁免表仍为空。落点：`src/ui/`、`AGENTS.md`。
- **R5 措辞与文档**：X-03 仪表盘筛选发现说程序键（`filter-1`）；X-08 一词多义（「筛选 ▾」→「添加筛选」、「分节标题」「笔记」统一、「板／仪表盘」）；X-09 两个「撤销」；X-06 本页与 [ui/dashboard.md](ui/dashboard.md) 的过期项与顺序行；X-07 README 入口表与迁移段落；X-11 D22 的「固定宽度／全宽」等补进 todo；X-12、X-13、X-16、Q-16、A-19 文档漂移与守护缺口；A-16 公开面快照（首发前必做，可在 Wow 做）。
  - 落点：`docs/design/`、`README*.md`、`AGENTS.md`、`test/docsReferences.test.ts`。

## 检查点：迁往 Wow 仓（阶段 3、4 收口之后）

- **到这里先停。** 阶段 4（嵌入视图）已经在这里合并（#1863，它在这个检查点写下之前就做完了），用户 2026-09-23 把停点挪到阶段 4 之后：上面批 B、批 C 剩下的几项合并，再把**阶段 3 与阶段 4 一起**审查、审查后的重构合并——这时不要再开始新的阶段或新的工作，先告诉用户：下一步是迁移窗口。阶段 5 起在 Wow 仓的 `typescript/wow-view-engine` 里做；上面的「固定范围的界面」（Q16）也到 Wow 里做。
  - 为什么：迁移方案定的时机是阶段边界。这时 PR 链是空的，冻结不会打断任何在做的工作；阶段 6 的存储后端也要和引擎放在同一个仓库。
  - 判据：远端一出现 tag `wow-migration-base`（用 `git ls-remote --tags origin wow-migration-base` 查），本包就冻结，这里一律不再改；迁移第 3′ 步把本包从 fetcher 删掉时，这一条随之删除。
  - 落点：[迁移方案](../../../../docs/superpowers/specs/2026-09-23-wow-packages-migration-design.md)，根目录 `AGENTS.md` 的「Migration Checkpoint」一节。

- **分析面板与分析工作台的「导出数据…」**（[D25](decisions.md#d25-阶段-3-收尾的四条细化2026-09-24) Q28）——**到 Wow 仓做（阶段 5 起）**：检查点之后这里不开新工作。
  - 为什么：记录面板能导出、分析面板不能；只在面板上给又会让面板做到分析视图本身做不到的事，所以两处一起加（与 Metabase 每张卡片都能下载结果一致）。
  - 判据：分析工作台的结果工具栏与分析面板的「⋯」都有「导出数据…」；文件是表格读法下的行（分组列在前、指标在后，列标题与格子读法同表格，「前 N 组」之内，合计行显示时作为最后一行，不含图上补出或并出的东西），与此刻画的是表还是图无关；窗口复用 D14 的壳、没有「所有／选中」；有测试与故事。
  - 落点：`src/ui/ExportDialog.tsx`、`src/ui/workbench/AnalysisParts.tsx`、`src/ui/dashboard/PanelExport.tsx`、[ui/analysis.md](ui/analysis.md)、[ui/dashboard.md](ui/dashboard.md) 面板菜单。

## 阶段 2 留下的线索（不做，或待产品口径）

- 准入发现里的字段用的是 `field.name`（「给 status 一个值」）而不是显示名——整个包的惯例，要改是包级的决定。
- 「更多图型」折叠宿主扩展的图型：今天没有宿主扩展图型的入口，等有了再做；散点的坐标轴范围要先进模型（`ScatterSpec` 没有轴规格，所以没有坐标轴页签）。
- 透视表（Q8）；精确的 M（Q7）；分析表冻结列；STDDEV／VARIANCE 与去重计数在 ES 上的近似提示按后端能力声明。
