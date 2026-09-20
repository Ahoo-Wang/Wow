# TODO

规则，先读再加：

- 只记**已经决定、但还没做**的事。尚无结论的产品问题不在这里，在 [decisions.md#搁置待议](decisions.md#搁置待议)。
- 每条都要写清 **为什么** / **做完的判据** / **落点**（design 里的哪一页，或哪个文件）。三者缺一就不是一条 TODO，是一句抱怨。
- 做完就**删掉**这一条，不打勾、不留归档。历史在 git 里。
- 改行为之前先看这里有没有对应项；有就接着做，别另起一条。

## 本轮范围：全局功能与记录视图推进到生产级

用户定下的范围只有两块——**全局功能（外壳）** 与 **记录视图**。Analysis 编辑器形态、Dashboard 编排不在本轮，它们的未决问题在 [decisions.md#搁置待议](decisions.md#搁置待议)。

每条的判据以这条基线读，**但只取适用的那几项**：一条只补回归的条目不必造文案，一条只看不改的条目没有并发路径。基线是——新出现的行为，其错误、空、权限、并发路径各有定义也有测试；新出现的界面键盘可达并过 axe；新出现的文案中英齐全；新出现的交互有故事既能手动操作也有回归；design 对应页与本页同步；全门绿。哪几项适用，由各条自己的判据说了算。

顺序：**自动刷新 → 打磨清单（先看后改）→ 筛选三条（日期时刻／IN 多值／软删除）→ ErrorBoundary → 单元格渲染器族 → 列宽・隐藏字段排序・指针拖动回归 → 写入结局故事・视图管理拖动排序**。打磨排在新功能之前：先把已经有的做对，再加没有的。

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

- **自动刷新缺的只是入口**——为什么：合同早已落地——`ViewConfigBase.refresh.interval` 随视图配置保存，取值由 `RuntimeLimits` 的 `minRefreshInterval`／`maxRefreshInterval` 兜住，runtime 按它持有唯一一个计时器并在四种情况暂停（[runtime.md](runtime.md)、[model.md](model.md)）。缺的只有界面：结果工具栏只有一次性的刷新按钮，看板式的用法只能靠人手点。判据：刷新改为拆分按钮（主键一次刷新、`▾` 选间隔），选中即 `edit({ refresh: { interval } })` 加 `apply`——改的是**现有的** `refresh.interval`，不得另起一套临时状态；可选间隔按 `runtime.limits` 裁剪，限制不允许的间隔是**不存在**而不是禁用（D4）；开启时刷新按钮上有一处在走的凭据（它说的是“这个视图在自己刷新”，与三态凭据不冲突）；暂停与停表的时机沿用 runtime 已有的四条，界面不自己发明第五条。三种视图都要有入口，因为 `refresh` 在 `ViewConfigBase` 上：Record 走 `ResultToolbar`（刷新那一组已经给它留了位置）；Analysis 不用这个工具栏，入口落在它自己的那一处刷新旁；Dashboard 的计时器由 `DashboardRuntime` 统一持有、被引用实例自身的 `refresh` 在其中被忽略（[runtime.md](runtime.md)），所以它编辑的是仪表盘自己的 `refresh.interval`，并要在界面上说清这一层关系。落点：`src/ui/ResultToolbar.tsx`、`src/ui/AnalysisWorkbench.tsx`、`src/ui/DashboardWorkbench.tsx`、`src/react/`、[ui/README.md](ui/README.md)。
- **带时刻的日期条件筛不准边界**——为什么：日期条件只有日历，没有 `HH:mm:ss` 控件，`withTime` 于是直接取日历给的那一刻（零点），"今天下午三点之后"写不出来，边界上的记录要么全进要么全不进。判据：`withTime` 为真的字段在日历旁给出时刻输入（与日历同属一个控件，一次提交），未填时刻时的缺省语义**按 [decisions.md](decisions.md) 的 Q10 执行**，不在实现时二选一（它决定哪些记录命中，不是实现细节）；相对日期与预设不受影响；`test/filter*.test.ts` 覆盖边界两侧各一条。落点：`src/ui/filter/`、[ui/README.md](ui/README.md)、[kernels.md](kernels.md)。
- **数值 `IN`／`NOT_IN` 只能录两个值**——为什么：内核早就收任意长度的数值数组，是 `src/ui/filter/inputs/number.tsx` 的 `NumberValue` 把 `multiple` 和 `range` 并在同一个分支里（`if (range || multiple)`）截成两个输入框，第三个值无处可填。判据：`multiple` 走自己的渲染——可增删的值列表，空值不提交；`range` 保持两端点；回归落在 **jsdom 的界面套件 `test/filterValueEditor.test.tsx`**（必要时加 `test/filterPanel.test.tsx`），不能只加一条内核用例——界面仍只能输入两个值时那种用例照样会过。落点：`src/ui/filter/inputs/number.tsx`、[ui/README.md](ui/README.md)。
- **软删除条件没了**——为什么：legacy 有 `DELETION` 条件（只看未删除／只看已删除／全都看），现在没有，列表会默不作声地混进已删除记录——这是数据口径的沉默，比少一个筛选项严重。判据：定义能声明这一维（能力决定它存不存在，D4），未声明时界面上没有这个东西；声明了则条件区有一处显式选择；**缺省口径与旧配置缺这一维时的读法按 [decisions.md](decisions.md) 的 Q11 执行**，并要在已应用条上说得出来；`test/` 覆盖三种口径各一条。落点：[model.md](model.md)、`src/filter/`、[kernels.md](kernels.md)。
- **默认 UI 没有任何 ErrorBoundary**——为什么：宿主真正交进来的 React 是动作槽位 `actions={{ global, bulk, row }}`（D6）与 Dashboard 面板里的 markdown，它们在工作台的渲染树里；其中任意一处抛错，今天会把整页带走。单元格渲染器不在此列——`FieldDefinition` 上那个是字符串提示，由本包硬编码分支读，宿主注册不进来，所以不要拿它当边界场景，也不要为此发明注册合同。判据：结果区、编辑带、面板各有边界，落到边界上时那一块显示可复原的错误态（带“重试”）而不是白屏，边界之外照常可用；错误能被宿主收走（回调），不只是吞掉；`test/` 覆盖“宿主的行动作抛错只毁掉那一块”与“面板抛错不影响同屏其他面板”。落点：`src/ui/`、[ui/README.md](ui/README.md)。

## 记录视图

- **单元格渲染器族只有默认与枚举徽章**——为什么：legacy 的表格能把一列读成状态、标签组、链接或多行文本，现在除枚举徽章外一律走默认渲染，于是一列 URL 只是一串字，一列字符串数组只是逗号拼接。判据：`FieldDefinition` 能声明单元格的读法（闭合取值，如 `cell?: 'status' | 'tags' | 'link' | 'text'`，定义准入拒绝未知值——能力决定存在，D4），`RecordTable` 按它分派：`status` 走徽章、`tags` 走多枚徽章、`link` 走 `isSafeContentUrl` 守住的外链（`target="_blank" rel="noopener noreferrer"`，与 `DashboardPanels` 的 markdown 链接同一条规则）、`text` 走可截断的多行；未声明时保持今天的默认渲染不变；`test/recordTable.test.tsx` 每族一条，`stories/` 有一屏能看全。落点：`src/model/field.ts`、`src/ui/RecordTable.tsx`、`src/ui/display.ts`、[ui/record.md](ui/record.md)。

## 打磨（先看后改，不凭想象改）

- **逐屏看一遍再列条目**——为什么：打磨的条目必须来自看，不能来自想；上一轮的问题（没有区块划分、同职责控件被拆成一排独立按钮）就是看出来的。判据：在 Storybook 上对着合并后的 `main` 逐屏过——默认、展开筛选、空、加载、失败、窄屏、暗色、中文、长标题与多列——把具体条目写进本页再小批改；间距分四级（块间 16 / 块内行间 12 / 控件组间 8 / 组内 4），互斥选项按 [ui/README.md](ui/README.md) 的判断规则选分段控件还是 `Select`，结果工具栏一律 `ghost`、只有宿主的批量动作用 `outline`，**布局切换是既有的唯一例外**（那一圈描边正是“一个控件两个档位”与“两个按钮”的差别，见 [ui/record.md](ui/record.md)，打磨时不要把它抹掉）。落点：[ui/README.md](ui/README.md)、[ui/record.md](ui/record.md)。
- **固定列的边缘是静态的，说不出内容有没有滑到它下面**——为什么：`src/ui/record/columns.ts` 的 `pin()` 已经给左右固定单元格发了 1px 的 inset 描边（`ACTION_CELL` 同理），所以固定列**不是**没有视觉——但那道边一直在，没滚动时也在，于是它说的是“这里有条线”而不是“内容正从这里滑到我下面”。判据：把这道既有的静态边升级成随滚动出现的阴影（`scrollLeft` 为 0 时左边那道不出现，滚到尽头时右边那道不出现），表头、数据行、汇总行三层同步；改的是 `pin()` 这一处，不要在 `RecordTable.tsx` 或全局 CSS 里另起一套而把旧的静态边留在原地；`stories/` 有横向滚动的回归。**末列是否在没有行操作时也强制固定，取决于 [decisions.md](decisions.md) 的 Q8**，在它有结论之前不要改列固定的语义。落点：`src/ui/record/columns.ts`、`src/ui/RecordTable.tsx`、[ui/record.md](ui/record.md)。

## 功能（legacy 形态）

- **写入结局的 Storybook 故事**——为什么：conflict / unknown / rejected 三条路径只有单测走过，改 UI 时没人看得见它们。判据：`WriteOutcome`、管理器行内结局、删除冲突二次确认各有故事，夹具的假存储能注入 `CONFLICT` 与 `UNAVAILABLE`。落点：`stories/`、`test/fixtures/`、[management.md#冲突与未知结果](management.md#冲突与未知结果)。
- **不是列的字段上的汇总够不着**——为什么：列设置的行来自「配置里的列 + 定义里还能当列的字段」，所以 `config.summaries` 里一条指向既不是列、定义也不再声明的字段时，`validateSummaries` 报 `record.field.unknown`、查询与保存都被挡住，而面板里没有任何一行能把它取消——正是"报了错却够不着"那一类（[ui/record.md](ui/record.md)）。本包的界面写不出这种配置，手写或旧版本迁移过来的可以。判据：想清楚它属于列设置还是属于一条"清掉读不出的设置"的通用出口（先在 [decisions.md](decisions.md) 给结论）；若归列设置，则 `columnSettingRows` 的 broken 行也覆盖只被 `summaries` 提到的字段，且它的勾选框取消时只删汇总、不动列；`test/columnSettings.test.tsx` 覆盖。落点：`src/ui/columns/rows.ts`、[ui/record.md](ui/record.md)。
- **列宽还没有入口**——为什么：`RecordColumn.width` 是模型的一部分、投影也带着它，但没有任何界面能改它，只有手写配置能。判据：列宽拖拽落在表头边界上（与列设置里的顺序拖拽用同一个库），落下时走 `setColumnWidth(field, width)`（新的控制器命令，同样是 `edit` 加 `apply`），双击边界恢复自适应；键盘可达。落点：`src/ui/RecordTable.tsx`、`src/react/useRecordTable.ts`、[react.md#userecordtable](react.md#userecordtable)。
- **隐藏字段排不了序**——为什么：配置只记已显示的列，所以列设置里隐藏的那几行没有顺序可拖，勾上之后一律落在中间区末尾；想把一个字段放到第三列，得先勾上再拖一次。判据：想清楚"隐藏字段的位置"要不要进配置（这是一个模型问题，先在 [decisions.md](decisions.md) 里给结论），若要，则 `table.columns` 增加 `hidden?: true` 一类的表达，`projectRecord` 跳过它们，列设置对隐藏行照常开放拖拽。落点：[model.md](model.md)、`src/record/project.ts`、`src/ui/columns/rows.ts`。
- **真指针拖动没有回归**——为什么：列设置的落点计算与键盘一步移动都有单测，但"按下手柄、移到第三行、松手"整条链路只有库自己的测试走过；jsdom 不算布局，碰撞检测在那里没有意义。判据：`stories/view-engine/RecordWorkbench.test.stories.tsx` 增加一条用真实 Pointer 事件的拖动回归（Playwright 环境），断言表格列序与保存后的配置；与既有的键盘回归共用同一个故事。落点：`stories/view-engine/`、[ui/record.md](ui/record.md)。
- **视图管理支持拖动排序**——为什么：现在只有上下移动按钮，一行一行点在长列表里不现实；legacy 的 `ListOrder` 是拖动手柄。判据：复用列设置已经引入的拖放库（`@dnd-kit/react` + `@dnd-kit/dom`，已在 catalog 与本包依赖里），管理器每行带手柄，拖动只在同一受众组内生效，落下时按 `move` 的同一条路径提交**整个定义的完整顺序**（未列出的种类保持原位）；键盘可达（保留上下按钮或改用库的键盘传感器），落库失败不乐观回滚而是退回原序并报出；`useViewManager` 需要 `moveTo(id, index)` 之类按位置落子的命令；`test/viewManagerUi.test.tsx` 覆盖拖动与键盘两条路径。落点：`src/ui/ViewManagerRow.tsx`、`src/react/useViewManager.ts`、[management.md#列表偏好与默认视图](management.md#73-列表偏好与默认视图)。

- **平击表头独占排序（Shift 追加）**——为什么：legacy 的表头是「点击排序，按住 Shift 添加排序」，即平击只按这一列排、Shift 才追加；`useRecordTable.toggleSort(field)` 无条件把新字段追加在 `sort` 末尾，控制器没有第二个入口，`RecordTable` 于是只能实现"每次点击都追加"这一半，Shift 没有可绑的语义。用现有接口模拟独占要对其余每个已排序列反复 `toggleSort`（升序列要两次），而每次 `toggleSort` 都是一次 `edit` + `apply`，即一次真实查询被随后的请求取代——为一次点击打三五个会被中止的请求，不能算实现。判据：`useRecordTable` 增加一次落下整份排序的成员（`setSort(sort: RecordSort[])`，或 `toggleSort(field, { additive?: boolean })`），`RecordTable` 平击走独占、Shift／Meta 走追加，键盘等价物随之给出（Shift+Enter，或表头菜单里的一项）；`test/recordTable.test.tsx`「sorting from the headers」补上两条路径，`ui/record.md#表头排序` 与 `react.md#userecordtable` 同步。落点：`src/react/useRecordTable.ts`、`src/ui/record/SortableHeader.tsx`、[ui/record.md#表头排序](ui/record.md#表头排序)。
- **枚举徽章的颜色要由定义说了算**——为什么：状态单元格已经是徽章，但一律中性 `secondary`——哪一个状态是好消息属于业务，渲染层猜不得；而 legacy 的状态列是有颜色的。判据：`FieldOption` 带上一个闭合的语气字段（如 `tone?: 'neutral' | 'success' | 'warning' | 'danger'`，映射到主题已有的 token，不收任意颜色值），`badgeEntries` 连同语气一起交出，`RecordTable` 按它选 variant；定义准入拒绝未知语气；`test/display.test.ts` 与 `test/recordTable.test.tsx` 各补一条。落点：`src/model/field.ts`、`src/ui/display.ts`、`src/ui/RecordTable.tsx`、[ui/record.md#枚举单元格是徽章](ui/record.md#枚举单元格是徽章)。

## 小修

- **Dashboard 面板不说结果自身的 warning**——为什么：`ProjectedView.issues`（汇总退回本页 `runtime.summary.page-only`、分析填满上限 `analysis.result.at-limit`）在两个工作台与 `EmbeddedView` 上都会说出来，面板不会：`DashboardPanelState.issues` 是 `dashboardRuntime` 从准入结果重建的，只含配置级发现，面板 chrome 的那个告警图标因此看不见这两条。于是同一个被截断的饼图，单开一个分析视图会说，放进仪表盘就不说了。判据：面板的 issues 合并子 runtime 当前结果的 `resultIssues(...)`（随子 runtime 的通知一起重建，不等下一次 Dashboard 同步），路径按面板重定址成 `['panels', index, ...]`，`test/dashboardRuntime.test.ts` 覆盖"子面板结果退回本页口径"与"子面板分析填满上限"两条；注意 `src/runtime/dashboardRuntime.ts` 已在 `max-lines` 豁免名单里（R11），这条要么先做 R11 的拆分，要么把合并逻辑放进新文件。落点：`src/runtime/dashboardRuntime.ts`、`src/ui/DashboardGrid.tsx`、[ui/dashboard.md](ui/dashboard.md)、[runtime.md#dashboard](runtime.md#dashboard)。

- **删除后重载列表的合同容易漏**——为什么：宿主直接经引擎删除实例时必须调用 `list.reload({ without: id })`，这条合同写在文档里而不是类型里。判据：评估改为引擎侧通知（架构决定，先记录，不急着改）；结论写进 [decisions.md](decisions.md)。落点：[react.md#useviewlist](react.md#useviewlist)。
