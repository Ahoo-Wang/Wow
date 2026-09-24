---
title: 视图引擎的主题
description: 尚未发布的 wow-view-engine 怎样穿上宿主的外观——预设、宿主变量、亮暗与跟随系统、钉住、嵌入与弹层、shadcn 桥接，以及覆盖变量要守的对比度。
---

# 视图引擎的主题

::: warning 尚未发布
`@ahoo-wang/wow-view-engine` 尚未发布到 npm，也不承诺兼容。本页描述的是仓库里当前的主题做法。
:::

主题是宿主的外观，不是观察方式：它不存进视图、仪表盘或个人偏好，工作台里也没有主题开关。预设与明暗都由宿主选，引擎跟随。下面的一切都是 CSS 自定义属性——没有主题对象，也没有 Provider。

## 三份样式表

| 入口 | 是什么 | 什么时候引 |
|---|---|---|
| `@ahoo-wang/wow-view-engine/styles.css` | 主题本身：每条规则都收在视图自己的边界里，每个颜色都是一个读宿主变量的 token | 总要引 |
| `@ahoo-wang/wow-view-engine/themes.css` | 内置预设，由 `data-fve-preset` 属性选中 | 想用预设 |
| `@ahoo-wang/wow-view-engine/shadcn-bridge.css` | 把宿主的 shadcn/ui token 读进视图的宿主变量 | 应用已经有一套 shadcn 主题 |

两个可选文件都只给 `--fve-*` 变量赋值：什么都不画，也碰不到宿主自己的任何变量。包在每次构建时核对这一点。

## 预设

```ts
import '@ahoo-wang/wow-view-engine/styles.css';
import '@ahoo-wang/wow-view-engine/themes.css';
```

```html
<html data-fve-preset="neutral">
```

`neutral` 就是主题本身的样子，也是不设预设时的缺省。`blue`（中性灰配蓝色主色）与 `slate`（冷灰配蓝）在随后的版本加入，每套都有亮暗两半。属性挂在 `<html>` 上，所有视图与弹层都换上这套预设。

- **预设与明暗互不相干。** 预设提供亮暗两半的值；亮还是暗仍按下文「亮、暗与跟随系统」决定。
- **预设从不改图表八色**、`pin-shadow` 与 `text-ui`，见[图表颜色](#图表颜色)。
- **自己的预设**照同样的写法定义、用同一个属性选中：`:where([data-fve-preset='acme']) { --fve-primary: …; --fve-dark-primary: …; }`。

## 宿主覆盖

每个 token 都读一个宿主变量，内置值作回退：亮色是 `--fve-<token>`，暗色是 `--fve-dark-<token>`。写在宿主自己的 `:root` 上：

```css
:root {
  --fve-primary: oklch(0.55 0.21 265deg);
  --fve-primary-foreground: oklch(0.99 0 0deg);
  --fve-dark-primary: oklch(0.75 0.15 265deg);
  --fve-dark-primary-foreground: oklch(0.21 0.05 265deg);
  --fve-radius: 0.375rem;
}
```

预设与桥接都写成 `:where(…)`，不占特异性，所以宿主在 `:root` 上设的变量总赢过它选的预设，与样式表的加载顺序无关。想改预设里的一个颜色，不必把其余的重写一遍。完整的 token 列表在[包的 README](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/README.zh-CN.md#定制主题)。

## 亮、暗与跟随系统

| 做法 | 效果 |
|---|---|
| 任一祖先（通常是 `<html>`）上挂 `.dark` class | 视图跟随页面的明暗 |
| 在 `ViewSurface`、工作台或嵌入组件上写 `theme="light"` 或 `theme="dark"` | 钉住这一个视图 |
| `theme="system"` | 跟随读者的 `prefers-color-scheme` 并实时切换，适合自己没有明暗开关的页面 |

## 钉住预设

在 `ViewSurface`、工作台或嵌入组件上写 `preset="blue"`，这个视图就钉在这套预设上，不管 `<html>` 上是什么。挂在其他祖先上的 `data-fve-preset` 也有效：面会找到最近的那一个。

```tsx
<EmbeddedView engine={engine} instanceId={id} theme="dark" preset="slate" />
```

## 嵌入与弹层

菜单、下拉、气泡、提示与对话框都 portal 到 `<body>`，不在视图所在的那部分页面里。它们带着面解析出的结果——明暗写成 `data-theme`，预设写成 `data-fve-preset`——所以钉住的嵌入，弹层也与它一致。

- **用属性切换，不要换样式表。** 图表从 token 读颜色，面或祖先上的 `class`、`data-theme`、`data-fve-preset`、`style` 属性一变就重读。只换了样式表、没有属性变化时，图表会留在旧颜色上。
- **设在某个元素上的变量到不了 `<body>`。** 嵌入外面那张卡片上设的 `--fve-*` 能传到嵌入，传不到它的弹层，因为弹层不在卡片里。整页的值写在 `:root` 上；只给一个视图的，用 `preset`。
- **卡片里的嵌入**画的是 `--background`。在卡片上把 `--fve-background` 与 `--fve-dark-background` 设成卡片的颜色，不要设成 `transparent`。
- **层级**：弹层在 `z-index: 50`；用 `:root` 上的 `--fve-popup-z-index` 一次抬高全部。

## shadcn 桥接

```ts
import '@ahoo-wang/wow-view-engine/styles.css';
import '@ahoo-wang/wow-view-engine/shadcn-bridge.css';
```

桥接把每个 `--fve-<token>` 与 `--fve-dark-<token>` 指向同名的 shadcn token——`background`、`foreground`、`card`、`popover`、`primary`、`secondary`、`muted`、`accent` 及它们的 `-foreground`、`border`、五个 `sidebar*` 与 `radius`。它在 `<html>` 上解析，所以取的是 `<html>` 当前模式下宿主的值。

| 不桥接 | 为什么 |
|---|---|
| `input`、`ring` | shadcn 主题常写 `--input: var(--border)`、`--ring: var(--primary)`：一条分隔线的灰和一个品牌色，都不欠控件边与焦点要的 3:1 |
| `destructive`、`success`、`warning` | 按两种明暗量到 4.5:1 的文字色；shadcn 没有 `success` 与 `warning` |
| 图表八色 | shadcn 的色板只有五色，常以红色打头；这八色按色觉缺陷间距量过 |
| `row-hover`、`quiet-foreground` | 由已桥接的 token 推导 |

已有 shadcn 主题的应用这样接入：

1. `:root` 与 `.dark` 两块保持原样，`.dark` 挂在 `<html>` 上。
2. 引 `styles.css` 与 `shadcn-bridge.css`。去掉 `<html>` 上的 `data-fve-preset`：桥接与挂在那里的预设分量相同，二者选一。
3. 让视图跟随页面的明暗。钉成相反模式的视图，亮暗两半读到的都是宿主当前的值。
4. 桥接之外还想改的，逐个变量覆盖，比如 `--fve-ring`，并量一量（见下）。
5. 检查自己的文字 token：它们原样桥接过来。宿主的 `--muted-foreground` 在 `--background` 上不到 4.5:1，视图里也就不到。

Storybook 的回归用例（`ShadcnBridge.test.stories.tsx`）把补偿控制台的 shadcn 主题连同桥接挂到一个工作台上，量出两种明暗下的控件边与焦点。

## 对比度由覆盖的人负责

每一套内置预设在两种明暗下都守住这些线，逐对 token 在真浏览器里量过：

| 线 | token |
|---|---|
| 文字，≥4.5:1 | 每个 `*-foreground` 在它的底上；`muted-foreground` 在 `background`、`card`、`popover` 上；`foreground` 在 `muted`、`row-hover` 上；`quiet-foreground`；`destructive`、`success`、`warning` 作为文字在 `background`、`card` 上 |
| 控件与焦点，≥3:1 | `input` 与 `ring` 在 `background`、`card`、`popover` 上，以及暗色控件自己的 `input/30` 底上 |
| 无 | `border` 与 `sidebar-border`（分隔线）、`radius`、`text-ui` |

设了 `--fve-ring` 或 `--fve-input`（或它们的 `--fve-dark-` 一半），3:1 就归你负责：没勾的复选框只剩 `input` 那一圈边，获焦的控件靠 `ring` 那条边认出来。只设 `--fve-primary` 或 `--fve-border` 不会动到它们。

用 Storybook 的[对比度矩阵](/storybook/?path=/story/view-engine-主题-预设--contrast)量自己的主题：把 `--fve-*` 声明粘进输入框，它们与内置预设一起逐对量出。

## 图表颜色

图表的八个色位在每套预设里都一样，也不桥接，所以同一个系列换了主题仍是同一种颜色。它们按两种明暗调过色位之间的色觉缺陷间距，以及每个标记上标签墨色的可读性。宿主仍可以设 `--fve-chart-1` … `--fve-chart-8` 及其 `--fve-dark-` 一半，这时就欠自己的色板这两项测量。图表配置里保存的颜色由代码声明，不随明暗与预设变化。

## 看一看

[主题一览](/storybook/?path=/docs/view-engine-主题-预设--docs)把每套预设在亮、暗、跟随系统下各画一遍：一块带筛选栏、记录表格面板与分析图表面板的仪表盘，同一份记录的卡片视图，以及导出对话框。Storybook 工具栏上有「Preset」开关，明暗开关多了「system」，其余故事都跟着它们走。
