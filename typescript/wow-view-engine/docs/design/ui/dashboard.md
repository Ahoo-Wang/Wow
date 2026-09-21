# UI 层：Dashboard 视图

栅格、面板 chrome 与面板级告警。三种视图共用的骨架、状态条与 `FilterPanel` 见 [README.md](README.md)；面板与子 runtime 的关系见 [runtime.md#dashboard](../runtime.md#dashboard)。

## DashboardGrid 与几何写回

- `DashboardGrid` 不做自动紧凑，面板按配置中的 `layout` 原样摆放；
- 只有用户拖动或缩放结束时才把几何写回（`edit` + `apply`），库自身在挂载或属性变化时算出的布局不写回，因此打开已保存的 Dashboard 不会变脏。（见 test/dashboardUi.test.tsx「DashboardGrid」「with a stored layout the grid would have compacted」）
- 栅格要的是像素宽度，而容器只有上了屏才知道自己多宽。量它的是 `react-grid-layout` 自己的 `useContainerWidth`，不是这里另写一个 `ResizeObserver`：同一个观察者、同一套取宽规则，没有 `ResizeObserver` 的环境照样能画，连续几次缩放合并到同一帧。容器报 0（被隐藏时就是这样）时这个 hook 照实上报，栅格自己不收，面板保持上一次能画的宽度。（见 test/dashboardUi.test.tsx「follows the container width once it can be measured」）

- 面板里的 Record 视图以 `selectable={false}` 渲染 `RecordTable`：Dashboard 是读数的地方，没有工具栏也没有行动作，没有任何东西读选择，勾选框因此只是一列点不出结果的控件。

## 刷新是整块板子的

- 仪表盘的刷新同样是标题栏里的那个拆分按钮，但它编辑的是**仪表盘自己的** `refresh.interval`：`DashboardRuntime` 为整块板子持有唯一一个计时器，被引用实例自身的 `refresh` 在其中被忽略，以免两层计时器（[runtime.md#dashboard](../runtime.md#dashboard)）。所以菜单顶上多一句 `label.refresh.panels` 说清这一层关系——控件若什么都不说，看上去就像在给每个面板各设一个间隔；
- 主键那一半在引用还在解析时禁用（`dashboard.resolving`，此时没有哪个面板能被刷新），**在任一面板的查询还在途时也禁用**（`dashboard.loading`）：仪表盘自己不跑查询，`state.query` 永远是 `idle`，问它「有没有东西在跑」永远答没有，于是一屏正在加载的面板会被一次点击整片顶掉，而按钮上连个转圈都没有。`useDashboard` 因此自己订阅各个子 runtime——子 runtime 的查询变化不会通知仪表盘的订阅者（那是有意的，否则每个面板每次请求都要让整张栅格重渲染），所以要知道这件事的人得自己去问。（见 test/refreshControl.test.tsx「the dashboard title bar, saying whose timer it is」）

## 面板 chrome 与 warning 标记

- Dashboard 在这里显示尚未被任何已应用面板承载的 warning：自己的（`useDashboard().issues`，不含 `['panels', …]` 路径），以及 draft 里面板级却还没交给面板的——全局条件映射到会告警的面板字段、尚未 Apply，这时 `state.panels` 仍是上一次 applied 的，不说就会被 Save 原样存下；
- 面板正文自己会滚动（面板高度由布局定，内容不一定装得下），所以它带 `tabIndex={0}` 与 `role="group"`、以面板标题为名：能滚动而键盘到不了的区域是一条实打实的缺陷。记录面板本来靠行里的控件凑巧满足了这一条，图表面板则一个可聚焦元素都没有——图表是一张 `role="img"`，不再是 recharts 默认挂在 `<svg>` 上的那个 tab 停靠点，见 [analysis.md#图表怎么被读出来](analysis.md#图表怎么被读出来)；
- Apply 之后由面板承载，条里不再重复。面板级的由面板自己呈现：不可用的面板在正文里说明理由（首个 error，或独自到来的那条 warning），随之而来的其余 warning 仍在头部标记里，能运行却带 warning 的面板（子 runtime 对自身配置的 warning，以及它上一次**结果**自身的 warning——汇总退回本页、分析填满上限——都已重定址到面板，见 [runtime.md#dashboard](../runtime.md#dashboard)）照常显示视图，头部加 `panel-warning` 标记并以 `data-warning` 标出边框。（见 test/dashboardUi.test.tsx「DashboardWorkbench」「content panels」）
- 这个标记是 `IconTooltip`（`ui/IconButton.tsx`）而不是一个挂着 `title` 的 `span`：`title` 只有鼠标悬停才出得来，键盘与触屏都够不着，于是 warning 说了什么就只有拿鼠标的人读得到。走同一条通路之后，同一份 `messages.issues(warnings)` 既是控件名也是气泡文案，聚焦与轻点都能打开。颜色（`text-warning`）落在图标上而不是按钮上——那是标记本身的含义，按钮保留 ghost 变体自己的悬停与聚焦配色。（见 test/iconTooltips.test.tsx）

## 内容面板：排版与占位

- **markdown 面板不引 `@tailwindcss/typography`，而是把那几条覆盖写成一个具名常量**（`MARKDOWN_PROSE`）。优先复用第三方是这一包的默认，这里是反过来的那一种，理由写在调用处：`prose` 是一栏文章——`max-width: 65ch`、围绕 16–20px 正文的字号阶梯，即便 `prose-sm` 的 `h1` 也有 30px 上下，比面板自己的标题还大，而面板多宽是用户拖出来的；它的颜色是一套写死的 gray，要让它认 `--foreground`／`--muted-foreground`／`--primary` 就得在 `styles.css` 里重定义十六个 `--tw-prose-*`，比它要替掉的那四条规则还多，而且颜色从此有两个决定的地方；它大部分规则是给这个面板画不出来的元素准备的（原始 HTML 是关的）。原来写在类名里的 `prose-sm` **一直是死的**——插件从未安装，编译出的样式表里一条 `prose` 规则也没有；
- **图片加载失败的占位就是 `Empty`**（`EmptyMedia variant="icon"` + `EmptyDescription`），与 `DashboardGrid` 里「面板不可用」「查询失败」同一个形状，不再是一个自己居中的 `div`。不放标题：作者写的 `alt` 是仅有的那句话，上面再加一句「图片加载失败」等于把同一件事说两遍；`alt` 缺席时才用 `label.image.failed` 当那句描述。（见 test/dashboardUi.test.tsx「content panels」）
