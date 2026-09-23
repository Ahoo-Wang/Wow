# UI 层：Dashboard 视图

栅格、面板 chrome 与面板级告警。三种视图共用的骨架、状态条与 `FilterPanel` 见 [README.md](README.md)；面板与子 runtime 的关系见 [runtime.md#dashboard](../runtime.md#dashboard)。

## DashboardGrid 与几何写回

- `DashboardGrid` 不做自动紧凑，面板按配置中的 `layout` 原样摆放；
- 只有用户动手才把几何写回（`dashboard.place(panelId, layout)`，只应用这一处摆放，见 [runtime.md#dashboard](../runtime.md#dashboard)）——一次拖拽或缩放结束，或者一条键盘命令（见下一节），库自身在挂载或属性变化时算出的布局不写回，因此打开已保存的 Dashboard 不会变脏。（见 test/dashboardUi.test.tsx「DashboardGrid」「with a stored layout the grid would have compacted」）
- **碰撞只有一套规则，是内核的**（`placePanel`，`src/dashboard/layout.ts`）：被摆的面板占它要的格子，被它盖住的面板往下推开，推下去的再压到谁就接着推。库自己的碰撞处理不用——不紧凑时它让缩放的面板直接叠在邻居上、拖过去的面板要么叠上要么和邻居对调行，和键盘走的内核规则是两个答案（R6、R9）。所以 `useGridPlacement`（`ui/gridPlacement.ts`）告诉库「可以叠」（它就什么都不处理），再把内核当作库的 compactor 交进去：手势进行中每一步都按「手势开始时的布局」重新摆一次正在拖的那块，拖下时的预览因此正是松手后应用的布局，被推开的面板在拖走之后也会回来；松手只把这一块交给 `place`，其余由运行时按同一规则推。手势以外（挂载、每次新布局）它什么也不改，屏幕上是准入过的布局原样。（见 test/dashboardPlacement.test.tsx「placing a panel」；浏览器里 stories/view-engine/Dashboard.test.stories.tsx「KeyboardStepPushes」）
- 栅格要的是像素宽度，而容器只有上了屏才知道自己多宽。量它的是 `react-grid-layout` 自己的 `useContainerWidth`，不是这里另写一个 `ResizeObserver`：同一个观察者、同一套取宽规则，没有 `ResizeObserver` 的环境照样能画，连续几次缩放合并到同一帧。容器报 0（被隐藏时就是这样）时这个 hook 照实上报，栅格自己不收，面板保持上一次能画的宽度。（见 test/dashboardUi.test.tsx「follows the container width once it can be measured」）

- **面板按阅读顺序画**：先按行、行内从左到右（内核的 `readingOrder`，`src/dashboard/layout.ts`；面板叫「面板 3」时数的也是这个顺序），与配置里的数组顺序无关，所以 Tab 走的是眼睛走的路；
- **窄于 `md`（768px）时一列**（D22 J）：栅格的列数被库自己的断点读法（`getBreakpointFromWidth`，量的是栅格拿到的宽度，不是给面板写媒体查询）换成 1，面板由内核的 `stackedLayout` 按上面的阅读顺序叠成一列、各保留保存时的高度。这一列是**推导出来的读法**，不写回：抓手、菜单、缩放角都不出现，指针拖拽与缩放关掉，交给 `place` 的手势回调也不接、键盘命令也不落——窄屏里摆出来的位置没有一个能对应回宽布局。容器被隐藏时量到 0 不是手机，断点停在上一次画得出的宽度。原来 414px 下分析面板被挤到 130px 上下，柱标签叠在一起、被截断。（见 test/dashboardUi.test.tsx「in a column narrower than md」；浏览器里无横向滚动、每个面板满宽、标签都在图里，见 stories/view-engine/Dashboard.test.stories.tsx「OnAPhone」）
- **空仪表盘不许诺做不到的事**（U1）：说「这个仪表盘还没有面板」，再用一句说仪表盘是什么（把已保存的记录视图和分析视图并排放在一起看），没有按钮、没有「添加」。今天界面上没有任何添加面板的入口，原来那句「添加一个已保存的记录或分析视图就能在这里看到」指向一扇不存在的门。批 B 的「添加」放在 `DashboardEmpty` 里、`EmptyHeader` 之下的 `EmptyContent`——一块没有面板的板子唯一有地方放第一个动作的位置；（见 test/dashboardUi.test.tsx「says what an empty dashboard is, and promises nothing」）
- 面板里的 Record 视图以 `selectable={false}` 渲染 `RecordTable`：Dashboard 是读数的地方，没有工具栏也没有行动作，没有任何东西读选择，勾选框因此只是一列点不出结果的控件。

## 摆放面板：键盘与指针写同一个 layout

`react-grid-layout` 2.2 没有键盘传感器——拖拽是 `react-draggable` 的、缩放角是 `react-resizable` 的，两者都只听鼠标与触摸。所以键盘等价物不是一个开关，而是本包自己的一组命令（D17 第 7 条：仪表盘编排按可达性缺陷处理）。它们都落在 `dashboard.place()` 上，和一次手势产生的东西完全相同——一块面板的新 `layout`，一步一格，因为拖拽本来也是吸附到列与行的；一步踩到邻居上，邻居照内核的规则往下让开，和指针拖过去一样。

- **两个手柄都有名字，也都答方向键**：抓手（`panel-grip`）移动，东南角（`panel-resize`）缩放，各自对应它用指针做的事。抓手原来是 `aria-hidden` 加一个 `title`——那是对的，当时拖拽没有键盘等价物，给一个键盘够不着的东西起名字比不起更糟；现在它有了，于是它是一个普通控件；
- **菜单把同样八条命令写成字**（`panel-arrange`，`DropdownMenu` 两组：移动 / 大小）：只能靠按下去才发现的键等于没有；
- **边界处是禁用而不是消失**：`arrangeLayout`（在内核里，`src/dashboard/layout.ts`，菜单与按键都调它）的边界就是内核的边界（`x`、`y` 非负，`w`、`h` 至少一格，`x + w` 不越界），所以没有哪条命令能产出 `validateDashboard` 会拒的 layout；一个面板此刻能往哪去是当下的状态、不是权限（D4），菜单里来来去去的条目没人学得会。向下与加高没有远边——仪表盘向下长；
- **每个手柄以自己的面板命名**：「移动『北区订单』」「调整『北区订单』的大小」「摆放『北区订单』」，哪些键能用由 `aria-keyshortcuts` 说，而不写进名字。原来缩放角全板同名（「用方向键调整这个面板的大小」），读屏走一遍板子分不出自己在哪个面板上（U8）。`resizeConfig` 是整张栅格级的属性，库只把 axis 交给这个工厂——但它返回的元素被 `react-resizable` 追加进**栅格项本身**的子节点里，所以栅格项是我们的 `PanelGridItem`，它向子树提供「这是哪个面板、叫什么」，角从 context 读名字与 id；不在栅格项里时它只说「调整这个面板的大小」、什么键也不答；
- **面板叫什么**（`panelName`）：自己的标题；没有标题的视图面板用它显示的视图的标题；内容面板用种类（笔记／图片／链接）；剩下的（视图打不开、又没有标题）按它在板上的位置叫「面板 3」。**从不用 `panel.id`**——那是配置里的键，不是看板子的人起的名字。标题、抓手、角、菜单、正文滚动区、落位播报都用这同一个名字；（见 test/dashboardUi.test.tsx「names an untitled panel by what it shows, never by its id」「names each corner after its own panel」）
- **落位要说出来**：指针能看见面板在自己手下动，键盘只有一个不在屏幕上的 layout，所以整张栅格有一个 `aria-live="polite"` 区域，说这个面板现在在第几列第几行、多宽多高（`label.panel.placed`）。一块板子一次只摆一个面板，所以是一个区域而不是每个面板一个。宽高各是一个带量词的整句（`label.panel.columns`／`-one`、`label.panel.rows`／`-one`），英文不再出「1 columns」；
- **菜单的八条命令成对**：上移／下移、左移／右移、加宽／减宽、加高／减高。原来的「压扁」口语、与「加高」不成对，「收窄」与「加宽」也不成对（U9）；
- **聚焦时角必须看得见**：上游只在指针悬停在面板上时才画那个 20px 的角（`opacity: 0` → `:hover` 时 `1`），键盘到得了却看不见就等于没到。`styles.css` 里补了 `:focus-visible` 的一条。

（见 test/dashboardUi.test.tsx「placed by keyboard」；浏览器里的那两件 jsdom 做不到的事——移动真的到了屏幕上、聚焦时角真的可见——在 stories/view-engine/Dashboard.test.stories.tsx「KeyboardLayout」）

## 刷新是整块板子的

- 仪表盘的刷新同样是标题栏里的那个拆分按钮，但它编辑的是**仪表盘自己的** `refresh.interval`：`DashboardRuntime` 为整块板子持有唯一一个计时器，被引用实例自身的 `refresh` 在其中被忽略，以免两层计时器（[runtime.md#dashboard](../runtime.md#dashboard)）。所以菜单顶上多一句 `label.refresh.panels` 说清这一层关系——控件若什么都不说，看上去就像在给每个面板各设一个间隔。那句话用读者的词：「仪表盘上的所有面板按这里的间隔一起刷新；面板所显示的视图自己保存的刷新间隔在这里不生效」，不说「计时器」「被引用视图」（U9）；
- 主键那一半在引用还在解析时禁用（`dashboard.resolving`，此时没有哪个面板能被刷新），**在任一面板的查询还在途时也禁用**（`dashboard.loading`）：仪表盘自己不跑查询，`state.query` 永远是 `idle`，问它「有没有东西在跑」永远答没有，于是一屏正在加载的面板会被一次点击整片顶掉，而按钮上连个转圈都没有。`useDashboard` 因此自己订阅各个子 runtime——子 runtime 的查询变化不会通知仪表盘的订阅者（那是有意的，否则每个面板每次请求都要让整张栅格重渲染），所以要知道这件事的人得自己去问。（见 test/refreshControl.test.tsx「the dashboard title bar, saying whose timer it is」）

## 面板失败：保留上次的结果，可以重试

- **刷新失败不丢上一次的结果**（R5）：子 runtime 的 `result` 只随成功推进，面板照样画它，上面一条 `QueryStrip`（`stale`）说「〈原因〉 · 显示的是上一次成功的结果」——与两个工作台、`EmbeddedView` 同一句话、同一个部件。从前面板见到 error 就把整块正文换成「查询失败」，断一次网读者正在看的数就没了，而工作台与嵌入视图都留着。只有**背后什么都没有**的失败（第一次就失败）才占正文（`panel-failed`，`Empty` 的形状）。
- **单个面板可以重试**（U4／G5）：两处失败都带「重试」——正文里的 `panel-failed` 在 `EmptyContent` 里放一个按钮，stale 那条线在行尾放 `QueryStrip` 自己的按钮——都调 `dashboard.refreshPanel(panel.id)`，只重跑这一块；从前唯一的出路是标题栏的整板刷新，为一块面板把其余全部重跑一遍。`DashboardPanel` 单独使用、没人给 `onRetry` 时不出按钮。
- 文案复用 `label.query.failed`／`label.query.stale`／`label.query.retry`，没有新词。（见 test/dashboardPlacement.test.tsx「a panel whose query failed」；浏览器里 stories/view-engine/Dashboard.test.stories.tsx「RefreshFailedKeepsData」）

## 面板 chrome 与 warning 标记

- Dashboard 在这里显示尚未被任何已应用面板承载的 warning：自己的（`useDashboard().issues`，不含 `['panels', …]` 路径），以及 draft 里面板级却还没交给面板的——全局条件映射到会告警的面板字段、尚未 Apply，这时 `state.panels` 仍是上一次 applied 的，不说就会被 Save 原样存下；
- **状态条上的面板级发现要说是哪个面板**：内核的句子说「这个面板……」，在面板自己的框里是清楚的，挪到栅格上方的状态条里就什么也没指。`DashboardWorkbench` 把 `nameIssue` 交给 `WorkbenchShell`，状态条的 error 与 warning 都先过它：路径是 `['panels', i, …]` 的发现改说成「「北区订单」：……」（`label.panel.finding`），名字取自 draft 里的那个面板（发现就是对 draft 下的）；
- **面板标题是标题元素**：工作台里是 `h3`，在视图自己的 `h2`（`ViewHeader`）之下，按标题跳读能一个个面板走到；原来是一个带样式的 `div`。嵌入没有自己的标题，面板标题默认 `h2`（在宿主页面的 `h1` 之下），宿主用 `EmbeddedView` 的 `headingLevel`（`DashboardGrid` 同名属性）按自己的大纲改——只有宿主知道自己的标题层级，首页那块板子就是在「运营概览」`h1` 下（axe `heading-order`，见 stories/view-engine/Home.test.stories.tsx）；
- **嵌入的仪表盘同样画整张栅格**（R3）：一个面板的 error 只停那一个面板（runtime 照跑其余的），`EmbeddedView` 却把它当成整块仪表盘的，用一条红条顶掉了整张栅格。现在路径是 `['panels', i, …]` 的 error 与 warning 都归面板自己说，只有仪表盘自己的（含落在 `['panels']` 本身、不属于任何一个面板的「面板太多」）才进嵌入上方的条——与工作台一致；（见 test/embeddedView.test.tsx「draws the grid around a panel that is out」、stories/view-engine/EmbeddedView.test.stories.tsx「DashboardWithAPanelOut」）
- **出不来的面板说为什么、找谁**（U5）：正文是一个 `Empty`，标题是原因，描述是出路，同一个词不说两遍（原来是「这个面板不可用」下面再一句「……不可用」，而且不说为什么）。原因按内核的 issue code 映射成读者的话（`PanelUnavailable.tsx` 的 `OUTAGES`）——视图被删或你没有权限（`dashboard.panel.unavailable`，存储分不出是哪一种，面板也就不猜）、没能打开、指向的不是记录或分析视图、视图并不对这块板的所有读者开放、仪表盘的筛选接不上这个面板（`dashboard.binding.*`）、视图保存的设置已经用不了（子视图自己的内核发现）；仪表盘自己关于一个面板的其余规则（位置、笔记长度、链接协议）本来就是读者的话，照说。**从不出现** instanceId、scope 代码、字段名或存储抛出的 `error.message`——那几条内核句子也一并改掉了占位符。出路是今天真有的那条：板上还不能移除或替换面板（批 B），所以说该找谁——「请视图的所有者共享给你／打开它修正」「请这个仪表盘的维护者替换或移除这个面板」；没有一颗按下去什么也做不了的按钮。（见 test/dashboardUi.test.tsx「says why a panel is out, in words its reader uses」、stories/view-engine/Dashboard.test.stories.tsx「PanelUnavailable」）
- 面板正文自己会滚动（面板高度由布局定，内容不一定装得下），所以它带 `tabIndex={0}` 与 `role="group"`、以面板标题为名：能滚动而键盘到不了的区域是一条实打实的缺陷。记录面板本来靠行里的控件凑巧满足了这一条，图表面板则一个可聚焦元素都没有——图表是一张 `role="img"`，里面没有任何能拿焦点的东西，见 [analysis.md#图表怎么被读出来](analysis.md#图表怎么被读出来)；
- Apply 之后由面板承载，条里不再重复。面板级的由面板自己呈现：不可用的面板在正文里说明理由（首个 error，或独自到来的那条 warning），随之而来的其余 warning 仍在头部标记里，能运行却带 warning 的面板（子 runtime 对自身配置的 warning，以及它上一次**结果**自身的 warning——汇总退回本页、分析填满上限——都已重定址到面板，见 [runtime.md#dashboard](../runtime.md#dashboard)）照常显示视图，头部加 `panel-warning` 标记并以 `data-warning` 标出边框。（见 test/dashboardWorkbench.test.tsx「DashboardWorkbench」、test/dashboardContent.test.tsx）
- 这个标记是 `IconTooltip`（`ui/IconButton.tsx`）而不是一个挂着 `title` 的 `span`：`title` 只有鼠标悬停才出得来，键盘与触屏都够不着，于是 warning 说了什么就只有拿鼠标的人读得到。走同一条通路之后，同一份 `messages.issues(warnings)` 既是控件名也是气泡文案，聚焦与轻点都能打开。颜色（`text-warning`）落在图标上而不是按钮上——那是标记本身的含义，按钮保留 ghost 变体自己的悬停与聚焦配色。（见 test/iconTooltips.test.tsx）

## 内容面板：排版与占位

- **markdown 面板不引 `@tailwindcss/typography`，而是把那几条覆盖写成一个具名常量**（`MARKDOWN_PROSE`）。优先复用第三方是这一包的默认，这里是反过来的那一种，理由写在调用处：`prose` 是一栏文章——`max-width: 65ch`、围绕 16–20px 正文的字号阶梯，即便 `prose-sm` 的 `h1` 也有 30px 上下，比面板自己的标题还大，而面板多宽是用户拖出来的；它的颜色是一套写死的 gray，要让它认 `--foreground`／`--muted-foreground`／`--primary` 就得在 `styles.css` 里重定义十六个 `--tw-prose-*`，比它要替掉的那四条规则还多，而且颜色从此有两个决定的地方；它大部分规则是给这个面板画不出来的元素准备的（原始 HTML 是关的）。原来写在类名里的 `prose-sm` **一直是死的**——插件从未安装，编译出的样式表里一条 `prose` 规则也没有；
- **图片加载失败的占位就是 `Empty`**（`EmptyMedia variant="icon"` + `EmptyDescription`），与 `DashboardGrid` 里「面板不可用」「查询失败」同一个形状，不再是一个自己居中的 `div`。不放标题：作者写的 `alt` 是仅有的那句话，上面再加一句「图片加载失败」等于把同一件事说两遍；`alt` 缺席时才用 `label.image.failed` 当那句描述。（见 test/dashboardContent.test.tsx）
- **带链接的图片要有名字**：链接的名字是它里面的文字，没有 `alt` 的图片什么也不给，于是读屏只念一个「链接」。没有 `alt` 时用面板标题、再没有就用「打开链接的页面」（`label.image.link`），写在链接里的一个 `sr-only` 里；
- **在新标签页打开要先说**：面板上的每一个链接（笔记里的、链接面板的、图片的）都 `target="_blank"`，看不见标签栏的人会发现原来的页面突然到了另一页后面、后退也没用。所以每个链接的名字末尾带一句「（在新标签页中打开）」（`label.link.new-tab`，`sr-only`）：只说不画——链接面板已经画了外链箭头，笔记与图片没地方再放一个图标。（见 test/dashboardContent.test.tsx「says every link opens a tab of its own」）

## 阶段 3 的交互（定稿，待实现）

[仪表盘交互稿](https://claude.ai/artifact/SVjSG6BH7WVAnqthQDh42y) 2026-09-23 定稿（用户：十条待拍板「全按推荐」），方向见 [D22](../decisions.md#d22-仪表盘与嵌入视图参照-metabase2026-09-23)。批 B～D 按下面逐屏实现；实现落地后把每条改写成现状并附测试名。

三条贯穿的原则：**读与搭分开**（平时不可拖、点不坏，「编辑」进入搭的状态，「完成」保存、「取消」放弃，系统仪表盘只有「另存为」）；**一个概念一种样子**（追问菜单、可视化面板、条件编辑器、候选值全部复用分析与记录视图的部件）；**说清作用范围**（每个筛选作用到哪些面板、哪个面板不受它影响，在面板上看得到）。

- **A 编辑模式与「添加」**（批 B）：标题栏「编辑」只对有编辑权的人出现；编辑中顶上一条编辑条：「＋ 添加 ▾」（数据：已保存的视图…、新建分析…；内容：标题、文字、图片、链接）、「筛选 ＋」、取消、完成。新面板放进当前可见区域的第一个空位，默认宽度按种类（指标卡 6、图 12、表 24，按 24 列计）。空仪表盘给同样三个入口；没有编辑权时只说「还没有面板」。**编辑中面板按草稿实时重跑，「完成」才写回**。
- **B 选一个视图**（批 B）：对话框与视图切换器同一套分组（系统／共享／我的）与种类图标，可搜索、可筛种类与数据定义；已在板上的仍可再加（标「已在板上」）。**共享板引用个人视图：允许，当场标「只有你看得到」，面板菜单给「复制为共享视图并替换」**。
- **C 在仪表盘里新建分析**（批 B）：大对话框，先选数据定义，里面就是分析视图的托盘与结果（自动运行照常）；「放进仪表盘」存成只属于这块板的视图（随板保存、删除、受众）。**首版只新建分析**；**面板菜单「另存为视图…」把它提成普通视图**，面板改为引用它。
- **D 面板菜单与展示覆盖**（批 B）：「⋯」分两组——看（在工作台中打开↗，带全局筛选；刷新这个面板；导出数据）与改（编辑中：改标题、改这里的展示、点击时…、替换视图、复制、移到标签页、移除）。**展示覆盖只管怎么看**（布局、图型与图的选项、表格合计行），不改问题；面板头标「此处改为〈图型〉」，菜单可「恢复为视图的样子」。
- **E 标签页与 24 列**（批 B）：筛选在标签之上、对所有标签生效；一个筛选在当前标签里没有受影响的面板时淡一档并说明；只有一个标签时不画标签栏；编辑中可加、改名、排序、删除（带面板删除先确认）。**记住每人上次看的标签（个人偏好，不入配置），当前标签写进地址**。24 列，旧 12 列布局读取时 `x`、`w` 乘 2、存回才写新格式。
- **F 筛选条**（批 C）：页头一排；值控件与候选值复用条件编辑器；**必填**带星号、永远有值（清空回默认）；时间分组是整板的「按日｜周｜月」；未接上的面板头上「不受『〈筛选〉』影响」。**筛选值写进地址，不写进配置**（默认值才是配置）。
- **G 加筛选与接线**（批 C）：设置里定类型、名字、默认值、可多选、必填、候选值来源（接上的字段／自己列一组）；在一个面板上选字段后**按同名同类型自动连接其余面板（跨数据定义也接）**，底部提示接了几个、可撤销，以后新加的面板同样自动接；每个面板底部一条接线条，可改、可断，跨定义手动接的标「手动」，没有可接的字段直说。
- **H 默认：追问菜单**（批 D）：点柱、点行、点扇区，弹与分析视图同一个追问菜单，标题带上当前全局筛选（「仓库 属于 华南 · 本月」），三项都在工作台打开（↗），仪表盘不变。**宿主没给路由钩子时，不出追问菜单**。
- **I 交叉筛选**（批 D）：作者在「点击时…」选「更新仪表盘筛选：〈筛选〉」；读者点一个值，筛选条上随之变化并注明来自哪个面板，其余接线面板重算；**被点的面板不筛自己、只高亮点中的一组；再点一次同一个值撤销**；面板头标「点击筛选〈筛选〉」。第三个选项「去另一个视图或页面」带上点中的值。
- **J 窄屏**：窄于 768px 时按阅读顺序排成一列、各保留高度，布局是推导出来的、不写回——**这一半已落地**（见上文「DashboardGrid 与几何写回」）；筛选条横向滚动；**窄屏编辑只允许改标题、移除、调顺序**（随批 B 的编辑模式）。
