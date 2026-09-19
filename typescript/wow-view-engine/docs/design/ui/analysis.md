# UI 层：Analysis 视图

分析编辑器、分析表格与图表。三种视图共用的骨架、状态条、值显示与 `FilterPanel` 见 [README.md](README.md)；图表规格见 [model-shapes.md#图表规格](../model-shapes.md#图表规格)，图表校验与整形见 [kernels.md#图表规则](../kernels.md#图表规则)。

## AnalysisChart 与 shapeChart

- 工作台交给 `AnalysisChart` 的是产生当前结果的那份图表配置（`ViewResult.config`），不是正在编辑的草稿：类目按别名找列取标签，草稿的别名在 Run 之前可能已指向别的列。图表只画内核已经整形好的数据：透视、合并"其他"、漏斗累计与转化率、热力图矩阵、比较值都在 `shapeChart` 里完成，`AnalysisChart` 只选标记与配色，换一个图表库不触碰任何规则。`AnalysisChart` 按 `spec.colors` 给系列或分类上色，其余按 `--chart-1..5` 顺序取用；
- 五档色相在亮暗两种模式下各自校过分离度与对比度。热力图与漏斗自绘，用图表库画它们的成本高于收益。`projectAnalysis` 只在 `layout === 'chart'` 时整形图表，因此切换 Table／Chart 是一次新的执行而不是重绘，`useAnalysisEditor.setLayout` 据此直接 apply；
- 其余改动等 Run。（见 test/analysisUi.test.tsx「AnalysisChart」「useAnalysisEditor」与 test/analysisChart.test.ts「shapeChart」）
