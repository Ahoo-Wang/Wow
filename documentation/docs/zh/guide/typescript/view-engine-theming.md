---
title: 视图引擎的主题
description: 尚未发布的 wow-view-engine 怎样穿上宿主的外观——三层变量、预设、作为输入的品牌色、引擎的角色与链接、图表的角色、亮暗与跟随系统、钉住、嵌入与弹层、shadcn 桥接，以及覆盖变量要守的对比度。
---

# 视图引擎的主题

::: warning 尚未发布
`@ahoo-wang/wow-view-engine` 尚未发布到 npm，也不承诺兼容。本页描述的是仓库里当前的主题做法。
:::

主题是宿主的外观，不是观察方式：它不存进视图、仪表盘或个人偏好，工作台里也没有主题开关。预设、品牌色与明暗都由宿主选，引擎跟随。下面的一切都是 CSS 自定义属性——没有主题对象，也没有 Provider。

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

`--_fve-*` 是引擎自己的名字，随时会变；登记表里没有的 `--fve-*`、`--fvp-*` 名字什么也不做。完整的列表是[包的 README](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/README.zh-CN.md#定制主题) 里的 token 表，由主题登记表生成；`dist/theme-tokens.json` 是同一张登记表的数据形式。

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
  --fve-primary: oklch(0.55 0.21 265deg);
  --fve-primary-foreground: oklch(0.99 0 0deg);
  --fve-dark-primary: oklch(0.75 0.15 265deg);
  --fve-dark-primary-foreground: oklch(0.21 0.05 265deg);
  --fve-radius: 0.375rem;
}
```

按[三层](#三层)的规则，它们赢过你选的预设，也赢过钉在面上的预设。只给某一块面的，把 `tokens` 传给 `ViewSurface`、工作台或嵌入组件，而不是写在包裹层上：弹层 portal 到 `<body>`，不在包裹层下，`tokens` 会写在面上以及它打开的每个弹层上。它的类型是 `/ui` 导出的 `FveToken`，即登记表里的每一个宿主变量。

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
| 表格         | `table-header`、`table-header-foreground`、`table-header-weight`、`table-header-divider`、`totals`、`row-selected`、`row-selected-foreground`、`row-hover`、`row-stripe`（不设就关）                                                                                                                                                                                                              |
| 状态         | `highlight`、`highlight-foreground`（菜单、选择框、组合框里键盘所在的那一项）；`item-selected`、`item-selected-foreground`、`item-selected-weight`（其中已选中的那一项）；`nav-current`、`nav-current-foreground`、`nav-current-edge`、`nav-current-shadow`（视图列表里正在看的那一个）；`control-hover`、`control-pressed`；`outline-hover-edge`、`outline-hover-foreground`（指针下的描边按钮） |
| 焦点         | `focus-width`、`focus-offset`、`focus-style`、`focus-halo`                                                                                                                                                                                                                                                                                                                                        |
| 控件         | `control`、`control-edge`、`control-thumb`、`control-thumb-shadow`、`control-height`、`control-height-sm`、`filter-height`（看板的筛选芯片）、`edge-width`、`badge-edge`、`badge-fill`                                                                                                                                                                                                            |
| 圆角         | `radius-card`、`radius-control`、`radius-popover`、`radius-badge`、`radius-checkbox`                                                                                                                                                                                                                                                                                                              |
| 字重与提示框 | `title-weight`、`strong-weight`、`tooltip`、`tooltip-foreground`                                                                                                                                                                                                                                                                                                                                  |
| 图表         | 见[图表的角色](#图表的角色)                                                                                                                                                                                                                                                                                                                                                                       |

- **角色要守的线**：控件在两档高度下都守 24px 的地板（WCAG 2.5.8），承诺 AAA 字的主题给焦点轮廓至少 2px（WCAG 2.4.13）。每个作为底的角色都与其余的底一起进对比度矩阵，所以把它与所落回的 token 分开的主题，量的是它真画出来的样子。
- **每个角色的用途与默认值**写在[包的 README](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/README.zh-CN.md#角色) 的 token 表里。

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

| 做法                                                                     | 效果                                                                     |
| ------------------------------------------------------------------------ | ------------------------------------------------------------------------ |
| 任一祖先（通常是 `<html>`）上挂 `.dark` class                            | 视图跟随页面的明暗                                                       |
| 在 `ViewSurface`、工作台或嵌入组件上写 `theme="light"` 或 `theme="dark"` | 钉住这一个视图                                                           |
| `theme="system"`                                                         | 跟随读者的 `prefers-color-scheme` 并实时切换，适合自己没有明暗开关的页面 |

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

给宿主的 CI，包带了一个命令，按同样的门检查一份样式表，用的是包自己的测试用的那张登记表、那套算术：

```bash
pnpm exec wow-view-engine theme-check src/theme.css
pnpm exec wow-view-engine theme-check src/theme.css --preset azure --json
```

它报出问题与行列号：登记表里没有的 `--fve-*`、`--fvp-*`，写了或读了 `--_fve-*`，写错了层的变量，写在 `@layer` 里的预设，只给了一部分的图表八色或阴影；该是颜色的地方写成了 Tailwind v3 的 HSL 通道，并给出要写的 `hsl()`；越界、上下颠倒或让某个品牌色不达标的品牌色边界（它扫过整个 sRGB）；两种明暗、每种涨跌约定下的每一对对比度，在你自己的每套预设上，以及你 `:root` 上的变量所落在的内置预设上（`--preset` 可以收窄）；带了自己的色板（或传了 `--brand-chart`）时图表八色的三道门。有错误退出码是 1，只有警告是 0。它看不见页面运行时做的事，所以浏览器里画出来的样子仍以对比度矩阵为准。细节见[包的 README](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/README.zh-CN.md#检查一套主题theme-check)。

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

五个是 `--fve-table-header-height`、`--fve-table-cell-padding-block` 与 `--fve-table-cell-padding-inline`（表头行，以及值上下、左右的留白），`--fve-sidebar-item-height`（视图列表的一项）和 `--fve-panel-padding`（仪表盘面板内容四周；上下最多 12px，一行 80px 高的格子才放得下它的数）。它们属于布局、不属于主题：预设不设它们，预设只推荐档位。样式表也不给它们设下限——视图列表的一项是按钮，`--fve-sidebar-item-height` 请保持 24px 以上（WCAG 2.5.8）。

## 看一看

[主题一览](/storybook/?path=/docs/view-engine-能力-主题与预设--docs)每套预设一个故事，亮、暗各一条带：记录视图、分析图表，以及带筛选栏的仪表盘。旁边还有逐套预设的页面（整张报表嵌在宿主外壳里）、三套预设上的品牌色、对比度矩阵，以及宿主自己的主题。Storybook 工具栏上有「Preset」开关，明暗开关多了「system」，其余故事都跟着它们走。
