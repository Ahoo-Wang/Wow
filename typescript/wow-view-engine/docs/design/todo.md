# TODO

规则，先读再加：

- 只记**已经决定、但还没做**的事。尚无结论的产品问题不在这里，在 [decisions.md#搁置待议](decisions.md#搁置待议)。
- 每条都要写清 **为什么** / **做完的判据** / **落点**（design 里的哪一页，或哪个文件）。三者缺一就不是一条 TODO，是一句抱怨。
- 做完就**删掉**这一条，不打勾、不留归档。历史在 git 里。
- 改行为之前先看这里有没有对应项；有就接着做，别另起一条。

## 阶段 2：分析视图（裁定见 [decisions.md#D20](decisions.md#d20-分析视图的交互2026-09-22)）

批次是做事顺序，每批一个或几个 PR，合并后真浏览器逐控件复验。已合：批 1（#1720）、批 K（#1717）、批 2（#1716、#1718）、批 3（#1721、#1722）、批 4a 托盘（#1723）。

### 批 4b 托盘的第二层：显示名、粒度推荐、卡片菜单

- **为什么**：G15/G16/K4 是托盘判据里还没落的三项；F11 的出路与折叠已随 4a 落地。
- **判据**：维度与指标卡片可改显示名（G15，`alias` 仍是内部键，`describeConfig` 与表头用显示名）；日期维度的粒度按范围里已应用的日期条件、其次结果跨度推荐（K4，`src/analysis/granularity.ts`），手选优先；维度卡片「…」里空值组名（missingKey，只对单值字符串字段）与补齐空桶（dense 只在唯一维度时可用）；「+ 添加维度」不再列已分组的字段（与追问菜单的「再按…拆一层」同一规则）。
- **落点**：`src/ui/analysis/{DimensionCard,MetricCard}.tsx`、`src/analysis/granularity.ts`、`src/react/useAnalysisEditor.ts`、`docs/design/ui/analysis.md`。

### 批 5 可视化：左侧栏的图型网格与选项

- **为什么**：G10/G11/G13/G19/K3；D20 的可视化形态。第一层（`fitCharts`、网格、表格作为图型、面板改动只重画）在 `claude/ve-b5-visualize`，第二层（数据／显示／坐标轴三页签、`chartOptions.ts`、图例位置／数值标签／轴标题／热力图色阶落到渲染器）在 `claude/ve-b5-options`，两者的测试、故事与文档在补。
- **判据（剩余）**：`WorkbenchFeatures` 加分析视图的项（可视化面板可关）；「更多图型」折叠宿主扩展的图型；系列可拖动排序（今天只能增删）；漏斗阶段的显示名（`FunnelStages.items[].label`）可编辑；散点的坐标轴范围要先进模型（`ScatterSpec` 今天没有轴规格，所以没有坐标轴页签）。
- **落点**：`src/ui/analysis/{ChartPicker,ChartOptions,DataTab}.tsx`、`src/model/chart.ts`、`src/ui/WorkbenchShell.tsx`、`docs/design/ui/analysis.md`。

### 批 6 指标的条件、公式与派生、只保留、展开槽

- **为什么**：Wow 的六种指标、指标条件、having、elements 在模型与内核里已 1:1 覆盖，缺的是编辑器入口（D20）。
- **判据**：指标卡片的漏斗按钮就地展开「只算满足条件的记录」（范围槽同一套 pill、跟随条件语法设置、只列标量字段、无「搜索」、元素域内无根字段），静止时常驻「只算 …」一行，不完整时不跑并直说，默认显示名规则，「复制『…』并加条件」；「添加指标 ▾」按能力多出「按公式」「按已有指标计算」（派生只能引用前面的、非任一值）；指标槽底部「+ 只保留…」（能力声明 having 时存在；字段是指标别名，不含任一值；文案说明没有值的组不保留）；多重排序（与记录视图同一个控件）；「展开」槽是链（每层一张卡带元素条件，「+ 再展开 ▾」只列最内层的数组字段，页脚写计数单位）。
- **落点**：`src/ui/analysis/{MetricCondition,MetricMenu,HavingRow,ElementsSlot}.tsx`、`src/react/useAnalysisEditor.ts`、`src/ui/messages/analysis.ts`、`docs/design/ui/analysis.md`。

### 批 7 改了就跑、探针行与口径文案

- **为什么**：G7/G8/G9/G14/K2/H3/H6；D20 的运行节奏与三条口径。
- **判据**：指标／维度／展开改了 300ms 合并后重算（走注入的环境计时器，下沉成 runtime 的 `autoApply` 与四条暂停理由并列），范围仍走「应用」；旧结果 `data-stale` 变淡不清空，工具栏与托盘底行各说一次「正在重新计算」；`ViewPreferences.autoRun` 记个人偏好（宿主整体存取，写进 management.md 合同），关掉时多一颗「运行」；条件不完整不跑；多要一行探针，「只显示了前 N 组，还有更多未列出」；合计行表头悬停「合计 = 范围内全部记录」；「只保留」说明没有值的组不保留；百分位表头「≈」。
- **落点**：`src/runtime/autoApply.ts`、`src/react/useAnalysisEditor.ts`、`src/ui/analysis/Tray.tsx`、`src/ui/AnalysisTable.tsx`、`src/model/config.ts`（preferences）、`docs/design/{runtime,management}.md`。

### 线索（本阶段不做，或待产品口径）

- 加了维度之后，钉住的 `table.columns` 不会自动多出那一列——表格分不出新组（同一个仓库出现两行）。新维度是否自动进列表要产品口径；分组→列的正向映射在 `reshape` 里只删不加。
- 三个以上维度时 cartesian 只消费横轴与拆分两个，第三个报 `chart.group.unconsumed`；`fitCharts` 却仍把柱状图列为可用。多维要么进透视表（Q8），要么 `fitCharts` 说「最多两个维度」。
- 透视表（Q8）；精确的 M（Q7）；分析表冻结列；STDDEV／VARIANCE 与去重计数在 ES 上的近似提示按后端能力声明。
