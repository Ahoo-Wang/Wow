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

Wow 客户端与视图引擎来自同仓的工作区包 `@ahoo-wang/wow-client`、`@ahoo-wang/wow-react`、`@ahoo-wang/wow-view-engine`（`workspace:*`），它们通过 `dist` 被引用，所以第二行先构建 Dashboard 依赖的工作区包；修改 `typescript/` 下的 SDK 后重新执行这一行，SDK 与视图引擎的改动在同一个 PR 里由 Dashboard 的构建与测试验证。`src/main.tsx` 引入视图引擎的 `styles.css` 与 `shadcn-bridge.css`，引擎视图穿控制台自己的 shadcn 主题。其余 Fetcher 包来自 npm。

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

## 失败执行（预览）

`/executions` 是视图引擎重构的预览页（[重构方案](docs/design/view-engine-rebuild.md)批 1），与旧队列并存，旧页面不动。页面是 `@ahoo-wang/wow-view-engine` 的 `DataWorkbench`，定义在 [`src/views/`](src/views/)：`execution_failed` 快照的字段与分组、与旧页面七个队列一一对应的系统视图（活动中、待重试、执行中、已到重试时间、不可重试、不可恢复、已成功）加「全部」、三张分析（按状态分布、活动失败按处理器、每日新增失败）。

依赖此刻的三个队列（待重试、执行中、已到重试时间，批 2）用服务端时钟的 `BEFORE_NOW`／`AFTER_NOW` 写条件：「此刻」由服务端每次查询时读自己的时钟，存下的视图不过期，也不取决于浏览器的钟；条件栏读作「重试超时 早于现在」。边界按命令侧：`now > timeoutAt` 才算超时，所以正好到期的那一毫秒仍在「执行中」。这两个运算符要 Wow 9.2.0 及以上的服务端，更早的服务端拒绝这三张视图的查询（其余视图不受影响）。

- **行动作与成批动作**（批 3）：每行有「准备」按钮与「⋯」菜单（强制准备、标记可恢复性）；勾选后工具栏左端出现「准备 N 条」「强制准备」「标记可恢复性」。可操作性与旧页面同一条规则（`compensationCapabilities.ts`，与命令侧的 `canRetry()`／`canForceRetry()` 一致）：未超时的已准备记录按不了，按钮的提示与菜单顶端写出原因。成批命令先确认（写明条数，并列出控制台已知不会发送的记录及原因）；命令经生成的 `ExecutionFailedCommandClient` 发出，等到快照写入再返回（`Command-Wait-Stage: SNAPSHOT`），由引擎的 `useBulkCommand` 每次 4 条并发地跑，工作台上方一行报告进度（可停）与结局，服务端拒绝的记录带着服务端的原因仍保持勾选，跑完刷新当前页。一行的命令也走同一个 `useBulkCommand`，结局报在同一行里。
- **详情抽屉**（批 4）：点一行（或键盘回车）从侧边打开这一条，引擎按定义的字段分组读全；控制台的节插在相关分组之后：「变更函数」表单在处理函数之后，「堆栈跟踪」在错误之后（行号、Java 高亮、自带滚动区、换行开关、一键复制；引擎的「错误」分组因此不再列这一字段），「应用重试规格」表单在重试之后，「执行历史」在最后——补偿事件流的系统视图以 `EmbeddedView` 嵌入，作用域是这一条的 ID，按版本降序、每页 10 条、每条事件流按事件类型读，明暗随抽屉。两份表单经生成的客户端发命令、等到快照写入再返回，成功后重读这一条，服务端拒绝时把原因写在表单下。抽屉头部是这一行的「准备」与「⋯」，与行上同一套。
- **打开的是哪一条在地址的 `id` 参数里**（`?id=`，旧页面的告警链接同样带它）：按行或关掉都改写地址；不在当前页上的也能打开，读取中、已不在、无权限、读取失败（可重试）四种状态由引擎说。整条读到之前只画执行历史，表单与堆栈等整条到了再画。
- **复制 ID** 是定义里 `copyable` 字段旁的按钮（ID、事件 ID、事件聚合 ID），纯 HTTP 部署没有剪贴板 API 时退回文档的 `copy` 命令。
- 打开的视图在地址的 `view` 参数里，视图可以当链接发出去；服务端的入口路由同样认 `/executions`。
- 条件、搜索、列、排序、分页、卡片、导出都是引擎的。个人视图存在**这台电脑的这个浏览器**里（`MemoryViewStore` 的快照写 `localStorage`，键 `wow-compensation-dashboard:views`），视图列表与保存对话框都这样说；共享视图等 Wow 存储后端（阶段 6）。
- 定义的显示名只有一种语言，所以中英各建一份定义，换语言时重建引擎（方案 G12）。
- 「搜索错误」是全文检索：Elasticsearch 快照存储可用；MongoDB 快照存储要在集合上建文本索引，否则服务端拒绝、页面显示原因并保留上次结果（方案 G15）。
- `src/main.tsx` 先引入 `index.css` 再引入引擎样式：两者都写 Tailwind 的 `utilities` 层，引擎的放在后面，它的响应式类才不被控制台的全局工具类盖掉（方案 G16）。

## 验证命令

| 目的                                          | 命令                                                                                         |
| --------------------------------------------- | -------------------------------------------------------------------------------------------- |
| 类型检查（应用、构建配置与 `e2e/`）与生产构建 | `pnpm --dir compensation/dashboard build`                                                    |
| 单次运行 Vitest                               | `pnpm --dir compensation/dashboard exec vitest run`                                          |
| 代码检查                                      | `pnpm --dir compensation/dashboard lint`                                                     |
| 覆盖率门禁                                    | `pnpm --dir compensation/dashboard coverage`                                                 |
| 构建后浏览器测试                              | `pnpm --dir compensation/dashboard test:browser`                                             |
| 对真实服务端的冒烟（可选）                    | `WOW_COMPENSATION_URL=http://127.0.0.1:18083 pnpm --dir compensation/dashboard test:browser` |
| 本地预览                                      | `pnpm --dir compensation/dashboard preview --host 127.0.0.1`                                 |

`pnpm --dir compensation/dashboard test` 直接调用 `vitest`，在交互终端中可能进入 watch；CI 和一次性验证使用表中的 `vitest run`。Playwright 会在 `127.0.0.1:4174` 运行已构建的 preview，首次使用前需确保 Chromium 已安装。

浏览器测试默认只跑打桩的一套（`e2e/*.spec.ts`，接口由 `page.route` 桩住）。设了 `WOW_COMPENSATION_URL` 时改为只跑 `e2e/real-server/`：不起 preview，直接打开那台服务端，它须从仓库根目录启动、提供上一步构建的 `dist/`（启动命令见 [RELEASING.md §C′](../../typescript/RELEASING.md) 第 3 步）。冒烟自己写入两条失败执行（处理器名带本次运行的标记），直接打开 `/executions`，断言真实的行渲染出来、按处理器加一个条件后只剩一行；再打开「待重试」与「已到重试时间」，断言服务端按自己的时钟接受 `BEFORE_NOW`／`AFTER_NOW`：新写入的两条在前者、不在后者（首次重试排在最小退避之后）；再按 `?id=` 打开第一条，断言抽屉画出它的处理器、重试规格表单、「没有堆栈」，且执行历史由服务端的事件流答出、第一条是「首次失败」；最后对第三条自己写入的记录按行上的「准备」，断言命令带 `Command-Wait-Stage: SNAPSHOT` 到达服务端、结局行报「1 项完成」、这一行读回「已准备」且准备按钮变为不可用；全程没有 4xx、5xx 与页面错误。它会写数据，只对测试环境跑；CI 不跑（要 JDK、Gradle 构建补偿服务端与 MongoDB，不适合放进 `dashboard-test.yml`）。

## 生成客户端边界

[`src/generated/`](src/generated/) 是 `wow-generator`（工作区包 `@ahoo-wang/wow-generator`）根据补偿服务 OpenAPI 产生的输出，连同生成清单 `.wow-generator.json` 逐字节提交，不是手工维护源码：

1. 先在 `wow-compensation-api`/服务端修改公开合同并生成运行时 `/v3/api-docs`；
2. 构建生成器：`pnpm --filter @ahoo-wang/wow-generator build`；
3. 开发集群可访问时执行 `pnpm --dir compensation/dashboard generate`（读取 `package.json` 中的集群 OpenAPI 地址）；否则按[补偿参考案例](../../documentation/docs/zh/reference/example/compensation.md#本地服务启动、健康与路由验证)在本机启动补偿服务，再执行 `pnpm --dir compensation/dashboard exec wow-generator generate -i http://127.0.0.1:18083/v3/api-docs -o src/generated`；
4. 审查生成 diff，再运行 build、Vitest 和 lint。

业务代码通过 [`src/services/`](src/services/) 包装生成的 command/query client；基地址和 CoSec 策略也在该层组装。不要为规避后端/OpenAPI 缺陷而手改 `src/generated/`。覆盖率统计排除该目录；ESLint 照常检查它，只关闭 `@typescript-eslint/no-explicit-any`，因为生成器把自由结构的 OpenAPI schema 和装饰器的 `attributes` 参数映射为 `any`。

指标口径、补偿状态、运营权限与部署要求见[补偿控制面](../../documentation/docs/zh/reference/example/compensation.md#补偿控制面)和[事件补偿指南](../../documentation/docs/zh/guide/event/compensation.md)。
