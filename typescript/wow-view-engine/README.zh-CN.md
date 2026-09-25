# Wow View Engine

> **状态：开发中，不承诺兼容。** Record、Analysis、Dashboard 三种视图与它们的嵌入，以及下文的 `/react` 控制器与 `/ui` 组件均已在包内。任何导出都还可能改形，改动不带兼容层；[docs/design/](docs/design/) 是唯一的依据，本页只说现在的 API。

**Wow View Engine 是面向 Wow 业务应用的数据视图引擎。** 业务应用用代码声明一份数据"能被怎样观察"：字段、类型、操作符、可用的维度与指标。用户在界面上决定"这一次怎样观察"：筛选、列、排序、维度与指标、图表、面板组合。引擎把这种观察方式编译成 Wow 查询、执行、渲染，并把有价值的观察方式保存下来供下次直接打开。

它是 `@ahoo-wang/wow-client` 之上的展示层，也是 `@ahoo-wang/fetcher-viewer` 的继任者。

## 解决什么问题

业务系统里的大多数页面是同一种页面：一张列表，带筛选、排序、分页，偶尔加一张统计图。每个业务对象（订单、库存、客户、工单）都要写一套。运营每提一次"再加一个筛选条件""按仓库拆开看一下""把这几张表放到一个概览页"，都要改代码、排期、发版。

数据没有变，变的只是观察方式。问题在于观察方式被写死在页面代码里：用户不能自己调整，研发被重复劳动占满，产品把展示层的每次调整都当成需求。

## 目标

- **用户目标。** 在已声明的能力范围内自己调整数据范围、组织方式与呈现方式，把常用的观察方式保存下来，下次一键重开。
- **研发目标。** 一个业务对象接入一次，即一份定义加一个查询客户端，之后记录视图、分析视图、仪表盘三类视图不再需要写页面；筛选编辑、查询协调、结果呈现、保存恢复与冲突处理由引擎统一提供。
- **演进目标。** 新增业务对象只增加定义，不在引擎里加业务分支；自定义布局通过无样式钩子复用同一套行为，不复制一份逻辑。

## 价值

| 对象     | 得到什么                                                                                                                       |
| -------- | ------------------------------------------------------------------------------------------------------------------------------ |
| 业务用户 | 不等排期就能得到需要的视图；常用视图保存后直接打开；同一份数据既能看记录，也能按维度看指标，还能看概览                         |
| 研发     | 列表类页面从"每个对象一套"变为"每个对象一份定义"；筛选、分页、排序、保存、冲突只实现一次；定义可由 generator 从 Wow 元数据生成 |
| 产品     | 支持范围内的展示调整不再是需求而是配置；"保存与共享视图"可以作为产品能力交付给客户                                             |

## 场景

| 场景                       | 视图                            | 用户做什么                                                                             |
| -------------------------- | ------------------------------- | -------------------------------------------------------------------------------------- |
| 仓管每天找待出库订单       | Record                          | 筛选状态为待出库，按创建时间排序，只显示需要的列，保存为"今日待出库"                   |
| 主管比较各仓库积压         | Analysis                        | 按仓库分组、计数并合计金额，切换为柱状图                                               |
| 运营周会看整体情况         | Dashboard                       | 把上面两个视图放进一个面板页，用全局时间范围同时约束两者                               |
| 业务页面里嵌一块数据       | EmbeddedView、EmbeddedDashboard | 开发者把已保存视图嵌入订单详情页，或把一块仪表盘锁定在这位客户上嵌进客户页，不带工作台 |
| 运维为新业务对象配基础视图 | 系统视图                        | 在定义中声明"全部""待处理""本周新增"三个视图，用户打开即有可用视角，再按需另存         |
| 客户在租户内自定义报表     | 全部                            | 客户保存并共享自己的视图，供应商无需为此发版                                           |

## 不是什么

不是数据库或计算后端，不是权限系统，不是通用低代码页面搭建器，也不是 BI 建模工具。数据、聚合能力与授权由业务服务提供，引擎只负责让观察方式可靠地运行。

## 安装

该包尚未在公共 registry 首次发布，稳定版发布脚本会刻意跳过它。在此之前请通过本工作区（`"@ahoo-wang/wow-view-engine": "workspace:^"`）或本地 `pnpm pack` 产物使用。发布后的安装方式为：

```bash
pnpm add @ahoo-wang/wow-view-engine @ahoo-wang/wow-client
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

<!-- typecheck: file=orders.ts -->

```ts
import type { ViewDefinition } from '@ahoo-wang/wow-view-engine';

export const orders: ViewDefinition = {
  id: 'orders',
  title: 'Orders',
  kind: 'data',
  source: 'orders',
  fields: [
    { name: 'id', label: 'Order', kind: 'string', sortable: true },
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
  // 行键必须可排序：每条记录查询的排序都以它收尾，排序值相同的行翻页时才不重不漏
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

<!-- typecheck-context
import { orders } from './orders';
import type { QueryApi } from '@ahoo-wang/wow-client';
declare const queryClients: Record<string, Pick<QueryApi<any>, 'paged' | 'cursor' | 'aggregate'>>;
-->

```ts
import { MemoryViewStore, ViewEngine } from '@ahoo-wang/wow-view-engine';

const engine = new ViewEngine({
  definitions: [orders],
  store: new MemoryViewStore(),
  // 来自 @ahoo-wang/wow-client 的 Pick<QueryApi, 'paged' | 'cursor' | 'aggregate'>
  resolveSource: key => queryClients[key],
});
```

**导出的文件缺省中和公式。** CSV 会离开页面，在表格软件里被打开，打开的人常常不是导出的人；所以每一处导出——记录视图的行、分析的「导出数据…」——都把文本以 `=`、`+`、`-`、`@`、制表符或回车开头的格子写成前面带一个 `'`（OWASP CSV Injection），表头也算。值是数的格子、以及文本就是一个纯数的格子（如 `-12.5`）照写：表格软件把它读成数，从不求值。文件不进表格软件时可以关掉：`limits: { ...DEFAULT_RUNTIME_LIMITS, exportNeutralizeFormulas: false }`；自己调用 `serializeCsv` 时传 `{ neutralizeFormulas: false }`。

**失败交给你的监控。** 查询、存储调用、导出、渲染或图表失败时，界面在出事的地方照旧说明；要记日志或送监控，就给环境一个 `onError`。每次失败它被告知一次，带着抛出来的原物和出事的位置；它抛什么都会被吞掉，不给它就什么也不记。

<!-- typecheck-context
import { orders } from './orders';
import type { QueryApi } from '@ahoo-wang/wow-client';
declare const queryClients: Record<string, Pick<QueryApi<any>, 'paged' | 'cursor' | 'aggregate'>>;
declare function sendToMonitoring(record: Record<string, unknown>): void;
-->

```ts
import { MemoryViewStore, ViewEngine } from '@ahoo-wang/wow-view-engine';
import { browserRuntimeEnvironment } from '@ahoo-wang/wow-view-engine/react';

const engine = new ViewEngine({
  definitions: [orders],
  store: new MemoryViewStore(),
  resolveSource: key => queryClients[key],
  // 不用 React 时写 `defaultRuntimeEnvironment({ onError })`。
  environment: browserRuntimeEnvironment({
    onError: ({ kind, error, context }) =>
      sendToMonitoring({ kind, error, ...context }),
  }),
});
```

| `kind`   | 何时告知                                                                          | `context.operation`                                             |
| -------- | --------------------------------------------------------------------------------- | --------------------------------------------------------------- |
| `query`  | 视图的查询失败，或它旁边的查询：汇总行、分析的合计、条件的取值                    | `query`、`summaries`、`totals`、`split`、`record`、`candidates` |
| `store`  | 一次 `ViewStore` 调用被拒：列表、打开、写入（每次重试各一次，同一个 `requestId`） | 端口的方法名：`list`、`get`、`create`、`save`……                 |
| `export` | 导出的行拉不下来，或文件做不出来、交不出去                                        | `fetch`、`deliver`、`image`                                     |
| `render` | 工作台或嵌入里的某一块画的时候抛错——常常是你的动作槽位                            | `render`                                                        |
| `chart`  | 图表库没加载到，或绘制时抛错                                                      | `load`、`draw`                                                  |

`context` 在知道时还写明是哪个视图——`definitionId`、`instanceId`、`runtimeId`——`render` 与 `chart` 另有 `boundary`、`panelId` 与 React 的 `componentStack`。被叫停的请求（被下一个顶掉、被取消）不算失败，不告知。工作台、网格与嵌入上的 `onRenderFailure` 照旧：它是那一块界面自己的回调，拿到的是同一个 `error`；`onError` 是整个引擎的。引擎的 `onIssue` 只管没有抛出物的发现，比如定义准入。

### 3a. 渲染默认工作台

<!-- typecheck-context
import { ViewEngine } from '@ahoo-wang/wow-view-engine';
declare const engine: ViewEngine;
-->

```tsx
import '@ahoo-wang/wow-view-engine/styles.css';
import { DataWorkbench } from '@ahoo-wang/wow-view-engine/ui';

export function OrdersPage() {
  return <DataWorkbench engine={engine} definitionId="orders" />;
}
```

主题跟随宿主：祖先上带 `.dark` class 即为暗色；给 `ViewSurface`（或工作台、嵌入组件）传 `theme="light"` 或 `theme="dark"` 可以把某一处视图钉住，传 `theme="system"` 则跟随读者系统的 `prefers-color-scheme` 并随它实时切换，适合自己没有明暗开关的页面。弹层 portal 到 `<body>` 时带着面从级联里解析出的模式，`.dark` 不必放在 `<html>` 上。预设也是这样选的，见[预设](#预设)。

#### 开着哪个视图，与宿主的路由

一个数据定义同时装着它的记录视图与分析视图，`DataWorkbench` 把它们列在一张列表里：用户在一张订单表与一张订单图之间切换，就像在任意两个视图之间切换一样，「新建视图」会先问要建哪一种。宿主要一页只有一种，就收窄——`kinds={['record']}`——另一种在这一页既不列出也打不开。

一个人打开的视图，就是他可以发出去的一条链接。两个工作台因此都收 `instanceId` 与 `onInstanceChange`——进出你的路由的两个方向，`DataWorkbench` 与 `DashboardWorkbench` 契约完全一致。

<!-- typecheck-context
import { ViewEngine } from '@ahoo-wang/wow-view-engine';
declare const engine: ViewEngine;
import { DataWorkbench } from '@ahoo-wang/wow-view-engine/ui';
declare function useSearchParam(key: string): [string | null, (value: string | null) => void];
-->

```tsx
export function OrdersPage() {
  // 你的路由给什么都行：path 参数、query、hash。
  const [view, setView] = useSearchParam('view');
  return (
    <DataWorkbench
      engine={engine}
      definitionId="orders"
      instanceId={view}
      onInstanceChange={setView}
    />
  );
}
```

`instanceId` 是受控的，语义照 input 的 `value`：

- **不传**——非受控形态：开着哪个由工作台自己拿着，从用户的有效默认开始；
- **传了**——一个视图 id，或 `null` 表示那个有效默认：由你说开哪个，此后每一次变化都打开它所指的视图。`null` 是一个值，不是"没有值"；
- `onInstanceChange(id)` 报的是此刻开着的那个，用同一套说法——`null` 在这里同样指有效默认——所以出来的值可以原样传回去。

它**收敛**而不是**渲染**。视图里装着未保存的草稿，所以推进来的值与侧栏上的一次点击走同一道离开守卫：后动的那一方说话，另一方跟上。若守卫问过而用户选择留下，工作台会把**留下的那个**报回来，你的路由不会停在一个没开着的视图上。完整参照见 `examples/PlainRecordWorkbench.tsx`，连浏览器后退一并覆盖。

#### 搭仪表盘，以及打开面板背后的视图

仪表盘平时是读的：能保存它的人按「编辑」之前，板上什么都不动。编辑中顶上一条编辑条，有「撤销」「重做」、「添加」（数据：已保存的视图，或只属于这块板的新分析；内容：标题、文字、图片、链接）、「添加筛选」（加一个仪表盘筛选，再接到面板上）、「取消」与「保存」，其下的标签栏可以加、改名、排序、删除标签页——面板随改随跑，只有「保存」才存下，走的就是标题栏那次保存。系统仪表盘只读，只有「另存为」。

离开这块板的每一条路都经过你给的一个路由 `onNavigate(to)`——包本身从不碰地址。不给，就一条都没有：

- 面板「⋯」里的「在工作台中打开」：`{ kind: 'view', definitionId, instanceId, scopeFilter, filter, from }`，面板显示的已保存视图，已经换成那个视图自己的字段名。不归读者的——板子的固定范围，以及页面持有的锁定或隐藏的筛选——是 `scopeFilter`：视图在它之下跑，是作用域，到了工作台谁也拿不掉。读者在板上设的是 `filter`：成为视图自己的条件，于是视图开着就是「已修改」，每一条都能拿掉——全拿掉就回到保存时的样子，不再「已修改」——「还原」一次全拿掉。板内自建的分析交的是 `{ kind: 'unsaved', … }`，读者的值在它的条件里，页面持有的是它的 `scopeFilter`。
- **点一组**（柱、扇区、表格的一行）打开分析视图的追问菜单（查看这些记录、按其他维度细分、只看这一组）。每一项都是一个没保存的视图：`{ kind: 'unsaved', definitionId, title, config, scopeFilter, named, from }`，读者的值与这一组已经在它的条件里，页面持有的是作用域；`named` 是标题里那一组，读者把它拿掉后工作台的名字就不再带它。
- 每一条路交法相同，`from` 是回去的路：把目标交给 `DataWorkbench` 的 `handOver` 属性（每个新对象打开一次），再给同一个 `onNavigate`，工作台就在标题栏下画「返回〈仪表盘〉」，按下交出 `{ kind: 'dashboard', definitionId, instanceId, filters, tab }`——离开时的那块板；只有读者在板子交来的之外又改过，才先问一句。宿主不必自己画返回键。
- **「点击时…」**（编辑中）：面板也可以改为用点中的一组设置仪表盘筛选（交叉筛选——不需要路由；其余接线的面板跟着筛，被点的面板只标出这一组，再点一次撤销），或者去另一个已保存的视图（`{ kind: 'view' }`，交法同上，这一组在它自己的条件里）、另一块仪表盘，或你的一个页面（`{ kind: 'url', url }`，`{{字段}}` 换成点中的值并编码）。
- **另一块仪表盘**：作者逐个列出目的板的筛选，每个映射到这块面板的一个维度、这块板上一个同类型的筛选，或者不带——不按名字猜。点一组时交给你 `{ kind: 'dashboard', definitionId, instanceId, filters }`：`filters` 就是那块板的 `DashboardFilters`，映射了的筛选是这一组的值或这块板那个筛选点的那一刻的值，其余是它们的默认值。把它交给 `DashboardWorkbench` 的 `initialFilters`（或 `ViewEngine.open` 的 `filters`）——它是读者的，不写进任何一块板的配置。映射失效（筛选或维度被删、那块板被删）时面板上挂 warning，点一组改为打开追问菜单。

<!-- typecheck: skip — 并列的两个 JSX 元素，各自是一个示例 -->

```tsx
<DashboardWorkbench
  engine={engine}
  definitionId="overview"
  onNavigate={to => router.push(routeFor(to))}
/>

<DataWorkbench
  engine={engine}
  definitionId={fromRoute.definitionId}
  handOver={fromRoute}
  onNavigate={to => router.push(routeFor(to))}
/>
```

`DashboardWorkbench`、`EmbeddedDashboard` 与 `EmbeddedView` 收同一个属性。

#### 嵌入一个视图或一块仪表盘

业务页面要摆出别人已经定好的观察，就嵌入它：只有结果——没有视图列表、没有条件编辑器、没有保存。**嵌入一律不写**：不存视图、不存板子、也不写偏好，读者在上面做的只影响这一次观看。定义视图、搭板子、保存都在工作台里；要让读者在页面上搭板子，就嵌 `DashboardWorkbench`。入口按资源分两个，与工作台的拆法一样：记录或分析视图用 `EmbeddedView`，仪表盘用 `EmbeddedDashboard`。各自只画自己那一种，给错了会直说。

<!-- typecheck: skip — 并列的两个 JSX 元素，各自是一个示例 -->

```tsx
import {
  EmbeddedDashboard,
  EmbeddedView,
} from '@ahoo-wang/wow-view-engine/ui';

// 订单页：这位客户最近的运单，照存下的样子读。
<EmbeddedView
  engine={engine}
  instanceId="orders-pending"
  scopeFilter={{
    op: 'and',
    children: [{ field: 'customer', operator: 'IN', value: [customerId] }],
  }}
/>

// 客户页：客户的那块板，锁定在这位客户上；时间归读者。
<EmbeddedDashboard
  engine={engine}
  instanceId="customer-board"
  interaction="interactive"
  filterModes={{ customer: 'locked' }}
  pageValues={{
    values: { customer: { items: [{ id: customerId, label: customerName }] } },
  }}
  initialFilters={readFromAddress()}
  onFiltersChange={writeToAddress}
  onNavigate={to => router.push(routeFor(to))}
/>
```

**读者能走多远是明确的一档**：`interaction`，缺省 `static`。两档都不存任何东西：

| 档            | `EmbeddedView`（记录、分析）                                                                                 | `EmbeddedDashboard`                                                                                                |
| ------------- | ------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------ |
| `static`      | 结果与它是在什么条件下取来的；表头不能排序、不能拖宽、没有分页、哪儿也去不了                                 | 面板照画、什么都不回应：没有追问菜单、没有联动、没有「⋯」；每个筛选读作它的值，没有控件                            |
| `interactive` | 表头排序、拖宽、翻页；分析的表格｜图表切换、按一组追问；「在工作台中打开」；开了 `expandable` 时「铺满屏幕」 | 改筛选（搜索类的筛选也是）、追问菜单、交叉筛选、自定义目的地、「在工作台中打开」；开了 `expandable` 时「铺满屏幕」 |

离开嵌入的每一条路都经宿主的**一个**路由 `onNavigate(to)`——与仪表盘工作台交出的同一个 `ViewNavigation`；不给就一条也没有。

**开关**——关掉就是不存在，不是置灰。档位是上限，开关在档位之内：搜索、导出、铺满屏幕是读者的控件，`static` 一档里开了也不起作用：

| 属性                        | 缺省      | 做什么                                                                                                      |
| --------------------------- | --------- | ----------------------------------------------------------------------------------------------------------- |
| `withTitle`                 | 关        | 画出视图或仪表盘的标题                                                                                      |
| `headingLevel`              | `2`       | 嵌入所标的标题级别：自己的标题在这一级，仪表盘的面板在它下一级（没有标题时就在这一级）。`h1` 归宿主页面     |
| `withPanelTitles`（仪表盘） | 开        | 关掉时面板标题只留给读屏                                                                                    |
| `withSearch`（记录）        | 关        | 视图的搜索框，定义声明了搜索字段才有；只在 `interactive` 一档                                               |
| `withExport`（记录）        | 关        | 导出按钮与窗口，行可以勾选；只在 `interactive` 一档                                                         |
| `withExport`（仪表盘）      | 关        | 面板「⋯」里的「导出数据…」，同一个导出窗口；只在 `interactive` 一档                                         |
| `autoRefresh`               | 开        | 按作者存的间隔自己刷新；关掉就从不自己刷新                                                                  |
| `openInWorkbench`           | 开        | `interactive` 一档里给不给「在工作台中打开」                                                                |
| `expandable`                | 关        | `interactive` 一档首行最后的「铺满屏幕」：与工作台一样就地铺开，Esc 收起                                    |
| `size`                      | `content` | `content` 按内容定高、有上限（记录表格在 `--fve-record-table-max-h` 里滚）；`fill` 填满容器——整页嵌入、大屏 |

**仪表盘的筛选逐个三态**（`filterModes` 按筛选名，时间粒度用 `groupingMode`）：`adjustable`——在筛选条上、归读者，这一次看时可调，与工作台一样，也是缺省；`locked`——在筛选条上读作它的值，带一把锁、没有控件；`hidden`——不在筛选条上，照样收窄接上的面板。锁定与隐藏由 runtime 持有，读者做什么——改值、「清空」、点一组交叉筛选——都改不了它们。它们的值是页面自己的 `pageValues`（没写就是默认值）：从第一次查询起就在，并**跟着这个属性变**——客户页换到下一位客户，板子跟着换。读者的筛选是宿主地址里的那一份：`initialFilters` 与 `onFiltersChange`，读法、报法与 `DashboardWorkbench` 相同。**锁定与隐藏的值从不走地址**：`initialFilters` 里写到它们的条目不算，`onFiltersChange` 只报读者能设的筛选——否则读者改一下地址就换了客户，与「锁定」正相反。板子不收条件树（`EmbeddedDashboard` 没有 `scopeFilter`）：要收窄它，在板上声明那个筛选，再锁定或隐藏它。

**记录面板上的宿主命令**（`recordPanel(panel)`，`EmbeddedDashboard` 与 `DashboardWorkbench` 都有）：按面板返回记录工作台同一套 `actions`——行动作两档都画，成批命令只在能勾选的一档（`interactive`）——以及 `useBulkCommand`，面板在行上方说它的进度；每个上下文的 `refresh` 只重跑那块面板。它们是宿主对自己服务的命令，嵌入照旧什么也不写。记录面板也说一共多少条，`interactive` 一档能翻页。

**锁定不是安全边界。** 页面锁定的条件是在浏览器里拼进查询的，只保证读者在界面上改不了、在这里看不到别的。改一下页面脚本、直接调接口，就能问到别的客户。租户、归属与权限必须由 Wow 后端强制——对外的页面尤其如此。本包是宿主进程里的库，不照搬 Metabase 的 iframe、签名令牌或 SSO：身份与权限属于宿主与后端。

铺满屏幕：开了 `expandable`，可交互的嵌入自己画这颗开关。想把它放在宿主自己的 chrome 里，或让一块 `static` 的大屏铺满，就传一个 `ref`，用 `useViewExpansion` 指向它。

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

| Token                       | 用途                                                                   | 亮色默认值                             | 暗色默认值                     |
| --------------------------- | ---------------------------------------------------------------------- | -------------------------------------- | ------------------------------ |
| `background`                | 整体底色                                                               | `oklch(1 0 0deg)`                      | `oklch(0.145 0 0deg)`          |
| `foreground`                | 默认文字                                                               | `oklch(0.145 0 0deg)`                  | `oklch(0.985 0 0deg)`          |
| `card`                      | 卡片与面板底色                                                         | `oklch(1 0 0deg)`                      | `oklch(0.205 0 0deg)`          |
| `card-foreground`           | 卡片上的文字                                                           | `oklch(0.145 0 0deg)`                  | `oklch(0.985 0 0deg)`          |
| `popover`                   | 弹层底色                                                               | `oklch(1 0 0deg)`                      | `oklch(0.205 0 0deg)`          |
| `popover-foreground`        | 弹层内文字                                                             | `oklch(0.145 0 0deg)`                  | `oklch(0.985 0 0deg)`          |
| `primary`                   | 主操作填充                                                             | `oklch(0.205 0 0deg)`                  | `oklch(0.922 0 0deg)`          |
| `primary-foreground`        | 主操作上的文字                                                         | `oklch(0.985 0 0deg)`                  | `oklch(0.205 0 0deg)`          |
| `secondary`                 | 次操作填充                                                             | `oklch(0.97 0 0deg)`                   | `oklch(0.269 0 0deg)`          |
| `secondary-foreground`      | 次操作上的文字                                                         | `oklch(0.205 0 0deg)`                  | `oklch(0.985 0 0deg)`          |
| `muted`                     | 弱化底色                                                               | `oklch(0.97 0 0deg)`                   | `oklch(0.269 0 0deg)`          |
| `muted-foreground`          | 次要文字                                                               | `oklch(0.556 0 0deg)`                  | `oklch(0.708 0 0deg)`          |
| `accent`                    | 悬停与选中填充                                                         | `oklch(0.97 0 0deg)`                   | `oklch(0.269 0 0deg)`          |
| `accent-foreground`         | 强调态上的文字                                                         | `oklch(0.205 0 0deg)`                  | `oklch(0.985 0 0deg)`          |
| `sidebar`                   | 导航列底色                                                             | `oklch(0.97 0 0deg)`                   | `oklch(0.205 0 0deg)`          |
| `sidebar-foreground`        | 导航列上的文字                                                         | `oklch(0.145 0 0deg)`                  | `oklch(0.985 0 0deg)`          |
| `sidebar-accent`            | 导航列的悬停项                                                         | `oklch(0.922 0 0deg)`                  | `oklch(0.279 0 0deg)`          |
| `sidebar-accent-foreground` | 悬停项上的文字                                                         | `oklch(0.205 0 0deg)`                  | `oklch(0.985 0 0deg)`          |
| `sidebar-border`            | 导航列的边                                                             | `oklch(0.898 0 0deg)`                  | `oklch(1 0 0deg / 20%)`        |
| `destructive`               | 危险与删除                                                             | `oklch(0.505 0.213 27.518deg)`         | `oklch(0.76 0.15 22.216deg)`   |
| `success`                   | 成功                                                                   | `oklch(0.448 0.119 151.328deg)`        | `oklch(0.792 0.15 151.711deg)` |
| `warning`                   | 需要注意、不阻塞                                                       | `oklch(0.473 0.137 46.201deg)`         | `oklch(0.828 0.15 84.429deg)`  |
| `border`                    | 边框与分隔线                                                           | `oklch(0.922 0 0deg)`                  | `oklch(1 0 0deg / 20%)`        |
| `input`                     | 输入与控件边框                                                         | `oklch(0.62 0 0deg)`                   | `oklch(1 0 0deg / 40%)`        |
| `ring`                      | 焦点环                                                                 | `oklch(0.62 0 0deg)`                   | `oklch(0.66 0 0deg)`           |
| `destructive-foreground`    | 危险填充上的文字（推导）                                               | `background`                           | `background`                   |
| `row-hover`                 | 悬停的行（推导）                                                       | `muted` 与 `background` 各半           | 同左                           |
| `quiet-foreground`          | 汇总行里弱的那一半（推导）                                             | `foreground` 的 70%                    | 同左                           |
| `pin-shadow`                | 冻结列的柔边；归明暗，不归预设                                         | `oklch(0 0 0deg / 12%)`                | `oklch(1 0 0deg / 10%)`        |
| `chart-1`                   | 图表第 1 槽，蓝                                                        | `#2675d3`                              | `#3987e5`                      |
| `chart-2`                   | 图表第 2 槽，橙                                                        | `#eb6834`                              | `#d95926`                      |
| `chart-3`                   | 图表第 3 槽，青                                                        | `#1baf7a`                              | `#199e70`                      |
| `chart-4`                   | 图表第 4 槽，黄                                                        | `#eda100`                              | `#c98500`                      |
| `chart-5`                   | 图表第 5 槽，品红                                                      | `#e87ba4`                              | `#d55181`                      |
| `chart-6`                   | 图表第 6 槽，绿                                                        | `#008300`                              | `#008300`                      |
| `chart-7`                   | 图表第 7 槽，紫                                                        | `#4a3aa7`                              | `#9085e9`                      |
| `chart-8`                   | 图表第 8 槽，红                                                        | `#e34948`                              | `#e66767`                      |
| `radius`                    | 圆角基准，其余档位由它换算                                             | `0.625rem`                             | —                              |
| `text-ui`                   | 正文之下唯一的那一档字号                                               | `0.8125rem`                            | —                              |
| `font-sans`                 | 字体，一条系统字体栈                                                   | 不设：页面的                           | —                              |
| `chart-patterns`            | 图表系列上的花纹：`on`、`off`，或不设／`auto` 跟随读者的「提高对比度」 | 不设                                   | —                              |
| `brand`                     | `brand` 预设派生主色与淡色所用的那一个颜色                             | 不设                                   | `brand`                        |
| `preset-density`            | 预设推荐的密度：`-1`、`0` 或 `1`（归预设；宿主用 `data-fve-density`）  | 不设                                   | —                              |
| `rise`                      | 上升，按方向                                                           | `success`（见[涨跌色](#涨跌色升与降)） | `success`                      |
| `fall`                      | 下降，按方向                                                           | `destructive`                          | `destructive`                  |
| `shadow-sm`、`-md`、`-lg`   | 三档浮起（卡片浮起、弹层、拖动中的面板）                               | Tailwind 的 `shadow-sm`／`-md`／`-lg`  | 同左                           |
| `canvas`                    | 分组底：看板与宿主按卡片排的页面站在它上面（`bg-canvas`）              | `background`                           | `background`                   |
| `card-edge`                 | 卡片的一圈边：看板面板、记录卡片                                       | `foreground` 的 10%                    | 同左                           |
| `card-shadow`               | 卡片离开底的浮起                                                       | 无（`0 0 #0000`）                      | 同左                           |
| `control`                   | 以文字或图标自明的控件的静止填色：筛选条、分段控件                     | 不设：各控件原样                       | 不设：各控件原样               |
| `control-edge`              | 这类控件的边（装着输入框的筛选条仍用 `input`）                         | 不设：各控件原样                       | 不设：各控件原样               |
| `control-thumb`             | 分段控件按下的那一项，轨道上的滑块                                     | 不设：`muted`                          | 不设：`muted`                  |
| `title-weight`              | 视图标题与卡片标题的字重                                               | `500`                                  | —                              |

字体归宿主：面上写的是 `font-family: var(--fve-font-sans)`，不设时这条声明无效，`font-family` 照旧从页面继承。把 `--fve-font-sans` 设成一条系统字体栈，视图就用它；图表读计算出来的字体，跟着变。它没有暗色那一半。

五个 `sidebar*` 用的是 shadcn 自己的命名，指的是工作台放视图列表的那条导航列——已经在给 shadcn 侧栏配主题的宿主，用同一组词就能配这一条。只声明这条列真正画到的那五个。列里当前打开的那一项是 `background` 叠在 `sidebar` 上，悬停是 `sidebar-accent`，三者因此必须互相分得开：其中两个解析成同一档灰，这份列表就没有「你在这里」了。

`input` 与 `ring` 要守一条别的 token 不必守的线：控件的边与焦点标记按 WCAG 1.4.11 要与身后的颜色有 3:1，两个默认值在明暗两态都调到过线（Storybook 的对比度故事在浏览器里量）。它们刻意是独立的值。常见的 shadcn 品牌主题会把它们改指别处——`--ring: var(--primary)`、`--input: var(--border)`——这就把 3:1 交给了一个品牌色和一档分隔线灰，而它们都不欠这条线：未勾的复选框成了一根细线，获焦的行只剩一层淡色。设 `--fve-primary` 或 `--fve-border` 不会动到它们；设了 `--fve-ring`／`--fve-input`（或 `--fve-dark-` 那一半）的宿主，同样欠自己的主题这条 3:1，应当自己量。

有几个 token 是推导出来的：汇总行的弱字 `quiet-foreground` 是 `foreground` 的七成，`destructive-foreground` 是 `background`，宿主改了 `--fve-foreground` 或 `--fve-background`，它们跟着变。每一个仍能单独设（`--fve-quiet-foreground`、`--fve-destructive-foreground` 与各自的 `--fve-dark-` 那一半）。

图表用从这些 token 读回来的具体颜色画，而不是 `var()`，所以要在能改变它们的东西变了时被告知重读：面或它任一祖先上的 `class`、`data-theme`、`data-fve-preset`、`data-fve-change-colors` 或 `style` 属性。样式表推导出来的颜色（`color-mix()`、`oklch(from …)`）由浏览器先算好再交给图表。换主题请改这些属性之一；只换样式表而不动任何属性，图表会留在旧颜色上。

`radius` 与 `text-ui` 是暗色块不重新声明的两个 token——长度在明暗两态里是同一个长度——因此 `--fve-radius` 与 `--fve-text-ui` 对两态同时生效，也就没有对应的 `--fve-dark-` 那一半。`text-ui` 是正文之下唯一的那一档：分组标签、列头、徽章、分页与所有 `sm` 控件都用它，宿主改一处，这些一起动。

根默认涂 `--background`，因此嵌入在宿主卡片里的视图会露出自己的底色矩形——暗色下 `--card` 比 `--background` 亮一档，嵌入块读成卡片里一块更深的区域。让它涂所在之处的颜色：在那张卡片上把 `--fve-background` 与 `--fve-dark-background` 设为卡片色（变量会继承，卡片里的嵌入视图读到，别处不受影响）。不要设成 `transparent`：行、冻结列、悬停色与危险按钮上的字都用 `--background` 画，透明会让横向滚动的列从冻结列底下透出来，危险按钮的字也看不见。

弹层——菜单、下拉列表、Popover、Tooltip 与对话框——都 portal 到 `<body>`，画在 `z-index: 50` 这一层，压在周围页面之上。宿主自己的 chrome 堆得比它还高时，改一个变量即可把它们一起抬起来：

```css
:root {
  --fve-popup-z-index: 2000;
}
```

#### 预设

预设是上面那些 `--fve-*`／`--fve-dark-*` 变量的一组取值，由 `data-fve-preset` 属性选中。选一套只要一行：引一个文件、写一个属性（或一个 prop）。

```ts
import '@ahoo-wang/wow-view-engine/styles.css';
// 只要一套：只引它自己的文件
import '@ahoo-wang/wow-view-engine/themes/porcelain.css';
// 或者运行时切换：引全部预设
// import '@ahoo-wang/wow-view-engine/themes.css';
```

```html
<html data-fve-preset="porcelain"></html>
```

属性挂在 `<html>` 上，所有视图与弹层都换上这套预设。想让某一个视图用自己的，就在 `ViewSurface`、工作台或嵌入组件上用 `preset` 钉住；它的弹层像带着 `data-theme` 一样把它带到 `<body>`。挂在其他祖先上的 `data-fve-preset` 也有效：面会找到最近的那个，交给自己的弹层。`/ui` 导出 `BUILT_IN_PRESETS`（内置预设名的只读数组）与由它得出的 `BuiltInPreset` 类型；`preset` prop 的类型 `ViewPreset` 是内置名加任意字符串——内置名有补全，宿主自己的预设名也能传。引擎不画主题选择器：要给用户选，就在宿主自己的 chrome 里用 `BUILT_IN_PRESETS` 列出来。

**内置目录**（名字是描述性的普通词，不指任何公司或产品；每套亮暗两半都量过）：

| 预设        | 性格                                                            | 圆角 | 字体                 | 图表八色 | 适合                                           |
| ----------- | --------------------------------------------------------------- | ---- | -------------------- | -------- | ---------------------------------------------- |
| `neutral`   | 默认；中性灰、黑色主色                                          | 10px | 宿主的               | 默认     | 不想要任何风格，或自己改几个变量               |
| `slate`     | 冷灰配蓝                                                        | 10px | 宿主的               | 默认     | 冷色调的后台（补偿控制台的样子）               |
| `azure`     | 中国企业后台：明快的蓝、灰底白卡、柔和的多层阴影                | 6px  | 中文优先的系统字体   | 自带     | 中国企业的内部系统                             |
| `porcelain` | 桌面原生：系统字体、大圆角、柔和阴影、近中性的灰，焦点跟主色    | 12px | 系统字体（苹果优先） | 自带     | 面向业务人员与管理层的产品、Mac 为主的团队     |
| `graphite`  | 方角、强灰阶、不用阴影、层级靠灰度，焦点跟主色                  | 0    | 宿主的               | 自带     | 运维、监控、事件流这类一屏看很多行的工具       |
| `fjord`     | 北欧冷色、低饱和，长时间盯着看不累                              | 8px  | 宿主的               | 自带     | 开发者工具、整天盯着看的内部系统               |
| `contrast`  | 高对比：字 ≥7:1、控件边与焦点 ≥4.5:1、默认开图表花纹            | 4px  | 宿主的               | 自带     | 低视力读者、强光下的大屏、要求 WCAG AAA 的客户 |
| `brand`     | neutral 的一切，主色与淡色从你给的一个颜色（`--fve-brand`）派生 | 10px | 宿主的               | 默认     | 只有一个品牌色                                 |

**我的品牌该选哪套**：

| 你的情况                         | 用什么                                                                       |
| -------------------------------- | ---------------------------------------------------------------------------- |
| 已经是 shadcn 应用，有自己的主题 | `shadcn-bridge.css`，不挂预设（见下文）                                      |
| 没有设计系统，要一个现成的风格   | 上表里最像你的那一套                                                         |
| 后台长得像国内常见的开源组件库   | `azure`；看板面向 A 股或国内经营数据时再加 `data-fve-change-colors="red-up"` |
| 桌面应用那样的质感               | `porcelain`                                                                  |
| 运维台、要方角和高密度           | `graphite`                                                                   |
| 只有一个品牌色                   | `brand` 加 `--fve-brand: <你的颜色>`（见下）                                 |
| 有完整的设计规范                 | 选最接近的一套，再在 `:root` 上覆盖差的那几个 `--fve-*`                      |

- **预设与明暗互不相干。** 预设只提供亮暗两半的值；亮还是暗仍由上文的 `.dark` 或 `theme` 决定。
- **宿主自己的变量优先。** 每套预设写成 `:where([data-fve-preset='…'])`，不占特异性，所以你在 `:root` 上设的 `--fve-*` 总是赢过你选的预设，不管哪份样式表先加载——想改预设里的某一个颜色，不必把其余的重写一遍。
- **图表花纹**：`--fve-chart-patterns: on | off` 设在任一祖先上，钉开或钉关图表系列上的花纹（decal）；不设（或 `auto`）时跟随读者系统的「提高对比度」（`prefers-contrast: more`）。它不是颜色；只有 `contrast` 这一套预设设它（`on`），你在 `:root` 上写的 `off` 仍然赢。
- **预设给什么**：每个颜色与 `radius` 必给；另有九个可选组，每组全给或全不给——两种明暗的图表八色、两种明暗的三档阴影、一条系统字体栈（`--fve-font-sans`）、图表花纹的钉（`--fve-chart-patterns`）、推荐的密度（`--fve-preset-density`，见[密度](#密度)），以及面怎样分层、怎样画：分组底（`--fve-canvas`，两种明暗）、卡片的边与浮起（`--fve-card-edge`、`--fve-card-shadow`，两种明暗）、填色的控件（`--fve-control`、`--fve-control-edge`、`--fve-control-thumb`，两种明暗）与标题的字重（`--fve-title-weight`）。不给某组的预设，那一组取外层的值：一套不带八色的预设钉在一套带八色的预设里，画的是外层的八色；只有 `neutral` 把每一组都放回原样。预设自带的色板与默认八色过同一套色觉与对比门（`test/paletteDistance.test.ts`）；色位是序数——「第三个系列」——不是色相，所以 `ChartSpec.colors` 里写 `var(--chart-3)` 的，换预设颜色会跟着变。要去掉一档阴影，写一个透明的阴影（`0 0 0 0 transparent`），不要写 `none`：工具类把阴影与描边拼成一个列表，`none` 放进列表里整条声明就失效，连弹层的描边也一起没了。
- **预设从不改的**：`pin-shadow`（由明暗决定）、`text-ui`（宿主的排版）与 `rise`／`fall`（宿主的[涨跌色约定](#涨跌色升与降)）。宿主自己设 `--fve-chart-*` 的，要替自己的色板补上上面那些测量。
- **每套都只用这份合同。** 内置预设只写上面 token 表里记下的变量，没有私有选择器，也没有为哪一套预设开的代码路径（`test/themeFiles.test.ts` 核对每个变量都在 token 表里）。所以内置预设做得到的，你自己的预设也做得到。每套预设在两种明暗下，字、控件边、焦点的每一对都过 4.5:1／3:1（`test/presetContrast.test.ts`）。
- `themes.css` 与 `themes/<名>.css` 里只有这些变量赋值，外加 `brand` 的那一个 `@supports`；`scripts/verify-package.mjs` 在每次构建时核对：每条规则都是一个预设块，每条声明都是 `--fve-` 变量，每套预设的必给集合相同（一套钉在另一套里时颜色整套替换），每个可选组全给或全不给，单套文件拼起来就是 `themes.css`，每套 gzip 后不超过 1.2 KB、全部不超过 8 KB。`neutral` 把可选组也写成未设，所以钉成 `neutral` 是完整的复位。每套的取值与取舍写在包里 `src/themes/<名>.css` 的注释里。

**只有一个品牌色**：选 `brand`，把你的颜色给它。整套主题就这些：

```css
@import '@ahoo-wang/wow-view-engine/styles.css';
@import '@ahoo-wang/wow-view-engine/themes/brand.css';

:root {
  --fve-brand: #7c3aed;
  /* 可选：暗色另给一个；不给，暗色也从 --fve-brand 派生 */
  --fve-dark-brand: #a78bfa;
}
```

```html
<html data-fve-preset="brand"></html>
```

`brand` 就是 `neutral`，只是主色、选中项与悬停行的淡色从你的颜色在 OKLCH 里派生，亮度被夹住：亮色 0.40～0.50、配近白的字，暗色 0.68～0.80（彩度至多 0.18）、配深色的字。所以不管给什么颜色，作字、作填充都守得住每一条对比度线——有测试把整个 sRGB 色域扫一遍来守。太亮的品牌色（比如黄）或太暗的，会比品牌手册深一些或浅一些：这是这份保证的代价。灰、`input`、`ring`、状态色与图表八色仍是 `neutral` 的。

- **`--fve-brand` 挂在预设所在的元素上或更外层**——与 `data-fve-preset` 同在 `<html>` 上，或写在 `:root` 上——因为自定义属性里的 `var()` 在声明它的元素上解析。
- **没给颜色就没有品牌**：不设 `--fve-brand`，页面就是 `neutral`。
- **浏览器**：用的是相对颜色语法（Chrome 119、Safari 18、Firefox 128 起）。整块包在 `@supports` 里，旧浏览器看到的是 `neutral`，而不是失效的颜色。
- **原来的 `blue` 预设**就是 `brand` 加 `--fve-brand: oklch(0.488 0.243 264.376deg)`；要暗色也一模一样，再加 `--fve-dark-brand: oklch(0.707 0.165 254.624deg)`。

**自己写一套**：照同样的写法定义自己的预设——`:where([data-fve-preset='acme']) { --fve-primary: …; --fve-dark-primary: …; }`——用同一个属性或 prop 选中。写完怎样自查：打开 Storybook 的「主题/预设 → 对比度矩阵」，把自己的 `--fve-*` 声明粘进输入框，它们作为一套预设当场与内置预设一起量——对比度矩阵与图表八色的三道门都在那一页。Storybook 的「主题/宿主自定义主题」是一套完整的例子：包外的一份样式表，只用这份合同，过同样的门。

#### 涨跌色：升与降

视图上有两处颜色表示变化：指标卡的变化（较上一期、较对比指标），和瀑布图的每一步。默认按**好坏**着色——指标往好的方向走（`lowerIsBetter` 决定哪边是好）用 `success`，往坏的方向用 `destructive`；瀑布图升用 `success`、降用 `destructive`。市场的读法不同：中国大陆的看板按**方向**着色、红涨；港股、欧美也按方向、绿涨。这由宿主按市场与读者决定，与预设无关：

```html
<html data-fve-change-colors="red-up"></html>
```

| `data-fve-change-colors` | 指标卡的变化 | `--rise`／`--fall`（瀑布图） |
| ------------------------ | ------------ | ---------------------------- |
| 不设，或 `semantic`      | 按好坏       | `success`／`destructive`     |
| `green-up`               | 按方向       | `success`／`destructive`     |
| `red-up`                 | 按方向       | `destructive`／`success`     |

- 语言不等于市场，所以没有任何东西替你自动切换：中文界面看海外业务、英文界面看 A 股都很常见。
- 没有 prop：一页读的是一个市场，一页里两种约定会让读者读反。弹层照抄它，与预设一样送到 `<body>`。
- `--fve-rise`／`--fve-fall`（及 `--fve-dark-` 两半）设的是颜色本身；约定只决定它们默认取哪一对。预设从不设它们。
- 颜色从来不是唯一的线索：指标卡的变化带方向箭头与正负号，瀑布图的标签带符号——红与绿在红绿色弱的读者眼里是同一种颜色。

#### 密度

行排得多紧也由宿主定，与预设互不相干：

```html
<html data-fve-density="compact"></html>
```

| `data-fve-density` | 表头行 | 表格行 | 值两侧 | 视图列表的一项 | 仪表盘面板内边距 |
| ------------------ | ------ | ------ | ------ | -------------- | ---------------- |
| `compact`          | 32px   | 33px   | 6px    | 24px           | 8px              |
| `default`          | 40px   | 41px   | 8px    | 28px           | 12px             |
| `comfortable`      | 44px   | 45px   | 12px   | 32px           | 16px             |

- **只动这四样长度。** 控件高度（点击目标至少 24px）、字号、弹层尺寸与仪表盘的 80px 行高都不动——存下的板子几何就是按这个行高数的。
- **单独一个视图**：在 `ViewSurface`、工作台或嵌入组件上写 `density`，钉在这块面和它的弹层上。
- **不设时，面按预设的推荐**：`porcelain` 舒适、`graphite` 紧凑，其余默认。预设用 `--fve-preset-density`（`-1`、`0`、`1`）说，是和图表八色一样的可选组；你的属性或 prop 总赢过它。
- `default` 画出的长度与有这条轴之前一模一样。

#### 已有 shadcn 主题的宿主：`shadcn-bridge.css`

宿主已经有一套 shadcn/ui 主题——`:root` 上声明了 `--background`、`--primary`、`--radius` 等，暗色值写在 `.dark` 下——就既不需要预设，也不必把颜色抄一遍。再引一个可选入口，它把每个 `--fve-*`／`--fve-dark-*` 变量指向同名的 shadcn token：

```ts
import '@ahoo-wang/wow-view-engine/styles.css';
import '@ahoo-wang/wow-view-engine/shadcn-bridge.css';
```

- **有四类不桥接**，保持本包自己的值：`input` 与 `ring`（shadcn 主题常写的 `--input: var(--border)`、`--ring: var(--primary)` 不欠控件边与焦点要的 3:1）、状态色 `destructive`、`success`、`warning`（按 4.5:1 量过的文字色；shadcn 没有 `success` 与 `warning`），以及图表八色。想用自己的，就逐个自己设——并量一量设出来的值。阴影也不桥接（shadcn 没有标准的阴影 token 名）；字体桥接，`--fve-font-sans` 取宿主的 `--font-sans`。
- **明暗归宿主。** 桥接在 `<html>` 上解析，读到的是 `<html>` 当前模式下 `:root` 的值：像 shadcn 那样把 `.dark` 挂在 `<html>` 上，让视图跟着它。用 `theme` 钉成相反模式的视图，亮暗两半拿到的都是宿主当前的值；只在与页面一致的地方钉模式。
- **宿主自己的 `--fve-*` 仍然优先**，用 `preset` 钉住预设的面穿那套预设。
- **桥接与预设二选一。** 桥接只在 `<html>` 没挂预设时生效：`<html>` 上写了 `data-fve-preset`，得到的就是预设，与两个文件谁后引入无关。
- **文字颜色是宿主主题的。** 文字 token 原样桥接；宿主的 `--muted-foreground` 在它的 `--background` 上不到 4.5:1，视图里的弱字也就不到。

Storybook 的回归用例 `ShadcnBridge.test.stories.tsx` 把补偿控制台的主题连同桥接挂到一个工作台上，量出两种明暗下控件边与焦点都 ≥3:1。

#### 覆盖变量要守的线

每一套内置预设在两种明暗下都守住这些线，由 Storybook 的**对比度矩阵**（View Engine / 主题 / 预设 / 对比度矩阵）在真浏览器里逐对 token 量出；粘贴进去的变量也一起量：

| 线     | token                                                                                                                                                                                                                         |
| ------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| ≥4.5:1 | 每个 `*-foreground` 在它的底上；`muted-foreground` 在 `background`、`card`、`popover` 上；`foreground` 在 `muted`、`row-hover` 上；`quiet-foreground`；`destructive`、`success`、`warning` 作为文字在 `background`、`card` 上 |
| ≥3:1   | `input` 与 `ring` 在 `background`、`card`、`popover` 上（以及暗色控件自己的 `input/30` 底上）                                                                                                                                 |
| 无     | `border` 与 `sidebar-border`（分隔线）、`radius`、`text-ui`                                                                                                                                                                   |

宿主设了其中哪一个，就欠自己的主题同一条线。图表八色另有自己的线：色位之间的色觉缺陷间距、每个标记上的字都读得清——自带色板的预设同样要过。完整的说明见[视图引擎的主题](https://wow.ahoo.me/zh/guide/typescript/view-engine-theming)。

#### 宿主自己的 chrome：`fve-tokens`

样式表的每一条规则都在构建时被收进样式边界，所以主题的 token，连 `grid`、`gap-4`、`bg-background` 这样的 utility，都只在边界里才画得出来。边界有两个，其中只有一个是 surface：

|                           | `.fve-root`                                       | `.fve-tokens`                                                    |
| ------------------------- | ------------------------------------------------- | ---------------------------------------------------------------- |
| 谁渲染                    | `ViewSurface`，以及各工作台与嵌入                 | 你自己的 DOM                                                     |
| token、utility、preflight | 有                                                | 有                                                               |
| 涂底色与文字色            | 涂                                                | **不涂**——想要本包那张底，自己写 `bg-background text-foreground` |
| 明暗                      | 祖先上的 `.dark`，或 `theme` 用 `data-theme` 钉住 | 只认祖先上的 `.dark`                                             |
| 措辞、语言、时区、tooltip | 有，走 `ViewSurface` 的 props                     | 没有                                                             |

**`fve-tokens` 许诺的是 token 与 utility，不是组件。** 本包渲染所用的 shadcn 原语是 vendored 的，靠 `shadcn add --diff` 升级，不属于公开 API——所以请用你自己的组件、或你自己那份 shadcn/ui 搭 chrome，由这道边界把本主题的配色与间距交给它们：

<!-- typecheck-context
import { ViewEngine } from '@ahoo-wang/wow-view-engine';
declare const engine: ViewEngine;
import { EmbeddedView } from '@ahoo-wang/wow-view-engine/ui';
declare const id: string;
-->

```tsx
<div className="fve-tokens flex flex-col gap-4">
  <header className="flex items-center gap-2 rounded-lg border bg-card p-4 text-card-foreground">
    ……你自己的页头，穿着本主题的 token……
  </header>
  <EmbeddedView engine={engine} instanceId={id} theme="light" />
</div>
```

`fve-tokens` 判断明暗只读一样东西：祖先上的 `.dark` class，和各个面读的是同一个——放在 `<html>` 上、放在应用外壳上都行，你的应用本来放在哪儿就放哪儿。它**不读**自己身上的 `data-theme`：钉模式是 surface 的事。它还会把凡是归某块面管的元素原样交还给那块面，所以上面那个钉成亮色的视图，在暗色页面里 token 与 utility 一路都是亮的。

preflight 同样在边界里生效：这片区域内你自己的标题、列表与按钮，会像在视图里一样被重置。这是换取这套 utility 的代价，也正是这个类该戴在用到它们的那块 chrome 上、而不是整页上的原因。

#### 工作台确实交出来的东西：一个值的读法

`/ui` 不把自己的 chrome 组件交出去，但它把**读一个值的那套东西**交出去了——改一个单元格，因此不必赔上整个工作台。`renderCell` 只盖住你在意的那一列，其余的交给 `cellValue`，照默认那样画：枚举取定义里的 option 标签、时间走这块面的时区与语言、数字按字段的 `numberFormat`：

<!-- typecheck-context
import { ViewEngine } from '@ahoo-wang/wow-view-engine';
declare const engine: ViewEngine;
declare function OrderStatusLamp(props: { status: string }): React.ReactNode;
-->

```tsx
import {
  DataWorkbench,
  cellValue,
  useSurfaceDisplay,
  useViewMessages,
} from '@ahoo-wang/wow-view-engine/ui';
import type { RecordCell } from '@ahoo-wang/wow-view-engine/ui';

function OrderCell({ cell }: { cell: RecordCell }) {
  // 从这个单元格所在的那块面上读：正在生效的措辞，以及值所用的语言与时区。
  const messages = useViewMessages();
  const display = useSurfaceDisplay();
  if (cell.column.field !== 'status') {
    return cellValue(cell.value, cell.column, messages, display, 'table');
  }
  return <OrderStatusLamp status={String(cell.value)} />;
}

<DataWorkbench
  engine={engine}
  definitionId="orders"
  // 关于记录视图的话——单元格怎么读、能不能勾选行、空结果说什么——是一个
  // 对象，因为它们对分析视图都没有意义。
  record={{
    renderCell: cell => <OrderCell cell={cell} />,
    selectable: false,
    emptyTitle: '没有待发货的订单',
  }}
/>;
```

`cellText` 是同一套读法的单行文本版——写 CSV、复制选区、`title` 属性都用它；`displayValue` 只给字段种类自己的那一层，种类没话可说时返回 `undefined`，剩下的交给你自己的渲染。

**面不嵌套。** 根套根不受支持：CSS 没有「最近祖先」选择器，内层面把 token 重新声明在自己身上，`dark:` 工具类认的却仍是外层那个根——钉成相反模式时就是浅色 token 配深色 utility，屏幕上画不出来。需要第二层的时候，戴 `fve-tokens`，不要再套一块面。

### 3b. 或者自行组合 UI

<!-- typecheck-context
import { ViewEngine } from '@ahoo-wang/wow-view-engine';
declare const engine: ViewEngine;
declare function Spinner(): React.ReactNode;
declare function NotFound(): React.ReactNode;
declare function OrdersLayout(props: Record<string, unknown>): React.ReactNode;
-->

```tsx
import { isRecordRuntime } from '@ahoo-wang/wow-view-engine';
import type { RecordViewRuntime } from '@ahoo-wang/wow-view-engine';
import {
  useOpenView,
  useViewRuntime,
  useFilterEditor,
  useRecordTable,
} from '@ahoo-wang/wow-view-engine/react';

export function OrdersPage({ instanceId }: { instanceId: string }) {
  const { runtime, loading } = useOpenView(engine, instanceId);
  if (!runtime) return loading ? <Spinner /> : <NotFound />;
  // 这个 id 也可能是分析视图或仪表盘；本页只画记录。
  if (runtime.kind !== 'record' || !isRecordRuntime(runtime))
    return <NotFound />;
  return <OrdersView runtime={runtime} />;
}

function OrdersView({ runtime }: { runtime: RecordViewRuntime }) {
  const state = useViewRuntime(runtime); // draft、applied、result、issues、dirty
  const filter = useFilterEditor(runtime); // 节点增删改、提交
  const table = useRecordTable(runtime); // 列、排序、选择、分页
  // 用这些控制器渲染任意布局，无需触及引擎内部。
  return <OrdersLayout state={state} filter={filter} table={table} />;
}
```

### 只用内核，不用 React

<!-- typecheck-context
import { orders } from './orders';
import type { QueryApi } from '@ahoo-wang/wow-client';
import type { RecordViewConfig } from '@ahoo-wang/wow-view-engine';
declare const config: RecordViewConfig;
declare const source: QueryApi<any>;
-->

```ts
import {
  builtinFieldKinds,
  compileRecord,
  projectRecord,
  validateRecord,
} from '@ahoo-wang/wow-view-engine';

// 内核只接受数据定义；`orders` 就是一个。
if (orders.kind !== 'data') throw new Error('orders is a data definition');
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

| 类型             | 职责                                                                                                                                                                                                                                                                                                                                         | 所在   |
| ---------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------ |
| `ViewDefinition` | 字段、类型、操作符、记录与分析能力。由代码声明或生成，运行时不可编辑。                                                                                                                                                                                                                                                                       | 代码   |
| `ViewConfig`     | `RecordViewConfig` / `AnalysisViewConfig` / `DashboardViewConfig` 判别联合，共享的 `FilterTree` 描述数据范围。它是意图模型：保存"最近 7 天"这样的语义而不是编译结果，也不含任何 UI 组件名。Analysis 覆盖 Wow 聚合协议全部能力；Dashboard 面板分数据面板（引用已保存的视图，或板子自己的分析）与标题、Markdown 文字、图片、链接四种内容面板。 | 数据   |
| `ViewInstance`   | 一份保存的 `ViewConfig`，加 id、标题、范围与不透明 `revision`。范围为系统、共享或个人。                                                                                                                                                                                                                                                      | 存储   |
| `ViewRuntime`    | 一个打开的视图：草稿、已应用配置、结果、状态、选择。提供 `subscribe` / `getSnapshot`。                                                                                                                                                                                                                                                       | 内存   |
| `ViewEngine`     | 定义、存储与已打开运行时的注册表；打开、保存、列表等命令的入口。                                                                                                                                                                                                                                                                             | 内存   |
| `ViewStore`      | 八个方法的持久化端口。业务应用为自己的后端实现它。                                                                                                                                                                                                                                                                                           | 应用   |
| `FieldKind`      | 一种字段类型的操作符、校验、编译与编辑器描述。                                                                                                                                                                                                                                                                                               | 注册表 |

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

| 入口                         | 导出                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| ---------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `@ahoo-wang/wow-view-engine` | 模型类型与常量；四个纯内核整份（`validate*` / `compile*` / `project*` 及其旁边的读法）；运行时只导出宿主要握的，不导出它由什么搭成——`ViewEngine`、`validateDefinition`、运行时合同 `ViewRuntime`、`RecordViewRuntime`、`DashboardRuntime`、`AnyViewRuntime` 连同它们签名里出现的每一个类型、`hasResult`、`hasAsked`、`isRecordRuntime`、写入错误 `ViewWriteError` 与 `ViewCommandError`、`ExportCancelled`、`RuntimeEnvironment`、`defaultRuntimeEnvironment`、`ViewSource`、`OptionSource`；`ViewStore` 端口与 `MemoryViewStore`                                                                               |
| `/react`                     | 钩子与无样式控制器，连同它们交出的类型：`useViewEngine`、`useOpenView`、`useViewRuntime`、`useViewList`、`useViewManager`、`useWorkbench`、`useLeaveGuard`、`useFilterEditor`、`useRecordTable`、`useAnalysisEditor`、`useAnalysisResult`、`useDashboard`、`useSaveCommands`、`RecordActionSlots`，以及保存命令与管理器共用的写入结局词汇                                                                                                                                                                                                                                                                       |
| `/ui`                        | 默认组件、视图与工作台，连同它们的 props：`DataWorkbench`、`DashboardWorkbench`、`DashboardEditExtensions`、`useDashboardExtensions`、`EmbeddedView`、`EmbeddedDashboard`、`ViewHeader`、`SaveActions`、`ViewManager`、`LeaveDialog`、`EditorBand`、`FilterPanel`、`StatusStrip`、`AppliedBar`、`ResultToolbar`、`RowActions`、`RecordTable`、`RecordCards`、`RecordPagination`、`AnalysisTable`、`AnalysisChart`、`DashboardGrid`、`HeadingPanel`、`MarkdownPanel`、`ImagePanel`、`LinksPanel`、`MessagesProvider`；措辞目录 `defaultMessages` 与 `zhCN`；一个值的读法 `cellValue`、`cellText`、`displayValue` |
| `/styles.css`                | 主题。显式导入；任何 JS 入口都不会引入 CSS，产物也不会在 `.fve-root`／`.fve-tokens` 两个样式边界之外绘制任何东西（preflight 与工具类在构建时收进边界内），`scripts/verify-package.mjs` 在每次构建时核对这两点。                                                                                                                                                                                                                                                                                                                                                                                                 |
| `/themes.css`                | 预设，可选：只有按 `data-fve-preset` 选中的 `--fve-*` 赋值（[预设](#预设)），由同一个脚本核对。                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 |
| `/themes/<名>.css`           | 单独一套预设，给只用一套的宿主：就是 `themes.css` 里它那一块（[预设](#预设)）。                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 |
| `/shadcn-bridge.css`         | 可选：把宿主的 shadcn token 读进 `--fve-*` 变量，`input`、`ring`、状态色、图表色与阴影除外，且只在没挂预设时生效（[桥接](#已有-shadcn-主题的宿主shadcn-bridgecss)），由同一个脚本核对。                                                                                                                                                                                                                                                                                                                                                                                                                         |

这就是公开面，而且逐个名字守着。每个代码入口的完整清单——每一个名字，以及它是类型还是值——在 `test/surface/`（`root.txt`、`react.txt`、`ui.txt`）：入口多导出了清单上没有的名字、或不再导出清单上有的名字，`test/publicSurface.test.ts` 就失败；`scripts/verify-package.mjs` 再拿同一份清单核对构建出的每个 JS 入口。往清单里加一个名字或拿掉一个，就是改公开面，按改公开面来审。

运行时自己的部件不导出：请求调度器、两种运行时共用的那个 store、刷新计时器、监听者集合、运行时的类与它们的构造函数。运行时经 `ViewEngine` 打开或新建、按合同持有，从不手搭；`/react` 与 `/ui` 在包内直接取这些部件，不经入口。

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

<!-- typecheck-context
import { ViewEngine } from '@ahoo-wang/wow-view-engine';
declare const engine: ViewEngine;
-->

```tsx
import { DataWorkbench, zhCN } from '@ahoo-wang/wow-view-engine/ui';

<DataWorkbench
  engine={engine}
  definitionId="orders"
  messages={{ ...zhCN, 'label.filter.apply': '确定' }}
  locale="zh-CN"
/>;
```

时间按引擎的时钟 `environment.timeZone` 显示：相对日期"今天"按它解析，未声明 `timeZone` 的日期直方图也按它切桶，所以一行按什么时钟被筛选、被分组，就按什么时钟显示。

找不到的 key 会沿点号回退到最长的已知前缀，再退回 key 本身，因此不会渲染空白。新增 issue code 却没有对应措辞时，测试会失败。组件能取的 key 是 `MessageKey` 这个联合类型，删掉一个键就是编译错误；宿主自己的 `messages` 仍是开放的字符串映射。

## 扩展点

| 变化轴   | 机制                                                                                                                                                                                    |
| -------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 字段类型 | 注册 `FieldKind`（操作符、校验、编译到 `FilterExpression`、编辑器描述）。编辑器从 `/ui` 已有的值控件里选一种（`EDITOR_INPUTS`）：没有渲染器注册表，要别的控件的类型会被拒绝而不是猜着画 |
| 数据来源 | `resolveSource(key)` 返回 Wow 查询客户端                                                                                                                                                |
| 持久化   | 实现 `ViewStore`                                                                                                                                                                        |
| 动作     | 向工作台传 `actions`：`global`、`bulk`、`row` 三个渲染函数。动作是代码，由宿主交出来，不进配置、不入库。一页只取视图显示的字段，动作要读的其他字段写在定义的 `record.rowFields` 里      |
| 外观     | CSS 变量与主题文件；通过组合 `/react` 钩子替换组件                                                                                                                                      |

内置字段类型：`string`、`number`、`boolean`、`date`、`datetime`、`enum`、`reference`、`array`、`elementMatch`、`search`，以及由 Wow 元数据筛选支撑的 `documentId`、`aggregateId`、`tenantId`、`ownerId`、`spaceId`、`deletion`。

## 分层

```text
model → filter → record | analysis | dashboard → runtime → react → ui
store → model
```

`validateDefinition` 在引擎注册定义时校验一次：字段名是否符合 Wow 查询语法、id 是否唯一且不含 `:`、能力是否足以让 `default*Config` 构造出合法配置、每个系统视图是否通过它自己的内核。含 error 的定义仍留在注册表里，但在使用处被拒绝——代码里的错误因此表现为一条可读的 issue，而不是用户打开视图时的 `TypeError`。

六条依赖规则由架构测试强制：`model` 不引入任何目录；`filter` 只引入 `model`；`record`、`analysis`、`dashboard` 只引入 `model` 与 `filter`；`runtime` 不引入 `react` 与 `ui`；`store` 只引入 `model`；`react` 不引入 `ui`。`store` 及以下不含 React 与 DOM。只使用 Wow 未弃用的、基于 `FilterExpression` 的查询 API。

## 不做

视图种类插件、定义 CRUD 后端、写入回执核对与读屏障、并发与页大小以外的资源预算、SSR 预载、通用 region 或事件总线、跨页全选、单元格编辑、Dashboard 嵌套。

## 内容安全策略（CSP）

本包可以在严格的策略下运行——`script-src 'self'`、`style-src 'self'`，不开 `'unsafe-inline'` 与 `'unsafe-eval'`——要放行的只有两件事：

- **样式表**是文件（`styles.css`，用预设时还有 `themes.css`）：从允许的来源加载，不要内联。组件画出来的标记里没有 `style` 属性：内联样式都经 DOM 的 style 对象写入，策略不拦；图表提示框的色块是 SVG 的 `fill`（有单测守着提示框的 HTML 里没有 `style=`）。
- **把图导出为 PNG** 时，图的 SVG 从一个 `blob:` 地址作为图片载入、再画到画布上，所以 `img-src` 要包含 `blob:`。不放行时 PNG 做不出来，工具栏会说明；导出 SVG 不需要任何放行。两种导出都不执行代码、不写内联脚本。

```
Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' blob:
```

## 开发

```bash
pnpm --filter @ahoo-wang/wow-view-engine test          # 单元测试、覆盖率与三个 tsc 工程
pnpm --filter @ahoo-wang/wow-view-engine build         # 构建，并核对发布出去的入口
pnpm --filter @ahoo-wang/wow-view-engine test:package  # 入口可导入、核心入口无 DOM 类型、JS 不引入 CSS
pnpm storybook                                             # 每个界面的每种状态，见导航「View Engine」
```

`examples/` 下是两个只依赖公开合同、不依赖内部实现的消费者：
`PlainRecordWorkbench.tsx` 用无样式 HTML 跑通整个闭环，
`FetcherViewStore.ts` 用 `@ahoo-wang/fetcher` 把 `ViewStore` 端口实现在 HTTP 上。

`@ahoo-wang/fetcher-viewer` 已弃用，新项目使用本包。两者模型与 API 不同。
