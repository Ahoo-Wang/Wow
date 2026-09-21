# TODO

规则，先读再加：

- 只记**已经决定、但还没做**的事。尚无结论的产品问题不在这里，在 [decisions.md#搁置待议](decisions.md#搁置待议)。
- 每条都要写清 **为什么** / **做完的判据** / **落点**（design 里的哪一页，或哪个文件）。三者缺一就不是一条 TODO，是一句抱怨。
- 做完就**删掉**这一条，不打勾、不留归档。历史在 git 里。
- 改行为之前先看这里有没有对应项；有就接着做，别另起一条。

## 本轮范围：全局功能与记录视图推进到生产级

用户定下的范围只有两块——**全局功能（外壳）** 与 **记录视图**。Analysis 编辑器形态、Dashboard 编排不在本轮，它们的未决问题在 [decisions.md#搁置待议](decisions.md#搁置待议)。

每条的判据以这条基线读，**但只取适用的那几项**：一条只补回归的条目不必造文案，一条只看不改的条目没有并发路径。基线是——新出现的行为，其错误、空、权限、并发路径各有定义也有测试；新出现的界面键盘可达并过 axe；新出现的文案中英齐全；新出现的交互有故事既能手动操作也有回归；design 对应页与本页同步；全门绿。哪几项适用，由各条自己的判据说了算。

顺序：**打磨清单（先看后改）→ 筛选三条（日期时刻／IN 多值／软删除）→ ErrorBoundary → 列宽・隐藏字段排序・指针拖动回归 → 写入结局故事・视图管理拖动排序**。打磨排在新功能之前：先把已经有的做对，再加没有的。

## 布局重构（D12／D13，按这个顺序开 PR）

结构在 [decisions.md#d12](decisions.md#d12-一屏七块每块只回答一个问题) 定了，对比页第 17 版是定稿。每条的判据除下面写的，还有共同的一份：`test/accessibility.test.tsx` 过 axe；jsdom 钉结构（哪些槽位、什么条件下存在）、浏览器故事钉几何与层叠色；中英文案齐全；`docs/design/ui/README.md` 对应节同步；全门绿。

## 重构（小步，每步一个 PR，零行为变化）

## `max-lines` 存量豁免（拆到阈值以内就删掉 override）

绊线已落地：`src` 上限 500 代码行、`test` 上限 1200，只数代码行（跳过空行与注释）；vendored 的 `ui/components`、`ui/lib` 与纯文案目录 `ui/messages/` 不在管辖内。

下面九个文件当下就超线，各自在 `eslint.config.js` 里有一条 override。**上限不钉死在实测值，而是实测代码行 × 1.1 向上取到十位**：改个 bug 多两行不该把 CI 打红，但 10% 攒不回一个新主题。拆完一轮要重新实测、重新收紧这些数字。**新增一条 override 必须同时在这里新增一条 TODO**，否则绊线就成了摆设。

- **R8 `src/analysis/validate.ts` 下线**——为什么：骨架、预算、别名、元素域、指标、having、排序七套规则挤在 701 代码行里，加一条分析规则就得在这个文件里找位置。判据：按规则族拆成同目录的若干文件（如 `validateMetrics.ts`、`validateHaving.ts`、`validateBudget.ts`），`validateAnalysis` 的签名与 Issue 码不变，`test/analysis.test.ts` 不改断言；下线到 500 代码行以内并删除 `eslint.config.js` 里的 override。落点：`src/analysis/`、[kernels.md#analysis-内核的规则](kernels.md#analysis-内核的规则)。
- **R9 `src/runtime/viewEngine.ts` 下线**——为什么：抽走 `writeLedger.ts` 之后还有 635 代码行，注册表、打开/创建、权限与偏好缓存仍同在一个类里。判据：偏好缓存与系统实例的构造抽出独立模块，`ViewEngine` 公开面不变，`test/engine.test.ts` 不改断言；下线到 500 代码行以内并删除 override。落点：`src/runtime/`、[runtime.md#viewengine](runtime.md#viewengine)。
- **R10 `src/ui/AnalysisChart.tsx` 下线**——为什么：Cartesian、Pie、Scatter、Heatmap、Funnel、MetricCard 六个家族的渲染器连同调色与坐标轴工具同居 599 代码行，改一个家族要通读全部。判据：每个家族一个文件（`charts/` 子目录），调色与坐标轴工具单独成文件，`AnalysisChart` 只剩按 family 分派；`test/analysisChart.test.ts` 不改断言；下线到 500 代码行以内并删除 override。落点：`src/ui/`、[ui/analysis.md#analysischart-与-shapechart](ui/analysis.md#analysischart-与-shapechart)。
- **R11 `src/runtime/dashboardRuntime.ts` 下线**——为什么：子运行时编排、轮询调度、全局筛选注入三件事同在一个类里，511 代码行。判据：面板解析与子运行时生命周期抽出独立模块，`DashboardRuntime` 接口不变；`test/dashboardRuntime.test.ts` 不改断言；下线到 500 代码行以内并删除 override。落点：`src/runtime/`、[runtime.md#dashboard](runtime.md#dashboard)。
- **R12 `test/analysis.test.ts` 下线**——为什么：1882 代码行、十个顶层 `describe`，从 `defaultAnalysisConfig` 一路盖到图表投影，跑一次全量才知道改坏了哪一层。判据：按顶层 `describe` 拆成 `analysisValidate` / `analysisCompile` / `analysisProject` 等文件，用例一条不删；下线到 1200 代码行以内并删除 override。落点：`test/`。
- **R13 `test/reactHooks.test.tsx` 下线**——为什么：十一个钩子的套件共 1766 代码行，一个钩子的失败要在整份输出里找。判据：按钩子拆文件（`useFilterEditor`、`useRecordTable`、`useSaveCommands`、`useViewList` 各自成文件），共用夹具进 `test/fixtures*`；下线到 1200 代码行以内并删除 override。落点：`test/`、[react.md](react.md)。
- **R14 `test/filter.test.ts` 下线**——为什么：校验、编译、时间解析、树编辑四个主题合成 1359 代码行，时间用例尤其厚（描述已拆出 `describeFilter.test.ts`，额度同步重新实测收紧）。判据：时间相关的 `describe` 独立成 `filterTime.test.ts`，树编辑自成文件，用例一条不删；下线到 1200 代码行以内并删除 override。落点：`test/`。
- **R15 `test/recordWorkbench.test.tsx` 下线**——为什么：渲染、交互、保存、视图管理、布局五个主题合成 1254 代码行，其中保存与视图管理本就属于别的套件。判据：保存与视图管理的 `describe` 并入对应套件，工作台文件只留渲染、交互与布局；下线到 1200 代码行以内并删除 override。落点：`test/`、[ui/README.md#工作台骨架](ui/README.md#工作台骨架)。
- **R16 `test/analysisUi.test.tsx` 下线**——为什么：编辑器、图表、指标卡三套 UI 的套件合成 1212 代码行，只超线十二行，但正是靠一次次「只多几行」长到这里的。判据：图表与指标卡的 `describe` 独立成文件，编辑器留在原处；下线到 1200 代码行以内并删除 override。落点：`test/`、[ui/analysis.md](ui/analysis.md)。

（架构测试里「`*Workbench.tsx` 不得绕过 `useWorkbench` 直接 import 装配用的钩子」那一条已随 R3 落地。）

## 全局功能（外壳）

- **带时刻的日期条件筛不准边界**——为什么：日期条件只有日历，没有 `HH:mm:ss` 控件，`withTime` 于是直接取日历给的那一刻（零点），"今天下午三点之后"写不出来，边界上的记录要么全进要么全不进。判据：`withTime` 为真的字段在日历旁给出时刻输入（与日历同属一个控件，一次提交），未填时刻时的缺省语义**按 [decisions.md](decisions.md) 的 Q10 执行**，不在实现时二选一（它决定哪些记录命中，不是实现细节）；相对日期与预设不受影响；`test/filter*.test.ts` 覆盖边界两侧各一条。落点：`src/ui/filter/`、[ui/README.md](ui/README.md)、[kernels.md](kernels.md)。
- **软删除条件没了**——为什么：legacy 有 `DELETION` 条件（只看未删除／只看已删除／全都看），现在没有，列表会默不作声地混进已删除记录——这是数据口径的沉默，比少一个筛选项严重。判据：定义能声明这一维（能力决定它存不存在，D4），未声明时界面上没有这个东西；声明了则条件区有一处显式选择；**缺省口径与旧配置缺这一维时的读法按 [decisions.md](decisions.md) 的 Q11 执行**，并要在已应用条上说得出来；`test/` 覆盖三种口径各一条。落点：[model.md](model.md)、`src/filter/`、[kernels.md](kernels.md)。

## 打磨（先看后改，不凭想象改）

这一组来自 2026-09-20 在 Storybook 上对合并后的 `main`（ddeda440）逐屏过的一遍，条件按九项走：默认、展开筛选、空、加载、失败、窄屏、暗色、中文、长标题与多列；三个工作台加视图管理器、另存对话框，以及各自铺满屏幕的样子，每屏都跑了 axe。**先看后改这条对下面每一条同样成立**——每条都记了当时量到的数，改之前先自己再看一眼那一屏，数会随别的改动变，条目不会自己失效。量出来是缺陷而不是难看的，不在这一组里：它们分别记在[全局功能（外壳）](#全局功能外壳)与[小修](#小修)。

- **Analysis 一屏上有两个 primary**——为什么：量到 Apply（宽 71）与 Run（宽 60）都是 `rgb(23,23,23)`，上下只差 42px，两个都是不带 variant 的 `<Button>`。[版式](ui/README.md#版式三块一套间距一种选项控件)是一屏一个 primary，筛选面板那一节更直接："Apply 是同屏唯一的 primary 按钮"。Analysis 的条件块是筛选面板**加上**分析编辑器，于是两个提交按钮叠在一起、分量一样、离得比一组控件还近，看不出按哪一个会跑查询。判据：这一屏只留一个 primary，另一个降成 `outline`；**哪一个是 primary、两个提交算一次执行还是两次，按 [decisions.md](decisions.md) 的 Q12 执行**——现状是 `useFilterEditor.submit` 与 `useAnalysisEditor.submit` 都调同一个 `runtime.apply()`，就是一次执行，这一条只动分量，不要顺手改提交语义；`test/analysisUi.test.tsx` 按 variant／class 钉住同屏只有一个 primary（jsdom 不套样式表，量不到颜色）。落点：`src/ui/AnalysisEditor.tsx`、`src/ui/filter/FilterActions.tsx`、[ui/README.md](ui/README.md)、[ui/analysis.md](ui/analysis.md)。

## 打磨（第二轮：2026-09-21 的评审）

这一组来自 2026-09-21 对 `main`（d987fb99，#1573／#1574 之后）做的一次以用户体验为目标的评审：Storybook 里 Record 工作台全部 17 个故事各跑 1280（亮／暗）、768、375 三档，另按真实任务走了一遍（打开 → 读结果 → 加条件 → 应用 → 保存 → 另存 → 切视图 → 管理器改名／排序／设默认／删除 → 列／排序／每页条数 → 选行 + 批量 → 铺满 → 查询失败恢复 → zh-CN → 窄宿主）。数字来自 `getComputedStyle`／`getBoundingClientRect`，对比度按 WCAG 公式由 oklch 换算。**先看后改**对这一组同样成立。评审时 #1575–#1578 还是开着的 PR，它们管的不重复记。评审中量出来是**缺陷**而不是难看的，记在最前面。

### 缺陷（先做）

### 打磨

- **选中／悬停行上枚举徽章消失**——为什么：`secondary` 徽章底色 = 行选中 `bg-muted` = `oklch(0.97)`，**1.00:1**（暗色 0.269 同样 1.00）——几个状态共用一档 3% 灰，叠在一起就归零。判据：单元格徽章带 `border-border`（带语气的徽章由 #1580 改成实底，已不受影响）；浏览器故事量选中行上的徽章与行底 ≥1.5:1。落点：`src/ui/record/cells.tsx`。（侧栏那一半已由 L1 做掉：当前项白底 + 左侧 2px `primary` 条 + 中等字重，悬停走 `--sidebar-accent`。）
- **高级模式根分组有两套「添加」**——为什么：`Add in this group / Add a group / Add / Add a group` 四个入口挤在一屏。判据：根分组只留一套（分组块自己的那套），`test/filterPanel.test.tsx` 断言高级模式下"添加"入口的数量。落点：`src/ui/FilterPanel.tsx`、`src/ui/filter/GroupBlock.tsx`、[ui/README.md](ui/README.md)。
- **列设置的分区眼睛看不见**——为什么：三个分区只有 `aria-label`，屏幕上没有分区标题，钉到右侧的列于是只是"掉到底下"。判据：列设置每个分区有可见标题（与 `aria-label` 同一个词）；`test/columnSettings.test.tsx` 一条。落点：`src/ui/ColumnSettings.tsx`。
- **表头排序按钮两枚箭头相隔 40px**——为什么：`↕ 金额 ↓` 一个在名字前一个在名字后。判据：只留一枚跟在列名内侧的箭头，`test/recordTable.test.tsx`「sorting from the headers」随之更新。落点：`src/ui/record/SortableHeader.tsx`、[ui/record.md#表头排序](ui/record.md#表头排序)。
- **没有多列夹具，"20 列 50 行"验不了**——为什么：故事里最多五列，`PinnedEdges` 里 `创建时间` 已被右钉列裁成 `7:10:0|`。判据：`stories/view-engine/fixtures.ts` 加一个 12 列以上的定义与一条故事，逐屏过一遍后把量到的记回这里。落点：`stories/view-engine/`。

### 视觉

- **字号阶梯里 12.8 与 12 挨得太近**——为什么：一屏 12 / 12.8 / 14 / 16 四档，12.8（shadcn 的 `sm` 按钮）与 12 只差 0.8px，侧栏视图名 12.8 压在 12 的分组标签上，中文在 12.8 渲染发虚。判据：`sm` 按钮与侧栏项统一到 13 或 12（在调用处或 `styles.css` 的 token 上，不改 vendored 文件），四档变三档；浏览器故事量侧栏项字号。落点：`src/styles.css`、`src/ui/layout.ts`。

## 功能（legacy 形态）

- **不是列的字段上的汇总够不着**——为什么：列设置的行来自「配置里的列 + 定义里还能当列的字段」，所以 `config.summaries` 里一条指向既不是列、定义也不再声明的字段时，`validateSummaries` 报 `record.field.unknown`、查询与保存都被挡住，而面板里没有任何一行能把它取消——正是"报了错却够不着"那一类（[ui/record.md](ui/record.md)）。本包的界面写不出这种配置，手写或旧版本迁移过来的可以。判据：想清楚它属于列设置还是属于一条"清掉读不出的设置"的通用出口（先在 [decisions.md](decisions.md) 给结论）；若归列设置，则 `columnSettingRows` 的 broken 行也覆盖只被 `summaries` 提到的字段，且它的勾选框取消时只删汇总、不动列；`test/columnSettings.test.tsx` 覆盖。落点：`src/ui/columns/rows.ts`、[ui/record.md](ui/record.md)。
- **隐藏字段排不了序**——为什么：配置只记已显示的列，所以列设置里隐藏的那几行没有顺序可拖，勾上之后一律落在中间区末尾；想把一个字段放到第三列，得先勾上再拖一次。判据：想清楚"隐藏字段的位置"要不要进配置（这是一个模型问题，先在 [decisions.md](decisions.md) 里给结论），若要，则 `table.columns` 增加 `hidden?: true` 一类的表达，`projectRecord` 跳过它们，列设置对隐藏行照常开放拖拽。落点：[model.md](model.md)、`src/record/project.ts`、`src/ui/columns/rows.ts`。

## 小修

- **「改过、没应用」只有筛选树说得出来**——为什么：这一态的凭据（D2）是条件 pill 与 Apply 上的那个点，而算出它的 `useFilterEditor.pendingCount` 只比两棵筛选树；配置里**其余任何成员**与 `applied` 分开时，屏幕上没有一处说得出来。两类分开的路子：其一是 `edit` 之后**不** `apply` 的控件——`setMode`（`filterMode`）、记录视图的 `setLayout`（只在什么都没跑过时顺带 apply），以及**整个分析编辑器**（分组、指标、排序、`limit`、图表规格、合计全都等 Run；`AnalysisEditor` 的 Run 按钮上没有任何待运行标记，`AnalysisWorkbench` 也不给 `editorLabel`，连能挂那个点的折叠带都没有）——分析编辑器这一条是常态而不是边角；其二是 `edit` 加 `apply` 的控件在 **apply 被拒**时分开：草稿里有 error 时 `apply` 不落地，于是表头读草稿的 `sort`、分页条读草稿的 `pageSize`、列设置读草稿的列，而行还是上一次执行的口径。标题旁那个「未保存」不顶这个用——它答的是另一个问题（没存过，而不是没跑过），未保存的新视图上它还一直亮着。**自动刷新的那个控件是这件事的一个实例，不是起因**：它的凭据现在读 `applied`（[ui/README.md#刷新是一个拆分按钮](ui/README.md#刷新是一个拆分按钮)），正是因为没有第二处凭据可以说"草稿不是这个数"。判据：先说清 D2 的「草稿未应用」管的是筛选树还是整份配置——**这是产品决定，先在 [decisions.md](decisions.md) 给结论，不要在实现时二选一**，因为它决定要不要在分析编辑器与记录工具栏上新增凭据，而 D2 的另一半规矩是一态只留一处；若判为整份配置，则 `pending`／`pendingCount` 的基准从筛选树扩到 draft 与 applied 的逐成员比较，分析的 Run 与被拒时的表头／分页条各自说得出来，且不与三态的另外两处重复；若判为只管筛选树，则把「其余成员只由 `dirty` 负责」连同分析编辑器为什么可以没有写进 [ui/README.md#三态各有一处凭据](ui/README.md#三态各有一处凭据)，这一条就此了结。两种结论都要在 `test/reactHooks.test.tsx`（`useFilterEditor`／`useRecordTable`）与 `test/analysisUi.test.tsx` 各留一条把它钉住。落点：`src/react/useFilterEditor.ts`、`src/react/useAnalysisEditor.ts`、`src/ui/AnalysisEditor.tsx`、[ui/README.md#三态各有一处凭据](ui/README.md#三态各有一处凭据)、[decisions.md](decisions.md)。

- **Dashboard 面板不说结果自身的 warning**——为什么：`ProjectedView.issues`（汇总退回本页 `runtime.summary.page-only`、分析填满上限 `analysis.result.at-limit`）在两个工作台与 `EmbeddedView` 上都会说出来，面板不会：`DashboardPanelState.issues` 是 `dashboardRuntime` 从准入结果重建的，只含配置级发现，面板 chrome 的那个告警图标因此看不见这两条。于是同一个被截断的饼图，单开一个分析视图会说，放进仪表盘就不说了。判据：面板的 issues 合并子 runtime 当前结果的 `resultIssues(...)`（随子 runtime 的通知一起重建，不等下一次 Dashboard 同步），路径按面板重定址成 `['panels', index, ...]`，`test/dashboardRuntime.test.ts` 覆盖"子面板结果退回本页口径"与"子面板分析填满上限"两条；注意 `src/runtime/dashboardRuntime.ts` 已在 `max-lines` 豁免名单里（R11），这条要么先做 R11 的拆分，要么把合并逻辑放进新文件。落点：`src/runtime/dashboardRuntime.ts`、`src/ui/DashboardGrid.tsx`、[ui/dashboard.md](ui/dashboard.md)、[runtime.md#dashboard](runtime.md#dashboard)。

- **分析图表对读屏是一片空白，还自称 `role="application"`**——为什么：量到那张 665×374 的 `<svg>` 带着 Recharts 默认的 `role="application"`，**没有 `aria-label`**，`<title>` 与 `<desc>` 都是空元素，旁边没有 `sr-only` 的替代内容也没有表格。整个结果块的可读文本只有 `"Showing All records 华东 华北 华南 西南 0 1500 3000 4500 6000"`——坐标轴刻度，**一个数据值都没有**。`role="application"` 还会让读屏关掉浏览模式、把按键交给一个没有任何键盘处理的元素。视图存在的意义就是给出这个答案，而这个答案读屏拿不到。本包已经有 `AnalysisTable` 在渲染同一份投影，所以一份 `sr-only` 的表几乎是现成的。判据：图表要么带上从投影生成的可读替代（`sr-only` 表或等价说明），要么交出 `role="application"` 换成 `img` 加名字；`test/analysisUi.test.tsx` 钉住"结果里的每个数据值都在可读文本里出现过"——`<AnalysisChart>` 的渲染与 DOM 断言都在那里，而 `test/analysisChart.test.ts` 是只导入根入口、测 `validateChart`／`shapeChart` 的内核套件，装不下 JSX。落点：`src/ui/AnalysisChart.tsx`、[ui/analysis.md](ui/analysis.md)。
- **仪表盘的编排只有鼠标能用**——为什么：`EditableLayout` 上量到三个 `.react-resizable-handle` 全是裸 `div`，**没有一个 `tabindex ≥ 0`**，也没有 `role` 与 `aria-label`；可拖的 `.react-grid-item` 同样没有 `role`／`tabindex`／`aria-label`；面板头上也没有"移动／调整大小"之类的菜单。面板的位置和大小只能用指针改，键盘完全没有入口。Dashboard 编排不在本轮范围，所以记在这里而不是[打磨](#打磨先看后改不凭想象改)——它不是难看，是一整块能力对键盘不存在。判据：移动与缩放各有键盘等价物（库的键盘传感器，或面板菜单里的一组命令），手柄有名字；`test/dashboardUi.test.tsx` 覆盖键盘改位置与改大小各一条——它已经在 jsdom 里驱动这张网格并断言 `controller().panels[0].layout`，键盘改的是同一个 layout，所以这一条不必上浏览器。落点：`src/ui/DashboardGrid.tsx`、`test/dashboardUi.test.tsx`、[ui/dashboard.md](ui/dashboard.md)。
- **`EmbeddedView` 没有故事，逐屏那一轮没看成**——为什么：对着 `main` 逐屏过的时候发现 `EmbeddedView` 只出现在 `test/embeddedView.test.tsx` 与 `test/viewExpansion.test.tsx` 里，`stories/` 一个都没有，于是九种条件没有一条在它身上看过——而它正是宿主把一个视图嵌进自己页面时用的那一个。判据：`stories/view-engine/` 给 `EmbeddedView` 至少一组故事（默认、空、失败、铺满屏幕），能手动操作也有回归；补齐之后按[打磨](#打磨先看后改不凭想象改)开头那条规矩再看它一遍。落点：`stories/view-engine/`、[ui/README.md](ui/README.md)。
- **删除后重载列表的合同容易漏**——为什么：宿主直接经引擎删除实例时必须调用 `list.reload({ without: id })`，这条合同写在文档里而不是类型里。判据：评估改为引擎侧通知（架构决定，先记录，不急着改）；结论写进 [decisions.md](decisions.md)。落点：[react.md#useviewlist](react.md#useviewlist)。
