# Fetcher View Engine

View Engine 承担数据视图能力的后续演进。`@ahoo-wang/fetcher-viewer` 已进入维护期（弃用），不再新增功能；新项目请使用本包。两者模型与 API 不同，迁移需要适配。

[任务指南](../../wiki/zh/guides/view-engine/index.md) · [API 参考](../../wiki/zh/reference/view-engine/index.md) · [共享可运行示例](../../wiki/zh/examples/view-engine.md)

独立的 `@ahoo-wang/fetcher-view-engine` 包，提供可独立使用的 Wow 过滤器编译与校验、完整 `FilterPanel`、结构化值编辑器和 shadcn/Base UI 控件。同时提供不依赖 React 的 ViewEngine 和完整 RecordView 页面，定义、实例与保存接口由宿主管理。记录表格/卡片、分析表格/图表与仪表盘共享同一引擎，三种视图均支持独立嵌入浏览。

## 模块职责

公开 `ViewEngine` 负责组合内部服务，应用通过公开命令和快照接入。核心运行时不导入 React、DOM 或表格组件库。

| 模块                                                       | 职责                                                                          |
| ---------------------------------------------------------- | ----------------------------------------------------------------------------- |
| `engine/SessionStore`、`sessionState`                      | 不可变快照、订阅、保存与编辑基线，集中推导 dirty/pending。                    |
| `EngineScope`、`InstanceWork`                              | 生命周期与导航版本、选择请求取消、写入/重载互斥，以及待核对的创建回包。       |
| `RecordEdits`                                              | 校验草稿与配置修改，统一决定何时查询记录或汇总。                              |
| `RecordQueries`、`RecordSummaries`                         | 独立的记录和聚合请求、取消、回包校验与失败恢复。                              |
| `ViewLoader`、`ViewReload`                                 | 定义/实例加载、导航与重载核对，保留本地编辑。                                 |
| `ViewPersistence`、`ViewManagement`、`instancePermissions` | 保存/另存、改名/删除/个人排序、权限和回包核对。                               |
| `record/validation`                                        | 定义、实例/列表和业务记录的输入边界。                                         |
| `filter`                                                   | 操作元数据、协议构造/编译、编辑器生命周期及面板/值组件。                      |
| `record/page`、`record/table`                              | 页面所有权/导航/操作，以及表格状态/表头/单元格/汇总组合；布局计算保持纯函数。 |

测试和 Storybook 交互按行为领域组织，覆盖受控编辑器生命周期、配置持久化与查询边界。

## 可运行的公开包示例

在仓库根目录使用已有工作区依赖运行：

```bash
pnpm --filter @ahoo-wang/fetcher-view-engine... build
node packages/view-engine/examples/core.mjs
node packages/view-engine/scripts/verify-package.mjs
pnpm exec vite packages/view-engine/examples/react --host 127.0.0.1 --port 4175
```

`examples/core.mjs` 演示无 React 的公开 API，包括未设置控件和自定义原始属性的 JSON 保存及新引擎恢复；`examples/react/sales-order/OrderWorkbench.tsx` 仅通过公开导入组合页面和五类扩展。打开 `http://127.0.0.1:4175`，或 Storybook 的 **View Engine → 开始体验 → 全链路体验 / 开发接入 → 最小接入**，体验操作、自定义过滤器/单元格、异常恢复与深色窄容器。

执行 `pnpm install` 后，`pnpm storybook` 和 `pnpm build-storybook` 会先显式构建 View Engine 及其工作区依赖，再启动或构建 Storybook。两者均使用包含 CSS 的公开 `dist` 入口；编辑包源码后重新运行命令即可重建，生产验收继续验证真实包产物。

`examples/react/FilterPersistenceExample.tsx` 保存选中状态 ID 和独立编辑的显示名称。打开 `http://127.0.0.1:4175/?example=persistence`，或 **View Engine → 专项场景 → 视图与运行时 → 配置与恢复 → 公共包 · 组件配置 JSON 保存与重新打开**：新增未设置控件后无需查询即可保存，并在新引擎中恢复；只改显示名称也能直接保存，有效状态值改变后同样可以在查询前保存。

`verify-package.mjs` 创建临时归档，检查 exports、CSS 与构建内容一致性，再针对解包后的产物运行并类型校验使用方代码，不执行安装或发布。示例使用严格的本地模拟服务；库级验证不替代宿主的真实鉴权、权限、持久化和后端查询验证。

### 库级交付验收

从仓库根目录执行 `pnpm verify:view-engine`（先完成工作区构建）。入口会校验公开包，使用隔离的 Storybook 开发服务验证本地/HTTP 服务恢复，再构建并启动新鲜的 Storybook 生产产物进行浏览器验收和性能测量，完成、失败或中断时清理自己启动的进程。它不使用你正在调试的 6006 服务。

```bash
pnpm build
pnpm exec playwright install chromium firefox webkit
VITEST_MAX_WORKERS=4 pnpm test:unit
pnpm lint:view-engine
pnpm test:storybook
VIEW_ENGINE_BROWSERS=chromium,firefox,webkit VIEW_ENGINE_ARTIFACTS=/tmp/view-engine-acceptance pnpm verify:view-engine
```

默认使用 Playwright Chromium；`VIEW_ENGINE_BROWSER_CHANNEL=chrome` 可使用本机 Chrome。浏览器缓存目录不可写时，用 `PLAYWRIGHT_BROWSERS_PATH` 指向可写目录，并在安装和运行时使用同一值。`VIEW_ENGINE_BROWSERS` 仅控制新增的 UX/规模验收；服务恢复阶段使用 Chromium。CI 安装三个引擎并执行相同入口，失败时上传分阶段日志、测量 JSON 和截图。

验收负载为每页 100 行、30 个数据列、100 个筛选候选字段；覆盖 1440px/390px、浅色/深色、键盘查询与选择、错误重试及反复卸载。刷新、行选择和字段面板的暖交互分别记录 10 个样本，p95 上限为 1250ms，包含自动化通信和绘制等待，不是业务网络延迟 SLA。更大规模需要消费方独立测量；当前不承诺虚拟滚动或任意数据量。

可访问性保留原始 axe 结果。WebKit 对 Base UI 隐藏焦点哨兵的命名告警按[上游已知行为](https://github.com/mui/base-ui/issues/5237)单独记录，并验证实际键盘进入、Tab 离开和 Escape 恢复；其他违规仍使验收失败。这不替代真实 VoiceOver/移动设备人工测试。

## 记录与分析的共享生命周期

```tsx
import type { ViewHost } from '@ahoo-wang/fetcher-view-engine';
import { useViewEngine, ViewPage } from '@ahoo-wang/fetcher-view-engine/react';
import '@ahoo-wang/fetcher-view-engine/styles.css';

export function OrderPage({
  host,
  scopeKey,
}: {
  host: ViewHost;
  scopeKey: string;
}) {
  const binding = useViewEngine({ scopeKey, definitionId: 'orders', host });
  return <ViewPage {...binding} record={{ selectable: true }} />;
}
```

`useViewEngine(options)` 负责创建、加载和释放，包括 React StrictMode。必填的 `scopeKey` 与 `definitionId` 标识生命周期，任一变化都会替换引擎。可选的本地 `definition`/`instances`、`extensions` 中成对的编译器/编辑器注册、`limits`、`onDiagnostic` 用于初始化该生命周期。同范围的 host 更新保留编辑；其他初始化输入需要重新建立时，显式改变 React key。返回的 `ViewEngineBinding` 为 `{ engine: ViewEngine | null, extensions?, error? }`。

`ViewPage` 是纯 UI，接收 binding 或调用方持有的 engine，不会自行加载或释放引擎。`ViewPageContent` 要求非 null engine。两者组合导航、共享写入和选中的 `RecordView` 或 `AnalysisView`；后两者只渲染各自类型。无界面调用方创建 `new ViewEngine({ definitionId, host, definition?, instances?, filterCompilers?, analysisCompilers?, limits?, onDiagnostic? })`，调用 `load()`，范围结束时调用 `dispose()`。

### 嵌入浏览

React 入口的 `EmbeddedView` 将已保存的仪表盘、记录或分析视图嵌入首页或业务页面。`EmbeddedViewProps` 扩展 `ViewEngineBinding`，增加必填 `instanceId`，以及可选 `filterContext`、`className`、`onOpenView({ instanceId, definitionId }): void | Promise<void>`。提供回调后显示“打开完整视图”，导航由宿主负责。

组件读取已加载受管理实例的保存基线 `baseline`，创建自己的运行位置，挂载时查询，卸载或身份变化时释放该位置。它不会选中实例，也不会释放调用方的引擎。同一实例的多个嵌入拥有独立筛选、排序、分页和结果；完整视图尚未保存的编辑不会成为嵌入基线。

嵌入浏览隐藏管理、新建、保存、列配置和布局编辑，保留筛选、刷新、排序与记录分页。浏览变更仅保留在运行位置，维持 `dirty: false`，不会改变受管理实例或保存配置，有保存权限的用户也遵循相同语义。数据访问仍由宿主授权；隐藏编辑控件不是权限边界。

```tsx
import type { ViewHost, ViewInstance } from '@ahoo-wang/fetcher-view-engine';
import {
  EmbeddedView,
  useViewEngine,
} from '@ahoo-wang/fetcher-view-engine/react';

function BusinessDashboardPage({
  host,
  savedViews,
  scopeKey,
  onOpenView,
}: {
  host: ViewHost;
  savedViews: ViewInstance[];
  scopeKey: string;
  onOpenView(identity: { instanceId: string; definitionId: string }): void;
}) {
  const binding = useViewEngine({
    scopeKey,
    definitionId: 'orders',
    host,
    instances: { instances: savedViews, defaultInstanceId: null },
  });
  return (
    <EmbeddedView {...binding} instanceId="overview" onOpenView={onOpenView} />
  );
}
```

仅用于首页的引擎应传入 `instances.defaultInstanceId: null`，避免额外自动选中并查询工作台视图。`savedViews` 必须属于加载的定义并包含 `overview`；替换为任意已保存记录或分析 ID 即可复用同一组件。宿主需启用仪表盘格式支持。Storybook 入口为 **View Engine → 引擎与宿主 → 嵌入视图**：`view-engine-embedded-view--dashboard`、`--record`、`--analysis`、`--independent`。

### 定义与已保存实例

`ViewDefinition` 包含 `id`、`title`、`sourceId`、共享 `fields`，以及可选的 `timeZone`、`allowedOperators`、`filterEditors`。至少声明一种能力：

- `record: { rowKey, allowedLayouts, defaultPresentation?, recordActions? }`。`RecordViewDefinition` 将该能力标记为必需；rowKey 是自有属性路径，allowedLayouts 是非空且不重复的 table/card 列表。
- `analysis: AnalysisCapability` 授权 COUNT、字段分组、数值函数、时间粒度和上限。仅聚合的数据源不需要记录主键或分页方法。

`ViewInstance` 是 `RecordViewInstance | AnalysisViewInstance | DashboardViewInstance` 判别联合。两者都要求非空 `id`、`definitionId`、`title`、`revision` 和 `scope`。`kind: 'record'` 使用 `RecordViewConfig`（filters、sort、pagination、presentation）；`kind: 'analysis'` 使用下文的 `AnalysisViewConfig`。scope 支持个人或公共/系统/共享分类，不代表权限。创建输入只省略 ID 和 revision，由服务回执返回。

`ViewInstanceList` 包含可见实例与 `defaultInstanceId: string | null`，可以混合两类视图。默认偏好与当前选中项独立。结构可读取但当前不可执行的配置仍保留编辑入口，通过 `session.validation` 报错；单个失效实例不会阻断健康实例。

### 工作配置、已应用结果与保存

快照不可变。`ViewEngineState.version` 随发布递增，会话 `editVersion` 标识工作编辑版本。`RecordSession.queryAttempt` 捕获在途查询，`RecordSession.result` 将成功行绑定到当时的 config/filter/page/cursor 和 receivedAt；`AnalysisSession.pendingQuery` 捕获在途计划。展示来源和业务动作必须使用对应结果/请求快照，不能从工作编辑推断。`ViewSession` 按 kind 区分；访问记录或分析专属字段前先收窄类型。共享字段包括 baseline、当前工作 instance、dirty、validation、writeStatus、writeError、requiresReload，以及可选 conflict。

记录会话保留 filterDraft、filterBaseline、appliedFilter、filterPending、页码/游标、行、汇总与选择。编辑工作筛选不会改变已应用查询或记录。filterPending 表示工作筛选与已应用范围不同，本身不阻止保存。分析会话独立保留成功 result 及查询/schema 来源；后续编辑或执行失败不会把旧行标记为新结果。

`save(id?)` 校验并保存当前工作内容，不执行查询。无效原始输入阻止保存；有效但未查询的编辑可以保存。回执推进保存基线，同时保留提交后的编辑。`saveAs({ title, scope }, id?)` 返回 `Promise<string | undefined>`：身份已知时返回创建 ID，创建核对可以跨越原选中实例。运行时行、选择、错误和倒计时不会作为配置保存。

### 命令与结果归属

`engine.analysis(id)` 将 `edit(updater)`、`run()`、`refresh()`、`clearSort()`、`setFilterValidity(valid)`、`restore()` 绑定到指定分析实例。edit/clearSort/restore 不查询；run 编译并校验完整结果后才发布。`engine.record(id)` 绑定 `edit(updater)`、`refresh()`、`setPage(page)`、`setPageSize(size)`、`applyFilter()`、`restore()`。实例生命周期被替换后，旧命令失效。编辑回调必须纯净，不得重入引擎命令。

`engine.analysis(id).refresh()` 请求安全的自动刷新：仅当当前查询仍与成功结果一致、编辑输入有效、写入空闲且没有冲突或待重载状态时执行；不满足条件则跳过，不提交草稿。`run()` 仍用于显式执行或重试。`AnalysisSession.queryValid` 统一由查询编译、编辑有效性和资源限制推导；仅展示配置错误不会使查询失效，但仍阻止保存。

两类会话都暴露 `editorEpoch`。采纳已审阅的远端版本会推进该代次并丢弃本地编辑器缓冲；普通重载和还原保留已约定的非破坏性输入行为。自定义已挂载编辑器应在 `(instance.id, editorEpoch)` 变化时重新绑定命令并重置本地缓冲，内置视图已处理。旧有效性回调在重置后被忽略，旧分析编辑和记录草稿编辑会被拒绝，不能覆盖刚采纳的远端配置。已发布的普通会话和待核对另存会话走同一最终校验路径。记录准入始终检查分页/布局判别字段及嵌套展示结构；失效字段或能力引用仍作为可恢复的语义错误处理。取消分析刷新时保留已有结果的成功状态，后续自动刷新可以继续。

记录操作统一通过 `engine.record(id)`：`setFilterDraft(configuration, valid?)`、`setFilterValidity(valid)`、`setFilterMode(mode)`、`applyFilter()`、`setSort(sort)`、`setColumns(columns)`、`setLayout(layout)`、`setCardConfig(card)`、`setPage(index)`、`setPageSize(size)`、`nextPage()`、`setSelection(keys)`、`refresh({ background? }?)`、`retryQuery()`、`refreshSummary()`。门面不再提供直接记录命令。共享操作为 setTitle、save、saveAs、restore、reloadInstance、renameInstance、deleteInstance、setDefaultInstance、reorderInstances。记录还原会恢复基线并查询；分析还原只恢复工作配置，不运行。

### 冲突、未知写入与运行上限

真实分歧在 session.conflict 中保留旧基线、本地编辑及最新远端文档。普通保存不能把旧内容静默附加到新 revision。页面提供使用最新版本、另存配置，以及有权限时覆盖。`useRemoteInstance(review, id?)` 与 `overwriteInstance(review, id?)` 要求确切的已审阅冲突快照；后续本地编辑或远端版本变化使旧确认失效。覆盖仍使用审阅过的远端 revision 做 CAS，远端元数据和当前权限始终有效。

未知写入结果单独处理：请求发出后的超时、网络错误或 UNKNOWN_OUTCOME 保留原操作，通过 reloadInstance 核对。未知创建重用原 requestId 与提交体，换新 requestId 可能产生重复。未知删除保留原身份与 revision。默认偏好、删除回执和跨标签页事务仍由宿主负责。内置内存/浏览器宿主是参考适配器，不是生产授权边界。

limits 默认：加载 15,000 ms，查询/写入 30,000 ms，4 个并发查询，5 份保留结果集，配置 262,144 字节。结果回收不会清除工作草稿或恢复状态。晚到读取不能覆盖新请求或不同结果范围；取消不作为用户查询失败。可选 onDiagnostic 只接收操作身份、类型、阶段、耗时及可选错误码，不携带查询/行内容，回调异常被隔离。部署时仍需核验宿主/后端契约和浏览器流程，具备这些 API 不代表生产验收完成。

## 视图服务契约与运行时边界

验收链路为 **服务 JSON → ViewHost → 新建 ViewEngine → 前端注册表 → 组件和操作恢复**。`MemoryViewHost` 是可执行的服务替身，包外 HTTP 适配器用于验证暂定协议。服务负责定义、实例、可见性、版本、创建回执和用户排序；前端负责组件实现、回调、过滤器编译和业务查询客户端。业务数据写入不应改变视图配置。

### 本地服务替身

```tsx
import { IndexedDBViewHost } from '@ahoo-wang/fetcher-view-engine/react';

const host = new IndexedDBViewHost({
  serviceKey: 'development-tenant',
  scopeKey: 'alice',
  definition: orderDefinition,
  instances: orderViews,
  resolveSource,
});
```

`serviceKey` 标识服务／租户，`scopeKey` 标识其中的可信用户。存储键为 `fve:views:${JSON.stringify([serviceKey, definition.id])}`。公共视图在同一服务内共享，个人视图与展示顺序按用户隔离；归属由服务决定，不能通过写入正文伪造。useViewEngine 的 scopeKey 应包含租户与用户；不传本地 definition/instances，让加载完整经过宿主。

`IndexedDBViewHost` 将读取、授权、版本检查和写入放在同一个 IndexedDB 读写事务中提交。`MemoryViewHost` 使用原生 Map 保存进程内服务状态，需共享时显式传入同一个 Map；两个宿主只共享业务规则，浏览器持久化全部使用 IndexedDB。初始实例在初始化时取得服务端版本。系统视图只读。可选 `instancePermissions`、`canReorder`、`permissionsRevision` 提供可信权限策略，权限变化时必须递增策略版本；写入在事务内重新检查最新权限。

`permission.load(definitionId, signal?)` 读取权限快照，`permission.refresh()` 通过 `permission.subscribe` 通知引擎。引擎随宿主生命周期订阅和解绑，权限更新不会丢弃草稿，渲染时仍只进行同步权限读取。`await reset()` 是清除此服务／定义下全部用户及幂等回执的测试管理操作，不映射为 REST 端点。损坏存储会报错，不自动覆盖。原始存储文档是内部服务状态，不是 ViewInstanceList DTO。

### 仅用于开发的 HTTP 实验

HTTP 类和状态码映射位于 `dev/http`，没有公共导出，也不进入发布包。详见[开发实验](dev/README.zh-CN.md)。可复制的订单示例通过 `createViewHost` 回调注入宿主；HTTP 接线仅存在于 `dev/HttpOrderExample.tsx`。

`ViewHost.instance.create(input, {requestId, signal?})` 要求每个逻辑创建保留同一个请求 ID。同一用户、同一键和同一规范化正文重放已存回执；正文变化返回 CONFLICT。实例与回执在同一个事务内提交。引擎保留原 ID 与提交快照直到结果验证完成，权限拒绝的重试和完整加载都不会清除未确认请求。未知创建结果通过同一 create 请求重放确认，不按列表内容认领实例；明确返回的新实例 ID 可以直接读取核对。其他写入等待确认，原请求仍可重试，后续本地编辑保留。直接使用客户端的调用者在重试、重建客户端后也必须保留原 ID。测试服务回执保留到管理重置为止。

个人排序采用完整替换，同一用户最后一次成功替换生效；可见 ID 集合必须仍然匹配，不修改其他用户顺序。实例写入使用 revision CAS，两者是明确不同的并发语义。

### 可重复验证

```bash
pnpm --filter @ahoo-wang/fetcher-view-engine build
pnpm storybook
# 另一终端：
node packages/view-engine/scripts/verify-view-host.mjs
node packages/view-engine/scripts/verify-http-view-host.mjs
# 手动体验 Storybook 的 HTTP 示例：
node packages/view-engine/scripts/verify-http-view-host.mjs --serve
```

HTTP 脚本启动隔离的本地服务，以相同的 MemoryViewHost 逻辑、同步存储接口和服务端身份绑定处理请求，浏览器通过 HttpViewHost 驱动真实组件。覆盖共享／私有可见性、个人排序、响应丢失幂等、权限撤销／恢复、超时重试和引擎取消；另用两个标签页验证真实 IndexedDB 事务竞争写入及排队取消。HTTP 测试还覆盖权限响应乱序、无效会话、归属伪造与并发写入。编译开关两种模式的组件测试验证缺失／替换扩展及冲突恢复。测试服务不是已经接入生产数据库或生产认证的部署系统。

## 本页 / 所有汇总

计算、查询构建与结果解析函数接收独立的 `RecordSummaryMetric[]`，每项为 `{ id, field, function }`。使用 `getRecordSummaryMetrics(instance.config.presentation)` 将表格实例转换为指标，查询计算不再依赖列宽、固定位置等展示属性。`ViewInstanceMetadata` 描述通用实例元数据；`RecordViewConfig` 直接包含 `sort`、`pagination`、统一组件配置 `filters` 与 `presentation`，其中 `RecordTablePresentation` 描述表格布局和列。

只有明确声明为 `type: 'number'` 的字段支持汇总，字段列通过 `summary: ['SUM', 'AVG', 'MIN', 'MAX']` 多选合计、平均值、最小值和最大值；取消全部选择即不汇总，省略或空数组均表示关闭。列设置仅为数值字段提供汇总入口，不支持 COUNT 记录数汇总。`field.summaryFunctions` 可限制可用方式，`[]` 可关闭汇总；列汇总方式随实例保存。

表格底部同时显示“本页”和“所有”两行，范围标签在左侧各显示一次，列内按合计、平均值、最小值、最大值的固定顺序展示已选指标，标签左对齐、数字右对齐且保持单行。加载时记录区居中显示 Spin，本页与所有范围标签旁各显示一个 Spin；待加载指标保留空位，分页不重复显示加载文字。本页直接计算已加载记录，所有通过宿主 Wow 查询客户端的 `aggregate` 按已查询条件跨页统计，不拉取全部明细。未提交的筛选修改与行勾选不影响统计范围；所有汇总的加载或失败不影响本页数值和业务记录，并支持独立重试。修改汇总方式会同步更新两行，不重新查询列表。

每个失败范围仅在标签旁显示一个错误图标，点击展开原因；“所有”的详情中提供“重试汇总”，只重新请求聚合。本页错误仅展示本页原因，指标保留“—”占位，表格底部不再额外显示通栏错误提示。

数值字段可配置 `numberFormat`（`Intl.NumberFormatOptions` 加可选的 `locale`，默认 `zh-CN`）。金额使用 `{ style: 'currency', currency: 'CNY' }`，整数使用 `{ maximumFractionDigits: 0 }`。普通数字未配置精度时默认最多两位小数，其他样式遵循 Intl 默认值。默认数据单元格与汇总共用该配置，自定义单元格可调用 `formatRecordNumber(value, field)`；原始记录与汇总值保持不变。悬停或键盘聚焦汇总值可通过 Tooltip 查看完整原值。

数值汇总忽略空值和缺失值；空集结果为 null，显示“—”，与零区分。不使用 React 时，可独立调用 `calculateRecordSummary`、`createRecordSummaryQuery`、`readRecordSummaryResult`，或使用引擎 `refreshSummary` 及会话 `pageSummary` / `allSummary`。结果按列 ID 和函数索引，例如 `values.amount.SUM`；所有列合计最多 64 个指标。Storybook 提供多选汇总、失败重试和空集场景。

### 自动刷新与页面展开

顶部全局工具栏提供刷新组合按钮和页面展开按钮。自动刷新默认关闭，可选择 30 秒 / 1 分钟 / 5 分钟。
后台请求成功前保留现有记录，不重叠请求；待查询筛选、已选记录、写入操作、
错误、隐藏页面、正在编辑或使用弹层时暂停，游标查询第二页起也暂停。
所有汇总完成后才开始下一次后台读取；刷新失败保留记录，等待手动重试。
宿主业务操作期间可向 `ViewPage`、`ViewPageContent` 或 `RecordView` 传入
`record.autoRefreshPaused`（独立 `RecordView` 使用 `autoRefreshPaused`）。切换实例后自动刷新恢复关闭。

刷新按钮显示所选周期及 `分:秒` 倒计时（例如 `30 秒 · 00:29`），按实际截止时间
计算。暂停时显示“已暂停”，请求期间显示“刷新中”；恢复、切换周期或刷新完成后
重新开始完整周期。倒计时不会每秒触发读屏播报。

无 React 时可调用 `engine.record(id).refresh({background: true})`，通过
`session.refreshing` 观察后台读取状态。引擎负责查询、选择和写入保护，
React 控件额外管理定时器、页面可见性与编辑焦点。

展开占满当前页面，保留浏览器标签、筛选、勾选和分页。Esc 优先关闭当前弹层，
再次按下收起视图，也可点击工具栏收起；在 iframe 中展开范围为当前 frame。
这些显示偏好不随实例保存。Storybook 的“自动刷新与页面展开”演示两个控件。

## FilterPanel

```tsx
import { FilterOperator, type FilterExpression } from '@ahoo-wang/fetcher-wow';
import {
  createFilterConfiguration,
  newFilterNode,
  type FilterFieldDefinition,
} from '@ahoo-wang/fetcher-view-engine';
import { FilterPanel } from '@ahoo-wang/fetcher-view-engine/react';
import '@ahoo-wang/fetcher-view-engine/styles.css';

const fields: FilterFieldDefinition[] = [
  { field: 'amount', label: 'Amount', type: 'number' },
  { field: 'status', label: 'Status', type: 'string' },
];

export function OrderFilters({
  onQuery,
}: {
  onQuery: (expression: FilterExpression) => void;
}) {
  return (
    <FilterPanel
      fields={fields}
      defaultValue={createFilterConfiguration(
        newFilterNode(FilterOperator.MATCH_ALL),
      )}
      onApply={({ expression }) => onQuery(expression)}
    />
  );
}
```

简单模式在根级和每个 ELEMENT_MATCH 元素作用域内隐含 AND，支持嵌套数组；高级模式结构化编辑全部 50 种 Wow 操作以及 AND / OR / NOR / ELEMENT_MATCH。编辑、清空、撤销、模式切换都不请求服务；点击查询后通过 `onApply` 提交合法条件。宿主接收 `{configuration, expression}` 并管理请求，通过 `querying` / `queryError` 传回状态。`onPendingChange` 用于提示尚未应用的查询编辑；保存由配置有效性校验保护。

简单模式中每个作用域内的字段只保留一项，未设置值也占用该字段。元素条件显示“同一元素满足”和子筛选项，不显示逻辑组合选择器；空元素作用域可继续编辑，但添加子条件前不能查询。高级模式的 AND、OR、NOR 均允许同一字段多条规则，元素作用域内同样适用；编译与实例保存接受合法的重复字段条件。包含重复字段的草稿保持高级模式，删除多余条件且各作用域符合简单模式结构后才允许切回简单模式。

点击“添加筛选”打开锚定按钮的 Popover，按组展示 Checkbox，打开和关闭不改变表格、查询按钮的位置。浮层限制高度，字段区内部滚动；添加后保持打开，支持连续添加。“完成”或 Esc 关闭并返回触发按钮焦点，点击外部也可关闭。字段定义可通过 `group` 指定分组，按定义中的首次出现顺序展示。与有分组字段混用时，未分组字段显示在“其他字段”下；复选框与当前分组的草稿同步：勾选添加条件，取消勾选移除该字段的直接条件，未设置值仍显示为已勾选。高级模式在已选字段旁显示条件数量和“追加条件”加号，AND、OR、NOR 统一支持追加；高级模式在“添加筛选”旁提供图标下拉按钮，独立选择 AND/OR/NOR 并添加到当前分组，这三项不进入字段面板；未获定义允许的操作禁用。根级操作仍保留添加按钮。所有变更仍在点击“查询”后统一生效。

完全未设置的值保留控件但不产生谓词；部分填写、无效数据和未注册扩展阻止查询。所有字段在添加时绑定，保留所属分组及作用域。`extensions.filters` 提供本地自定义编辑器；`value` / `onChange` 可将配置交由宿主按实例保留；`defaultValue` 则让面板本地管理，两者互斥。`appliedValue` 提供已接受配置基线。各快照共用组件树，模式位于 configuration.mode。自定义组件通过 `onChange(props)` 发布可序列化属性；选中 ID、显示名称等需要保存的 UI 状态放在 props，需要跨卸载恢复的原始缓冲同样放在组件 props 中。

简单模式没有条件操作菜单或前后排序。高级模式支持新增、删除和编辑分组，不提供条件或分组的移动功能；内置标量筛选项移除“清空”和“特殊值”按钮；删除输入内容可保留未设置值，空值和空字符串使用对应操作符。编辑已有日期时间会保留夏令时重复小时的原偏移，跨季节日期仍使用目标日期的实际偏移。没有适用的偏移提示时，重复时刻统一选择较早的一次，不受系统时区影响；跳时期间不存在的本地时间仍判为无效。

过滤器组件定义将 `component`、纯函数 `compile(props, context)` 和可选的 `clear(props, context)` 一起注册到 `extensions.filters`。这是 `ViewPage` 唯一的筛选器注册入口；页面从同一份定义提供引擎所需能力，保持作用域内渲染与编译一致。`FilterCompiler` 是不依赖 React 的最小能力契约，仅在直接构造 `ViewEngine` 时通过 `filterCompilers` 使用，React 接入无需另行注册。兼容内置属性的编辑器可复用 `compileBuiltinFilter` 和 `clearBuiltinFilterProps`；完整组件仅在提供清空语义时获得 `onClear`。

自定义筛选器可使用任意 React 组件。注册 `render: 'value'`（默认）替换值区域，或用 `render: 'filter'` 与 `FilterComponentProps` 接管完整非容器 UI。面板继续负责字段与能力校验、错误展示，以及点击查询后统一生效。

组合布局可用 `FilterPanel.renderToolbar` 替换默认标题，接收 `FilterPanelToolbarProps`：`panelId`、`mode`、`options`、`pending`、`disabled`、`onModeChange`。使用面板提供的回调与禁用选项，含 OR/NOR、嵌套逻辑分组、作用域内重复字段或未完善的条件不能切回简单模式；符合规则的元素作用域可切换且保留原树结构。`collapsed` 仅隐藏内容并保留编辑器；`RecordView.toolbarStart` 可插入全局工具栏左侧内容，`ViewPage` 在此提供保存、标题与实例选择。

## 单个控件

```tsx
import { useState } from 'react';
import {
  FieldFilter,
  InputGroupInput,
} from '@ahoo-wang/fetcher-view-engine/react';
import '@ahoo-wang/fetcher-view-engine/styles.css';

export function AmountFilter() {
  const [operator, setOperator] = useState('GTE');
  return (
    <FieldFilter
      field={{ field: 'state.amount', label: '订单金额' }}
      operator={operator}
      operators={[
        { value: 'EQ', label: '等于' },
        { value: 'GTE', label: '大于等于' },
      ]}
      onOperatorChange={setOperator}
    >
      <InputGroupInput
        aria-label="订单金额值"
        type="number"
        defaultValue="1000"
      />
    </FieldFilter>
  );
}
```

字段名固定显示，值编辑器由 `children` 提供。操作选择只调用 `onOperatorChange`；输入校验、待查询缓冲、执行查询和保存视图由宿主管理。`FilterSelect` 也可用于受控枚举值、添加字段及逻辑组合方式。传入 `onClear={() => setValue(null)}` 即可提供“清空选择”；必选操作选择器不传此回调。

`FilterSearchSelect` 使用 Base UI Combobox 提供下拉内置搜索，沿用 `FilterSelect` 属性并增加 `searchPlaceholder` / `emptyText`。输入只筛选本地候选，选择和清空仅更新编辑缓冲。Storybook 的“自定义筛选器 · 内置搜索 Select”提供完整客户选择器注册示例。

## 入口与主题

- 核心入口导出字段与草稿契约、过滤器编译校验函数，不加载 React、DOM 或 CSS。
- `/react` 导出 `ViewTheme`、`ViewThemeStyle`、`FilterPanel`、`FilterValueEditor`、单个过滤器控件及组合组件。
- `/styles.css` 包含已编译的前缀组件样式与默认 Neutral 外观；`/themes/{neutral,blue,violet,green,orange,shadcn}.css` 提供可选主题。宿主无需安装 Tailwind；使用 `/react` 时需要 React 19。

导入主题只会让它可用，必须通过 `data-fve-theme` 或 `ViewTheme.theme` 选择；导入顺序不会选择主题。主题名是开放字符串，自定义 CSS 使用相同契约：

```tsx
import { ViewTheme } from '@ahoo-wang/fetcher-view-engine/react';
import '@ahoo-wang/fetcher-view-engine/styles.css';
import '@ahoo-wang/fetcher-view-engine/themes/blue.css';

<ViewTheme theme="blue" appearance="system" density="compact">
  <ViewPage {...props} />
</ViewTheme>;
```

`appearance` 可取 `light`、`dark` 或 `system`，`density` 可取 `comfortable` 或 `compact`；省略时继承。`ViewThemeStyle` 同时接受普通 React CSS 属性与有类型的 `--fve-*` 变量。CSS 是基础接口，任意自定义主题可使用 `.fve-root[data-fve-theme='brand']`。未知或未加载的主题名不会抛错，而是按 CSS 继承/默认值回退；发生回退不代表主题文件已经正确导入。

`--fve-font-size` 只使用 `px` 或 `rem`；`em` 与 `%` 会在语义字号中重复放大。其他公开尺寸变量可使用相对于有效字号的 `em`。未设置 `--fve-line-height` 时，根行高为 `1.5`，语义文字样式保留上游比例；显式设置后会覆盖这些比例。

`shadcn` 主题读取宿主语义变量中的完整 CSS 颜色，例如 `oklch(...)`、`hsl(...)` 或 `#hex`，不解析旧版裸 HSL 通道。缺失 token 使用库回退值；已存在但无效或循环的值遵循 CSS 的无效值行为。全局偏好、持久化和 `.dark` 仍由宿主负责；如果宿主只定义了深色 token，局部 light 标记无法还原浅色 token。

Select、下拉菜单与 Popover 面板默认 Portal 到 body，避免被有裁剪或滚动的祖先容器遮住。每次打开（包括受控 `open` 变化）时，将所属范围当前的主题变量、显式主题标记、颜色模式和字体传到弹层。深色变体使用原生 CSS 容器样式查询遵循最近的显式主题，支持深色页面内的浅色区域及浅色区域内的深色区域；同一元素上的 `data-theme` 优先于 `.dark`。需要支持容器样式查询的现代浏览器，不提供旧浏览器兼容层。打开期间会同步实际祖先的 class、data-theme 与行内样式变化，关闭后停止监听。宿主需要其他 Select 挂载位置时可显式指定 `SelectContent.container`。

变量主题会传入库自己的 Portal；`.brand [data-slot=...]` 一类结构选择器不会跨越 body Portal，任意 CSSOM 样式表替换若没有属性、class 或行内样式变化也不会被监听。第三方 Portal 必须采用其组件自己的主题容器机制。别名和派生值应定义在目标主题边界：CSS 自定义属性引用在继承前解析，不能假设子作用域修改基础变量后会重算继承的派生值。`--fve-primary` 与 `--fve-primary-foreground` 等配对颜色应一起修改。完整公开变量表与区域回调契约见 [API 参考](../../skills/fetcher-view-engine/references/api.md)。

`ViewPage`、`ViewPageContent` 通过 `record.renderToolbar` 和 `record.renderPagination` 接入区域回调；`RecordView` 直接接收 `renderToolbar` 和 `renderPagination`。每个回调收到只读状态、默认区域节点和绑定实例的受控操作。返回默认节点可保留它，包裹节点可组合 UI，返回 `null` 可隐藏区域。扩展需要 Hook 或局部状态时应返回一个组件。

`FilterDatePicker` 使用中文 shadcn Calendar，受控值为 `Date | undefined`。`FilterTimeInput` 组合文本输入与时、分、秒 Select，保留未完成输入，接受 `HH:mm` 或 `HH:mm:ss`，最多精确到秒；已有小数秒时间在显示或编辑时截到秒。两者均支持 `inline`，可放入 `FieldFilter`。时区转换和查询生效时机由宿主管理。未设置值合法：保留编辑器，点击查询时不生成需要值的对应谓词；没有剩余条件时使用 `filter.matchAll()`。日期和时间均为空才算未设置，只填一项时提示补全；时间下拉保留其他已填写片段。已填写但格式错误时仍提示错误；无需值的操作与显式 null / 零 / false 保留 Wow 语义。

## 开发

库构建通过 Vite 的 `reactCompilerPreset` 启用 React Compiler，使用 React 19 提供的 `react/compiler-runtime`，使用者无需安装编译插件。纯计算、表格 JSX 和操作回调交由编译器缓存；仅保留受控筛选草稿副本及弹层主题捕获两处缓存，以稳定 Effect 依赖。错误边界按渲染输入恢复，不依赖事件回调引用。权限与宿主能力通过 `getCapabilitiesSnapshot` 和 `subscribe` 订阅，无需组件使用 `use no memo`。自行管理引擎时，同一作用域下用 `updateHost(nextHost)` 更新回调或权限策略；用户、租户、访问范围改变时更换引擎。权限策略必须纯粹，修改闭包后需替换宿主或通过 permission.subscribe 通知引擎。操作执行时仍会重新检查权限。

`test` 在不启用编译器和启用编译器两种模式下运行同一套测试，再检查类型。Storybook 验证编译后的公开产物。打包检查要求 `/react` 包含编译器运行时导入，并禁止核心入口引入 React。单元格回归检查保证编译模式下输入待查询筛选不增加单元格渲染次数，这是回归约束，不代表所有场景都会变快。

```bash
pnpm --filter @ahoo-wang/fetcher-view-engine build
pnpm --filter @ahoo-wang/fetcher-view-engine test
pnpm --filter @ahoo-wang/fetcher-view-engine test:compiled
pnpm storybook
```

在 Storybook 中打开 **View Engine → 专项场景 → 查询与筛选 → 组合筛选**，体验业务筛选、嵌套元素条件、自定义编辑器校验、查询重试、深色主题与 50 种操作。**View Engine → 专项场景 → 组件与主题 → 日期时间** 提供单独日期时间控件。示例包括手动查询的字段组合、日期选择、秒精度时间、未完成输入、未设置值和深色主题；**View Engine → 专项场景 → 组件与主题 → Select** 演示选择、清空和重新选择；Controls 支持切换外观与禁用状态。组合示例使用浏览器本地时区生成毫秒时间戳的 Wow 表达式，不请求业务服务。这些示例消费包的公开构建产物，修改包源码后需重新构建。

详见 [API 参考](../../skills/fetcher-view-engine/references/api.md) 与 [第三方许可说明](THIRD_PARTY_NOTICES.md)。

### React 与编译器 lint

在仓库根目录运行 `pnpm lint:view-engine`，只读检查包内代码及 Storybook；仅检查本包可运行 `pnpm --filter @ahoo-wang/fetcher-view-engine lint:check`。原有包内 `lint` 命令会应用 ESLint 修复。

两处入口共享官方 `eslint-plugin-react-hooks` 稳定 recommended 规则，包含 Compiler 诊断。依赖数组、不兼容库、不支持的语法均按 error 处理，无效的禁用注释也会报错；无需另加旧的编译器 lint 插件。根目录 CI 的 `pnpm lint` 同时检查 `stories/view-engine`。显式解析器根目录避免从仓库、包目录或 worktree 执行时出现根目录推断歧义。

回归测试通过三种入口检查错误 Hook 和编译器用法，并确认合法的手动 memoization 可以通过。参见[官方规则说明](https://react.dev/reference/eslint-plugin-react-hooks)。

### 常用内置筛选组件

React 入口导出 `FilterMultiSelect`、`FilterRemoteSelect`、`FilterTextValues` 和 `FilterDateTimeRange`。字段可直接引用 `select`、`multi-select`、`remote-select`、`remote-multi-select`、`text-values`、`datetime-range`，无需重复注册组件。显式业务注册在渲染、编译和清空三处具有一致优先级；未知名称仍报错。

选择值支持字符串与有限数字，`1` 和 `'1'` 不混淆；候选可用 `group` 分组。单选对应 EQ/NE，多选及多值文本对应 IN/NOT_IN，日期/日期时间区间对应 BETWEEN。空选择不生成条件；不完整或逆序区间无效，时间戳遵循全局视图时区与 DST 规则。

`FilterDateTimeRange` 与默认日期/日期时间 BETWEEN 编辑器使用 Date Range Picker，默认只显示日期。双月日历在宽屏并排显示，窄屏上下排列并在浮层内滚动。datetime 字段按完整自然日查询，包含结束日期当天，并遵循夏令时的实际日长；组件属性保留选择的日期，查询边界只在编译时生成。

配置 `editor: { name: 'datetime-range', options: { showTime: true } }` 开启精确到秒的时间编辑。起止值合并在一个紧凑触发器中，弹层内一起编辑日期和时间，点击“确定”才回填，取消或 Escape 保留原条件。单值 datetime 编辑器同样支持 `editor: { name: 'builtin', options: { showTime: true } }`。时间模式接受 `HH:mm` 和 `HH:mm:ss`，保留有效偏移提示。已有小数秒字符串和数值时间戳在筛选时向下取整到所在秒，选择或确认后写入秒精度值；查询仍使用 epoch 毫秒单位。纯日期区间的上界仍为次日开始前 1 毫秒，包含最后一整秒。表格单元格数据和底层 Wow 协议值保留原有精度。

时区统一配置在 `ViewDefinition.timeZone`，所有筛选器、已应用摘要和日期时间单元格共用；未配置时使用本地运行时区。独立 `FilterPanel` 和直接使用的日期组件接受 `timeZone`。字段不再单独配置时区，相对时间筛选也使用全局设置。

```ts
// 全局视图设置；省略时使用本地时区。
const definition = { ...orderDefinition, timeZone: 'Asia/Shanghai' };
// 默认日期粒度：
const dateEditor = { name: 'datetime-range' };
// 显式开启日期 + 秒精度时间：
const dateTimeEditor = { name: 'datetime-range', options: { showTime: true } };
```

远程候选通过 `extensions.optionSources` 注入，不加入 ViewHost。数据源对象在会话内应保持稳定，范围变化时替换对象。useViewEngine 使用 `scopeKey` 隔离访问范围，独立 FilterPanel 在用户/租户变化时使用 React `key` 重挂载。

```tsx
import type { FilterOptionSource } from '@ahoo-wang/fetcher-view-engine';
import { useViewEngine, ViewPage } from '@ahoo-wang/fetcher-view-engine/react';

// sources.users 提供 search(query, signal) 和 resolve(ids, signal)。
// search 复用 Wow CursorPage，返回 { list, nextCursor }。
// resolve 返回 { list, missing }，明确交代每一个请求 ID。
const sources: Record<string, FilterOptionSource> = { users: userOptionSource };

function RemoteOptionsPage() {
  const binding = useViewEngine({
    definitionId: 'orders',
    scopeKey: 'tenant:user:access',
    host,
    extensions: { optionSources: sources },
  });
  return <ViewPage {...binding} />;
}
// 字段：editor: { name: 'remote-multi-select', options: { source: 'users', pageSize: 20, debounceMs: 300 } }
```

远程组件复用 `@ahoo-wang/fetcher-react/core` 的异步执行与防抖，分页复用 `CursorPage`。支持加载更多、取消、过期响应丢弃、分页去重，以及候选和标签的独立重试。输入法组合期间不搜索，搜索框 Enter 确认候选而不触发记录查询。

持久化属性为 `value` 或 `values` 加 `selectedOptions` 标签快照。自动标签回填只更新运行时显示，不修改属性、dirty 或记录查询。明确缺失的 ID 仍保留原标识；回填失败不代表选项已删除。清空移除选择值和标签快照，保留配置好的筛选节点。

多值文本按换行、中英文逗号和分号拆分、去除两端空白并去重，保留名称内部空格、大小写和前导零；Enter 先提交未完成条目，不承担 CSV 引号解析。区间保留 `lowerBound`、`upperBound` 组件属性；日期模式编译时包含最后一天全天，显式时间模式按精确时刻查询。

在 Storybook 的 **View Engine → 专项场景 → 查询与筛选 → 内置筛选器** 或独立示例 `?example=builtin-filters` 查看。`BuiltinFiltersExample.tsx` 通过 Fetcher 读取确定性 data URL 夹具，并以 IndexedDBViewHost 验证标签恢复与 JSON 持久化。只有该 data URL 夹具移除 URL 模板解析，真实 HTTP 客户端保留原有 URL 和鉴权拦截器。

`getFieldOperators(field)` 仅根据字段类型和显式 `field.operators` 推导能力。字段 `editor` 和定义 `filterEditors` 仅作为新建节点默认值；已有节点以自身 `component` 为准，由该组件注册检查兼容性。因此修改字段默认编辑器不会限制或替换已保存组件。

### 内置表格单元格

`/react` 导出 `TextCell`、`TagsCell`、`StatusCell`、`LinkCell`、`DateTimeCell` 和 `NumberCell`。可以独立使用，也可以在 `field.cellRenderer` / `column.renderer` 中引用内置名称，无需配置 `extensions.cells`。显式注册的自有业务组件优先；列配置优先于字段配置。

| 渲染器      | JSON options                           | 行为                                                     |
| ----------- | -------------------------------------- | -------------------------------------------------------- |
| `text`      | `ellipsis`、`copyable`，默认均为 false | 枚举名称、可聚焦完整内容提示、复制原始值                 |
| `tags`      | `maxVisible`，正整数，默认 2           | 标量/数组标签、按类型去重、键盘展开全部标签              |
| `status`    | `tones: [{ value, tone }]`             | 枚举名称与 neutral/success/warning/danger/info 语义色    |
| `link`      | `hrefField`、`newTab`，默认 false      | 地址来自当前值或记录的其他字段；文字仍来自绑定字段       |
| `date-time` | `locale`、`dateStyle`、`timeStyle`     | 沿用字段类型/时区；样式为 full/long/medium（默认）/short |
| `number`    | 无；使用 `field.numberFormat`          | 与汇总共用数字、金额、百分比格式                         |

```tsx
import { NumberCell, TextCell } from '@ahoo-wang/fetcher-view-engine/react';

<TextCell value="ORDER-0001" ellipsis copyable />;
<NumberCell
  value={0.125}
  format={{ style: 'percent', maximumFractionDigits: 1 }}
/>; // 12.5%
// 字段：{ field: 'amount', label: '金额', type: 'number',
//   numberFormat: { style: 'currency', currency: 'CNY' },
//   cellRenderer: { name: 'number' } }
```

空值显示 `—`，0 和 false 保留为有效值。NumberCell 接收有限数值，不解析已格式化的金额字符串。纯日期 `YYYY-MM-DD` 不发生时区平移，时间戳 0 有效；日期时间文本支持 YYYY-MM-DD，后接可选的 T/t 或空白分隔符和 HH:mm[:ss[.fraction]]，末尾可带 Z/z 或数字偏移；其他格式明确拒绝。不带偏移的本地日期时间使用全局视图时区与现有 DST 校验；小数秒超过三位时拒绝显示，避免回退到电脑时区。无效日期/数值显示占位。无效组件选项属于配置错误，由表格现有渲染边界隔离。

LinkCell 在 URL 解析后允许 HTTP(S)、mailto、tel 和相对地址，危险地址呈现为普通文本；新页链接固定带 `noopener noreferrer`。业务路由继续通过自定义组件处理。复制使用原始值，不能复制枚举名称或截断文字；剪贴板拒绝/不可用在按钮旁提示重试。TextCell 的 `text` 只改变展示。这些交互不会修改视图或触发查询。

状态主题变量为 `--fve-success`、`--fve-warning`、`--fve-info` 与已有 `--fve-destructive`；状态始终保留文字，不只依赖颜色。弹层继承当前主题。**View Engine → 专项场景 → 组件与主题 → 内置单元格** 与 `examples/react/BuiltinCellsExample.tsx` 包含独立组合、深色窄屏、异常数据及 IndexedDBViewHost 刷新恢复示例。

创建不修改默认实例偏好。`MemoryViewHost` 保留显式 `defaultInstanceId: null`；只有原先指定的默认实例已不可见时才回退到其他实例。删除当前访问范围中不存在的实例视为成功，不触及其他用户的私有视图；仍存在的可见实例继续校验权限和 revision。待确认创建状态属于当前引擎生命周期，直接服务调用者在重建客户端后自行保留原 requestId。

未确认创建的源视图若从完整列表中消失，`getSnapshot().pendingCreates` 会独立保留编辑上下文，不把它重新放入可见视图。通过 `reloadInstance(sourceId)` 核对原请求，`ViewPageContent` 提供对应恢复入口。这些条目不包含业务记录，不能作为普通实例查询或保存。重载保留本地标题、配置及筛选草稿，可见范围 `scope` 采用服务端返回的权威值。

远程标签快照仅在该 ID 新增或重新选择时采用当前候选；后台回填或修改其他 ID 不覆盖其已保存名称。多值文本粘贴先按光标/选区替换再拆分。畸形区间端点报告校验错误，不会退化为未设置条件。

记录视图支持表格与卡片布局，通过`record.defaultPresentation` 提供预设。使用 `resolveRecordPresentation` 构造展示配置，使用 `setLayout` / `setCardConfig` 编辑；切换回来保留原配置。参见[表格与卡片指南](https://fetcher.ahoo.me/zh/guides/view-engine/table-and-runtime)。

使用 `renderCard(context)` 自定义卡片信息结构，网格、选择与分页仍由库管理。展示方式切换位于顶部全局工具栏，统一使用显示当前模式的下拉框。

`ViewDefinition.record.allowedLayouts` 为必填的非空、不重复数组：`['table']`、`['card']` 或同时开启。仅允许一种布局时，顶部不显示切换入口；引擎和实例加载均拒绝未允许的活动布局。切换保留各模式配置。卡片使用右上角选择按钮（`aria-pressed`），不占独立行；自定义内容应避让该角标。顶部通用操作使用图标及提示，菜单保留文字。

## 纯分析编译

核心入口导出 `compileAnalysis(config, context)`、`validateAnalysisResult(rows, plan)`、`analysisRowKey(row, dimensions)` 及分析模型类型。这些函数不渲染 React，也不发请求。下面的示例编译 COUNT + SUM 并校验给定回包，不依赖分析页面或引擎集成。

```ts
import { AggregationFunction, FilterOperator } from '@ahoo-wang/fetcher-wow';
import {
  compileAnalysis,
  createFilterConfiguration,
  newFilterNode,
  validateAnalysisResult,
  type AnalysisCompileContext,
  type AnalysisViewConfig,
} from '@ahoo-wang/fetcher-view-engine';

const context: AnalysisCompileContext = {
  fields: [{ field: 'amount', label: 'Amount', type: 'number' }],
  capability: {
    count: true,
    fields: [
      { field: 'amount', groups: [], functions: [AggregationFunction.SUM] },
    ],
  },
};
const config: AnalysisViewConfig = {
  filters: createFilterConfiguration(newFilterNode(FilterOperator.MATCH_ALL)),
  dimensions: [],
  metrics: [
    {
      id: 'count',
      component: { name: 'count' },
      alias: 'orders',
      title: 'Orders',
      props: {},
    },
    {
      id: 'sum',
      component: { name: 'numeric' },
      field: 'amount',
      alias: 'revenue',
      title: 'Revenue',
      props: { function: AggregationFunction.SUM },
    },
  ],
  sort: [],
  limit: 100,
  presentation: {
    layout: 'table',
    columns: [{ alias: 'orders' }, { alias: 'revenue' }],
  },
};
const compiled = compileAnalysis(config, context);
if (!compiled.plan)
  throw new Error(compiled.errors.map(error => error.message).join('; '));
const result = validateAnalysisResult(
  [{ orders: 2, revenue: 125 }],
  compiled.plan,
);
if (!result.rows)
  throw new Error(result.errors.map(error => error.message).join('; '));
console.log(compiled.plan.query, result.rows);
```

`AnalysisViewConfig` 保存 `filters`、`dimensions`、`metrics`、基于 alias 的 `sort`、`limit` 和表格 `presentation.columns`。每个组件都有稳定的 `id`、可持久化的 `component` 引用、可选 `field`、输出 `alias`、展示 `title` 和原始 JSON `props`。不完整文本可以保留在配置中，但编译会返回错误，不产生可执行计划。

`AnalysisCompileContext` 提供筛选 `fields`、明确的 `capability`，以及可选 `timeZone`、`allowedOperators`、`filterCompilers` 和自定义分析 `compilers`。能力分别授权 COUNT、字段分组、数值函数和时间粒度。内置组件为 `terms`、`histogram`（`props.interval`）、`date-histogram`（`props.unit`）、`count`、`numeric`（`props.function`）和 `any`（已授权标量字段的代表值）。日期分组必须明确时区。自定义 `AnalysisCompiler` 注册必须声明非空且不重复的 `roles`（`['dimension']`、`['metric']` 或两者）。`AnalysisComponentCompileContext` 在 `AnalysisCompileContext` 上增加实际的只读 `role`，供自定义编译器和编辑器分支处理。不支持的角色会在调用编译器前被拒绝。`compile` 返回单个分组或指标，核心校验其字段、alias 和能力后才接受。引擎与 React 适配器在每个生命周期内复制并冻结角色元数据。

`compileAnalysis` 返回 `{ plan?, errors }`；成功的 `AnalysisPlan` 包含 Wow `query`、匹配的 `schema` 和可选 `timeZone`。排序中未指定的分组 alias 会追加为升序，确保顺序稳定。默认上限为 32 个分组、64 个指标、32 个有效排序项和 10,000 行，能力限制可以进一步收紧。至少需要一个指标。不分组的分析不支持排序，结果最多一行。

`validateAnalysisResult` 返回 `{ rows?, errors }`，遇到缺失 alias、类型无效、超量行或重复的带类型维度组合时拒绝整批结果。alias 按字面自有键读取，只保留 schema 中的列；仅可空列接受 null，COUNT 必须是非负安全整数，日期分桶值为 epoch 毫秒。对已校验行调用 `analysisRowKey(row, plan.schema.filter(column => column.role === 'dimension'))`，可获得带类型的维度身份。

### 多值文本缓冲

`FilterTextValues` 接收 `value?: readonly string[]`、可选受控 `rawText?: string`、报告原始键入的 `onRawTextChange?(text)` 和 `onValueChange(values, rawText)`。未提供 rawText 时由组件保存本地输入缓冲。回车或粘贴确认时，通过一次回调返回新值集合和空缓冲；移除标签时返回剩余值及当前缓冲。受控调用方应在该回调中同时更新 values 和 rawText。输入法确认不会提交值或发起查询。

注册的 `text-values` 编辑器将每次原始编辑保存到 `props.rawText`，未确认输入可跨实例切换恢复。去除首尾空白后非空的 rawText 阻止纯编译，非字符串 rawText 无效。确认会移除 rawText 并保留已确认值；清空同时移除 values 和 rawText。仅空白输入不产生未确认值。独立控件仍可使用可选 `onValidityChange(valid, message?)`，注册组件的有效性由编译结果决定。

`AnalysisPlan.schema` 保留查询输出顺序与语义元数据。`projectAnalysis(plan, rows, presentation).plan` 才按 `presentation.columns` 中的 alias 顺序与可选正数 CSS 像素宽度排列，再追加未指定输出，不隐藏列。查询编译不依赖展示兼容性。`analysisRowKey` 按 alias 规范化维度顺序，展示重排不改变行身份。histogram 的 `props.interval` 接受完整正数文本，并保留原始编辑字符串，编译后 interval 为数值。

## TypeScript 消费边界

已验证 TypeScript 6.0.3 的 ESNext + Bundler 和 NodeNext 两种模式，均使用 strict 与 `skipLibCheck: false`。打包后的公开入口仍能拒绝无效聚合指标和缺失引擎 scope 的错误输入。Wow／React 依赖链中的相对模块引用已使用明确的 `.js` 路径，目录导出使用 `/index.js`。

## 分析图表与 Wow API 接入

`AnalysisPresentation.layout` 支持 `table`、`metric`、`bar`、`line`、`area` 和 `pie`。柱状图可设置 `orientation: 'horizontal'`；`donut: true` 使用环形。`x`、`series` 和 `metrics` 引用输出 alias。同一份已校验结果可以切换展示方式而不重新查询。保存只持久化配置，**运行分析**才刷新数据；查询草稿改变时，旧结果保留自己的执行口径和展示配置。

切换到数据表时保留图表轴、系列、指标选择和显示偏好，切回后恢复。删除输出会清理对应展示引用；最后一个被选指标被删除时恢复默认指标，用户主动清空的选择仍保留为可编辑状态。重新选择有效指标也会移除失效别名。柱状图与面积图将正负 SUM 值分别堆叠在零轴两侧；null 或缺失组合提示不兼容，数据表仍可查看。 仅切换布局不会截断有效指标选择，也不会替换用户主动清空的选择。多指标切换到饼图时保留选择并提示兼容性问题，直到用户明确选定一个支持的指标；直接切回原布局时保留原有选择。 可选默认值在渲染时解析，不反写进草稿，因此单纯往返切换不会把原本未修改的视图标记为待保存。

`projectAnalysis` 是不涉及网络或持久化的纯投影，保留带类型的分组身份和 null，不再次平均 AVG，不虚构零值时间桶。图形最多展示 500 个已返回分组和 12 个系列。单位不兼容、存在未表达的额外维度、数值 ANY 或不支持的图形组合时，解释原因并显示表格。饼图和堆叠要求可加的 COUNT/SUM 指标；含 null 的堆叠和全零饼图也会回退。数据表分页只切换已经返回的行。

`AnalysisView` 按需加载 shadcn Chart/Recharts。查询配置统一使用右侧可访问 Sheet；左侧可视化配置独立折叠。结果始终显示执行时的根/元素筛选、时区和返回上限。日期坐标轴使用紧凑标签，tooltip 和数据表保留完整值。`formatAnalysisValue(value, column, timeZone)` 支持枚举标签和纯显示数字格式；`capability.fields[].numberFormat` 接收 Intl 数字选项与 `locale`，COUNT 保留准确整数显示。图表颜色使用局部 `--fve-chart-1` 至 `--fve-chart-5` token。

`adaptWowAnalysisSchema(schema, { labels?, units? })` 将当前 Wow Schema 转为筛选字段与聚合能力，不暴露 masked、未知/联合类型、标量数组和不支持的时间编码。合法同类型 `enumValues` 转为保留原始类型的字段选项。已授权对象数组的 `ELEMENT_SCOPE` 路径形成预定义 `capability.scopes`；`config.scope` 选择统计对象并提供各层元素筛选。根筛选保持根字段语义，各层元素筛选和最终聚合字段相对于选定元素。

启用 `capability.expressions` 后，数值指标可包含 FIELD/CONSTANT/BINARY `expression`，最多 8 层、256 个节点。ANY 仅作为表格中的标量代表值，不作为稳定分组/系列身份或数值图表指标。`AnalysisEditor` 接收筛选扩展、宿主上下文并报告合并后的元素筛选有效性；完整 `AnalysisView` 将根与元素编辑有效性合并，用于运行和保存。

宿主数据源复用 `SnapshotQueryClient` / `EventStreamQueryClient` 与应用已有的鉴权 `Fetcher`。[补偿 dev 示例](examples/react/compensation/README.md) 提供 Schema 发现、真实只读查询、取消、本地视图持久化及显式启用的集成测试。根事件流 COUNT 统计批次，展开 `body` 范围后 COUNT 统计事件条目。Storybook **View Engine → 专项场景 → 分析图表** 提供离线场景，**补偿 API 分析** 在用户主动连接后才访问服务。

可通过 `VIEW_ENGINE_BROWSER_CHANNEL=chrome pnpm verify:view-engine` 复现生产验收（或使用已安装的 Playwright 浏览器）。现有验证器构建并服务静态 Storybook，检查宿主与无障碍契约，然后在 100 字段/20 个过滤项/100 行下强制输入 P95 ≤400ms、缓存实例切换 P95 ≤450ms，并检查 10,000 行×21 列时的输入保留与取消。这些回归门槛为共享 CI 机器留出余量，不代表实测性能提升。设置 `VIEW_ENGINE_ARTIFACTS` 可保留原始样本和环境信息；开发模式计时不作为生产验收。

分析采用三块区域：右侧查询 Sheet、中间结果区、默认折叠的左侧可视化配置区。查询编辑器在关闭和调整尺寸时保持挂载；关闭保留草稿，不保存也不运行。先选择图表展示方式，再配置字段。未配置图表时默认显示数据表；已保存图表恢复原展示配置。底部“分析 / 数据表”模式始终保留，包括无图表或无结果时，切换不查询、不保存。图表不兼容时在分析模式说明原因，并提供明确的查看数据表操作。维度和指标保留摘要卡片、弹层编辑和键盘排序。

分析配置在桌面/对话框切换及关闭时保留同一编辑子树，保存扩展本地草稿与有效性。`DialogContent.keepMounted` 默认 `false`，需要时可保留关闭后隐藏的内容。饼图/环形图常驻显示分组数值和已返回分组内占比；原始值不变，占比先归一化再求和，避免溢出。

侧栏、实例选择器和管理视图统一使用图标区分分析视图与数据视图，并提供可访问的类型说明。图表与指标结果通过底部居中的“分析 / 数据表”标签切换；切换只影响本地展示，不发起查询或保存，数据表首次打开后保留当前页。图表配置不支持绘制时在分析模式显示原因，并提供“查看数据表”操作，不自动切换模式或覆盖图表配置。

`ViewPage` / `ViewPageContent` 的公共参数为 `engine`、`extensions`、`filterContext`、`className` 和 `initialSidebarCollapsed`（`ViewPage` 还接收绑定的 `error`）。记录专用选项 `selectable`、`autoRefreshPaused`、`renderToolbar`、`renderCard`、`renderPagination` 统一放在 `record` 对象内，只作用于记录视图；独立 `RecordView` 仍直接接收这些参数。页面统一管理配置面板开关。`ViewExtensions` 在页面层组合 `RecordExtensions` 与 `AnalysisExtensions`。

## 仪表盘组合

定义声明 `dashboard: true` 后可保存 `DashboardViewInstance`。纯仪表盘定义不需要 `sourceId`；记录/分析能力仍要求数据源。`config` 为 `{ schemaVersion: 1, panels, filters }`。数据引用面板保存 `{ kind: 'view', id, instanceId, layout: { x, y, w, h } }`：稳定面板 ID 与整数网格坐标/尺寸。网格为 12 列，x/y 非负，w 为 1–12，x+w ≤ 12，h 为 1–100，y+h ≤ 10000。引用必须解析为记录或分析实例；重复引用有独立运行位置。

```ts
const definition = {
  id: 'overview',
  title: '业务概览',
  fields: [],
  dashboard: true as const,
};
const engine = new ViewEngine({
  definitionId: definition.id,
  definition,
  host,
});
await engine.load();
const draftId = engine.createDashboard({
  title: '销售概览',
  scope: { type: 'personal' },
});
const dashboard = engine.dashboard(draftId);
dashboard.edit(config => ({
  ...config,
  panels: [
    {
      kind: 'view',
      id: 'orders-panel',
      instanceId: 'saved-orders',
      layout: { x: 0, y: 0, w: 12, h: 18 },
    },
  ],
}));
await engine.save(draftId); // 首次真实创建，由宿主提供保存身份与 revision。
```

`permission.getDefinition()` 必须明确授予 `createPersonal` / `createShared`，缺省拒绝。未保存会话标记 `persisted: false`，不会进入权威 `instanceIds`；创建草稿不写入。保存、另存、版本冲突及未知创建核对复用统一实例服务，只保存仪表盘配置，不保存引用的子配置。

`engine.dashboard(id)` 返回 `DashboardRuntime`，提供稳定的 `getSnapshot` / `subscribe`，以及 `edit`、`setFilter`、`setEditorValidity`、`apply`、`refresh(panelId?)`、`reloadReference(panelId)`、`suspend`、`resume`、`dispose`。引擎负责导航暂停/恢复与释放。`DashboardView` 只展示调用方拥有的运行对象；`ViewPage` 将其接入既有导航与保存操作。`RecordContent` 通过会话、定义和绑定命令复用表格、卡片、业务操作与分页，不依赖页面导航。

快照分别保存 `config` 草稿和 `applied` 已应用配置，并提供 `pending`、包含 dirty/写入状态的 `session`、`editable`、校验及逐面板状态。查询应用全局草稿；刷新沿用已应用快照；保存不查询。暂停释放运行位置/结果，返回时重新授权并使用保留的子版本；显式重载引用才采用最新保存配置。布局调整保留位置身份，不发查询。

每个全局项保存 `{ id, filters: FilterConfiguration, bindings, excludedPanelIds }`，每个 `kind: 'view'` 数据面板必须恰好绑定一次或明确不参与。字段绑定为 `{ panelId, kind: 'fields', fields, semanticCompatibility: true }`，元素完整路径与 SEARCH 字段列表必须全部映射。转换绑定为 `{ panelId, kind: 'transform', name, options? }`；在 `ViewEngineOptions.dashboardTransforms` 注册同步纯函数，接收只读 `{ expression, source, target, instance, options }`。转换缺失或无效时阻断整个受影响面板，不删去 OR 分支。最终作用域为原子视图条件 AND 所有参与的全局条件。

可选 `host.dashboard.search({ query, cursor? }, signal?)` 返回 `{ items: [{ id, definitionId, title, kind }], nextCursor }`，每次最多 100 个候选。使用前重新加载并授权；未提供 search 时隐藏数据引用的添加/替换入口，仍可添加内容卡片。`host.dashboard.openOriginal({ instanceId, definitionId })` 提供原视图导航。`extensions.dashboard.transforms[name]` 提供 `label`、可选 `applicable`、`hasOptions`，以及接受 `value`、`onChange`、`onValidityChange` 的受控 `Editor`；执行仍在核心注册表。`useViewEngine` 在访问生命周期开始时捕获配对注册表。未知扩展保留展示，不静默改写。

布局采用 react-grid-layout，支持二维移动与宽高缩放。手势结束才提交预览，Escape 取消当前手势。布局撤销、重做和取消仅影响几何配置；键盘手柄提供非拖拽等效操作，不再显示位置/尺寸数字控件。窄容器单列堆叠且不回写桌面坐标。布局变化保留查询位置且不发起取数；原子视图的配置编辑与保存仍在面板外完成。

### 预算与兼容

`RuntimeLimits` 默认：`maxDashboardPanels=12`、`maxDashboardFilters=32`、`maxDashboardResultRows=12000`、`maxDashboardResultBytes=16777216`、`maxDashboardMetadataBytes=127926272`（每引擎 122 MiB）。结果行数/字节在发布前累计当前仪表盘所有面板；元数据接纳按整个引擎保留的配置/引用 JSON 字节计数。`scripts/verify-dashboard-budget.mjs` 实测 1/6/20 个仪表盘 × 12 面板、重复/不同引用及 262144 字节子配置；默认值取最坏测量负载向上取整至 MiB 后的两倍，不代表传输或堆内存上限。

仪表盘数据请求共享引擎并发预算和 48 项 FIFO 等待队列；独立记录/分析入口仍保持即时 BUSY。引用加载独立并发 4、等待 24，每次真实实例/定义/数据源加载分别计执行期限。等待会话使用 `queryStatus: 'waiting'`；诊断 queued/started/终态分开报告等待与执行耗时，不含筛选值或记录。全局和合并表达式分别限制深度 32、节点 512；传输响应体上限仍由宿主保障。

Stateful/Memory/Local 与示例 HTTP 宿主接受 `supportedFormats: { record: true, analysis: true, dashboard: 1 }`，缺省表示旧客户端；HTTP 适配器发送 `X-View-Formats`。所有实例响应统一投影：隐藏的仪表盘默认项返回 null 而不改变真实偏好，删除回执可重放，旧客户端排序保留隐藏位置；不支持的单实例读写在变更前拒绝。先部署宿主格式投影，再允许创建仪表盘；客户端回退时保留投影。

本地测试、模拟实例服务持久化与只读 Wow 查询是不同证据。真实触摸、读屏、业务用户走查及生产宿主授权/回退准入仍需在消费应用验证。

本地仪表盘草稿在导航与管理器中显示为独立的未保存分组，仍不进入权威 `instanceIds`，不参与已保存视图的排序或默认设置。`createDashboard()` 在创建本地状态前同时检查创建授权和宿主 `instance.create` 服务。

内容卡片共用 `id` 与 `layout`：`{ kind: 'markdown', title, content }`、`{ kind: 'link', title, href, description? }` 或 `{ kind: 'image', title, src, alt, caption? }`。Markdown 使用 CommonMark，内容限制为 UTF-8 64 KiB，不执行原始 HTML。链接接受 HTTP(S)、mailto、tel 和相对地址；图片接受 HTTP(S) 和相对地址。`alt` 为字符串，装饰性图片可以留空。图片使用 URL，不提供文件上传服务。所有卡片均计入配置字节和面板数量预算。

内容在本地对话框中编辑，明确提交才修改仪表盘草稿，取消保留原草稿。内容卡片不解析数据源、不创建查询位置，也不接收筛选绑定或排除配置。编辑内容不查询其它面板。`DashboardSnapshot.panels` 只包含数据引用的运行快照，内容卡片从 `config.panels` 渲染。
