# UI 层：主题

本页是主题的合同与机制：几条轴、三层变量、角色、登记表、品牌色、内置预设、图表怎样跟上，以及守住这些的门。弹层怎样带走主题、明暗怎样判定、面为什么不嵌套，见 [README.md#主题弹层与明暗](README.md#主题弹层与明暗)；宿主第一步选哪条路（引擎跟宿主，或宿主跟引擎）与 **ViewHost** 怎样写 `<html>`，见 [host-integration.md](../host-integration.md) 4.1、4.2；面向宿主的写法在文档站的主题指南（`documentation/docs/{en,zh}/guide/typescript/view-engine-theming.md`）。

裁定：[D30](../decisions.md#d30-阶段-5-内置多主题的十条裁定2026-09-24)（阶段 5 的十条）、[D35](../decisions.md#d35-内置主题目录与三条轴的四条裁定2026-09-24)（目录与三条轴）、[D43](../decisions.md#d43-主题可以说面怎样分层控件怎样画2026-09-25)（面怎样分层、控件怎样画）、[D45](../decisions.md#d45-主题一览拆成每套一个故事截图基线在-playwright-的-linux-容器里截2026-09-25)（一览与截图基线）、[D46](../decisions.md#d46-主题架构重构五条结构一张登记表2026-09-25)（五条结构、一张登记表）、[D57](../decisions.md#d57-点击目标由-theme-check-报错样式表不设下限2026-09-26)（点击目标）、[D66](../decisions.md#d66-引擎的工具类带前缀-fve与宿主不再同名2026-09-28)（工具类前缀）。

## 前提

- **主题是宿主的外观，不是观察方式。** 项目的价值链是研发声明「能怎样观察」、用户决定「这次怎样观察」、引擎编译执行渲染保存（[../README.md#定位与第一性原理](../README.md#定位与第一性原理)），主题不在链上。所以它**不进任何配置**：`ViewConfig`、`ViewInstance`、仪表盘配置都不存预设、明暗、密度或涨跌约定，一份观察换到另一个宿主就穿那个宿主的衣服；也没有「每个视图各带一个主题」（D30 Q41）。
- **宿主研发选，引擎跟随。** 工作台、视图管理与仪表盘里没有终端用户的主题开关；宿主要给读者选，就在自己的外壳里放选择器、改 `<html>` 上的属性。明暗可以交给 **ViewHost** 的 `colorMode`（跟随系统、记住读者的选择），其余几条轴引擎不读不写任何存储（D35）。
- **CSS 变量是运行时唯一的真相源。** 没有 JS 主题对象、`createTheme()`、ThemeProvider 或按组件的样式 props——它们都会成为第二个真相源。React context 只搬运级联送不到的东西：portal 出去的弹层（属性、字体、`tokens`）与 ECharts（读回的具体值）。
- **一切只画在两个边界里**（`.fve-root`／`.fve-tokens`，[README.md#shadcn-与两个样式边界](README.md#shadcn-与两个样式边界)）；预设与桥接文件只声明变量、本身不画东西。
- **可达性线对每一套内置预设、每一种明暗都成立并被测试守住**：字 ≥4.5:1，控件边与焦点 ≥3:1，图表八色过色觉间距、对底与柱内墨色三道门。宿主自己的主题至少有办法自己量（`theme-check` 与 Storybook 的对比度矩阵）。
- **预设只用公开合同。** 每套内置预设只经宿主也能写的 `--fve-*`／`--fvp-*` 搭出来；没有私有选择器、不伸进组件内部、组件里没有为哪一套开的代码路径。一套预设说不出的东西是**机制的缺口**：在机制里补（加一个角色、一个链接），不给那一套开特例。
- **为什么内置预设**：接入成本（多数宿主没有设计师，不会自己调几十个变量、量焦点是不是 3:1，内置的是调好、量过的一套）；归属感（视图嵌在宿主的后台里，风格与周围不一致就读成外来的插件）；可达性保证（线只对内置的能证明，宿主抄来的主题常丢掉它们）；机制需要真实的使用者（多主题的机制只有在几套差别够大的主题下才暴露缺陷）。
- **neutral 不设任何值时就是样式表自己的样子**；机制的改动让 neutral 逐像素不变，由截图基线（D45，比对不留容差）证明。

## 五条轴

| 轴                           | 载体                                                                   | 谁定               |
| ---------------------------- | ---------------------------------------------------------------------- | ------------------ |
| 风格（形状、面的层次、字体） | `data-fve-preset`，或面上的 `preset` 属性                              | 宿主               |
| 品牌色                       | `--fve-brand`（暗色可另给 `--fve-dark-brand`）                         | 宿主               |
| 明暗                         | 祖先上的 `.dark`，面上的 `theme`（`light`／`dark`／`system`）          | 宿主（可跟随系统） |
| 密度                         | `data-fve-density="compact｜default｜comfortable"`，或面上的 `density` | 宿主；预设只推荐   |
| 涨跌约定                     | `data-fve-change-colors="semantic｜green-up｜red-up"`                  | 宿主，按市场与读者 |

五条互相正交：任何预设都接受任何品牌色、任何密度、任何约定。属性挂在 `<html>` 或任一祖先上，所有面与 portal 到 `<body>` 的弹层都继承；一块面要与页面不同，用面上的属性（`theme`、`preset`、`density`、`tokens`，工作台与两个嵌入组件都透传）。涨跌约定没有面上的属性：一页一个市场口径，一页里两种约定会让读者读反。

**首帧就对**：宿主若自己记住读者选的预设、密度或约定，要在首帧之前把属性与 `.dark` 写到 `<html>` 上——在 `<head>` 里、样式表之后放一段同步小脚本读出存的值（常见明暗切换库的做法）；服务端渲染的宿主按 cookie 直接渲染这些属性。`theme="system"` 要在客户端才知道答案，服务端渲染的宿主应当由那段小脚本挂 `.dark`，否则首帧按亮色画、水合后再变暗。视图在客户端渲染时读到的已是最终属性，图表首画就用对的颜色。

**不进主题的**：网络字体与字体文件（体积、授权、离线；宿主要就在自己的样式里加，`--fve-font-sans` 指过去）；字号阶梯与 `--fve-text-ui`（为可读性定的，只给字重）；组件形状的变体（药丸按钮、浮动标签——那是第二套组件库）；毛玻璃（亮暗弹层要不透明到 0.96 才守得住弱字的 4.5:1，那时模糊已看不出，改用不透明的分层灰加发丝高光）；动效、图标、插画、渐变；**存下的几何**（仪表盘行高 80px，D34；固定宽度 1200px，D31）。D46 论证过进来的三处边界：分部件的圆角（同一个量分部件给值）、控件高度的梯级（风格的一部分，不进存下的几何）、控件边的线宽（只到控件边，分隔线仍是 1px）；菜单高亮与徽标样式是「用哪对颜色」，作为颜色角色进。

## 三层变量

| 前缀          | 谁写                                               | 读的地方                                                  |
| ------------- | -------------------------------------------------- | --------------------------------------------------------- |
| `--fve-*`     | 宿主（`:root`、任何祖先、`tokens` 属性）           | 引擎在边界上读，**永远最先**                              |
| `--fvp-*`     | 预设（内置的与宿主写的）、shadcn 桥接              | 宿主没写时才用                                            |
| `--_fve-*`    | 引擎自己（算出来的角色、中间量、测量值）           | 引擎内部；不是合同，登记表里没有                          |
| shadcn 语义名 | `styles.css` 的 token 块（`--primary`、`--muted`） | vendored 组件与工具类；`.fve-tokens` 有意把它们给宿主外壳 |

- **颜色写成 `oklch()`**：L 近似就是亮度，对比度在写的时候就估得出来，调一套只动一个量（把灰移到同亮度只换 h 与 C）；品牌色的夹子（按亮度夹主色）正是靠这一点。ECharts 读不了 `oklch()`，图表那头统一转 `rgb()`。
- **不设公开的原始色阶**（没有 `--fve-gray-100` 这一层）：多一层公开面翻倍，宿主改了一档灰却不知道它流进了哪几个语义 token，对比度也就无从量。色阶只存在于写预设的时候，预设源文件的注释写明取的是哪一档，值落到语义层。
- **每个登记的 token 在 token 块里是同一个形状**：`var(--fve-x, var(--fvp-x, 内置值))`，暗色一半读 `--fve-dark-x`／`--fvp-dark-x`；内置值就是 neutral，只写在 `styles.css` 这一处。宿主与预设写不同的变量，所以谁赢由 `var()` 的后备顺序决定，与值声明在哪个元素上无关：宿主在 `:root` 上的值赢过任何嵌套、任何钉住的预设（D46）：这是 README 与指南对宿主的承诺，现在在结构上成立，不靠值写在哪个元素上。宿主要让某块面**不**受自己的覆盖影响，就把覆盖写窄——那是它在自己的两个决定之间取舍。
- **钉住的预设完整替换外层的**：`styles.css` 开头的 `@layer fve-reset` 里有一条零权重的 `:where([data-fve-preset])` 规则，把预设层的每个名字设成 `initial`；预设块不在层里，所以它写了的那些留下、没写的回到内置值。列表写在源码的 `fve-reset:begin`／`end` 两个注释之间，由 `theme:docs` 从登记表重写、`test/themeFiles.test.ts` 比对。于是预设**只写它改的**（`neutral` 是空块），宿主写的预设嵌套时也不继承外层预设的值；宿主若把自己的预设写进了自己的 `@layer` 且那层在我们之前声明，它会输给复位——主题指南写明「预设写在层外」，`theme-check` 查这一种。没选 `@scope` 加不继承的 `@property` 来代替复位：桥接要把 `--fvp-*` 指向宿主的 `--primary`，而在边界上 `--primary` 是我们自己的 token，会成环；零权重的复位规则就够了。
- **预设块是 `:where([data-fve-preset='<名>'])`**，不占特异性；只许赋 `--fvp-*`，没有 at-rule。「全给或全不给」只剩登记表里标 `whole` 的两组：图表八色（亮暗共 16 个）与阴影三档（6 个）。
- **私有的用私有前缀**：展开框、钉住列的偏移、图表的轻点提示、表面字体、`.fve-tokens` 上引擎自己的名字（`canvas`、`control*`、`density`、`rise`／`fall`、`card-*`、`title-weight`、密度的长度……）都是 `--_fve-*`，宿主外壳里同名的变量不会被悄悄遮住；工具类照旧读这些角色，名字带 `fve:` 前缀（`fve:bg-canvas`，D66）。`--fve-` 下的每个名字都是宿主能写、登记表里有、指南里有的。
- **`tokens` 属性**：`ViewSurface`、三个工作台与两个嵌入组件收 `tokens`（`Partial<Record<FveToken, string>>`），写在面根与每个弹层（含遮罩）的行内样式上（`useSurfaceHostTokens`）。宿主把 `--fve-*` 写在某个包裹层上时，portal 到 `<body>` 的弹层不在那层下面——属性能照抄，变量不能——所以「这一块不同」用 `tokens`，不用包裹层。
- **宿主的 Tailwind 与引擎的不同名**（D66）：引擎的工具类带 `fve:` 前缀，Tailwind 的主题一律 `inline reference`（一个变量也不声明——带前缀后它们会叫 `--fve-*`，正是宿主的命名空间），作用域只管 preflight、base 与变量，不加权重；谁先导入、谁在谁的面里都各按各的层叠。`verify-package` 守「样式表里没有不带前缀的工具类」。

## 登记表：合同只有一个来源

`src/ui/theme/` 是主题合同的机器形式，只写**结构**，不写值——值仍只在 `styles.css` 与预设源文件里：

- **登记表不带值**：值若也写在登记表里，就有 TS 与 CSS 两份，或者 CSS 成了生成物、宿主读不到带理由的源；所以 CSS 是值的真相源，登记表只写结构，测试检查两者对得上。
- `tokens.ts`：每个 token 一条（`tier`：`semantic`／`role`／`group`／`axis`／`layout`；`kind`；有没有暗色一半；预设能不能写；桥接取不取；图表读不读；落回哪个 token；是否随品牌派生；链接到哪），外加 `TOKEN_GROUPS`、`THEME_AXES`，由它推出 `FveToken` 类型、`CHART_TOKENS` 与 `THEME_ATTRIBUTES`；构建写出 `dist/theme-tokens.json`（随包发出、不进 `exports`），`verify-package` 与 `theme-check` 读它。
- `tokenDocs.ts`（与 `brandDocs.ts`、`densityDocs.ts`、`stateDocs.ts`）：主题指南里生成的几张表的中英措辞，与结构分开，运行时不带。
- `pairs.ts`：底的列表、每一对、每套的线（`PRESET_LINES`：`contrast` 字 7、边与标记 4.5）与欠账（`PENDING`，今天是空的）；jsdom 的 `test/presetContrast.test.ts` 与 Storybook 的对比度矩阵都从它展开，两边永远量同一组对。
- **从它生成、或在测试里与它比对**：主题指南（中英）的 token 表、布局变量与让图表重读的属性三段（`pnpm --filter @ahoo-wang/wow-view-engine theme:docs` 重写，`test/themeFiles.test.ts` 比对）；复位规则的名单；`verify-package` 的每条主题断言（预设层集合、`whole` 的组、桥接的集合与例外、先读宿主再读预设的形状）；`styles.css` 与 `src/ui` 读的每个 `--fve-*` 都登记过、每个登记的都有读者（「一个没人读的 token 是一份没人守的合同」——`--info` 因此删掉，D30 Q45）。
- **`theme-check`**（包的命令 `wow-view-engine theme-check`，D46 修订 D30 Q50）：宿主在 CI 里对自己的 CSS 跑，用测试夹具同一份解析器（`theme-check/resolve.ts`）算出每套、每种明暗与约定下的 token，量登记表的每一对与色板三道门，并报未登记的变量、包在 `@layer` 里的预设、Tailwind v3 的 HSL 通道（给出换算写法）、品牌边界的越界与颠倒、低于 24px 的点击目标（D57：样式表不夹值，与颜色同一份合同，量出来、报出来）。

## 角色：引擎自己的面

shadcn 的语义名描述「颜色的用途类别」，一个名字身兼数职（`muted` 同时是表头带、合计带、选中行与按下态），一套预设想要「表头无底、选中行是品牌淡色」就说不出来。**角色**是引擎自己画的某一块面的某一个属性，宿主写 `--fve-<角色>`、预设写 `--fvp-<角色>`，引擎在边界上解析成 `--_fve-<角色>`；**不设时落回一个语义 token 或加角色之前画出来的值**，所以什么都不设的宿主与只改了 `muted` 的宿主都照旧。

| 区域（`area`） | 角色（举例）                                                                                                                                                                                                                                                               |
| -------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `surface`      | `canvas`（分组的底：仪表盘与宿主按卡片排的页面）、`content`（记录与分析的行、结果块）、`card-edge`／`card-shadow`、`scrim`                                                                                                                                                 |
| `table`        | `table-header`（及 `-foreground`、`-weight`、`-divider`）、`totals`、`row-selected`（及 `-foreground`）、`row-selected-mark`（选中行左边的色条，不设没有）、`row-hover`、`row-stripe`（默认关：它与悬停、选中争同一档亮度差，默认开会改 neutral 的样子、也让每套多量一对） |
| `state`        | `highlight`（菜单高亮）、`nav-current`（侧栏当前项，及 `-edge`、`-shadow`）、`item-selected`（菜单与选择框里已选的项）、`control-hover`／`control-pressed`、`outline-hover-edge`／`-foreground`                                                                            |
| `focus`        | `focus-width`、`focus-offset`、`focus-style`、`focus-halo`                                                                                                                                                                                                                 |
| `control`      | `control`、`control-edge`、`control-thumb`、`control-thumb-shadow`、`control-height`／`-sm`、`filter-height`、`edge-width`、`badge-edge`／`badge-fill`（百分比）                                                                                                           |
| `shape`        | `radius-card`、`radius-control`、`radius-popover`、`radius-badge`、`radius-checkbox`                                                                                                                                                                                       |
| `type`         | `title-weight`、`strong-weight`                                                                                                                                                                                                                                            |
| `float`        | `tooltip`、`tooltip-foreground`                                                                                                                                                                                                                                            |
| `chart`        | 见[图表从主题读外观](#图表从主题读外观)                                                                                                                                                                                                                                    |

完整的表、每个角色的后备与措辞在登记表与主题指南里。

- **施加不改 vendored 组件**：角色由 `styles.css` 里 `utilities` 层的规则按 `data-slot`、`data-state`、`data-variant` 施加，排在 registry 自己的类之后、与它替换的那条类同权重，靠次序赢；`data-slot` 会被调用处的 `render` 换掉的地方（按钮做了提示框的触发器）认 registry 自己的组类（`fve:group/button`、`fve:group/toggle`、`fve:group/badge`，样式表里写作 `.fve\:group\/button`）。要留住调用处选择的属性（高度、圆角、边宽），规则再用 `:where(.fve\:h-8)` 一类点名它替换的那个 registry 类，描边按钮按它的 `fve:border-border` 认（registry 的 `Button` 不在元素上写 variant；自己包一层只标得到我们的调用处，registry 自己组件里的描边按钮仍认不出，D76）——这是 D16「不按类名选」的有意例外，随 registry 改名暴露，`shadcn add --diff` 时要看。我们自己的配方（`variants.tsx`、`record/sticky.ts`）直接读角色的工具类。
- **没有内置值的角色**（`control`、`control-edge`、`control-thumb`、`control-hover`、`control-pressed`、`focus-width`、`filter-height`、`row-selected-mark`）：施加规则各自带着控件原来的值作后备，不设就是原样。`focus-width` 与 `filter-height` 靠计算期无效：不设时 `outline` 整条无效、回到 registry 的 `outline-none`；筛选芯片的高度与里面控件的上下限回到 `auto`／`0`／`none`，芯片仍由控件撑开；`row-selected-mark` 不设时渐变无效，`background-image` 是 `none`，什么都不画。
- **徽标的边默认保留**（D46）：`badge-edge`／`badge-fill` 是取徽标自己色调的多少（默认 30%／10%）；去掉边，淡色徽标在选中行上只剩约 1.2:1，低于「在行上仍是一个徽标」的 1.5:1，所以预设改用填色须在自己的 `row-selected` 上仍过 1.5:1，门守着。
- **打字的框仍有 3:1 的边**：WCAG 1.4.11 不要求以文字或图标自明的控件有边界（选择框有字和箭头），要求输入框有；所以 `ControlFrame` 装着输入框的筛选芯片不管主题怎么说都保留 `input` 边。要让它无边，得先有一套预设的芯片填色对底 ≥3:1——量过（D76）每套最高 1.50:1，所以不给配方加角色。
- **neutral 的选中、悬停、焦点保持原样**（D46）：neutral 的焦点在 AA 上成立，「机制的改动 neutral 逐像素不变」是硬约束；更强的默认值由各预设设；neutral 要不要变强是另一次视觉决定。
- **焦点**：不设 `focus-width` 时是 vendored 的 1px `border-ring` 加 `focus-halo`（`ring` 的 50%）的光晕，AA 成立；设了时控件画一道 `outline`，宽度与偏移读角色，光晕只改「本来就是 `ring` 的 50%」的那些（危险按钮与无效控件的红光晕不受影响）。引擎自己的三个焦点配方也读它（D76）：`FOCUS_ROW` 的内描边 `focus-width` 宽、光晕在它外 2px；`FOCUS_CARD` 的轮廓 `focus-width` 宽、离边 `focus-offset`（不设时压在自己的边上）；`FOCUS_INSET` 画在边内、`focus-offset` 作边内的间隔；三个的光晕都读 `focus-halo`。不设时读 1px，逐像素是原来的 1px `ring` 加光晕。
- **选中不只靠颜色**（WCAG 1.4.1，D76）：`row-selected-mark` 是选中的记录行、按下的分析分组第一格左边一道 3px 的色条（引擎只有从左到右的排版；背景图，不压字，获焦时行的内描边盖住它靠里的 `focus-width`），不设就没有；对比度矩阵在设了时量它在选中行上 ≥3:1。contrast 链到主色。

### 链接：角色跟着面上解析出的 token

预设块挂在 `data-fve-preset` 所在的元素上（宿主通常挂在 `<html>`），块里的 `var(--primary)` 在那里就替换了——那里没有引擎的 `--primary`（有 shadcn 宿主时还会取到宿主自己的）。自定义属性的值在声明它的元素上算，所以「在预设里写一个引用」在 CSS 里走不通；宿主在 `:root` 上写 `--fve-highlight: var(--primary)` 也一样。能原样穿过级联、到面上再解释的，只有不含 `var()` 的值。

- **所以链接写一个数**：`--fvp-<角色>-link: 100%`（宿主写 `--fve-<角色>-link`）。面上一条规则把它解析成 `color-mix(in oklab, var(<目标>) <数>, transparent)`，角色在宿主层与预设层之间读它：`var(--fve-x, var(--_fve-link-x, var(--fvp-x, …)))`。链到哪个 token 由登记表定（`link`），值只是「取多少」：`100%` 就是目标本身，少于它是把目标半透明地铺在底上（一个随品牌的淡色）。一个数管两种明暗，目标在每种明暗下各自解析。
- 链接随品牌色、宿主自己的 `--fve-primary`、`tokens` 与明暗走；不设时无效，角色读预设的字面量。宿主写了角色本身的颜色仍然最先赢。
- 今天的链接：`highlight` → `primary-fill`、`highlight-foreground` → `primary-fill-foreground`、`row-selected-mark` → `primary`、`nav-current` → `row-selected`、`nav-current-foreground` → `primary`、`item-selected` → `row-selected`、`outline-hover-edge` → `primary`、`outline-hover-foreground` → `primary`。以后再有角色要跟面上的 token，就在登记表加一个链接，不开新机制。
- **`primary-fill`**（D76）：主色作为承载文字的填色，不设就是 `primary`／`primary-foreground`。暗色的主色浅到能在暗底上当字，也就托不住白字；预设可给更深一档（porcelain 暗色 `#0058D0` 配白字），给了品牌色时按预设的 `brand-primary-fill-l-min`／`-max` 取品牌色相（与焦点环同一种「给了边界才派生」）。这一档是字面量：宿主在这样的预设上写自己的 `--fve-dark-primary` 时也写 `--fve-dark-primary-fill`。
- 没选的：关键字（`--fvp-highlight: primary`）要按值分支，靠 `@container style()` 或 `if()`，Firefox 都还没有；把这些角色也放进品牌派生只跟品牌色、不跟宿主的 `--fve-primary`，每个角色还要一组与主色重复的边界；让 JS 把外层的预设名抄到面上是第二个真相源。（见 test/themeLinks.test.ts，浏览器故事 `ThemeMechanism.test.stories.tsx`「PorcelainMenuFollowsTheBrandInDark」「AzureMarksFollowTheBrandInLight」）

## 品牌色是输入，不是预设

宿主写 `--fve-brand: <任意颜色>`（暗色可另给 `--fve-dark-brand`，不给就用同一个），任何一套预设照挂——或不挂（D46）。品牌色是一条独立的轴，不是一套风格：一个值若只能在风格与品牌之间二选一，就是被放错了层。

- **派生只写一处**：`styles.css` 里一个 `@supports (color: oklch(from red l c h))` 块，声明在面的边界上（`:where(.fve-root, .fve-tokens)`），所以 `var(--fve-brand)` 在面上替换，品牌色挂在任何祖先上都对。登记表标 `brand` 的 token 在块里多读一层：`var(--fve-x, var(--_fve-brand-x, var(--fvp-x, 内置值)))`——**宿主明写的 > 品牌派生 > 预设的字面量 > 内置值**。
- **派生什么**：主色（`oklch(from var(--fve-brand) clamp(下, l, 上) min(c, 彩度上限) h)`，暗色一半同形）、`accent`、`sidebar-accent` 与选中行（`row-selected`）取品牌色相的一档淡色；焦点 `ring` 只在预设给了焦点的亮度边界时派生（`azure`、`porcelain`、`contrast` 给了，`neutral` 的焦点是调到 3:1 的灰、没给）；图表第 1 色只在宿主挂 `data-fve-brand-chart` 属性时跟品牌色相、保留预设第 1 色的亮度与彩度（D46；品牌色相与相邻色位的色觉间距对任意品牌色证明不了，所以默认关、开了由宿主与 `theme-check` 量）。其余（灰、`input`、状态色、图表其余七色）是预设自己的。
- **边界是预设的**（`--fvp-brand-*` 这组参数，宿主也能写 `--fve-brand-*` 自己负责）：线是在预设自己的底上量的，同一个品牌色放在 azure 的灰底与 porcelain 的白底上，过线的亮度区间不同；neutral 用 `styles.css` 的默认（亮 0.40～0.50、暗 0.68～0.80）。代价是极亮（黄、青）或极暗的品牌色会被压进夹子，看起来比品牌手册深或浅——可达性优先，文档写明。
- **没给颜色、或浏览器不支持相对颜色**：派生值在计算期无效（或整块 `@supports` 不生效），每个 token 落回预设的字面量，像素不变；不会失效成透明。
- （见 test/brandInput.test.ts：每套 × 两种明暗 × 两种回到色域的方式扫 1 314 个颜色，夹具里每一对都过该预设的线；浏览器故事 `ThemeBrand.stories.tsx`）

## 内置预设

| 名字        | 性格                                                                                                     |
| ----------- | -------------------------------------------------------------------------------------------------------- |
| `neutral`   | 默认：中性灰、黑色主色，就是样式表自己的值；一个预设变量也不写                                           |
| `azure`     | 中国企业后台：明快的蓝、灰底白卡、6px 圆角、中文优先的系统字体栈；面向国内经营数据时常与 `red-up` 一起用 |
| `porcelain` | 桌面原生：系统字体、分部件的圆角、柔和的浮起、填色的控件、表格读作桌面列表（D59、D63、D69）              |
| `contrast`  | 高对比：字 ≥7:1，控件边、焦点与标记 ≥4.5:1，2px 焦点与控件边，图表默认开花纹                             |

- **每个值的理由写在它的源文件里**：`src/themes/<名>.css` 一套一个文件，块加上每个取值的理由（与参考的差异、量出来最紧的几对）；`src/themes.css` 是索引，顺序就是一切名单的顺序。构建由 `scripts/themes.mjs` 去掉注释做出 `themes.css`（全部）与 `themes/<名>.css`（单套，`./themes/*.css`）；`verify-package` 断言单套文件拼起来等于 `themes.css`、与 `BUILT_IN_PRESETS` 同序，并称重（每套 gzip ≤1.5 KB、`themes.css` ≤8 KB）。
- **类型**：`/ui` 导出 `BUILT_IN_PRESETS`（只读名字数组）、`BuiltInPreset` 与 `ViewPreset`（内置名 ∪ `(string & {})`：内置名有补全，宿主自己的预设名也能传）。不导出显示名：引擎里没有选择器。
- **命名**：描述性的普通词（材料、自然、性格），小写；不用任何公司、产品、设计系统或编辑器配色的名字。对外只描述性格，唯一的例外是主题指南「该选哪套」的指路表可以写「想要 macOS／Apple 桌面应用那种感觉 → `porcelain`」，只为让宿主找得到（D46：Apple 是商标，预设以风格命名）。值是按本包的门自己调出来的，不整套照搬；不带任何标识、图标、插画或字体文件，系统字体只经名字引用本机已装的。
- **预设可以带自己的图表八色**（D35 Q62，修订 D30 Q47）：全带或全不带，且必须过同一套色板门；过不了就用默认八色。色位是序数：`--chart-3` 是「第三个系列的颜色」，不是「青色」，所以写色位的 `ChartSpec.colors` 换预设会换色，要表达好坏、涨跌的用语义 token（[../model-shapes.md](../model-shapes.md)）。
- **高对比不跟随系统自动切换**：换哪套预设是宿主的外观；系统的「提高对比度」自动开的是花纹（D33 Q57）；Windows 的强制颜色模式由浏览器接管颜色，本包保证的是焦点、控件边与选中在那种模式下仍看得见（见[打印与强制颜色](#打印与强制颜色)）。
- **加回一套的条件**：有一个真实宿主要，且亮暗两半都过下面的门；预设名首发后锁定，改名是破坏性变更。
- **宿主自己的预设**写法与内置的完全一样（`:where([data-fve-preset='acme'])` 里写 `--fvp-*`，只写它改的）；Storybook 的样板 `host-theme/acme.css` 用同一份合同、角色、链接与品牌色，在浏览器里过同样的门（「能力/主题与预设/宿主自定义主题」）。

## 密度

- **一个档位驱动几样长度**：`styles.css` 在边界上算 `--_fve-density`（−1／0／+1），表头行高、单元格上下与左右内边距、侧栏视图项高、仪表盘面板内边距都写成「默认值 + a·档位 + b·档位²」，档位 0 时恰好是 registry 类给的长度。它们必须一起变，所以用一个档位而不是几个旋钮。
- **谁说了算**：面自己的 `density`（写成自己身上的 `data-fve-density`）＞ 祖先上的 `data-fve-density` ＞ 预设推荐的 `--fvp-preset-density` ＞ 0。推荐与选择是两个变量，所以任何预设嵌套下宿主的选择都赢。
- **每个长度也是宿主变量**（`tier: 'layout'`）：`--fve-table-header-height`、`--fve-table-cell-padding-block`、`--fve-table-cell-padding-inline`、`--fve-sidebar-item-height`、`--fve-panel-padding`；宿主写了哪个就是哪个，档位只给缺省值，预设不写。登记表的 `layout` 一层另有几个宿主的长度与层级，不属于主题、预设不写：`--fve-expanded-z-index`（展开到铺满时的层级）、`--fve-popup-z-index`（所有弹层的层级）、`--fve-record-table-max-h`（记录表的最大高度）、`--fve-record-text-max-w`（长文本单元格的最大宽度）、`--fve-workbench-min-height`（工作台的最小高度）。
- **不动**：按钮与输入框的高度（那是 `control-height` 角色，风格的一部分）、字号、弹层尺寸、**仪表盘行高 80px**（D34：存下的板子几何不变）。24px 的点击目标由 `theme-check` 量（D57）。表格那几样落在 `styles.css` 对 `[data-slot='table-head']`／`[data-slot='table-cell']` 的规则上，不改 vendored `Table`。
- （见 test/styleBoundary.test.tsx，浏览器故事 `Density.test.stories.tsx`）

## 涨跌色约定

- `data-fve-change-colors` 缺省即 `semantic`：按**好坏**着色（西方 BI 与 IBCS 的做法）；`red-up`／`green-up` 按**方向**，红涨绿跌是中国大陆股市与大量企业看板的口径，绿涨红跌是港股与欧美的。**不按界面语言自动切**（D35 Q61）：语言不等于市场，自动切会让同一个仪表盘换个语言就红绿颠倒。
- `--rise`／`--fall` 是方向的颜色：宿主的 `--fve-rise`／`--fve-fall` 优先，否则取约定的默认对——`semantic`、`green-up` 下是成功色／危险色，`red-up` 下对调。预设不写这四个变量。默认对写在 `:where(.fve-root, .fve-tokens)` 上而不是 `<html>` 上：`var(--destructive)` 在声明它的元素上替换，`<html>` 上没有 `--destructive`。
- 瀑布图与 K 线的升降永远按方向；指标卡的变化徽标（`ChangeBadge`）在 `semantic` 下按好坏（`data-tone`），在按方向的两种约定下按 `data-change` 取 `--rise`／`--fall`。选择全在 `styles.css`，组件不知道约定、不经 context；弹层经 `useSurfaceAttributes()` 照抄最近祖先的属性，与预设同一条路。
- **颜色从来不是唯一的线索**：红与绿对红绿色弱几乎一样，所以徽标带方向箭头与正负号，瀑布图的标签带符号，提示框写「较上一期 +12%」。（见 test/metricCardUi.test.tsx「says the direction without colour」，test/presetContrast.test.ts 三种约定下各量一遍）

## 字体、阴影与状态色

- **字体**：`.fve-root` 上 `font-family` 读表面字体，再读 `--fve-font-sans`、`--fvp-font-sans`；都没设时这条声明在计算期无效，按继承取宿主的，不需要写 `inherit`。预设可以带一条**系统字体栈**（`azure` 中文优先、`porcelain` 苹果优先，中文显式写 `PingFang SC`，否则缺 `lang` 时可能取到繁体字形）。
- **弹层的字体就是面的字体**：弹层 portal 到 `<body>`，继承的是 `<body>` 的字体；宿主把字体写在应用外壳上而 `<body>` 不设时，弹层会退成浏览器的衬线体。所以面把自己**算出的** `font-family` 读回来（与读 token 同一处、同一组观察者），经 `useSurfaceFont()` 交给 `ui/kit/popups.tsx`，弹层写成自己身上的表面字体变量。它是面的计算值，所以预设的栈、宿主的 `--fve-font-sans`（挂在 `<html>` 或只挂在子树上）、继承来的宿主字体，哪一种来源弹层都与面一致；样式表在每个根上先把它设成 `initial`，面不会从宿主页面上拿到同名变量。（见 test/popups.test.tsx「every popup takes the type of its surface」）
- **阴影三档**（`--fve-shadow-sm／md／lg`，亮暗各一份）只改阴影本身；卡片怎样浮起是 `card-shadow` 角色（D43）。**去掉阴影写透明阴影，不写 `none`**：工具类把阴影与 ring 拼成一个 `box-shadow` 列表，`none` 进了列表整条声明无效，弹层的发丝描边也跟着没了；测试禁止预设写 `none`。
- **暗色状态色是安静的**（D30 Q44，结清 D18 第 12 条）：暗色 success／warning／destructive 保留色相、彩度降到约 0.15，软徽章在深底上不发艳，danger 徽章在选中行上直接用自己的 token 就过线，不再为暗色写 `color-mix` 补丁。亮色状态色取作字 ≥4.5 的那一档。（见 test/presetContrast.test.ts「the dark status colours are desaturated (Q44)」）
- **能推导的就推导**：`--quiet-foreground` 是前景的七成（`color-mix`），`--destructive-foreground` 是底色，`row-hover` 是 `muted` 混进底；宿主只改前景或底色它们也跟着走，仍能单独覆盖。成对量对比度的 `-foreground` 保持 shadcn 的「成对显式」，不推导。（见 test/styleBoundary.test.tsx「the tokens the theme declares」，浏览器故事「能力/主题与预设/令牌/回归」的 `QuietInkFollowsForeground`）
- **`--ring` 与 `--input` 是独立的灰，不指向品牌 token**：shadcn 品牌主题惯用的 `--ring: var(--primary)`、`--input: var(--border)` 会把 1.4.11 要的 3:1 交给一个品牌色与一档分隔线灰。宿主改 `--fve-primary`／`--fve-border` 动不到焦点与控件边；品牌色只在预设给了焦点边界时带动焦点；宿主自己覆盖 `--fve-ring`／`--fve-input` 就欠自己的主题同一条线。（见 test/styleBoundary.test.tsx「keeps the focus ring and the control edge off the brand tokens」）

## shadcn 桥接

`/shadcn-bridge.css` 是一条 `:where(:root:not([data-fve-preset]))` 规则，把预设层的每个 `--fvp-<token>`（与暗色一半）写成 `var(--<token>)`——指向宿主同名的 shadcn token；**ViewHost** 的 `theme="host"` 就是这条路（[host-integration.md](../host-integration.md) 4.1）。

- **写预设层**：宿主的 `--fve-*` 仍先读；钉住预设的面由复位规则清掉桥接给的值。只在 `<html>` 没挂预设时生效：两者都在 `<html>` 上时要预设，不看引入顺序。
- **不桥接的**：`input`、`ring`（shadcn 主题常写成 `var(--border)`／`var(--primary)`，不欠 3:1）、状态色（连同墨色）、推导出来的 `row-hover`、`quiet-foreground`、图表八色与阴影（shadcn 没有标准的阴影名）。`radius` 与 `--fve-font-sans`（指向宿主的 `--font-sans`）桥接。宿主没声明的 token 让变量无效，落回内置值。
- **只配跟随宿主的明暗**：它在 `<html>` 上解析，两半读同一个宿主 token，钉成相反模式的面拿到的仍是宿主当前模式的值。
- **只支持 Tailwind v4**（D46；没有真实的 v3 宿主，不为没有使用者的形态加文件）：v3 的 shadcn 主题把 token 写成 HSL 通道（`--primary: 222 47% 11%`），`var(--primary)` 不是颜色；`theme-check` 认出并给换算写法，不另出 HSL 桥接。
- （见 test/shadcnBridge.test.ts，浏览器故事 `ShadcnBridge.test.stories.tsx`：补偿控制台自己的 `:root`／`.dark` 挂上桥接，面上的底、前景、主色、卡片等于宿主的，`input`／`ring` 不等于，控件边与焦点在亮暗两种下都 ≥3:1）

## 图表从主题读外观

ECharts 画在级联够不到的地方，所以 `readChartTheme`（`ui/charts/theme.ts`）在图自己的元素上把 token 读回成具体的值交给它（D21），`ChartTheme` 是完整的图表外观：色板、前景、底（`groundOf` 沿祖先往上找）、网格、轴、字号、线宽、面积、柱与扇区、花纹。

- **探针让浏览器算**：能直接解析的字面量直接读；写成 `var()`、`color-mix()` 或相对颜色的，挂一个隐藏探针让浏览器算——颜色用不继承的 `background-color`，长度与数字用 `width`（数字写成 `calc(var(--x) * 1px)`；`opacity` 会被夹到 0～1、初始值 1 又分不出「算出来是 1」），读不出落回内置值。不用 `@property` 注册：`--primary` 这类名字也是宿主 shadcn 的，全局注册会改变宿主自己的变量。ECharts 读不了 `oklch()`，颜色一律转成 `rgb()`。
- **图表的角色**（`area: 'chart'`）：`chart-grid`、`chart-grid-width`、`chart-axis`（图里弱一级的字）、`chart-text-size`／`chart-label-size`（跟随 `text-ui`）、`chart-line-width`、`chart-area-opacity`、`chart-bar-radius`（跟随 `radius`、但不超过 2px，`radius` 为 0 的方角风格柱子自己就方了）、`chart-bar-min-width`／`chart-bar-max-width`（最宽 80px，并排两根柱那么宽）、`chart-slice-border`、`chart-map-edge`（[D54](../decisions.md#d54-地图的区域靠边界线与底色分开2026-09-26)），以及图表提示框的 `chart-tooltip`、`-foreground`、`-shadow`（落回弹层那一组 `popover` 与 `shadow-md`）。选项构造函数里不写字号、圆角、线宽与柱宽的字面量（`test/chartTheme.test.tsx` 读语法树守着）。
- **何时重读**：`ViewSurface` 的观察者盯着根与每一层祖先上的 `THEME_ATTRIBUTES`（明暗、预设、涨跌约定、密度、`data-fve-brand-chart` 的属性，加上 `style`），每次变动在根上读一遍 `CHART_TOKENS` 与两种底色，读数的 key 变了才经 `useSurfaceTokens()` 往下送；`EChart` 以它为依赖就地重画、不重挂载，key 相同就不重画。只换样式表、不动任何属性的切换观察不到，所以换主题请改属性（指南写明）。系统的明暗与「提高对比度」由各自的 `matchMedia` 监听。（见 test/chartTheme.test.tsx「redraws when the host swaps its tokens and the mode stays」，浏览器故事「组件状态/分析工作台/图型/回归」的 `ChartFollowsHostTokens`）
- **兜底色**：jsdom 读不到样式表时用 `CHART_FALLBACK`，它是亮色 token 的第二份拼写，由测试逐值对齐 `styles.css`（颜色、长度与数字都对）。（见 test/chartTheme.test.tsx「falls back to the stylesheet’s own light tokens」）
- **色板**：八色按两种明暗、按色觉间距校过，顺序固定、按序发（顺序本身就是相邻两色分得开的保证）。顺序色（热力图、矩形树图、日历、地图）由第一色向底色混，不设 token；发散色今天没有读者，不加 token。悬停色（`emphasized`）、柱内字色（`inkOn`）、热力格的浅端（`mixColor`）都从读回的值算。
- **打印时重读一次**：图表是脚本画的，媒体查询够不到；`charts/print.ts` 的 `usePrinting` 在打印开始与结束时让 `EChart` 重读，纸上的图是亮色、带花纹的。

## 打印与强制颜色

- **打印**：暗色的变体与暗色 token 块包在 `@media not print` 里，纸上读当前预设的亮色一半，每套预设天然有一份打印样子，不需要打印预设；`@media print` 把三级阴影、卡片的浮起、钉住列的边影与行悬停色设成透明，把 `--fve-chart-patterns` 设成 `on`（黑白打印时系列只能靠花纹区分；这是 `styles.css` 唯一有意写公开前缀的地方），图表、徽章与变化徽标 `print-color-adjust: exact`。
- **强制颜色**（`forced-colors: active`）：vendored 控件的焦点（叠在 `outline-none` 上的阴影）与获焦行的光晕会被丢掉、选中行的底被重画成页底。`@media (forced-colors: active)` 给 `:focus-visible` 画 2px `CanvasText` 的轮廓，选中行与按下的分析分组画 2px `Highlight` 的内框（系统色，浏览器不重画；不用 `Highlight` 填满——那要让格子退出重画，里面的复选框与徽章就留着为主题挑的颜色），说自己被选中的控件也一样：侧栏与板上标签的当前项（`aria-current`）、按下的开关（`aria-pressed`，图例除外：按下即系列在画，隐藏的那条有删除线）、选中的标签页（D73）；焦点的轮廓压过这些框。
- （见浏览器故事 `PaperAndContrast.test.stories.tsx`「ForcedColors」「Print」）

## 质量门

- **对比度矩阵**：`test/presetContrast.test.ts` 按两份样式表解析出的值，对每套 × 每种明暗 × 每种涨跌约定量登记表的每一对（字 ≥4.5:1、控件边与焦点 ≥3:1、标记，`contrast` 按 `PRESET_LINES` 更高），底包括页底、分组底、卡片、弹层、内容底、表头带、合计带、选中行、隔行、悬停行、侧栏、菜单高亮、提示框、控件填色与暗色的 `input/30` 洗色；新预设按名字自动进矩阵。浏览器里的矩阵（`ThemeContrast.stories.tsx`）量级联后的真实颜色，抓得到 jsdom 看不到的（阴影描边、相对颜色真的解析了没有）；页面上能粘贴一段 `--fve-*` 当场一起量。
- **色板门**（`test/paletteDistance.test.ts`，每套自带的与默认八色、每种明暗；相邻含第 8 与第 1 色，饼图首尾相接；culori 的色觉模拟，强度 1）：

  | 检查                | 门槛                                                                   |
  | ------------------- | ---------------------------------------------------------------------- |
  | 相邻色，正常视觉    | OKLab×100 ≥15                                                          |
  | 红色弱、绿色弱模拟  | ≥8                                                                     |
  | 蓝色弱模拟          | ≥6                                                                     |
  | 对卡片底            | 暗色每色 ≥3:1；亮色 ≥3:1，例外逐色列在测试里                           |
  | 柱内字色（`inkOn`） | 每色都有一种墨色 ≥4.5:1，页底与卡片各量一次（test/paletteInk.test.ts） |

  亮色允许例外，因为有补救：花纹（D33 Q57）与读屏表；新增一个例外就是一次有意的改动。蓝色弱取 6 而不是 8：默认八色暗色只有 6.1，定 8 等于否定一套上线的、另两种色弱都过的色板。

- **品牌扫描**（test/brandInput.test.ts）、**点击目标**（test/themeRoles.test.ts：两档控件高度在每套里 ≥24px，承诺 AAA 的预设画轮廓时 ≥2px）、**三层与链接**（test/themeLayers.test.ts、test/themeLinks.test.ts）、**解析快照**（test/themeSnapshot.test.ts：每套 × 明暗 × 约定解析出的 token，改一处值只让它自己的那几行变）。
- **非颜色线索**：涨跌、好坏、状态在任何预设下都不只靠颜色（见[涨跌色约定](#涨跌色约定)）。
- **截图基线**（D45）：「主题一览」每套一个故事，亮暗两条带、每条记录／分析／仪表盘三种视图，外加几块关键屏与角色的几张；Vitest 浏览器模式的截图断言，不同像素为 0，浏览器永远在 Playwright 的 Linux 容器里，本机与 CI 截出同一张图。更新办法写在 Storybook README「截图基线」。机制的改动让每张逐像素不变；一套预设的重调只更新它自己的基线。
- **体积**：`verify-package` 称重 `styles.css`、`themes.css` 与每个单套文件（上限在 `scripts/size-budget.json` 与脚本里，理由写在各自旁边）。体积是护栏，不是目标。

## Storybook

- 工具栏有「Preset」（`fvePreset`，把 `data-fve-preset` 挂在 `<html>` 上；`neutral` 即不挂）、明暗（多一档 `system`）、「Density」「Change colors」四个全局，都像宿主一样写 `<html>`。Storybook 打开时是 `porcelain`，引擎自己的默认仍是 `neutral`，讲 neutral 的故事用 `globals` 钉住它。
- 预设名单读 `/ui` 的 `BUILT_IN_PRESETS`（`stories/view-engine/presets.ts`），工具栏、主题一览与对比度矩阵共用它：包里多一套，三处就多一套。
- 「能力/主题与预设」下：每套一个主题一览（`ThemeGallery.stories.tsx`）、对比度矩阵、品牌色、宿主自定义主题；角色、三层、链接、图表角色、密度、打印与强制颜色各有自己的回归故事。
