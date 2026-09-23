# UI 层：Dashboard 视图

栅格、面板 chrome 与面板级告警。三种视图共用的骨架、状态条与 `FilterPanel` 见 [README.md](README.md)；面板与子 runtime 的关系见 [runtime.md#dashboard](../runtime.md#dashboard)。

## DashboardGrid 与几何写回

- `DashboardGrid` 画的是配置中的 `layout` 原样，库自己不紧凑；**24 列**（D22 E），存的是旧的 12 列的板子由运行时读成 24 列、每个面板落在原来的像素上（[model.md#dashboard-配置](../model.md#dashboard-配置)）；
- 只有用户动手才把几何写回（`dashboard.place(panelId, layout)`，只应用这一处摆放，见 [runtime.md#dashboard](../runtime.md#dashboard)）——一次拖拽或缩放结束，或者一条键盘命令（见下一节），库自身在挂载或属性变化时算出的布局不写回，因此打开已保存的 Dashboard 不会变脏。（见 test/dashboardUi.test.tsx「DashboardGrid」「with a stored layout the grid would have compacted」）
- **碰撞只有一套规则，是内核的**（`placePanel`，`src/dashboard/layout.ts`）：被摆的面板占它要的格子，被它盖住的面板让开——正好落在下一块上时两块对调（被压的那块上到它原来的位置，与 react-grid-layout 纵向紧凑对拖着的那块一样），否则往下推，推下去的再压到谁就接着推；**然后整个标签页上浮压紧**（批 A 走查，照 Metabase）：拖走的面板原位由下面的补上，放到空处的面板浮到有东西托住为止，动手之后不留洞。只有动手才压紧——打开保存的仪表盘什么也不动，旧板子里本来的洞第一次动手时一并收掉。（见 test/dashboardLayout.test.ts「placePanel」）库自己的碰撞处理不用——不紧凑时它让缩放的面板直接叠在邻居上、拖过去的面板要么叠上要么和邻居对调行，和键盘走的内核规则是两个答案（R6、R9）。所以 `useGridPlacement`（`ui/gridPlacement.ts`）告诉库「可以叠」（它就什么都不处理），再把内核当作库的 compactor 交进去：手势进行中每一步都按「手势开始时的布局」重新摆一次正在拖的那块，拖下时的预览因此正是松手后应用的布局，被推开的面板在拖走之后也会回来；松手只把这一块交给 `place`，其余由运行时按同一规则推。手势以外（挂载、每次新布局）它什么也不改，屏幕上是准入过的布局原样。（见 test/dashboardPlacement.test.tsx「placing a panel」；浏览器里 stories/view-engine/Dashboard.test.stories.tsx「KeyboardStepPushes」）
- 栅格要的是像素宽度，而容器只有上了屏才知道自己多宽。量它的是 `react-grid-layout` 自己的 `useContainerWidth`，不是这里另写一个 `ResizeObserver`：同一个观察者、同一套取宽规则，没有 `ResizeObserver` 的环境照样能画，连续几次缩放合并到同一帧。容器报 0（被隐藏时就是这样）时这个 hook 照实上报，栅格自己不收，面板保持上一次能画的宽度。（见 test/dashboardUi.test.tsx「follows the container width once it can be measured」）

- **面板按阅读顺序画**：先按行、行内从左到右（内核的 `readingOrder`，`src/dashboard/layout.ts`；面板叫「面板 3」时数的也是这个顺序），与配置里的数组顺序无关，所以 Tab 走的是眼睛走的路；
- **窄于 `md`（768px）时一列**（D22 J）：栅格的列数被库自己的断点读法（`getBreakpointFromWidth`，量的是栅格拿到的宽度，不是给面板写媒体查询）换成 1，面板由内核的 `stackedLayout` 按上面的阅读顺序叠成一列、各保留保存时的高度。这一列是**推导出来的读法**，不写回：抓手、菜单、缩放角都不出现，指针拖拽与缩放关掉，交给 `place` 的手势回调也不接、键盘命令也不落——窄屏里摆出来的位置没有一个能对应回宽布局。容器被隐藏时量到 0 不是手机，断点停在上一次画得出的宽度。原来 414px 下分析面板被挤到 130px 上下，柱标签叠在一起、被截断。（见 test/dashboardUi.test.tsx「in a column narrower than md」；浏览器里无横向滚动、每个面板满宽、标签都在图里，见 stories/view-engine/Dashboard.test.stories.tsx「OnAPhone」）
- **空仪表盘只许诺做得到的事**（U1，D22 A）：说「这个仪表盘还没有面板」，再用一句说仪表盘是什么（把已保存的记录视图和分析视图并排放在一起看）。**能搭这块板的人**在 `EmptyHeader` 之下的 `EmptyContent` 里拿到同样的第一步——「添加视图…」、「新建分析…」（只在有人提供那个对话框时，见下文「扩展」）、「添加标题」——按下去同时进入编辑；**不能搭的人**什么按钮也没有，一个按不动的入口就是那扇不存在的门。（见 test/dashboardUi.test.tsx「says what an empty dashboard is, and promises nothing」、test/dashboardBuilding.test.tsx「offers an empty board its first steps, and a reader who cannot build it nothing」）
- 面板里的 Record 视图以 `selectable={false}` 渲染 `RecordTable`：Dashboard 是读数的地方，没有工具栏也没有行动作，没有任何东西读选择，勾选框因此只是一列点不出结果的控件。

## 摆放面板：键盘与指针写同一个 layout

`react-grid-layout` 2.2 没有键盘传感器——拖拽是 `react-draggable` 的、缩放角是 `react-resizable` 的，两者都只听鼠标与触摸。所以键盘等价物不是一个开关，而是本包自己的一组命令（D17 第 7 条：仪表盘编排按可达性缺陷处理）。它们都落在 `dashboard.place()` 上，和一次手势产生的东西完全相同——一块面板的新 `layout`，一步一格，因为拖拽本来也是吸附到列与行的；一步踩到邻居上，邻居照内核的规则往下让开，和指针拖过去一样。

- **两个手柄都有名字，也都答方向键**：抓手（`panel-grip`）移动，东南角（`panel-resize`）缩放，各自对应它用指针做的事。抓手原来是 `aria-hidden` 加一个 `title`——那是对的，当时拖拽没有键盘等价物，给一个键盘够不着的东西起名字比不起更糟；现在它有了，于是它是一个普通控件；
- **菜单把同样八条命令写成字**（`panel-arrange`，`DropdownMenu` 两组：移动 / 大小）：只能靠按下去才发现的键等于没有；
- **一步是什么、何时禁用**：`arrangePanel`（在内核里，`src/dashboard/layout.ts`，菜单与按键都调它，看的是整块板子）。左右与大小一步一格；上下是压紧的板子上的一步——往下挪一行会被上浮送回原处，所以「下移」是让它落到别处的最小一步，也就是越过下面那块、两块对调，「上移」同理（批 A 走查）。边界就是内核的边界（`x`、`y` 非负，`w`、`h` 至少一格，`x + w` 不越界），所以没有哪条命令能产出 `validateDashboard` 会拒的 layout；一条什么也不会改变的命令——一列最下面那块的「下移」、第一列的「左移」——在菜单里**禁用而不是消失**：一个面板此刻能往哪去是当下的状态、不是权限（D4），菜单里来来去去的条目没人学得会。落位播报说的是压紧之后它停在哪。（见 test/dashboardLayout.test.ts「arranging by keyboard」、test/dashboardPlacement.test.tsx「trades places with the panel above on a keyboard step up」）
- **每个手柄以自己的面板命名**：「移动『北区订单』」「调整『北区订单』的大小」「摆放『北区订单』」，哪些键能用由 `aria-keyshortcuts` 说，而不写进名字。原来缩放角全板同名（「用方向键调整这个面板的大小」），读屏走一遍板子分不出自己在哪个面板上（U8）。`resizeConfig` 是整张栅格级的属性，库只把 axis 交给这个工厂——但它返回的元素被 `react-resizable` 追加进**栅格项本身**的子节点里，所以栅格项是我们的 `PanelGridItem`，它向子树提供「这是哪个面板、叫什么」，角从 context 读名字与 id；不在栅格项里时它只说「调整这个面板的大小」、什么键也不答；
- **面板叫什么**（`panelName`）：自己的标题；没有标题的视图面板用它显示的视图的标题；标题卡片用它的字；内容面板用种类（标题／笔记／图片／链接）；剩下的（视图打不开、又没有标题）按它在板上的位置叫「面板 3」。**从不用 `panel.id`**——那是配置里的键，不是看板子的人起的名字。**板子起的名字不重名**（`panelNames`，批 A 走查）：两块没标题的笔记原来都叫「笔记」，抓手、发现、落位播报按名字指向的是两块；现在按阅读顺序编号「笔记」「笔记 2」，并绕开作者起的标题（作者自己起了两个同名的，那是作者的）。标题、抓手、角、菜单、正文滚动区、落位播报都用这同一个名字；（见 test/dashboardUi.test.tsx「names an untitled panel by what it shows, never by its id」「numbers the names it makes up, in reading order, around the titles given」「names each corner after its own panel」）
- **标题卡片**（`heading`，分节用）：它的字就是面板的标题元素（`panelName`），卡片上**只有这个标题**、字号大一档（`text-base`）、没有正文——没有一块可滚动、可聚焦却空着的区域；新加的标题卡片叫「新的分节」，加上就地改名。标题层级与其它面板同级（工作台里 `h3`）：分节卡片是读者扫板子时的路标，把它升一级会让它下面的面板在大纲里变成它的子节，而栅格上的「下面」并不是从属。（见 test/dashboardBuilding.test.tsx「adds a heading and names it in place」）
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
- **出不来的面板说为什么、找谁**（U5）：正文是一个 `Empty`，标题是原因，描述是出路，同一个词不说两遍（原来是「这个面板不可用」下面再一句「……不可用」，而且不说为什么）。原因按内核的 issue code 映射成读者的话（`PanelUnavailable.tsx` 的 `OUTAGES`）——视图被删或你没有权限（`dashboard.panel.unavailable`，存储分不出是哪一种，面板也就不猜）、没能打开、指向的不是记录或分析视图、视图并不对这块板的所有读者开放、仪表盘的筛选接不上这个面板（`dashboard.binding.*`）、视图保存的设置已经用不了（子视图自己的内核发现）；仪表盘自己关于一个面板的其余规则（位置、笔记长度、链接协议）本来就是读者的话，照说。**从不出现** instanceId、scope 代码、字段名或存储抛出的 `error.message`——那几条内核句子也一并改掉了占位符。出路按读者分：**读板子的人**被告诉该找谁——「请视图的所有者共享给你／打开它修正」「请这个仪表盘的维护者替换或移除这个面板」，没有一颗按下去什么也做不了的按钮；**正在搭板子的人**就是那句话要找的人，于是那句换成「换成另一个视图，或把它从仪表盘移除」，下面是真按钮：数据面板「替换视图…」、内容面板「改内容…」，都有「从仪表盘移除」（与面板菜单同一个确认）。（见 test/dashboardUi.test.tsx「says why a panel is out, in words its reader uses」、test/dashboardBuilding.test.tsx「offers the way out as buttons rather than a person to ask」、stories/view-engine/Dashboard.test.stories.tsx「PanelUnavailable」）
- 面板正文自己会滚动（面板高度由布局定，内容不一定装得下），所以它带 `tabIndex={0}` 与 `role="group"`、以面板标题为名：能滚动而键盘到不了的区域是一条实打实的缺陷。记录面板本来靠行里的控件凑巧满足了这一条，图表面板则一个可聚焦元素都没有——图表是一张 `role="img"`，里面没有任何能拿焦点的东西，见 [analysis.md#图表怎么被读出来](analysis.md#图表怎么被读出来)；
- Apply 之后由面板承载，条里不再重复。面板级的由面板自己呈现：不可用的面板在正文里说明理由（首个 error，或独自到来的那条 warning），随之而来的其余 warning 仍在头部标记里，能运行却带 warning 的面板（子 runtime 对自身配置的 warning，以及它上一次**结果**自身的 warning——汇总退回本页、分析填满上限——都已重定址到面板，见 [runtime.md#dashboard](../runtime.md#dashboard)）照常显示视图，头部加 `panel-warning` 标记并以 `data-warning` 标出边框。（见 test/dashboardWorkbench.test.tsx「DashboardWorkbench」、test/dashboardContent.test.tsx）
- **面板上的图表提示说列标题**：头部标记里的发现先过 `analysisIssueNamer`（`ui/analysis/issueNames.ts`，工作台与嵌入视图用的同一个），维度与指标按列标题说、不说别名；`chart.as-table` 说出图型与原因（「柱状图画不了这个结果（最多两个维度），先以表格显示」）。这一条原来根本到不了面板：子 runtime 只有 warning 被重定址到面板，这条是 note，一张退回表格的图在仪表盘里一声不吭——现在子 runtime 对自身配置的 note 同样交给面板（`panelIssues`，见 [runtime.md#dashboard](../runtime.md#dashboard)）。（见 test/dashboardBuilding.test.tsx「says why a chart shows as its table, in the words of the picker」）
- 这个标记是 `IconTooltip`（`ui/IconButton.tsx`）而不是一个挂着 `title` 的 `span`：`title` 只有鼠标悬停才出得来，键盘与触屏都够不着，于是 warning 说了什么就只有拿鼠标的人读得到。走同一条通路之后，同一份 `messages.issues(warnings)` 既是控件名也是气泡文案，聚焦与轻点都能打开。颜色（`text-warning`）落在图标上而不是按钮上——那是标记本身的含义，按钮保留 ghost 变体自己的悬停与聚焦配色。（见 test/iconTooltips.test.tsx）

## 内容面板：排版与占位

- **markdown 面板不引 `@tailwindcss/typography`，而是把那几条覆盖写成一个具名常量**（`MARKDOWN_PROSE`）。优先复用第三方是这一包的默认，这里是反过来的那一种，理由写在调用处：`prose` 是一栏文章——`max-width: 65ch`、围绕 16–20px 正文的字号阶梯，即便 `prose-sm` 的 `h1` 也有 30px 上下，比面板自己的标题还大，而面板多宽是用户拖出来的；它的颜色是一套写死的 gray，要让它认 `--foreground`／`--muted-foreground`／`--primary` 就得在 `styles.css` 里重定义十六个 `--tw-prose-*`，比它要替掉的那四条规则还多，而且颜色从此有两个决定的地方；它大部分规则是给这个面板画不出来的元素准备的（原始 HTML 是关的）。原来写在类名里的 `prose-sm` **一直是死的**——插件从未安装，编译出的样式表里一条 `prose` 规则也没有；
- **图片加载失败的占位就是 `Empty`**（`EmptyMedia variant="icon"` + `EmptyDescription`），与 `DashboardGrid` 里「面板不可用」「查询失败」同一个形状，不再是一个自己居中的 `div`。不放标题：作者写的 `alt` 是仅有的那句话，上面再加一句「图片加载失败」等于把同一件事说两遍；`alt` 缺席时才用 `label.image.failed` 当那句描述。（见 test/dashboardContent.test.tsx）
- **带链接的图片要有名字**：链接的名字是它里面的文字，没有 `alt` 的图片什么也不给，于是读屏只念一个「链接」。没有 `alt` 时用面板标题、再没有就用「打开链接的页面」（`label.image.link`），写在链接里的一个 `sr-only` 里；
- **在新标签页打开要先说**：面板上的每一个链接（笔记里的、链接面板的、图片的）都 `target="_blank"`，看不见标签栏的人会发现原来的页面突然到了另一页后面、后退也没用。所以每个链接的名字末尾带一句「（在新标签页中打开）」（`label.link.new-tab`，`sr-only`）：只说不画——链接面板已经画了外链箭头，笔记与图片没地方再放一个图标。（见 test/dashboardContent.test.tsx「says every link opens a tab of its own」）

## 搭板子（D22 A～E，批 B2、B3）

**读与搭分开**（D22 A）：读板子时什么都不动——没有抓手、缩放角、摆放菜单，指针拖不动，键盘命令不落；标题栏里保存按钮旁边一颗「编辑」进入搭的状态，面板上的这些才出现。「编辑」**只给能保存这块板的人**（`SaveCommands.can.save`：系统仪表盘只读、没有保存权限的读者都没有），系统仪表盘因此只有「另存为」（D4）。编辑状态是界面的，属于这一次打开（`DashboardWorkbench` 按 runtime id 记）：换一个视图就回到读。它**不是** runtime 的 `setEditing`——那个是编辑器拿着焦点、暂停自动刷新的意思，条件面板一失焦就会把它关掉。（见 test/dashboardBuilding.test.tsx「offers 编辑 to whoever may save it, and nothing moves until it is pressed」「offers a system dashboard 另存为 and no 编辑」「offers no 编辑 to a reader who may not save the board」；浏览器里 stories/view-engine/DashboardBuilding.test.stories.tsx「NoGripsUntilBuilding」「SystemDashboardHasNoEdit」）

- **编辑条**（`ui/dashboard/EditBar.tsx`）在栅格上方、全局筛选之下：「正在编辑」与一句说明（面板随改随跑；按「完成」才保存）、「＋ 添加 ▾」、「取消」、「完成」。它是一个以「正在编辑」为名的 `region`。**编辑中提交与回退只有一处**（用户 2026-09-23 定：一件事一种做法）：标题栏上的保存按钮与「已修改 ↺」都收起来——「完成」就是那次保存、「取消」就是那次还原，旁边再有一颗保存或一个 ↺ 就是同一条命令换个名字，只会让人猜哪颗才算。离开编辑（「完成」或「取消」）之后标题栏照旧：有未保存的修改（例如正在拼的全局条件）就有「已修改 ↺」。开关是 `WorkbenchShell`／`ViewHeader` 的 `commitElsewhere`。（见 test/dashboardBuilding.test.tsx「leaves 「已修改 ↺」 to the edit bar while the board is built」；浏览器里「BuildFromEmpty」「CancelReverts」）「筛选 ＋」在「添加」旁边（批 C2，见下文「筛选」）。
- **「完成」就是保存**：走 `SaveCommands.save` 同一条路，共享板先问一句、点名这块板（#1836 的 `SharedSaveConfirm`），个人板直接存；**从没保存过的板**弹首存对话框问名字与受众；没有改动就直接退出、什么也不写；写没落地（冲突、拒绝、结果未知）就留在编辑中，出了什么事照旧由标题下的 `WriteOutcome` 说。被整板 error 挡住的草稿「完成」禁用，原因在状态行里。（见 test/dashboardBuilding.test.tsx「saves on 完成 and reads the board again」「asks before 完成 writes over a shared board, naming it」「leaves at once on 完成 with nothing to save」）
- **「取消」是 `revert`**：有改动先问——与 ↺ 同一个问题、同一个对话框（`RevertDialog`）——再回到保存的样子并退出；没有改动直接退出。从没保存过的板没有可回的地方，所以没有「取消」。（见 test/dashboardBuilding.test.tsx「puts back the saved board on 取消, asking first」；浏览器里「CancelReverts」）
- **焦点**：按「编辑」时这颗按钮离开标题栏，焦点落到编辑条的「正在编辑」上（`tabIndex=-1`，只被送达、不是 Tab 站）；「完成」「取消」之后回到重新出现的「编辑」上。都只在焦点真丢了（落到 `body`）时才做——从空板子的第一步进来，焦点已经在对话框或标题的输入框里。
- **编辑中面板按草稿实时重跑，「完成」才写回**：每一次增删改都是 runtime 的一条 `DashboardEditing` 命令（`useDashboard().edit`），同时写进草稿与屏幕，正在编辑的全局筛选照样待应用（[runtime.md#dashboard](../runtime.md#dashboard)）。

### 添加（D22 A、B）

- **「＋ 添加 ▾」**（`ui/dashboard/AddMenu.tsx`，Base UI 菜单，两组）：数据——「已保存的视图…」「新建分析…」（后者只在 `onAddOwnedAnalysis` 在时——`DashboardWorkbench` 总是给，见「扩展」；嵌入或宿主自拼的板子没有它就没有这一项）；内容——「标题」「文字…」「图片…」「链接…」。带省略号的会先问一些东西；标题直接放上板，**就地改名**（焦点直接在名字框里，所以这一项让菜单不把焦点还给触发器）。
- **新面板放哪、多大**：当前标签页、从读者屏幕上能看到的第一行起的第一个空位（`fromRow`，按栅格顶边滚出视口多少行算），大小按种类（`defaultPanelSize`：指标卡 6、图 12、表格 24，按 24 列）。已保存的视图**先读进来再放**（`DashboardRuntime.preload`）——不读就不知道它是指标卡还是表格，从前一律按图的 12 列放；读进来的引用直接用于这个面板的子 runtime，不多读一次。（见 test/dashboardBuilding.test.tsx「adds a saved view from the picker, sized by what it shows」）
- **选一个视图**（`ui/dashboard/ViewPicker.tsx`）：一个对话框，列出每个数据定义下的记录与分析视图（每次打开现读，刚在别处存的也在），**与视图切换器同一套分组**——系统视图、共享视图、我的视图——每行带种类图标、系统视图的锁、「种类 · 数据」一行说明；按名称搜索，按种类（全部／记录／分析，`ToggleGroup`）与数据定义（多于一个时，`Select`）收窄。**已经在板上的仍可再加**，行尾标「已在板上」（同一个视图两块面板、各看各的，是正常用法）；**共享板上的个人视图**当场标「只有你看得到」——允许放（D22 B，面板上随后是 `dashboard.panel.scope-too-narrow` 的 warning）。「替换视图…」用同一个对话框，标题换成「替换「X」显示的视图」。列表读失败的定义说一行 warning，其余照列。
- **内容的小表单**（`ui/dashboard/ContentEditor.tsx`）：文字（Markdown，默认一句提示语，`MAX_MARKDOWN_LENGTH`）、图片（地址必填、描述、点击时打开、显示方式「完整显示／铺满裁切」）、链接（一条一组：文字、地址、说明，可加可删，至多 `MAX_PANEL_LINKS`），每种都可选一个面板标题。内核会拒的东西——地址不是 http／https／mailto／相对路径、链接没有文字——**在字段上先说**（`data-invalid` + `aria-invalid` + `FieldError`，第一次提交之后才标），不写一块会被准入拒掉的面板。改的时候清空的可选项是删掉那个键，不留一个空字符串让内核拒。（见 test/dashboardBuilding.test.tsx「writes a note, a picture and links through their forms」）
- 加上、复制、移除都用一个 `aria-live` 区域说一句（「已添加「X」」「已复制「X」」「已移除「X」」）：指针看得见面板出现或消失，别人听不见。对话框关上时焦点回到打开它的那颗按钮——「＋ 添加」或面板的「⋯」——而不是跟着已关掉的菜单项落到 `body`（`FinalFocus`）。

### 面板菜单（D22 D）

每块面板标题行末尾一颗「⋯」（`ui/dashboard/PanelMenu.tsx`，名字「「X」的操作」），两组，**一项只在做得到时出现**（D4），一项都没有就不画这颗按钮：

- **看**（读与搭都有）：「在工作台中打开」——只在宿主给了路由钩子 `onOpenView(instanceId, filter)` 时（`DashboardWorkbench` 与 `EmbeddedView` 同名属性），`filter` 是面板此刻带着的全局条件、**已经映射成那个视图自己的字段名**（子 runtime 的 `scopeFilter`），宿主可以直接当作用域交给工作台；板内分析没有已保存的视图，没有这一项。「刷新这个面板」（`refreshPanel`）。「导出数据…」这一批没有：导出窗口今天是工具栏里自带触发器的一个窗口、交付逻辑在记录视图的部件里，面板要复用得先把它拆成可受控的——记在 todo。
- **改**（只在编辑中）：「改标题」（就地，Enter 或离开保留、Escape 放弃，空白回到按内容命名；标题卡片改的就是它的字）、「改这里的展示…」（分析面板，有 `onEditPresentation` 时）、「恢复为视图的样子」（面板有自己的展示覆盖、有 `onResetPresentation` 时）、「改内容…」（文字、图片、链接）、「替换视图…」、「复制」（原面板旁边第一个空位）、「移到标签页 ›」（板子有两个以上标签页时，子菜单列出其余的，没名字的按位置叫「标签页 2」）、「另存为视图…」（板内分析，且有 `onSaveOwnedAsView` 时）、「从仪表盘移除」（先问）。「点击时…」随批 D。
- **移除先问**（`RemovePanelDialog`）：说走的是什么、留下的是什么——引用的视图不会被删／板内分析会一起移除／内容会一起移除——以及编辑条的「取消」还能找回。问而不是「撤销」提示：runtime 没有单步撤销，「取消」会连别的改动一起撤掉。移除后焦点落到编辑条上。（见 test/dashboardBuilding.test.tsx「a panel's menu (D22 D)」各条；浏览器里「BuildFromEmpty」）
- **窄屏只改标题、移除**（D22 J）：一列的读法是推导出来的，里面摆不出能写回的位置，而复制、换标签页、添加都是一次摆放——所以窄于 `md` 时编辑条上没有「添加」，面板菜单的「改」只有改标题与移除；调顺序这一批没做（todo）。

### 标签页（D22 E，批 B3）

- **标签栏在编辑条之下、面板之上，两个及以上才画**（`ui/dashboard/DashboardTabs.tsx`，经扩展的 `tabBar` 交给板子，嵌入视图里是栅格的 `header`）：一个标签页读作没有标签页。全局筛选对所有标签页生效，所以它在更上面。读的时候它是 Base UI 的 `Tabs`（`line` 变体，`tablist`），方向键在标签之间走、按下就切；栅格那一块随之是以当前标签命名的 `tabpanel`（`data-slot="dashboard-tab-panel"`）。**每个标签页是一张自己的栅格**：`DashboardGrid` 只画、只摆 `dashboard.tab` 那一页的面板，键盘摆放的邻居也只算这一页的；一页上什么都没有时说「这个标签页还没有面板」，不说整块板子没有。面板的名字仍按整块板子编号，状态条说到别的页上的面板时名字对得上。屏幕上是哪一页是 runtime 的（`DashboardController.tab`，只有它在跑），不是扩展的一项——栅格与新面板的落点都读它。（见 test/dashboardExtensions.test.tsx「the tab bar」）
- **只跑屏幕上的那一页**（runtime 收口，见 [runtime.md#dashboard](../runtime.md#dashboard)）：打开时只有这一页的面板查询；切过去才跑那一页，切回来直接画留着的行；整板刷新只刷屏幕上的这一页，别的页切回来时补一次。（见 test/dashboardTabs.test.ts「only the tab on screen runs」；浏览器里 stories/view-engine/DashboardBuilding.test.stories.tsx「TabsRunOnlyTheTabShown」数了查询次数）
- **记住每人上次的标签页，当前标签页交给宿主写进地址**（D22 E，用户「全按推荐」）：读者按下一个标签页，工作台经 `engine.rememberTab` 把它记进这个人的偏好（`ViewPreferences.lastTabs`，[management.md](../management.md#列表偏好与默认视图)），下次打开从那一页开始跑——故事里从视图列表换到另一块板再回来，落在上次那一页。地址是宿主的：`DashboardWorkbench` 的 `onTabChange(tabId | null)` 在屏幕上的标签页每次变化时说一声（打开那一刻也说），`initialTab` 是宿主从地址里读回来的那一页——它随 `instanceId` 读，指的是那块板子的一页；没受控时只给第一块板子。板子没有那一页就退回上次看的那一页。包本身从不碰地址。嵌入的板子同样画标签栏、能切，但不记。（见 test/dashboardExtensions.test.tsx「opens on the tab the host names」「switches tabs in an embed too, and remembers nothing there」）
- **搭的时候标签栏是一张要整理的列表**：`tablist` 里只能放标签，所以编辑中（与编辑条同一个编辑状态）它换成一个有名字的列表（「标签页」），每一行一个抓手（`DragHandle`，指针拖或在它上面按左右方向键，落位说「「异常」现在是第 2 个标签页，共 3 个」）、一颗显示这一页的按钮（屏幕上那一页是按下去的样子，`aria-current`）和一个菜单（改名、左移、右移、删除标签页）；末尾一颗「添加标签页」。一块没有或只有一个标签页的板子，编辑中只有这一颗「添加标签页」。新加的标签页随即显示、名字就地可改（全选，直接打字替换）；双击也能改名；Enter 或离开保留、Esc 放回原名，空名不改，改完键盘回到那一页的按钮。**删一个带面板的标签页先问**（`AlertDialog`：它上面的 N 个面板会一起删除；完成编辑前不会保存，取消编辑可以找回），空的直接删。这些都是草稿里的编辑（`DashboardEditing` 的四个标签页命令），「完成」才写回。（见 test/dashboardExtensions.test.tsx「adds, renames, moves and deletes tabs while the board is built」「renames a tab by double-clicking it, and Escape keeps the old name」；浏览器里「TabsBuilt」）
- **移到标签页**：面板菜单的「移到标签页 ›」（上文「面板菜单」）放进那一页的第一个空位，离开的那一页若空了就说「这个标签页还没有面板」。（见 test/dashboardExtensions.test.tsx「moves a panel to another tab, where the tab it left says it is empty」）

### 在仪表盘里新建分析（D22 C，批 B3）

- **一个大对话框，里面就是分析视图**（`ui/dashboard/NewAnalysisDialog.tsx`）：从「＋ 添加 ▾」的「新建分析…」或空板子的「新建分析」打开。标题「新建分析 · 〈数据〉」，先选数据（只列声明了分析能力、定义准入没 error 的；只有一份时已选好），然后就是工作台的 `AnalysisParts`——同一个托盘（范围、维度、指标、结果）、同一个结果（表格｜图表、可视化面板开在结果左侧，它的「‹」在这里叫「收起可视化」，因为没有视图列表可回），改了就跑照读者自己对这份数据的偏好。它不开追问（`followUps={false}`：对话框里没有「旁边」可开）。对话框自己持有一个未保存的分析视图（`engine.create`，缺省配置：记录数，一打开就有一个数），关掉或换数据时放掉。（见 test/dashboardExtensions.test.tsx「a new analysis made in the dashboard」）
- **标题跟着读法走，直到作者自己起名**：缺省就是结果工具栏那一句读法（`analysisReading`，「按仓库 · 记录数」），作者一改就不再跟。「放进仪表盘」在草稿被准入拒绝或标题为空时不可按，字段下一句说为什么；板子满了就留着对话框说「放不下更多面板了」。按下就是 `addPanel({ kind: 'view', owned, title }, spot)`：`spot` 是编辑条算好的放置点——屏幕上那一页、读者看得到的第一行起的第一个空位；读屏听到「「…」已放进仪表盘」，键盘回到「添加」。取消不留痕迹。（见 test/dashboardExtensions.test.tsx「is the analysis view in a dialog, named by its reading, and lands on the tab on screen」「keeps the title its author typed, and cancels without a trace」；浏览器里「CreateOwnedAnalysis」）
- **「另存为视图…」**：板内分析的「⋯」里那一项，打开的就是「另存为」那一个对话框（`SaveAsDialog` 的 `promote`：标题从面板的名字开始，一句说它会成为「〈数据〉」的一个视图、面板改为显示它，受众先选板子自己的那一个——共享板上存成个人视图就是一块别人看不到的面板）。保存是 `ViewEngine.saveOwnedView`，面板随即改为引用它（展示覆盖保留），菜单里这一项随之消失；失败留在对话框里说。（见 test/dashboardExtensions.test.tsx「is saved as a view of its own, the panel pointing at it」；浏览器里「PromoteOwnedAnalysis」）

### 面板自己的展示（D22 D，批 B3）

- **「改这里的展示…」打开的就是可视化面板**（`ui/dashboard/PresentationDialog.tsx`，只给分析面板：记录面板没有可视化面板可画的东西）：左边是这块面板改后的样子（与栅格里的面板同一个子 runtime、同一个画法），右边是工作台的 `ChartPicker` 与 `ChartOptions`——图型网格、这种图的选项、表格合计行。每一下都当场写进这块面板的 `presentation`（`setPresentation`，草稿与屏幕同时）：分析编辑器的 `setLayout`／`updateChart`／`setTotals` 在这里改写为写覆盖，而不是写视图；一个与视图自己一样的样子不算覆盖（那一项不写，全都一样就是 `null`）。换图型只重画不重跑（D20，runtime 的收口见 [runtime.md#dashboard](../runtime.md#dashboard)），合计行是一次自己的查询、照跑。「完成」收起，「取消」放回打开时的样子，对话框里的「恢复为视图的样子」随时可按；关上时键盘回到面板的「⋯」。（见 test/dashboardExtensions.test.tsx「a panel’s own look」）
- **面板头上说「此处改为〈图型〉」**（`presentationMark`，`DashboardPanel` 标题后一枚 `secondary` 的 `Badge`）：「此处改为饼图」「此处改为表格」，只改了合计行时是「此处改了展示」；覆盖不合身被丢掉时不带（面板头上已有那条 warning）。有覆盖时「⋯」的「改」组多一项「恢复为视图的样子」＝`onResetPresentation(panelId)`＝`setPresentation(panelId, null)`，没有覆盖就没有这一项。（见 test/dashboardExtensions.test.tsx「offers 恢复为视图的样子 only over a look of its own」；浏览器里「OverrideToPieAndReset」：换成饼图后面板画的是饼、查询数不变，从菜单恢复后回到柱状图，对话框的「取消」放回原样）

### 扩展：搭板子里住在编辑条与面板菜单之外的那几件

`DashboardEditExtensions`（`ui/dashboard/extensions.ts`，经 `DashboardEditExtensionsContext` 提供，`/ui` 导出）是**一份**接口，每一项可选，**没提供就没有对应的入口**：

```ts
interface DashboardEditExtensions {
  onAddOwnedAnalysis?(spot: { fromRow: number; tab?: string }): void; // 「新建分析…」：添加菜单与空板子
  onEditPresentation?(panelId: string): void; // 分析面板的「改这里的展示…」
  onResetPresentation?(panelId: string): void; // 有覆盖的面板的「恢复为视图的样子」
  onSaveOwnedAsView?(panelId: string): void; // 板内分析的「另存为视图…」
  tabBar?: ReactNode; // 编辑条之下、面板之上
}
```

- **`DashboardWorkbench` 把四项与标签栏全部提供**（`ui/dashboard/building.tsx` 的 `useDashboardExtensions`：命令与它们打开的三个对话框），所以默认工作台里这些入口都在；包在工作台外面的宿主若提供了其中某一项，**那一项用宿主的**，其余仍是工作台的。嵌入视图、宿主自拼的 `DashboardBoard` 没人提供就没有这些入口。
- 入口只在**一处**出现、只听**一个**编辑状态：编辑条、添加菜单、面板菜单都从 context 读它，按 `panelCommands`（`ui/dashboard/commands.ts`）决定哪块面板出哪一项；编辑状态是工作台里那一个（按下「编辑」到「完成」／「取消」，每次打开一个视图重新开始），标签栏的编辑形态读的也是它。
- **屏幕上是哪一页不是扩展的一项**：它是 runtime 的（`DashboardController.tab`），栅格与 `spot.tab` 都读那里——只有一个来源，只跑那一页也由它说了算。
- 对话框关上时键盘回到打开它的那颗控件（打开时那个菜单的触发钮——「添加」或面板的「⋯」——或空板子上那颗按钮），经一个稳定的 `finalFocus`：对话框的焦点管理在它换成新函数时会重新布防、顺手把键盘交回去。打开对话框的菜单项让菜单**不**在收起时把键盘拿回触发钮（`handedOff`）——菜单收起的动画晚于对话框打开，从前键盘会在对话框开着时被拿到板上的「添加」，打进标题框的字只进去一两个（浏览器里「CreateOwnedAnalysis」守着）。（见 test/dashboardBuilding.test.tsx「shows the extensions’ entries only where they are provided」）

## 筛选（D22 F、G，批 C）

模型与运行时见 [model.md#dashboard-配置](../model.md#dashboard-配置) 与 [runtime.md#dashboard](../runtime.md#dashboard)；这里是屏幕上的样子（`ui/dashboard/FilterBar.tsx`、`FilterSettings.tsx`、`FilterWiring.tsx`，由 `BoardFilters.tsx` 的 `useBoardFilters` 交给 `DashboardBoard`）。

- **筛选条在板子最上面**（F 屏）：在编辑条、标签栏、面板之上，对所有标签页生效；一个以「筛选」为名的 `region`。一个筛选一枚：名字、值控件、需要时一颗 ✕。**值控件就是条件编辑器的那几个**（`FilterValueEditor`，经内核的 `filterEditor` 选：日期是那套「日历／相对／命名时段」，文本或类别：自己列了一组的是选择；没列而接上的字段自己声明了一组（`enum` 的选项）就从那一组里选——显示标签、存代码，几个字段的同一个代码只出一次（内核 `wiredOptions`，照 Metabase 的类别筛选），读者选「待出库」、筛选存 `PENDING`，不必打协议代码；都没有才从接上的字段在数据里的值里挑、带记录数（`DashboardRuntime.valueCandidates`，与文本条件同一个 `SuggestedValue`），连这也没有才是输入框；ID 走宿主的候选源，数字、是否各是各的）；单值筛选存成一项的列表，控件里就是那一个值（`filterControlValue`／`filterStoredValue`）。没有「应用」：改了就跑（300 毫秒后面板自己重跑）。（类别见 test/dashboardFilterBar.test.tsx「picks a category from the labels its wired fields declare, and holds their codes」，浏览器里「CategoryPicksFromLabels」「TextOffersCountedValues」）
- **必填带星号，永远有值**：星号画出来，读屏念「创建时间（必填）」（名字写在这一枚 `group` 的 `aria-label` 上，星号本身 `aria-hidden`）；在默认值上没有 ✕，改过之后那颗是「把「创建时间」恢复为默认值」（`RotateCcwIcon`），「清空」把它放回默认值而不是清空。非必填的 ✕ 是「清除「仓库」」。**「清空」**在筛选条末尾，一切都在起点（必填在默认值、粒度在默认粒度、其余为空）时禁用。（见 test/dashboardFilterBar.test.tsx「never leaves a required filter empty: its ✕ and 「清空」 go back to the default」；浏览器里 stories/view-engine/DashboardFilters.test.stories.tsx「RequiredNeverEmpty」）
- **时间粒度是一组「按日｜按周｜按月」**（`ToggleGroup`，按 `units` 的顺序，名字「时间粒度」）：按下就整板重算，接得上的面板都换成那个粒度（`regrouped`）；换不了的面板头上带那条 note（「保留自己的时间粒度」）。编辑中旁边一颗「移除时间粒度」。（见 test/dashboardFilterBar.test.tsx「switches the time grouping of every panel that can take it」；浏览器里「TimeGroupingSwitches」数了查询次数）
- **在当前标签页上什么也没影响的筛选淡一档**（`filtersOnTab`）：边框变虚、底色退到背景，字保持原来的对比度——整枚降透明度会让文字过不了 axe 的对比度；旁边一颗 `InfoIcon` 的 `IconTooltip`，悬停与聚焦都说「这个标签页里没有受『仓库』影响的面板」。时间粒度同理（这一页没有面板按时间分组时）。（见 test/dashboardFilterBar.test.tsx「draws a filter that reaches nothing on the tab quieter, and says why」）
- **「不受『〈筛选〉』影响」**：一个**此刻有值**的筛选没接上某个数据面板时，那个面板的标题后一枚 `ToneBadge`（warning 语气、不带圆点，`data-slot="panel-not-reached"`），几个就并成一句：不受「仓库」、「时间」影响（这句单独站在屏幕上，所以用「」而不是『』）。没值的筛选什么也没筛，就不说——否则一块板子上每个面板都挂一串。（见 test/dashboardFilterBar.test.tsx「runs a value on its own, only on the panels it reaches, and clears it」；浏览器里「UnwiredPanelSaysSo」）
- **地址是宿主的**：`DashboardWorkbench` 的 `initialFilters`／`onFiltersChange`（批 C1）；浏览器里「ValuesReachTheHost」。
- **标题栏里原来那个条件折叠面板去掉了**：筛选条就是板子的筛选。「已应用」那条带子只在还有筛选条不管的条件时才画——批 C 之前存下的整板条件，或宿主的作用域（Q16）——并照旧可以从那里拿掉。（见 test/dashboardWorkbench.test.tsx「opens a dashboard, shows its panels and its filter bar」「draws the applied band only for a standing condition the bar does not hold」）
- **加筛选**（G 屏，编辑中）：编辑条上「添加」旁边一颗「筛选 ▾」（`AddFilterMenu`，名字「添加筛选」）：日期、文本或类别、ID、数字、是否；板子还没有时间粒度时多一项「时间粒度」（按日／周／月，默认按日）。新筛选以类型为名、加在末尾，它的**设置弹层随即打开**（`FilterSettings`，挂在那一枚上的齿轮，编辑中每一枚都有）：类型（换类型会丢掉默认值、列表与接线）、名字（空白不收）、默认值（与筛选条同一个值控件；必填却没有默认值时字段标红并说「必填的筛选需要一个默认值」）、可多选（日期、是否没有）、必填、值从哪来（文本与数字：「接上的字段」／「自己列一组」，后者就是条件编辑器的 chips）；底下「接线」「移除筛选」。每一下都是草稿里的编辑，「完成」才保存。（见 test/dashboardFilterBar.test.tsx「sets a text filter up: several values, a list of its own, then removes it」「adds the time grouping from 「筛选 ＋」 and takes it off from the bar」）
- **接线**：「接线」让板子进入给这个筛选接线的状态：编辑条下一条「正在给『〈筛选〉』接线」与「完成接线」（`WiringBar`），每个数据面板底部一条接线条（`PanelWiring`）：「筛选字段 〈字段〉 ▾」，下拉里只有同类型的字段（`wireableFields`）加「不接」；一个同类型字段都没有就说「没有可接的字段」；亲手选的标「手动」（自动接的不标）。在一个面板上选了字段，**同名同类型的其余面板自动接上**（任何标签页、任何数据定义，`bindPanel`），随即一条提示「已自动接上 N 个有『〈字段〉』字段的面板」带「撤销」（`unbindPanels` 撤掉那几个，亲手选的留着）。提示是注册表的 `toast`（Base UI），**不 portal 出去**：放在板子自己的 `.fve-root` 里，主题才到得了它（同 `popups.tsx` 的理由），视口是它自己的 `aria-live` 区域。（见 test/dashboardFilterBar.test.tsx「adds a date filter, sets it up, and wires it — auto-connecting the rest, with an undo」；浏览器里「AddTimeFilterAutoConnects」）
- **以后新加的面板同样自动接**（`addPanel` 经 `autoBindings`，批 C1）。筛选的顺序由 `moveFilter` 改，界面上的拖动排序这一批没做（todo）。

## 阶段 3 的交互（定稿）

[仪表盘交互稿](https://claude.ai/artifact/SVjSG6BH7WVAnqthQDh42y) 2026-09-23 定稿（用户：十条待拍板「全按推荐」），方向见 [D22](../decisions.md#d22-仪表盘与嵌入视图参照-metabase2026-09-23)。批 B～D 按下面逐屏实现；实现落地后把每条改写成现状并附测试名。

**批 B1 落地了 A～E 背后的模型与运行时**：24 列与旧布局迁移、标签页、板内分析视图与「另存为视图」、展示覆盖、标题卡片（[model.md#dashboard-配置](../model.md#dashboard-配置)）；搭板子的命令 `DashboardEditing`，编辑中按草稿实时重跑、保存才写回；保存只被整板 error 挡；动手后上浮压紧；同名面板编号（[runtime.md#dashboard](../runtime.md#dashboard)）。**批 B2 落地了 A、B、D 的界面**，已改写成上一节的现状；B3 做标签栏、「在仪表盘里新建分析」的大对话框、展示覆盖的编辑与「另存为视图」，从「扩展」接进来。

**批 C1 落地了 F、G 背后的模型与运行时**：五种筛选类型、默认值、必填、多值、值从哪来、时间粒度、接线与自动连接、「不受影响」的答案（[model.md#dashboard-配置](../model.md#dashboard-配置)）；筛选此刻的值是读者的、改了就跑、必填永不为空、时间粒度只换定义允许的、候选值取自接上的字段（[runtime.md#dashboard](../runtime.md#dashboard)）。宿主经 `DashboardWorkbench` 的 `initialFilters`／`onFiltersChange` 把值写进自己的地址——打开那一刻也说一声，与 `initialTab`／`onTabChange` 同一个样子，包本身从不碰地址（见 test/dashboardWorkbench.test.tsx「opens under the filters a host keeps in its address, and tells it what they hold (D22 F)」）。筛选条与接线的界面是批 C2，见上文「筛选」。

三条贯穿的原则：**读与搭分开**（平时不可拖、点不坏，「编辑」进入搭的状态，「完成」保存、「取消」放弃，系统仪表盘只有「另存为」）；**一个概念一种样子**（追问菜单、可视化面板、条件编辑器、候选值全部复用分析与记录视图的部件）；**说清作用范围**（每个筛选作用到哪些面板、哪个面板不受它影响，在面板上看得到）。

- **A 编辑模式与「添加」**——**已落地**（批 B2，见上文「搭板子」；「新建分析…」的对话框批 B3；「筛选 ＋」批 C2）。
- **B 选一个视图**——**已落地**（批 B2）；面板菜单的「复制为共享视图并替换」还没有（todo）。
- **C 在仪表盘里新建分析**——**已落地**（批 B3，见上文「在仪表盘里新建分析」）：大对话框，先选数据定义，里面就是分析视图的托盘与结果；「放进仪表盘」存成只属于这块板的视图；「另存为视图…」把它提成普通视图，面板改为引用它。首版只新建分析。
- **D 面板菜单与展示覆盖**——**已落地**（菜单批 B2，展示覆盖批 B3，见上文「面板菜单」「面板自己的展示」）；「导出数据…」「点击时…」不在其中（批 D 与以后）。
- **E 标签页与 24 列**——**已落地**（24 列批 B1，标签页批 B3，见上文「标签页」）；「一个筛选在当前标签里没有受影响的面板时淡一档」批 C2 已落地（上文「筛选」）。
- **F 筛选条**——**已落地**（模型与运行时批 C1，界面批 C2，见上文「筛选」）：页头一排、值控件与候选值复用条件编辑器、必填带星号永不为空、整板「按日｜周｜月」、未接上的面板头上「不受『〈筛选〉』影响」、筛选值经宿主进地址不进配置。
- **G 加筛选与接线**——**已落地**（批 C1、C2，见上文「筛选」）：设置弹层、接线条、同名同类型自动连接（跨定义也接）并可撤销、以后新加的面板同样自动接、「手动」、「没有可接的字段」。筛选条上的拖动排序未做（todo）。
- **H 默认：追问菜单**（批 D）：点柱、点行、点扇区，弹与分析视图同一个追问菜单，标题带上当前全局筛选（「仓库 属于 华南 · 本月」），三项都在工作台打开（↗），仪表盘不变。**宿主没给路由钩子时，不出追问菜单**。
- **I 交叉筛选**（批 D）：作者在「点击时…」选「更新仪表盘筛选：〈筛选〉」；读者点一个值，筛选条上随之变化并注明来自哪个面板，其余接线面板重算；**被点的面板不筛自己、只高亮点中的一组；再点一次同一个值撤销**；面板头标「点击筛选〈筛选〉」。第三个选项「去另一个视图或页面」带上点中的值。
- **J 窄屏**：窄于 768px 时按阅读顺序排成一列、各保留高度，布局是推导出来的、不写回——**这一半已落地**（见上文「DashboardGrid 与几何写回」）；筛选条窄屏时横向滚动（批 C2）；**窄屏编辑只允许改标题、移除、调顺序**——改标题、移除已落地（批 B2），调顺序未做。
