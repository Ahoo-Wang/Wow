# TODO

规则，先读再加：

- 只记**已经决定、但还没做**的事。尚无结论的产品问题不在这里，在 [decisions.md#搁置待议](decisions.md#搁置待议)。
- 每条都要写清 **为什么** / **做完的判据** / **落点**（design 里的哪一页，或哪个文件）。三者缺一就不是一条 TODO，是一句抱怨。
- 做完就**删掉**这一条，不打勾、不留归档。历史在 git 里。
- 改行为之前先看这里有没有对应项；有就接着做，别另起一条。

## 阶段 2：分析视图（裁定见 [decisions.md#D20](decisions.md#d20-分析视图的交互2026-09-22)）

批次是做事顺序，每批一个或几个 PR，合并后真浏览器逐控件复验。已合：批 1（#1720）、批 K（#1717）、批 2（#1716、#1718）、批 3（#1721、#1722）、批 4 托盘与卡片（#1723、#1729）、批 5 可视化两层与系列排序（#1725、#1727、#1732）、批 6 指标条件／展开链／只保留／公式与派生／多重排序（#1731）、批 7 探针行与三条口径（#1730）。

### 批 7b 改了就跑

- **为什么**：G7/G9/G14；D20 的运行节奏。内核与界面已在 `claude/ve-b7-auto-run`：`runtime/autoApply.ts`（`autoApplyDue`、300ms 合并）、`ViewRuntime.setAutoApply`、`ViewPreferences.autoRun` 与 `ViewEngine.setAutoRun`、托盘页脚的「改了就跑」、结果 `data-stale` 变淡；缺的是 UI 测试、故事与文档。
- **判据**：维度／指标／展开／只保留改了 300ms 后自动重算，范围仍走「应用」（范围改了就什么都不自动跑）；旧结果变淡不清空；关掉开关记进个人偏好、跨视图生效；新建的 UI 测试（`autoRun.test.tsx`）、故事 `RunsAsEdited`、`runtime.md`／`management.md`／`react.md`／`ui/analysis.md` 各说一段。
- **落点**：新建 `autoRun.test.tsx`（`test/` 下）、`stories/view-engine/AnalysisMetrics.test.stories.tsx`、`docs/design/{runtime,management,react}.md`、`docs/design/ui/analysis.md`。

### 阶段末：审查与复验

- **为什么**：每阶段的节奏是打磨 → 审查 → 重构（[progress.md](progress.md)）；批次合完要按五个维度（用户／研发／演进／可达性／文案）再看一遍分析视图。
- **判据**：真浏览器逐控件复验托盘、可视化两层、追问、改了就跑；`ui/analysis.md` 读一遍没有过时句；线索里没有一条其实已经做完；`progress.md` 重写成阶段 3 的起点。
- **落点**：`docs/design/{progress,todo}.md`、`docs/design/ui/analysis.md`。

### 线索（本阶段不做，或待产品口径）

- 加了维度之后，钉住的 `table.columns` 不会自动多出那一列——表格分不出新组（同一个仓库出现两行）。新维度是否自动进列表要产品口径；分组→列的正向映射在 `reshape` 里只删不加。
- 三个以上维度时 cartesian 只消费横轴与拆分两个，第三个报 `chart.group.unconsumed`；`fitCharts` 却仍把柱状图列为可用。多维要么进透视表（Q8），要么 `fitCharts` 说「最多两个维度」。
- 公式列没有数字格式（内核不知道两列金额之差还是金额），与旁边的「金额 的 合计」一列读法不同。
- 准入发现里的字段用的是 `field.name`（「给 status 一个值」）而不是显示名——整个包的惯例，要改是包级的决定。
- 「更多图型」折叠宿主扩展的图型：今天没有宿主扩展图型的入口，等有了再做；散点的坐标轴范围要先进模型（`ScatterSpec` 没有轴规格，所以没有坐标轴页签）。
- 故事 `Follow Up To Records` 偶发失败（三次里一次，重跑即过），`.storybook/vitest.setup.ts` 拦 ResizeObserver 事件后仍在；真因在查。
- 透视表（Q8）；精确的 M（Q7）；分析表冻结列；STDDEV／VARIANCE 与去重计数在 ES 上的近似提示按后端能力声明。
