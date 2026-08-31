# Wow Compensation Dashboard

该 React 应用是 `wow-compensation-server` 的运营客户端：查询 `ExecutionFailed` 队列和历史，通过 Dashboard 查看补偿压力与趋势，修改恢复性/重试规格/目标函数，以及发起准备或强制准备。服务端状态机才是最终决策边界。

## 开发

从仓库根目录执行：

```shell
pnpm --dir compensation/dashboard install --frozen-lockfile

VITE_API_BASE_URL=http://127.0.0.1:18083/ \
pnpm --dir compensation/dashboard dev --host 127.0.0.1
```

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

[`src/generated/`](src/generated/) 是 Fetcher Generator 根据补偿服务 OpenAPI 产生的输出，不是手工维护源码：

1. 先在 `wow-compensation-api`/服务端修改公开合同并生成运行时 `/v3/api-docs`；
2. 确认 `package.json` 中的开发集群 OpenAPI 地址可访问；
3. 执行 `pnpm --dir compensation/dashboard generate`；
4. 审查生成 diff，再运行 build、Vitest 和 lint。

业务代码通过 [`src/services/`](src/services/) 包装生成的 command/query client；基地址和 CoSec 策略也在该层组装。不要为规避后端/OpenAPI 缺陷而手改 `src/generated/`。ESLint 和覆盖率统计均明确排除该目录。

指标口径、补偿状态、运营权限与部署要求见[补偿控制面](../../documentation/docs/zh/reference/example/compensation.md#补偿控制面)和[事件补偿指南](../../documentation/docs/zh/guide/event/compensation.md)。
