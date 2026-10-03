---
title: 'React Hooks'
description: '/react 入口的 Hooks：打开视图、订阅运行时、视图列表、工作台、写入命令与声明的操作——@ahoo-wang/wow-view-engine/react'
---

# React Hooks

`/react` 是默认界面之下的那一层：同样的控制器，不带任何外观。`/ui` 的组件消费的正是它，所以用这些 Hooks 自己搭界面，行为与默认界面一致——同一套准入、同一条写入路径、同样的恢复动作。只想换掉一个单元格或加一个按钮时用 `/ui` 的插槽就够了；整块界面要自己画时才来这一层。

它们共同的约定：

- **Hooks 接收引擎，从不接收选项**。`useViewEngine` 是唯一的例外，它为组件的生命周期建一个引擎，卸载时释放；需要更长生命期的应用自己建引擎，再交给其余 Hooks。
- **命令 resolve 而不 reject**：`useSaveCommands` 与 `useViewManager` 把结局放进状态，点击处理器不需要 try/catch，没解决的写入一直可见，直到用户重试、覆盖或放弃。
- **加载状态是推导的**：只要手上的回答属于另一次请求，这一次就还在进行，所以 effect 只在回答到达时写状态；在它之后才到的回答被丢弃，而不是套到没人在看的视图上。

<!-- typecheck-context
declare const engine: import('@ahoo-wang/wow-view-engine').ViewEngine
-->

```tsx
import { systemInstanceId } from '@ahoo-wang/wow-view-engine';
import { useOpenView, useRecordTable, useViewRuntime } from '@ahoo-wang/wow-view-engine/react';

export function ToShipCount() {
  // 定义 orders 在代码里声明的系统视图 to-ship：system:orders:to-ship。
  const { runtime, loading, error } = useOpenView(engine, systemInstanceId('orders', 'to-ship'));
  useViewRuntime(runtime);
  const table = useRecordTable(runtime?.kind === 'record' ? runtime : null);
  if (error) return <p role="alert">{error.code}</p>;
  if (loading || !table.hasResult) return <p>…</p>;
  return <p>待发货 {table.rows.length} 张</p>;
}
```

相关指南：[视图引擎的核心概念](../../../guide/typescript/view-engine-concepts.md)（这些 Hook 打开与订阅的视图与运行时）、[声明式操作](../../../guide/typescript/view-engine-actions.md#slots)（只换一个单元格或按钮时的插槽）。

## 打开与订阅 {#api-useOpenView}

| Hook | 作用 |
|---|---|
| `useViewEngine(options)` | 为组件的生命周期建一个引擎，卸载时释放。选项只读一次：定义集合与存储不是渲染期的值 |
| `useOpenView(engine, instanceId, scopeFilter?, opening?)` | 打开一个实例并拥有它产生的运行时：换 id 或卸载时释放前一个。被引擎释放的运行时（实例被删）不会交出，id 会再开一次。`scopeFilter` 从第一次查询起生效，开时读一次之后跟随，每次渲染传新对象也不会重开；定义拒绝的条件不生效，`scopeIssues` 说明哪个没生效。`opening` 给看板说打开在哪个页签、筛选是什么 |
| `useViewRuntime(runtime)` | 订阅一个运行时，返回快照。运行时先提交状态再通知，没变化时返回同一个对象——正是 `useSyncExternalStore` 对存储的要求 |

`useOpenView` 的 `retry()` 再开同一个 id：给可能会过去的失败（存储不可达，或服务端答了自己的错误）一条回头路。

```ts
export declare function useViewEngine(options: ViewEngineOptions): ViewEngine;
export declare function useOpenView(engine: ViewEngine, instanceId: string | null, scopeFilter?: FilterTree | null, opening?: (instanceId: string) => Omit<OpenOptions, 'scopeFilter'> | undefined): OpenViewState;
export declare function useViewRuntime<R extends ViewRuntimeStore<unknown>>(runtime: R | null): SnapshotOf<R> | null;

export interface OpenViewState {
  error: Issue | null;
  loading: boolean;
  retry(): void;
  runtime: AnyViewRuntime | null;
  scopeIssues: Issue[];
}
```

## 视图列表与管理 {#api-useViewList}

`useViewList` 给出一份定义的列表、偏好与权限。它们各自加载、互不阻塞：列表失败不影响已打开的视图，偏好失败只是退回服务端顺序。`options.kinds` 在排序与解析缺省之前把列表收窄到这些种类——工作台只画它有部件的种类，否则侧栏会提供画不出来的视图。

`all` 是收窄前的同一批摘要：调整顺序存的是整份定义的一个顺序，收窄的工作台只提交它看得见的会把其他 id 从 `preferences.order` 里丢掉，所以写顺序的调用方从 `all` 读，只在其中移动它看得见的两个 id。

`useViewManager` 管列表上的视图而不是打开着的那个：改名、换受众、删除、调整顺序、选缺省，以及没有运行时拥有的写入的恢复动作。落地的写入会重新加载列表，因为变的就是列表。

```ts
export declare function useViewList(engine: ViewEngine, definitionId: string, options?: ViewListOptions): ViewListState;
export declare function useViewManager(engine: ViewEngine, definitionId: string, list: ViewListState): ViewManagerController;

export interface ViewListState {
  all: ViewInstanceSummary[];
  defaultInstanceId: string | null;
  error: Issue | null;
  items: ViewInstanceSummary[];
  loading: boolean;
  permissions: ViewPermissions;
  preferences: ViewPreferences | null;
  preferencesError: Issue | null;
  preferencesSettled: boolean;
  reload(options?: ViewListReloadOptions): void;
}
```

## 工作台外壳 {#api-useWorkbench}

`useWorkbench` 是一个工作台的外壳，不带任何外观：视图怎样被找到、打开、离开、保存。种类是参数——记录、分析与看板的不同在于它们从 `runtime` 渲染的编辑器与结果，而不在这些流程。唯一读种类的是 `drill`：打开分析结果一个分组背后的记录（它要求打开的是分析视图、且 `record` 在这个工作台列出的种类里）。

`useLeaveGuard` 是一个打开的视图与下一个之间的那次确认。打开另一个视图会释放这个视图的运行时，而未保存的草稿只活在运行时里——所以离开就是删除工作，问一次；没有东西可丢的视图从不问。页面内切换与离开页面是同一条规则：侧栏、切换器和推过来的 `instanceId` 走 `request`，标签页自己的关闭走 `beforeunload`（`guardUnload`，缺省开；只有拥有整个页面的宿主才该关掉它）。嵌入从不调用它。

```ts
export declare function useWorkbench(engine: ViewEngine, definitionId: string, options: WorkbenchOptions): WorkbenchController;
export declare function useLeaveGuard(state: LeaveGuardState | null, input?: LeaveGuardOptions): LeaveGuard;

export interface WorkbenchOptions {
  guardUnload?: boolean;
  handOver?: ViewHandOver | null;
  instanceId?: string | null;
  kinds: readonly ViewKind[];
  newView?: NewViewOptions;
  onDrilldown?(target: DrillTarget): void;
  onInstanceChange?(id: string | null): void;
  onNavigate?(to: ViewNavigation): void;
  opening?(instanceId: string): DashboardOpening | undefined;
}
```

## 编辑与结果 {#api-useRecordTable}

| Hook | 作用 |
|---|---|
| `useFilterEditor(runtime)` | 按路径编辑草稿的条件树。它自己不持有状态：每个动作都是对运行时的一次 `edit`，所以同一视图上的两个编辑器意见一致，撤销就是不提交。看板上没有可编辑的树，编辑什么也不做 |
| `useRecordTable(runtime)` | 表格渲染的记录视图，看不到任何第三方类型。行和列来自最后一次成功的结果而不是草稿，所以改列立即生效，没有别的在等时排序也立即生效（`toggleSort`），改条件要等提交 |
| `useAnalysisEditor(runtime)` | 分析视图的草稿编辑：分组、指标、条件、排序与条数，以及它们各自能选什么 |
| `useAnalysisResult(runtime, analysis, workbench)` | 宿主画出的分析结果：哪些行、上面画哪张图、选图器提供什么、按一个分组接下来能做什么。**行是运行过的配置，怎么看是草稿**：版式和图表是呈现，换图不重跑 |
| `useDashboard(runtime)` | 看板作为面板网格。它报告已应用的面板而不是草稿的，因为面板是正在运行的查询；几何是例外——拖动一个面板只应用那次移动 |

```ts
export declare function useFilterEditor(runtime: ViewRuntime | null): FilterEditorController;
export declare function useRecordTable(runtime: RecordViewRuntime | null): RecordTableController;
export declare function useAnalysisEditor(runtime: ViewRuntime | null): AnalysisEditorController;
export declare function useAnalysisResult(runtime: ViewRuntime<AnalysisViewConfig> | null, analysis: AnalysisEditorController, workbench: Pick<WorkbenchController, 'state' | 'canDrill' | 'drill' | 'follow'>): AnalysisResultController;
export declare function useDashboard(runtime: DashboardRuntime | null): DashboardController;
```

## 写入命令 {#api-useSaveCommands}

`useSaveCommands` 是一个打开视图的写入命令，连同决定哪些按钮可用的权限。每个命令都 resolve：结局落进 `state`。

```ts
export declare function useSaveCommands(engine: ViewEngine, runtime: ViewRuntime | null): SaveCommands;
```

## 声明的操作 {#api-useRecordActions}

`useRecordActions` 把声明的操作（[`actions()`](./host#api-actions)）放到宿主自己画的表格上：哪些行提供哪个、可不可用、选择怎样分组，以及执行。

| 选项 | 作用 |
|---|---|
| `table` | 操作所在的表格（`useRecordTable` 的控制器满足它） |
| `actions` | 声明的操作；不传只剩执行器 |
| `concurrency` | 同时在途的记录数，缺省几条 |
| `refresh` | 命令之后「重读视图」在这里是什么意思：缺省是表格自己的；看板的记录面板重读整块看板 |
| `also` | 表格当前页以外显示的记录——由链接打开的记录详情——它们的操作也要画，可用性也会自己变化 |
| `onError` | 命令在某条记录上抛出的东西，原样告诉宿主——不包括操作自己的拒绝，也不包括中止 |

`RecordActionSlots`（`global`、`bulk`、`row` 三个渲染函数）是插槽：画在声明的操作之后的宿主标记。

```ts
export declare function useRecordActions(input: RecordActionsOptions): RecordActionsController;

export interface RecordActionsOptions {
  actions?: RecordActions;
  also?: readonly RecordRow[];
  concurrency?: number;
  onError?(error: unknown, context: ActionFailureContext): void;
  refresh?(): void;
  table: RecordActionTable;
}

export interface RecordActionSlots {
  bulk?(context: RecordBulkActionContext): import('react').ReactNode;
  global?(context: RecordGlobalActionContext): import('react').ReactNode;
  row?(context: RecordRowActionContext): import('react').ReactNode;
}
```

## 完整可运行版本

- 用这些 Hooks 搭的一个工作台：引擎源码里的 [`examples/PlainRecordWorkbench.tsx`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/examples/PlainRecordWorkbench.tsx)。
- `/ui` 的组件就是这些 Hooks 的消费者：[`src/ui/DataWorkbench.tsx`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/src/ui/DataWorkbench.tsx)；Hooks 本身在 [`src/react/`](https://github.com/Ahoo-Wang/Wow/tree/main/typescript/wow-view-engine/src/react)。
- Storybook：[记录工作台](/storybook/?path=/docs/view-engine-组件状态-记录工作台--docs)。
