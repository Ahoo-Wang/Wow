# Wow Compensation Dashboard

该 React 应用是 `wow-compensation-server` 的运营客户端：查询 `ExecutionFailed` 队列和历史，通过 Dashboard 查看补偿压力与趋势，修改恢复性/重试规格/目标函数，以及发起准备或强制准备。服务端状态机才是最终决策边界。

## 开发

从仓库根目录执行：

```shell
pnpm install --frozen-lockfile
pnpm --filter wow-compensation-dashboard^... build

VITE_API_BASE_URL=http://127.0.0.1:18083/ \
pnpm --dir compensation/dashboard dev --host 127.0.0.1
```

Wow 客户端来自同仓的工作区包 `@ahoo-wang/wow-client`、`@ahoo-wang/wow-react`（`workspace:*`），它们通过 `dist` 被引用，所以第二行先构建 Dashboard 依赖的工作区包；修改 `typescript/` 下的 SDK 后重新执行这一行，SDK 的改动在同一个 PR 里由 Dashboard 的构建与测试验证。其余 Fetcher 包来自 npm。

`VITE_API_BASE_URL` 是所有 Fetcher 请求的基地址。`.env.development` 默认指向开发集群服务；连接本地服务时必须像上面一样显式覆盖。本地补偿服务的安全启动命令见[补偿参考案例](../../documentation/docs/zh/reference/example/compensation.md#本地服务启动、健康与路由验证)。

## 仪表盘

`/` 是默认运营入口，使用现有
`/execution_failed/snapshot/aggregation` 与
`/execution_failed/event/aggregation` 展示当前补偿压力和历史结果；
`/dashboard` 与 `/analytics` 保留为到根入口的兼容跳转。

Dashboard 内容区的 `Time range` 默认为最近 7 个自然日，同时约束 Snapshot 的
`state.executeAt` 与 EventStream 的 `createTime`。完整选择日期范围并点击 Apply、使用
Today / Last 7 days / Last 30 days 快捷项，或点击 Refresh，都会重载两类聚合；
刷新期间保留最后一次成功数据，首次加载使用与最终布局一致的骨架屏。

## 队列与操作

打包服务器将 `/active` 映射到 Dashboard 首页，支持直接打开和刷新下钻链接。`/active` 展示全部活跃记录（`FAILED` / `PREPARED`）。Dashboard 的失败集群链接携带完整函数身份、错误码和所选执行时间范围，下钻后在列表上方显示，可单独清除。无效的集群链接会提示错误，不会退回无筛选查询。

筛选表单区分已应用条件和草稿；移除已应用字段立即更新查询，不会提交其他未保存草稿。“已到重试时间”（Due for retry，原 `/next-retry` 路由保留）表示已经到达自动调度时间的候选。

选中记录离开当前列表后，其详情仍随现有刷新周期更新；刷新失败保留最近成功内容并禁用修改，显示错误和重试入口。未超时的 `PREPARED` 禁止普通或强制准备；超时后重新计算可操作性，服务端保留最终校验。

## 验证命令

| 目的 | 命令 |
| --- | --- |
| 类型检查与生产构建 | `pnpm --dir compensation/dashboard build` |
| 单次运行 Vitest | `pnpm --dir compensation/dashboard exec vitest run` |
| 代码检查 | `pnpm --dir compensation/dashboard lint` |
| 覆盖率门禁 | `pnpm --dir compensation/dashboard coverage` |
| 构建后浏览器测试 | `pnpm --dir compensation/dashboard test:browser` |
| 本地预览 | `pnpm --dir compensation/dashboard preview --host 127.0.0.1` |

`pnpm --dir compensation/dashboard test` 直接调用 `vitest`，在交互终端中可能进入 watch；CI 和一次性验证使用表中的 `vitest run`。Playwright 会在 `127.0.0.1:4174` 运行已构建的 preview，首次使用前需确保 Chromium 已安装。

## 生成客户端边界

[`src/generated/`](src/generated/) 是 `wow-generator`（工作区包 `@ahoo-wang/wow-generator`）根据补偿服务 OpenAPI 产生的输出，连同生成清单 `.fetcher-generator.json` 逐字节提交，不是手工维护源码：

1. 先在 `wow-compensation-api`/服务端修改公开合同并生成运行时 `/v3/api-docs`；
2. 构建生成器：`pnpm --filter @ahoo-wang/wow-generator build`；
3. 开发集群可访问时执行 `pnpm --dir compensation/dashboard generate`（读取 `package.json` 中的集群 OpenAPI 地址）；否则按[补偿参考案例](../../documentation/docs/zh/reference/example/compensation.md#本地服务启动、健康与路由验证)在本机启动补偿服务，再执行 `pnpm --dir compensation/dashboard exec wow-generator generate -i http://127.0.0.1:18083/v3/api-docs -o src/generated`；
4. 审查生成 diff，再运行 build、Vitest 和 lint。

业务代码通过 [`src/services/`](src/services/) 包装生成的 command/query client；基地址和 CoSec 策略也在该层组装。不要为规避后端/OpenAPI 缺陷而手改 `src/generated/`。ESLint 和覆盖率统计均明确排除该目录。

指标口径、补偿状态、运营权限与部署要求见[补偿控制面](../../documentation/docs/zh/reference/example/compensation.md#补偿控制面)和[事件补偿指南](../../documentation/docs/zh/guide/event/compensation.md)。
