# Dashboard 设计 QA

## 范围

- 默认路由 `/` 的运维仪表盘。
- 全局 Date Picker、刷新、加载骨架、区域错误和空状态。
- 桌面 Sidebar 的展开/折叠，以及移动端 Sheet 导航。
- Compensation outcomes、Current health 与 Top 5 failure pressure。

## 视觉与交互结论

- 页面由四个 shadcn Card 组成，保持状态、结果/健康、失败压力的阅读顺序。
- 桌面 Sidebar 宽度为 176px，折叠宽度为 56px；菜单图标为 20px，折叠态居中，菜单项间隔为 4px。
- 版本与提交信息保留在桌面顶部栏；移动端使用完整 Sheet 导航，选择路由或品牌入口后自动关闭。
- Date Picker 使用 Popover、Calendar 与 ToggleGroup；快捷范围和手动范围共享同一全局窗口。
- 1440×1024 与 1280×720 桌面视口无页面级横向溢出，Top 5 行全部可见。
- 900px 内容宽度下，压力表通过容器查询转换为卡片；390×844 移动端无横向滚动或区域重叠。
- Refresh 保持按钮文字和尺寸稳定，仅更新图标状态；刷新失败时保留最后成功数据。
- 图表同时提供可访问文本或隐藏数据表，不依赖颜色独立表达状态。

## 数据边界

- 仅使用现有 Snapshot 与 EventStream 聚合查询 API。
- 不推断 Success rate、环比或严重度等缺少现有数据支持的指标。
- Recoverability 与 Retry distribution 总数由可见桶计算。

## 验证

- `pnpm test`：41 个测试文件、204 个测试通过。
- `pnpm lint`：通过。
- `pnpm build`：通过。
- `pnpm exec playwright test`：23 个测试通过，5 个按项目或状态有意跳过。
- 浏览器检查覆盖桌面展开/折叠、移动导航、Date Picker、加载、错误、空状态和窄内容区重排。

final result: passed
