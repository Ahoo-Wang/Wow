# UI 层：Analysis 视图

分析托盘、分析表格与图表。三种视图共用的骨架、状态条、值显示与 `FilterPanel` 见 [README.md](README.md)；图表规格见 [model-shapes.md#图表规格](../model-shapes.md#图表规格)，图表校验与整形见 [kernels.md#图表规则](../kernels.md#图表规则)。

## 托盘：范围 → 维度 | 指标，一个应用

分析视图的编辑器是 `ui/analysis/Tray.tsx`（D20 屏 B），位置与形态跟记录视图的筛选面板**完全一样**：折在标题栏「分析」按钮后面，已保存的视图打开时折起、新建的展开，折起时按钮上带「改过没应用」的点（`editorPending`，由 `filter.pendingCount` 与 `analysis.pending` 合成）。结果才是视图的意思，而一个已保存的分析是作者已经决定过的问题——它不该每次打开都先占掉半屏。

- **托盘只装问题，怎么看是结果的事**（D20）。槽按分析师的步骤排，每一个是一个有名字的 `section`（`EditorSlot`，`data-slot="analysis-slot-*"`）：`RangeSlot` 独占第一行——它就是记录视图那块 `FilterPanel`，`submit={false} modes={false}`，只是换了个标题「范围」；下一行是 `DimensionCard` 的「维度 · 按什么比」与 `MetricCard` 的「指标 · 看什么数」两列，窄屏纵向堆叠。**表格｜图表、图型与合计行不在这里**：它们答的是「我怎么看这个结果」，落在结果第一行的 `AnalysisToolbar` 上（下一节）；
- **没有「简单／高级」两套托盘**（结清 Q2）。更多能力按定义声明长出来，不长出第二套界面。唯一保留的简单／高级是**条件树的语法**（记录视图已有的 `filterMode`），标在范围槽标题右端的一颗 ghost 菜单「条件：简单 ▾」里（`data-slot="conditions-mode"`，装的就是 `FilterModes`）——它管范围，也管将来每个指标自己的条件，所以它属于条件而不属于面板。没有它，`defaultAnalysisConfig` 从 simple 起步的分析视图**永远表达不出 OR、NOR 与嵌套组**，那是少了一种能力，不是少了一个控件；
- **一个应用，跑整份草稿**（D17-3）。托盘底下是 `FilterActions`，`pending={filter.pending || analysis.pending}`：清空与一颗「应用」。**没有「运行」了**——范围的条件与问题本来就是一份配置，`useAnalysisEditor.submit` 与 `useFilterEditor.submit` 调的是同一个 `runtime.apply()`，两个入口只是逼着屏幕把其中一个降成 `outline`。降级解决不了「哪个按钮跑查询」这个问题，删掉一个才解决。那颗点因此也只有一处：无论改动落在哪个槽，应用上带点，托盘折起时点在标题栏按钮上；
- **维度卡片**：字段显示名，加上它的类型要的那个控件——字段能被切两种以上时才画类型选择（`label.analysis.grouping-of`），只能切一种就只写那个词（一个改不了的选择不教人任何事，还白占一个 tab 位）；`DATE_HISTOGRAM` 多一个粒度选择（`label.date-unit.*`），`HISTOGRAM` 多一个区间宽度数字框；末尾一颗移除。「+ 添加维度」只列定义声明为可分组的字段，所以点不出一份跑不起来的查询；
- **指标卡片**：字段显示名加一个「汇总方式」选择——Wow 测量一个字段的六种方式（函数、去重计数、百分位、任一值）在卡片上是**一张单子**（D20 汇总方式），记录数自己一张卡、不带字段。百分位多一个数字框（Wow 的开区间，100 不是百分位、0 不是），最后一条指标的移除按钮禁用（聚合查询至少要一个指标）；
- **卡片自己的菜单**（`CardMenu.tsx`，D20 屏 B）：卡片上只放问题本身的那两三个控件，别的收进末尾一颗 `IconButton`（`data-slot="card-menu"`，`label.analysis.card-menu`「{name} 的更多设置」）。**不是多摆几个控件**——一张摆着六个控件的卡片读起来是张表单而不是一句话，而这些设置一个视图一辈子改一次；按卡片命名而不是叫「更多」，是因为一屏卡片不该有两颗同名的按钮。**同一张卡上的每个控件都按这张卡现在叫什么来命名**（`group.label ?? 字段显示名`）：改完名字，移除、维度设置、汇总方式与菜单一起改口，否则一张卡会一半叫「门店」、一半叫「仓库」；
- **显示名**（`CardName`，D20 显示名）：菜单第一项「改显示名…」（`label.analysis.rename`）把卡片上的名字换成一个输入框（`label.analysis.display-name`「{name} 的显示名」），回车或移开焦点提交，Escape 放弃，**清空则把名字收回去**而不是存一个空名（准入会以 `analysis.label.blank` 拒绝）。框子关掉时焦点回到打开它的那颗菜单按钮，不是丢回页面。离开只结算一次：回到菜单按钮本身就是一次失焦，若失焦也提交，Escape 就会把刚刚丢掉的字存进去；框子只在打开期间挂载，所以每次都从存着的名字起步，而不是从上次放弃的那半截；
- **空值单独一组**（`label.analysis.missing-bucket`，TERMS）是分析师的选择而不是内核的默认：勾上就是 `missingKey` 哨兵，缺值的记录单独成一组；不勾 Wow 直接把它们丢掉。所以新加一个按值的维度**默认是勾上的**（`groupOfType`）——维度不该不声不响地少数记录——而字段担不起哨兵（非单值文本，`AnalysisFieldOption.missingKey`）时这一项根本不出现：一个勾上之后会在应用时被拒的复选框是在骗人；
- **补齐空的时段**（`label.analysis.dense`，DATE_HISTOGRAM）：没有订单的那个月是折线上的一个豁口，跳过它的折线在形状上撒谎。Wow 只允许唯一分组这么填，所以**旁边还有第二个维度时这一项禁用并换一句话**（`label.analysis.dense-alone`「补齐空的时段（只有一个维度时）」），而不是让它消失——消失的控件什么也不教；
- **「+ 添加维度」不再列已经分组的字段**：按同一个字段切两刀切出来的还是第一刀那些组，是没人问的问题（追问菜单的「再按…拆一层」出于同一条理由也把它排掉）。新加的时间维度**从范围推荐的粒度起步**（K4，`analysis.dateUnitFor(field)`，见 [kernels.md#粒度推荐k4](../kernels.md#粒度推荐k4)）：一年的订单按小时切是八千个没人要的桶，一周按月切是一个。它只是个起点，粒度选择就在卡片上，手选过的永远优先；
- **`replaceMetric` 而不是 `updateMetric`**：换汇总方式是**换指标类型**——合计变成去重计数、再变成任一值——`updateMetric` 那种 patch 会把上一形态的 `function` 或 `expression` 留在对象里让准入绊倒。卡片按 `metricOfSummary` 造一个完整的指标整只换掉（别名留着：它是查询的名字，图表与排序都指着它）。`updateMetric` 只用于同一形态里的一个数，比如百分位那个数；
- **排序与前 N 组**（`SortRow.tsx`，`data-slot="analysis-sort"`）落在指标槽底部：「前 N 组」只有挨着「按什么排」才读得懂。没有维度就整行不画——Wow 拒绝对无分组聚合排序，而它本来就只有一行；
- **跑不起来的配置从状态行回到托盘**：`errorAction` 是一颗「打开分析」（`label.analysis.open-editor`），因为发现是关于托盘的，而托盘可能正折着（F11）。
- 纯规则都在 `ui/analysis/editing.ts`（`freeAlias`、`defaultGroup`、`groupOfType`、`summaryOf`、`summaryChoices`、`metricOfSummary`、`defaultMetric`、`fieldOfMetric`），卡片因此只剩标记。（见 test/analysisTray.test.tsx「the analysis tray」「opens a saved view folded, and the toggle opens the tray」「lays the slots out as range, then dimensions beside metrics」「carries one primary button on the screen, and it is Apply」「marks Apply while any slot holds something that has not run」「reaches the condition grammar from the range slot’s heading」「opens the tray from a config that will not run」「the tray’s dimension cards」「the tray’s metric cards」「swaps the whole metric when the summary changes」、test/analysisCards.test.tsx「a tray card’s menu」「a display name」「drops the edit on Escape, and starts fresh the next time」「titles the header and the reading once it has run」「the sentinel bucket」「filling in empty periods」「the granularity a new time dimension starts at」「the fields a dimension may be added on」与 stories/view-engine 的 `TrayFolds`、`TrayEdits`、`TrayCardMenu`）

## 结果第一行：读法与看法

- **结果的第一行是 `AnalysisToolbar`**（`data-slot="result-toolbar"`，D12 Ⅳ）。左边一句「按 仓库 · 记录数、金额 的 合计」（`label.analysis.reading`，无维度时 `label.analysis.reading-flat`，`data-slot="analysis-reading"`）——下面这些数是什么，按**产生这个结果的那份配置**（`view.schema ?? view.columns`）读出来，不是按正在编辑的草稿；右边是怎么看它：表格｜图表、图型、合计行；
- **表格｜图表是重绘，不是重跑**（D20，`ANALYSIS_PRESENTATION_MEMBERS`）：结果的行来自跑过的那份配置，怎么看它来自草稿，所以换布局只是把同一批行画成表或画成图，不发查询、不算待应用；「可视化」在这一行打开左侧栏的图型网格（下一节），托盘里没有它。合计行是一次自己的查询，所以仍是 `change(); analysis.submit()`。（见 test/analysisTray.test.tsx「the analysis result toolbar」「reads the result out as dimensions and metrics」「keeps the way into the visualization beside the layout switch, not in the tray」与 test/analysisUi.test.tsx「redraws the layout from the rows on hand, without a run」）

## AnalysisChart 与 shapeChart

- **行与列读的是产生当前结果的那份配置（`ViewResult.config`），怎么看它读的是草稿。** 别名只有那份配置说了算——类目按别名找列取标签，草稿的别名在应用之前可能已指向别的列；而"画成表还是画成图、画成哪种图"是结果的属性而不是问题的一部分（D20），所以它们读草稿，拿同一批行重画。两者的接缝在 `ui/workbench/AnalysisParts.tsx`：**草稿的形态与跑出这批行的形态不一致时**，图表规格先过一遍 `fitChartSlots` 落到跑出来的那个形态上——托盘里刚加、还没应用的那个维度不是这批行的列，指着它的图什么也画不出来；一致时图表**原样**画，因为 `fitChartSlots` 会把作者收窄过的槽重新放开（两个指标只画一条系列的柱状图会变回两条），那在形态挪动时是对的，在每一次重绘里是错的。图表只画内核已经整形好的数据：透视、合并"其他"、漏斗累计与转化率、热力图矩阵、比较值都在 `shapeChart` 里完成，`AnalysisChart` 只选标记与配色，换一个图表库不触碰任何规则。`AnalysisChart` 按 `spec.colors` 给系列或分类上色，其余按 `--chart-1..5` 顺序取用；
- 五档色相在亮暗两种模式下各自校过分离度与对比度。热力图与漏斗自绘，用图表库画它们的成本高于收益。**布局与图型都是重绘，不是重跑**（D20，见下一节）：工作台拿回来的那批行用 `shapeChart` 按草稿的图表规格现整形，所以 `useAnalysisEditor.setLayout` 与 `setChartType` 只编辑草稿，不 apply；
- 托盘里的改动等「应用」，等着的时候那颗点在应用按钮上（`data-pending`，`filter.pending || analysis.pending`，基准是整份配置，见 [ui/README.md#三态各有一处凭据](README.md#三态各有一处凭据)），被拒的应用同样算没应用。**同屏唯一的 primary 就是它**（D17-3，[版式](README.md#版式三块一套间距一种选项控件)）：范围与问题是一份配置、一次 `runtime.apply()`，所以只有一颗按钮跑查询。（见 test/analysisChart.test.tsx「AnalysisChart」、test/analysisUi.test.tsx「useAnalysisEditor」、test/analysisTray.test.tsx「carries one primary button on the screen, and it is Apply」与 test/analysisChart.test.ts「shapeChart」）

### 一个家族一个文件

- `AnalysisChart.tsx` 只剩按 `data.type` 分派，外加把类目标签器交给家族；六个家族与它们共用的工具各自成文件，改一个家族不必通读另外五个：

| 文件                          | 管什么                                                                                          |
| ----------------------------- | ----------------------------------------------------------------------------------------------- |
| `ui/AnalysisChart.tsx`        | 按 family 分派；`AnalysisChartProps` 是对外的那一个                                             |
| `ui/charts/Cartesian.tsx`     | bar／line／area／combo，双数值轴、参考线与 `mark` 选标记                                        |
| `ui/charts/PieSlices.tsx`     | 饼图与环形图，「其他」那一片                                                                    |
| `ui/charts/ScatterPoints.tsx` | 散点，第三维走 `ZAxis`                                                                          |
| `ui/charts/Heatmap.tsx`       | 自绘网格                                                                                        |
| `ui/charts/Funnel.tsx`        | 自绘阶段条与转化率                                                                              |
| `ui/charts/MetricCard.tsx`    | 指标卡：值、比较、目标与迷你趋势                                                                |
| `ui/charts/palette.ts`        | `--chart-1..5` 取色与 `spec.colors` 的覆盖（值在进 `<style>` 前再校一次）                       |
| `ui/charts/axis.ts`           | 数值格式、轴域与刻度格式、左右轴归属                                                            |
| `ui/charts/family.ts`         | `FamilyProps`（每个家族收到的同一份 props）、值标签器 `useValueLabel` 与列标题 `useColumnTitle` |
| `ui/charts/TooltipValue.tsx`  | 提示里的那一个数值，按它所在列的读法                                                            |

- 家族组件都不导出到包外：`/ui` 的出口只有 `AnalysisChart`，换一种画法是换 `charts/` 下的文件，不是换一个公开 API。（见 test/analysisChart.test.tsx「AnalysisChart」）

## 图表怎么被读出来

- **画出来的部分是一张有名字的图，数字在它旁边。** recharts 默认打开 `accessibilityLayer`，给根 `<svg>` 挂上 `role="application"` 与 `tabIndex={0}`：读屏会因此退出浏览模式、把按键交给一个没有任何键盘处理的元素，而那个元素里只有坐标轴刻度、一个空 `<title>` 和一个空 `<desc>`——查询回答的数字一个都不在。`charts/asImage.ts` 把这一层关掉，换成 `role="img"` 加 `aria-label`；热力图与漏斗是自绘的 `div`，同样以 `role="img"` 加名字整块作为一张图。图里于是没有任何可聚焦的元素；
- **名字说的是"画的是什么"**：`{图型}：{度量}，按 {类目}`（`label.chart.figure`），度量与类目都按别名回 `AnalysisView.schema` 取列标题，取不到就只剩图型名。指标卡没有类目，用 `label.chart.figure.plain`；它的迷你趋势线另有一个 `label.chart.sparkline`；
- **可读替代来自同一份投影。** `charts/reading.ts` 从 `ChartData`——而不是旁边那份行投影——生成 `{name, header, rows}`，`ChartReadingTable` 用注册表的 `Table` 以 `sr-only` 渲染在图旁边，于是屏幕上画了什么、读屏就读到什么，两者不会各说各话：图上有、这里没有的值，只能意味着某个家族画了内核没整形过的东西。数值按该系列所在坐标轴的格式打印（`percent` 轴上的 0.25 读作 25%），空洞读作 `label.summary.unavailable`；
- **指标卡的值本来就是文字**，所以它整张卡不是图：值与带符号的比较照旧是可见文本，只有进度条背后的目标、比较的原值与趋势的每个点进 `sr-only` 表——那三样只有画出来的部分知道。（见 test/analysisChartA11y.test.tsx 与 test/accessibility.test.tsx「analysis, drawn as a chart」）
- **那根进度条就是注册表的 `Progress`**，不是一个靠内联 `width` 撑长度的 `div`：会填充的条本来就是 progressbar，角色、当前值与上下界只有真的那个才带得上。它以 `label.chart.target` 为名，`aria-valuetext` 用 `label.chart.target.reached` 把位置读成背后那两个数（`{value} of {target}`），而不是角色默认要念的那个百分比；目标为 0 时不做除法——没有可差的距离，要么到了要么没到。轨道高度在调用处抬到 2px（与导出进度同一个写法），`ui/components/**` 是上游的，不手改。（见 test/analysisChartA11y.test.tsx「the metric card still says its value out loud」）

## 追问：点一组弹三项

- **手势是"按下这一组"，而不是"按下某个按钮"。** 指针按在标记上——柱子（`charts/Cartesian.tsx`，挂在 recharts 的 `<Bar>` 上）、扇区（`PieSlices.tsx`）、热力图格子（`Heatmap.tsx`）、散点（`ScatterPoints.tsx`）——键盘按在表格的行上（`AnalysisTable.tsx`：行 `tabIndex=0`、`aria-haspopup="menu"`、`data-pickable`，Enter 或空格弹出同一个菜单）。四个家族与表格交出的都是同一个 `onPick(row, anchor)`（`charts/family.ts`），所以一个菜单服务所有布局；
- **键盘那条路是表格布局，不是图旁边那张 `sr-only` 表（F10）。** 画出来的部分整块是一张 `role="img"` 的图，里面一个可聚焦元素都没有（[图表怎么被读出来](#图表怎么被读出来)），而 `ChartReadingTable` 是读屏用的替代文本，本来就摆在指针与 Tab 都够不到的地方——把它做成可操作的，等于把"读得到"和"点得动"混成一件事。两种布局回答的是同一个问题，所以键盘的入口是切到表格：那里一行就是一组；
- **三项**（`ui/analysis/DrillMenu.tsx`）：**查看这些记录**（`workbench.drill(conditions)`，在同一个工作台里开出未保存的记录视图，带「来自」那一条，见 [react.md](../react.md) 的「持有的视图」；`canDrill` 为假时这一项不在——不是禁用，是不画）、**再按…拆一层**（一层子菜单，列出能分组、当前结果又还没按它分的字段；选中即 `splitBy` 后 `apply`）、**只看这一组**（`focusOn` 后 `apply`）。后两项改的是当前这个分析视图的配置，所以它们和手改编辑器一样会变脏、可撤、可保存；
- **菜单的标题就是这一组的条件**，由 `drillConditions` 交出、`describeFilter` 描述、`summaryText` 说出来——与「正在显示」那条用的是同一套词，所以"我点的是哪一组"和"现在筛的是什么"读起来是一句话的两半。标题写在菜单组**里面**：它标的就是组里这几项，读屏进到组里先听见条件；
- **贴着按下去的那个东西弹**：标记交出自己的元素，柱子、扇区与散点交出按下的那个点（`pointAnchor`），`ui/popups.tsx` 的 `DropdownMenuContent` 因此多一个 `anchor`。菜单**没有**自己的触发控件，但 Base UI 把菜单在浮动树里的节点挂在 Trigger 上，没有 Trigger 的根会把自己的子菜单当成兄弟菜单、一展开就把自己关掉——所以 `DrillMenu` 画一个谁也够不到的 Trigger 只为占住那个节点，焦点去哪儿由 `finalFocus` 说了算：关掉菜单，键盘回到按下的那一行；
- **展开了 elements 的分析不可按**（`pickable` 为假）：它的一行是最内层元素的一组，根文档上没有哪条条件选得出来，`drillConditions` 也交不出条件——于是标记与行根本不带这个手势，而不是弹一个三项都不灵的菜单。（见 test/drillMenu.test.tsx「the follow-up menu on one group」与 stories/view-engine 的 `FollowUpToRecords`／`FollowUpFocus`／`FollowUpSplit`）

## 刷新落在标题栏

- 刷新那个拆分按钮落在标题栏右组的视图级控件里（`WorkbenchShell` 的 `freshness` 槽）——它问的是「这一屏多久自己更新一次」，属于视图而不属于结果，所以它与结果工具栏上那几个「我怎么看这个结果」不在一处。规则与措辞与 Record 的那一个完全相同，见 [README.md#刷新是一个拆分按钮](README.md#刷新是一个拆分按钮)。`QueryStrip` 上的「重试」是失败后的出口，与它不是一回事：一个是出错了再来一次，一个是没出错也每隔一段时间来一次。（见 test/refreshControl.test.tsx「every workbench offers the interval」）

## 被截断的分组要说出来

- 结果行数恰好填满 `limit` 时，屏幕上的行只是真实分组的一个前缀，于是这一屏的每个占比、每个百分比、每个扇区都是拿"已显示的部分"当分母算出来的。饼图是最坏的一种：它的全部含义就是"各部分占整体多少"，而整体已经不在图里了；
- 因此工作台把这条 warning（`analysis.result.at-limit`）交给 `WorkbenchShell` 的 `warnings`，状态条在**表格与图表之上**，两种布局各画各的，这一行是共同的，切换布局不会把它丢掉。措辞是"可能被截断"：聚合只回答了行数，"恰好等于上限"既可能是刚好这么多组，也可能是被截掉的前缀，判据见 [../kernels.md#compileanalysis-与-projectanalysis](../kernels.md#compileanalysis-与-projectanalysis)；
- 合计行照旧来自自己的无分组查询，所以它仍然覆盖全部——可见的几行加起来小于它们下面的合计，两个数都没错，正是这条 warning 要解释的事。（见 test/resultIssues.test.tsx「what the screen says about an analysis cut short」与 stories/view-engine 的 `CutShort`／`CutShortTable`）

## 数字按它自己的列读

- **坐标轴、提示、热力图格子、漏斗条与指标卡上的数，和表格里那一列是同一个读法**（`useValueLabel`）：一列在表里写作 ¥1,234.00、在提示里写作 1234，是同一个数的两种读法，而只有一种是那一列的。`AxisSpec.format` 仍然优先——那是关于这条轴的指令，`percent` 轴上的 0.25 就该读作 25%；
- **格式来自 `metricFormat(metric, field)` 而不是字段本身**（[kernels.md#指标的数怎么读](../kernels.md#指标的数怎么读)）：字段的 `numberFormat` 描述的是一个存下来的值，而一个整数字段的平均值不是整数，一个金额字段的去重计数不是钱；
- **语言也跟着界面走**：`valueText` 收 `DisplayContext.locale`，没有它的时候数字按运行这台机器的语言分组——那是唯一一种没人选过的语言，`zh-CN` 下写作 `CN¥` 而整页写的是 `¥`；
- **表头是两截拼出来的**（`columnTitle`）：内核交出字段显示名与汇总方式两个部件，中英各按自己的语序拼成「金额 的 平均」／`Average of Amount`（`label.summary.of`，与记录视图的列汇总同一套词）。同一字段的两个汇总方式因此是两个不同的表头，而别名（`amount_1`）从来不是谁起的名字；记录数自己一个词。（见 test/analysisTable.test.tsx「an analysis column header」「an analysis number」与 test/analysisChart.test.tsx「reads a numeric axis through the metric on it」）
- **提示里的那一行是自己画的。** 上游的 `ChartTooltipContent` 把数字写成 `toLocaleString()`，而它给出的唯一钩子 `formatter` 替换的是整行，所以色块、系列名与数值写在 `ui/charts/TooltipValue.tsx`——与 `ui/popups.tsx` 同一条缝，理由也一样：`ui/components/**` 是上游的，不手改。

## 空结果只有一句话

- 结果没有任何一组时，表格与图表说同一句 `label.analysis.empty`（`ui/analysis/EmptyResult.tsx`）。图表从前画一对空坐标轴——那读起来是"这张图坏了"，而不是"范围里没有符合条件的组"；
- 句子说的是**范围**，不是分析：从前的「没有可聚合的内容」读作"你这个分析算不出东西"，而指标好好的，只是没有组落进来。（见 test/analysisTable.test.tsx「says that no group matched」与 test/analysisUi.test.tsx「says that no group matched, chart layout included」）

## 可视化：结果工具栏呼出左侧栏，先选图型

D20 把可视化定为分析的**最后一步**：结果先是表格，确认完数据再谈怎么画。入口因此在结果工具栏右侧（`ui/analysis/AnalysisToolbar.tsx` 的 `data-slot="visualize"`，`aria-pressed` 说它开着没有），而不是在托盘里——托盘只装问题本身，图是结果的属性。

- **面板占左侧栏，不另开一栏**（`WorkbenchShell` 的 `panel` 槽，`data-slot="view-panel"`，[README.md#工作台骨架](README.md)）。视图列表是导航，配图的时候不需要导航；结果区因此一格不移，用户盯着的那张图不会因为开了个面板就跳一下。列表折起与否都画，**面板自带返回**（`label.chart.picker-back`），不靠列表把自己换回来。
- **第一层是图型网格**（`ui/analysis/ChartPicker.tsx`，`role="radiogroup"`），每格一张卡片（`data-slot="chart-tile"`，`data-chart-type`），三种状态各有各的凭据：
  - **在不在由能力决定**（D4）：定义没声明的图型根本不在网格里，灰着也不给——一个永远按不动的东西不是选项；
  - **灰不灰由形态决定，而且写明理由**：`fitCharts` 判（[kernels.md#哪些图型画得了这个形态fitchartsk3](../kernels.md)），灰掉的卡片带 `aria-disabled` 与一行 `data-slot="chart-reason"`，句子同时进它的 `aria-label`（「热力图。要两个维度」），因为"为什么不能选"和"不能选"是两件事，只说后一件等于不说；
  - **推荐带一个记号**（`data-recommended` 与 `data-slot="chart-recommended"`，文案 `label.chart.recommended`），至多一张卡片有。推荐是记号不是动作：手选之后不再自动换。
- **表格也是一张卡片**，排在最后。"回到表格"和"换成饼图"于是是同一个手势、同一处控件，而不是一个在工具栏的分段按钮、一个在面板里。
- **选完只重画，不发查询**：`layout` 与 `chart` 是呈现成员（`ANALYSIS_PRESENTATION_MEMBERS`，[model.md#viewconfigbase-的三个字段](../model.md)），`comparePending` 跳过它们，所以标题栏那颗「改过没应用」的点不为它们亮——按下去什么也不跑的点是在教人按没用的按钮。它们照旧随视图保存。
- **键盘按 radiogroup 的规矩走**：整组只有一个 Tab 停留点（选中的那张），方向键在**画得出来的**卡片之间同时移动选择与焦点，空格与回车就地选中。灰掉的那几张被方向键跳过——没有什么可选的——但它们用 `aria-disabled` 而不是 `disabled`：`disabled` 在有些读屏里连同那行理由一起从可访问树里拿走，而"为什么不能选"正是它唯一要说的话。

（见 test/chartPicker.test.tsx「the visualization panel」「a layout is a redraw, not a run」与 test/fitCharts.test.ts「fitCharts」；浏览器里的回归是 stories/view-engine 的 `VisualizePanel`）

## 可视化的第二层：选中图型的选项，三个页签

- **齿轮在磁贴旁边，不在磁贴里面。** 第一层的每块磁贴本身是一颗按钮（`role="radio"`），而一颗按钮里装不下另一颗按钮——嵌套的可交互元素读屏说不清、指针也分不出按的是哪一个。所以齿轮是磁贴的邻居而不是它的孩子：`IconButton`（`data-slot="chart-options-open"`，名字是「{图型}的选项」）浮在被选中那块磁贴的右上角，且只有被选中的那一块有它——没选中的图型谈不上"它的选项"。推荐标记因此让到磁贴左上角，两枚角标各占一头；
- **不论哪个家族，页都是同样那三页**：数据（哪个别名坐哪个槽）、显示（这张图怎么画）、坐标轴（数值轴的标题与范围）。`optionTabs(picked)` 说一个图型有哪几页：笛卡尔家族三页；散点只有"画哪两个指标"，一页；表格只有合计行，那是显示，一页；其余家族两页。**只有一页时不画页签条**——一条只有一项的页签条是个按不动的控件。结构按家族走而不是每个图型另起一套，是因为换图型换的是画法而不是这块面板：从柱状图换到饼图，"数据"仍然在第一页上；
- **数据页只有一条规则：位置槽列维度，度量槽列指标。** 横轴／拆分／类别／行／列／每个点是／阶段取自只列分组别名，系列／数值／横／纵／大小／对比只列指标别名，于是一个槽装不进不该装的东西，`validateChart` 的那几条别名规则在界面上根本无从触发。每个选项写的是**列标题**而不是别名（`useColumnTitle`）：「金额 的 合计」，而 `amount_1` 命名的是查询；漏斗的阶段写的是那个分组值自己的读法（`useValueLabel`）；
- **选另一个槽已经拿着的别名，两个槽对调**（`placed`）。按横轴拆分的图是 `chart.splitBy.same-as-x`，画不出来；而用户的动作分明是"把这个维度放到横轴上"。对调是唯一一种不丢东西的解释——两个槽仍各有人坐，没有谁需要重新选。热力图的行／列与散点的横／纵走同一条规则；
- **显示页上的设置是整张图一个选择。** 堆叠与平滑不是"某几个系列凑一堆"：`isStacked`／`withStacked` 与 `isSmooth`／`withSmooth` 要么全体加入要么全体退出，读回来时半数堆叠不算堆叠；只有一个系列又没有拆分时没有可堆的东西，那个框在那儿禁用着而不是不画——不画会读成"这张图不支持堆叠"。**加入堆叠同时把所有系列收回同一根轴**：叠在一起的段是在相加，而两把尺子相加没有意义——跨两根轴的"堆叠"是每根轴各堆一摞，画在同一个位置、同样的宽度，于是高的那摞把矮的整个盖住，读起来是图坏了而不是一个和。取消堆叠不把轴还回去：哪个系列量在哪把尺子上是个选择，面板不替人猜一个旧的。图例与数值标签同理，一张图一份。图例的缺省是家族自己的答案（`charts/legend.ts` 的 `legendPlacement`）：饼图总有一个，笛卡尔图要到第二个系列才有，用户说了「无」就一个也没有。**颜色不在这里**（D20）：配色是主题的事；
- **坐标轴页只有笛卡尔家族有**，因为只有它有数值轴；类目轴没有可设的东西——它说的就是那个维度说的话。**右轴要等有系列坐上去才成为一节**：一条没有系列的轴不画，于是也没有它的标题与范围可填。四项（轴标题／最小／最大／数值格式）都空掉时 `yAxis.left` 整个消失，最后一侧消失时 `yAxis` 也消失——没人说过的事不该在配置里留下一个空对象；
- **表格那一项要跑查询，其余都是重画。** 合计行来自它自己那次无分组聚合（见[被截断的分组要说出来](#被截断的分组要说出来)），所以按下即 `setTotals` + `submit`；图表的每一处改动只改 `chart`，而 `chart` 与 `layout` 都是 `ANALYSIS_PRESENTATION_MEMBERS`，走 `updateChart` 重画屏幕上已有的行——不回后端，也不在标题栏「分析」那颗开关上点亮未应用的点；
- **漏斗的阶段顺序从结果行里起头**（`withStagesFrom`）。阶段的业务顺序内核不知道，`fitChartSlots` 把 `order` 留空，而没有阶段的漏斗什么也画不出来——刚选中就是一片空白，读起来是坏了。所以在第一层选中漏斗的那一刻，就按结果行来的顺序把各分组值填进去（每个文本值一次），之后用户在数据页上用上移／下移排它。（见 test/chartOptionsUi.test.tsx「the chart options」「the chart options’ display page」「the chart options’ axes page」「the chart options of the other families」「what the chart options change on screen」、test/chartOptions.test.ts「chartOptions」与 test/chartLegend.test.ts「legendPlacement」；浏览器里走一遍的是故事「可视化面板/回归」的 `ChartOptionsPages`）
