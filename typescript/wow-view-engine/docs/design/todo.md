# TODO

规则，先读再加：

- 只记**已经决定、但还没做**的事。尚无结论的产品问题不在这里，在 [decisions.md#搁置待议](decisions.md#搁置待议)。
- 每条都要写清 **为什么** / **做完的判据** / **落点**（design 里的哪一页，或哪个文件）。三者缺一就不是一条 TODO，是一句抱怨。
- 做完就**删掉**这一条，不打勾、不留归档。历史在 git 里。
- 改行为之前先看这里有没有对应项；有就接着做，别另起一条。

## 首发前的门（npm 首发与阶段 6 开工之前）

- **全面审查过门，由用户明确点头**（用户 2026-09-24）：npm 首发与阶段 6（Wow 存储后端）开工之前，先做第二轮全面审查——架构质量（职责清晰、高内聚、低耦合、扩展性、可维护性）、企业级产品体验（UI 视觉、UX 交互）、功能的可用性、可访问性与易用性——处置完后交审查报告，**用户明确审查通过**才开始这两件事。
  - 为什么：这是一次重大发布；公开面一旦上 npm 就要背兼容性。
  - 判据：审查报告（含 Storybook 场景审查与就绪审计 P1 的处置）交给用户并得到明确通过；此前不发布、不开阶段 6。
  - 落点：[progress.md](progress.md)「这个暂停点」；Wow 仓的 `typescript/RELEASING.md`。
- **先重构到生产就绪，不留兼容债**（用户 2026-09-24：「尽早重构解决问题，避免以后再考虑兼容性债务」）：审查里「现在做还是以后做」默认现在做；公开面（[D29](decisions.md) 快照）上的破坏性改动趁首发前一次改到位，不为发布前的形态留兼容层（面向已部署 Wow 服务端的兼容除外）。
  - 判据：首发时 `docs/compat-debt.md` 里没有本包因发布前形态而欠下的条目。落点：本页各节、[decisions.md](decisions.md)。
- **采用查询模块的能力描述，替掉本包写死的上限与算子表**（查询模块重构 N5；协调会话 2026-09-25）：本包首发 npm 之前，定义准入、编译与托盘改读服务端声明的能力描述（能分哪些组、能算哪些指标、上限多少、百分位是否精确），不再写死 `maxAnalysisRows`、算子表与「百分位是近似值」。
  - 为什么：写死的上限与服务端守卫不一致已经出过错（见下「连真 Wow 服务端的端到端」第一条）；D41 箱线图的「近似值」也该按后端声明写。
  - 判据：能力描述落地后，本包的上限、算子与百分位精度都读它；端到端去掉手写的 `maxLimit` 仍通过。
  - 落点：查询模块的方案 documentation/designs/2026-09-24-query-target-architecture-design.md §11（尚未合入 main）；本包 `src/model/limits.ts`、`src/analysis/`、`src/filter/`。
  - 方案：[capabilities.md](capabilities.md)（数据源端口的 `describe`、定义 × 描述的收窄、缓存与重新验证、违规码、批次 C2～C6；Q1～Q3 已定，D47）。
  - 进度：C1 已合并（#3482）；C2 已落地，分两次合并（#3499 与余项，capabilities.md 第 12 节）；C3 已落地（#3517，第 14 节）；C4 已落地（第 15 节）；C5 已落地（第 16 节）；C6 已落地（补偿控制台 #3541，接入时撞到的 `ELEMENT_MATCH` 收窄缺陷由 #3540 修，第 20 节）。
- **就绪审计里本包的 P1**（2026-09-24 只读审计；本包这次不发 npm，所以不挡 9.2.0，但挡本包首发）——并入第二轮审查的清单，逐条变成带判据的 TODO 或拍板：
  - 严格 CSP：提示框色块的 `style=` 改 class，写 CSP 指南，加一个严格 CSP 下的故事。
  - 真人读屏走查（VoiceOver／NVDA）：纯键盘走查与 WCAG 2.2 AA 符合性声明已成文（文档站「视图引擎的可访问性」），读屏这一半见下面「可访问性」一节的第一条。
  - 视觉回归基线，Firefox／WebKit 跑一次。（连真服务端的端到端已落地，见下一节。）
  - 判据：每条要么合并、要么由用户拍板推迟到首发后并写进 [decisions.md](decisions.md)。落点：本页。

## Storybook 审查（2026-09-26）的处置

- **按道修 [review-2026-09-26.md](../../../storybook/docs/review-2026-09-26.md) 的 P0/P1**：
  - 为什么：P0 是数字的口径标错，P1 是状态不反映结果、表格被裁、标签压盖与宿主学不到接入，都挡首发审查。
  - 判据：报告里每条 P0/P1 的状态一栏写上合并的 PR 号，或由用户拍板推迟并写进 [decisions.md](decisions.md)。
  - 落点：报告本身（逐条标了道）；P0 由 #3606、P1-1 与 P1-10 由 #3619、P1-3 由 #3615（空合计带）与 #3630（面板按行长高、滚动提示）修掉。D51（视野外写出汇总）由 #3634、P1-5 与 P1-6 由 #3654 与 #3657（D53）、P1-2／P1-7／P1-8／P1-9 由 #3656（D55）修掉。P1-4 由 #3661（D56）与 #3663 修掉。P2 在报告里，首发后排。
- **对话框与弹层的函数式 `finalFocus`**：#3547 修了下拉菜单关闭时抢回已移走的焦点（Base UI 1.8.0 在 `finalFocus` 是函数时不看焦点是否已离开）；对话框、弹层若也传函数，可能是同一个竞态。
  - 判据：逐个查过，同样处理或说明不受影响，并有一个回归故事。落点：`src/ui/popups.tsx`。
- **图上的字：还没做的两处**（D53 落地时记下）：长柱上「最低 …」比柱子宽时压到两旁柱身（有底色描边，读得清，如「近 30 天」「双 11」）；多段堆叠柱多时栈顶合计仍每根都写，要不要也只标峰谷。
  - 判据：各自定下做法并落地，或写进 decisions 不做。落点：`src/ui/charts/cartesianMarks.ts`、`src/analysis/chartFamilies.ts`。
- **查询失败时仍显示上一次的结果**（故事道 P1-2 时提出）：条件改了而新查询失败，表格留着旧条件的行（`useRecordTable` 的「失败的刷新保留旧行」）。刷新失败时保留是对的；条件变了时要让人看出眼前是旧条件的结果。
  - 判据：第二轮全面审查里逐个筛选模式在真浏览器里验证：改条件后失败，界面说清「显示的是改之前的结果」或不再显示旧行；不清楚就修。落点：`src/react/useRecordTable.ts`、`src/ui/record/`。
- **场景描述里还有手写的数字**（故事道 P1-7 时记下）：如运营日报的「约 82%」「11 张」。
  - 判据：像导览一样由 `retail/guide.ts` 的做法从数据读出，或删掉数字。落点：`typescript/storybook/stories/view-engine/` 各场景的 docs 描述。
- **theme-check 检查宿主写的点击目标长度**（#3649 记下）：`--fve-sidebar-item-height`、`--fve-control-height` 宿主写得低于 24px 时，CSS 不拦，只靠文档。
  - 判据：`wow-view-engine theme-check` 对这两项低于 24px 报错，文档同步。落点：`theme-check/check.ts`。

## 连真 Wow 服务端的端到端（2026-09-25 落地后的余项）

端到端在 Wow 仓 `typescript/integration-test/test/view-engine/`，由 `typescript-contract.yml` 的同源契约作业对着同一提交构建的示例服务端（MongoDB）运行；覆盖面与本地跑法见那里的 README「View engine against the server」。落地时发现、没在那个 PR 里修的：

- **分析视图的时间轴也只补首尾之间的洞**：走势卡已经补到自己的窗口（`cardWindow`，D39），柱、线、面积与热力图的时间轴仍只补回来的首尾两桶之间——「近 30 天」而前五天没有记录时，轴从第六天开始。
  - 为什么：与走势卡同一个缺口；拆分与热力图的洞要按组合补，与卡的一维补法不同，没在修卡的 PR 里一起做。
  - 判据：条件在时间轴字段上钉住窗口、且结果完整（`absenceReader` 能担保）时，这几种图的时间轴补到窗口两端，可加的指标补 0 并标 `filled`。
  - 落点：`src/analysis/cartesian.ts`、`src/analysis/chart.ts`（热力图），[kernels.md](kernels.md)。
- **搜索在 MongoDB 后端不可用**：示例服务端的快照模型没有全文能力，`SEARCH` 被拒（`Model search is unsupported.`／`FULL_TEXT_TERMS`），端到端只验证了拒绝如实报出。要验证搜索真的命中，需要一台带 Elasticsearch 快照的服务端。
  - 判据：契约作业有了 ES 快照（或另起一个作业）后，搜索用例改为断言命中。落点：`recordView.test.ts`。

## 可访问性：2026-09-25 走查留下的

走查记录与逐条符合性表在文档站「视图引擎的可访问性」（`documentation/docs/{zh,en}/guide/typescript/view-engine-accessibility.md`）；小而局部的问题已随同一个 PR 修掉。下面是没修的，每条对应声明里一处「部分支持」或一条已知缺口，做完一条就改声明里那一格。

- **真人读屏走一遍**：VoiceOver + Safari（macOS）、NVDA + Firefox／Chrome（Windows）各走一遍记录工作台、分析、仪表盘与两种嵌入的核心任务。
  - 为什么：这次没有驱动 VoiceOver（打开它的 AppleScript 控制要改系统安全设置），读屏一半用的是 Chromium 自己算出的无障碍树（CDP `Accessibility.getFullAXTree`）、Playwright 的 ARIA 快照与播报区的变化记录——它们说明名字、角色、状态和播报文字对不对，说明不了读屏软件实际怎么念、念几遍、会不会被打断。
  - 判据：每个任务一行「能否完成 / 实际念出的话 / 与预期的差异」，差异修掉或各成一条 TODO；声明里「评估方法」一节补上读屏软件与版本。
  - 落点：文档站那一页的「评估方法」与走查表。
  - 清单：[screen-reader-walkthrough.md](screen-reader-walkthrough.md)——约 30 分钟的逐步走查（准备、十个任务的按键与应听到的话、记录表与严重程度），走完照它交回结果。
- **工具栏的漫游焦点漏进它打开的弹层**：Base UI 1.8 的 `Toolbar` 把 composite 上下文交给整棵子树，弹层也在其中；弹层里用 `useButton` 的控件自认是工具栏的一项，不再给自己 `tabindex`。复选框（`span`）因此整个 Tab 不到——这次在列设置与卡片设置的复选框上各补了一个明写的 `tabIndex` 止血；原生按钮与下拉框的触发钮在 Chromium 里照样可达，但在 Safari 默认设置（Tab 只停在带 `tabindex` 的控件与输入框）下被跳过，列设置的「固定」、汇总下拉都是。
  - 为什么：止血是逐个控件记着补，下一个放进工具栏弹层的复选框或开关还会再掉一次。
  - 判据：工具栏里的弹层内容不再在工具栏的 React 子树里（Base UI 的分离触发器 `Popover.createHandle`，或上游修复后升级），删掉两处明写的 `tabIndex`；一条故事在 WebKit 里从列设置的搜索框一路 Tab，每一行的复选框、固定钮与汇总下拉都停得到。
  - 落点：`src/ui/ResultToolbar.tsx`、`ColumnSettings.tsx`、`CardSettings.tsx`、`SortSettings.tsx`；[ui/README.md](ui/README.md)。
- **仪表盘面板的移动与缩放、列宽要有单指针的替代**（WCAG 2.2 2.5.7）：可排序的列表已经有了——抓手点一下弹出「移到…」菜单（[D49](decisions.md#d49-可排序的列表一律拖拽排序2026-09-25)）；剩下宽栅格里面板的移动与缩放、表头的列宽，今天指针只能拖，键盘各有等价物（抓手上的 Enter、Alt+←／→），只有指针、不能拖的人（头控、单开关、手抖）用不了。
  - 为什么：声明里 2.5.7 是「部分支持」，剩下的原因就是这两处。
  - 判据：面板「⋯」里有「左移、右移、加宽、变窄」一类，列设置里能填宽度，各有一条故事只用点击完成；2.5.7 改为「支持」。
  - 落点：[ui/record.md](ui/record.md)、[ui/dashboard.md](ui/dashboard.md)。
- **列宽的把手只有 8px 宽**（WCAG 2.2 2.5.8）：表头右缘的拖动区 `w-2`，紧挨着排序按钮，既不够 24px、也不满足间距例外。
  - 判据：拖动区在不压住排序按钮的前提下达到 24px（比如向两侧各伸出、且不与相邻按钮的 24px 圆相交），或上一条的点击替代落地后按「等价控件」例外成立；声明改为「支持」。
  - 落点：`src/ui/record/ColumnResizer.tsx`、[ui/record.md](ui/record.md)。
- **只改外观的编辑也重跑查询，播报也跟着念**：列宽（Alt+←／→ 每一步）、列顺序、固定、汇总都走 `editAndApply`，每一步都重发一次查询，播报区连说「正在查询」「共 N 条记录」；而新的列宽一次也没说。
  - 为什么：读屏用户按一下方向键听到三句与这一下无关的话；查询也白跑。
  - 判据：只动呈现的编辑不重发查询（汇总变了才发聚合），Alt+←／→ 每一步说一次「〈列〉宽 N 像素」一类的话、不说查询；故事断言按三下只有三句宽度的播报、没有查询。
  - 落点：`src/react/useRecordTable.ts`、`src/ui/record/ColumnResizer.tsx`、[ui/record.md](ui/record.md)。
- **仪表盘的两件事不出声**：改筛选条上的值（或交叉筛选）之后，面板各自重跑，板子的播报区什么也不说；面板查询失败也只在面板里画出来，没有播报。
  - 为什么：读屏用户改完筛选听不到任何回应，也不知道有面板失败，要逐块走过去看（WCAG 4.1.3 目前记「支持」是因为记录与分析视图都播报，这一处是差距）。
  - 判据：筛选落定后在板子的那一个播报区说一次（「已按〈筛选〉筛选，N 个面板已更新」一类，措辞走目录、双语），有面板失败时同一句带上「M 个面板没能加载」；一个板一次、不按面板逐条念；故事断言播报文字。
  - 落点：`src/ui/dashboard/`（等 PR3 合并后再动）、[ui/dashboard.md](ui/dashboard.md)。
- **表格与小图的名字**：记录表没有可及名字（读屏的表格列表里是一串「表格」，一块板上有几个记录面板时分不开）；指标卡的走势小图没有摘要句（别的图都有），它的数据表首列表头是「类别」而不是维度名。
  - 判据：记录表以视图名（工作台）或面板标题（仪表盘）命名；指标卡小图有与其他图同一套的摘要句，数据表首列用维度的显示名；各有测试。
  - 落点：`src/ui/RecordTable.tsx`、`src/ui/charts/`、[ui/analysis.md](ui/analysis.md#图表怎么被读出来)。
- **窄列里溢出的单元格盖住焦点**（WCAG 2.2 2.4.11）：列宽比内容窄时（存下来的列宽，或窄屏），订单号那一格的文字加「复制」按钮溢出到右边一格，右边那格的底色把聚焦的「复制」盖得只剩一条边（运单宽表，900px 宽）。溢出再多一点就整颗看不见。
  - 判据：单元格不向相邻格溢出（截断加省略号、提示框给全文，或行内按钮留在格内），聚焦的控件四角都在自己格里；一条故事在窄宽度下把每一颗「复制」聚焦一遍，量它正中那一点是它自己。
  - 落点：`src/ui/record/cells.tsx`、`src/ui/CopyButton.tsx`、[ui/record.md](ui/record.md)。
- **视图的面不声明自己的语言**（WCAG 2.2 3.1.2）：措辞目录是中文、宿主页面是英文（或反过来）时，`.fve-root` 上没有 `lang`，读屏按宿主的语言念中文。
  - 判据：面按它用的措辞目录写 `lang`（宿主可覆盖），弹层随之带上；测试断言中英两种目录下的 `lang`。
  - 落点：`src/ui/ViewSurface.tsx`、`src/ui/popups.tsx`、[ui/README.md](ui/README.md#措辞与-messagesprovider)。
- **看得见的快捷键提示**：Alt+←／→ 调列宽只写在 `aria-keyshortcuts` 里，Shift+Enter 选一段只在读屏的描述里；看得见屏幕、只用键盘的人无从得知。
  - 判据：表头聚焦时的提示框（或表格设置里的一行）写出列宽键；分析表的「按住 Shift 选一段」在表格布局下看得见；措辞双语。
  - 落点：`src/ui/record/SortableHeader.tsx`、`src/ui/AnalysisTable.tsx`。

## Storybook：真实交易订单场景

- **七批按方案做**（Wow 仓 [typescript/storybook/docs/scenarios.md](../../../storybook/docs/scenarios.md)，Q1～Q4 与首页验收 6.3 已定）：零售数据集与生成器（第 1 批，在做）→ 数据源加速与补语义 → 记录、分析、事件流的业务场景 → 仪表盘与嵌入页 → 目录与导览 → 跟着 ECharts B、C、E 补展示 → 首页换成只读的运营日报（必做）。
  - 为什么：用户 2026-09-24——夹具要面向真实交易订单场景，尽量体现本包的能力，分析视图面向真实的数据分析。
  - 判据：以方案为准；七批做完后按第一性原理做一次完整 review（领域专家、架构、前端、数据分析、UI/UX 五个视角，真浏览器逐场景走查），处置后报告给用户，并入上面的第二轮审查。
  - 落点：`typescript/storybook/stories/view-engine/retail/`；方案页。

## 补偿控制台留下的引擎缺口

补偿控制台按方案八批重构完（Wow 仓 [compensation/dashboard/docs/design/view-engine-rebuild.md](../../../../compensation/dashboard/docs/design/view-engine-rebuild.md)「批 7 的记录」与验证报告）；走查里的引擎缺口已合并的不再列，余下这几条：

- **四种结局画成一张四条线的走势图**：要么 Wow 查询允许对数组元素写指标条件（今天拒绝：`METRIC_FILTER_ELEMENT_MATCH`、`METRIC_FILTER_ARRAY_FIELD`），要么展开元素时允许按根字段（事件流的 `createTime`）分组（今天报 `analysis.field.outside-scope`）。N1～N3 都没有改变这两点。
  - 为什么：控制台只能画四张各带走势的指标卡，看不出结局之间的相对走势。判据：补偿概览的「流入与结局」写成一张按事件名拆开的日直方图，对真服务答得出。落点：Wow 查询目标架构；本包 `analysis/`。
- **直角坐标图的拆分系列读选项的 `tone`**：饼已按语气取色（[ui/analysis.md](ui/analysis.md)「饼的类别穿选项的语气」）；按带语气的枚举拆开的柱与线仍按次序取色位。
  - 为什么：同一个状态在饼上是红的、在拆分的柱上是某个色位，读者要重新对一遍图例。N1～N4 正在改图型，本条等它们合并后接，免得冲突。判据：按带 `tone` 的枚举拆分时系列颜色与徽标同语气，同语气的两条取色位。落点：`ui/charts/cartesianPlan.ts`、`timeOption.ts`（`toneColor`）。

## D22 标了「以后」的几项（线索）

- 联动筛选、卡片内筛选（「全局筛选」）；按列的点击行为（「点击」）；整板 PDF（「运维」）；订阅、版本历史、验证、缓存要服务端，归阶段 6。
  - 为什么：它们只在 decisions 里有半句话，排下一个阶段时看不到（审查 X-11）。
  - 判据：排进某个阶段时各自成为一条带判据的 TODO，或进 [decisions.md#搁置待议](decisions.md#搁置待议)；那时删掉这一条。
  - 落点：本页。
- 改变窗口尺寸或从板子「返回」重新挂载的第一帧，网格会短暂比容器宽（随即恢复）——迁移前看到、未处理；第二轮审查时复现，确认后修或删掉这一条。

## 阶段 5：内置多主题

- **四批（5A～5D）都已合并，剩阶段审查与收尾**（[phase5-themes.md](phase5-themes.md) 第 4 节，裁定 [D30](decisions.md#d30-阶段-5-内置多主题的十条裁定2026-09-24)）：
  - 为什么：批次的判据各自由测试守住了（对比度矩阵、`test/presetContrast.test.ts`、`verify-package` 的预设与桥接检查），但方案页还单独立着，阶段的五维审查也还没做。
  - 判据：按惯例先把架构、代码质量、UI、视觉、UX 五个维度的审查清单给用户看，处置完后把 [phase5-themes.md](phase5-themes.md) 并入 [ui/README.md#主题弹层与明暗](ui/README.md#主题弹层与明暗)，删掉方案页与这一条，并重写 [progress.md](progress.md)。
  - 落点：[phase5-themes.md](phase5-themes.md)、[ui/README.md](ui/README.md)、[progress.md](progress.md)。

## 内置主题目录

- **六批按方案做**（[themes.md](themes.md) 第 7 节，裁定 [D35](decisions.md#d35-内置主题目录与三条轴的四条裁定2026-09-24)）；每批的完整判据以方案为准，这里只列线索：
  - 为什么：用户 2026-09-24 要内置常用、经典风格的主题；宿主研发选，引擎只暴露属性、prop 与 CSS 入口。
  - T6 阶段审查与收尾。
  - porcelain 走查剩下的三件（[D43](decisions.md#d43-主题可以说面怎样分层控件怎样画2026-09-25)，themes.md 第 7 节「porcelain 走查与四个缺口」）：描边按钮也能填色（先让按钮在元素上说出 variant）。徽标的边已定保留（`badge-edge`）、选中项的着色已是角色（`row-selected`，[theme-architecture.md](theme-architecture.md) 4.8），各预设在重调批里设。
  - 判据：每套 × 每种明暗过对比度矩阵与色板门；neutral 在默认密度、默认约定下像素不变；每批 PR 写 CSS gzip 实测数。
  - 落点：[themes.md](themes.md)；做完一批删一行，全部做完后把方案页并入 [ui/README.md](ui/README.md)，删掉方案页与这一条。

## 主题架构重构

- **按方案逐批做**（[theme-architecture.md](theme-architecture.md) 第 9 节，裁定 [D46](decisions.md#d46-主题架构重构五条结构一张登记表2026-09-25)）；每批的完整判据以方案为准，这里只列线索：
  - 为什么：首发前把主题的结构一次改到位（品牌是输入、三层、角色、登记表、图表读角色）；本包在 `HELD_BACK`，不欠兼容。
  - S1 登记表已合并（#3476），S2 三层（含控制台的 G16：宿主 Tailwind 与引擎样式的先后，#3518）、S3 角色（theme-architecture.md 4.8，#3528）、S4 品牌是输入（2.7，#3533；`brand` 预设删掉）、S5 图表读角色（6.7，#3536）、S9 porcelain 重调（9.1）、S8 azure 重调（9.2）与 S11 contrast 重调（9.4）已完成，S8、S9 报出的机制缺口也已修（9.3）；首发收敛为四套预设（`neutral`、`azure`、`porcelain`、`contrast`；2026-09-25 删掉 `slate`、`graphite`、`fjord`）。
  - 结构批 S6（#3635，宿主主题指南与 `acme.css`）、S7（#3643，`theme-check` 命令）已完成；D46 各批只剩 S6 清单里的「密度的长度对宿主开放」（要改 `styles.css` 与登记表）。
  - 两次重调报出的机制缺口已在机制批修了（theme-architecture.md 9.3）：角色的**链接**（`<role>-link`，在面上解析，随品牌色与宿主的 `--fve-primary`）、侧栏当前项的边与浮起、菜单与选择框的已选项、描边按钮悬停的边与字、看板筛选芯片的高度（`filter-height`）；azure、porcelain 已用上。剩下的：
    - porcelain 暗色的菜单高亮链了主色后是 `#5AAEFF` 配深字，不再是 `#0058D0` 配白字——面上没有「暗色下更深一档的品牌色」，要两全得加一个角色或派生（9.3）。
    - 看板筛选芯片里打字的框必有 `input` 边（S9 的第二条）：要无边须先论证填色本身能当边界，再给配方一个角色。
    - 角色截图「提示框」（`ThemeRoles.test.stories.tsx` 的 `TooltipChip`）钉 neutral，却截到故事外壳顶栏的提示框（在 `<html>` 的预设下），应改截面内的触发器。
  - S11 查出的机制缺口（theme-architecture.md 9.4，没有给 contrast 开特例）：引擎自己的三个焦点配方（`FOCUS_ROW`、`FOCUS_CARD`、`FOCUS_INSET`：表格行、记录卡片、看板面板的滚动体）不读 `focus-width`／`focus-offset`，是 1px `ring` 加 `focus-halo`，而光晕是控件轮廓与控件之间的那道间隔、不能同时当行的粗边——contrast 的这三处因此是 1px；选中行没有颜色以外的标记角色（左侧色条或加粗的边），今天靠行里勾上的复选框；contrast 的菜单高亮没用 9.3 的链接，仍是字面值、不跟品牌色（要跟时加 `highlight-link`）。登记表 `PENDING` 已清空（S11 还清 contrast 暗色的六条），机制留着给以后的重调批。
  - 判据：S1～S7 每批全部截图逐像素相同（只有方案里明说的例外）；重调批只改它那一套的基线，PR 里并排给出改前改后。
  - 落点：[theme-architecture.md](theme-architecture.md)；做完一批删一行，全部落地后并入 [themes.md](themes.md) 与 [ui/README.md#主题弹层与明暗](ui/README.md#主题弹层与明暗)，删掉方案页与这一条。

## 只读的板不挂拖动的触摸监听

- **只读的仪表盘不再给每块面板挂非 passive 的 `touchstart`**：
  - 为什么：react-grid-layout 在拖动与缩放都关着时仍把每个格子包进 `DraggableCore`、给缩放角包一个，每块面板两个非 passive 的 `touchstart`（T5 的 Linux WebKit 剖析里看到，[themes.md](themes.md) T5 落地记录）。触屏上从面板开始的滚动要等主线程答完它，图表正在画时就是卡顿；桌面浏览器不受影响。
  - 判据：读的时候（非搭建）面板上没有 `touchstart`／`touchmove` 监听，摆放与今天逐像素相同（`calcGridItemPosition` 同一套算法），进入与退出搭建不重挂面板（图表不重建）；截图基线不变。
  - 落点：`src/ui/DashboardGrid.tsx`、[ui/dashboard.md](ui/dashboard.md)。

## 分析视图：释放 ECharts

- **五批按方案做**，A（#3334）、B（#3341）、C（#3365）、D（#3331）、E 已做，剩阶段审查（[analysis-echarts.md](analysis-echarts.md) 第 3 节，裁定 [D33](decisions.md#d33-分析视图释放-echarts-能力的九条裁定2026-09-24)）；每批的完整判据以方案为准，这里只列线索：
  - 为什么：用户 2026-09-24 的方向，首个大版本前分析视图要到企业 BI（Metabase、Superset、Grafana、Tableau）的水准；审计见方案第 1 节——缩放、框选、图例点选、花纹、采样、导出图片都还没有。
  - 首发后的线索（Q59 整段对比、注释等）见方案第 3 节末；新图型已由 D41 提到首发前，见下一条；每批 PR 写图表块 gzip 实测数。
  - 落点：[analysis-echarts.md](analysis-echarts.md)；做完一批删一行，五批与阶段审查做完后把方案页并入 [ui/analysis.md](ui/analysis.md)、[model-shapes.md](model-shapes.md)、[kernels.md](kernels.md)，删掉方案页与这一条。

- **D41：除了要后端的，全部图型都加**（[decisions.md](decisions.md#d41-除了要后端的全部图型都加2026-09-25)，分四个 PR，线索在 [analysis-echarts.md](analysis-echarts.md) 第 6 节）：统计（箱线图、刻度盘、雷达、平行坐标）→ 层级与流向（旭日、树、桑基等）→ 时间（日历热力图、河流图）→ 地理（中国省级地图）。
  - 为什么：用户 2026-09-25 修订 D33 Q55。
  - 判据：四个 PR 都合并，每种图有适合规则、内核整形、读屏表与摘要、导出、零售故事与孪生；每个 PR 写一次包体实测。
  - 落点：[analysis-echarts.md](analysis-echarts.md) 第 6 节；做完一个 PR 删掉第 6 节里对应的一行。

## 需要后端的图型与分析

D41 定下「除了需要后端支持的，全部都需要增加」；下面这些当时 Wow 聚合算不出，查询模块重构会话把它们记为 N1～N6（方案 documentation/designs/2026-09-24-query-target-architecture-design.md §11）。wow-client 已有 DATE_PART（#3524，N2）、元素里的 SEARCH（#3525，N4）、FIRST／LAST（#3532，N1）、DATE_DIFF 与 EXPRESSION 条件（#3539，N3）；本包按 N2 → N1 → N3 → N4 一项一个 PR 采用，采用一项删一行。按日期部件分组（N2）已采用；元素内检索（N4）已采用：只在描述有 `elements[].search` 时提供（见 D39）；两个时刻之差（N3）已采用：指标、箱线图、直方分组与「距另一时刻」的条件（见 test/dateDiff.test.ts、test/durationUi.test.tsx、test/durationCondition.test.tsx）；期初值、期末值与 K 线图（N1）已采用：指标卡的汇总方式与先后、「补齐 K 线的四个数」、涨跌配色、读屏表与摘要（见 test/firstLast.test.ts、test/candlestickUi.test.tsx）。N2 的落点：定义准入、描述收窄、托盘的周期选择、周期轴与热力图「星期 × 时段」、Storybook 零售数据按真实下单时间分组（见 [kernels.md](kernels.md)、test/datePart.test.ts）。

- **能力描述**——N5，见上面「首发前的门」。

## 阶段 2 留下的线索（不做，或待产品口径）

- 准入发现里的字段用的是 `field.name`（「给 status 一个值」）而不是显示名——整个包的惯例，要改是包级的决定。
- 「更多图型」折叠宿主扩展的图型：今天没有宿主扩展图型的入口，等有了再做。
- 透视表（Q8）；精确的 M（Q7）；分析表冻结列；STDDEV／VARIANCE 与去重计数在 ES 上的近似提示按后端能力声明。
