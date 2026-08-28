# 补偿控制面分析页设计

## 背景

`compensation/dashboard` 当前围绕 `ExecutionFailed` 的六类运营队列提供查询、详情检查和补偿操作，但没有面向值班人员的整体压力视图。运营人员需要先回答“现在有多少任务需要处理、压力集中在哪里、最近补偿结果如何”，再进入现有队列处理具体失败记录。

Wow 当前主线已经提供公共 `AggregationQuery`、Snapshot/EventStream WebFlux 聚合路由、MongoDB/Elasticsearch 聚合实现和 Fetcher 3.18.1 聚合客户端。本功能只消费这些既有能力，不新增后端 API、查询 AST 或专用分析端点。

开发环境核对得到两个不同状态：

- `POST /execution_failed/snapshot/aggregation` 已存在并成功执行；运行时 Snapshot Schema 也声明了本设计所需字段的 TERMS、NUMERIC 和 RANGE 能力。
- 当前开发环境部署的 `v8.15.0` 尚未暴露 `/execution_failed/event/aggregation`，请求返回 `404`。该路由已经在当前主线提交 `3cc7d0158` 中实现，因此开发环境需要升级到包含该提交的构建后，历史趋势才能进入验收。

开发环境升级是部署前置条件，不是本功能的后端代码变更。

## 证据状态

### VERIFIED

- 开发环境 OpenAPI 声明 `/execution_failed/snapshot/aggregation`，实际 COUNT 聚合请求成功。
- 开发环境 Snapshot Schema 声明本设计所需字段能力。
- 开发环境 OpenAPI 未声明 `/execution_failed/event/aggregation`，实际请求返回 `404`。
- 当前主线 `EventRouteContributor` 已声明 `event/aggregation`，Fetcher 3.18.1 的两个 Query Client 均提供 `aggregate()`。

### LOCAL VALIDATION

下列聚焦测试已在设计阶段通过：

```text
:wow-query:test
  SystemQuerySchemaSourceTest
  QuerySchemaResolverTest

:wow-mongo:test
  EventStreamFilterConverterTest
  MongoAggregationCompilerTest

:wow-webflux:test
  EventStreamAggregationHandlerFunctionTest
  SnapshotAggregationHandlerFunctionTest
```

同时已用 dashboard 当前安装的 Fetcher 3.18.1 构造 Snapshot Top 集群与 EventStream 日期趋势请求，确认生成的 AST、客户端方法和 `/execution_failed` basePath 一致。

### MISSING EVIDENCE

- 开发环境升级后 `/execution_failed/event/aggregation` 的真实成功响应。
- 四个补偿事件名称在开发环境真实数据上的字段能力、日期分桶和时区结果。
- 尚未实现的 dashboard 页面、依赖构建、单元测试、浏览器测试与可访问性结果。
- 任何生产发布、部署或运行证据。

## 目标

- 在现有 dashboard 侧栏增加 `Analytics` 页面。
- 用 Snapshot 聚合展示当前补偿压力、失败集群、可恢复性和重试次数分布。
- 用 EventStream 聚合展示近 24 小时、7 天或 30 天的补偿结果趋势。
- 默认突出当前处置压力，历史趋势作为辅助信息。
- 复用现有 Fetcher、RetryConditions、shadcn/ui、错误状态和取消请求模式。
- 保持局部失败隔离；某一分析区域失败不能阻断其他区域。
- 提供可访问、响应式且可测试的图表与文本结果。

## 非目标

- 不新增或修改后端 API、OpenAPI、Query Schema、`AggregationQuery` 或存储实现。
- 不修改 `src/generated/` 下的生成客户端和类型。
- 不实现通用 BI、自助查询构建器、任意字段选择器或保存视图。
- 不增加自动轮询、客户端持久化缓存或时间范围持久化。
- 不增加分析详情页、Top 集群“查看全部”跳转或新的失败队列。
- 不修改现有六类失败队列的过滤、刷新和操作行为。
- 不把不完整的事件序列绘制成部分趋势。

## 已验证能力

### 前端客户端

Fetcher 3.18.1 的 `SnapshotQueryClient` 与 `EventStreamQueryClient` 都提供：

```ts
aggregate<Row>(query, attributes?, abortController?): Promise<Row[]>
```

`executionFailedQueryClientFactory` 已包含正确的聚合名和客户端元数据。dashboard 继续把 `contextAlias` 覆盖为空字符串，因此目标路径分别为：

- `/execution_failed/snapshot/aggregation`
- `/execution_failed/event/aggregation`

### Snapshot 字段

开发环境 Snapshot Schema 已证明下列能力：

| 字段 | 使用方式 | 必需能力 |
| --- | --- | --- |
| `state.error.errorCode` | 集群维度 | `AGGREGATE_TERMS` |
| `state.function.contextName` | 函数身份维度 | `AGGREGATE_TERMS` |
| `state.function.processorName` | 函数身份维度 | `AGGREGATE_TERMS` |
| `state.function.name` | 函数身份维度 | `AGGREGATE_TERMS` |
| `state.function.functionKind` | 函数身份维度 | `AGGREGATE_TERMS` |
| `state.status` | 状态维度与过滤 | `AGGREGATE_TERMS`、`EXACT_MATCH` |
| `state.recoverable` | 可恢复性维度与过滤 | `AGGREGATE_TERMS`、`EXACT_MATCH` |
| `state.executeAt` | 最早执行时间 | `AGGREGATE_NUMERIC` |
| `state.retryState.nextRetryAt` | 最早下次重试时间 | `AGGREGATE_NUMERIC` |
| `state.retryState.timeoutAt` | 超时过滤 | `RANGE` |
| `state.retryState.retries` | 重试直方图 | `AGGREGATE_NUMERIC` |

### EventStream 字段

公共 EventStream Schema 声明：

- 根 `createTime` 是毫秒 Epoch，支持时间过滤和 DATE_HISTOGRAM；
- 根 `body` 是多值事件集合，支持 ELEMENT_MATCH；
- `body.name` 是字符串，支持事件名称精确匹配。

每个 `ExecutionFailed` 命令处理方法当前只返回一个领域事件；`DomainEventStream.createTime` 取流内首个事件的 `createTime`。因此当前补偿模型中，“包含目标事件名的事件流数量”等于该事件的发生次数。本设计把这一当前实现事实作为历史趋势的统计前提；若未来单条命令产生多个同名事件，趋势语义必须重新审查，不能继续把流计数直接标成事件次数。

## 页面结构

### 路由与导航

- 新增 `/analytics` 路由。
- 侧栏在现有队列之后增加 `Analytics` 项，使用 Lucide 图表图标。
- 应用默认入口仍跳转到 `/to-retry`，不改变现有用户路径。
- 页面标题沿用 App 顶栏的现有标题机制。

现有 `NavItems` 同时承担队列分类和路由生成，不能把没有 `FindCategory` 的 Analytics 强行塞入同一类型。保持现有队列 `NavItems` 不变，新增一个无 category 的 `AnalyticsNavItem`，再组合出只供 `App` 渲染侧栏的 `PrimaryNavItems`；`Routes.tsx` 为 Analytics 声明显式懒加载路由，六个队列仍沿用现有映射。这样不把 queue category 改成可空字段，也不增加通用路由抽象。

### 布局

页面按“摘要 -> 定位 -> 趋势”组织：

1. 顶部当前压力摘要；
2. 左侧主区域为失败压力 Top 10 表；
3. 右侧为可恢复性与重试次数环图；
4. 底部为补偿结果折线趋势。

宽屏保持主表加右栏的双列结构；窄屏改为纵向排列，表格允许水平滚动。页面使用现有白色内容面、浅灰背景、深蓝侧栏和紫色强调色，不引入新的视觉系统。

### 顶部摘要

摘要固定展示三个 Snapshot 指标：

| 指标 | 过滤语义 |
| --- | --- |
| Actionable now | 复用 `RetryConditions.nextRetryCondition(now)` |
| Timed out | `status = PREPARED && retryState.timeoutAt <= now` |
| Unrecoverable | 复用 `RetryConditions.unrecoverableCondition` |

三个指标分别执行无 group 的 COUNT 聚合。每次 Snapshot 刷新只捕获一次 `now`，所有时间敏感过滤共享该值，避免同一屏数据跨越不同判断时刻。

### 失败压力表

主表只统计 `FAILED` 和 `PREPARED` 当前快照。集群身份由以下字段共同组成，避免同名函数跨上下文或处理器发生碰撞：

- `state.error.errorCode`
- `state.function.contextName`
- `state.function.processorName`
- `state.function.name`
- `state.function.functionKind`

第一条查询按完整集群身份分组，指标为：

- `COUNT` -> `currentCount`
- `MIN(state.executeAt)` -> `oldestExecuteAt`
- `MIN(state.retryState.nextRetryAt)` -> `nextRetryAt`

结果按 `currentCount DESC` 排序并限制为 10 个集群。

第二条查询在同一个 active filter 上，再用 OR 精确限定第一条查询返回的 10 个集群，并增加 `state.status` 分组。前端把结果合并为每个集群的 FAILED/PREPARED 占比条。若第一条查询为空，不执行第二条查询。

表格列为错误码、完整函数身份、状态占比、当前数量、最早执行/下次重试时间。表格不渲染行跳转箭头或“查看全部”，避免暗示不存在的筛选导航。

### 可恢复性分布

在 active filter 上按 `state.recoverable` TERMS 分组并 COUNT，展示 `RECOVERABLE`、`UNKNOWN` 和 `UNRECOVERABLE`。环图旁同时展示分类名称、数量和比例，不依赖颜色表达含义。

### 重试次数分布

在 active filter 上对 `state.retryState.retries` 执行 interval 为 1 的 HISTOGRAM 与 COUNT，前端合并为：

- `0`
- `1–2`
- `3–5`
- `6+`

查询使用聚合最大 `limit = 10_000`。若返回行数正好达到 10,000，前端把该区域标记为“分布可能被截断”并不绘图，禁止静默展示不完整比例。当前业务的重试次数种类预计远低于该上限；如真实数据触达上限，再重新设计该指标，而不是新增专用后端 API 作为首期前置。

### 补偿结果趋势

历史趋势包含四条固定序列：

| 序列 | `body.name` |
| --- | --- |
| New failures | `execution_failed_created` |
| Prepared | `compensation_prepared` |
| Retried failed | `execution_failed_applied` |
| Succeeded | `execution_success_applied` |

每条序列独立执行一条 EventStream 聚合：

1. 根 filter 使用 `createTime >= start && createTime < end`；
2. 根 filter 使用 `ELEMENT_MATCH(body, name = eventName)`；
3. 按根 `createTime` 做 DATE_HISTOGRAM；
4. 使用 COUNT 指标 `streamCount`。

不展开 `body`，因为 Elements 会把后续 group 字段切换为事件相对作用域，无法再按根 `createTime` 分桶。四条查询共享相同的 start、end、分桶单位和浏览器 IANA 时区。

| 范围 | 分桶单位 | 预期桶数 |
| --- | --- | ---: |
| 24h | HOUR | 24 |
| 7d | DAY | 7 |
| 30d | DAY | 30 |

默认范围为 7d。范围只影响 EventStream 趋势，不改变 Snapshot 指标。后端只返回有数据的桶，前端用已经安装的 `dayjs` 按浏览器本地时间补齐空桶并写入 0；不得用固定 `24 * 60 * 60 * 1000` 递增本地日期，以免夏令时日期错位。

范围边界与桶对齐：

- `end` 是当前小时或当前日期桶之后的下一个桶起点，查询使用 `< end`；
- 24h 从 `end` 向前取 24 个小时桶；
- 7d/30d 从本地下一日零点向前取 7/30 个日历日桶；
- 四条序列复用同一组边界，前端补桶也复用这些边界。

因此页面始终得到固定 24、7 或 30 个桶；当前未结束的桶包含到刷新时刻为止的数据。夏令时切换按真实 Epoch 桶排序，本地标签允许出现跳过或重复小时，不伪造不存在的本地时刻。

## 图表实现

使用 shadcn/ui Chart 组件和 Recharts v3：

- 通过仓库现有 shadcn CLI 增加 `src/components/ui/chart.tsx`；
- 直接使用 Recharts 的 `PieChart`、`LineChart` 等组件；
- 使用 shadcn 的 `ChartContainer`、`ChartTooltipContent` 和 `ChartLegendContent` 统一尺寸、主题、Tooltip 与 Legend；
- 状态占比条继续使用普通 CSS，不把表格单元格变成嵌套图表。

每个图表通过 `ChartConfig` 显式声明业务标签与状态颜色，不改动全局 `--chart-*` token；失败、准备、再次失败和成功分别使用可区分的红、橙、紫、绿，并始终由文本图例补充语义。

依赖固定为 `recharts@3.10.1` 与匹配当前 React 的 `react-is@19.2.8`，并更新 `package.json` 与 `pnpm-lock.yaml`。若 supply-chain minimum-release-age 策略拒绝安装，等待策略窗口通过；不绕过策略，也不换用 canary 版本。

shadcn 组件是仓库内源码，不形成第二套图表抽象。生成后只保留 chart 所需文件和依赖，审查并拒绝 CLI 产生的无关样式或配置变化。

## 组件与文件边界

最小职责划分如下：

| 位置 | 职责 |
| --- | --- |
| `src/services/executionFailedQueryClient.ts` | 在现有 factory 上增加 Snapshot/EventStream `aggregate` 直通函数 |
| `src/features/Analytics/analyticsQueries.ts` | 构造固定聚合查询、定义结果行类型、归一化趋势与重试分布 |
| `src/features/Analytics/useSnapshotAnalytics.ts` | 执行和管理 7 个 Snapshot 请求、局部结果与最后成功数据 |
| `src/features/Analytics/useEventTrend.ts` | 执行和管理 4 个 EventStream 请求、范围与趋势数据 |
| `src/features/Analytics/AnalyticsCharts.tsx` | shadcn/Recharts 环图与折线图 |
| `src/features/Analytics/AnalyticsView.tsx` | 页面布局、摘要、表格、范围和刷新交互 |
| `src/routes/constants.tsx` / `Routes.tsx` | Analytics 导航与懒加载路由 |
| `src/components/ui/chart.tsx` | shadcn 官方 chart 本地组件 |

不新增 analytics client class、repository、factory、context provider 或通用 dashboard framework。查询与归一化保持纯函数，异步生命周期只存在于两个按事实源划分的 Hook。

## 数据加载与并发

### Snapshot

一次 Snapshot 刷新流程：

1. 捕获统一 `now`；
2. 并行启动三个摘要查询、Top 集群查询、可恢复性查询和重试直方图查询；
3. Top 集群返回后，仅在非空时启动状态占比查询；
4. 最多执行 7 个 Snapshot 聚合请求。

### EventStream

一次趋势刷新同时启动四个事件名称查询。任何一条失败都使整个趋势区域失败，不绘制三条或更少的曲线。

### 刷新规则

- 初次进入页面同时加载 Snapshot 与默认 7d EventStream 数据。
- 手动 `Refresh` 同时刷新两个事实源。
- 切换时间范围只取消并重新加载 EventStream 数据。
- 不自动轮询。现有队列的 30 秒刷新不扩展到 11 个聚合请求。
- 不把结果写入 localStorage、sessionStorage 或跨页面缓存。

两个 Hook 分别持有自己的 `AbortController`。同类刷新开始时先取消旧请求；组件卸载时取消全部请求。Abort 错误不进入可见错误状态。

## 加载与错误处理

页面划分为五个独立结果区域：摘要、压力表、可恢复性、重试次数、历史趋势。

- 初次无数据时使用现有 Skeleton。
- 刷新时保留该区域最后一次成功数据，并显示刷新中状态。
- 刷新失败时保留旧数据，显示内联警告和最后成功时间。
- 首次加载失败且没有旧数据时，显示区域级错误状态。
- 一个区域失败不隐藏其他成功区域。
- 手动 Refresh 是统一重试入口，不为每个查询增加独立按钮。
- 压力表的两条查询视为一个原子区域；状态占比失败时不展示半完成表。
- EventStream 四条查询视为一个原子区域；不得把缺失序列当成 0。

请求错误保留 Fetcher/后端提供的可操作消息，不把 Schema、能力或路由错误转换为空数组。页面级 ErrorBoundary 继续处理未预期的渲染错误。

## 可访问性

- Recharts 启用 `accessibilityLayer`。
- 所有图表都具有可读标题和描述。
- 环图旁保留可见的分类、数量和比例。
- 趋势图提供屏幕阅读器可访问的数据摘要表。
- 图例同时使用文本和颜色；颜色不作为唯一信息载体。
- 24h/7d/30d 使用可键盘操作且具有选中状态的按钮组。
- 加载状态使用 `role="status"`，错误使用 `role="alert"`。
- 图表容器声明稳定高度，避免 ResponsiveContainer 初次测量为 0。
- 窄屏按阅读顺序纵向排列，不通过仅视觉 CSS 排序改变语义顺序。

## 兼容性与安全边界

- 只新增 dashboard 路由和依赖，不改变既有 dashboard URL 或行为。
- 聚合调用继续经过现有 CoSec Fetcher 配置、HTTP guard、rewrite、QueryGateway 和 Schema 校验。
- dashboard 不增加 tenant/owner 选择器；作用域继续由当前认证、Header 和网关策略决定。
- 不把 API 路由存在当成授权证明。
- 不从错误堆栈、消息正文或其他高基数字段做聚合维度。
- Top 集群 limit、事件时间范围和既有查询 guard 共同约束聚合成本。

## 测试策略

### 纯函数与查询合同

- 三个摘要查询固定复用正确的 RetryConditions 和统一 `now`。
- Top 集群固定使用完整函数身份、active filter、三个指标、排序和 limit。
- 状态占比查询只包含 Top 集群且保留完整身份。
- 可恢复性与重试直方图使用正确 group、metric 和 alias。
- 四条 EventStream 查询固定事件名、根时间过滤、ELEMENT_MATCH、DATE_HISTOGRAM、时区和 limit。
- 24h/7d/30d 的单位、桶序、空桶补零和本地日期边界正确。
- 重试次数按 `0`、`1–2`、`3–5`、`6+` 合并；达到 10,000 行时拒绝绘图。

### Hook

- Snapshot 首批六个请求并行，状态占比只在 Top 集群返回后执行。
- EventStream 四个请求并行且全有或全无。
- 新刷新取消旧请求；卸载取消在途请求。
- Abort 不展示错误。
- 时间范围变化不触发 Snapshot 请求。
- 刷新中和刷新失败均保留最后成功数据。
- 一个区域失败不覆盖其他区域结果。

### 页面与路由

- Analytics 导航和路由可访问，默认入口保持 `/to-retry`。
- 加载、空数据、成功、首次失败和旧数据警告均可见。
- 手动 Refresh 和范围切换触发正确请求集合。
- 表格按完整函数身份展示，状态占比和时间格式正确。
- shadcn Chart 获得正确 `ChartConfig` 与数据；不测试 Recharts 内部绘制算法。
- 图表标题、Legend、文本值、按钮组选中状态和隐藏趋势摘要可被无障碍查询定位。
- 窄屏布局保持内容可达，表格可水平滚动。

### 浏览器测试

Playwright 拦截既有 Snapshot/EventStream aggregation 路由并返回固定动态行，验证：

- 页面能渲染摘要、Top 表、两个分布图和四线趋势；
- 切换范围只请求 EventStream aggregation；
- Refresh 同时请求两个事实源；
- 局部失败保留其他区域；
- 页面没有未处理控制台错误。

## 验证命令

```bash
pnpm --dir compensation/dashboard build
pnpm --dir compensation/dashboard exec vitest run
pnpm --dir compensation/dashboard lint
pnpm --dir compensation/dashboard coverage
pnpm --dir compensation/dashboard test:browser
git diff --check
```

依赖变更后同时审查 Vite 构建产物的 chunk 提示，确认只引入 shadcn Chart/Recharts 所需代码；不为单个页面引入第二套图表引擎。

## 开发环境验收

补偿控制面开发环境升级后执行以下只读验收：

1. `/v3/api-docs` 同时包含 `/execution_failed/snapshot/aggregation` 和 `/execution_failed/event/aggregation`；
2. Snapshot Schema 仍声明本设计字段的所需能力；
3. 三个摘要、Top 集群、可恢复性和重试分布查询成功；
4. 四个事件名称在 24h、7d、30d 与浏览器时区下成功分桶；
5. 空数据返回公共聚合合同允许的空组或单行汇总；
6. dashboard 切换时间范围时 Snapshot 指标保持不变；
7. 查询没有 `404`、Schema capability、字段路径或时区错误。

开发环境验收不是生产可用性证明。发布或部署到其他环境仍需单独授权和证据。

## 完成条件

- Analytics 页面按已确认布局提供五个独立分析区域。
- 只使用现有 Snapshot/EventStream aggregation API 和 Fetcher 聚合客户端。
- 后端、OpenAPI 和生成客户端无变更。
- shadcn Chart + Recharts 3.10.1 与 React 19 正常构建。
- Snapshot 与 EventStream 的统计单位、字段作用域和时间范围语义明确且通过测试。
- 切换时间范围只影响趋势，手动 Refresh 刷新全部。
- 请求取消、旧数据保留、局部错误和事件趋势全有或全无行为通过测试。
- 图表具备文本等价信息和键盘可访问能力。
- dashboard build、Vitest、lint、coverage、Playwright 与 diff 检查全部通过。
- 开发环境升级后完成两类真实聚合查询验收。
