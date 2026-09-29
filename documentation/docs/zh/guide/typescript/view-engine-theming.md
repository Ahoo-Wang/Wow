---
title: 视图引擎的主题
description: 尚未发布的 wow-view-engine 怎样穿上宿主的外观——三层变量、预设、作为输入的品牌色、引擎的角色与链接、图表的角色、亮暗与跟随系统、钉住、嵌入与弹层、shadcn 桥接，以及覆盖变量要守的对比度。
---

# 视图引擎的主题

::: warning 尚未发布
`@ahoo-wang/wow-view-engine` 尚未发布到 npm，也不承诺兼容。本页描述的是仓库里当前的主题做法。
:::

主题是宿主的外观，不是观察方式：它不存进视图、仪表盘或个人偏好，工作台里也没有主题开关。预设、品牌色与明暗都由宿主选，引擎跟随。下面的一切都是 CSS 自定义属性——没有主题对象。

## 从这里开始：两条路

宿主只做一个选择，写在 `ViewHost` 上（包的 README 的快速上手）：

| 路         | 适合                                  | 写法                                                                                                                                                  |
| ---------- | ------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------- |
| 引擎跟宿主 | 已有 shadcn 主题（Tailwind v4）的宿主 | `theme="host"`，并引入 `shadcn-bridge.css`（[桥接](#shadcn-桥接)）                                                                                     |
| 宿主跟引擎 | 没有主题、或愿意穿引擎主题的宿主      | `preset="porcelain"`（引入它的文件），可加 `brand="#1d4ed8"`（[预设](#预设)、[品牌色](#我有品牌色)）；宿主自己的外壳挂 [`fve-tokens`](#宿主自己的外壳) |

`ViewHost` 把预设与品牌色点名在 `<html>` 上，也在那里画明暗（`colorMode`，[见下](#亮、暗与跟随系统)）。本页其余部分是这两行底下的东西，以及越过它们时伸手去拿的东西。

## 样式表

| 入口                                           | 是什么                                                                | 什么时候引                 |
| ---------------------------------------------- | --------------------------------------------------------------------- | -------------------------- |
| `@ahoo-wang/wow-view-engine/styles.css`        | 主题本身：每条规则都收在视图自己的边界里，每个 token 都先读宿主的变量 | 总要引                     |
| `@ahoo-wang/wow-view-engine/themes.css`        | 全部内置预设，由 `data-fve-preset` 属性选中                           | 运行时要切换预设           |
| `@ahoo-wang/wow-view-engine/themes/<名>.css`   | 单独一套内置预设，就是 `themes.css` 里它那一块                        | 只用一套预设               |
| `@ahoo-wang/wow-view-engine/shadcn-bridge.css` | 把宿主的 shadcn/ui（Tailwind v4）token 读进预设层                     | 应用已经有一套 shadcn 主题 |

几个可选文件都只给预设层的变量（`--fvp-*`）赋值：什么都不画，也碰不到宿主自己的任何变量。包在每次构建时核对这一点。

## 三层

主题由三方来写，各用自己的前缀，每个 token 都按固定的顺序读它们：

| 前缀                      | 谁写                                       | 写在哪                                   | 例子                                                         |
| ------------------------- | ------------------------------------------ | ---------------------------------------- | ------------------------------------------------------------ |
| `--fve-*`、`--fve-dark-*` | **宿主**，也就是你                         | `:root`、视图的任一祖先，或面的 `tokens` | `--fve-primary`、`--fve-brand`、`--fve-row-selected`         |
| `--fvp-*`、`--fvp-dark-*` | **预设**——内置的、你自己的，或 shadcn 桥接 | `:where([data-fve-preset='…'])` 块       | `--fvp-primary`、`--fvp-brand-l-max`、`--fvp-highlight-link` |
| `--_fve-*`                | **引擎**自己                               | 视图内部                                 | 它解析出、量出的中间值；宿主既不写也不读                     |

面上的每个 token 都是 `var(--fve-<token>, var(--fvp-<token>, <内置值>))`：先读你的，再读预设的，最后是样式表自己的值。所以**你设的变量总赢过任何预设**——`<html>` 上的，与钉在面上的都一样，与样式表的加载顺序无关。想改预设里的一个颜色，不必把其余的重写一遍。要让某一块面不受某个覆盖影响，就把这个覆盖写在比 `:root` 更窄的选择器上。

`--_fve-*` 是引擎自己的名字，随时会变；登记表里没有的 `--fve-*`、`--fvp-*` 名字什么也不做。完整的列表是下面的 [token 表](#全部变量)，由主题登记表生成；`dist/theme-tokens.json` 是同一张登记表的数据形式。

## 预设

选一种外观只要一行：引这套预设的文件，在 `<html>` 上写它的名字。

```ts
import '@ahoo-wang/wow-view-engine/styles.css';
import '@ahoo-wang/wow-view-engine/themes/porcelain.css';
```

```html
<html data-fve-preset="porcelain"></html>
```

要在运行时切换，就改引 `themes.css`（全部预设）。`<html>` 上的属性作用到所有视图与弹层；想让某一个视图用自己的，传 `preset`（见[钉住预设](#钉住预设)）。`/ui` 导出 `BUILT_IN_PRESETS`（内置预设名的列表），给宿主在自己的 chrome 里做选择器——引擎不画选择器。

| 预设        | 性格                                                                                               | 圆角           | 图表八色 |
| ----------- | -------------------------------------------------------------------------------------------------- | -------------- | -------- |
| `neutral`   | 默认：中性灰、黑色主色                                                                             | 10px           | 默认     |
| `azure`     | 中国企业后台：明快的蓝、灰底白行、中文优先的系统字体栈                                             | 6px            | 自带     |
| `porcelain` | 桌面原生：系统字体、6px 的控件配 12px 的卡片、柔和阴影、近中性的灰、主色填充的菜单高亮、隔行的表格 | 6px／卡片 12px | 自带     |
| `contrast`  | 高对比：字 ≥7:1，2px 的控件边与离控件 2px 的 2px 焦点 ≥4.5:1，选中行着色，默认开图表花纹           | 4px            | 自带     |

我的品牌该选哪套：

| 你的情况                           | 用什么                                                                        |
| ---------------------------------- | ----------------------------------------------------------------------------- |
| 已经是 shadcn 应用，有自己的主题   | [`shadcn-bridge.css`](#shadcn-桥接)，不挂预设                                 |
| 没有设计系统，要一个现成的风格     | 上表里最像你产品的那一套                                                      |
| 后台长得像国内常见的开源组件库     | `azure`；看板面向 A 股或国内经营数据时再加 `data-fve-change-colors="red-up"`  |
| 想要 macOS／Apple 桌面应用那种感觉 | `porcelain`                                                                   |
| 只有一个品牌色                     | 任何一套预设（包括 `neutral`）加 `--fve-brand`（见[我有品牌色](#我有品牌色)） |
| 有完整的设计规范                   | 选最接近的一套，再在 `:root` 上覆盖差的那几个 `--fve-*`                       |

每套都有亮暗两半，每一对都量过：字 ≥4.5:1，控件边与焦点 ≥3:1，自带的图表八色过与默认八色同一套色觉门。每套设了哪些值、为什么，写在包里 `src/themes/<名>.css` 的注释里。

- **预设与明暗互不相干。** 预设提供亮暗两半的值；亮还是暗仍按下文「亮、暗与跟随系统」决定。
- **预设只写它改的。** 没写的就是样式表自己的值：`styles.css` 在每个挂了预设的元素上把预设层清空（一条零权重的规则，放在它最低的层 `fve-reset` 里），所以钉在另一套里的预设拿不到外层那套的任何值，`neutral` 什么都不写。
- **预设可以给什么**：它要改的颜色与 `radius`、任何一个[角色](#角色)与[链接](#角色的链接)、[品牌色](#我有品牌色)的边界（`--fvp-brand-l-min` 等）、自己的图表八色与三档阴影（各自两种明暗全带或全不带）、一条系统字体栈（`--fvp-font-sans`）、图表花纹的钉（`--fvp-chart-patterns`，只有 `contrast` 设它），以及它推荐的密度（`--fvp-preset-density`）。它从不设 `pin-shadow`、`text-ui`、涨跌色，也不设品牌色本身。

### 自己的预设

自己的预设照同样的写法定义、用同一个属性或 prop 选中。内置预设用的也是这同一份合同、别无其他——没有私有选择器，也没有为哪一套预设开的代码路径——所以内置预设做得到的，你的也做得到：

```css
:where([data-fve-preset='acme']) {
  --fvp-radius: 0.5rem;
  --fvp-table-header-weight: 600;
  --fvp-highlight-link: 100%;
  --fvp-highlight-foreground-link: 100%;
  --fvp-focus-width: 2px;
  --fvp-focus-offset: 2px;
  --fvp-focus-halo: transparent;
  --fvp-dark-focus-halo: transparent;
}
```

- **写在你自己的任何 `@layer` 之外。** 复位规则在 `styles.css` 最低的那一层；预设若写在你的样式表更早声明的层里，就输给复位，什么都画不出来。
- **只写 `--fvp-*`，只写不一样的。** 不写 `initial`，也不抄你保留的值。预设块里的 `--fve-*` 是宿主变量：钉在这个元素里面的每一套预设都会输给它。
- **检查它。** `wow-view-engine theme-check` 在你的 CI 里把它对到登记表与每一对对比度上（见[检查一套主题](#检查一套主题)）；也可以把它的声明粘进[对比度矩阵](/storybook/?path=/story/view-engine-能力-主题与预设--contrast)：与内置预设一起逐对量，图表八色也过色板的门。

Storybook 的[宿主自定义主题](/storybook/?path=/story/view-engine-能力-主题与预设-宿主自定义主题--host-authored)是一个完整的例子，即仓库里的 `stories/view-engine/host-theme/acme.css`：包外的一份样式表，一个品牌色、几个角色、链接到主色的菜单高亮、一套自己的图表八色，在浏览器里与包的测试里过同样的门。

## 我有品牌色

品牌色不是一套预设：把它写成 `--fve-brand`，预设照样随便挑——不挂也行。

```css
@import '@ahoo-wang/wow-view-engine/styles.css';
@import '@ahoo-wang/wow-view-engine/themes/azure.css';

:root {
  --fve-brand: #7c3aed;
  --fve-dark-brand: #a78bfa;
}
```

```html
<html data-fve-preset="azure"></html>
```

主色取你的颜色的色相，选中项（`accent`）、视图列表里悬停的那一个（`sidebar-accent`）与选中行（`row-selected`）的淡色也取它；焦点环本来就是主色的预设（`porcelain`、`contrast`）里焦点环也跟着变。派生在 OKLCH 里算、只写一处（`styles.css`），在视图自己身上算；每套预设只给它在自己的底上量出来的**边界**：`neutral` 把主色亮度夹在亮色 0.40～0.50、暗色 0.68～0.80，`contrast` 为了 7:1 夹在 0.25～0.36 与 0.80～0.90。所以任何颜色都守得住你挂的那一套的每一条对比度线——有一个测试在每套、两种明暗下扫过整个 sRGB——太亮或太暗的品牌色会比品牌手册深一些或浅一些。灰、`input`、状态色与图表八色仍是预设自己的。

- `--fve-dark-brand` 给暗色一个自己的颜色；不写，暗色也从 `--fve-brand` 派生。
- 变量挂在视图之上的哪里都行——`:root`、某个包裹层，或面的 `tokens`（弹层会离开包裹层，只给某一块视图时用 `tokens`）。
- 你自己设的 `--fve-primary`（或 `--fve-accent`、`--fve-sidebar-accent`、`--fve-row-selected`、`--fve-ring`）仍赢过品牌色，品牌色又赢过预设自己的颜色。
- 边界也是预设层的变量（`--fvp-brand-l-min`、`--fvp-brand-l-max`、`--fvp-brand-c-max`，以及 token 表里其余 `brand-*` 几行）。宿主可以用 `--fve-brand-l-max` 这样的写法放宽或收紧某一个，写了它的测量就归你。
- 图表第 1 色默认保持预设的颜色，在 `<html>`（或视图的任一祖先）上加 `data-fve-brand-chart` 属性才跟品牌色：取你的色相、保留预设调好的亮度与彩度，此时色板的测量归你，与自己设 `--fve-chart-1` 一样。它与预设、密度一样是属性：有就开、没有就关，视图会把它抄到弹层上。
- [链接](#角色的链接)到主色或选中行的角色也跟着品牌色：`porcelain` 的菜单高亮，`azure` 的当前视图与已选项。
- 没给颜色，或浏览器早于 Chrome 119、Safari 18、Firefox 128，预设原样。

## 宿主覆盖

每个 token 都先读一个宿主变量：亮色是 `--fve-<token>`，暗色是 `--fve-dark-<token>`。写在宿主自己的 `:root` 上：

```css
:root {
  --fve-primary: oklch(0.4 0.21 265deg);
  --fve-primary-foreground: oklch(0.99 0 0deg);
  --fve-dark-primary: oklch(0.75 0.15 265deg);
  --fve-dark-primary-foreground: oklch(0.21 0.05 265deg);
  --fve-radius: 0.375rem;
}
```

按[三层](#三层)的规则，它们赢过你选的预设，也赢过钉在面上的预设——所以每一套预设上都要量：这里的主色够深，过得了 `contrast` 的 7:1，也过得了其余各套的 4.5:1；你的主题也由 [`theme-check`](#检查一套主题) 这样量。只给某一块面的，把 `tokens` 传给 `ViewSurface`、工作台或嵌入组件，而不是写在包裹层上：弹层 portal 到 `<body>`，不在包裹层下，`tokens` 会写在面上以及它打开的每个弹层上。它的类型是 `/ui` 导出的 `FveToken`，即登记表里的每一个宿主变量。

<!-- typecheck-context
import type { ViewEngine } from '@ahoo-wang/wow-view-engine';
import { DataWorkbench } from '@ahoo-wang/wow-view-engine/ui';
declare const engine: ViewEngine;
-->

```tsx
<DataWorkbench
  engine={engine}
  definitionId="orders"
  tokens={{ '--fve-brand': '#0f766e', '--fve-table-header-weight': '600' }}
/>
```

## 角色

`muted` 这样的 token 是一类颜色，面上好几处都用它：表头带、合计带、选中行都是 `muted`。**角色**就是其中的一处——引擎自己的一块面——所以可以只改它。每个角色和其他 token 一样是一个变量（宿主写 `--fve-<角色>`，预设写 `--fvp-<角色>`，颜色的暗色一半带 `-dark-`），不设时落回它一直读的那个 token，或者这块面在有这个角色之前画出来的样子：一个角色都不设就什么都不变，改 `--fve-muted` 仍会让表头带、合计带与选中行一起变。

```css
:root {
  --fve-row-selected: oklch(0.96 0.03 250deg);
  --fve-table-header-weight: 600;
  --fve-table-header-divider: oklch(0.87 0 0deg);
  --fve-focus-width: 2px;
  --fve-focus-offset: 2px;
  --fve-focus-halo: transparent;
  --fve-dark-focus-halo: transparent;
  --fve-control-height: 2.25rem;
  --fve-control-height-sm: 1.875rem;
}
```

这几行只给选中行上色、表头带仍是 `muted`；表头 600、列之间加分隔线；焦点去掉光晕、换成离控件 2px 的 2px 轮廓；控件 36px，小控件 30px。

| 面的哪一部分 | 角色                                                                                                                                                                                                                                                                                                                                                                                              |
| ------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 底与卡片     | `canvas`（看板的分组底）、`content`（行的底）、`card-edge`、`card-shadow`、`scrim`                                                                                                                                                                                                                                                                                                                |
| 表格         | `table-header`、`table-header-foreground`、`table-header-weight`、`table-header-divider`、`totals`、`row-selected`、`row-selected-foreground`、`row-hover`、`row-stripe`（不设就关）、`row-divider`（表体行线，不设就是 `border`）、`table-sort-idle`（未排序列的标记在指针设备上显示多少，不设是 1）                                                                                                                                                                                                              |
| 状态         | `highlight`、`highlight-foreground`（菜单、选择框、组合框里键盘所在的那一项）；`item-selected`、`item-selected-foreground`、`item-selected-weight`（其中已选中的那一项）；`nav-current`、`nav-current-foreground`、`nav-current-edge`、`nav-current-shadow`（视图列表里正在看的那一个）；`control-hover`、`control-pressed`；`outline-hover-edge`、`outline-hover-foreground`（指针下的描边按钮） |
| 焦点         | `focus-width`、`focus-offset`、`focus-style`、`focus-halo`                                                                                                                                                                                                                                                                                                                                        |
| 控件         | `control`、`control-edge`、`control-thumb`、`control-thumb-shadow`、`control-height`、`control-height-sm`、`filter-height`（看板的筛选芯片）、`edge-width`、`badge-edge`、`badge-fill`                                                                                                                                                                                                            |
| 圆角         | `radius-card`、`radius-control`、`radius-popover`、`radius-badge`、`radius-checkbox`                                                                                                                                                                                                                                                                                                              |
| 字重与提示框 | `title-weight`、`strong-weight`、`tooltip`、`tooltip-foreground`                                                                                                                                                                                                                                                                                                                                  |
| 图表         | 见[图表的角色](#图表的角色)                                                                                                                                                                                                                                                                                                                                                                       |

- **角色要守的线**：控件在两档高度下都守 24px 的地板（WCAG 2.5.8）——样式表按你写的高度原样画，所以 `--fve-control-height`、`--fve-control-height-sm` 或 `--fve-filter-height` 低于它时 `theme-check` 报错——承诺 AAA 字的主题给焦点轮廓至少 2px（WCAG 2.4.13）。每个作为底的角色都与其余的底一起进对比度矩阵，所以把它与所落回的 token 分开的主题，量的是它真画出来的样子。
- **每个角色的用途与默认值**写在 [token 表](#全部变量)里。

### 角色的链接

预设写的颜色在挂预设的元素上（通常是 `<html>`）就定下来了，所以预设说不出「菜单高亮就是主色」：那个主色要到视图上才从你的品牌色或你自己的 `--fve-primary` 解析出来。**链接**替它说。`<角色>-link` 是视图上解析出的另一个 token 取多少：`100%` 就是那个 token 本身，少于它是把它半透明地铺在底上。

| 链接                            | 画的角色                   | 取自                 |
| ------------------------------- | -------------------------- | -------------------- |
| `highlight-link`                | `highlight`                | `primary`            |
| `highlight-foreground-link`     | `highlight-foreground`     | `primary-foreground` |
| `item-selected-link`            | `item-selected`            | `row-selected`       |
| `nav-current-link`              | `nav-current`              | `row-selected`       |
| `nav-current-foreground-link`   | `nav-current-foreground`   | `primary`            |
| `outline-hover-edge-link`       | `outline-hover-edge`       | `primary`            |
| `outline-hover-foreground-link` | `outline-hover-foreground` | `primary`            |

```css
:root {
  --fve-highlight-link: 100%;
  --fve-highlight-foreground-link: 100%;
}
```

写了这两行，菜单、选择框与组合框的高亮项都用主色填充、主色的字——就是你的品牌色，亮暗都对。链接一个数管两种明暗，因为它指向的 token 在每种明暗下各自解析。你写的角色颜色赢过链接，链接（你的或预设的）赢过预设自己的颜色；不设时角色照旧。`porcelain` 链了菜单高亮，`azure` 链了当前视图、已选项与描边按钮悬停时的边——所以它们会跟着品牌色。

## 图表的角色

图表库自己画 SVG，样式表够不到，所以图表把自己的整个外观从元素上读回来——颜色读成颜色，长度读成像素，数字读成数字，都由浏览器算（`calc()`、`min()`、`oklch(from …)` 都算在内）——再按它画：

| 角色                                                                | 是什么                                              | 不设时                                       |
| ------------------------------------------------------------------- | --------------------------------------------------- | -------------------------------------------- |
| `chart-grid`、`chart-grid-width`                                    | 网格线与坐标轴的轴线                                | `border`、1px                                |
| `chart-axis`                                                        | 图表里弱一级的字：刻度、轴名、色阶两端              | `muted-foreground`                           |
| `chart-text-size`、`chart-label-size`                               | 图表的字号与值标签的字号                            | `text-ui` 减 1px、减 2px                     |
| `chart-line-width`、`chart-area-opacity`                            | 折线，与它下面的面积                                | 2px、0.2                                     |
| `chart-bar-radius`、`chart-bar-min-width`、`chart-bar-max-width`    | 柱的圆角与柱宽的上下限                              | `radius` 的 0.6、至多 2px；无；80px          |
| `chart-slice-border`                                                | 扇区之间的缝                                        | 1px                                          |
| `chart-map-edge` | 地图的边界线，描在每个区域四周：没数的区域、岛屿与争议线靠它才与底色分得开；它是边，对底色和色阶最浅的一档都要 3:1 | `chart-axis` |
| `chart-tooltip`、`chart-tooltip-foreground`、`chart-tooltip-shadow` | 图表的提示框，它是 HTML，像其他弹层一样读这几个角色 | `popover`、`popover-foreground`、`shadow-md` |

柱的圆角跟着 `radius`，所以方角风格的柱子自己就方了，不必另说。主题一变，图表会被告知重读（见[嵌入与弹层](#嵌入与弹层)）。

## 亮、暗与跟随系统

| 做法                                                                     | 效果                                                                                                                                   |
| ------------------------------------------------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------- |
| `ViewHost` 缺省（`colorMode="system"`）                                  | 在第一次绘制前写 `<html>` 的 `.dark` 与 `color-scheme`，跟随系统；`useColorMode()` 让宿主的开关钉住一种，按 `rememberColorMode` 记住 |
| `ViewHost` 上写 `colorMode="light"` 或 `"dark"`                          | 从钉住开始；读者仍可另选                                                                                                               |
| `ViewHost` 上写 `colorMode="host"`，宿主自己挂 `.dark`（通常在 `<html>`） | 视图跟随页面的明暗（next-themes、宿主自己的开关）；引擎不碰 `<html>`                                                                    |
| 在 `ViewSurface`、工作台或嵌入组件上写 `theme="light"` 或 `theme="dark"` | 钉住这一个视图                                                                                                                         |
| `theme="system"`                                                         | 这一个视图跟随读者的 `prefers-color-scheme` 并实时切换                                                                                 |

**暗色值写 `--fve-dark-*`，不写 `.dark` 下的 `--fve-*`。** shadcn 的习惯——在 `.dark` 下重写同一个变量——过不了「钉住」：变量从 `<html class="dark">` 一路继承下来，钉在浅色的视图拿到的就是宿主的暗色值，分不出是给哪种模式的。两半各按自己的模式起名，每块面才能挑自己的。走[桥接](#shadcn-桥接)的宿主不受影响：桥接读的是宿主自己按 `.dark` 切换的 shadcn 变量。

## 钉住预设

在 `ViewSurface`、工作台或嵌入组件上写 `preset="porcelain"`，这个视图就钉在这套预设上，不管 `<html>` 上是什么。挂在其他祖先上的 `data-fve-preset` 也有效：面会找到最近的那一个。钉住的预设完整替换外层那套，你自己的 `--fve-*` 在里面仍然赢。

<!-- typecheck-context
import type { ViewEngine } from '@ahoo-wang/wow-view-engine';
import { EmbeddedView } from '@ahoo-wang/wow-view-engine/ui';
declare const engine: ViewEngine;
declare const id: string;
-->

```tsx
<EmbeddedView engine={engine} instanceId={id} theme="dark" preset="azure" />
```

## 嵌入与弹层

菜单、下拉、气泡、提示与对话框都 portal 到 `<body>`，不在视图所在的那部分页面里。它们带着面解析出的结果——明暗写成 `data-theme`，预设写成 `data-fve-preset`，还有面找到的密度、涨跌约定与图表跟品牌的开关——所以钉住的嵌入，弹层也与它一致。

- **用属性切换，不要换样式表。** 图表从 token 读外观，面或祖先上这些属性一变就重读：`class`、`data-theme`、`data-fve-preset`、`data-fve-change-colors`、`data-fve-density`、`data-fve-brand-chart` 或 `style`。只换了样式表、没有属性变化时，图表会留在旧颜色上。
- **设在某个元素上的变量到不了 `<body>`。** 嵌入外面那张卡片上设的 `--fve-*` 能传到嵌入，传不到它的弹层，因为弹层不在卡片里。整页的值写在 `:root` 上；只给一个视图的，用 `tokens` 或 `preset`。
- **卡片里的嵌入**画的是 `--background`。在卡片上把 `--fve-background` 与 `--fve-dark-background` 设成卡片的颜色，不要设成 `transparent`。
- **层级**：弹层在 `z-index: 50`；用 `:root` 上的 `--fve-popup-z-index` 一次抬高全部。

## shadcn 桥接

```ts
import '@ahoo-wang/wow-view-engine/styles.css';
import '@ahoo-wang/wow-view-engine/shadcn-bridge.css';
```

桥接写的是预设层——每个 `--fvp-<token>` 与 `--fvp-dark-<token>`——取同名的 shadcn token：`background`、`foreground`、`card`、`popover`、`primary`、`secondary`、`muted`、`accent` 及它们的 `-foreground`、`border`、五个 `sidebar*` 与 `radius`，`--fvp-font-sans` 取宿主的 `--font-sans`。它在 `<html>` 上解析，所以取的是 `<html>` 当前模式下宿主的值；你自己的 `--fve-*` 仍赢过它。

| 不桥接                              | 为什么                                                                                                                     |
| ----------------------------------- | -------------------------------------------------------------------------------------------------------------------------- |
| `input`、`ring`                     | shadcn 主题常写 `--input: var(--border)`、`--ring: var(--primary)`：一条分隔线的灰和一个品牌色，都不欠控件边与焦点要的 3:1 |
| `destructive`、`success`、`warning` | 按两种明暗量到 4.5:1 的文字色；shadcn 没有 `success` 与 `warning`                                                          |
| 图表八色                            | shadcn 的色板只有五色，常以红色打头；这八色按色觉缺陷间距量过                                                              |
| 阴影                                | shadcn 没有标准的阴影 token 名                                                                                             |
| `row-hover`、`quiet-foreground`     | 由已桥接的 token 推导                                                                                                      |

已有 shadcn 主题的应用这样接入：

1. `:root` 与 `.dark` 两块保持原样，`.dark` 挂在 `<html>` 上。
2. 引 `styles.css` 与 `shadcn-bridge.css`。去掉 `<html>` 上的 `data-fve-preset`：桥接只在 `<html>` 没挂预设时生效，挂了就是预设赢；用 `preset` 钉住的面穿的是钉住的那套。
3. 让视图跟随页面的明暗。钉成相反模式的视图，亮暗两半读到的都是宿主当前的值。
4. 桥接之外还想改的，逐个变量覆盖，比如 `--fve-ring`，并量一量（见下）。
5. 检查自己的文字 token：它们原样桥接过来。宿主的 `--muted-foreground` 在 `--background` 上不到 4.5:1，视图里也就不到。

### 只支持 Tailwind v4

桥接把宿主的 token 当颜色读，这是 Tailwind v4 的 shadcn 主题的写法（`--primary: oklch(0.205 0 0)`）。Tailwind v3 的主题写的是 HSL 通道（`--primary: 222.2 47.4% 11.2%`），单独拿出来不是颜色：原样桥接过来，每一个都无效。v3 的应用不要引 `shadcn-bridge.css`，自己写同一条规则，把每个通道 token 包进 `hsl()`：

```css
:where(:root:not([data-fve-preset])) {
  --fvp-background: hsl(var(--background));
  --fvp-dark-background: hsl(var(--background));
  --fvp-foreground: hsl(var(--foreground));
  --fvp-dark-foreground: hsl(var(--foreground));
  --fvp-primary: hsl(var(--primary));
  --fvp-dark-primary: hsl(var(--primary));
  --fvp-radius: var(--radius);
}
```

桥接覆盖的每个 token 及它的暗色一半各写一行，照 `shadcn-bridge.css` 列的那些。Storybook 的回归用例（`ShadcnBridge.test.stories.tsx`）把补偿控制台的 shadcn 主题连同桥接挂到一个工作台上，量出两种明暗下的控件边与焦点。

## 对比度由覆盖的人负责

每一套内置预设在两种明暗下都守住这些线，面上画的每一对——字在它的底上、边在它背后的东西上——在 jsdom 里由包的测试、在真浏览器里由对比度矩阵量过。这些对是包里的一张表 `src/ui/theme/pairs.ts`，每个作为底的角色都在上面。

| 线                                                | 量什么                                                                                                                                                                               |
| ------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| 文字，≥4.5:1（`contrast` 为 7:1）                 | 每个前景色在它的底上：页面、卡片、弹层、表头带与合计带、选中行、隔行与悬停行、高亮项与已选项、当前视图、提示框、图表里弱一级的字；状态色作为文字，以及作为徽标的字落在它自己的淡底上 |
| 控件、焦点与状态标记，≥3:1（`contrast` 为 4.5:1） | `input` 与 `ring` 在控件所在的每一种底上，包括暗色控件自己的 `input/30` 底；`primary`、`rise`、`fall` 填充表示状态的标记时                                                           |
| 无                                                | `border` 与 `sidebar-border`（分隔线）、`radius`、`text-ui`                                                                                                                          |

设了一个颜色——token、角色、链接、品牌色的边界——它落到的每一种底上的那条线就归你负责。宿主最常丢的是 `--fve-ring` 与 `--fve-input`（或它们的 `--fve-dark-` 一半）：没勾的复选框只剩 `input` 那一圈边，获焦的控件靠 `ring` 那条边认出来。落在预设边界之内的品牌色、你没设的角色，都不欠你什么。

用 Storybook 的[对比度矩阵](/storybook/?path=/story/view-engine-能力-主题与预设--contrast)量自己的主题：把 `--fve-*` 或 `--fvp-*` 声明粘进输入框，它们与内置预设一起逐对量出。

## 检查一套主题

包带一个命令，给宿主的 CI 用：按内置预设要过的那些门检查你的主题，用的是包自己的测试用的同一张登记表、同一套算术。

```bash
pnpm exec wow-view-engine theme-check src/theme.css
pnpm exec wow-view-engine theme-check src/theme.css --preset azure --preset porcelain
```

它读你的样式表（给几个就按次序拼起来），报出问题与行列号：

- **登记表与分层**：登记表里没有的 `--fve-*`、`--fvp-*`（什么也不做）、写了或读了 `--_fve-*`（引擎自己的）、预设块之外的预设变量、预设块里的宿主变量、写在 `@layer` 里的预设（输给复位）、只给了一部分的图表八色或三档阴影、预设里的 `initial`；
- **点击目标的高度**：`--fve-control-height`、`--fve-control-height-sm`、`--fve-filter-height` 或 `--fve-sidebar-item-height`（或你的预设里对应的 `--fvp-*`）低于 24px——可按的东西最低就是这么高（WCAG 2.5.8）——报错，并给出要写的值；算不出的高度（`calc()`）报警告。样式表按你写的高度原样画，不给它们设下限；
- **Tailwind v3 的 HSL 通道**——写成或读到 `222.2 47.4% 11.2%` 的颜色，或桥接会读到的 v3 shadcn 主题——并给出要的 `hsl()` 写法；
- **你改了的品牌色边界**（`--fve-brand-*`，或你的预设里的 `--fvp-brand-*`）：越界或上下颠倒，以及一遍扫过整个 sRGB 的品牌色，因为边界是对任何颜色的承诺；
- **对比度**：`src/ui/theme/pairs.ts` 的每一对，两种明暗、每种涨跌约定，在你自己的每套预设上，以及你 `:root` 上的变量所落在的内置预设上（不用 `--preset` 点名就是全部）；给了品牌色时两种回到色域的方式都量；
- **图表八色**：带了自己的色板或传了 `--brand-chart` 时，过上面那三道门。

有错误退出码是 1，只有警告是 0；`--json` 把结果打成数据。它读 `dist/theme-tokens.json`、`dist/theme-source.css`（`styles.css` 里原样的 token 规则）与 `dist/themes.css`，在 Node 22.12 及以上运行，不需要页面。它看不见页面运行时做的事——脚本里设的变量、它不知道是你的选择器——所以浏览器里画出来的样子仍以 Storybook 的对比度矩阵为准。

## 图表颜色

图表的八个色位按两种明暗调过相邻色位（第八与第一也算相邻）之间的色觉缺陷间距，以及每个标记上标签墨色的可读性，不桥接。预设可以带自己的八色——两种明暗共十六个，全带或全不带——过同一套门。色位是序数，「第三个系列」，不是色相：写 `var(--chart-3)` 的图换预设会换色；要表达好坏、涨跌，改写状态色或 `--rise`／`--fall`。宿主仍可以设 `--fve-chart-1` … `--fve-chart-8` 及其 `--fve-dark-` 一半，这时就欠自己的色板这些测量。图表配置里保存的颜色由代码声明。

## 涨跌色

指标卡「较上一期」的变化与瀑布图的每一步，按宿主的涨跌色约定着色，即 `<html>` 上的 `data-fve-change-colors`：

| 取值                | 指标卡的变化                       | 瀑布图的升／降           |
| ------------------- | ---------------------------------- | ------------------------ |
| 不设，或 `semantic` | 按好坏（`success`／`destructive`） | `success`／`destructive` |
| `green-up`          | 按方向                             | `success`／`destructive` |
| `red-up`            | 按方向，红涨                       | `destructive`／`success` |

这由宿主按市场与读者决定——不随界面语言切换，预设不设它，也没有 prop（一页只读一个市场）。`--fve-rise`／`--fve-fall` 设的是颜色本身。方向从不只靠颜色：指标卡的变化带箭头与正负号，瀑布图的标签带符号。

## 密度

`<html>` 上的 `data-fve-density`——`compact`、`default` 或 `comfortable`——决定表格、视图列表与仪表盘面板排得多紧：表头行 32、40 或 44px，值两侧 6、8 或 12px，视图列表一项 24、28 或 32px，面板内边距 8、12 或 16px。控件、字号与仪表盘的 80px 行高都不变。面上的 `density` 钉住单个视图。两者都不设时，面按预设的推荐（`porcelain` 舒适）；`default` 画出的与以前一模一样。

### 单独改一个长度

这几个长度也各是一个宿主变量，给那些要一个三档都给不出的长度的宿主。写了哪个，哪个就压过档位——不论哪套预设、钉没钉住、`data-fve-density` 是什么——其余的仍由档位给：

```css
:root {
  --fve-table-header-height: 36px;
  --fve-table-cell-padding-inline: 10px;
}
```

五个是 `--fve-table-header-height`、`--fve-table-cell-padding-block` 与 `--fve-table-cell-padding-inline`（表头行，以及值上下、左右的留白），`--fve-sidebar-item-height`（视图列表的一项）和 `--fve-panel-padding`（仪表盘面板内容四周；上下最多 12px，一行 80px 高的格子才放得下它的数）。它们属于布局、不属于主题：预设不设它们，预设只推荐档位。样式表也不给它们设下限——视图列表的一项是按钮，`--fve-sidebar-item-height` 请保持 24px 以上（WCAG 2.5.8），低于它时 `theme-check` 报错。

## 全部变量

`/ui` 导出 `FveToken`，即下表每个变量的类型——`--fve-<token>`，以及有暗色那一半时的 `--fve-dark-<token>`——供在代码里设变量的宿主使用。下表由包的主题登记表生成，`dist/theme-tokens.json` 是这张登记表的数据形式，宿主自己的工具可以读它。

<!-- theme-tokens:begin -->

| Token                           | 用途                                                                                                                                | 亮色默认值                             | 暗色默认值                        |
| ------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------- | --------------------------------- |
| `background`                    | 整体底色                                                                                                                            | `oklch(1 0 0deg)`                      | `oklch(0.145 0 0deg)`             |
| `foreground`                    | 默认文字                                                                                                                            | `oklch(0.145 0 0deg)`                  | `oklch(0.985 0 0deg)`             |
| `card`                          | 卡片与面板底色                                                                                                                      | `oklch(1 0 0deg)`                      | `oklch(0.205 0 0deg)`             |
| `card-foreground`               | 卡片上的文字                                                                                                                        | `oklch(0.145 0 0deg)`                  | `oklch(0.985 0 0deg)`             |
| `popover`                       | 弹层底色                                                                                                                            | `oklch(1 0 0deg)`                      | `oklch(0.205 0 0deg)`             |
| `popover-foreground`            | 弹层内文字                                                                                                                          | `oklch(0.145 0 0deg)`                  | `oklch(0.985 0 0deg)`             |
| `primary`                       | 主操作填充                                                                                                                          | `oklch(0.205 0 0deg)`                  | `oklch(0.922 0 0deg)`             |
| `primary-foreground`            | 主操作上的文字                                                                                                                      | `oklch(0.985 0 0deg)`                  | `oklch(0.205 0 0deg)`             |
| `secondary`                     | 次操作填充                                                                                                                          | `oklch(0.97 0 0deg)`                   | `oklch(0.269 0 0deg)`             |
| `secondary-foreground`          | 次操作上的文字                                                                                                                      | `oklch(0.205 0 0deg)`                  | `oklch(0.985 0 0deg)`             |
| `muted`                         | 弱化底色                                                                                                                            | `oklch(0.97 0 0deg)`                   | `oklch(0.269 0 0deg)`             |
| `muted-foreground`              | 次要文字                                                                                                                            | `oklch(0.556 0 0deg)`                  | `oklch(0.708 0 0deg)`             |
| `accent`                        | 悬停与选中填充                                                                                                                      | `oklch(0.97 0 0deg)`                   | `oklch(0.269 0 0deg)`             |
| `accent-foreground`             | 强调态上的文字                                                                                                                      | `oklch(0.205 0 0deg)`                  | `oklch(0.985 0 0deg)`             |
| `sidebar`                       | 导航列底色                                                                                                                          | `oklch(0.97 0 0deg)`                   | `oklch(0.205 0 0deg)`             |
| `sidebar-foreground`            | 导航列上的文字                                                                                                                      | `oklch(0.145 0 0deg)`                  | `oklch(0.985 0 0deg)`             |
| `sidebar-accent`                | 导航列的悬停项                                                                                                                      | `oklch(0.922 0 0deg)`                  | `oklch(0.279 0 0deg)`             |
| `sidebar-accent-foreground`     | 悬停项上的文字                                                                                                                      | `oklch(0.205 0 0deg)`                  | `oklch(0.985 0 0deg)`             |
| `sidebar-border`                | 导航列的边                                                                                                                          | `oklch(0.898 0 0deg)`                  | `oklch(1 0 0deg / 20%)`           |
| `destructive`                   | 危险与删除                                                                                                                          | `oklch(0.505 0.213 27.518deg)`         | `oklch(0.76 0.15 22.216deg)`      |
| `success`                       | 成功                                                                                                                                | `oklch(0.448 0.119 151.328deg)`        | `oklch(0.792 0.15 151.711deg)`    |
| `warning`                       | 需要注意、不阻塞                                                                                                                    | `oklch(0.473 0.137 46.201deg)`         | `oklch(0.828 0.15 84.429deg)`     |
| `border`                        | 边框与分隔线                                                                                                                        | `oklch(0.922 0 0deg)`                  | `oklch(1 0 0deg / 20%)`           |
| `input`                         | 输入与控件边框                                                                                                                      | `oklch(0.62 0 0deg)`                   | `oklch(1 0 0deg / 40%)`           |
| `ring`                          | 焦点环                                                                                                                              | `oklch(0.62 0 0deg)`                   | `oklch(0.66 0 0deg)`              |
| `destructive-foreground`        | 危险填充上的文字（推导）                                                                                                            | `background`                           | `background`                      |
| `quiet-foreground`              | 汇总行里弱的那一半（推导）                                                                                                          | `foreground` 的 70%                    | 同左                              |
| `pin-shadow`                    | 冻结列的柔边；归明暗，不归预设                                                                                                      | `oklch(0 0 0deg / 12%)`                | `oklch(1 0 0deg / 10%)`           |
| `chart-1`                       | 图表第 1 个色位：第 1 个系列                                                                                                        | `oklch(0.565 0.1626 255.532deg)`       | `oklch(0.6221 0.1612 255.053deg)` |
| `chart-2`                       | 图表第 2 个色位：第 2 个系列                                                                                                        | `oklch(0.6708 0.175 40.642deg)`        | `oklch(0.6221 0.1726 40.112deg)`  |
| `chart-3`                       | 图表第 3 个色位：第 3 个系列                                                                                                        | `oklch(0.669 0.1408 162.111deg)`       | `oklch(0.6212 0.1283 163.115deg)` |
| `chart-4`                       | 图表第 4 个色位：第 4 个系列                                                                                                        | `oklch(0.7644 0.1612 75.116deg)`       | `oklch(0.6699 0.1425 73.227deg)`  |
| `chart-5`                       | 图表第 5 个色位：第 5 个系列                                                                                                        | `oklch(0.7163 0.1412 357.389deg)`      | `oklch(0.6224 0.1712 0.838deg)`   |
| `chart-6`                       | 图表第 6 个色位：第 6 个系列                                                                                                        | `oklch(0.5285 0.1798 142.495deg)`      | `oklch(0.5285 0.1798 142.495deg)` |
| `chart-7`                       | 图表第 7 个色位：第 7 个系列                                                                                                        | `oklch(0.4331 0.1671 283.624deg)`      | `oklch(0.6696 0.1452 286.827deg)` |
| `chart-8`                       | 图表第 8 个色位：第 8 个系列                                                                                                        | `oklch(0.6226 0.1909 24.912deg)`       | `oklch(0.6693 0.1586 22.307deg)`  |
| `radius`                        | 圆角基准，其余档位由它换算                                                                                                          | `0.625rem`                             | —                                 |
| `text-ui`                       | 正文之下唯一的那一档字号                                                                                                            | `0.8125rem`                            | —                                 |
| `font-sans`                     | 字体，一条系统字体栈                                                                                                                | 不设：页面的                           | —                                 |
| `chart-patterns`                | 图表系列上的花纹：`on`、`off`，或不设／`auto` 跟随读者的「提高对比度」                                                              | 不设                                   | —                                 |
| `brand`                         | 品牌色，任何预设都接受：主色、`accent`、`sidebar-accent` 与选中行的淡色，以及预设给了边界时的焦点环都取它的色相，各按该预设的线收住 | 不设                                   | `brand`                           |
| `brand-l-min`                   | 主色亮度的下限：比它暗的品牌色被提到这里                                                                                            | `0.4`                                  | `0.68`                            |
| `brand-l-max`                   | 主色亮度的上限：比它亮的品牌色被压到这里                                                                                            | `0.5`                                  | `0.8`                             |
| `brand-c-max`                   | 主色与焦点环的彩度上限                                                                                                              | `0.37`                                 | `0.18`                            |
| `brand-ring-l-min`              | 焦点环亮度的下限；两个焦点边界都不设时焦点环不跟品牌色                                                                              | 不设                                   | 同左                              |
| `brand-ring-l-max`              | 焦点环亮度的上限                                                                                                                    | 不设                                   | 同左                              |
| `brand-accent-lc`               | `accent` 取品牌色相时的亮度与彩度（两个数）                                                                                         | `0.96 0.02`                            | `0.3 0.03`                        |
| `brand-sidebar-accent-lc`       | `sidebar-accent` 取品牌色相时的亮度与彩度                                                                                           | `0.92 0.03`                            | `0.3 0.03`                        |
| `brand-row-selected-lc`         | 选中行取品牌色相时的亮度与彩度                                                                                                      | `0.965 0.02`                           | `0.28 0.03`                       |
| `brand-chart-1-lc`              | 有 `data-fve-brand-chart` 时图表第 1 色的亮度与彩度——即预设自己第 1 色的                                                            | `0.565 0.1626`                         | `0.6221 0.1612`                   |
| `preset-density`                | 预设推荐的密度：`-1`、`0` 或 `1`（归预设；宿主用 `data-fve-density`）                                                               | 不设                                   | —                                 |
| `rise`                          | 上升，按方向                                                                                                                        | `success`（见[涨跌色](#涨跌色)）       | `success`                         |
| `fall`                          | 下降，按方向                                                                                                                        | `destructive`                          | `destructive`                     |
| `shadow-sm`                     | 低的一档浮起：浮起的卡片                                                                                                            | Tailwind 的 `shadow-sm`                | 同左                              |
| `shadow-md`                     | 中的一档浮起：弹层                                                                                                                  | Tailwind 的 `shadow-md`                | 同左                              |
| `shadow-lg`                     | 高的一档浮起：拖动中的面板                                                                                                          | Tailwind 的 `shadow-lg`                | 同左                              |
| `canvas`                        | 分组底：看板与宿主按卡片排的页面站在它上面（`fve:bg-canvas`）                                                                       | `background`                           | `background`                      |
| `content`                       | 行与结果写在上面的底                                                                                                                | `background`                           | `background`                      |
| `card-edge`                     | 卡片的一圈边：看板面板、记录卡片                                                                                                    | `foreground` 的 10%                    | 同左                              |
| `card-shadow`                   | 卡片离开底的浮起                                                                                                                    | `0 0 #0000`                            | `0 0 #0000`                       |
| `scrim`                         | 对话框与抽屉背后压暗页面的遮罩                                                                                                      | `oklch(0 0 0deg / 10%)`                | `oklch(0 0 0deg / 10%)`           |
| `table-header`                  | 表格的表头带                                                                                                                        | `muted`                                | `muted`                           |
| `table-header-foreground`       | 表头带上的文字                                                                                                                      | `foreground`                           | `foreground`                      |
| `table-header-weight`           | 表头的字重                                                                                                                          | `strong-weight`                        | —                                 |
| `table-header-divider`          | 表头列与列之间的分隔线（`transparent`：没有）                                                                                       | `transparent`                          | `transparent`                     |
| `totals`                        | 表格的合计带：汇总行、分析的合计行                                                                                                  | `muted`                                | `muted`                           |
| `row-selected`                  | 选中的行、按下的分组                                                                                                                | `muted`                                | `muted`                           |
| `row-selected-foreground`       | 选中行上的文字                                                                                                                      | `foreground`                           | `foreground`                      |
| `row-hover`                     | 悬停的行（推导）                                                                                                                    | `muted` 与 `background` 各半           | 同左                              |
| `row-stripe`                    | 隔行的底（关：行自己的底）                                                                                                          | `content`                              | `content`                         |
| `row-divider`                   | 表体两行之间的分隔线（`transparent`：没有，隔行已有条纹时）                                                                         | `border`                               | `border`                          |
| `table-sort-idle`               | 可排序列未排序时的标记在有指针的设备上显示多少（`0`：只在指针下或聚焦时出现）                                                       | `1`                                    | —                                 |
| `highlight`                     | 菜单、选择框、组合框里键盘或指针所在的那一项                                                                                        | `accent`                               | `accent`                          |
| `highlight-foreground`          | 那一项上的文字                                                                                                                      | `accent-foreground`                    | `accent-foreground`               |
| `highlight-link`                | `highlight` 取解析后的 `primary` 多少：`100%` 就是 `primary` 本身，随品牌色与明暗                                                   | 不设：不链接                           | —                                 |
| `highlight-foreground-link`     | `highlight-foreground` 取解析后的 `primary-foreground` 多少：`100%` 就是 `primary-foreground` 本身，随品牌色与明暗                  | 不设：不链接                           | —                                 |
| `item-selected`                 | 菜单、选择框、组合框里已选中的那一项                                                                                                | `transparent`                          | `transparent`                     |
| `item-selected-foreground`      | 那一项上的文字                                                                                                                      | `popover-foreground`                   | `popover-foreground`              |
| `item-selected-weight`          | 那些文字的字重                                                                                                                      | 不设：那一项原样                       | —                                 |
| `item-selected-link`            | `item-selected` 取解析后的 `row-selected` 多少：`100%` 就是 `row-selected` 本身，随品牌色与明暗                                     | 不设：不链接                           | —                                 |
| `nav-current`                   | 视图列表里正在看的那一个                                                                                                            | `background`                           | `background`                      |
| `nav-current-foreground`        | 它的文字                                                                                                                            | `foreground`                           | `foreground`                      |
| `nav-current-edge`              | 它的边                                                                                                                              | `border`                               | `border`                          |
| `nav-current-shadow`            | 它离开侧栏的浮起                                                                                                                    | `0 1px 2px 0 rgb(0 0 0 / 0.05)`        | `0 1px 2px 0 rgb(0 0 0 / 0.05)`   |
| `nav-current-link`              | `nav-current` 取解析后的 `row-selected` 多少：`100%` 就是 `row-selected` 本身，随品牌色与明暗                                       | 不设：不链接                           | —                                 |
| `nav-current-foreground-link`   | `nav-current-foreground` 取解析后的 `primary` 多少：`100%` 就是 `primary` 本身，随品牌色与明暗                                      | 不设：不链接                           | —                                 |
| `control-hover`                 | 指针下的按钮或切换                                                                                                                  | 不设：各控件原样                       | 不设：各控件原样                  |
| `control-pressed`               | 按下的切换                                                                                                                          | 不设：`muted`                          | 不设：`muted`                     |
| `outline-hover-edge`            | 指针下的描边按钮的边                                                                                                                | 不设：`border`                         | 不设：`input`                     |
| `outline-hover-foreground`      | 它的文字                                                                                                                            | `foreground`                           | `foreground`                      |
| `outline-hover-edge-link`       | `outline-hover-edge` 取解析后的 `primary` 多少：`100%` 就是 `primary` 本身，随品牌色与明暗                                          | 不设：不链接                           | —                                 |
| `outline-hover-foreground-link` | `outline-hover-foreground` 取解析后的 `primary` 多少：`100%` 就是 `primary` 本身，随品牌色与明暗                                    | 不设：不链接                           | —                                 |
| `focus-width`                   | 获得焦点的控件的轮廓宽度                                                                                                            | 不设：没有轮廓，用 registry 的边与光晕 | —                                 |
| `focus-offset`                  | 那道轮廓离控件边的距离                                                                                                              | `0px`                                  | —                                 |
| `focus-style`                   | 那道轮廓的样式：`solid`、`dashed`、`double`……                                                                                       | `solid`                                | —                                 |
| `focus-halo`                    | 获得焦点的控件周围的光晕（`transparent`：没有）                                                                                     | `ring` 的 50%                          | 同左                              |
| `control`                       | 以文字或图标自明的控件的静止填色：筛选条、分段控件、工具栏上的描边按钮                                                              | 不设：各控件原样                       | 不设：各控件原样                  |
| `control-edge`                  | 这类控件的边（装着输入框的筛选条仍用 `input`）                                                                                      | 不设：各控件原样                       | 不设：各控件原样                  |
| `control-thumb`                 | 分段控件按下的那一项，轨道上的滑块                                                                                                  | 不设：`muted`                          | 不设：`muted`                     |
| `control-thumb-shadow`          | 滑块离开轨道的浮起                                                                                                                  | `0 0 #0000`                            | `0 0 #0000`                       |
| `control-height`                | 控件的高度：按钮、输入框、选择框、筛选条里的控件                                                                                    | `2rem`                                 | —                                 |
| `control-height-sm`             | 小控件的高度：工具栏的按钮                                                                                                          | `1.75rem`                              | —                                 |
| `filter-height`                 | 看板筛选芯片的高度，里面的控件填满它                                                                                                | 不设：由控件与内边距撑开               | —                                 |
| `edge-width`                    | 控件边的宽度（分隔线仍是 1px）                                                                                                      | `1px`                                  | —                                 |
| `badge-edge`                    | 带色徽标的边取它的色调多少                                                                                                          | `30%`                                  | —                                 |
| `badge-fill`                    | 带色徽标的底取它的色调多少                                                                                                          | `10%`                                  | —                                 |
| `radius-card`                   | 卡片与对话框的圆角                                                                                                                  | `radius` × 1.4                         | —                                 |
| `radius-control`                | 控件的圆角（小控件取它的 0.8，最多 12px）                                                                                           | `radius`                               | —                                 |
| `radius-popover`                | 弹层的圆角                                                                                                                          | `radius`                               | —                                 |
| `radius-badge`                  | 徽标的圆角                                                                                                                          | `radius` × 2.6                         | —                                 |
| `radius-checkbox`               | 复选框的圆角                                                                                                                        | `4px`                                  | —                                 |
| `title-weight`                  | 视图、卡片与对话框标题的字重                                                                                                        | `500`                                  | —                                 |
| `strong-weight`                 | 比周围文字更重的那些的字重：表头、合计                                                                                              | `500`                                  | —                                 |
| `tooltip`                       | 提示框的底                                                                                                                          | `foreground`                           | `foreground`                      |
| `tooltip-foreground`            | 提示框里的文字                                                                                                                      | `background`                           | `background`                      |
| `chart-grid`                    | 图表的网格线与轴线                                                                                                                  | `border`                               | `border`                          |
| `chart-grid-width`              | 图表网格线的宽度                                                                                                                    | `1px`                                  | —                                 |
| `chart-axis`                    | 图表里弱一级的字：刻度、轴名、色阶两端、图形旁的名称                                                                                | `muted-foreground`                     | `muted-foreground`                |
| `chart-text-size`               | 图表文字的字号：刻度、轴名、名称                                                                                                    | `text-ui` − 1px（12px）                | —                                 |
| `chart-label-size`              | 标在图形上的数值的字号，比图表文字小一级                                                                                            | `text-ui` − 2px（11px）                | —                                 |
| `chart-line-width`              | 折线的宽度（算出的系列取它的 ¾）                                                                                                    | `2px`                                  | —                                 |
| `chart-area-opacity`            | 面积图线下填色的不透明度                                                                                                            | `0.2`                                  | —                                 |
| `chart-bar-radius`              | 柱末端的圆角（漏斗的级、热力图的格同样）                                                                                            | `radius` × 0.6，最多 2px               | —                                 |
| `chart-bar-min-width`           | 柱最窄画多宽                                                                                                                        | 不设：随绘图区                         | —                                 |
| `chart-bar-max-width`           | 柱最宽画多宽                                                                                                                        | `80px`                                 | —                                 |
| `chart-slice-border`            | 饼图扇区之间的缝，颜色取图表的底                                                                                                    | `1px`                                  | —                                 |
| `chart-map-edge`                | 地图的边界线，有数与没数的区域都描                                                                                                  | `chart-axis`                           | `chart-axis`                      |
| `chart-tooltip`                 | 图表提示框的底                                                                                                                      | `popover`                              | `popover`                         |
| `chart-tooltip-foreground`      | 图表提示框里的数                                                                                                                    | `popover-foreground`                   | `popover-foreground`              |
| `chart-tooltip-shadow`          | 图表提示框的浮起                                                                                                                    | `shadow-md`                            | `shadow-md`                       |

<!-- theme-tokens:end -->

字体归宿主：面上写的是 `font-family: var(--fve-font-sans, var(--fvp-font-sans))`——先宿主、后预设——不设时这条声明无效，`font-family` 照旧从页面继承。把 `--fve-font-sans` 设成一条系统字体栈，视图就用它；图表读计算出来的字体，跟着变。它没有暗色那一半。

五个 `sidebar*` 用的是 shadcn 自己的命名，指的是工作台放视图列表的那条导航列——已经在给 shadcn 侧栏配主题的宿主，用同一组词就能配这一条。只声明这条列真正画到的那五个。列里当前打开的那一项是 `background` 叠在 `sidebar` 上，悬停是 `sidebar-accent`，三者因此必须互相分得开：其中两个解析成同一档灰，这份列表就没有「你在这里」了。

`input` 与 `ring` 要守一条别的 token 不必守的线：控件的边与焦点标记按 WCAG 1.4.11 要与身后的颜色有 3:1，两个默认值在明暗两态都调到过线（Storybook 的对比度故事在浏览器里量）。它们刻意是独立的值。常见的 shadcn 品牌主题会把它们改指别处——`--ring: var(--primary)`、`--input: var(--border)`——这就把 3:1 交给了一个品牌色和一档分隔线灰，而它们都不欠这条线：未勾的复选框成了一根细线，获焦的行只剩一层淡色。设 `--fve-primary` 或 `--fve-border` 不会动到它们；设了 `--fve-ring`／`--fve-input`（或 `--fve-dark-` 那一半）的宿主，同样欠自己的主题这条 3:1，应当自己量。

有几个 token 是推导出来的：汇总行的弱字 `quiet-foreground` 是 `foreground` 的七成，`destructive-foreground` 是 `background`，宿主改了 `--fve-foreground` 或 `--fve-background`，它们跟着变。每一个仍能单独设（`--fve-quiet-foreground`、`--fve-destructive-foreground` 与各自的 `--fve-dark-` 那一半）。

图表用从这些 token 读回来的具体颜色、长度与数字画，而不是 `var()`，所以面或它任一祖先上的这些属性变了时，它要被告知重读：<!-- chart-attributes:begin -->`class`、`data-theme`、`data-fve-preset`、`data-fve-change-colors`、`data-fve-density`、`data-fve-brand-chart` 或 `style`<!-- chart-attributes:end -->。样式表推导出来的值（`color-mix()`、`oklch(from …)`、`calc()`）由浏览器先算好再交给图表。换主题请改这些属性之一；只换样式表而不动任何属性，图表会留在旧颜色上。

`radius` 与 `text-ui` 是暗色块不重新声明的两个 token——长度在明暗两态里是同一个长度——因此 `--fve-radius` 与 `--fve-text-ui` 对两态同时生效，也就没有对应的 `--fve-dark-` 那一半。`text-ui` 是正文之下唯一的那一档：分组标签、列头、徽章、分页与所有 `sm` 控件都用它，宿主改一处，这些一起动。

根默认涂 `--background`，因此嵌入在宿主卡片里的视图会露出自己的底色矩形——暗色下 `--card` 比 `--background` 亮一档，嵌入块读成卡片里一块更深的区域。让它涂所在之处的颜色：在那张卡片上把 `--fve-background` 与 `--fve-dark-background` 设为卡片色（变量会继承，卡片里的嵌入视图读到，别处不受影响）。不要设成 `transparent`：行、冻结列、悬停色与危险按钮上的字都用 `--background` 画，透明会让横向滚动的列从冻结列底下透出来，危险按钮的字也看不见。

弹层——菜单、下拉列表、Popover、Tooltip 与对话框——都 portal 到 `<body>`，画在 `z-index: 50` 这一层，压在周围页面之上。宿主自己的 chrome 堆得比它还高时，改一个变量即可把它们一起抬起来：

```css
:root {
  --fve-popup-z-index: 2000;
}
```

铺满屏幕的视图（工作台或嵌入上的「铺满屏幕」）钉在视口上、层级为 `0`：盖住页面的普通内容，宿主有意抬高的界面仍然盖在它上面。侧栏是 `position: fixed` 且层级更高的外壳（shadcn 的侧栏是 `z-index: 10`）会挡住视图最左边的几列，这样的宿主把铺满的视图抬到自己的界面之上、弹层之下：

```css
:root {
  --fve-expanded-z-index: 20;
}
```

它是十个宿主变量之一：它们是布局的长度与层级，不属于主题——没有预设设它们，也没有暗色那一半。前五个是[密度](#密度)的长度：缺省值由密度档位给，你写的值压过档位：

<!-- layout-variables:begin -->

| 变量                              | 用途                                                                    | 默认值                 |
| --------------------------------- | ----------------------------------------------------------------------- | ---------------------- |
| `--fve-table-header-height`       | 表格表头行的高度，压过密度                                              | 随密度：32 / 40 / 44px |
| `--fve-table-cell-padding-block`  | 表格单元格里值上下的留白，压过密度                                      | 随密度：4 / 8 / 10px   |
| `--fve-table-cell-padding-inline` | 表格单元格里值左右的留白，压过密度                                      | 随密度：6 / 8 / 12px   |
| `--fve-sidebar-item-height`       | 视图列表里一项的高度，压过密度；不要低于 24px（WCAG 2.5.8）             | 随密度：24 / 28 / 32px |
| `--fve-panel-padding`             | 仪表盘面板内容四周的留白，压过密度；上下最多 12px（仪表盘的 80px 行高） | 随密度：8 / 12 / 16px  |
| `--fve-expanded-z-index`          | 铺满屏幕的视图相对宿主页面所在的层级                                    | `0`                    |
| `--fve-popup-z-index`             | 每个 portal 出去的弹层所在的层级                                        | `50`                   |
| `--fve-record-table-max-h`        | 记录表格与分析表格的最大高度，超出即在表内滚动（`size="content"`）      | `70vh`                 |
| `--fve-record-text-max-w`         | `text` 单元格换行之前最多多宽                                           | `24rem`                |
| `--fve-workbench-min-height`      | 容器没有确定高度时，工作台的最低高度                                    | `36rem`                |

<!-- layout-variables:end -->

## 宿主自己的外壳

样式表的每一条规则都在构建时被收进样式边界，所以主题的 token，连 `grid`、`gap-4`、`bg-background` 这样的 utility，都只在边界里才画得出来。边界有两个，其中只有一个是 surface：

|                           | `.fve-root`                                       | `.fve-tokens`                                                    |
| ------------------------- | ------------------------------------------------- | ---------------------------------------------------------------- |
| 谁渲染                    | `ViewSurface`，以及各工作台与嵌入                 | 你自己的 DOM                                                     |
| token、utility、preflight | 有                                                | 有                                                               |
| 涂底色与文字色            | 涂                                                | **不涂**——想要本包那张底，自己写 `bg-background text-foreground` |
| 明暗                      | 祖先上的 `.dark`，或 `theme` 用 `data-theme` 钉住 | 只认祖先上的 `.dark`                                             |
| 措辞、语言、时区、tooltip | 有，走 `ViewSurface` 的 props                     | 没有                                                             |

**`fve-tokens` 许诺的是 token 与 utility（引擎的，带它的前缀写：`fve:flex`），不是组件。** 本包渲染所用的 shadcn 原语是 vendored 的，靠 `shadcn add --diff` 升级，不属于公开 API——所以请用你自己的组件、或你自己那份 shadcn/ui 搭 chrome，由这道边界把本主题的配色与间距交给它们：

<!-- typecheck-context
import { ViewEngine } from '@ahoo-wang/wow-view-engine';
declare const engine: ViewEngine;
import { EmbeddedView } from '@ahoo-wang/wow-view-engine/ui';
declare const id: string;
-->

```tsx
<div className="fve-tokens fve:flex fve:flex-col fve:gap-4">
  <header className="fve:flex fve:items-center fve:gap-2 fve:rounded-lg fve:border fve:bg-card fve:p-4 fve:text-card-foreground">
    ……你自己的页头，穿着本主题的 token……
  </header>
  <EmbeddedView engine={engine} instanceId={id} theme="light" />
</div>
```

`fve-tokens` 判断明暗只读一样东西：祖先上的 `.dark` class，和各个面读的是同一个——放在 `<html>` 上、放在应用外壳上都行，你的应用本来放在哪儿就放哪儿。它**不读**自己身上的 `data-theme`：钉模式是 surface 的事。它还会把凡是归某块面管的元素原样交还给那块面，所以上面那个钉成亮色的视图，在暗色页面里 token 与 utility 一路都是亮的。

preflight 同样在边界里生效：这片区域内你自己的标题、列表与按钮，会像在视图里一样被重置。这是换取这套 utility 的代价，也正是这个类该戴在用到它们的那块 chrome 上、而不是整页上的原因。

宿主自己的弹层离开外壳到了 `<body>`，它的 portal 也挂上这个类——`<Menu.Portal className="fve-tokens">`——就像引擎自己的弹层带着所在面的主题出去一样。

## 看一看

[主题一览](/storybook/?path=/docs/view-engine-能力-主题与预设--docs)每套预设一个故事，亮、暗各一条带：记录视图、分析图表，以及带筛选栏的仪表盘。旁边还有逐套预设的页面（整张报表嵌在宿主外壳里）、三套预设上的品牌色、对比度矩阵，以及宿主自己的主题。Storybook 工具栏上有「Preset」开关，明暗开关多了「system」，其余故事都跟着它们走。
