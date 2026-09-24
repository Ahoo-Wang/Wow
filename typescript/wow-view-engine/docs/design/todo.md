# TODO

规则，先读再加：

- 只记**已经决定、但还没做**的事。尚无结论的产品问题不在这里，在 [decisions.md#搁置待议](decisions.md#搁置待议)。
- 每条都要写清 **为什么** / **做完的判据** / **落点**（design 里的哪一页，或哪个文件）。三者缺一就不是一条 TODO，是一句抱怨。
- 做完就**删掉**这一条，不打勾、不留归档。历史在 git 里。
- 改行为之前先看这里有没有对应项；有就接着做，别另起一条。

## 检查点：迁往 Wow 仓（阶段 3、4 收口之后）

- **迁移窗口已开（2026-09-24）。** 阶段 3、4 与它们的联合审查处置全部合并（最后一批 R3b #1902），用户确认了迁移步骤：这里不再开新工作，本包之后在 Wow 仓的 `typescript/wow-view-engine` 里做（Wow 那份方案是 `typescript/MIGRATION.md`）。下面几条都到 Wow 做。
  - 为什么：迁移方案定的时机是阶段边界；阶段 6 的存储后端也要和引擎放在同一个仓库。
  - 判据：远端一出现 tag `wow-migration-base`（用 `git ls-remote --tags origin wow-migration-base` 查），本包就冻结，这里一律不再改；迁移第 3′ 步把本包从 fetcher 删掉时，这一页随之删除。
  - 落点：[迁移方案](../../../../docs/superpowers/specs/2026-09-23-wow-packages-migration-design.md)，根目录 `AGENTS.md` 的「Migration Checkpoint」一节。

- **P2：数值区间的一段，已应用条与菜单两种说法**（2026-09-23 审查 P2 的余项；日期桶那一半已做，见 [ui/analysis.md](ui/analysis.md) 的追问一节）：一段的条件是同一字段的 `GTE` 与 `LT`，已应用条上是两个 chip，菜单与从它开出去的视图的名字却说「单价 在 ¥0～500」。
  - 判据：从一段开出去的视图，「正在显示」与标题说同一句；有测试。
  - 落点：`src/filter/describe.ts`（同一字段的 `GTE`＋`LT` 合读成一段，像 `period` 那样交出）、`src/ui/summary.ts`。

- **批 C 之前的整板条件——固定范围的界面**（[D23](decisions.md#d23-搁置待议的六条拍板2026-09-23) Q16）：迁成默认值的那一半已做（`migrateDashboardConfig` 的 `intoDefaults`，[model.md#dashboard-配置](model.md#dashboard-配置)）。拆不开的存成独立成员 `fixed`（D26 Q31），在筛选条那一行只读地写作「固定范围」、读者拿不掉（[D27](decisions.md#d27-仪表盘不画正在显示条2026-09-24)，R7b 已做，见 [ui/dashboard.md](ui/dashboard.md)「筛选」）。剩下编辑模式里整体删掉——到 Wow 做。
  - 判据：编辑中那一枚删得掉（删掉即 `fixed` 为空树，随板子保存）；有测试与故事。
  - 落点：`src/ui/dashboard/FilterBar.tsx`、[ui/dashboard.md](ui/dashboard.md)「筛选」。

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
