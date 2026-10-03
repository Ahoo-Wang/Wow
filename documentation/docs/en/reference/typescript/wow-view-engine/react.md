---
title: 'React Hooks'
description: 'The /react hooks: opening a view, subscribing to a runtime, the view list, the workbench, write commands and declared actions — @ahoo-wang/wow-view-engine/react'
---

# React Hooks

`/react` is the layer under the default UI: the same controllers, with no look of their own. `/ui`'s components consume exactly this, so a UI built on these hooks behaves as the default one does — the same admission, the same write path, the same recovery actions. To swap one cell or add one button, `/ui`'s slots are enough; come down to this layer when a whole surface is drawn your own way.

What they share:

- **Hooks take an engine, never options.** `useViewEngine` is the one exception: it makes an engine for the lifetime of the component and disposes it on unmount; an application that wants a longer life builds the engine itself and passes it to the other hooks.
- **Commands resolve rather than reject**: `useSaveCommands` and `useViewManager` put the outcome in their state, so a click handler needs no try/catch, and an unresolved write stays visible until the user retries, overwrites or abandons it.
- **Loading is derived**: as long as the answer on hand belongs to another request, this one is still in flight, so an effect writes state only when an answer arrives; one that arrives after that is dropped rather than applied to a view nobody is looking at.

<!-- typecheck-context
declare const engine: import('@ahoo-wang/wow-view-engine').ViewEngine
-->

```tsx
import { systemInstanceId } from '@ahoo-wang/wow-view-engine';
import { useOpenView, useRecordTable, useViewRuntime } from '@ahoo-wang/wow-view-engine/react';

export function ToShipCount() {
  // The system view `to-ship` the `orders` definition declares in code: system:orders:to-ship.
  const { runtime, loading, error } = useOpenView(engine, systemInstanceId('orders', 'to-ship'));
  useViewRuntime(runtime);
  const table = useRecordTable(runtime?.kind === 'record' ? runtime : null);
  if (error) return <p role="alert">{error.code}</p>;
  if (loading || !table.hasResult) return <p>…</p>;
  return <p>{table.rows.length} orders to ship</p>;
}
```

## Opening and subscribing {#api-useOpenView}

| Hook | Role |
|---|---|
| `useViewEngine(options)` | Makes one engine for the lifetime of the component and disposes it on unmount. Options are read once: a definition set and a store are not render-time values |
| `useOpenView(engine, instanceId, scopeFilter?, opening?)` | Opens an instance and owns the runtime it produced: changing the id or unmounting disposes the previous one. A runtime the engine disposed under it (its instance deleted) is not handed out; the id is opened again. `scopeFilter` is in force from the opening query, read when the view opens and then followed, so a fresh object every render reopens nothing; a condition the definition refuses is not in force, and `scopeIssues` says which. `opening` tells a dashboard which tab and filter values it opens with |
| `useViewRuntime(runtime)` | Subscribes to a runtime and answers its snapshot. The runtime commits state before it notifies and returns the same object while nothing changes — exactly what `useSyncExternalStore` asks of a store |

`useOpenView`'s `retry()` opens the same id again: the way back from a failure that may pass (the store unreachable, or answering with an error of its own).

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

## The view list and its management {#api-useViewList}

`useViewList` answers one definition's list, preferences and permissions. They load independently and never block each other: a failed list leaves an open view alone, and failed preferences only drop back to server order. `options.kinds` narrows the list to those kinds before it is ordered and before a default is resolved from it — a workbench draws only the kinds it has parts for, or its sidebar would offer views the body cannot render.

`all` holds the same summaries before that narrowing: a reorder stores one order for the whole definition, and a narrowed workbench submitting only what it sees would drop every other id from `preferences.order`, so a caller that writes the order reads it from `all` and moves the two ids it can see inside it.

`useViewManager` manages the views a list shows rather than the open one: rename, move to the other audience, delete, reorder and choose a default, with the recovery actions for writes no runtime owns. A write that lands reloads the list, because the list is what changed.

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

## The workbench shell {#api-useWorkbench}

`useWorkbench` is the shell of one workbench, without any of its look: how a view is found, opened, left and saved. The kinds are a parameter — what differs between record, analysis and dashboard is the editor and the result they render from `runtime`, not those flows. The one command that reads a kind is `drill`: it opens the records behind one group of an analysis result (it asks the open view to be an analysis, and `record` to be among the kinds this workbench lists).

`useLeaveGuard` is the confirmation between an open view and the next one. Opening another view releases this one's runtime, and the unsaved draft lives nowhere else — so leaving is the deletion of work, asked about once; a view with nothing to lose is never asked about. Switching inside the page and leaving the page are one rule: the sidebar, the switcher and a pushed `instanceId` come through `request`, the tab's own close through `beforeunload` (`guardUnload`, on by default; only a host that owns the whole page should turn it off). An embed never calls it.

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

## Editing and results {#api-useRecordTable}

| Hook | Role |
|---|---|
| `useFilterEditor(runtime)` | Edits the draft filter tree, addressed by path. It holds no state of its own: every action is an `edit` on the runtime, so two editors over one view agree, and undo is not submitting. Over a dashboard there is no tree to edit, and an edit does nothing |
| `useRecordTable(runtime)` | A record view as a table renders it, with no vendor types in sight. Rows and columns come from the last successful result rather than the draft, so changing columns applies at once, and so does a sort while nothing else waits (`toggleSort`); a filter change waits for submit |
| `useAnalysisEditor(runtime)` | Editing an analysis view's draft: its groups, metrics, conditions, sort and limit, and what each may choose from |
| `useAnalysisResult(runtime, analysis, workbench)` | The analysis result as a host draws it: which rows, which chart over them, what the picker offers, and what a press on one group can do next. **The rows are the config that ran; how they are looked at is the draft**: layout and chart are presentation, so switching charts runs nothing |
| `useDashboard(runtime)` | A dashboard as a grid of panels. It reports the applied panels rather than the draft's, because a panel is a running query; geometry is the exception — dragging a panel applies that move alone |

```ts
export declare function useFilterEditor(runtime: ViewRuntime | null): FilterEditorController;
export declare function useRecordTable(runtime: RecordViewRuntime | null): RecordTableController;
export declare function useAnalysisEditor(runtime: ViewRuntime | null): AnalysisEditorController;
export declare function useAnalysisResult(runtime: ViewRuntime<AnalysisViewConfig> | null, analysis: AnalysisEditorController, workbench: Pick<WorkbenchController, 'state' | 'canDrill' | 'drill' | 'follow'>): AnalysisResultController;
export declare function useDashboard(runtime: DashboardRuntime | null): DashboardController;
```

## Write commands {#api-useSaveCommands}

`useSaveCommands` is the write commands of one open view, with the permissions that decide which buttons are live. Every command resolves: the outcome lands in `state`.

```ts
export declare function useSaveCommands(engine: ViewEngine, runtime: ViewRuntime | null): SaveCommands;
```

## Declared actions {#api-useRecordActions}

`useRecordActions` puts declared actions ([`actions()`](./host#api-actions)) on a table the host draws itself: which rows offer which, whether each is available, how a selection splits, and running them.

| Option | Role |
|---|---|
| `table` | The table the actions sit on (`useRecordTable`'s controller satisfies it) |
| `actions` | The declared actions; none leaves only the runner |
| `concurrency` | How many records are in flight at once; a handful by default |
| `refresh` | What reading the view again after a command means here: the table's own by default; a board's record panel reads the whole board again |
| `also` | Records shown beyond the table's page — a record detail opened by a link — whose actions are drawn and whose availability can change on its own as well |
| `onError` | Told of what a command threw on a record, as it was thrown — not of an action's own refusal, nor of an abort |

`RecordActionSlots` (`global`, `bulk`, `row` render functions) are the slots: the host's markup drawn after the declared actions.

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

## The full working version

- A workbench built on these hooks: the engine's [`examples/PlainRecordWorkbench.tsx`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/examples/PlainRecordWorkbench.tsx).
- `/ui`'s components are these hooks' consumers: [`src/ui/DataWorkbench.tsx`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/src/ui/DataWorkbench.tsx); the hooks themselves are in [`src/react/`](https://github.com/Ahoo-Wang/Wow/tree/main/typescript/wow-view-engine/src/react).
- Storybook: [record workbench](/storybook/?path=/docs/view-engine-组件状态-记录工作台--docs).
