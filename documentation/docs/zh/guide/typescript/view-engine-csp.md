---
title: 视图引擎的内容安全策略
description: "wow-view-engine 在严格的内容安全策略下运行：样式表是文件、打包进来的库加的样式带 nonce、PNG 导出要 blob: 图片，以及守着这条策略的测试。"
---

# 视图引擎的内容安全策略

本页回答：**页面开着严格的内容安全策略（CSP）时，视图引擎要放行什么？**

引擎可以在严格的内容安全策略下运行：`script-src 'self'`、`style-src 'self'`，既不开 `'unsafe-inline'`，也不开 `'unsafe-eval'`。引擎不执行字符串形式的代码，不写内联脚本；要放行的只有三件事，各有一个原因。

## 要放行的三件事

- **样式表是文件。** 从允许的来源加载 `styles.css`（用预设时还有 `themes.css` 或 `themes/<名>.css`），不要内联。引擎画出来的标记里没有 `style` 属性：内联样式都经 DOM 的 style 对象写入，策略不拦；图表提示框的色块是 SVG 的 `fill`。也不载入 `data:` 图片：搭看板时显示的格子画在页面里。
- **打包进来的库加的样式带上页面的 nonce。** 有三个库干活时会往 `<head>` 加一个 `<style>`：拖放库在拖动排序时加（拖动时的光标、不选中文字），栅格的拖动库在移动或缩放仪表盘面板时加（不选中文字），Base UI 在下拉选择的列表打开时加（出现滚动箭头时藏起滚动条）。按 Vite 的 `html.cspNonce` 的约定把本次响应的 nonce 写成 `<meta property="csp-nonce" nonce="…">`（写在 `content` 里也认），并在 `style-src` 里放行 `'nonce-…'`；引擎把它交给这三个库。没有这个 meta 时一切照样能用，只是这几条规则被拦掉，各报一次违规。
- **导出 PNG 要载入 `blob:` 图片。** 图的 SVG 从一个 `blob:` 地址作为图片载入、再画到画布上，所以 `img-src` 要包含 `blob:`。不放行时 PNG 做不出来，工具栏会说明。导出 SVG 不需要任何放行，两种导出都不执行代码、不写内联脚本。

## 策略

```text
Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self' 'nonce-<每次响应不同>'; img-src 'self' blob:
```

```html
<meta property="csp-nonce" nonce="<同一个 nonce>" />
```

nonce 每次响应都要新生成，由服务端（或渲染 HTML 的那一层）写进响应头与页面里的 meta；一个固定不变的 nonce 等于没有。

## 守着这条策略的测试

有两处测试正是按这条策略跑的，出现一次违规就失败：

- Storybook 里的 [`StrictCsp.test.stories.tsx`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/storybook/stories/view-engine/StrictCsp.test.stories.tsx) 走过记录工作台（拖一列、从下拉选一个汇总、拉宽一列、打开一条记录的详情）、每种图型连同它的提示框、SVG／PNG／CSV 三种导出，以及一块仪表盘的读、从图上筛选、搭（移动与缩放面板、拖动标签页、加一个分析）与保存；它和其他故事一起在 CI 里跑。策略由 [`strictCsp.ts`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/storybook/stories/view-engine/strictCsp.ts) 在每个故事开头装到页上。
- 补偿控制台按同一条策略跑端到端（[`e2e/csp.spec.ts`](https://github.com/Ahoo-Wang/Wow/blob/main/compensation/dashboard/e2e/csp.spec.ts)）。

## 下一步

| 接下来 | 阅读 |
|---|---|
| 样式表有哪些、各什么时候引 | [视图引擎的主题](./view-engine-theming.md) |
| [`ViewHost`](../../reference/typescript/wow-view-engine/host.md#api-ViewHost)、路由与嵌入 | [把引擎接进宿主](./view-engine-host.md) |
| 视图引擎是什么 | [视图引擎](./view-engine.md) |
