# 决定记录

界面已经体现的产品决定，一条一则，写明决定、依据与落点。这里不新增规则：每一条都能在所指的页上找到它的完整表述，那些页才是合同。尚未决定的问题在本页末尾的[搁置待议](#搁置待议)；已决定但尚未做的事在 [todo.md](todo.md)。

## D1 结果是主体

- **日期**：2026-09-19
- **决定**：工作台自上而下是标题栏 → 编辑带 → 状态条 → 已应用条件条 → 结果工具栏 → 结果 → 分页。`EditorBand` 是编辑器所在的折叠带：已保存的视图打开时折起，没存过的展开；折叠状态属于这一次打开，以 `runtime.id` 为 key 重置，不入库也不记忆。
- **依据**：顺序按"离结果多近"排——结果是视图的目的，它上面的每一样都要为自己的高度负责；已保存视图的作者已经决定过了，结果才是要看的东西。
- **落点**：[ui/README.md#工作台骨架](ui/README.md#工作台骨架)

## D2 三态各有一处凭据

- **日期**：2026-09-19
- **决定**：**草稿未应用**是条件 pill 与应用按钮上的那个点（`data-pending`，基准是 `state.applied`）；**已应用**是结果上方的 `AppliedBar`，它读 `state.result.own.filter` 而不是 `applied`；**未保存**是标题旁的标记。三处互不重复。
- **依据**：应用会启动一次查询，在查询答复之前 `applied` 已经走在前面，跟着它的条会描述还没到屏幕上的行。
- **落点**：[ui/README.md#三态各有一处凭据](ui/README.md#三态各有一处凭据)、[react.md#usefiltereditor](react.md#usefiltereditor)

## D3 提交是显式的

- **日期**：2026-09-19
- **决定**：Apply 是同屏唯一的 primary 按钮，面板里的任何输入都不会自己重跑查询。排序与列的改动是例外：立即 `edit` 后 `apply`。
- **依据**：表格渲染的列与行来自上一次成功结果，由内核按执行时的配置投影，因此排序与列不重跑就看不到改动；筛选则等提交。
- **落点**：[ui/README.md#filterpanel-的布局](ui/README.md#filterpanel-的布局)、[ui/README.md#控制器输出合同](ui/README.md#控制器输出合同)

## D4 能力决定选项存在

- **日期**：2026-09-19
- **决定**：编辑器只提供内核会接受的选项——每页条数档位按 `runtime.limits.maxPageSize` 裁剪，布局按 `RecordCapability.layouts`，分组与函数按 `AnalysisCapability`。管理器里每个按钮按许可**存在或不存在**而不是置灰，系统视图没有改名与删除。
- **依据**：管理入口通向一屏只读的行，就是一个只能教人它通向哪儿也不去的按钮。
- **落点**：[runtime.md#状态与命令](runtime.md#状态与命令)、[ui/README.md#保存与视图管理](ui/README.md#保存与视图管理)

## D5 告警是单行状态条

- **日期**：2026-09-19
- **决定**：两级 severity 都以**单行状态条**（`StatusStrip`）呈现而不是整块 Alert；error 以 `role="alert"` 播报，其余以 `role="status"`；多于一条时只显示一句概述与「{count} more」。
- **依据**：结果区始终留着上一次成功的结果，一条阻塞不了什么的提示不该把它顶下屏幕。
- **落点**：[ui/README.md#两级-severity-与-statusstrip](ui/README.md#两级-severity-与-statusstrip)

## D6 业务动作走 render 槽位

- **日期**：2026-09-19
- **决定**：`global` / `bulk` / `row` 三层业务动作由宿主以 render 函数交出（`RecordActionSlots`），不按字符串键注册，也不进配置、不进 `ViewInstance`。
- **依据**：动作是代码——它开表单、发命令、跳页面——存下来的是"看法"，能对记录做什么属于挂载工作台的那个应用。
- **落点**：[ui/README.md#动作槽位](ui/README.md#动作槽位)、[react.md#recordactionslots](react.md#recordactionslots)

## D7 已应用条的 badge 保留 ✕

- **日期**：2026-09-19
- **决定**：`AppliedBar` 里每个自有条件的 badge 带一个 ✕，把对应条件的值设回未填写并重新应用，字段行留在编辑器里（`clearValue(path)` + `submit()`）。宿主注入的作用域 badge 不带 ✕；`EmbeddedView` 整条 bar 只读。
- **依据**：作用域不在 draft 里，也没有一条编辑器的路径指向它，给一个删不掉的 ✕ 等于许诺一次做不到的放宽。
- **落点**：[ui/README.md#三态各有一处凭据](ui/README.md#三态各有一处凭据)

## D8 视图管理按 legacy 形态

- **日期**：2026-09-19
- **决定**：`SaveActions` 是拆分按钮组，主按钮说此刻该做的那一件事，菜单里放其余的；改名、删除、排序、设默认都在侧栏标题旁的管理器（`ViewManager` + `useViewManager`）里；删除遇到冲突时「保留我的」不直接覆盖，而是用 `write.remote` 刷新后的摘要再确认一次；另存的准入由对话框按目标受众自己判，不拿当前受众的判决锁住它；`useLeaveGuard` 只在 `dirty` 或写入结局为 `unknown` 时拦一句。
- **依据**：改名、删除、排序、设默认改的是列表而不是眼前这个视图；第一次确认说的是列表里的那个视图，冲突报回来的已经不是它；每次切换都拦的守卫，人会学会不读就点掉。
- **落点**：[ui/README.md#保存与视图管理](ui/README.md#保存与视图管理)、[ui/README.md#离开保护](ui/README.md#离开保护)

## D9 工作台只列自己那一种

- **日期**：2026-09-19
- **决定**：`useViewList(engine, definitionId, { kind })` 先按 `kind` 过滤，再排序、再解析默认，因此 Record 工作台的侧栏与默认视图里不会出现分析实例，反之亦然；宿主若显式指定了另一种的 `instanceId`，工作台以 `view.open.wrong-kind` 按「打不开」呈现。
- **依据**：留下一张空白正文比直说打不开更难理解；而 `reorder` 提交的仍是 `all`（未经 `kind` 过滤的完整顺序），否则记录工作台调一次序就会把所有分析实例从 `order` 里抹掉。
- **落点**：[management.md#列表偏好与默认视图](management.md#列表偏好与默认视图)、[react.md#useviewlist](react.md#useviewlist)

## 搁置待议

尚无结论，不要当作规则执行。

- **Q2 Analysis 编辑器的形态**：顶部折叠带，还是侧面板？现状是 Analysis 与 Dashboard 只接了标题栏、状态条与已应用条件条，编辑器形态照旧（[ui/README.md#工作台骨架](ui/README.md#工作台骨架)）。
- **Q3 提交的措辞**：「应用／未应用」还是「查询／未生效」？现状是措辞集中在 `ui/messages.ts`，按 key 可覆盖，换词不动行为（[ui/README.md#措辞与-messagesprovider](ui/README.md#措辞与-messagesprovider)）。
- **Q6 图型不可用时怎么呈现**：能力决定一个图型存不存在（D4），配置决定它此刻可不可用（分组别名没被消费、指标不可加）；两者在界面上如何区分尚未定（[kernels.md#图表规则](kernels.md#图表规则)）。
