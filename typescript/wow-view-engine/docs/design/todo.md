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
- **批 C 之前的整板条件——固定范围的界面**（[D23](decisions.md#d23-搁置待议的六条拍板2026-09-23) Q16）：迁成默认值的那一半已做（`migrateDashboardConfig` 的 `intoDefaults`，[model.md#dashboard-配置](model.md#dashboard-配置)）。拆不开的存成独立成员 `fixed`（D26 Q31），在筛选条那一行只读地写作「固定范围」、读者拿不掉（[D27](decisions.md#d27-仪表盘不画正在显示条2026-09-24)，R7b 已做，见 [ui/dashboard.md](ui/dashboard.md)「筛选」）。剩下编辑模式里整体删掉——到 Wow 做。
  - 判据：编辑中那一枚删得掉（删掉即 `fixed` 为空树，随板子保存）；有测试与故事。
  - 落点：`src/ui/dashboard/FilterBar.tsx`、[ui/dashboard.md](ui/dashboard.md)「筛选」。

## 阶段 3＋4 联合审查的处置（2026-09-24）

四路只读审查（架构 A-、代码质量 Q-、UI 与可达性 U-、UX 与文档 X-）加主会话的视觉走查（V-）共 69 条，原报告在主会话 scratchpad `review-p34/`（不在仓库里，要点记在这里）。要拍板的 11 条已定为 [D26](decisions.md#d26-阶段-34-联合审查的十一条拍板2026-09-24)（Q30～Q40，2026-09-24 按推荐），Q31、Q35、Q39 已做完（R1b），R6、R7 已做完；R2～R5 不需要产品判断。批次按文件分开、可并行。每批：先在最新 main 上复现，修掉并补测试，文档按现状改，本地门禁全绿即合并，最后一批等完整 CI。

- **R3b 界面的几处修复**（R3 的界面一半只留用户看得到的；不改行为的重构按 [D28](decisions.md#d28-r3-的界面重构到-wow-做迁移前提放宽2026-09-24) 挪到 Wow，见下面检查点一节）：运行时一半已在 R3a（#1892）做完。R7b 合并后做。
  - Q-01／A-07 接上：`DashboardWorkbench` 的 `carried`／`sameIssue` 与 `EmbeddedDashboard` 自己拆 `state.issues` 的那段都改读 `dashboard.issues`（按 severity 分 error 与 warning）；`namePanel`（栅格上方给面板发现起名）两处共用；可编辑档嵌入补一条测试：草稿里只在 `['panels', 0, …]` 的 warning 要显示。
  - Q-02 界面：`Board.tsx` 的 preload 续体里再读一次当前的 `dashboard.edit`，补一条界面测试（preload 挂起、先按取消再放行，板上不多面板）；`place` 也纳入非搭建态拒绝——先让 test/dashboardUi.test.tsx 的「places a panel and applies the placement」「placed by keyboard」「writes the geometry back once a drag ends」与 test/dashboardPlacement.test.tsx 的「placing a panel」在摆放前开始搭建，再删掉 `runtime/dashboard/editing.ts` 里 `draftFor` 对 `place` 的例外。
  - A-11（审查原文是 `PanelPresses` 自己判断点击生不生效、不读面板状态的 `click`）：口径不变——准入报过 warning 的点击按下去回到追问菜单并说为什么（[model.md](model.md) 的「点击」、D22 H）；改的是做法：面板状态同时带点击与它的准入结论，`PanelPresses` 只读状态、不再自己判（2026-09-24 主会话定，技术取舍、不涉产品口径）。test/dashboardPress.test.ts「warns of a filter this board no longer has, and a press falls back to the menu」照旧成立。
  - 搭板时编辑条一直可见（2026-09-24 R4b 走查发现）：嵌入可编辑档里添加一块面板，卡片内部滚到新面板，编辑条（保存／取消）随之滚出视野，要滚回去才能结束；工作台同理。编辑条在滚动口里吸顶（Metabase 的编辑条也固定在顶上），随共用外壳一起做，故事断言添加后编辑条仍在视野内。
  - 判据：各条有测试或故事断言；`max-lines` 豁免表仍为空。落点：`src/ui/`。
- **R5 措辞与文档**：X-06 本页与 [ui/dashboard.md](ui/dashboard.md) 的过期项与顺序行，连同 X-12 留在那一页的一处（「搭板子」「扩展」末句说宿主自拼 `DashboardBoard`——它不从 `/ui` 导出，宿主能拼的是 `DashboardGrid`）。
  - 落点：`docs/design/`（X-06、X-12）。X-03、X-08、X-09 已在 R7b 做完（见 [ui/README.md](ui/README.md) 的「一词一义」）。

## 检查点：迁往 Wow 仓（阶段 3、4 收口之后）

- **到这里先停。** 阶段 4（嵌入视图）已经在这里合并（#1863，它在这个检查点写下之前就做完了），用户 2026-09-23 把停点挪到阶段 4 之后：上面批 B、批 C 剩下的几项合并，再把**阶段 3 与阶段 4 一起**审查、审查后的重构合并——这时不要再开始新的阶段或新的工作，先告诉用户：下一步是迁移窗口。阶段 5 起在 Wow 仓的 `typescript/wow-view-engine` 里做；上面的「固定范围的界面」（Q16）也到 Wow 里做。
  - 为什么：迁移方案定的时机是阶段边界。这时 PR 链是空的，冻结不会打断任何在做的工作；阶段 6 的存储后端也要和引擎放在同一个仓库。
  - 判据：远端一出现 tag `wow-migration-base`（用 `git ls-remote --tags origin wow-migration-base` 查），本包就冻结，这里一律不再改；迁移第 3′ 步把本包从 fetcher 删掉时，这一条随之删除。
  - 落点：[迁移方案](../../../../docs/superpowers/specs/2026-09-23-wow-packages-migration-design.md)，根目录 `AGENTS.md` 的「Migration Checkpoint」一节。

- **R3 的界面重构——到 Wow 仓做**（[D28](decisions.md#d28-r3-的界面重构到-wow-做迁移前提放宽2026-09-24)）：阶段 3＋4 联合审查里不改行为的界面重构，迁移后在 Wow 做。
  - A-14／Q-06：工作台与嵌入共用一份搭建外壳（编辑按钮、完成／取消后焦点回「编辑」、离开守卫、`onTabChange`／`onFiltersChange` 两个上报）。
  - Q-05 界面：`DashboardPanel`（圈复杂度 50）标题行的六种标记抽成一个组件；`ClickForm`（49）的九个状态收拢、`wanted()` 下沉为内核纯函数；`panelCommands` 按「看」「改」拆开；`DashboardTabs.tsx`、`ExportDialog.tsx` 贴近 500 行。
  - Q-07 界面：`ui/dashboard/commands.ts` 的 `hasOwnLook`、`PresentationDialog.tsx` 的 `hasLook`、`PanelBodies.tsx` 的 `presentationMark` 改用内核的 `presentationMembersOf`。
  - Q-08 界面：`filterModes.ts` 与 `PresentationDialog.tsx` 两份语义不同的 `sameValue` 收成一份。
  - Q-09 可视化面板的两层与焦点跟随在 `PresentationDialog` 与 `AnalysisParts` 各写一份，`ignore`／`NO_ROWS` 也各一份；Q-10 三份原地改名输入框（面板菜单、标签栏、视图管理行）行为各异，收成一个；Q-11 六份拖拽可达性插件样板收成一个共用的插件工厂。
  - Q-12 界面：`ui/dashboard/history.ts` 的 `usable` 改成类型守卫（去掉 `node!`），`PresentationDialog.tsx` 的 `as unknown as Record<string, unknown>`。
  - Q-13：`useExportOffer` 自己取 `messages`／`display` 并交回 `columns`／`max`，`PanelExport`、`EmbeddedRecord`、`RecordParts` 只传 `runtime, table, filter, title`。
  - A-15：`ui/index.ts` 开头「只读控制器」的承诺改成「纯内核读法可以直接用，有状态的判断走控制器」，或把有状态的判断上移到 `/react`。
  - 判据：行为不变的重构由现有测试守住；`max-lines` 豁免表仍为空。落点：`src/ui/`。

- **仪表盘上的报错句子仍说「视图」——到 Wow 做**（D26 Q34 的余项，R7b 走查时发现）：`view.*` 这组按报错代码出字的句子（「视图保存失败」「这个视图已不存在」等）不走种类词，在仪表盘工作台里仍说「视图」；按钮、标题与读屏名已在 R7b 统一。
  - 判据：仪表盘工作台与嵌入仪表盘上的报错与提示句一律说「仪表盘」，有测试扫一遍目录。
  - 落点：`src/ui/messages/`、`src/ui/kinds.ts`。

- **分析面板与分析工作台的「导出数据…」**（[D25](decisions.md#d25-阶段-3-收尾的四条细化2026-09-24) Q28）——**到 Wow 仓做（阶段 5 起）**：检查点之后这里不开新工作。
  - 为什么：记录面板能导出、分析面板不能；只在面板上给又会让面板做到分析视图本身做不到的事，所以两处一起加（与 Metabase 每张卡片都能下载结果一致）。
  - 判据：分析工作台的结果工具栏与分析面板的「⋯」都有「导出数据…」；文件是表格读法下的行（分组列在前、指标在后，列标题与格子读法同表格，「前 N 组」之内，合计行显示时作为最后一行，不含图上补出或并出的东西），与此刻画的是表还是图无关；窗口复用 D14 的壳、没有「所有／选中」；有测试与故事。
  - 落点：`src/ui/ExportDialog.tsx`、`src/ui/workbench/AnalysisParts.tsx`、`src/ui/dashboard/PanelExport.tsx`、[ui/analysis.md](ui/analysis.md)、[ui/dashboard.md](ui/dashboard.md) 面板菜单。

- **仪表盘可切固定宽度／全宽**（[D22](decisions.md#d22-仪表盘与嵌入视图参照-metabase2026-09-23)「怎么搭」）——**到 Wow 仓做**：检查点之后这里不开新工作。
  - 为什么：D22 定了、一直没做，也没进这一页（审查 X-11）；24 栏在宽屏上拉满时，一块指标卡能宽到半屏，作者要能让板子按固定宽度居中排。Metabase 的「固定宽度／全宽」是仪表盘自己的设置。
  - 判据：仪表盘配置里有一个宽度成员，校验、编辑模式里切换、读的状态按它排，工作台与嵌入一致；缺省是哪一种动手前先问用户（Metabase 新建的板缺省固定宽度）；有测试与故事。
  - 落点：[model.md#dashboard-配置](model.md#dashboard-配置)、`src/model/dashboard.ts`、`src/dashboard/validate.ts`、`src/ui/DashboardGrid.tsx`、[ui/dashboard.md](ui/dashboard.md)。
- **公开面快照**（审查 A-16）——**首次发布前必做，到 Wow 仓做**。
  - 为什么：根入口一层层 `export *`，连 runtime 的内部件（`RuntimeStore`、`RequestRunner`、`listenerSet` 等）一起导出；迁移方案按带 `!` 的提交判断破坏性改动，首发之后每一个多余的导出都是兼容负担。README 的入口表今天只守「列出的名字真从那个入口导出」（test/docsReferences.test.ts），不守入口多导出了什么。
  - 判据：一条导出名清单的快照测试（读构建出的入口，或 api-extractor）；runtime 的内部件改为按名导出或不导出；[README.md](README.md) 写明各入口的公开面。
  - 落点：`src/index.ts`、`src/runtime/index.ts`、`test/`、[README.md](README.md)。
- **D22 标了「以后」的几项**——线索，到 Wow 仓排阶段时再定：联动筛选、卡片内筛选（「全局筛选」）；按列的点击行为（「点击」）；整板 PDF（「运维」）；订阅、版本历史、验证、缓存要服务端，归阶段 6。
  - 为什么：迁走之后它们只剩 decisions 里的半句话，排下一个阶段时看不到（审查 X-11）。
  - 判据：排进某个阶段时各自成为一条带判据的 TODO，或进 [decisions.md#搁置待议](decisions.md#搁置待议)；那时删掉这一条。
  - 落点：本页。

## 阶段 2 留下的线索（不做，或待产品口径）

- 准入发现里的字段用的是 `field.name`（「给 status 一个值」）而不是显示名——整个包的惯例，要改是包级的决定。
- 「更多图型」折叠宿主扩展的图型：今天没有宿主扩展图型的入口，等有了再做；散点的坐标轴范围要先进模型（`ScatterSpec` 没有轴规格，所以没有坐标轴页签）。
- 透视表（Q8）；精确的 M（Q7）；分析表冻结列；STDDEV／VARIANCE 与去重计数在 ES 上的近似提示按后端能力声明。
