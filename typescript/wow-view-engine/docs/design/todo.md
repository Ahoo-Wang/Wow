# TODO

规则，先读再加：

- 只记**已经决定、但还没做**的事。尚无结论的产品问题不在这里，在 [decisions.md#搁置待议](decisions.md#搁置待议)。
- 每条都要写清 **为什么** / **做完的判据** / **落点**（design 里的哪一页，或哪个文件）。三者缺一就不是一条 TODO，是一句抱怨。
- 做完就**删掉**这一条，不打勾、不留归档。历史在 git 里。
- 改行为之前先看这里有没有对应项；有就接着做，别另起一条。

## 阶段 2：分析视图（裁定见 [decisions.md#D20](decisions.md#d20-分析视图的交互2026-09-22)）

批次是做事顺序：批 1 与批 K 不依赖任何结构改动，先派；批 2 是骨架；其余骑在批 2 之上。每批一个或几个 PR，合并后真浏览器逐控件复验。

### 批 1 一开屏就撞上的缺陷 + 词汇

- **为什么**：今天换一个图型或加一个维度，配置立刻变 error、图消失（F1/F2）；数字格式与语言不跟随（F4/F5/F6/K5）；饼图故事不画饼（F14）；图表布局下空结果无话（F12）；布局读 draft 而图表配置读结果（F13）；`AnalysisColumn.pinned` 无人读（F7，删）；`resultSchema` 合同与代码不符（F8）；`defaultAnalysisConfig` 签名与文档不符（F9）。词汇整批换成 D20 的词，后面的新控件直接用新词。
- **判据**：换任何图型、加减任何维度都不产生 issue（`setChartType` 按 `CHART_FAMILY` 建家族子对象并按维度自动装槽）；`metricFormat(metric, field)` 在内核，AVG 两位小数、记录数整数、去重计数不带货币；坐标轴与提示按 `numberFormat` 与界面语言；饼图故事画出饼；空结果一句话「没有符合条件的组」；目录键按 D20 的词（范围／展开／维度／指标／汇总方式／记录数／显示名／前 N 组／合计行／可视化），中英同步，`describeConfig` 与表头用显示名（同字段两个汇总方式不同名）。
- **落点**：`src/analysis/{chart,defaults,project}.ts`、`src/react/useAnalysisEditor.ts`、`src/ui/{AnalysisEditor,AnalysisChart,AnalysisTable}.tsx`、`src/ui/messages/analysis.ts`、`src/ui/charts/*`、`docs/design/{kernels,model}.md`、`stories/view-engine/*Analysis*`。

### 批 K 内核与 Wow 聚合语义对齐

- **为什么**：Wow 的 `elements` 是由外到内的链，展开后分组、指标、指标条件与元素条件的字段名相对当前元素（`sku` 而不是 `lines.sku`），根字段在元素域被拒；内核的 `analysisScope`／`compileGroup`／`compileMetric`／`compileElement` 把每个 path 当根下并列数组、原样发出 `path.field` 且允许根字段——发到 Wow 会被拒。TERMS 不设 `missingKey` 时 Wow 把没有该值的记录整条丢掉，且只允许单值字符串字段。
- **判据**：能力 `elements[].path` 按链解析（第 i 层相对第 i−1 层，最多 5 层），最内层决定维度／指标的字段域；配置继续存全名，编译按链剥前缀；有 `elements` 时分组／指标／指标条件引用根字段报 error（新 issue code）；`missingKey` 只允许单值字符串字段（kind 自述），字符串 TERMS 的缺省配置带目录的「（空）」；`dense` 规则不变；`compileAnalysis` 的测试给出与 Wow DSL 测试同形的查询（`state.orders → lines`，字段 `sku`）；`kernels.md`／`model.md`／`extension.md` 改口径。
- **落点**：`src/analysis/{capability,compile,validateElements,validateGroups,defaults}.ts`、`src/model/{definition,analysis}.ts`、`test/analysis{Capability,Compile,Validate}.test.ts`、`docs/design/{kernels,model,extension}.md`。

### 批 2 一个数据工作台（D18-1）

- **为什么**：记录与分析同列表切换是本阶段第一项；今天两个工作台各自完整调用一次外壳（G1），`useWorkbench`／`useViewList`／`kindMismatch` 只认一个 kind（G2）。
- **判据**：八步——`useViewList` 收 `kinds` → 外壳不再被告知种类 → 两个正文抽成注入件（零行为）→ 漂移一批（warnings 一处、失败读法一处、分析侧 busy 与 live region、多余守卫）→ `DataWorkbench` 上线、删 `RecordWorkbench`／`AnalysisWorkbench`（不留别名）、D9 改成 `kinds`。每步外壳净减或持平（`WorkbenchShell.tsx` 不过 500 行）。动作槽只在记录注入件里（D20：分析视图没有业务操作）。
- **落点**：`src/react/{useWorkbench,useViewList}.ts`、`src/ui/{DataWorkbench,WorkbenchShell}.tsx`、`src/ui/workbench/{RecordParts,AnalysisParts}.tsx`、`docs/design/ui/README.md`、`docs/design/decisions.md`（D9）。

### 批 3 新建选种类、追问与下钻

- **为什么**：G3/G4/G5/G6；D20 的三项追问菜单；下钻是本阶段的核心价值。
- **判据**：「+」弹两项菜单（只能建一种时是按钮）；工作台持有的视图推广成 `held + origin`，「来自」条是外壳直接读 `workbench.held.origin` 画的组件，返回回到原来那次结果不重跑；点柱子／点行弹三项（查看这些记录／再按…拆一层／只看这一组），键盘路径走表格行；`analysis/drill.ts` 的 `bucketRange(unit, key, timeZone)`（K1，BETWEEN 减 1ms）；analysis 只交出条件，runtime/react 与 `defaultRecordConfig` 合成（K6）；`create` 撤掉重复的许可闸（H1）；`canDrill`（H2）；临时视图继承作用域（H4）；宿主可接管 `onDrilldown`（H5）。
- **落点**：`src/analysis/drill.ts`、`src/react/useWorkbench.ts`、`src/ui/workbench/{OriginBar,KindMenu,DrillMenu}.tsx`、`src/ui/{ViewList,AnalysisChart,AnalysisTable}.tsx`、`docs/design/{runtime,react}.md`、`docs/design/ui/README.md`。

### 批 4 托盘：范围 → 展开 → 维度 → 指标

- **为什么**：G17/G18/G12/G15/G16/F11/F15；D20 的托盘形态。
- **判据**：分析编辑器折在「分析」拆分按钮后面（与筛选面板同一折叠 hook），已保存视图打开时折起；槽是有名字的 `section`（范围独占一行；「展开」只在能力声明了 elements 时存在；维度、指标两列），窄屏纵向堆叠；维度卡片有粒度／区间宽度（K4：先看范围里已应用的日期条件，其次看结果跨度；手选优先）、卡片「…」里空值组名与补齐空桶（dense 只在唯一维度时可用）；指标卡片有汇总方式（含去重计数／百分位／任一值，任一值带「不保证每次一样」）与显示名，排序与前 N 组在指标槽底部；配置错误的出路指向对应的槽（F11）；范围槽标题写条件语法的简单／高级；无「简单／高级」托盘开关。
- **落点**：`src/ui/{AnalysisEditor → analysis/Tray}.tsx`、`src/ui/analysis/{RangeSlot,ElementsSlot,DimensionCard,MetricCard,SortRow}.tsx`、`src/react/useAnalysisEditor.ts`、`src/analysis/granularity.ts`、`docs/design/ui/analysis.md`。

### 批 5 可视化：左侧栏的图型网格与选项

- **为什么**：G10/G11/G13/G19/K3；D20 的可视化形态。
- **判据**：`fitCharts(config, view) → Record<ChartType, {available, reason?, recommended?}>`（能力决定在不在，配置决定灰不灰，理由复用 chart.* 文案）；结果工具栏「表格｜图表」分段（图型是表格时不出现）、「可视化」、导出；左侧栏面板替换视图列表，「‹」回去；第一层网格是 radiogroup（方向键、置灰可聚焦读原因、推荐记号、选中带 ⚙、「更多图型」折叠宿主扩展的）；第二层「数据／显示／坐标轴」按图族换内容，位置槽只列维度、度量槽只列指标，进入时按形态自动填好、改过的槽切图型时尽量保留；表格是一种图型；面板改动只重画；`WorkbenchFeatures` 加分析视图的项；两个维度默认拆分柱状或热力。
- **落点**：`src/analysis/fitCharts.ts`、`src/ui/analysis/{ChartPicker,ChartOptions,DataTab,DisplayTab,AxesTab}.tsx`、`src/ui/workbench/SidePanel.tsx`、`src/model/chart.ts`、`docs/design/ui/analysis.md`、`docs/design/kernels.md`。

### 批 6 指标的条件、公式与派生、只保留、展开槽

- **为什么**：Wow 的六种指标、指标条件、having、elements 在模型与内核里已 1:1 覆盖，缺的是编辑器入口（D20）。
- **判据**：指标卡片的漏斗按钮就地展开「只算满足条件的记录」（范围槽同一套 pill、跟随条件语法设置、只列标量字段、无「搜索」、元素域内无根字段），静止时常驻「只算 …」一行，不完整时不跑并直说，默认显示名规则，「复制『…』并加条件」；「添加指标 ▾」按能力多出「按公式」「按已有指标计算」（派生只能引用前面的、非任一值）；指标槽底部「+ 只保留…」（能力声明 having 时存在；字段是指标别名，不含任一值；文案说明没有值的组不保留）；多重排序（与记录视图同一个控件）；「展开」槽是链（每层一张卡带元素条件，「+ 再展开 ▾」只列最内层的数组字段，页脚写计数单位）。
- **落点**：`src/ui/analysis/{MetricCondition,MetricMenu,HavingRow,ElementsSlot}.tsx`、`src/react/useAnalysisEditor.ts`、`src/ui/messages/analysis.ts`、`docs/design/ui/analysis.md`。

### 批 7 改了就跑、探针行与口径文案

- **为什么**：G7/G8/G9/G14/K2/H3/H6；D20 的运行节奏与三条口径。
- **判据**：指标／维度／展开改了 300ms 合并后重算（走注入的环境计时器，下沉成 runtime 的 `autoApply` 与四条暂停理由并列），范围仍走「应用」；旧结果 `data-stale` 变淡不清空，工具栏与托盘底行各说一次「正在重新计算」；`ViewPreferences.autoRun` 记个人偏好（宿主整体存取，写进 management.md 合同），关掉时多一颗「运行」；条件不完整不跑；多要一行探针，「只显示了前 N 组，还有更多未列出」；合计行表头悬停「合计 = 范围内全部记录」；「只保留」说明没有值的组不保留；百分位表头「≈」。
- **落点**：`src/runtime/autoApply.ts`、`src/react/useAnalysisEditor.ts`、`src/ui/analysis/Tray.tsx`、`src/ui/AnalysisTable.tsx`、`src/model/config.ts`（preferences）、`docs/design/{runtime,management}.md`。

### 线索（本阶段不做）

透视表（Q8）；精确的 M（Q7）；分析表冻结列；STDDEV／VARIANCE 与去重计数在 ES 上的近似提示按后端能力声明。
