# TODO

规则，先读再加：

- 只记**已经决定、但还没做**的事。尚无结论的产品问题不在这里，在 [decisions.md#搁置待议](decisions.md#搁置待议)。
- 每条都要写清 **为什么** / **做完的判据** / **落点**（design 里的哪一页，或哪个文件）。三者缺一就不是一条 TODO，是一句抱怨。
- 做完就**删掉**这一条，不打勾、不留归档。历史在 git 里。
- 改行为之前先看这里有没有对应项；有就接着做，别另起一条。

## 交接（2026-09-23 换账号）：先把这一节做完

接手第一件事：按 [progress.md#上一个暂停点](progress.md) 看清 #1787／#1788 是否已合并。每一条合并前都在真实浏览器里对受影响的视图逐控件走一遍（亮／暗、1440 与窄屏），门禁逐条看退出码（`pnpm test` 末尾还有 `test:type`）。

- **把三个新真实场景接进来并开 PR**。
  - 为什么：用户要求 CRM、交易、定价各一个快照控制台与事件流分析台，仿补偿场景。三个子代理在各自 worktree 里做完了定义、系统视图、回归孪生与录制数据，**未推送**，且被要求不改共享文件。
  - 判据：三条分支（`claude/ve-scene-customer`、`claude/ve-scene-trade-order`、`claude/ve-scene-product-pricing`，worktree 在 `.claude/worktrees/agent-*`；若已不在，按同名分支或重做）合进一个或三个 PR；`stories/shared/AppShell.tsx` 的导航按目录加上「真实后端」下的 客户／交易订单／商品定价 各两项，「服务」一行写各自 host；`stories/README.md` 的真实后端一节补三段；默认 host 分别是 `http://localhost:8085`、`8088`、`8089`（集群地址写在注释里；内置浏览器解析不了 `*.svc.cluster.local`）；每个场景的每个系统视图在真实服务上亮暗走过（无截断、无原始 JSON／毫秒数、图表画出）；四道故事门禁全绿。
  - 落点：`stories/view-engine/` 下各场景文件、`stories/shared/AppShell.tsx`、`stories/README.md`。
  - 进度：**客户已完成并推送**（`origin/claude/ve-scene-customer` @ `91ba61434`，未开 PR）：`customer.ts`／`customerEvents.ts`、两个展示故事与回归孪生、录制服务 `customerService.ts`；共享文件只改了 `rowSource.ts`（录制服务算 `DISTINCT_COUNT`）。展示故事暂用 `current="snapshots"`／`"event-streams"`，接入时给 `AppShell` 的 `ScenePage` 加 `customer-snapshots`（「客户 · 快照控制台」，`ClipboardListIcon`，`view-engine-真实后端-客户-快照控制台--data-console`）与 `customer-event-streams`（「客户 · 事件流分析台」，`ActivityIcon`，`view-engine-真实后端-客户-事件流分析台--event-stream-console`）再换掉 `current`。待用户定：租户是列/维度还是宿主固定范围；年营业额单位（Schema 未写）。子代理报的缺陷：每月折线图最右「2026年9月」刻度在含 #1786 的 main 上仍被截（view-engine，另修）；CRM 服务 event schema 31 种事件标题全相同（服务侧）。**定价已完成并推送**（`origin/claude/ve-scene-product-pricing` @ `e1f311629`，未开 PR，没改共享文件）：导航加 `pricing-snapshots`（「商品定价 · 快照控制台」，`view-engine-真实后端-商品定价-快照控制台--snapshot-console`）与 `pricing-event-streams`（「商品定价 · 事件流分析台」，`view-engine-真实后端-商品定价-事件流分析台--event-stream-console`）。**阻塞：`localhost:8089` 的 CORS 预检不回 `Access-Control-Allow-Origin`**，浏览器里拿不到数据——要用户定：服务开 CORS，还是 `.storybook/main.ts` 加 Vite 代理。待用户定：单价按人民币、「半年内到期」窗口。又报：折线图首个月刻度不显示、末个月被截（#1786 后仍在，补偿「每月事件量」同样）；视图列表标题「事件流分析台」被截成「事件流分析...」。**交易订单已完成并推送**（`origin/claude/ve-scene-trade-order` @ `dbb623c07`，未开 PR）：导航加 `trade-order-snapshots`（「交易订单 · 快照控制台」，`view-engine-真实后端-交易订单-快照控制台--snapshot-console`）与 `trade-order-events`（「交易订单 · 事件流分析台」，`view-engine-真实后端-交易订单-事件流分析台--event-stream-console`），服务 `http://localhost:8088`。**客户与交易两个分支都改了 `rowSource.ts`（各自给录制服务加 `DISTINCT_COUNT`）**——接入时只留一份实现，另一份丢弃。待用户定：商品列用货号而非商品名；真实数据还没有「履约中」订单，以后是否补视图。又报：元素读成的徽标没有最大宽度，长标题会撑宽整列（包层）；交易服务 event schema 29 种事件标题全相同、快照 schema 多列一个从不填的 `orderItems`（服务侧）。
- **分析视图全面审查，修到企业生产交付级别**。
  - 为什么：用户原话「分析视图的 UI、UX 需要全面审查，还没有达到企业生产交付级别」「这些问题应该是你审查出的，而不是由我来主动发现」。审查已做完（真浏览器，main@810484eb6），完整清单在会话记忆 `view-engine-analysis-audit-2026-09-23`；#1786／#1787 已修掉其中的页脚位置、合计吸底、工具栏跳动、刻度截断、视图管理锁图标；`claude/ve-analysis-controls` 把图型选择器的「选项」从磁贴上的齿轮换成网格下一颗写着字的按钮，修掉、「前 N 组」的框与追问菜单的宽度。P0 还剩 7 条待修：按日图表时间轴倒序（投影层按时间升序）；指标卡大数字是截断可见桶之和；类目超过 5 个颜色重复（色板只有 5 色）；漏斗图可选却只报错；散点刻度重复、点被裁、无轴标题；换图型悄悄换指标、饼图无指标名与占比；截断饼图的占比口径没说。另有「组／行／条」用词要问用户（页脚按用户原话写的是「行」）。
  - 判据：把清单（P0／P1／P2，覆盖标题栏与「分析」开关、托盘各槽、已应用带、结果工具栏、可视化面板每页、每种图型（刻度、图例、颜色、留白、tooltip、暗色）、分析表、追问与下钻、页脚、加载／空／错误／截断、键盘与焦点、铺满、视图切换时的跳动、与记录视图同概念同表达、文案）先给用户看、拍板；P0／P1 全部修掉并有故事或测试守着；P2 进本页。
  - 落点：`src/ui/analysis/*`、`src/ui/workbench/AnalysisParts.tsx`、`src/ui/AnalysisTable.tsx`、`src/ui/AnalysisChart.tsx`、`src/ui/charts/*`、[ui/analysis.md](ui/analysis.md)。

## 阶段 3：仪表盘

从审计清单起：按五个维度（用户、研发、演进、可达性、文案）把仪表盘现状过一遍，清单先给用户看、拍板，再按批次写进这一节。

## 阶段 2 留下的线索（不做，或待产品口径）

- 加了维度之后，钉住的 `table.columns` 不会自动多出那一列——表格分不出新组（同一个仓库出现两行）。新维度是否自动进列表要产品口径；分组→列的正向映射在 `reshape` 里只删不加。
- 公式列没有数字格式（内核不知道两列金额之差还是金额），与旁边的「金额 的 合计」一列读法不同。
- 准入发现里的字段用的是 `field.name`（「给 status 一个值」）而不是显示名——整个包的惯例，要改是包级的决定。
- 「更多图型」折叠宿主扩展的图型：今天没有宿主扩展图型的入口，等有了再做；散点的坐标轴范围要先进模型（`ScatterSpec` 没有轴规格，所以没有坐标轴页签）。
- 透视表（Q8）；精确的 M（Q7）；分析表冻结列；STDDEV／VARIANCE 与去重计数在 ES 上的近似提示按后端能力声明。
- 没有维度时结果只有一行，合计行把同一个数再说一遍；无维度时是否省掉合计行，要产品口径。
