# UI 层：Analysis 视图

分析编辑器、分析表格与图表。三种视图共用的骨架、状态条、值显示与 `FilterPanel` 见 [README.md](README.md)；图表规格见 [model-shapes.md#图表规格](../model-shapes.md#图表规格)，图表校验与整形见 [kernels.md#图表规则](../kernels.md#图表规则)。

## AnalysisChart 与 shapeChart

- 工作台交给 `AnalysisChart` 的是产生当前结果的那份图表配置（`ViewResult.config`），不是正在编辑的草稿：类目按别名找列取标签，草稿的别名在 Run 之前可能已指向别的列。图表只画内核已经整形好的数据：透视、合并"其他"、漏斗累计与转化率、热力图矩阵、比较值都在 `shapeChart` 里完成，`AnalysisChart` 只选标记与配色，换一个图表库不触碰任何规则。`AnalysisChart` 按 `spec.colors` 给系列或分类上色，其余按 `--chart-1..5` 顺序取用；
- 五档色相在亮暗两种模式下各自校过分离度与对比度。热力图与漏斗自绘，用图表库画它们的成本高于收益。`projectAnalysis` 只在 `layout === 'chart'` 时整形图表，因此切换 Table／Chart 是一次新的执行而不是重绘，`useAnalysisEditor.setLayout` 据此直接 apply；
- 其余改动等 Run。（见 test/analysisUi.test.tsx「AnalysisChart」「useAnalysisEditor」与 test/analysisChart.test.ts「shapeChart」）

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

- 家族组件都不导出到包外：`/ui` 的出口只有 `AnalysisChart`，换一种画法是换 `charts/` 下的文件，不是换一个公开 API。（见 test/analysisUi.test.tsx「AnalysisChart」）

## 刷新落在标题栏

- 分析工作台没有结果工具栏——它的结果是一张表或一张图，不是一排控件——所以刷新那个拆分按钮落在标题栏右组的视图级控件里（`WorkbenchShell` 的 `freshness` 槽），规则与措辞与 Record 的那一个完全相同，见 [README.md#刷新是一个拆分按钮](README.md#刷新是一个拆分按钮)。`QueryStrip` 上的「重试」是失败后的出口，与它不是一回事：一个是出错了再来一次，一个是没出错也每隔一段时间来一次。（见 test/refreshControl.test.tsx「every workbench offers the interval」）

## 被截断的分组要说出来

- 结果行数恰好填满 `limit` 时，屏幕上的行只是真实分组的一个前缀，于是这一屏的每个占比、每个百分比、每个扇区都是拿"已显示的部分"当分母算出来的。饼图是最坏的一种：它的全部含义就是"各部分占整体多少"，而整体已经不在图里了；
- 因此工作台把这条 warning（`analysis.result.at-limit`）交给 `WorkbenchShell` 的 `warnings`，状态条在**表格与图表之上**，两种布局各画各的，这一行是共同的，切换布局不会把它丢掉。措辞是"可能被截断"：聚合只回答了行数，"恰好等于上限"既可能是刚好这么多组，也可能是被截掉的前缀，判据见 [../kernels.md#compileanalysis-与-projectanalysis](../kernels.md#compileanalysis-与-projectanalysis)；
- 合计行照旧来自自己的无分组查询，所以它仍然覆盖全部——可见的几行加起来小于它们下面的合计，两个数都没错，正是这条 warning 要解释的事。（见 test/resultIssues.test.tsx「what the screen says about an analysis cut short」与 stories/view-engine 的 `CutShort`／`CutShortTable`）
