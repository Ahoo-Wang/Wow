# Dashboard Design QA

## 比较输入

- Source visual：`docs/superpowers/specs/assets/2026-08-29-compensation-dashboard-target.png`，`1672 × 941`。
- Normalized source：`.superpowers/sdd/2026-08-29-compensation-dashboard-redesign/task-5-source-normalized.png`，`1280 × 720`。
- Final implementation：`.superpowers/sdd/2026-08-29-compensation-dashboard-redesign/task-5-browser-round-3.png`。
  - URL：`http://localhost:5173/`
  - viewport / 实际像素：`1280 × 720 CSS px` / `1280 × 720 px`
  - density：`devicePixelRatio = 1`
  - state：Dashboard 成功态，Date Picker 关闭。
- Full comparison：`.superpowers/sdd/2026-08-29-compensation-dashboard-redesign/task-5-comparison-round-3.png`。
- Focused comparison：`.superpowers/sdd/2026-08-29-compensation-dashboard-redesign/task-5-focused-comparison-round-3.png`。
- Loading skeleton：`.superpowers/sdd/2026-08-29-compensation-dashboard-redesign/task-5-dashboard-skeleton-later.png`。

用户浏览器批注覆盖 source visual 中冲突的表达：规范路由为根 `/`；Time range 是 Dashboard 内容级全局 Date Picker；压力表按内容自然撑开；底部 signals 增高；底部三个 signals 不重复显示日期范围。

## 自动化证据

- Vitest：`40 files / 194 tests` 通过。
- desktop/mobile `e2e/dashboard.spec.ts`：`16/16` 通过。
- Lint、TypeScript/Vite build、`git diff --check` 通过。
- 请求合同：初始 `7 Snapshot + 4 EventStream`；Today 后累计 `14 + 8`；Refresh 后累计 `21 + 12`；每批 `state.executeAt` 与 `createTime` 的 start/end 一致。
- 交互合同：手动不完整范围禁用 Apply；Cancel 不应用；完整范围 Apply 后更新；Today / Last 7 days / Last 30 days 立即应用并关闭。
- 真实 Calendar 回归：任意同日范围可以完成并 Apply；从完整范围重选时从新起点开始，不复用旧 endpoint。
- 键盘可访问性：真实 Calendar 按 ArrowRight 后，`document.activeElement` 从 20 日移动到 21 日，焦点状态与 DOM 焦点连续一致。
- 浏览器回归：Top 5 五行完整；signals 至少 `200px`；Retry 的 `1–2 retries`、`3–5 retries` 各由单个完整 `tspan` 渲染；成功态 `console.error = []`。

## in-app Browser Round 3

- Dashboard：`clientHeight=652`、`scrollHeight=652`、`clientWidth=1110`、`scrollWidth=1110`；文档和 Dashboard 均无横向或纵向滚动。
- Signals：高度 `208px`，三个区域全部可见；底部日期范围 caption 数量 `0`。
- Pressure：Top 5 共五条真实内容行，表格按内容自然撑开，无裁剪或内部纵向滚动。
- 状态：alerts `0`；console warning/error `[]`。
- 交互：Today 立即应用并关闭；手动选择 20–22 日后 Apply 成功；Cancel 不改变已应用范围；Last 7 days 恢复默认范围；range calendar、Apply、Cancel 均可达。
- Skeleton：网络节流后 app-shell/data 首次加载阶段显示 `role="status"`、可访问名称 `Loading dashboard`；恢复正常网络后成功态恢复。

## 视觉核对

- 字体排版：Geist 已加载；标题、正文、数字和图表标签层级清晰，Retry 标签保持单行。
- 间距布局：summary、Top 5、signals 节奏与 source visual 一致；`1280 × 720` 一屏完整；signals 高度未压回。
- 颜色 token：失败、准备、恢复性和趋势序列沿用既定 token，颜色之外同时保留数量、比例和可访问文本。
- Logo / icons：Wow、GitHub、Gitee、Lucide Date Picker/Refresh 图标尺寸和对齐通过。
- Copy：顶部 Date Picker 是唯一可见时间语义；无 `Now`、`Outcomes window`、duration presets 或底部重复日期说明。

## Findings 与修复轮次

- Round 1 P2：Dashboard 内部纵向溢出（`652/665`）。只把 `.dashboard-view` 垂直 padding 从 `14px` 收敛为 `12px`、section gap 从 `12px` 收敛为 `7px`；Round 3 为 `652/652`。
- Round 2 P2：Retry distribution 的 `1–2 retries`、`3–5 retries` 拆成两行。只把 Recharts `YAxis.width` 从 `72` 增至 `88`；Round 3 保持单行。
- Round 3：无可执行 P0 / P1 / P2。
- Review fix：补上 `resetOnSelect` 与 DayButton DOM ref；真实 Date Picker/Calendar 回归覆盖日期重选和方向键焦点。
- P3：无。

## 证据边界

- 已验证：本地单元/组件测试、类型构建、Lint、固定响应下的 desktop/mobile 浏览器行为，以及 in-app Browser 的最终视觉、交互、loading skeleton 和 console。
- 未验证：真实后端数据正确性、认证环境、部署环境和生产运行状态。

final result: passed
