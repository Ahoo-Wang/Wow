# UI 层：Dashboard 视图

栅格、面板 chrome 与面板级告警。三种视图共用的骨架、状态条与 `FilterPanel` 见 [README.md](README.md)；面板与子 runtime 的关系见 [runtime.md#dashboard](../runtime.md#dashboard)。

## DashboardGrid 与几何写回

- `DashboardGrid` 不做自动紧凑，面板按配置中的 `layout` 原样摆放；
- 只有用户拖动或缩放结束时才把几何写回（`edit` + `apply`），库自身在挂载或属性变化时算出的布局不写回，因此打开已保存的 Dashboard 不会变脏。（见 test/dashboardUi.test.tsx「DashboardGrid」「with a stored layout the grid would have compacted」）

- 面板里的 Record 视图以 `selectable={false}` 渲染 `RecordTable`：Dashboard 是读数的地方，没有工具栏也没有行动作，没有任何东西读选择，勾选框因此只是一列点不出结果的控件。

## 刷新是整块板子的

- 仪表盘的刷新同样是标题栏里的那个拆分按钮，但它编辑的是**仪表盘自己的** `refresh.interval`：`DashboardRuntime` 为整块板子持有唯一一个计时器，被引用实例自身的 `refresh` 在其中被忽略，以免两层计时器（[runtime.md#dashboard](../runtime.md#dashboard)）。所以菜单顶上多一句 `label.refresh.panels` 说清这一层关系——控件若什么都不说，看上去就像在给每个面板各设一个间隔；
- 主键那一半在引用还在解析时禁用（`dashboard.resolving`，此时没有哪个面板能被刷新），**在任一面板的查询还在途时也禁用**（`dashboard.loading`）：仪表盘自己不跑查询，`state.query` 永远是 `idle`，问它「有没有东西在跑」永远答没有，于是一屏正在加载的面板会被一次点击整片顶掉，而按钮上连个转圈都没有。`useDashboard` 因此自己订阅各个子 runtime——子 runtime 的查询变化不会通知仪表盘的订阅者（那是有意的，否则每个面板每次请求都要让整张栅格重渲染），所以要知道这件事的人得自己去问。（见 test/refreshControl.test.tsx「the dashboard title bar, saying whose timer it is」）

## 面板 chrome 与 warning 标记

- Dashboard 在这里显示尚未被任何已应用面板承载的 warning：自己的（`useDashboard().issues`，不含 `['panels', …]` 路径），以及 draft 里面板级却还没交给面板的——全局条件映射到会告警的面板字段、尚未 Apply，这时 `state.panels` 仍是上一次 applied 的，不说就会被 Save 原样存下；
- Apply 之后由面板承载，条里不再重复。面板级的由面板自己呈现：不可用的面板在正文里说明理由（首个 error，或独自到来的那条 warning），随之而来的其余 warning 仍在头部标记里，能运行却带 warning 的面板（子 runtime 对自身配置的 warning，以及它上一次**结果**自身的 warning——汇总退回本页、分析填满上限——都已重定址到面板，见 [runtime.md#dashboard](../runtime.md#dashboard)）照常显示视图，头部加 `panel-warning` 标记（图标的可访问名与 `title` 是 warning 文案）并以 `data-warning` 标出边框。（见 test/dashboardUi.test.tsx「DashboardWorkbench」「content panels」）
