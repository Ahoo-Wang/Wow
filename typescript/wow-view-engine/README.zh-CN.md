# Wow View Engine

随 Wow 发布，与 Wow 同一个 tag、同一个版本号。

**Wow View Engine 是面向 Wow 业务应用的数据视图引擎。** 业务应用用代码声明一份数据"能被怎样观察"：字段、类型、操作符、可用的维度与指标。用户在界面上决定"这一次怎样观察"：筛选、列、排序、维度与指标、图表、面板组合。引擎把这种观察方式编译成 Wow 查询、执行、渲染，并把有价值的观察方式保存下来供下次直接打开。

它是 `@ahoo-wang/wow-client` 之上的展示层，也是 `@ahoo-wang/fetcher-viewer` 的继任者。指南与参考都在[文档站](https://wow.ahoo.me/zh/typescript/view-engine/)；本页只讲安装、最短的可运行示例与各个入口。

> **兼容性。** 补丁版本不破坏公开面：每个入口的导出，包括后端实现的 `ViewStore` 端口；CSS 合同（[什么是公开的](https://wow.ahoo.me/zh/typescript/view-engine/theming#什么是公开的)）；消息键与 issue code；以及 `wow-view-engine` 命令。次版本可以破坏，它的发布说明逐条列出每个破坏与迁移步骤；Wow 包请停在同一个次版本上，见[版本范围](https://wow.ahoo.me/zh/typescript/compatibility#版本范围)。用户以旧形状保存的视图照常打开：引擎在读取时迁移存储的配置。

## 安装

```bash
pnpm add @ahoo-wang/fetcher \
  @ahoo-wang/fetcher-eventstream @ahoo-wang/wow-client @ahoo-wang/wow-view-engine
# /react 与 /ui 入口
pnpm add react react-dom
```

版本号跟随 Wow，次版本可能带有破坏性改动：用 `save-prefix=~` 或 `--save-exact` 让 Wow 包停在同一个次版本上，见[版本范围](https://wow.ahoo.me/zh/typescript/compatibility#版本范围)。

Peer 依赖：`@ahoo-wang/wow-client`（以及它需要的 fetcher 包）；`react` 与 `react-dom` 只用于 `/react` 和 `/ui` 入口，`react-router` 只用于 `/react-router`，`mingo` 只用于 `/testing`。根入口可在 Node 中运行。要把保存的视图放在 Wow 服务端，再加 [`@ahoo-wang/wow-view-store`](../wow-view-store/README.zh-CN.md)（在 Wow V10 服务上用它的 `/v10` 入口，数据源也走 V10 的查询路由）。只提供 ES 模块；Node `>=22.12.0` 或当前的浏览器；TypeScript 6 及以上。

## 快速开始

### 1. 声明定义

<!-- typecheck: file=orders.ts -->

```ts
import {
  AggregationFunction,
  FilterOperator,
  SortDirection,
} from '@ahoo-wang/wow-client/dsl';
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
      summary: [AggregationFunction.SUM, AggregationFunction.AVG],
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
          children: [
            {
              field: 'status',
              operator: FilterOperator.IN,
              value: ['PENDING'],
            },
          ],
        },
        filterMode: 'simple',
        refresh: { interval: null },
        sort: [{ field: 'createdAt', direction: SortDirection.DESC }],
        pageSize: 20,
        summaries: [{ field: 'amount', fn: AggregationFunction.SUM }],
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
import type { ViewSource } from '@ahoo-wang/wow-view-engine';
declare const sources: Record<string, ViewSource>;
-->

```ts
import { MemoryViewStore, ViewEngine } from '@ahoo-wang/wow-view-engine';

const engine = new ViewEngine({
  store: new MemoryViewStore(),
  // 一个 ViewSource：Wow 查询路由的客户端（生成的 …SnapshotQueryClient）本身就是
  resources: [{ definition: orders, source: sources.orders }],
});
```

每个**资源**把一份定义和它的行从哪里来配成一对；在应用启动时把每个资源登记一次，**整个应用一个引擎**。要把定义收窄到服务端允许的范围，给来源加上 `describe`（[引擎与资源](https://wow.ahoo.me/zh/typescript/view-engine/host)）。

### 3. 渲染工作台

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

真实应用的页面外面套一层 `ViewHost`，把引擎、你的路由、语言、主题与命令接起来：见[把引擎放进宿主](https://wow.ahoo.me/zh/typescript/view-engine/host)。

### 引用字段的排序与搜索

带 `remote` 的 `reference` 字段存的是 id、显示的是名称（由宿主的选项读出）。服务端按存储的 id 排序、在存储的文本里搜索，从不按显示的名称，所以这样的字段只有写了 `sortable: true` 才排序——按 id，表头和排序编辑器都会标明「按 ID」——而且不能作为搜索字段：准入会拒绝保存在它上面的排序（`record.sort.reference-label`）和点名它的搜索（`definition.search.reference-label`）。要按名称排序或搜索，把名称冗余进读模型，用那个字段：

```ts
import type { FieldDefinition } from '@ahoo-wang/wow-view-engine';

export const customerFields: FieldDefinition[] = [
  // 存客户的 id，显示它的名称。
  {
    name: 'customerId',
    label: '客户',
    kind: 'reference',
    remote: 'customers',
  },
  // 冗余在读模型里的名称：行按它排序、在它里面搜索。
  {
    name: 'customerName',
    label: '客户名称',
    kind: 'string',
    sortable: true,
  },
  { name: 'q', label: '搜索', kind: 'search', searchFields: ['customerName'] },
];
```

## 文档

| 想做的事                                           | 读                                                                                                                 |
| -------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------ |
| 判断引擎是否合适，以及它的模型                     | [概览](https://wow.ahoo.me/zh/typescript/view-engine/)、[核心概念](https://wow.ahoo.me/zh/typescript/view-engine/) |
| 写定义：手写，或从查询描述生成                     | [写好一份定义](https://wow.ahoo.me/zh/typescript/view-engine/definitions)                                          |
| 把引擎放进宿主：`ViewHost`、路由、嵌入、措辞、测试 | [宿主](https://wow.ahoo.me/zh/typescript/view-engine/host)                                                         |
| 在记录上提供命令                                   | [声明式操作](https://wow.ahoo.me/zh/typescript/view-engine/actions)                                                |
| 保存视图                                           | [存储](https://wow.ahoo.me/zh/typescript/view-engine/storage)                                                      |
| 主题，以及检查宿主主题                             | [主题](https://wow.ahoo.me/zh/typescript/view-engine/theming)                                                      |
| 在严格的内容安全策略下运行                         | [CSP](https://wow.ahoo.me/zh/typescript/view-engine/csp)                                                           |
| 可访问性                                           | [可访问性](https://wow.ahoo.me/zh/typescript/view-engine/accessibility)                                            |
| 按入口列出的全部导出                               | [参考](https://wow.ahoo.me/zh/reference/typescript/wow-view-engine/)                                               |

设计——模型、内核、运行时与界面上的决定——在 [docs/design/](https://github.com/Ahoo-Wang/Wow/tree/main/typescript/wow-view-engine/docs/design/)，它是模型的唯一依据。

## 入口

| 入口                         | 导出                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
| ---------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `@ahoo-wang/wow-view-engine` | 模型类型与常量；四个纯内核（`validate*` / `compile*` / `project*` 及其旁边的读法）；运行时只导出宿主要握的，不导出它由什么搭成——`ViewEngine`、`validateDefinition`、运行时合同 `ViewRuntime`、`RecordViewRuntime`、`DashboardRuntime`、`AnyViewRuntime` 连同它们签名里出现的每一个类型、`hasResult`、`hasAsked`、`isRecordRuntime`、`actions` 连同声明式操作的类型、写入错误 `ViewWriteError` 与 `ViewCommandError`、`ExportCancelledError`、`RuntimeEnvironment`、`defaultRuntimeEnvironment`、`ViewSource`、`OptionSource`；`ViewStore` 端口、`MemoryViewStore` 与 `localStorageSnapshot`                                                                              |
| `/react`                     | 钩子与无样式控制器，连同它们交出的类型：`useViewEngine`、`useOpenView`、`useViewRuntime`、`useViewList`、`useViewManager`、`useWorkbench`、`useLeaveGuard`、`useFilterEditor`、`useRecordTable`、`useAnalysisEditor`、`useAnalysisResult`、`useDashboard`、`useSaveCommands`、`useRecordActions`（一个记录面上的声明式操作：每条记录与选中的能做什么、等读者回答的确认或表单、唯一的执行器）、`RecordActionSlots`，以及保存命令报告的两个写入结局类型（`SettledWrite`、`RecoveredWrite`）                                                                                                                                                                                |
| `/ui`                        | 默认组件、视图与工作台，连同它们的 props：`DataWorkbench`、`DashboardWorkbench`、`DashboardEditExtensions`、`useDashboardExtensions`、`EmbeddedView`、`EmbeddedDashboard`、`ViewHost`、`bind`、`useEngine`、`useViewNavigation`、`useColorMode`、`ViewHeader`、`SaveActions`、`ViewManager`、`LeaveDialog`、`EditorBand`、`FilterPanel`、`StatusStrip`、`AppliedBar`、`ResultToolbar`、`RowActions`、`RecordTable`、`RecordCards`、`RecordPagination`、`AnalysisTable`、`AnalysisChart`、`DashboardGrid`、`HeadingPanel`、`MarkdownPanel`、`ImagePanel`、`LinksPanel`、`MessagesProvider`；措辞目录 `en` 与 `zhCN`；一个值的读法 `cellValue`、`cellText`、`displayValue` |
| `/testing`                   | `memorySource` 与 `matches`：带 Wow 查询语义的内存 `ViewSource`；`resolveNavigation`：引擎自己按宿主绑定解析离开的路，供宿主测路由；`admit`：按提交的描述符准入宿主的声明；`actionHarness`：按引擎的规则读宿主声明的操作，不画界面——供宿主测试使用（[测试宿主](https://wow.ahoo.me/zh/typescript/view-engine/host#testing)）                                                                                                                                                                                                                                                                                                                                             |
| `/react-router`              | `useReactRouter`：把 React Router 接成视图宿主的路由端口。React Router 是可选 peer，只有这个入口加载它                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| `/styles.css`                | 主题。显式导入；任何 JS 入口都不会引入 CSS，产物也不会在 `.fve-root`／`.fve-tokens` 两个样式边界之外绘制任何东西（preflight 与 base 在构建时收进边界内、不加权重，工具类一律带 `fve:` 前缀、与宿主的不同名，所以宿主的导入次序无关）——预设的复位规则除外，它只在挂了预设的元素上清空 `--fvp-*` 层——`scripts/verify-package.mjs` 在每次构建时逐条核对。                                                                                                                                                                                                                                                                                                                   |
| `/themes.css`                | 预设，可选：只有按 `data-fve-preset` 选中的 `--fvp-*` 赋值（[预设](https://wow.ahoo.me/zh/typescript/view-engine/theming#预设)），由同一个脚本核对。                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
| `/themes/<名>.css`           | 单独一套预设，给只用一套的宿主：就是 `themes.css` 里它那一块（[预设](https://wow.ahoo.me/zh/typescript/view-engine/theming#预设)）。                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
| `/shadcn-bridge.css`         | 可选：把宿主的 shadcn token 读进 `--fvp-*` 变量，`input`、`ring`、状态色、图表色与阴影除外，且只在没挂预设时生效（[桥接](https://wow.ahoo.me/zh/typescript/view-engine/theming#shadcn-桥接)），由同一个脚本核对。                                                                                                                                                                                                                                                                                                                                                                                                                                                        |

一个值只从一个入口导出，打包时就不会出现它的两份副本；次级入口自己的 API 用到的类型也从那里导出（`/react-router` 导出 `ViewRouter`，`/testing` 导出 `ViewDestination`），只导入那个入口的宿主也能写出签名。

这就是公开面，而且逐个名字守着。每个入口把它导出的名字逐个写出，按声明它的文件分组，不整模块转出（`test/architecture.test.ts`），所以文件为包内邻居写的 `export` 不会意外变成公开的。每个代码入口的完整清单——每一个名字，以及它是类型还是值——在 `test/surface/`（`root.txt`、`react.txt`、`ui.txt`、`testing.txt`、`react-router.txt`）：入口多导出了清单上没有的名字、或不再导出清单上有的名字，`test/publicSurface.test.ts` 就失败；`scripts/verify-package.mjs` 再拿同一份清单核对构建出的每个 JS 入口。往清单里加一个名字或拿掉一个，就是改公开面，按改公开面来审。命令也是公开面：`test/surface/bin.txt` 列出 `bin`（`wow-view-engine`）与它认的每个子命令（[`theme-check`](https://wow.ahoo.me/zh/typescript/view-engine/theming#检查一套主题)）。

## 开发

```bash
pnpm --filter @ahoo-wang/wow-view-engine test          # 单元测试、覆盖率与三个 tsc 工程
pnpm --filter @ahoo-wang/wow-view-engine build         # 构建，并核对发布出去的入口
pnpm --filter @ahoo-wang/wow-view-engine test:package  # 入口可导入、核心入口无 DOM 类型、JS 不引入 CSS
pnpm storybook                                             # 每个界面的每种状态，见导航「View Engine」
```

本仓库的 `examples/`（不在 npm 包里）是三个只依赖公开合同、不依赖内部实现的消费者：
`quickstart.ts` 是快速上手里那份定义的代码，由测试准入；
`PlainRecordWorkbench.tsx` 用无样式 HTML 跑通整个闭环；
`FetcherViewStore.ts` 用 `@ahoo-wang/fetcher` 为一个不是 Wow 的后端（带 `If-Match` revision 的 REST API）实现 `ViewStore` 端口——Wow 服务端的 store 是 `WowViewStore`。

`@ahoo-wang/fetcher-viewer` 已弃用，新项目使用本包。两者模型与 API 不同。
