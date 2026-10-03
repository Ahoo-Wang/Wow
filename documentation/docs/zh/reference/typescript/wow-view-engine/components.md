---
title: '工作台与嵌入'
description: 'DataWorkbench、DashboardWorkbench、EmbeddedView 与 EmbeddedDashboard 的属性——@ahoo-wang/wow-view-engine/ui'
---

# 工作台与嵌入

`/ui` 给宿主四个现成的界面，按两条轴分：**改变怎样观察**还是**展示别人定好的**，以及**数据视图**还是**看板**。

| | 数据视图（记录与分析） | 看板 |
|---|---|---|
| 工作台：列表、编辑、保存 | `DataWorkbench` | `DashboardWorkbench` |
| 嵌入：只读，放进业务页面 | `EmbeddedView` | `EmbeddedDashboard` |

工作台让用户改变观察的方式；嵌入让一个页面展示某人已经决定好的东西，所以嵌入没有视图列表、没有条件编辑器、不保存——它做的任何事都不写到任何地方。按资源分开的原因相同：宿主嵌入看板时要说出来，把记录视图交给看板入口会被当作它显示不了的视图拒绝。

四个组件都可以省略 `engine`、`messages`、`locale`：缺省取上方 [`ViewHost`](./host#api-ViewHost) 的。

<!-- typecheck: skip — 一个 JSX 片段，宿主见宿主接线页 -->

```tsx
<DataWorkbench definitionId="orders" />
```

相关指南：[把视图引擎接进宿主](../../../guide/typescript/view-engine-host.md#embeds)（嵌入在业务页面里展示决定好的视图）、[视图引擎入门](../../../guide/typescript/view-engine-getting-started.md)（第 8 步把 `DataWorkbench` 放进页面）、[视图引擎的主题](../../../guide/typescript/view-engine-theming.md)（四个界面的外观）。

## DataWorkbench {#api-DataWorkbench}

一份数据定义的工作台：记录视图与分析视图在同一个列表里，用户像在任意两个视图之间一样切换。

| 属性 | 作用 |
|---|---|
| `definitionId` | 要画的定义 |
| `instanceId`、`onInstanceChange` | 打开哪个视图，像输入框的 `value`：不传则工作台从有效缺省开始自己管；传了——字符串，或 `null` 表示缺省——就由宿主的路由做主，之后每次变化都打开它指的视图。它经过离开确认，所以推过来的视图不会不问就丢掉未保存的草稿。`onInstanceChange` 用同样的词汇报告，宿主可以直接放进路由 |
| `viewKinds` | 列出并能画哪些种类，顺序即「新建视图」菜单的顺序。缺省两种都有；只写一种是真正的收窄——另一种既不列出也打不开 |
| `record` | 宿主对记录视图说的话（[`RecordViewProps`](#api-RecordViewProps)）：业务操作、单元格读法、能否选行、空结果说什么。绑定（`bind` 的 `actions`、`slots`、`reading`）是各项的缺省，这里给的优先 |
| `features` | 工作台自己的哪些控件出现在屏幕上：`export`、`layouts`、`columns`、`sort`、`search`、`visualization`、`manage`。缺省全开；关掉的是不存在，不是禁用 |
| `templates` | 新建各种视图时从什么开始，缺省是 `defaultRecordConfig` 与 `defaultAnalysisConfig`。视图仍然以未保存打开，首次保存时问名字和受众 |
| `handOver` | 看板或嵌入交给宿主路由的一个视图，按它来的样子在这里打开：保存的视图带着读者在看板上设的条件（「已修改」，可逐个去掉，「还原」全部去掉），或没人保存过的视图。每个新对象经离开确认打开一次 |
| `onNavigate` | 回到交出视图的那个看板的路：有了它，工作台在标题栏下画「返回〈仪表盘〉」。缺省用 `ViewHost` 的路由 |
| `expandable` | 是否提供铺满屏幕，缺省开 |
| `landmark` | 工作列是什么地标：缺省 `main`；页面已经有自己的 `<main>` 时写 `region` |
| `defaultSidebarOpen`、`onSidebarOpenChange` | 侧栏起始状态：视图状态，不保存、离开确认也不问。缺省窄于 `md` 时折叠 |
| `density`、`preset`、`theme`、`tokens` | 这个界面及其弹层的密度、固定的预设、明暗、宿主自己的 `--fve-*` |
| `onRenderFailure` | 某个边界接住渲染失败时告诉宿主；那部分照常原地显示可恢复的错误状态 |

```ts
export interface DataWorkbenchProps {
  defaultSidebarOpen?: boolean;
  definitionId: string;
  density?: ViewDensity;
  engine?: ViewEngine;
  expandable?: boolean;
  features?: WorkbenchFeatures;
  handOver?: ViewHandOver | null;
  instanceId?: string | null;
  landmark?: WorkbenchLandmark;
  locale?: string;
  messages?: ViewMessages;
  onInstanceChange?(id: string | null): void;
  onNavigate?(to: ViewNavigation): void;
  onRenderFailure?: RenderFailureHandler;
  onSidebarOpenChange?(open: boolean): void;
  optionsFor?(remote: string): FieldOption[] | undefined;
  preset?: ViewPreset;
  record?: RecordViewProps;
  templates?: { record?: RecordViewConfig; analysis?: AnalysisViewConfig };
  theme?: ViewTheme;
  tokens?: ViewSurfaceProps['tokens'];
  viewKinds?: readonly DataViewKind[];
}
export declare function DataWorkbench(props: DataWorkbenchProps): import('react').JSX.Element;
```

### RecordViewProps {#api-RecordViewProps}

宿主对工作台所画记录视图说的话。它们是一个对象，因为没有一项对分析视图有意义（分析视图没有业务操作）。

| 属性 | 作用 |
|---|---|
| `actions`、`slots` | 声明的操作与宿主自己的标记（见[声明的操作](./host#api-actions)） |
| `detail` | 记录详情：打开哪条记录（给把它放进地址的宿主），以及宿主自己的分节。不写时详情归工作台自己——点行打开、关闭即关——只含定义的字段分组 |
| `renderCell` | 渲染表格的一个单元格，其余照常。它没什么特别要说的单元格就回落到 `cellValue`，枚举标签、时区和数字格式照常工作 |
| `selectable` | 能否选行，缺省开；宿主对选择没什么可做时关掉，免得一列复选框无处可去 |
| `emptyTitle`、`emptyDescription`、`emptyAction` | 宿主自己的话说空结果；`emptyAction` 是唯一按钮做什么，`null` 不要按钮 |
| `onExported` | 每次导出交给浏览器时告诉宿主：文件名、内容、哪个范围多少行。要审计什么离开了应用的宿主读它 |

```ts
export interface RecordViewProps {
  actions?: RecordActions;
  detail?: RecordDetailOptions;
  emptyAction?: (() => void) | null;
  emptyDescription?: string;
  emptyTitle?: string;
  onExported?(file: ExportedFile): void;
  renderCell?(cell: RecordCell): import('react').ReactNode;
  selectable?: boolean;
  slots?: RecordActionSlots;
}
```

## DashboardWorkbench {#api-DashboardWorkbench}

一份看板定义的工作台：浏览、编辑、保存看板。看板上的筛选值属于读者，从不写进看板的配置——宿主想把它们放进地址，就听 `onFiltersChange`、`onTabChange`；包本身从不碰地址。

| 属性 | 作用 |
|---|---|
| `instanceId`、`onInstanceChange` | 同 `DataWorkbench` |
| `initialTab`、`onTabChange` | 打开在哪个页签，按宿主路由给的；不写或看板没有该页签时，打开在读者上次读它的地方 |
| `initialFilters`、`onFiltersChange` | 筛选打开时的值，按宿主地址给的；不写时每个筛选从缺省开始，看板不接受的部分被略过 |
| `onNavigate` | 离开看板的每条路——面板上的「在工作台中打开」、分组上的追问菜单、面板的自定义目的地——都经过它。没有它这些都不存在：除非面板做交叉筛选，按一个分组什么也不发生 |
| `features` | 只有 `manage`（视图管理器）与 `export`（记录面板的「导出数据…」）；数据不能离开页面的看板关掉 `export` |
| `template` | 新看板从什么开始，缺省为空看板 |

其余属性（`definitionId`、`expandable`、`landmark`、侧栏、外观、`onRenderFailure`）与 `DataWorkbench` 相同。

```ts
export interface DashboardWorkbenchProps {
  defaultSidebarOpen?: boolean;
  definitionId: string;
  density?: ViewDensity;
  engine?: ViewEngine;
  expandable?: boolean;
  features?: Pick<WorkbenchFeatures, 'manage' | 'export'>;
  initialFilters?: DashboardFilters | null;
  initialTab?: string | null;
  instanceId?: string | null;
  landmark?: WorkbenchLandmark;
  locale?: string;
  messages?: ViewMessages;
  onFiltersChange?(filters: DashboardFilters): void;
  onInstanceChange?(id: string | null): void;
  onNavigate?(to: ViewNavigation): void;
  onRenderFailure?: RenderFailureHandler;
  onSidebarOpenChange?(open: boolean): void;
  onTabChange?(tabId: string | null): void;
  optionsFor?(remote: string): FieldOption[] | undefined;
  preset?: ViewPreset;
  template?: DashboardViewConfig;
  theme?: ViewTheme;
  tokens?: ViewSurfaceProps['tokens'];
}
export declare function DashboardWorkbench(props: DashboardWorkbenchProps): import('react').JSX.Element;
```

## 两种嵌入共有的属性 {#api-EmbedBaseProps}

嵌入丢掉的都是界面外壳；准入、分页、自动刷新和请求预算归运行时，与工作台完全相同。

| 属性 | 作用 |
|---|---|
| `instanceId` | 要显示的保存视图或看板；代码声明的系统视图也行 |
| `interaction` | 读者能走多远（[`EmbedInteraction`](#api-EmbedInteraction)），缺省 `static` |
| `withTitle`、`headingLevel` | 是否以 `headingLevel` 级标题画出它的标题（缺省关：页面通常用自己的话称呼嵌入的东西）；级别缺省 `2`，只有宿主知道自己的大纲 |
| `autoRefresh` | 是否按作者保存的间隔自刷新，缺省开。关掉时计时器从不运行，界面上也不再提供 |
| `expandable` | 交互档里第一行的「铺满屏幕」，缺省关；静态档从不提供 |
| `openInWorkbench` | 交互档里是否提供「在工作台中打开」（需要能走的路由），缺省开；静态档从不提供 |
| `onNavigate` | 宿主的路由：「在工作台中打开」、分组上的追问菜单与面板目的地都经过它。这里、`ViewHost` 的 `router` 或 `navigate` 都没有时，这些都不存在 |
| `size` | 高度：`content`（缺省）或 `fill` |
| `ref` | 它画在上面的那个元素；想在自己的外壳里放铺满屏幕控件的宿主把 `useViewExpansion` 指向它 |

```ts
export interface EmbedBaseProps {
  autoRefresh?: boolean;
  className?: string;
  density?: ViewDensity;
  engine?: ViewEngine;
  expandable?: boolean;
  headingLevel?: PanelHeadingLevel;
  instanceId: string;
  locale?: string;
  messages?: ViewMessages;
  onNavigate?(to: ViewNavigation): void;
  onRenderFailure?: RenderFailureHandler;
  openInWorkbench?: boolean;
  preset?: ViewPreset;
  ref?: import('react').Ref<HTMLDivElement>;
  size?: EmbedSize;
  theme?: ViewTheme;
  tokens?: ViewSurfaceProps['tokens'];
  withTitle?: boolean;
}
```

### 交互档 {#api-EmbedInteraction}

两档都不写任何东西：嵌入从不保存视图、看板或偏好。

- `static`——页面显示的就是作者保存的样子：行、图或看板，以及取数时的条件；上面没有东西能筛选、排序、翻页、重画或通往别处。这是缺省，因为业务页面显示的是别人设好的东西。
- `interactive`——读者可以凑近看，仅限这次浏览：改看板的筛选、按表头排序、翻页、在表格与图表之间切换、按一个分组（追问菜单、看板的交叉筛选）、在宿主提供时铺满屏幕、在工作台中打开。都不保存；离开页面的路走宿主的路由。

```ts
export type EmbedInteraction = 'static' | 'interactive';
```

## EmbeddedView {#api-EmbeddedView}

业务页面里的一个保存的记录或分析视图：结果，加上宿主打开的开关。

| 属性 | 作用 |
|---|---|
| `scopeFilter` | 与视图自己的条件 AND 的外层条件，用视图的字段名写：页面的收窄，锁定——读者在已应用条里看得见、去不掉。它像用户自己的条件一样准入，所以宿主不能把视图放宽到定义允许之外，也从不进入保存的配置 |
| `withSearch` | 定义声明了搜索字段时，在已应用条末端放视图的搜索框（缺省关）。仅记录视图、仅交互档 |
| `withExport` | 第一行的导出按钮与窗口（缺省关）。仅记录视图、仅交互档；有了它行可以被选，因为窗口提供只导出选中的 |
| `detail` | 从行打开的记录详情（缺省关）：`true` 按宿主对定义的读法（`bind` 的 `reading`）打开，打开的记录归这个嵌入自己，不写宿主地址；`RecordDetailOptions` 让宿主握住打开哪条并加自己的分节。仅记录视图、仅交互档；只读 |
| `rowActions` | 宿主在记录视图一行上提供的东西；这里没有可以命令的视图，所以插槽只拿到行 |

`scopeFilter` 与锁定的看板筛选都**不是安全边界**：它们决定页面显示什么，不决定读者被允许读什么——授权归服务端。

```ts
export interface EmbeddedViewProps extends EmbedBaseProps {
  detail?: boolean | RecordDetailOptions;
  interaction?: EmbedInteraction;
  rowActions?(row: RecordRow): import('react').ReactNode;
  scopeFilter?: FilterTree | null;
  withExport?: boolean;
  withSearch?: boolean;
}
export declare function EmbeddedView(given: EmbeddedViewProps): import('react').JSX.Element;
```

## EmbeddedDashboard {#api-EmbeddedDashboard}

业务页面里的一个保存的看板：看板、它的筛选条（每个筛选按页面给的模式），以及宿主打开的开关。它读看板，从不写：没有「编辑」、没有保存、没有另存为、没有偏好——读者改的东西只活在这次浏览里。搭建看板是 `DashboardWorkbench` 的事。

| 属性 | 作用 |
|---|---|
| `filterModes` | 每个筛选按名字怎样提供：`adjustable`（缺省，在条上，归读者）、`locked`（在条上显示它的值，固定）、`hidden`（不在条上，仍然收窄它接到的面板）。锁定或隐藏的筛选持有 `pageValues` 给的值，或它的缺省 |
| `groupingMode` | 时间分组的模式，同上；缺省 `adjustable` |
| `pageValues` | 页面持有的值：每个锁定或隐藏筛选的值，以及 `groupingMode` 锁定或隐藏时时间分组的单位。从第一次查询起生效，并随它变化——客户页翻到下一个客户。它属于页面，从不属于地址（读者能改地址） |
| `initialFilters`、`onFiltersChange` | 读者的筛选打开时的值（来自宿主地址），以及变化时告诉宿主——只有可调的筛选，锁定或隐藏的值属于页面 |
| `initialTab`、`onTabChange` | 打开在哪个页签，以及页签变化 |
| `withPanelTitles` | 是否画面板标题，缺省开 |
| `withRefresh` | 第一行的「更新于 10:32」，交互档里旁边还有刷新按钮（缺省关）；不提供间隔，间隔归作者和 `autoRefresh` |
| `withExport` | 面板「⋯」里的「导出数据…」（缺省关）；静态档无效 |
| `caption` | 看板铺满屏幕时画在标题下的说明，宿主的话；页面上由宿主自己画在自己的标题旁 |

```ts
export interface EmbeddedDashboardProps extends EmbedBaseProps {
  caption?: import('react').ReactNode;
  filterModes?: Readonly<Record<string, DashboardFilterMode>>;
  groupingMode?: DashboardFilterMode;
  initialFilters?: DashboardFilters | null;
  initialTab?: string | null;
  interaction?: EmbedInteraction;
  onFiltersChange?(filters: DashboardFilters): void;
  onTabChange?(tabId: string | null): void;
  pageValues?: DashboardFilters | null;
  withExport?: boolean;
  withPanelTitles?: boolean;
  withRefresh?: boolean;
}
export declare function EmbeddedDashboard(given: EmbeddedDashboardProps): import('react').JSX.Element;
```

## 完整可运行版本

- Storybook：[记录工作台](/storybook/?path=/docs/view-engine-组件状态-记录工作台--docs)、[仪表盘](/storybook/?path=/docs/view-engine-组件状态-仪表盘--docs)、[EmbeddedView](/storybook/?path=/docs/view-engine-组件状态-embeddedview--docs)、[EmbeddedDashboard](/storybook/?path=/docs/view-engine-组件状态-embeddeddashboard--docs)。
- 源文件：[`DataWorkbench.tsx`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/src/ui/DataWorkbench.tsx)、[`DashboardWorkbench.tsx`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/src/ui/DashboardWorkbench.tsx)、[`EmbeddedView.tsx`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/src/ui/EmbeddedView.tsx)、[`EmbeddedDashboard.tsx`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/src/ui/EmbeddedDashboard.tsx)、[`embed/options.ts`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/src/ui/embed/options.ts)。
