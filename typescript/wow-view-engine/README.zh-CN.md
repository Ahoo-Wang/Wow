# Fetcher View Engine

View Engine 承担数据视图能力的后续演进。`@ahoo-wang/fetcher-viewer` 已进入维护期（弃用），不再新增功能；新项目请使用本包。两者模型与 API 不同，迁移需要适配。

[任务指南](../../wiki/zh/guides/view-engine/index.md) · [API 参考](../../wiki/zh/reference/view-engine/index.md) · [共享可运行示例](../../wiki/zh/examples/view-engine.md)

独立的 `@ahoo-wang/fetcher-view-engine` 包，提供可独立使用的 Wow 过滤器编译与校验、完整 `FilterPanel`、结构化值编辑器和 shadcn/Base UI 控件。同时提供不依赖 React 的 ViewEngine 和完整 RecordView 页面，定义、实例与保存接口由宿主管理。卡片、AnalysisView 与 DashboardView 属于后续工作。

## 模块职责

公开 `ViewEngine` 负责组合内部服务，应用通过公开命令和快照接入。核心运行时不导入 React、DOM 或表格组件库。

| 模块                                                       | 职责                                                                          |
| ---------------------------------------------------------- | ----------------------------------------------------------------------------- |
| `record/engine/SessionStore`、`sessionState`               | 不可变快照、订阅、保存与编辑基线，集中推导 dirty/pending。                    |
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

`examples/core.mjs` 演示无 React 的公开 API，包括未设置控件和自定义原始属性的 JSON 保存及新引擎恢复；`examples/react/OrderExample.tsx` 仅通过公开导入组合页面和五类扩展。打开 `http://127.0.0.1:4175`，或 Storybook 的 **View Engine → 快速开始 / 扩展接入**，体验操作、自定义过滤器/单元格、异常恢复与深色窄容器。

执行 `pnpm install` 后，`pnpm storybook` 和 `pnpm build-storybook` 会先显式构建 View Engine 及其工作区依赖，再启动或构建 Storybook。两者均使用包含 CSS 的公开 `dist` 入口；编辑包源码后重新运行命令即可重建，生产验收继续验证真实包产物。

`examples/react/FilterPersistenceExample.tsx` 保存选中状态 ID 和独立编辑的显示名称。打开 `http://127.0.0.1:4175/?example=persistence`，或 **View Engine → 扩展接入 → 公共包 → 公共包 · 组件配置 JSON 保存与重新打开**：新增未设置控件后无需查询即可保存，并在新引擎中恢复；只改显示名称也能直接保存，状态值改变后则需先查询。

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

验收负载为每页 100 行、30 个数据列、100 个筛选候选字段；覆盖 1440px/390px、浅色/深色、键盘查询与选择、错误重试及反复卸载。刷新、行选择和字段面板的暖交互分别记录 10 个样本，p95 上限为 1000ms，包含自动化通信和绘制等待，不是业务网络延迟 SLA。更大规模需要消费方独立测量；当前不承诺虚拟滚动或任意数据量。

可访问性保留原始 axe 结果。WebKit 对 Base UI 隐藏焦点哨兵的命名告警按[上游已知行为](https://github.com/mui/base-ui/issues/5237)单独记录，并验证实际键盘进入、Tab 离开和 Escape 恢复；其他违规仍使验收失败。这不替代真实 VoiceOver/移动设备人工测试。

## RecordView 数据视图

```tsx
import type { ViewHost } from '@ahoo-wang/fetcher-view-engine';
import { ViewPage } from '@ahoo-wang/fetcher-view-engine/react';
import '@ahoo-wang/fetcher-view-engine/styles.css';

export function OrderPage({
  host,
  scopeKey,
}: {
  host: ViewHost;
  scopeKey: string;
}) {
  return (
    <ViewPage
      scopeKey={scopeKey}
      definitionId="orders"
      host={host}
      selectable
    />
  );
}
```

`ViewHost` 是组合门面，接口按职责组织：`definition`（`ViewDefinitionService`）读取定义；`instance`（`ViewInstanceService`）负责实例列表、读取、创建、保存、改名与删除；`preference`（`ViewPreferenceService`）保存当前用户的排序偏好；`permission`（`ViewPermissionService`）提供权限快照、刷新与订阅。各服务可以独立注入，未提供的服务或方法会禁用对应能力。`resolveSource` 保留为本地运行时的数据源桥接。

```ts
const host: ViewHost = {
  definition: definitionService,
  instance: instanceService,
  preference: preferenceService,
  permission: permissionService,
  resolveSource: id => businessSources[id],
};
```

服务契约位于独立的 `src/record/ViewHost.ts`。`LocalStorageViewHost` 实现相同职责边界，存储操作共用事务以保持完整性；HTTP 保留为包外的开发实验。只替换某个方法时显式合并该服务，例如 `instance: {...host.instance, save: customSave}`。

实例列表必须提供 `defaultInstanceId: null` 或列表中已有的实例 ID；可选 `revision` 一旦提供就必须是非空白字符串。无效响应在加载边界失败。

`ViewHost` 加载定义及完整实例列表、解析已配置的 Wow 查询客户端、提供权限，并按需实现保存与创建接口。本地数据可直接传入 `definition` 与 `instances: {instances, defaultInstanceId}`。必填 `scopeKey` 标识用户、租户与访问范围，范围变化时更换此值；引擎按 `[scopeKey, definitionId]` 管理生命周期。同一作用域下替换宿主对象会更新回调与能力，保留草稿。本地定义和列表作为该生命周期的初始值，引用变化不触发重载；需要重新初始化时显式改变 React key。宿主自行管理引擎时，使用 `ViewPageContent` 或 `RecordView`。

每次 `engine.load()` 都会与元数据并行初始化权限：优先等待 `permission.load(definitionId, signal)`，未提供时等待 `permission.refresh(signal)`。权限服务负责维护快照，并须在完成前准备好同步 getter。初始化失败时不进入 ready、不查询记录；通过 `load()` 重试，释放或重新加载会取消初始化信号。只有同步 getter 的服务无需初始化。同作用域 `updateHost` 接收已准备好的权限投影；后续异步变化通过 `permission.subscribe` 通知。

`RecordQuerySource` 可以只提供 `paged`、只提供 `cursor` 或同时提供两者；`aggregate` 仍为可选能力。保存的分页模式不受支持时，在发出记录及汇总请求前明确报错，页码分页适配器无需编写会抛错的游标方法。

引擎订阅回调异常通过 `console.error` 报告，不中断写入和其他订阅者。筛选编译只在草稿、已应用条件或有效性变化时重新执行；行选择、查询状态、汇总和仅改标题不触发重新编译。五类扩展均只读取注册表自身明确注册的属性。持久化组件名称代表稳定的属性与编译语义；不兼容变化应使用新名称（例如 `order-status/v2`），旧配置仍存在时保留旧注册。未知注册继续阻止相关能力运行，当前不引入自动迁移框架。

实例保存 `config.filters: {mode, root}`。每个组件保存稳定配置 ID、`{name, options?}` 引用、操作符、字段绑定、原始 JSON `props` 和子组件。编译结果只在运行时的 `session.appliedFilter` 中；null 表示尚未编译成功，阻止记录及汇总查询。恢复直接读取组件配置，不从查询表达式反推 UI。对象属性中的 undefined 保存时省略；null、false、零和空字符串保留，拒绝非 JSON 值。

`setFilterDraft(configuration, id?, valid?)` 通过已注册的纯函数编译。有效修改若未改变已应用查询，会立即更新接受的配置与编辑基线，无需请求即可保存，例如新增未设置控件或修改显示名称。查询值改变或输入无效时产生 `filterPending`，需先查询接受草稿或撤销修改，才能保存。同步状态下 `setFilterMode` 保存支持的模式变化；`dirty` 比较接受的配置与已存 JSON。`sameFilterQuery` 忽略对象键顺序和仅含一个条件的冗余 AND/OR 包装，其余表达式变化仍需查询。

程序调用先 `setFilterDraft(configuration)`，再 `applyFilter(id?)`。编辑和已接受筛选快照均使用含模式的 `FilterConfiguration`。局部输入无效时拒绝执行，保留草稿和已应用查询；`setFilterValidity(true)` 也不能使已改变或未编译的查询变得可保存。

核心快照中的定义、实例、草稿和记录采用 `DeepReadonly`。可直接读取，也可将快照传回 `setFilterDraft`、`setSort` 和 `setColumns`，引擎会复制接受的输入。修改时构造新对象；传给宿主查询和写入接口的参数仍是独立、可编辑的数据对象。

表格采用 shadcn Table + TanStack Table，支持服务端排序、分页与只向前的游标查询。仅在表头列边缘拖动调整宽度，并保留键盘方向键作为无障碍替代；列设置通过拖动手柄调整同一区域内的顺序，也可聚焦手柄后按上、下方向键移动，同时支持显示/隐藏。松手后写入顺序，取消拖动保持原配置。调整列展示不查询，筛选点击“查询”才生效。页面按实例保留草稿，当前实例不再单独显示“已编辑”标签，由保存按钮表达可保存状态，有待查询筛选时仍阻止保存。另存为支持个人和公共共享实例，实际权限与持久化由宿主负责。

紧凑工作台将标题与全局工具栏合并：按标题、当前实例、保存组合按钮排列，菜单提供另存为与还原，创建等全局操作置于右侧。没有原实例保存权限时，主按钮改为另存为。筛选组合按钮同时提供展开/收起和简单/高级模式选择，省去独立标题行；添加筛选在左，撤销、清空与查询在右。

独立记录工具栏左侧显示已选数量和取消选择，右侧依次为批量操作、列设置。取消选择不查询，也不清空筛选草稿。待查询提示在展开时位于查询附近，收起时位于筛选按钮；当前标题与侧栏不再重复，其他实例仍保留待查询标记。记录统计和分页统一放在底部。筛选区默认展开，收起时编辑器持续挂载，保留草稿与勾选。收起状态不保存到实例，也不触发查询；窄容器内控件自动换行。

未固定、没有枚举选项且未显式设置 `width` 的 string 列均分剩余宽度，从默认 180px 增长到最多 480px；显式列宽、固定列与其他类型保持配置或默认尺寸。窄容器内横向滚动。拖动自动列会保存实际新宽度；列设置不提供宽度输入，定义可省略 width 启用自动适配。容器大小变化不修改实例、不查询。无法分配的空间放在右固定区域之前，操作保持右边缘。数值表头与单元格默认右对齐，使用等宽数字；自定义渲染器可覆盖自身对齐。响应式工作台 Storybook 示例每页展示 15 条记录。

视图列表分为“个人视图”和“公共视图”，系统视图显示“系统”标签。
列表旁及视图切换下拉面板底部的**管理视图**统一提供行内改名、确认删除和组内拖动排序。
名称默认显示为文本，点击编辑图标才显示输入框；保存或取消后恢复文本，Escape 取消当前名称编辑，保存失败保留输入以便重试。
系统视图不能改名或删除，但可调整其个人展示顺序。改名由
`host.instance.rename(id, title, revision?)` 执行，删除由 `instance.delete(id, revision?)`
执行，宿主通过 `permission.getInstance` 的 `rename` / `delete` 授权。
改名只保存名称，保留待查询筛选和未保存的列配置，不触发查询。
`preference.saveOrder(definitionId, ids)` 保存当前用户的展示顺序，包括公共视图；不影响其他用户。
写入成功后更新列表，失败保留编辑并可重试。save、rename、delete 或偏好排序进行中时，完整 `engine.load()` 会被拒绝；加载完成前不接受读取或编辑会话的命令，包括取消回调中重入的命令；快照仍可读取。另存为或核对成功后独立完成，后续记录读取失败仅保留在 `queryError` 中。原保存菜单移除独立删除入口，保留另存为和还原。

save、rename 或 delete 发出后，`UNKNOWN_OUTCOME`、`UNAVAILABLE` 或未分类异常会阻止该实例的其他写入并保留本地编辑。保存和改名需要重载成功核对版本；实例不存在或不可访问时保留核对错误与编辑。不确定的创建可通过原请求 ID 重试；不确定的删除可按同一 ID/revision 幂等重试。界面订阅 `getCapabilitiesSnapshot().instances[id].retryDelete` 判断此例外。宿主应使用明确的 `ViewServiceError` 代码报告确定拒绝。
无 React 时可调用 `renameInstance(title, id?)`、`deleteInstance(id?)`、
`canReorderInstances()` 和 `reorderInstances(ids)`。

主键列（字段绑定 `definition.rowKey`）始终固定在左侧最前面，操作列始终固定在右侧最后面，实例配置和列设置都不能改变这两类列的固定方向。列设置使用图钉按钮切换固定状态，不提供左/右下拉框：未固定列仅在上下恰有一个相邻设置项已固定时可点击，继承其固定方向；上下均未固定或均已固定时不可固定，已固定的普通列仍可取消固定；主键和操作列显示已固定且禁用的图钉。宿主契约仍接受 `pinned: 'left' | 'right' | false`，已有固定方向在用户修改前保持有效，调整随实例保存；同一区域内可拖动排序，数值汇总选择与显隐、固定控件处于同一行。表头、数据行和汇总行保持对齐，隐藏与调宽同步更新偏移；选择列位于主键之前。调整固定位置和顺序不查询记录或汇总。

`extensions.cells`、`globalActions`、`toolbarActions`、`rowActions` 与 `filters` 注册任意本地 React 组件，远程定义只保存名称与 JSON 参数。定义通过 `recordActions.global`、`.toolbar`、`.row` 指定创建、批量处理、查看记录等操作所在区域。已有全局注册保留原位置，批量组件需显式移到 `toolbarActions`。独立使用 `RecordTable` 时必须显式传入 `appliedFilter`。业务操作得到这一运行时查询范围（编译成功前 `filter` 为 null）、稳定记录主键与绑定当前实例的刷新回调。勾选仅表示明确选择的当前页记录。定义、实例和记录必须是 JSON 数据；主键必须为唯一字符串或有限数字，不回退到数组下标。核心入口仍不加载 React。

扩展输入采用导出的 `DeepReadonly<T>` 递归只读快照。把需要编辑的字段复制到组件自己的表单状态，再通过宿主命令或引擎方法提交。尚未查询的筛选编辑不会触发记录单元格边界的重渲染。

字段定义和列的 `field` 使用相对于返回记录的完整点路径，例如 `customer.name`、`state.amount`、`items.0.name`。默认、内置和自定义单元格共用取值函数，渲染器直接接收解析后的 `value`。只读取自有属性；缺失或中间值为空时返回 undefined，默认显示“—”，保留零和 false。不解析方括号或通配符，也不自动展开对象生成列。

全局和批量操作组件渲染失败后，选择、查询状态等实际输入发生变化时会重新尝试渲染；无关的筛选草稿编辑不会反复触发失败组件。

Storybook 的 **View Engine → Record View** 使用内存服务演示完整请求与回包。详见[宿主与扩展契约](../../skills/fetcher-view-engine/references/api.md#record-views-and-host-contract)。

另存为的“可见范围”使用 Radio：个人视图仅自己可见，公共视图对有访问权限的用户可见。两项均直接展示说明；无创建权限的范围禁用，默认选中有权限的范围。

当固定区域让业务字段不足 128px 时，表格临时采用紧凑布局：主键缩窄并通过 Tooltip 显示完整值，操作列使用 64px 图标弹层，普通固定列暂随中间区域滚动。容器恢复后还原原列宽与固定偏好，不写入实例；紧凑模式的主键/操作列自动定宽，在常规布局中可拖动调宽。极窄容器或固定锚点过多时明确提示空间不足。

查询失败在记录区展示图标、原因与重试，不使用空结果图标，不显示零条记录或分页。后台失败保留原记录，并标明上次查询结果。

已应用筛选在编辑区下方、记录工具栏上方展示为 shadcn Badge 标签，收起编辑区后仍可见；顶层 AND 条件分别展示，OR/NOR 与元素条件保留完整分组。点击标签的 × 按注册的清空语义将值设为“未设置”并立即查询，保留字段、操作符、分组与编辑器 ID；无需值的操作及未提供清空语义的自定义组件不提供清空按钮。查询中或有待查询修改时禁用清空，先查询或撤销修改后可继续操作。单行筛选输入框支持回车查询；中文输入法确认、下拉选择、多行输入和弹层交互保留原有键盘行为。标签保留精确阈值，长条件自动换行，无条件时显示“全部记录”。待查询草稿不会替换标签；顶部筛选按钮仅保留展开/收起与模式，不再显示摘要 Tooltip。

记录错误区域的“重试查询”调用 `engine.retryQuery(id?)`，保留当前页码与游标；显式 `refresh()` 仍使游标分页回到第一页。

自动刷新暂停时提供原因和恢复条件。普通保存成功后短暂显示“已保存”及无障碍播报。

## 视图服务契约与运行时边界

验收链路为 **服务 JSON → ViewHost → 新建 ViewEngine → 前端注册表 → 组件和操作恢复**。`LocalStorageViewHost` 是可执行的服务替身，包外 HTTP 适配器用于验证暂定协议。服务负责定义、实例、可见性、版本、创建回执和用户排序；前端负责组件实现、回调、过滤器编译和业务查询客户端。业务数据写入不应改变视图配置。

### 本地服务替身

```tsx
import { LocalStorageViewHost } from '@ahoo-wang/fetcher-view-engine';

const host = new LocalStorageViewHost({
  serviceKey: 'development-tenant',
  scopeKey: 'alice',
  storage: localStorage,
  lock: (name, operation, signal) =>
    navigator.locks.request(name, { signal }, operation),
  definition: orderDefinition,
  instances: orderViews,
  resolveSource: id => orderService.host.resolveSource(id),
});
```

`serviceKey` 标识服务／租户，`scopeKey` 标识其中的可信用户。存储键为 `fve:views:${JSON.stringify([serviceKey, definition.id])}`。公共视图在同一服务内共享，个人视图与展示顺序按用户隔离；归属由服务决定，不能通过写入正文伪造。ViewPage 的 scopeKey 应包含租户与用户；不传本地 definition/instances，让加载完整经过宿主。

必填 `lock` 覆盖读取、授权、版本检查和写入的整个事务。示例使用 Web Locks 串行化同源标签页；同一存储键的所有写入者必须使用同一个锁域。初始实例在初始化时取得服务端版本。系统视图只读。可选 `instancePermissions`、`canReorder`、`permissionsRevision` 提供可信权限策略，权限变化时必须递增策略版本；写入在事务内重新检查最新权限。

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

HTTP 脚本启动隔离的本地服务，以相同的 LocalStorageViewHost 逻辑、同步存储接口和服务端身份绑定处理请求，浏览器通过 HttpViewHost 驱动真实组件。覆盖共享／私有可见性、个人排序、响应丢失幂等、权限撤销／恢复、超时重试和引擎取消；另用两个标签页验证真实 localStorage + Web Locks 竞争写入及排队取消。HTTP 测试还覆盖权限响应乱序、无效会话、归属伪造与并发写入。编译开关两种模式的组件测试验证缺失／替换扩展及冲突恢复。测试服务不是已经接入生产数据库或生产认证的部署系统。

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
`autoRefreshPaused`。切换实例后自动刷新恢复关闭。

刷新按钮显示所选周期及 `分:秒` 倒计时（例如 `30 秒 · 00:29`），按实际截止时间
计算。暂停时显示“已暂停”，请求期间显示“刷新中”；恢复、切换周期或刷新完成后
重新开始完整周期。倒计时不会每秒触发读屏播报。

无 React 时可调用 `engine.refresh(id, {background: true})`，通过
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

简单模式在根级和每个 ELEMENT_MATCH 元素作用域内隐含 AND，支持嵌套数组；高级模式结构化编辑全部 50 种 Wow 操作以及 AND / OR / NOR / ELEMENT_MATCH。编辑、清空、撤销、模式切换都不请求服务；点击查询后通过 `onApply` 提交合法条件。宿主接收 `{configuration, expression}` 并管理请求，通过 `querying` / `queryError` 传回状态。`onPendingChange` 用于保存视图前的待查询保护。

简单模式中每个作用域内的字段只保留一项，未设置值也占用该字段。元素条件显示“同一元素满足”和子筛选项，不显示逻辑组合选择器；空元素作用域可继续编辑，但添加子条件前不能查询。高级模式的 AND、OR、NOR 均允许同一字段多条规则，元素作用域内同样适用；编译与实例保存接受合法的重复字段条件。包含重复字段的草稿保持高级模式，删除多余条件且各作用域符合简单模式结构后才允许切回简单模式。

点击“添加筛选”打开锚定按钮的 Popover，按组展示 Checkbox，打开和关闭不改变表格、查询按钮的位置。浮层限制高度，字段区内部滚动；添加后保持打开，支持连续添加。“完成”或 Esc 关闭并返回触发按钮焦点，点击外部也可关闭。字段定义可通过 `group` 指定分组，按定义中的首次出现顺序展示。与有分组字段混用时，未分组字段显示在“其他字段”下；复选框与当前分组的草稿同步：勾选添加条件，取消勾选移除该字段的直接条件，未设置值仍显示为已勾选。高级模式在已选字段旁显示条件数量和“追加条件”加号，AND、OR、NOR 统一支持追加；高级模式在“添加筛选”旁提供图标下拉按钮，独立选择 AND/OR/NOR 并添加到当前分组，这三项不进入字段面板；未获定义允许的操作禁用。根级操作仍保留添加按钮。所有变更仍在点击“查询”后统一生效。

完全未设置的值保留控件但不产生谓词；部分填写、无效数据和未注册扩展阻止查询。所有字段在添加时绑定，保留所属分组及作用域。`extensions.filters` 提供本地自定义编辑器；`value` / `onChange` 可将配置交由宿主按实例保留；`defaultValue` 则让面板本地管理，两者互斥。`appliedValue` 提供已接受配置基线。各快照共用组件树，模式位于 configuration.mode。自定义组件通过 `onChange(props)` 发布可序列化属性；选中 ID、显示名称等需要保存的 UI 状态放在 props，仅未提交的临时缓冲保留在 React 局部状态中。

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

`ViewPage`、`ViewPageContent` 与 `RecordView` 支持 `renderToolbar` 和 `renderPagination`。每个回调收到只读状态、默认区域节点和绑定实例的受控操作。返回默认节点可保留它，包裹节点可组合 UI，返回 `null` 可隐藏区域。扩展需要 Hook 或局部状态时应返回一个组件。

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

在 Storybook 中打开 **View Engine → 过滤器**，体验业务筛选、嵌套元素条件、自定义编辑器校验、查询重试、深色主题与 50 种操作。**View Engine → 基础组件 → 日期时间** 提供单独日期时间控件。示例包括手动查询的字段组合、日期选择、秒精度时间、未完成输入、未设置值和深色主题；**View Engine → 基础组件 → Select** 演示选择、清空和重新选择；Controls 支持切换外观与禁用状态。组合示例使用浏览器本地时区生成毫秒时间戳的 Wow 表达式，不请求业务服务。这些示例消费包的公开构建产物，修改包源码后需重新构建。

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

远程候选通过 `extensions.optionSources` 注入，不加入 ViewHost。数据源对象在会话内应保持稳定，范围变化时替换对象。ViewPage 使用 `scopeKey` 隔离访问范围，独立 FilterPanel 在用户/租户变化时使用 React `key` 重挂载。

```tsx
import type { FilterOptionSource } from '@ahoo-wang/fetcher-view-engine';
import { ViewPage } from '@ahoo-wang/fetcher-view-engine/react';

// sources.users 提供 search(query, signal) 和 resolve(ids, signal)。
// search 复用 Wow CursorPage，返回 { list, nextCursor }。
// resolve 返回 { list, missing }，明确交代每一个请求 ID。
const sources: Record<string, FilterOptionSource> = { users: userOptionSource };

<ViewPage
  definitionId="orders"
  scopeKey="tenant:user:access"
  host={host}
  extensions={{ optionSources: sources }}
/>;
// 字段：editor: { name: 'remote-multi-select', options: { source: 'users', pageSize: 20, debounceMs: 300 } }
```

远程组件复用 `@ahoo-wang/fetcher-react/core` 的异步执行与防抖，分页复用 `CursorPage`。支持加载更多、取消、过期响应丢弃、分页去重，以及候选和标签的独立重试。输入法组合期间不搜索，搜索框 Enter 确认候选而不触发记录查询。

持久化属性为 `value` 或 `values` 加 `selectedOptions` 标签快照。自动标签回填只更新运行时显示，不修改属性、dirty 或记录查询。明确缺失的 ID 仍保留原标识；回填失败不代表选项已删除。清空移除选择值和标签快照，保留配置好的筛选节点。

多值文本按换行、中英文逗号和分号拆分、去除两端空白并去重，保留名称内部空格、大小写和前导零；Enter 先提交未完成条目，不承担 CSV 引号解析。区间保留 `lowerBound`、`upperBound` 组件属性；日期模式编译时包含最后一天全天，显式时间模式按精确时刻查询。

在 Storybook 的 **View Engine → 过滤器 → 内置组件** 或独立示例 `?example=builtin-filters` 查看。`BuiltinFiltersExample.tsx` 通过 Fetcher 读取确定性 data URL 夹具，并以 LocalStorageViewHost 验证标签恢复与 JSON 持久化。只有该 data URL 夹具移除 URL 模板解析，真实 HTTP 客户端保留原有 URL 和鉴权拦截器。

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

状态主题变量为 `--fve-success`、`--fve-warning`、`--fve-info` 与已有 `--fve-destructive`；状态始终保留文字，不只依赖颜色。弹层继承当前主题。**View Engine → 单元格 → 内置组件** 与 `examples/react/BuiltinCellsExample.tsx` 包含独立组合、深色窄屏、异常数据及 LocalStorageViewHost 刷新恢复示例。

创建不修改默认实例偏好。`LocalStorageViewHost` 保留显式 `defaultInstanceId: null`；只有原先指定的默认实例已不可见时才回退到其他实例。删除当前访问范围中不存在的实例视为成功，不触及其他用户的私有视图；仍存在的可见实例继续校验权限和 revision。待确认创建状态属于当前引擎生命周期，直接服务调用者在重建客户端后自行保留原 requestId。

未确认创建的源视图若从完整列表中消失，`getSnapshot().pendingCreates` 会独立保留编辑上下文，不把它重新放入可见视图。通过 `reloadInstance(sourceId)` 核对原请求，`ViewPageContent` 提供对应恢复入口。这些条目不包含业务记录，不能作为普通实例查询或保存。重载保留本地标题、配置及筛选草稿，可见范围 `scope` 采用服务端返回的权威值。

远程标签快照仅在该 ID 新增或重新选择时采用当前候选；后台回填或修改其他 ID 不覆盖其已保存名称。多值文本粘贴先按光标/选区替换再拆分。畸形区间端点报告校验错误，不会退化为未设置条件。

记录视图支持表格与卡片布局，通过定义级 `defaultPresentation` 提供预设。使用 `resolveRecordPresentation` 构造展示配置，使用 `setLayout` / `setCardConfig` 编辑；切换回来保留原配置。参见[表格与卡片指南](https://fetcher.ahoo.me/zh/guides/view-engine/table-and-runtime)。

使用 `renderCard(context)` 自定义卡片信息结构，网格、选择与分页仍由库管理。展示方式切换位于顶部全局工具栏，统一使用显示当前模式的下拉框。

`ViewDefinition.allowedLayouts` 为必填的非空、不重复数组：`['table']`、`['card']` 或同时开启。仅允许一种布局时，顶部不显示切换入口；引擎和实例加载均拒绝未允许的活动布局。切换保留各模式配置。卡片使用右上角选择按钮（`aria-pressed`），不占独立行；自定义内容应避让该角标。顶部通用操作使用图标及提示，菜单保留文字。
