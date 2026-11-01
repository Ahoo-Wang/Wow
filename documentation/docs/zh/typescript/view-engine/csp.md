---
title: 内容安全策略
description: "严格的内容安全策略需要为视图引擎放行什么：作为文件的样式表、其依赖库添加的样式所需的 nonce、PNG 导出所需的 blob: 图片，以及守住这一点的测试。"
---

# 内容安全策略

视图引擎可以在严格的内容安全策略下运行：`script-src 'self'` 和 `style-src 'self'`，既没有 `'unsafe-inline'` 也没有 `'unsafe-eval'`。它不从字符串求值代码，也不写内联脚本。需要放行的有三样，各有原因。

## 放行什么 {#what-to-allow}

- **样式表是文件。** 从允许的来源提供 `styles.css`，使用预设时还有 `themes.css` 或 `themes/<name>.css`；不要内联它们。引擎绘制的东西在标记中都不带 `style` 属性：内联样式经过 DOM 的 style 对象设置，任何策略都不会拦截。也不会加载任何 `data:` 图片。
- **它的依赖库添加的样式带上页面的 nonce。** 它的三个依赖库在工作时会添加 `<style>` 元素：拖放库在拖动列表时、栅格库在移动或调整仪表盘面板时、Base UI 在打开下拉列表时。把响应的 nonce 发布为 `<meta property="csp-nonce" nonce="…">`（Vite 的 `html.cspNonce` 就是这样做的），并在 `style-src` 中放行它；引擎会把它交给这三个库（栅格的那条样式由引擎自己写）。没有它一切照常工作，只是那几条规则会被拒绝，每次都会报告一次违规。
- **PNG 导出会加载一个 `blob:` 图片。** 图表的 SVG 从一个 `blob:` URL 作为图片加载，再画到 canvas 上，所以 `img-src` 必须包含 `blob:`。没有它就生成不了 PNG，工具栏会说明这一点。SVG 导出不需要任何放行。

## 策略 {#the-policy}

```text
Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self' 'nonce-<new every response>'; img-src 'self' blob:
```

```html
<meta property="csp-nonce" nonce="<the same nonce>" />
```

nonce 每次响应都是新的，由渲染 HTML 的程序同时写进响应头和页面的 meta。永远不变的 nonce 就不是 nonce。

## 守住它的测试 {#the-tests-that-hold-it}

在 Storybook 中，[`StrictCsp.test.stories.tsx`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/storybook/stories/view-engine/StrictCsp.test.stories.tsx) 在页面上施加的正是这条策略，在走查以下内容时只要出现一次违规就失败：记录工作台（拖动列、从下拉中选择、加宽列、打开记录）、每种图表类型及其提示、SVG/PNG/CSV 导出，以及一个被读取、筛选、重新排布和保存的仪表盘。它与其他用例一起在 CI 中运行。
