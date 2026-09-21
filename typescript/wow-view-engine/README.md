# Fetcher View Engine

> **Status: the rewrite defined in [docs/design/](docs/design/) is delivered**, through its ninth and last step. Record, Analysis and Dashboard views, the `/react` controllers and the `/ui` components below are all in the package. The previous implementation is frozen at the git tag `view-engine-legacy` (`a064fc1a`) for reference only.

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
          // `enum` is a closed set, so it offers IN and NOT_IN rather than EQ:
          // one condition covers "any of these", and switching between the two
          // keeps the selection.
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

### 2. Create an engine

```ts
import { MemoryViewStore, ViewEngine } from '@ahoo-wang/fetcher-view-engine';

const engine = new ViewEngine({
  definitions: [orders],
  store: new MemoryViewStore(),
  // Pick<QueryApi, 'paged' | 'cursor' | 'aggregate'> from @ahoo-wang/fetcher-wow
  resolveSource: key => queryClients[key],
});
```

### 3a. Render the default workbench

```tsx
import '@ahoo-wang/fetcher-view-engine/styles.css';
import { RecordWorkbench } from '@ahoo-wang/fetcher-view-engine/ui';

export function OrdersPage() {
  return <RecordWorkbench engine={engine} definitionId="orders" />;
}
```

The theme follows the host through a `.dark` class on any ancestor; pass `theme="light"` or `theme="dark"` to `ViewSurface` to pin one view. Popups portalled to `<body>` carry the mode the surface resolved, so the class does not have to sit on `<html>`.

#### Customising the theme

Every token reads a host-level variable with the built-in value as its fallback: set `--fve-<token>` for light and `--fve-dark-<token>` for dark on your own `:root`, and the surface and the popups portalled to `<body>` both pick it up — no selector to scope, no load order to win.

```css
:root {
  --fve-primary: oklch(0.55 0.21 265deg);
  --fve-primary-foreground: oklch(0.99 0 0deg);
  --fve-dark-primary: oklch(0.75 0.15 265deg);
  --fve-dark-primary-foreground: oklch(0.21 0.05 265deg);
  --fve-radius: 0.375rem;
}
```

| Token                       | Role                                 | Light default                   | Dark default                    |
| --------------------------- | ------------------------------------ | ------------------------------- | ------------------------------- |
| `background`                | Surface behind everything            | `oklch(1 0 0deg)`               | `oklch(0.145 0 0deg)`           |
| `foreground`                | Default text                         | `oklch(0.145 0 0deg)`           | `oklch(0.985 0 0deg)`           |
| `card`                      | Card and panel surface               | `oklch(1 0 0deg)`               | `oklch(0.205 0 0deg)`           |
| `card-foreground`           | Text on cards                        | `oklch(0.145 0 0deg)`           | `oklch(0.985 0 0deg)`           |
| `popover`                   | Popup surface                        | `oklch(1 0 0deg)`               | `oklch(0.205 0 0deg)`           |
| `popover-foreground`        | Text in popups                       | `oklch(0.145 0 0deg)`           | `oklch(0.985 0 0deg)`           |
| `primary`                   | Primary action fill                  | `oklch(0.205 0 0deg)`           | `oklch(0.922 0 0deg)`           |
| `primary-foreground`        | Text on primary                      | `oklch(0.985 0 0deg)`           | `oklch(0.205 0 0deg)`           |
| `secondary`                 | Secondary action fill                | `oklch(0.97 0 0deg)`            | `oklch(0.269 0 0deg)`           |
| `secondary-foreground`      | Text on secondary                    | `oklch(0.205 0 0deg)`           | `oklch(0.985 0 0deg)`           |
| `muted`                     | Muted surface                        | `oklch(0.97 0 0deg)`            | `oklch(0.269 0 0deg)`           |
| `muted-foreground`          | Secondary text                       | `oklch(0.556 0 0deg)`           | `oklch(0.708 0 0deg)`           |
| `accent`                    | Hover and selected fill              | `oklch(0.97 0 0deg)`            | `oklch(0.269 0 0deg)`           |
| `accent-foreground`         | Text on accent                       | `oklch(0.205 0 0deg)`           | `oklch(0.985 0 0deg)`           |
| `sidebar`                   | Navigation column ground             | `oklch(0.97 0 0deg)`            | `oklch(0.205 0 0deg)`           |
| `sidebar-foreground`        | Text in the navigation column        | `oklch(0.145 0 0deg)`           | `oklch(0.985 0 0deg)`           |
| `sidebar-accent`            | Hovered row in the column            | `oklch(0.922 0 0deg)`           | `oklch(0.279 0 0deg)`           |
| `sidebar-accent-foreground` | Text on a hovered row                | `oklch(0.205 0 0deg)`           | `oklch(0.985 0 0deg)`           |
| `sidebar-border`            | The column's edge                    | `oklch(0.898 0 0deg)`           | `oklch(1 0 0deg / 20%)`         |
| `destructive`               | Danger and delete                    | `oklch(0.577 0.245 27.325deg)`  | `oklch(0.704 0.191 22.216deg)`  |
| `success`                   | Positive outcome                     | `oklch(0.527 0.154 150.069deg)` | `oklch(0.792 0.209 151.711deg)` |
| `warning`                   | Needs attention, not blocking        | `oklch(0.555 0.163 48.998deg)`  | `oklch(0.828 0.189 84.429deg)`  |
| `info`                      | Neutral notice                       | `oklch(0.546 0.245 262.881deg)` | `oklch(0.707 0.165 254.624deg)` |
| `border`                    | Borders and dividers                 | `oklch(0.922 0 0deg)`           | `oklch(1 0 0deg / 20%)`         |
| `input`                     | Input and control borders            | `oklch(0.62 0 0deg)`            | `oklch(1 0 0deg / 40%)`         |
| `ring`                      | Focus ring                           | `oklch(0.62 0 0deg)`            | `oklch(0.66 0 0deg)`            |
| `chart-1`                   | Chart slot 1, blue                   | `#2a78d6`                       | `#3987e5`                       |
| `chart-2`                   | Chart slot 2, orange                 | `#eb6834`                       | `#d95926`                       |
| `chart-3`                   | Chart slot 3, aqua                   | `#1baf7a`                       | `#199e70`                       |
| `chart-4`                   | Chart slot 4, yellow                 | `#eda100`                       | `#c98500`                       |
| `chart-5`                   | Chart slot 5, magenta                | `#e87ba4`                       | `#d55181`                       |
| `radius`                    | Corner radius, the rest scale off it | `0.625rem`                      | —                               |

The five `sidebar*` tokens are shadcn's own names for the navigation column the workbench puts its view list in, so a host that already themes a shadcn sidebar themes this one with the same words. Only the five the column paints with are declared. The open view in that column is `background` on top of `sidebar`, and `sidebar-accent` is the hover, so the three have to stay apart from one another: a set where two of them resolve to the same grey is a list with no "you are here".

`radius` is the one token the dark block does not redeclare, so `--fve-radius` sets it in both modes and there is no `--fve-dark-radius`.

The root paints `--background`, so an embedded view shows its own rectangle inside a host card; to let the host's own surface show through instead, set `--fve-background: transparent` (and `--fve-dark-background` for a surface pinned to dark), and the root then paints nothing behind the components, which keep their own card, popover and input colours.

Popups — menus, lists, popovers, tooltips and dialogs — are portalled to `<body>` and paint at `z-index: 50`, above the page around them. A host whose own chrome stacks higher than that raises every one of them with a single variable:

```css
:root {
  --fve-popup-z-index: 2000;
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
- **Two questions, one value.** The three scopes are the legal combinations of who a view is for and whether a user configured it: a system view is always a shared view, and a personal system view cannot be written down. `audienceOf(scope)` answers the first, `isSystemScope(scope)` the second, and everything from the dashboard reference rule to the sidebar's grouping asks through them. What a user creates is a `ViewAudience`, never a scope.
- **The sidebar.** Views are grouped by audience — personal first, then shared, with system views among the shared ones under a `system` tag — and each row is prefixed by its kind, because one data definition holds record and analysis views together. The heading is the definition's own title. `ViewInstanceSummary` carries `kind` for this: it is the config's tag, projected, so a store answers `list` from the configs it holds.
- **Permissions.** `store.permissions()` supplies permissions synchronously and only drives button availability. The server is the authority. Shared views you cannot edit and system views can still be saved as personal ones.
- **List and preferences.** List, preferences and permissions load independently and never block each other; personal ordering and the default view live in `ViewPreferences`, and deleting an instance never rewrites them.
- **Conflicts and unknown outcomes.** On a revision conflict choose reload or overwrite, or save as; when a request was sent but its outcome is unknown, retry with the same `requestId`. The draft is always kept.
- **Leave protection.** Closing a view with an unsaved draft or an unknown write asks for confirmation; navigation never cancels an in-flight write.

Details in [docs/design/management.md](docs/design/management.md).

## Entries

| Entry                            | Exports                                                                                                                                                                                                                                                                                                                                                                               |
| -------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `@ahoo-wang/fetcher-view-engine` | Model types, pure kernels (`validate*` / `compile*` / `project*`), runtime, `ViewStore`, `MemoryViewStore`                                                                                                                                                                                                                                                                            |
| `/react`                         | `useViewEngine`, `useOpenView`, `useViewRuntime`, `useViewList`, `useViewManager`, `useFilterEditor`, `useRecordTable`, `useAnalysisEditor`, `useDashboard`, `useSaveCommands`, `RecordActionSlots`                                                                                                                                                                                   |
| `/ui`                            | `RecordWorkbench`, `AnalysisWorkbench`, `DashboardWorkbench`, `ViewHeader`, `SaveActions`, `ViewManager`, `useLeaveGuard`, `EditorBand`, `FilterPanel`, `StatusStrip`, `AppliedBar`, `ResultToolbar`, `RowActions`, `RecordTable`, `RecordCards`, `RecordPagination`, `AnalysisEditor`, `AnalysisChart`, `DashboardGrid`, `MarkdownPanel`, `ImagePanel`, `LinksPanel`, `EmbeddedView` |
| `/styles.css`                    | The theme. Import it explicitly; no JavaScript entry imports CSS, and nothing in it paints outside `.fve-root` (preflight and utilities are scoped at build time), both checked by `scripts/verify-package.mjs` on every build.                                                                                                                                                       |

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

### Wording and language

The model carries `code` and `params` and no copy, so `/ui` owns the words. `defaultMessages` (`en`) gives every issue an English sentence, and `messages` — on `ViewSurface` and on every workbench — is merged over the wording already in force, the same seam for rewording and translation. A `MessagesProvider` around the application sets it once for every view inside. `zhCN` is a second catalogue, key for key: hand it over whole, or spread it and change what you like (`{ ...zhCN, 'label.filter.apply': '确定' }`).

Values show as their fields say: an enum by its option's label, a `datetime` or a `date` through `Intl.DateTimeFormat`, a date-histogram key as the year, quarter, month or day it starts. `locale` is the language they show in, the runtime's when left out; it is the same choice as `messages`, made for values rather than words:

```tsx
import { RecordWorkbench, zhCN } from '@ahoo-wang/fetcher-view-engine/ui';

<RecordWorkbench
  engine={engine}
  definitionId="orders"
  messages={{ ...zhCN, 'label.filter.apply': '确定' }}
  locale="zh-CN"
/>;
```

Times read on the engine's clock, `environment.timeZone`: the zone "today" is resolved in, and the one a date histogram that names no `timeZone` is bucketed in, so a row shows the time it was filtered and grouped by.

An unknown key falls back along the dots and then to the key itself, so a gap shows up rather than rendering blank. A test fails when a new issue code has no entry. What a component may ask for is the `MessageKey` union, so a key the catalogue dropped is a compile error; a host's own `messages` stays an open string map.

## Extension points

| Axis        | Mechanism                                                                                                                                                                          |
| ----------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Field type  | Register a `FieldKind` (operators, validation, `compile` to `FilterExpression`, editor descriptor); register the matching React editor and cell in `/ui` under the same id         |
| Data source | `resolveSource(key)` returns a Wow query client                                                                                                                                    |
| Persistence | Implement `ViewStore`                                                                                                                                                              |
| Actions     | Pass `actions` to a workbench — `global`, `bulk` and `row` render functions. They are code, so they are handed over rather than named in a config, and nothing about them is saved |
| Appearance  | CSS variables and theme files; replace components by composing `/react` hooks                                                                                                      |

Built-in kinds: `string`, `number`, `boolean`, `date`, `datetime`, `enum`, `reference`.

## Layering

```text
model → filter → record | analysis | dashboard → runtime → react → ui
store → model
```

`validateDefinition` admits a definition once, where the engine registers it: field names against Wow's query syntax, unique ids free of `:`, capabilities that `default*Config` can actually build from, and every system view through its own kernel. A definition with an error stays in the registry but is refused at the point of use, so a mistake in code surfaces as a reported issue rather than as a `TypeError` when a user opens a view.

Six dependency rules are enforced by architecture tests: `model` imports nothing; `filter` imports only `model`; `record`, `analysis` and `dashboard` import only `model` and `filter`; `runtime` never imports `react` or `ui`; `store` imports only `model`; `react` never imports `ui`. Everything up to `store` is free of React and DOM. Only the non-deprecated `FilterExpression` based Wow APIs are used.

## Out of scope

View-kind plugins, a definition CRUD backend, write-receipt reconciliation or read fences, resource budgets beyond concurrency and page size, SSR preloading, generic region or event buses, cross-page select-all, cell editing and nested dashboards.

## Development

```bash
pnpm --filter @ahoo-wang/fetcher-view-engine test          # unit tests, coverage and three tsc projects
pnpm --filter @ahoo-wang/fetcher-view-engine build         # build, then verify the published entries
pnpm --filter @ahoo-wang/fetcher-view-engine test:package  # entries, DOM-free types, no CSS from JS
pnpm storybook                                             # every state of every surface, under "View Engine"
```

`examples/` holds two consumers written against the public contracts rather
than against the internals: `PlainRecordWorkbench.tsx` drives the whole loop
with unstyled HTML, and `FetcherViewStore.ts` implements the `ViewStore` port
over HTTP with `@ahoo-wang/fetcher`.

`@ahoo-wang/fetcher-viewer` is deprecated in favor of this package. The two use different models and APIs.
