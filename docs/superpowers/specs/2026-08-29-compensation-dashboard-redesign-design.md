# 补偿控制面 Dashboard 重设计

## 目标

将 `/` 重构为运维控制仪表盘，优先回答三个问题：当前是否失控、失败压力集中在哪里、补偿结果正在改善还是恶化。

## 约束

- 不新增后端 API，仅使用现有快照和事件流聚合查询。
- 不新增前端依赖，复用 React、shadcn/Base UI、Recharts、Lucide 与 Geist。
- 保留现有路由、深蓝导航、Logo、版本、提交与仓库入口。
- 时间范围作用于全部 Dashboard 查询，但控件属于 Dashboard 内容区，不进入 App 框架顶部。
- 不实现视觉稿中缺少现有数据支持的 Success rate、环比或严重度推断。
- 维持 Top 5 压力表按内容伸缩，不设置固定高度或内部纵向滚动。

## 页面结构

1. App 框架顶部只显示 `Dashboard`、版本、提交和仓库入口。
2. `Current compensation state` 内容标题行右侧承载全局 Date Picker、更新时间与 Refresh。
3. 标题下为三个等宽状态指标：Actionable now、Timed out、Unrecoverable。
4. 中部为分析主区域：左侧大面积 `Compensation outcomes`；右侧 `Current health`，纵向排列 Recoverability 与 Retry distribution。
5. 底部为 `Current failure pressure — Top 5 clusters` 表格。

## 交互

- Date Picker 继续支持 Today、Last 7 days、Last 30 days 和手动 Apply/Cancel。
- Date Picker 日历填满弹层宽度，不保留右侧空白，移动端不得越出视口。
- 刷新保留最后成功数据；不在区域中插入 `Refreshing…` 行。
- 刷新期间保持 Refresh 按钮尺寸与文字不变，仅旋转图标并设置 `aria-busy`、动态 accessible name。
- 区域错误仍在所属区域显示，避免一个查询失败阻塞其他区域。

## 响应式

- `>=1280px`：趋势与 Current health 左右分栏，压力表位于下方。
- `<1280px`：趋势、Current health、压力表依次纵向排列。
- `<=720px`：使用完整 Sheet 导航；压力表转换为标签化卡片，不产生页面级或区域级横向滚动。

## 无障碍

- 保留 skip link、语义 heading/section、表格名称、图表隐藏数据表和错误 alert。
- 交互文字不小于 14px；焦点状态继续使用现有 shadcn/Base UI 样式。
- 动画遵守既有 `prefers-reduced-motion` 规则。

## 验收

- Dashboard 阅读顺序为状态、趋势/健康、压力表。
- 快照与事件流继续共享同一时间窗口。
- 刷新期间主区域高度不变化，不出现可见 `Refreshing…`。
- Date Picker 的日历宽度填满弹层且桌面、移动端无溢出。
- 1280×720 桌面基准无页面级横向溢出，核心区域可见。
- Vitest、ESLint、TypeScript/Vite build、桌面与移动端 E2E 全部通过。
