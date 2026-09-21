# UI 层：Analysis 视图

分析编辑器、分析表格与图表。三种视图共用的骨架、状态条、值显示与 `FilterPanel` 见 [README.md](README.md)；图表规格见 [model-shapes.md#图表规格](../model-shapes.md#图表规格)，图表校验与整形见 [kernels.md#图表规则](../kernels.md#图表规则)。

## AnalysisChart 与 shapeChart

- 工作台交给 `AnalysisChart` 的是产生当前结果的那份图表配置（`ViewResult.config`），不是正在编辑的草稿：类目按别名找列取标签，草稿的别名在 Run 之前可能已指向别的列。图表只画内核已经整形好的数据：透视、合并"其他"、漏斗累计与转化率、热力图矩阵、比较值都在 `shapeChart` 里完成，`AnalysisChart` 只选标记与配色，换一个图表库不触碰任何规则。`AnalysisChart` 按 `spec.colors` 给系列或分类上色，其余按 `--chart-1..5` 顺序取用；
- 五档色相在亮暗两种模式下各自校过分离度与对比度。热力图与漏斗自绘，用图表库画它们的成本高于收益。`projectAnalysis` 只在 `layout === 'chart'` 时整形图表，因此切换 Table／Chart 是一次新的执行而不是重绘，`useAnalysisEditor.setLayout` 据此直接 apply；
- 其余改动等 Run，等着的时候 Run 带着筛选面板的 Apply 上那颗点（`data-pending`，`useAnalysisEditor.pending`，基准是整份配置，见 [ui/README.md#三态各有一处凭据](README.md#三态各有一处凭据)），被拒的 Run 同样算没应用。**Run 是 `outline`，不是 primary**（D17-3）：分析的编辑带是筛选面板**加上**分析编辑器，两个提交按钮叠在一屏上，而 `useAnalysisEditor.submit` 与 `useFilterEditor.submit` 调的是同一个 `runtime.apply()`——一次执行的两个入口，所以同屏唯一的 primary 是 Apply（[版式](README.md#版式三块一套间距一种选项控件)），这一条只动分量、不动提交语义。（见 test/analysisChart.test.tsx「AnalysisChart」、test/analysisUi.test.tsx「useAnalysisEditor」「carries one primary button on the screen, and it is Apply」与 test/analysisChart.test.ts「shapeChart」）

### 一个家族一个文件

- `AnalysisChart.tsx` 只剩按 `data.type` 分派，外加把类目标签器交给家族；六个家族与它们共用的工具各自成文件，改一个家族不必通读另外五个：

| 文件                          | 管什么                                                                     |
| ----------------------------- | -------------------------------------------------------------------------- |
| `ui/AnalysisChart.tsx`        | 按 family 分派；`AnalysisChartProps` 是对外的那一个                        |
| `ui/charts/Cartesian.tsx`     | bar／line／area／combo，双数值轴、参考线与 `mark` 选标记                   |
| `ui/charts/PieSlices.tsx`     | 饼图与环形图，「其他」那一片                                               |
| `ui/charts/ScatterPoints.tsx` | 散点，第三维走 `ZAxis`                                                     |
| `ui/charts/Heatmap.tsx`       | 自绘网格                                                                   |
| `ui/charts/Funnel.tsx`        | 自绘阶段条与转化率                                                         |
| `ui/charts/MetricCard.tsx`    | 指标卡：值、比较、目标与迷你趋势                                           |
| `ui/charts/palette.ts`        | `--chart-1..5` 取色与 `spec.colors` 的覆盖（值在进 `<style>` 前再校一次）  |
| `ui/charts/axis.ts`           | 数值格式、轴域与刻度格式、左右轴归属                                       |
| `ui/charts/family.ts`         | `FamilyProps`（每个家族收到的同一份 props）与类目标签器 `useCategoryLabel` |

- 家族组件都不导出到包外：`/ui` 的出口只有 `AnalysisChart`，换一种画法是换 `charts/` 下的文件，不是换一个公开 API。（见 test/analysisChart.test.tsx「AnalysisChart」）

## 图表怎么被读出来

- **画出来的部分是一张有名字的图，数字在它旁边。** recharts 默认打开 `accessibilityLayer`，给根 `<svg>` 挂上 `role="application"` 与 `tabIndex={0}`：读屏会因此退出浏览模式、把按键交给一个没有任何键盘处理的元素，而那个元素里只有坐标轴刻度、一个空 `<title>` 和一个空 `<desc>`——查询回答的数字一个都不在。`charts/asImage.ts` 把这一层关掉，换成 `role="img"` 加 `aria-label`；热力图与漏斗是自绘的 `div`，同样以 `role="img"` 加名字整块作为一张图。图里于是没有任何可聚焦的元素；
- **名字说的是"画的是什么"**：`{图型}：{度量}，按 {类目}`（`label.chart.figure`），度量与类目都按别名回 `AnalysisView.schema` 取列标题，取不到就只剩图型名。指标卡没有类目，用 `label.chart.figure.plain`；它的迷你趋势线另有一个 `label.chart.sparkline`；
- **可读替代来自同一份投影。** `charts/reading.ts` 从 `ChartData`——而不是旁边那份行投影——生成 `{name, header, rows}`，`ChartReadingTable` 用注册表的 `Table` 以 `sr-only` 渲染在图旁边，于是屏幕上画了什么、读屏就读到什么，两者不会各说各话：图上有、这里没有的值，只能意味着某个家族画了内核没整形过的东西。数值按该系列所在坐标轴的格式打印（`percent` 轴上的 0.25 读作 25%），空洞读作 `label.summary.unavailable`；
- **指标卡的值本来就是文字**，所以它整张卡不是图：值与带符号的比较照旧是可见文本，只有进度条背后的目标、比较的原值与趋势的每个点进 `sr-only` 表——那三样只有画出来的部分知道。（见 test/analysisChartA11y.test.tsx 与 test/accessibility.test.tsx「analysis, drawn as a chart」）
- **那根进度条就是注册表的 `Progress`**，不是一个靠内联 `width` 撑长度的 `div`：会填充的条本来就是 progressbar，角色、当前值与上下界只有真的那个才带得上。它以 `label.chart.target` 为名，`aria-valuetext` 用 `label.chart.target.reached` 把位置读成背后那两个数（`{value} of {target}`），而不是角色默认要念的那个百分比；目标为 0 时不做除法——没有可差的距离，要么到了要么没到。轨道高度在调用处抬到 2px（与导出进度同一个写法），`ui/components/**` 是上游的，不手改。（见 test/analysisChartA11y.test.tsx「the metric card still says its value out loud」）

## 刷新落在标题栏

- 分析工作台没有结果工具栏——它的结果是一张表或一张图，不是一排控件——所以刷新那个拆分按钮落在标题栏右组的视图级控件里（`WorkbenchShell` 的 `freshness` 槽），规则与措辞与 Record 的那一个完全相同，见 [README.md#刷新是一个拆分按钮](README.md#刷新是一个拆分按钮)。`QueryStrip` 上的「重试」是失败后的出口，与它不是一回事：一个是出错了再来一次，一个是没出错也每隔一段时间来一次。（见 test/refreshControl.test.tsx「every workbench offers the interval」）

## 被截断的分组要说出来

- 结果行数恰好填满 `limit` 时，屏幕上的行只是真实分组的一个前缀，于是这一屏的每个占比、每个百分比、每个扇区都是拿"已显示的部分"当分母算出来的。饼图是最坏的一种：它的全部含义就是"各部分占整体多少"，而整体已经不在图里了；
- 因此工作台把这条 warning（`analysis.result.at-limit`）交给 `WorkbenchShell` 的 `warnings`，状态条在**表格与图表之上**，两种布局各画各的，这一行是共同的，切换布局不会把它丢掉。措辞是"可能被截断"：聚合只回答了行数，"恰好等于上限"既可能是刚好这么多组，也可能是被截掉的前缀，判据见 [../kernels.md#compileanalysis-与-projectanalysis](../kernels.md#compileanalysis-与-projectanalysis)；
- 合计行照旧来自自己的无分组查询，所以它仍然覆盖全部——可见的几行加起来小于它们下面的合计，两个数都没错，正是这条 warning 要解释的事。（见 test/resultIssues.test.tsx「what the screen says about an analysis cut short」与 stories/view-engine 的 `CutShort`／`CutShortTable`）
