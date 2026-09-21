# Fetcher View Engine

> **状态：[docs/design/](docs/design/) 定义的重写已交付**，第九步即最后一步已完成。Record、Analysis、Dashboard 三种视图，以及下文的 `/react` 控制器与 `/ui` 组件均已在包内。旧实现以 git tag `view-engine-legacy`（`a064fc1a`）冻结，仅作参考。

**Fetcher View Engine 是面向 Wow 业务应用的数据视图引擎。** 业务应用用代码声明一份数据"能被怎样观察"：字段、类型、操作符、可用的分组与指标。用户在界面上决定"这一次怎样观察"：筛选、列、排序、分组、图表、面板组合。引擎把这种观察方式编译成 Wow 查询、执行、渲染，并把有价值的观察方式保存下来供下次直接打开。

它是 `@ahoo-wang/fetcher-wow` 之上的展示层，也是 `@ahoo-wang/fetcher-viewer` 的继任者。

## 解决什么问题

业务系统里的大多数页面是同一种页面：一张列表，带筛选、排序、分页，偶尔加一张统计图。每个业务对象（订单、库存、客户、工单）都要写一套。运营每提一次"再加一个筛选条件""按仓库分组看一下""把这几张表放到一个概览页"，都要改代码、排期、发版。

数据没有变，变的只是观察方式。问题在于观察方式被写死在页面代码里：用户不能自己调整，研发被重复劳动占满，产品把展示层的每次调整都当成需求。

## 目标

- **用户目标。** 在已声明的能力范围内自己调整数据范围、组织方式与呈现方式，把常用的观察方式保存下来，下次一键重开。
- **研发目标。** 一个业务对象接入一次，即一份定义加一个查询客户端，之后明细、分析、概览三类视图不再需要写页面；筛选编辑、查询协调、结果呈现、保存恢复与冲突处理由引擎统一提供。
- **演进目标。** 新增业务对象只增加定义，不在引擎里加业务分支；自定义布局通过无样式钩子复用同一套行为，不复制一份逻辑。

## 价值

| 对象     | 得到什么                                                                                                                       |
| -------- | ------------------------------------------------------------------------------------------------------------------------------ |
| 业务用户 | 不等排期就能得到需要的视图；常用视图保存后直接打开；同一份数据既能看明细，也能看分组统计与概览                                 |
| 研发     | 列表类页面从"每个对象一套"变为"每个对象一份定义"；筛选、分页、排序、保存、冲突只实现一次；定义可由 generator 从 Wow 元数据生成 |
| 产品     | 支持范围内的展示调整不再是需求而是配置；"保存与共享视图"可以作为产品能力交付给客户                                             |

## 场景

| 场景                       | 视图         | 用户做什么                                                                     |
| -------------------------- | ------------ | ------------------------------------------------------------------------------ |
| 仓管每天找待出库订单       | Record       | 筛选状态为待出库，按创建时间排序，只显示需要的列，保存为"今日待出库"           |
| 主管比较各仓库积压         | Analysis     | 按仓库分组、计数并合计金额，切换为柱状图                                       |
| 运营周会看整体情况         | Dashboard    | 把上面两个视图放进一个面板页，用全局时间范围同时约束两者                       |
| 业务页面里嵌一块数据       | EmbeddedView | 开发者把已保存视图嵌入订单详情页，只读展示，不带工作台                         |
| 运维为新业务对象配基础视图 | 系统视图     | 在定义中声明"全部""待处理""本周新增"三个视图，用户打开即有可用视角，再按需另存 |
| 客户在租户内自定义报表     | 全部         | 客户保存并共享自己的视图，供应商无需为此发版                                   |

## 不是什么

不是数据库或计算后端，不是权限系统，不是通用低代码页面搭建器，也不是 BI 建模工具。数据、聚合能力与授权由业务服务提供，引擎只负责让观察方式可靠地运行。

## 安装

该包尚未在公共 registry 首次发布，稳定版发布脚本会刻意跳过它。在此之前请通过本工作区（`"@ahoo-wang/fetcher-view-engine": "workspace:^"`）或本地 `pnpm pack` 产物使用。发布后的安装方式为：

```bash
pnpm add @ahoo-wang/fetcher-view-engine @ahoo-wang/fetcher-wow
```

`react` 与 `react-dom` 只是 `/react` 和 `/ui` 入口的 peer 依赖。根入口可在 Node 中运行。

## 设计原则

| 事实                   | 推论                                                                                     |
| ---------------------- | ---------------------------------------------------------------------------------------- |
| **定义是代码。**       | 没有定义服务与定义版本。定义变更就是一次发版；保存的视图在打开时按当前定义校验。         |
| **配置是数据。**       | 持久化对象只有 `ViewInstance` 与个人偏好。一致性策略是乐观 revision 加幂等 `requestId`。 |
| **运行状态是临时的。** | 草稿、结果、分页与选择只活在一个打开的 `ViewRuntime` 中，不持久化。                      |

## 快速开始

### 1. 声明定义

```ts
import type { ViewDefinition } from '@ahoo-wang/fetcher-view-engine';

export const orders: ViewDefinition = {
  id: 'orders',
  title: 'Orders',
  kind: 'data',
  source: 'orders',
  fields: [
    { name: 'id', label: 'Order', kind: 'string' },
    {
      name: 'status',
      label: 'Status',
      kind: 'enum',
      options: [
        { value: 'PENDING', label: '待出库' },
        { value: 'SHIPPED', label: '已出库' },
      ],
    },
    { name: 'warehouse', label: 'Warehouse', kind: 'string' },
    {
      name: 'amount',
      label: 'Amount',
      kind: 'number',
      summary: ['SUM', 'AVG'],
    },
    { name: 'createdAt', label: 'Created', kind: 'datetime', sortable: true },
  ],
  record: { rowKey: 'id', paging: 'paged', layouts: ['table', 'card'] },
  // 系统视图：开发或运维配置的基础视图，随定义部署，所有用户可见，只读，可另存
  views: [
    {
      id: 'pending',
      title: 'Pending',
      config: {
        kind: 'record',
        filter: {
          op: 'and',
          // enum 是封闭集合，因此提供 IN 与 NOT_IN 而不是 EQ：
          // 一个条件即表达“取其中之一”，两者互换时保留已选项。
          children: [{ field: 'status', operator: 'IN', value: ['PENDING'] }],
        },
        filterMode: 'simple',
        refresh: { interval: null },
        sort: [{ field: 'createdAt', direction: 'DESC' }],
        pageSize: 20,
        summaries: [{ field: 'amount', fn: 'SUM' }],
        layout: 'table',
        table: {
          columns: [
            { field: 'id' },
            { field: 'warehouse' },
            { field: 'amount' },
          ],
        },
        card: { title: 'id', fields: ['status', 'warehouse', 'amount'] },
      },
    },
  ],
};
```

### 2. 创建引擎

```ts
import { MemoryViewStore, ViewEngine } from '@ahoo-wang/fetcher-view-engine';

const engine = new ViewEngine({
  definitions: [orders],
  store: new MemoryViewStore(),
  // 来自 @ahoo-wang/fetcher-wow 的 Pick<QueryApi, 'paged' | 'cursor' | 'aggregate'>
  resolveSource: key => queryClients[key],
});
```

### 3a. 渲染默认工作台

```tsx
import '@ahoo-wang/fetcher-view-engine/styles.css';
import { RecordWorkbench } from '@ahoo-wang/fetcher-view-engine/ui';

export function OrdersPage() {
  return <RecordWorkbench engine={engine} definitionId="orders" />;
}
```

主题跟随宿主：祖先上带 `.dark` class 即为暗色；给 `ViewSurface` 传 `theme="light"` 或 `theme="dark"` 可以把某一处视图钉住。弹层 portal 到 `<body>` 时带着面从级联里解析出的模式，`.dark` 不必放在 `<html>` 上。

#### 定制主题

每个 token 都读一个宿主层变量，并以内置值兜底：在自己的 `:root` 上给亮色设 `--fve-<token>`、给暗色设 `--fve-dark-<token>` 即可，视图根与 Portal 到 `<body>` 的弹层都会读到——不必考虑选择器作用域，也不必考虑样式加载顺序。

```css
:root {
  --fve-primary: oklch(0.55 0.21 265deg);
  --fve-primary-foreground: oklch(0.99 0 0deg);
  --fve-dark-primary: oklch(0.75 0.15 265deg);
  --fve-dark-primary-foreground: oklch(0.21 0.05 265deg);
  --fve-radius: 0.375rem;
}
```

| Token                  | 用途                       | 亮色默认值                      | 暗色默认值                      |
| ---------------------- | -------------------------- | ------------------------------- | ------------------------------- |
| `background`           | 整体底色                   | `oklch(1 0 0deg)`               | `oklch(0.145 0 0deg)`           |
| `foreground`           | 默认文字                   | `oklch(0.145 0 0deg)`           | `oklch(0.985 0 0deg)`           |
| `card`                 | 卡片与面板底色             | `oklch(1 0 0deg)`               | `oklch(0.205 0 0deg)`           |
| `card-foreground`      | 卡片上的文字               | `oklch(0.145 0 0deg)`           | `oklch(0.985 0 0deg)`           |
| `popover`              | 弹层底色                   | `oklch(1 0 0deg)`               | `oklch(0.205 0 0deg)`           |
| `popover-foreground`   | 弹层内文字                 | `oklch(0.145 0 0deg)`           | `oklch(0.985 0 0deg)`           |
| `primary`              | 主操作填充                 | `oklch(0.205 0 0deg)`           | `oklch(0.922 0 0deg)`           |
| `primary-foreground`   | 主操作上的文字             | `oklch(0.985 0 0deg)`           | `oklch(0.205 0 0deg)`           |
| `secondary`            | 次操作填充                 | `oklch(0.97 0 0deg)`            | `oklch(0.269 0 0deg)`           |
| `secondary-foreground` | 次操作上的文字             | `oklch(0.205 0 0deg)`           | `oklch(0.985 0 0deg)`           |
| `muted`                | 弱化底色                   | `oklch(0.97 0 0deg)`            | `oklch(0.269 0 0deg)`           |
| `muted-foreground`     | 次要文字                   | `oklch(0.556 0 0deg)`           | `oklch(0.708 0 0deg)`           |
| `accent`               | 悬停与选中填充             | `oklch(0.97 0 0deg)`            | `oklch(0.269 0 0deg)`           |
| `accent-foreground`    | 强调态上的文字             | `oklch(0.205 0 0deg)`           | `oklch(0.985 0 0deg)`           |
| `destructive`          | 危险与删除                 | `oklch(0.577 0.245 27.325deg)`  | `oklch(0.704 0.191 22.216deg)`  |
| `success`              | 成功                       | `oklch(0.527 0.154 150.069deg)` | `oklch(0.792 0.209 151.711deg)` |
| `warning`              | 需要注意、不阻塞           | `oklch(0.555 0.163 48.998deg)`  | `oklch(0.828 0.189 84.429deg)`  |
| `info`                 | 中性提示                   | `oklch(0.546 0.245 262.881deg)` | `oklch(0.707 0.165 254.624deg)` |
| `border`               | 边框与分隔线               | `oklch(0.922 0 0deg)`           | `oklch(1 0 0deg / 10%)`         |
| `input`                | 输入与控件边框             | `oklch(0.62 0 0deg)`            | `oklch(1 0 0deg / 40%)`         |
| `ring`                 | 焦点环                     | `oklch(0.708 0 0deg)`           | `oklch(0.556 0 0deg)`           |
| `chart-1`              | 图表第 1 槽，蓝            | `#2a78d6`                       | `#3987e5`                       |
| `chart-2`              | 图表第 2 槽，橙            | `#eb6834`                       | `#d95926`                       |
| `chart-3`              | 图表第 3 槽，青            | `#1baf7a`                       | `#199e70`                       |
| `chart-4`              | 图表第 4 槽，黄            | `#eda100`                       | `#c98500`                       |
| `chart-5`              | 图表第 5 槽，品红          | `#e87ba4`                       | `#d55181`                       |
| `radius`               | 圆角基准，其余档位由它换算 | `0.625rem`                      | —                               |

`radius` 是暗色块唯一不重新声明的 token，因此 `--fve-radius` 对明暗两态同时生效，也就没有 `--fve-dark-radius`。

根默认涂 `--background`，因此嵌入在宿主卡片里的视图会露出自己的底色矩形；若想让宿主自己的底色透出来，把 `--fve-background` 设为 `transparent`（钉住暗色的视图再设 `--fve-dark-background`），根就不再在组件后面涂任何底色，而组件仍保留各自的卡片、弹层与输入框底色。

弹层——菜单、下拉列表、Popover、Tooltip 与对话框——都 portal 到 `<body>`，画在 `z-index: 50` 这一层，压在周围页面之上。宿主自己的 chrome 堆得比它还高时，改一个变量即可把它们一起抬起来：

```css
:root {
  --fve-popup-z-index: 2000;
}
```

### 3b. 或者自行组合 UI

```tsx
import {
  useOpenView,
  useViewRuntime,
  useFilterEditor,
  useRecordTable,
} from '@ahoo-wang/fetcher-view-engine/react';

export function OrdersPage({ instanceId }: { instanceId: string }) {
  const { runtime, loading } = useOpenView(engine, instanceId);
  if (!runtime) return loading ? <Spinner /> : <NotFound />;
  return <OrdersView runtime={runtime} />;
}

function OrdersView({ runtime }: { runtime: ViewRuntime }) {
  const state = useViewRuntime(runtime); // draft、applied、result、issues、dirty
  const filter = useFilterEditor(runtime); // 节点增删改、提交
  const table = useRecordTable(runtime); // 列、排序、选择、分页
  // 用这些控制器渲染任意布局，无需触及引擎内部。
}
```

### 只用内核，不用 React

```ts
import {
  builtinFieldKinds,
  compileRecord,
  projectRecord,
  validateRecord,
} from '@ahoo-wang/fetcher-view-engine';

const issues = validateRecord(orders, config, builtinFieldKinds);
if (issues.some(i => i.severity === 'error')) throw new Error('配置无效');

const query = compileRecord(
  orders,
  config,
  builtinFieldKinds,
  { now: new Date(), timeZone: 'Asia/Shanghai' },
  { index: 1 }, // Wow 页码从 1 开始
);
const page = await source.paged(query);
const view = projectRecord(orders, config, page);
```

## 概念

| 类型             | 职责                                                                                                                                                                                                                                                                                         | 所在   |
| ---------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------ |
| `ViewDefinition` | 字段、类型、操作符、记录与分析能力。由代码声明或生成，运行时不可编辑。                                                                                                                                                                                                                       | 代码   |
| `ViewConfig`     | `RecordViewConfig` / `AnalysisViewConfig` / `DashboardViewConfig` 判别联合，共享的 `FilterTree` 描述数据范围。它是意图模型：保存"最近 7 天"这样的语义而不是编译结果，也不含任何 UI 组件名。Analysis 覆盖 Wow 聚合协议全部能力；Dashboard 面板分数据面板与 Markdown、图片、链接三种内容面板。 | 数据   |
| `ViewInstance`   | 一份保存的 `ViewConfig`，加 id、标题、范围与不透明 `revision`。范围为系统、共享或个人。                                                                                                                                                                                                      | 存储   |
| `ViewRuntime`    | 一个打开的视图：草稿、已应用配置、结果、状态、选择。提供 `subscribe` / `getSnapshot`。                                                                                                                                                                                                       | 内存   |
| `ViewEngine`     | 定义、存储与已打开运行时的注册表；打开、保存、列表等命令的入口。                                                                                                                                                                                                                             | 内存   |
| `ViewStore`      | 八个方法的持久化端口。业务应用为自己的后端实现它。                                                                                                                                                                                                                                           | 应用   |
| `FieldKind`      | 一种字段类型的操作符、校验、编译与编辑器描述。                                                                                                                                                                                                                                               | 注册表 |

## 视图管理

- **生命周期。** 新建、保存、另存、改名、删除全部是 `ViewEngine` 命令，默认 UI 与自定义组合走同一路径。保存的只有配置，不含选择、页码与结果。
- **三种范围。** `system` 由开发或运维配置，是定义的基础视图与常用视图，所有用户可见、只读、可另存，可在 `definition.views` 中用代码声明，也可由服务端返回；`shared` 由有许可的业务用户创建并对同定义用户可见；`personal` 仅本人可见。
- **两个问题，一个值。** 三种范围是"给谁看"与"是不是用户配置的"两件事的合法组合：系统视图一定是共享视图，"个人的系统视图"写都写不出来。`audienceOf(scope)` 回答前者，`isSystemScope(scope)` 回答后者，从 Dashboard 的引用约束到侧栏分组都经由它们提问；用户创建时给的是 `ViewAudience` 而不是范围。
- **侧栏。** 视图按受众分组——个人在前、共享在后，系统视图落在共享组里并带 `system` 标签——每项以种类图标作前缀，因为一个 data 定义同时承载记录与分析视图；标题取自定义本身。`ViewInstanceSummary` 为此带上 `kind`：它是配置判别标签的投影，store 回答 `list` 时从自己存的配置里读出来。
- **许可。** `store.permissions()` 同步提供许可，只决定按钮可用性，服务端才是权威。无权修改的共享视图与系统视图都可另存到个人范围。
- **列表与偏好。** 列表、偏好、许可独立加载，互不阻塞；个人排序与默认视图存于 `ViewPreferences`，删除实例不改写偏好。
- **冲突与未知结果。** 版本冲突时二选一：重新加载或覆盖，另加"另存"；请求已发出但结果未知时可用同一 `requestId` 重试，草稿始终保留。
- **离开保护。** 有未保存草稿或未知写入的视图关闭前确认；导航不取消在途写入。

细节见 [docs/design/management.md](docs/design/management.md)。

## 入口

| 入口                             | 导出                                                                                                                                                                                                                                                                                                                                                                                  |
| -------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `@ahoo-wang/fetcher-view-engine` | 模型类型、纯内核（`validate*` / `compile*` / `project*`）、运行时、`ViewStore`、`MemoryViewStore`                                                                                                                                                                                                                                                                                     |
| `/react`                         | `useViewEngine`、`useOpenView`、`useViewRuntime`、`useViewList`、`useViewManager`、`useFilterEditor`、`useRecordTable`、`useAnalysisEditor`、`useDashboard`、`useSaveCommands`、`RecordActionSlots`                                                                                                                                                                                   |
| `/ui`                            | `RecordWorkbench`、`AnalysisWorkbench`、`DashboardWorkbench`、`ViewHeader`、`SaveActions`、`ViewManager`、`useLeaveGuard`、`EditorBand`、`FilterPanel`、`StatusStrip`、`AppliedBar`、`ResultToolbar`、`RowActions`、`RecordTable`、`RecordCards`、`RecordPagination`、`AnalysisEditor`、`AnalysisChart`、`DashboardGrid`、`MarkdownPanel`、`ImagePanel`、`LinksPanel`、`EmbeddedView` |
| `/styles.css`                    | 主题。显式导入；任何 JS 入口都不会引入 CSS，产物也不会在 `.fve-root` 之外绘制任何东西（preflight 与工具类在构建时收进根内），`scripts/verify-package.mjs` 在每次构建时核对这两点。                                                                                                                                                                                                    |

## 持久化

后端只需满足 `ViewStore` 这一个端口：

```ts
interface ViewStore {
  list(
    definitionId: string,
    signal?: AbortSignal,
  ): Promise<ViewInstanceSummary[]>;
  get(id: string, signal?: AbortSignal): Promise<ViewInstance>;
  create(
    input: Omit<ViewInstance, 'id' | 'revision'>,
    ctx: WriteContext,
  ): Promise<ViewInstance>;
  save(
    id: string,
    config: ViewConfig,
    revision: string,
    ctx: WriteContext,
  ): Promise<ViewInstance>;
  rename(
    id: string,
    title: string,
    revision: string,
    ctx: WriteContext,
  ): Promise<ViewInstance>;
  delete(id: string, revision: string, ctx: WriteContext): Promise<void>;
  getPreferences(
    definitionId: string,
    signal?: AbortSignal,
  ): Promise<ViewPreferences>;
  setPreferences(
    definitionId: string,
    prefs: ViewPreferences,
    ctx: WriteContext,
  ): Promise<ViewPreferences>;
  permissions?(definitionId: string): ViewPermissions;
}
```

两条规则保证一致性：

1. **乐观 revision。** 写入携带期望 `revision`，不匹配时抛出 code 为 `CONFLICT` 的 `ViewStoreError`，UI 提供"重新加载后覆盖"或"另存"。
2. **幂等 `requestId`。** 每个逻辑写入在 `WriteContext` 中携带一次 `requestId`，超时后的重试复用它，服务端去重。

本包提供 `MemoryViewStore`，用于测试、示例与只查询不持久化的场景。业务应用用自己的 fetcher 针对自己的 API 实现 `ViewStore`，HTTP 状态码到 `ViewStoreError.code` 的映射在应用侧完成。授权、可见性过滤与去重是服务端职责，`permissions` 只决定按钮可用性。

### 措辞与语言

模型只带 `code` 与 `params`，措辞归 `/ui`。`defaultMessages`（即 `en`）给每个 issue 一句英文，`ViewSurface` 与每个工作台的 `messages` 按 key 合并在已生效的措辞之上——改写与本地化是同一个入口；在应用外层放一个 `MessagesProvider`，就能对其中所有视图一次设定。包里另带一份逐键对应的简体中文 `zhCN`：整份交给 `messages` 即可，要改其中几句就铺开再覆盖（`{ ...zhCN, 'label.filter.apply': '确定' }`）。

值按字段显示：枚举显示选项的标签，`datetime`／`date` 经 `Intl.DateTimeFormat` 格式化，日期直方图的键显示为它起始的年、季度、月或日。`locale` 决定这些值用什么语言显示，缺省为运行环境的语言；它和 `messages` 是同一个选择，一个管文字，一个管值：

```tsx
import { RecordWorkbench, zhCN } from '@ahoo-wang/fetcher-view-engine/ui';

<RecordWorkbench
  engine={engine}
  definitionId="orders"
  messages={{ ...zhCN, 'label.filter.apply': '确定' }}
  locale="zh-CN"
/>;
```

时间按引擎的时钟 `environment.timeZone` 显示：相对日期"今天"按它解析，未声明 `timeZone` 的日期直方图也按它切桶，所以一行按什么时钟被筛选、被分组，就按什么时钟显示。

找不到的 key 会沿点号回退到最长的已知前缀，再退回 key 本身，因此不会渲染空白。新增 issue code 却没有对应措辞时，测试会失败。组件能取的 key 是 `MessageKey` 这个联合类型，删掉一个键就是编译错误；宿主自己的 `messages` 仍是开放的字符串映射。

## 扩展点

| 变化轴   | 机制                                                                                                                   |
| -------- | ---------------------------------------------------------------------------------------------------------------------- |
| 字段类型 | 注册 `FieldKind`（操作符、校验、编译到 `FilterExpression`、编辑器描述），并在 `/ui` 以同一 id 注册对应的编辑器与单元格 |
| 数据来源 | `resolveSource(key)` 返回 Wow 查询客户端                                                                               |
| 持久化   | 实现 `ViewStore`                                                                                                       |
| 动作     | 向工作台传 `actions`：`global`、`bulk`、`row` 三个渲染函数。动作是代码，由宿主交出来，不进配置、不入库                 |
| 外观     | CSS 变量与主题文件；通过组合 `/react` 钩子替换组件                                                                     |

内置字段类型：`string`、`number`、`boolean`、`date`、`datetime`、`enum`、`reference`。

## 分层

```text
model → filter → record | analysis | dashboard → runtime → react → ui
store → model
```

`validateDefinition` 在引擎注册定义时校验一次：字段名是否符合 Wow 查询语法、id 是否唯一且不含 `:`、能力是否足以让 `default*Config` 构造出合法配置、每个系统视图是否通过它自己的内核。含 error 的定义仍留在注册表里，但在使用处被拒绝——代码里的错误因此表现为一条可读的 issue，而不是用户打开视图时的 `TypeError`。

六条依赖规则由架构测试强制：`model` 不引入任何目录；`filter` 只引入 `model`；`record`、`analysis`、`dashboard` 只引入 `model` 与 `filter`；`runtime` 不引入 `react` 与 `ui`；`store` 只引入 `model`；`react` 不引入 `ui`。`store` 及以下不含 React 与 DOM。只使用 Wow 未弃用的、基于 `FilterExpression` 的查询 API。

## 不做

视图种类插件、定义 CRUD 后端、写入回执核对与读屏障、并发与页大小以外的资源预算、SSR 预载、通用 region 或事件总线、跨页全选、单元格编辑、Dashboard 嵌套。

## 开发

```bash
pnpm --filter @ahoo-wang/fetcher-view-engine test          # 单元测试、覆盖率与三个 tsc 工程
pnpm --filter @ahoo-wang/fetcher-view-engine build         # 构建，并核对发布出去的入口
pnpm --filter @ahoo-wang/fetcher-view-engine test:package  # 入口可导入、核心入口无 DOM 类型、JS 不引入 CSS
pnpm storybook                                             # 每个界面的每种状态，见导航「View Engine」
```

`examples/` 下是两个只依赖公开合同、不依赖内部实现的消费者：
`PlainRecordWorkbench.tsx` 用无样式 HTML 跑通整个闭环，
`FetcherViewStore.ts` 用 `@ahoo-wang/fetcher` 把 `ViewStore` 端口实现在 HTTP 上。

`@ahoo-wang/fetcher-viewer` 已弃用，新项目使用本包。两者模型与 API 不同。
