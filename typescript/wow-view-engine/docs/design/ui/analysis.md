# UI 层：Analysis 视图

分析托盘、分析表格与图表。三种视图共用的骨架、状态条、值显示与 `FilterPanel` 见 [README.md](README.md)；图表规格见 [model-shapes.md#图表规格](../model-shapes.md#图表规格)，图表校验与整形见 [kernels.md#图表规则](../kernels.md#图表规则)。

## 托盘：范围 → 维度 | 指标，一个应用

分析视图的编辑器是 `ui/analysis/Tray.tsx`（D20 屏 B），位置与形态跟记录视图的筛选面板**完全一样**：折在标题栏「分析」按钮后面，已保存的视图打开时折起、新建的展开，折起时按钮上带「改过没应用」的点（`editorPending`，由 `filter.pendingCount` 与 `analysis.pending` 合成）。结果才是视图的意思，而一个已保存的分析是作者已经决定过的问题——它不该每次打开都先占掉半屏。

- **托盘只装问题，怎么看是结果的事**（D20）。槽按分析师的步骤排，每一个是一个有名字的 `section`（`EditorSlot`，`data-slot="analysis-slot-*"`）：`RangeSlot` 独占第一行——它就是记录视图那块 `FilterPanel`，`submit={false} modes={false}`，只是换了个标题「范围」；下一行是 `DimensionCard` 的「维度 · 按什么比」与 `MetricCard` 的「指标 · 看什么数」两列，窄屏纵向堆叠。**表格｜图表、图型与合计行不在这里**：它们答的是「我怎么看这个结果」，落在结果第一行的 `AnalysisToolbar` 上（下一节）；
- **没有「简单／高级」两套托盘**（结清 Q2）。更多能力按定义声明长出来，不长出第二套界面。唯一保留的简单／高级是**条件树的语法**（记录视图已有的 `filterMode`），标在范围槽标题右端的一颗 ghost 菜单「条件：简单 ▾」里（`data-slot="conditions-mode"`，装的就是 `FilterModes`）——它管范围，也管将来每个指标自己的条件，所以它属于条件而不属于面板。没有它，`defaultAnalysisConfig` 从 simple 起步的分析视图**永远表达不出 OR、NOR 与嵌套组**，那是少了一种能力，不是少了一个控件；
- **一个应用，跑整份草稿**（D17-3）。托盘底下是 `FilterActions`，`pending={filter.pending || analysis.pending}`：清空与一颗「应用」。**没有「运行」了**——范围的条件与问题本来就是一份配置，`useAnalysisEditor.submit` 与 `useFilterEditor.submit` 调的是同一个 `runtime.apply()`，两个入口只是逼着屏幕把其中一个降成 `outline`。降级解决不了「哪个按钮跑查询」这个问题，删掉一个才解决。那颗点因此也只有一处：无论改动落在哪个槽，应用上带点，托盘折起时点在标题栏按钮上；
- **维度卡片**：字段显示名，加上它的类型要的那个控件——字段能被切两种以上时才画类型选择（`label.analysis.grouping-of`），只能切一种就只写那个词（一个改不了的选择不教人任何事，还白占一个 tab 位）；`DATE_HISTOGRAM` 多一个粒度选择（`label.date-unit.*`），`HISTOGRAM` 多一个区间宽度数字框；末尾一颗移除。「+ 添加维度」只列定义声明为可分组的字段，所以点不出一份跑不起来的查询；
- **指标卡片**：字段显示名加一个「汇总方式」选择——Wow 测量一个字段的六种方式（函数、去重计数、百分位、任一值）在卡片上是**一张单子**（D20 汇总方式），记录数自己一张卡、不带字段。百分位多一个数字框（Wow 的开区间，100 不是百分位、0 不是），最后一条指标的移除按钮禁用（聚合查询至少要一个指标）；
- **`replaceMetric` 而不是 `updateMetric`**：换汇总方式是**换指标类型**——合计变成去重计数、再变成任一值——`updateMetric` 那种 patch 会把上一形态的 `function` 或 `expression` 留在对象里让准入绊倒。卡片按 `metricOfSummary` 造一个完整的指标整只换掉（别名留着：它是查询的名字，图表与排序都指着它）。`updateMetric` 只用于同一形态里的一个数，比如百分位那个数；
- **排序与前 N 组**（`SortRow.tsx`，`data-slot="analysis-sort"`）落在指标槽底部：「前 N 组」只有挨着「按什么排」才读得懂。没有维度就整行不画——Wow 拒绝对无分组聚合排序，而它本来就只有一行；
- **跑不起来的配置从状态行回到托盘**：`errorAction` 是一颗「打开分析」（`label.analysis.open-editor`），因为发现是关于托盘的，而托盘可能正折着（F11）。
- 纯规则都在 `ui/analysis/editing.ts`（`freeAlias`、`defaultGroup`、`groupOfType`、`summaryOf`、`summaryChoices`、`metricOfSummary`、`defaultMetric`、`fieldOfMetric`），卡片因此只剩标记。（见 test/analysisTray.test.tsx「the analysis tray」「opens a saved view folded, and the toggle opens the tray」「lays the slots out as range, then dimensions beside metrics」「carries one primary button on the screen, and it is Apply」「marks Apply while any slot holds something that has not run」「reaches the condition grammar from the range slot’s heading」「opens the tray from a config that will not run」「the tray’s dimension cards」「the tray’s metric cards」「swaps the whole metric when the summary changes」与 stories/view-engine 的 `TrayFolds`、`TrayEdits`）

## 结果第一行：读法与看法

- **结果的第一行是 `AnalysisToolbar`**（`data-slot="result-toolbar"`，D12 Ⅳ）。左边一句「按 仓库 · 记录数、金额 的 合计」（`label.analysis.reading`，无维度时 `label.analysis.reading-flat`，`data-slot="analysis-reading"`）——下面这些数是什么，按**产生这个结果的那份配置**（`view.schema ?? view.columns`）读出来，不是按正在编辑的草稿；右边是怎么看它：表格｜图表、图型、合计行；
- **这一行改了就跑**，不等托盘的应用：内核只为跑过的那份配置整形图表（`projectAnalysis` 只在 `layout === 'chart'` 时整形），所以换布局本来就是一次新执行而不是重绘。`setLayout` 自己就带 apply，因此工具栏**不**再给它套一层 `submit()`——套了就是同一个问题发两遍；图型与合计行则是 `change(); analysis.submit()`。（见 test/analysisTray.test.tsx「the analysis result toolbar」「reads the result out as dimensions and metrics」与 test/analysisUi.test.tsx「draws the layout the result was shaped by, not the draft」）

## AnalysisChart 与 shapeChart

- **布局与图表配置读的是同一处：产生当前结果的那份配置（`ViewResult.config`），不是正在编辑的草稿。** 类目按别名找列取标签，草稿的别名在 Run 之前可能已指向别的列；而布局从前读草稿、图表配置读结果，于是草稿刚切到 chart、结果还是按 table 整形的那一刻，工作台要一张根本没整形过的图，什么也画不出来——一处说了算就不会自相矛盾。图表只画内核已经整形好的数据：透视、合并"其他"、漏斗累计与转化率、热力图矩阵、比较值都在 `shapeChart` 里完成，`AnalysisChart` 只选标记与配色，换一个图表库不触碰任何规则。`AnalysisChart` 按 `spec.colors` 给系列或分类上色，其余按 `--chart-1..5` 顺序取用；
- 五档色相在亮暗两种模式下各自校过分离度与对比度。热力图与漏斗自绘，用图表库画它们的成本高于收益。`projectAnalysis` 只在 `layout === 'chart'` 时整形图表，因此切换 Table／Chart 是一次新的执行而不是重绘，`useAnalysisEditor.setLayout` 据此直接 apply；
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
