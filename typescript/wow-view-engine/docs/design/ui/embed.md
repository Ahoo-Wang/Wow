# UI 层：嵌入视图

一个已经存好的视图或仪表盘摆进宿主自己的业务页面（D22 嵌入一半，阶段 4）。工作台存在是为了让人**改变**怎么观察，嵌入存在是为了让一张业务页面**摆出**别人早已定好的那份观察——所以嵌入没有视图列表、没有条件编辑器、没有保存（仪表盘的可编辑一档除外）。三种视图共用的规则（措辞、状态条、主题作用域、铺满屏幕）见 [README.md](README.md)；仪表盘的栅格、筛选与点击见 [dashboard.md](dashboard.md)。

## 两个入口，按资源分

- **`EmbeddedView`（记录／分析）与 `EmbeddedDashboard`**，与工作台拆成 `DataWorkbench`／`DashboardWorkbench` 同一条线；一个入口只画自己那几种。给错了种类不画任何东西，只有一条提示：标题「无法打开这个视图」，说明「这个视图是另一种类型（〈种类〉），这个页面无法显示。」（`view.open.wrong-kind`，与工作台同一句）；`EmbeddedDashboard` 只画一种，按种类词说（D26 Q34，`EmbedFrame` 把 `SurfaceKind` 设成它那一种）：「无法打开这个仪表盘」「这不是仪表盘（〈种类〉），这个页面无法显示。」，打开中、打不开、被拒的收窄与板上的报错同理。（见 test/embeddedView.test.tsx「names a dashboard as a view it cannot show: that is EmbeddedDashboard」，test/embeddedDashboard.test.tsx「names a record view as one it cannot show: that is EmbeddedView」）
- 两者共用的都在 `ui/embed/`：`EmbedFrame` 是面（主题、措辞、语言、时区、`data-embed-size`）、打开时的三种失败（打不开、种类不对、页面的收窄被拒，D17-5）与一道渲染边界；**正在打开时说出来**（U-13）：视图还在读的那一刻画骨架，同时 `aria-busy="true"`、一句只给读屏的 `role="status"`「正在打开视图」（`embed-opening`，与工作台打开时同一句），原来嵌入的第一刻对读屏一声不响（见 test/embeddedView.test.tsx「says it is opening while the view is read」）；`EmbedHead` 是第一行——有标题或有控件时才有，否则一行 chrome 也不加（D10）；`EmbedBaseProps`（`ui/embed/options.ts`）是两者都收的属性。

## 交互是明确的一档

`interaction`，缺省 `read-only`——业务页面摆出的是别人定好的东西，更多交互由宿主明说（[D24](../decisions.md#d24-嵌入视图的六条细化2026-09-23) Q21）：

| 档            | 记录／分析（`EmbeddedView`）                                                                                        | 仪表盘（`EmbeddedDashboard`）                                                                                   |
| ------------- | ------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------- |
| `read-only`   | 结果与它的已应用条件（只读）；表头不能排序、不能拖宽，没有分页，没有勾选（开了导出也没有），失败条没有「重试」      | 面板照画，什么都不回应：点一组不弹菜单、不联动、不去别处；面板没有「⋯」（开了导出除外），失败的面板没有「重试」 |
| `interactive` | 表头排序、分页、失败时「重试」；分析的表格｜图表切换（`AnalysisToolbar`）、表头排序、按一组追问；「在工作台中打开」 | 追问菜单、交叉筛选、自定义目的地、面板「⋯」里的「在工作台中打开」                                               |
| `editable`    | —                                                                                                                   | 再加「编辑」：就地搭板子（编辑条、添加、添加筛选、面板菜单的「改」），「保存」存下                              |

- 离开嵌入的每一条路都经宿主的**一个**路由 `onNavigate(to: ViewNavigation)`，包本身不碰地址；没有路由，追问与「在工作台中打开」都不出现。去另一块仪表盘的点击（D23 Q17）交出 `{ kind: 'dashboard', instanceId, filters }`，宿主可以把 `filters` 交给另一个 `EmbeddedDashboard` 的 `initialFilters`（或 `DashboardWorkbench` 的 `initialFilters`）。记录／分析的「在工作台中打开」交出 `{ kind: 'view', definitionId, instanceId, scopeFilter, filter: null }`——存下的视图，页面的收窄是它的作用域，到了工作台照样锁着、没有 ✕（D26 Q30：页面持有的保持锁定）；读者这一次的排序、翻页、搜索不带走。追问与仪表盘面板同一个钩子（`usePanelFollowUps`），交法也一样：页面的收窄是开出去那个视图的 `scopeFilter`，这一组的条件是它自己的。宿主把两种都原样交给 `DataWorkbench` 的 `handOver`。仪表盘面板的出口与工作台里的仪表盘一种交法，锁定与隐藏的筛选是作用域、读者的值是视图自己的条件，另带回板子的路（[dashboard.md](dashboard.md) 面板菜单的「看」）。（见 test/embeddedView.test.tsx「reads, and does nothing else, in the read-only tier」「sorts by a header and pages in the interactive tier」「switches an analysis between table and chart in the interactive tier, and not in the read-only one」「opens the follow-up menu on a group, through the host route, in the interactive tier」「opens the view in the workbench through the host route, under the page narrowing — interactive only」）
- 仪表盘的只读一档由 `DashboardGrid` 的 `readOnly` 说：不给面板 `press`、不给「⋯」、不给重试——板子按自己的计时器重跑。（见 test/embeddedDashboard.test.tsx「answers no press and offers no way off the board in the read-only tier」「cross-filters on a press in the interactive tier」）
- **可编辑一档**用的是工作台的同一套：`DashboardBoard`、`useDashboardExtensions`（新建分析、面板自己的展示、另存为视图、复制为共享视图并替换、标签栏）、`useSaveCommands` 的保存、`WriteOutcome` 的冲突出路；「编辑」只给能保存这块板的人——系统板、没有保存权限的读者读作可交互一档，不给「另存为」（D24 Q23）。编辑中挂 `beforeunload`（`useLeaveGuard`），宿主自己的导航卸掉嵌入，不经过它。「保存」「取消」之后键盘回到「编辑」。编辑条吸在滚动这块板的那个框的顶上（R3b）——宿主包着嵌入的框若是不滚的滚动框（`overflow: hidden`），它就吸在那个不动的框上；要裁切用 `overflow: clip`（见 [dashboard.md](dashboard.md)「编辑条在搭的时候一直看得见」）。（见 test/embeddedDashboard.test.tsx「builds in place in the editable tier, for whoever may save the board」「offers no building on a board nobody may save here」；浏览器里 stories/view-engine/Home.test.stories.tsx「Editing」）

## 开关

| 属性                        | 缺省      | 说什么                                                                                                                                                                                                                                                                                                                                                     |
| --------------------------- | --------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `withTitle`                 | 关        | 视图或仪表盘的标题，画成 `headingLevel` 级的标题                                                                                                                                                                                                                                                                                                           |
| `headingLevel`              | `2`       | **正式开关**：嵌入所标的标题级别——自己的标题在这一级，仪表盘的面板在它下一级；没有标题时面板就在这一级。宿主页面的 `h1` 是宿主的，所以最小 2                                                                                                                                                                                                               |
| `withPanelTitles`（仪表盘） | 开        | 关掉时面板标题只给读屏（`sr-only`，标题元素还在，按标题跳读照样走得到），头部一行里没别的就整行一起收                                                                                                                                                                                                                                                      |
| `withSearch`（记录）        | 关        | 已应用条末尾的搜索框（`SearchBox`，定义声明了搜索字段才有）                                                                                                                                                                                                                                                                                                |
| `withExport`（记录）        | 关        | 第一行里的导出（`ExportButton`，D14，与工作台同一个 `useExportOffer`）；可交互一档里有了它行可勾选，导出窗口可按勾选的导出；只读一档仍然没有勾选，导出整份结果，与面板导出一致（[D26](../decisions.md#d26-阶段-34-联合审查的十一条拍板2026-09-24) Q36，test/embeddedExport.test.tsx「draws no row checks, and exports every row」；故事 `ReadOnlyExport`） |
| `withExport`（仪表盘）      | 关        | 记录面板「⋯」里的「导出数据…」（同一个导出窗口与 `useExportOffer`，见 [dashboard.md](dashboard.md) 面板菜单）；只读一档里「⋯」只有这一项                                                                                                                                                                                                                   |
| `autoRefresh`               | 开        | 按作者存的间隔自己刷新；关掉时计时器一直停着（`ViewRuntime.setAutoRefresh`），间隔原样保留、不写进草稿，手动刷新照样跑                                                                                                                                                                                                                                     |
| `openInWorkbench`           | 开        | 可交互、可编辑两档里给不给「在工作台中打开」（仍要有路由）；只读档从来没有                                                                                                                                                                                                                                                                                 |
| `size`                      | `content` | 见下文「高度」                                                                                                                                                                                                                                                                                                                                             |

没有的开关就是不存在，不是置灰（D4）；导出与搜索是开关、不看档位，只读一档也能开（D24 Q24）——仪表盘也一样：只读一档的面板本来没有「⋯」，开了导出，记录面板就有一颗只装着「导出数据…」的「⋯」（Metabase 静态嵌入的下载也是卡片菜单里的一项）；分析面板的导出已定、没做（[D25](../decisions.md#d25-阶段-3-收尾的四条细化2026-09-24) Q28：与分析工作台的导出一起，到 Wow 仓做，见 [todo.md](../todo.md) 的检查点）。（见 test/embeddedDashboard.test.tsx「offers a record panel’s export where the host switched it on, in any tier」；浏览器里 stories/view-engine/EmbeddedDashboard.test.stories.tsx「CustomerOrdersExport」； test/embeddedView.test.tsx「titles itself at the level the host outline calls for, when asked」「offers the search box and the export where the host switched them on」，test/embeddedDashboard.test.tsx「puts the titles where the host outline wants them: the board’s, and its panels one under」「keeps a panel’s title for a screen reader alone when the host turns titles off」）

## 仪表盘的筛选：逐个三态

`filterModes`（按筛选名）与 `groupingMode`（时间粒度），没点名的都是 `editable`（`ui/dashboard/filterModes.ts`）：

- **可编辑**：在筛选条上，归读者，与工作台一样。
- **锁定**：在筛选条上读作它的值——「客户 是 晨光食品」，与已应用条同一套读法（`describeFilter` + `summaryText`）——旁边一把锁（`IconTooltip`「由页面设定」），没有控件、没有 ✕；读屏念「客户（由页面设定）」。画出来而不藏起来是 D24 Q19：不想让读者看见的用隐藏。时间粒度锁定时读作「时间粒度 按月」。
- **隐藏**：不在筛选条上，照样收窄接上的面板；面板头上的「不受…影响」不提它（那是读者看不见的筛选）。
- 锁定与隐藏都是**页面的**：由 runtime 持有（`DashboardRuntime.holdFilters`，见 [../runtime.md#dashboard](../runtime.md#dashboard)），读者的每一条命令都碰不到——筛选条上的值、「清空」、按粒度、设它的交叉筛选点击（那块面板的点击退回追问菜单，面板头不再说「点击筛选」；隐藏的也一样，D24 Q20）。筛选条上只有锁定的筛选时没有「清空」。别的文本筛选，候选值在页面持有的值下计数：锁定在一位客户上的页面，别的筛选只列这位客户的数据里有的值。（见 test/embeddedDashboard.test.tsx「draws a locked filter as what it holds, keeps a hidden one off the bar, and lets the reader change neither」，test/embedRuntime.test.ts「sets aside a click that sets a filter the page holds: a press does what a panel without one does」「counts what a text filter offers under what the page holds」）
- **读者的值是宿主的地址，锁定与隐藏的值是页面自己的**：两个属性，两个来源。`pageValues` 是页面持有的值——锁定与隐藏的筛选各取其中的值（没写就是默认值），时间粒度被持有时取它的 `unit`；它们从第一次查询起就在（`OpenOptions.held`），不会有一次没锁的查询出去，并**跟着这个属性变**：客户页换到下一位客户，板子跟着换，内容一样的新对象什么也不动；其中写到可编辑筛选的条目不算。`initialFilters`／`onFiltersChange` 是读者的、宿主写进地址的那一份，与 `DashboardWorkbench` 同名同读法（D24 Q22）：打开时读一次，点名了任何一个读者的筛选就是它们的全部，一个也没点名就都从默认值开始；`initialTab`／`onTabChange` 同理。**锁定与隐藏的值从不走地址**：`initialFilters` 里写到它们的条目不算（页面的值为准），`onFiltersChange` 只报读者能设的筛选（可编辑的那些，时间粒度没被持有时连同单位）——否则宿主把它写进地址、再从地址读回来，读者改一下地址就换了客户，与「锁定」正相反。页面的值被板子拒绝（没有这个筛选、值读不懂）时，板子上方一条与「收窄被拒」同一句的 destructive `Alert`，能收的照收。**读者那一份地址里用不上的**另外在筛选条上方说一次、可以关掉——与工作台同一个 `FiltersRefused`（见 [dashboard.md](dashboard.md#筛选d22-fg批-c)「地址里用不上的筛选说一次」）；页面的值被拒只按页面的说，不在那里再说一遍。（见 test/embeddedDashboard.test.tsx「follows what the page holds as it changes, and never tells the address a held value」「ignores an address that names a held filter: the page’s value wins」「opens the reader’s filters at their defaults when the address names none of them」「says what the board refuses of the page’s values, as a refused narrowing」，test/embedRuntime.test.ts「is in force from the first query when the board opens under it」「follows the page, puts a default back for null, and lets go」「answers what the board refuses of the page, every time it is asked, and takes the rest」；浏览器里 stories/view-engine/EmbeddedDashboard.test.stories.tsx「CustomerDetail」：地址跟着下单时间变，锁定的客户不在里面）
- **一块嵌入的板一个声音**：播报区是 `EmbeddedDashboard` 建的那一个，栅格、标签栏、筛选条与搭板子的对话框都说在里面；接线提示的视口只在可编辑一档搭板子时挂（见 [dashboard.md](dashboard.md) 的「一块板一个声音」）。只读的大屏因此没有一个英文的「Notifications」地标。
- **可编辑一档里搭板子时，三态照旧**：锁定的一枚与别的一样带抓手、能挪（锁的是值不是位置），隐藏的不在条上、也就挪不到，别的筛选越过它时它在整张列表里的相对次序不变；挪完页面持有的值原样还在。（见 [dashboard.md](dashboard.md#筛选d22-fg批-c)「调筛选的顺序」；test/embeddedDashboard.test.tsx「reorders the filters on the bar while building, the locked one with them and the hidden one kept held」）
- **板子不收条件树，收窄就是「锁定」**（D26 Q32）：`EmbeddedDashboard` 没有 `scopeFilter`，runtime 也不收（`dashboard.scope.unsupported`）；页面要收窄一块板，是在板上声明那个筛选，再把它锁定或隐藏。`EmbeddedView` 的 `scopeFilter` 留着——记录与分析没有筛选条，它就是那一档锁定：已应用条上单成一组、不带 ✕（README「三态各有一处凭据」）。

## 锁定不是安全边界

宿主锁定的条件是在浏览器里拼进查询的：它只保证读者**在界面上**改不了、看不到别的。改一下页面脚本、直接调接口，就能问到别的客户——租户、归属与权限必须由 Wow 后端强制，对外页面尤其如此（D22）。本包是宿主进程内的库，不照搬 Metabase 的 iframe／签名令牌／SSO：身份与权限属于宿主与后端。README 的嵌入一节中英文都写着这一条。

## 高度

`size`，缺省 `content`：

- **`content`**——放在卡片里的嵌入按内容定高、各部件有自己的上限：记录表格停在 `--fve-record-table-max-h`（70vh）并在里面滚、表头与合计贴住；图表是自己的高度；仪表盘有多少行就多高。
- **`fill`**——整页嵌入（大屏、只有一个视图的页面）填满容器：根是定高弹性列（`data-embed-size="fill"`），记录表格或分析结果接住其余的高度并自己滚，与工作台铺满屏幕时同一条链；仪表盘没有一块可以交出高度，根自己滚。容器得给得出高度，给不出时读作 `content`。（见 test/embeddedView.test.tsx「fills its container when asked, and never refreshes itself when told not to」；浏览器里 stories/view-engine/EmbeddedDashboard.test.stories.tsx「WallScreenReadOnly」量了面与容器的高度）

铺满屏幕仍是宿主的事（D10）：嵌入不长自己的开关，`ref` 交出面，宿主用 `useViewExpansion` 放在自己的 chrome 里。

## 三个场景

每个故事跑一档（`stories/view-engine/`）：

- **首页**（`Home.stories.tsx`，可编辑）：运营组共享的那块板嵌在宿主首页，「编辑」就地加一个标题、「保存」替整组存下（`Home.test.stories.tsx`「Editing」）。
- **客户详情页**（`EmbeddedDashboard.stories.tsx`「CustomerDetail」，可交互）：客户锁定成这一页的客户，下单时间可改；筛选值在页脚的「宿主地址」里来回；追问与「在工作台中打开」经宿主路由、带着这位客户；开了导出，「这个客户的订单」可以「导出数据…」（`EmbeddedDashboard.test.stories.tsx`「CustomerOrdersExport」）。
- **大屏**（「WallScreenReadOnly」，全只读）：暗色、铺满、标题画出来，仓库锁定在华东仓；没有「⋯」、没有可按的组、没有「编辑」、没有「清空」。

记录与分析的两档在 `EmbeddedView.stories.tsx`：缺省是只读，「Interactive」「AnalysisInteractive」跑可交互一档。
