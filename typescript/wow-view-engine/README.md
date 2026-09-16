# Fetcher View Engine

> **Status: rewrite in progress.** This README describes the target package defined in [docs/design.md](docs/design.md). The public API below is the design target; delivery order and what is implemented at any point are tracked in design §13. The previous implementation is frozen at the git tag `view-engine-legacy` (`a064fc1a`) for reference only.

**Fetcher View Engine is a data view engine for Wow-based business applications.** The application declares in code _how a dataset can be observed_: fields, kinds, operators, available groupings and metrics. Users decide in the UI _how to observe it this time_: filters, columns, sorting, groupings, charts, panel composition. The engine compiles that way of observing into Wow queries, runs them, renders the result, and saves the ways worth keeping so they can be reopened with one click.

It is the presentation layer on top of `@ahoo-wang/fetcher-wow` and the successor of `@ahoo-wang/fetcher-viewer`.

## The problem

Most pages in a business system are the same page: a list with filters, sorting and paging, sometimes with a chart. Every business object (orders, inventory, customers, tickets) gets its own copy. Every request from operations, "add one more filter", "group this by warehouse", "put these tables on one overview page", means a code change, a sprint slot and a release.

The data did not change; only the way of observing it did. The problem is that the way of observing is hard-coded in page code: users cannot adjust it themselves, developers are consumed by repetition, and product treats every presentation tweak as a feature request.

## Goals

- **For users.** Adjust scope, organization and presentation within declared capabilities, save the ways of observing that matter, and reopen them with one click.
- **For developers.** Integrate a business object once, one definition plus one query client, and stop writing pages for its list, analysis and overview views; filter editing, query coordination, result rendering, save and restore and conflict handling are provided once by the engine.
- **For evolution.** Adding a business object adds a definition, never a business branch inside the engine; custom layouts reuse the same behavior through headless hooks instead of copying logic.

## Value

| For            | What they get                                                                                                                                                                             |
| -------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Business users | The view they need without waiting for a sprint; saved views open instantly; the same data as details, grouped statistics or an overview                                                  |
| Developers     | List pages go from "one per object" to "one definition per object"; filtering, paging, sorting, saving and conflicts are implemented once; definitions can be generated from Wow metadata |
| Product        | Presentation changes within the supported range become configuration, not requirements; "save and share views" ships as a product capability                                              |

## Scenarios

| Scenario                                       | View         | What the user does                                                                                                           |
| ---------------------------------------------- | ------------ | ---------------------------------------------------------------------------------------------------------------------------- |
| Warehouse staff find today's pending orders    | Record       | Filter status = pending, sort by creation time, keep only the needed columns, save as "Pending today"                        |
| A manager compares backlog across warehouses   | Analysis     | Group by warehouse, count and sum amounts, switch to a bar chart                                                             |
| Operations review the week                     | Dashboard    | Place both views on one panel page and constrain them with a global time range                                               |
| A slice of data inside a business page         | EmbeddedView | A developer embeds a saved view into the order detail page, read-only, without the workbench                                 |
| Operators seed baseline views for a new object | System views | Declare "All", "Pending" and "New this week" in the definition; users open a usable view at once and save variants as needed |
| Customers build their own reports              | All          | Customers save and share views within their tenant; the vendor ships no release for it                                       |

## What it is not

Not a database or compute backend, not a permission system, not a general low-code page builder, not a BI modeling tool. Data, aggregation capabilities and authorization come from the business services; the engine only makes ways of observing run reliably.

## Install

The package has not had its first public registry release; the stable release scripts skip it on purpose. Until then, consume it from this workspace (`"@ahoo-wang/fetcher-view-engine": "workspace:^"`) or from a local `pnpm pack` archive. Once published, installation will be:

```bash
pnpm add @ahoo-wang/fetcher-view-engine @ahoo-wang/fetcher-wow
```

Peer dependencies `react` and `react-dom` are required only for the `/react` and `/ui` entries. The root entry runs in Node.

## Design principles

| Fact                            | Consequence                                                                                                                          |
| ------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------ |
| **Definitions are code.**       | No definition service or definition versions. A definition change is a deploy; saved views are validated on open.                    |
| **Configs are data.**           | The only persisted objects are `ViewInstance` and personal preferences. Consistency is optimistic revision + idempotent `requestId`. |
| **Runtime state is transient.** | Drafts, results, paging and selection live in one open `ViewRuntime` and are never persisted.                                        |

## Quick start

### 1. Declare a definition

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
        { value: 'PENDING', label: 'Pending' },
        { value: 'SHIPPED', label: 'Shipped' },
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
  // System views: baseline views configured by developers or operators.
  // Deployed with the definition, visible to everyone, read-only, can be saved as.
  views: [
    {
      id: 'pending',
      title: 'Pending',
      config: {
        kind: 'record',
        filter: {
          op: 'and',
          children: [{ field: 'status', operator: 'EQ', value: 'PENDING' }],
        },
        filterMode: 'simple',
        refresh: { interval: null },
        sort: [{ field: 'createdAt', direction: 'DESC' }],
        pageSize: 20,
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

### 2. Create an engine

```ts
import {
  createViewEngine,
  MemoryViewStore,
} from '@ahoo-wang/fetcher-view-engine';

const engine = createViewEngine({
  definitions: [orders],
  store: new MemoryViewStore(),
  // Pick<QueryApi, 'paged' | 'cursor' | 'aggregate'> from @ahoo-wang/fetcher-wow
  resolveSource: key => queryClients[key],
});
```

### 3a. Render the default workbench

```tsx
import '@ahoo-wang/fetcher-view-engine/styles.css';
import { Workbench } from '@ahoo-wang/fetcher-view-engine/ui';

export function OrdersPage() {
  return <Workbench engine={engine} definitionId="orders" />;
}
```

### 3b. Or compose your own UI

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
  const state = useViewRuntime(runtime); // draft, applied, result, issues, dirty
  const filter = useFilterEditor(runtime); // nodes, add/remove/edit, apply
  const table = useRecordTable(runtime); // columns, sort, selection, paging
  // Render any layout from these controllers. No engine internals required.
}
```

### Core only, no React

```ts
import {
  builtinFieldKinds,
  compileRecord,
  projectRecord,
  validateRecord,
} from '@ahoo-wang/fetcher-view-engine';

const issues = validateRecord(orders, config, builtinFieldKinds);
if (issues.some(i => i.severity === 'error')) throw new Error('invalid config');

const query = compileRecord(
  orders,
  config,
  builtinFieldKinds,
  { now: new Date(), timeZone: 'Asia/Shanghai' },
  { index: 1 }, // Wow pages start at 1
);
const page = await source.paged(query);
const view = projectRecord(orders, config, page);
```

## Concepts

| Type             | Role                                                                                                                                                                                                                                                                                                                                                                                               | Lives in |
| ---------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------- |
| `ViewDefinition` | Fields, kinds, operators, record and analysis capabilities. Declared or generated, never edited at runtime.                                                                                                                                                                                                                                                                                        | code     |
| `ViewConfig`     | `RecordViewConfig` / `AnalysisViewConfig` / `DashboardViewConfig` discriminated union. A shared `FilterTree` describes scope. It is an intent model: it stores semantics such as "last 7 days" rather than compiled values, and never a UI component name. Analysis covers the whole Wow aggregation protocol; dashboard panels are either view panels or Markdown, image and link content panels. | data     |
| `ViewInstance`   | A saved `ViewConfig` plus id, title, scope and an opaque `revision`. Scope is system, shared or personal.                                                                                                                                                                                                                                                                                          | store    |
| `ViewRuntime`    | One open view: draft, applied config, result, status, selection. `subscribe` / `getSnapshot`.                                                                                                                                                                                                                                                                                                      | memory   |
| `ViewEngine`     | Registry of definitions, the store and open runtimes; entry point for open, save, list commands.                                                                                                                                                                                                                                                                                                   | memory   |
| `ViewStore`      | Eight-method persistence port. Ship your own for your backend.                                                                                                                                                                                                                                                                                                                                     | app      |
| `FieldKind`      | Operators, validation, compilation and editor descriptor for one field type.                                                                                                                                                                                                                                                                                                                       | registry |

## View management

- **Lifecycle.** Create, save, save as, rename and delete are all `ViewEngine` commands; the default UI and custom compositions share one path. Only the config is saved, never selection, page or results.
- **Three scopes.** `system` views are configured by developers or operators as the baseline and common views of a definition: visible to everyone, read-only, can be saved as; declare them in code under `definition.views` or return them from the server. `shared` views are created by users with permission and visible to everyone on the definition. `personal` views are visible to their owner only.
- **Permissions.** `store.permissions()` supplies permissions synchronously and only drives button availability. The server is the authority. Shared views you cannot edit and system views can still be saved as personal ones.
- **List and preferences.** List, preferences and permissions load independently and never block each other; personal ordering and the default view live in `ViewPreferences`, and deleting an instance never rewrites them.
- **Conflicts and unknown outcomes.** On a revision conflict choose reload or overwrite, or save as; when a request was sent but its outcome is unknown, retry with the same `requestId`. The draft is always kept.
- **Leave protection.** Closing a view with an unsaved draft or an unknown write asks for confirmation; navigation never cancels an in-flight write.

Details in [docs/design.md](docs/design.md) §7.

## Entries

| Entry                            | Exports                                                                                                                                                                   |
| -------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `@ahoo-wang/fetcher-view-engine` | Model types, pure kernels (`validate*` / `compile*` / `project*`), runtime, `ViewStore`, `MemoryViewStore`                                                                |
| `/react`                         | `useViewEngine`, `useOpenView`, `useViewRuntime`, `useFilterEditor`, `useRecordTable`, `useAnalysisEditor`, `useDashboard`, `useSaveCommands`                             |
| `/ui`                            | `Workbench`, `FilterPanel`, `RecordTable`, `RecordCards`, `AnalysisEditor`, `AnalysisChart`, `DashboardGrid`, `MarkdownPanel`, `ImagePanel`, `LinksPanel`, `EmbeddedView` |
| `/styles.css`, `/themes/*`       | Default styles and themes. Import explicitly; JS entries never import CSS.                                                                                                |

## Persistence

`ViewStore` is the only port a backend must satisfy:

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

Two rules make it consistent:

1. **Optimistic revision.** Writes carry the expected `revision`; a mismatch throws `ViewStoreError` with code `CONFLICT`, and the UI offers reload-and-overwrite or save-as.
2. **Idempotent `requestId`.** Each logical write gets one `requestId` in `WriteContext`. Retries after a timeout reuse it; the server deduplicates.

The package ships `MemoryViewStore` for tests, examples and query-only use. Business applications implement `ViewStore` against their own API with their own fetcher; mapping HTTP status codes to `ViewStoreError.code` belongs there. Authorization, visibility filtering and deduplication are server responsibilities; `permissions` only drives button availability.

## Extension points

| Axis        | Mechanism                                                                                                                                                                  |
| ----------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Field type  | Register a `FieldKind` (operators, validation, `compile` to `FilterExpression`, editor descriptor); register the matching React editor and cell in `/ui` under the same id |
| Data source | `resolveSource(key)` returns a Wow query client                                                                                                                            |
| Persistence | Implement `ViewStore`                                                                                                                                                      |
| Renderers   | Register cell, row-action and toolbar-action components by key                                                                                                             |
| Appearance  | CSS variables and theme files; replace components by composing `/react` hooks                                                                                              |

Built-in kinds: `string`, `number`, `boolean`, `date`, `datetime`, `enum`, `reference`.

## Layering

```text
model → filter → record | analysis | dashboard → runtime → react → ui
store → model
```

Six dependency rules are enforced by architecture tests: `model` imports nothing; `filter` imports only `model`; `record`, `analysis` and `dashboard` import only `model` and `filter`; `runtime` never imports `react` or `ui`; `store` imports only `model`; `react` never imports `ui`. Everything up to `store` is free of React and DOM. Only the non-deprecated `FilterExpression` based Wow APIs are used.

## Out of scope

View-kind plugins, a definition CRUD backend, write-receipt reconciliation or read fences, resource budgets beyond concurrency and page size, SSR preloading, generic region or event buses, cross-page select-all, cell editing and nested dashboards.

## Development

```bash
pnpm --filter @ahoo-wang/fetcher-view-engine test
pnpm --filter @ahoo-wang/fetcher-view-engine build
pnpm storybook
```

`@ahoo-wang/fetcher-viewer` is deprecated in favor of this package. The two use different models and APIs.
