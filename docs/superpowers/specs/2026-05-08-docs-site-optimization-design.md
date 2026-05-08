# 文档站点优化设计

## 背景

Wow 框架文档站点基于 VitePress 1.6.x 构建，中英双语，约 45 个内容页面。
当前存在拼写错误、SEO 缺陷、视觉不一致、导航冗余等问题，需要全面优化。

## 第一层：基础设施修复

| 改动 | 说明 |
|------|------|
| R2DBC 拼写修正 | 文件 `r2bdc.md` → `r2dbc.md`，sidebar.en.ts / sidebar.zh.ts 同步修正 |
| 移除 no-cache meta | head.ts 中删除 `cache-control: no-cache`、`pragma: no-cache`、`expires: 0` 三个标签 |
| 清理 dist | 将 `.vitepress/dist/` 加入 `.gitignore`，从 git 跟踪中移除 |
| promql 警告修复 | 找到使用 promql 的页面，改为 yaml 或安装 shiki promql 语言包 |

## 第二层：SEO 与元数据

| 改动 | 说明 |
|------|------|
| 补充 frontmatter | 为所有 guide 页面补充 `title` 和 `description` 字段 |
| description 多语言 | EN 页面用英文 description，ZH 页面用中文 description |

frontmatter 模板：
```yaml
---
title: Command Gateway
description: 命令网关是系统中接收和发送命令的核心组件...
---
```

## 第三层：视觉体验优化

| 改动 | 说明 |
|------|------|
| 首页 features 图标 | emoji 替换为 VitePress 内置 SVG 图标 |
| 首页 hero 背景 | 紫蓝渐变换为品牌色（indigo）系渐变 |
| 内容区宽度优化 | `--vp-layout-max-width` 从 `100%` 调整为 `1440px` |
| 侧边栏宽度 | `--vp-sidebar-width` 从 `200px` 调整为 `272px` |

## 第四层：内容与导航优化

| 改动 | 说明 |
|------|------|
| 侧边栏分组折叠 | Extensions/Advanced/Reference 改为 `collapsed: true`，Guide 保持展开 |
| 导航栏精简 | "资源"下拉菜单从三级精简为二级 |
| R2dbc 侧边栏文案修正 | `R2bdc` → `R2dbc`（配合文件重命名） |
| reference 路径 sidebar 去重 | `/zh/reference/` 路径下 sidebar 与 guide 中 Reference 分组去重 |
