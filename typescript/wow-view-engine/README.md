# Wow View Engine

> **Status: in active development, with no compatibility promise.** Record, Analysis and Dashboard views, their embeds, the `/react` controllers and the `/ui` components below are all in the package. Any export may still change shape, and no change carries a compatibility layer; [docs/design/](docs/design/) is the source of truth, and this page describes the API as it is now.

**Wow View Engine is a data view engine for Wow-based business applications.** The application declares in code _how a dataset can be observed_: fields, kinds, operators, available dimensions and metrics. Users decide in the UI _how to observe it this time_: filters, columns, sorting, dimensions and metrics, charts, panel composition. The engine compiles that way of observing into Wow queries, runs them, renders the result, and saves the ways worth keeping so they can be reopened with one click.

It is the presentation layer on top of `@ahoo-wang/wow-client` and the successor of `@ahoo-wang/fetcher-viewer`.

## The problem

Most pages in a business system are the same page: a list with filters, sorting and paging, sometimes with a chart. Every business object (orders, inventory, customers, tickets) gets its own copy. Every request from operations, "add one more filter", "break this down by warehouse", "put these tables on one overview page", means a code change, a sprint slot and a release.

The data did not change; only the way of observing it did. The problem is that the way of observing is hard-coded in page code: users cannot adjust it themselves, developers are consumed by repetition, and product treats every presentation tweak as a feature request.

## Goals

- **For users.** Adjust scope, organization and presentation within declared capabilities, save the ways of observing that matter, and reopen them with one click.
- **For developers.** Integrate a business object once, one definition plus one query client, and stop writing pages for its list, analysis and overview views; filter editing, query coordination, result rendering, save and restore and conflict handling are provided once by the engine.
- **For evolution.** Adding a business object adds a definition, never a business branch inside the engine; custom layouts reuse the same behavior through headless hooks instead of copying logic.

## Value

| For            | What they get                                                                                                                                                                             |
| -------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Business users | The view they need without waiting for a sprint; saved views open instantly; the same data as records, as metrics by dimension, or as an overview                                         |
| Developers     | List pages go from "one per object" to "one definition per object"; filtering, paging, sorting, saving and conflicts are implemented once; definitions can be generated from Wow metadata |
| Product        | Presentation changes within the supported range become configuration, not requirements; "save and share views" ships as a product capability                                              |

## Scenarios

| Scenario                                       | View                            | What the user does                                                                                                                           |
| ---------------------------------------------- | ------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------- |
| Warehouse staff find today's pending orders    | Record                          | Filter status = pending, sort by creation time, keep only the needed columns, save as "Pending today"                                        |
| A manager compares backlog across warehouses   | Analysis                        | Group by warehouse, count and sum amounts, switch to a bar chart                                                                             |
| Operations review the week                     | Dashboard                       | Place both views on one panel page and constrain them with a global time range                                                               |
| A slice of data inside a business page         | EmbeddedView, EmbeddedDashboard | A developer embeds a saved view into the order detail page, or a board into the customer page locked to that customer, without the workbench |
| Operators seed baseline views for a new object | System views                    | Declare "All", "Pending" and "New this week" in the definition; users open a usable view at once and save variants as needed                 |
| Customers build their own reports              | All                             | Customers save and share views within their tenant; the vendor ships no release for it                                                       |

## What it is not

Not a database or compute backend, not a permission system, not a general low-code page builder, not a BI modeling tool. Data, aggregation capabilities and authorization come from the business services; the engine only makes ways of observing run reliably.

## Install

The package has not had its first public registry release; the stable release scripts skip it on purpose. Until then, consume it from this workspace (`"@ahoo-wang/wow-view-engine": "workspace:^"`) or from a local `pnpm pack` archive. Once published, installation will be:

```bash
pnpm add @ahoo-wang/wow-view-engine @ahoo-wang/wow-client
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
  // The row key must be sortable: every record query ends its sort on it,
  // so rows that tie on the chosen sort never repeat or go missing across pages.
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
  // Pick<QueryApi, 'paged' | 'cursor' | 'aggregate'> from @ahoo-wang/wow-client
  resolveSource: key => queryClients[key],
});
```

**Exported files neutralize formulas.** A CSV leaves the page and is opened in a spreadsheet, often by someone other than whoever exported it, so every export — a record view's rows and an analysis's **Export data…** — writes a cell whose text starts with `=`, `+`, `-`, `@`, a tab or a carriage return with a leading `'` (OWASP, CSV Injection), header labels included. A cell whose value is a number, and one whose text is a plain number such as `-12.5`, is left as it is: a spreadsheet reads it as a number, never as a formula. Where the file never reaches a spreadsheet, turn it off with `limits: { ...DEFAULT_RUNTIME_LIMITS, exportNeutralizeFormulas: false }`, or with `{ neutralizeFormulas: false }` when you call `serializeCsv` yourself.

### 3a. Render the default workbench

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

The theme follows the host through a `.dark` class on any ancestor; pass `theme="light"` or `theme="dark"` to `ViewSurface` (or to a workbench or an embed) to pin one view, or `theme="system"` to follow the reader's `prefers-color-scheme`, live, on a page with no switch of its own. Popups portalled to `<body>` carry the mode the surface resolved, so the class does not have to sit on `<html>`. A preset is chosen the same way — see [Presets](#presets).

#### Which view is open, and your route

One data definition holds its record views and its analysis views, and `DataWorkbench` lists them together: the user switches between a table of orders and a chart of them as between any two views, and the "new view" button asks which kind to make. A host that wants a page of one kind narrows it — `kinds={['record']}` — and the other kind is neither listed nor openable there.

A view somebody opened is a link they can send, so both workbenches take `instanceId` and `onInstanceChange` — the two directions in and out of your router. `DataWorkbench` and `DashboardWorkbench` share the contract exactly.

<!-- typecheck-context
import { ViewEngine } from '@ahoo-wang/wow-view-engine';
declare const engine: ViewEngine;
import { DataWorkbench } from '@ahoo-wang/wow-view-engine/ui';
declare function useSearchParam(key: string): [string | null, (value: string | null) => void];
-->

```tsx
export function OrdersPage() {
  // Whatever your router gives you: a param, a search key, a hash.
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

`instanceId` is controlled in the sense `value` is on an input:

- **left out** — the uncontrolled form: the workbench owns which view is open, starting from the user's effective default;
- **passed** — a view id, or `null` for that effective default: you say which view is open, and every later change of it opens what it names. `null` is a value, not the absence of one;
- `onInstanceChange(id)` reports what is open now, in the same vocabulary — `null` means the effective default there too — so what comes out goes straight back in.

It converges rather than renders. A view holds an unsaved draft, so a pushed value goes through the same leave guard a click on the sidebar goes through: whichever side moved last is the one that speaks, and the other follows. If the guard asks and the user stays, the workbench reports the view that stayed, so your route is never left naming a view that is not on screen. `examples/PlainRecordWorkbench.tsx` has the whole of it against `window.location.hash`, back button included.

#### Building a dashboard, and opening a panel's view

A dashboard is read until someone who may save it presses **Edit**: nothing on a board being read moves. Editing brings up a bar with **Undo** and **Redo**, **Add** (data: a saved view, or a new analysis the board owns; content: a heading, text, an image, links), **Add filter** (a board filter, then wired to the panels), **Cancel** and **Save**, and the tab bar under it adds, renames, reorders and deletes tabs — panels re-run as the board changes, and only **Save** saves it, through the same save the title bar has. A system dashboard is read-only and offers **Save as**.

Every way off the board goes through one route of yours, `onNavigate(to)` — the package never touches the address. Without it none of them exist:

- **Open in the workbench** in a panel's **⋯**: `{ kind: 'view', definitionId, instanceId, scopeFilter, filter, from }`, the saved view the panel shows, in that view's own field names. What is not the reader's — the board's fixed scope and what the page holds, a locked or hidden filter — is `scopeFilter`: the view runs under it as its scope, and nobody takes it off in the workbench. What the reader set on the board is `filter`: it becomes the view's own conditions, so the view opens **Edited**, each condition removable — taking them all off leaves the saved view, no longer **Edited** — and **Revert** taking them all off at once. A board's own analysis goes as `{ kind: 'unsaved', … }`, the reader's values among its conditions and the page's hold as its `scopeFilter`.
- **Pressing a group** — a bar, a slice, a row — opens the analysis view's follow-up menu (see these records, split the group, only this group). Each item is a view nobody saved: `{ kind: 'unsaved', definitionId, title, config, scopeFilter, named, from }`, the reader's values and the group already among its conditions, the page's hold its scope; `named` is what the title says of the group, so the workbench drops the group from the name once the reader takes it off.
- Every way off hands over the same way, and `from` is the way back: pass the target to `DataWorkbench`'s `handOver` prop (each new object opens once) along with the same `onNavigate`, and the workbench draws **Back to 〈board〉** under its title bar, which routes `{ kind: 'dashboard', definitionId, instanceId, filters, tab }` — the board as it was left. It asks first only if the reader changed the view beyond what the board handed over. Your host draws no back button of its own.
- **When clicked…** (while building): a panel can instead set a board filter from the group pressed (cross-filtering — no route needed; the other wired panels follow, the panel pressed marks the group, a second press clears it), or go to another saved view (`{ kind: 'view' }`, handed over as above, the group among its own conditions), another dashboard, or a page of yours (`{ kind: 'url', url }`, `{{field}}` filled with the group, encoded).
- **Another dashboard**: the author lists the target board's filters and maps each one to a dimension of the panel, to one of this board's filters of the same type, or leaves it out — nothing is matched by name. A press hands you `{ kind: 'dashboard', definitionId, instanceId, filters }`: `filters` is that board's `DashboardFilters`, each mapped filter holding the group's value or what this board's filter holds at the press, and every other one its default. Pass it to `DashboardWorkbench`'s `initialFilters` (or `ViewEngine.open`'s `filters`) — it is the reader's, never written into either board. A mapping gone stale (a filter or a dimension removed, the board deleted) warns on the panel, and a press opens the follow-up menu instead.

<!-- typecheck: skip — two JSX elements side by side, each an example on its own -->

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

`DashboardWorkbench`, `EmbeddedDashboard` and `EmbeddedView` take the same prop.

#### Embedding a view or a dashboard

A business page that shows what somebody already decided embeds it: the result and nothing else, no view list, no condition editor, no save. **An embed never writes**: not a view, not a board, not a preference — what a reader does on it lasts for that viewing. Defining views, building boards and saving them happen in the workbenches; a page whose readers should build boards embeds `DashboardWorkbench` instead. There are two entries, split by resource as the workbenches are — `EmbeddedView` for a record or analysis view, `EmbeddedDashboard` for a board. Each draws only its own kind, and says so if handed the other.

<!-- typecheck: skip — two JSX elements side by side, each an example on its own -->

```tsx
import {
  EmbeddedDashboard,
  EmbeddedView,
} from '@ahoo-wang/wow-view-engine/ui';

// An order page: this customer's recent shipments, read as saved.
<EmbeddedView
  engine={engine}
  instanceId="orders-pending"
  scopeFilter={{
    op: 'and',
    children: [{ field: 'customer', operator: 'IN', value: [customerId] }],
  }}
/>

// A customer page: the customer's board, locked to them; the time is the reader's.
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

**How far a reader may go is one explicit tier**, `interaction`, `static` by default. Neither tier saves anything:

| Tier          | `EmbeddedView` (record, analysis)                                                                                                                                                  | `EmbeddedDashboard`                                                                                                                                       |
| ------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `static`      | The result and what it was fetched under. Headers do not sort or resize, there are no pages, nothing leads anywhere                                                                | The panels, answering nothing: no follow-up menu, no cross-filtering, no **⋯**; every filter reads as what it holds, with no control                      |
| `interactive` | Header sort, column widths and pages; an analysis's table｜chart switch and the follow-up menu on a group; **Open in the workbench**; **Fill the screen** where `expandable` is on | Filters (a search filter too), the follow-up menu, cross-filtering, destinations, **Open in the workbench**; **Fill the screen** where `expandable` is on |

Every way off the embed goes through your one route, `onNavigate(to)` — the same `ViewNavigation` the dashboard workbench hands over; without it, none of those ways exist.

**The switches** — each absent, not greyed, when off. The tier is the ceiling and a switch opts in within it: search, export and fill-the-screen are reader controls, so they have no effect in the static tier:

| Prop                          | Default   | What it does                                                                                                                                                            |
| ----------------------------- | --------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `withTitle`                   | off       | Draws the view's or board's title                                                                                                                                       |
| `headingLevel`                | `2`       | The heading level the embed titles at: its own title, and a board's panels one level under it (or at it, with no title). Your page owns its `h1`                        |
| `withPanelTitles` (dashboard) | on        | Off, each panel's title is kept for screen readers only                                                                                                                 |
| `withSearch` (record)         | off       | The view's search box, where its definition declares a search field; interactive tier only                                                                              |
| `withExport` (record)         | off       | The export button and window, rows picked with it; interactive tier only                                                                                                |
| `withExport` (dashboard)      | off       | **Export data…** in a panel's "⋯" menu, the same export window; interactive tier only                                                                                   |
| `autoRefresh`                 | on        | Refreshes on the interval its author saved; off, never on its own                                                                                                       |
| `openInWorkbench`             | on        | Whether **Open in the workbench** is offered in the interactive tier                                                                                                    |
| `expandable`                  | off       | **Fill the screen** at the end of the embed's first row, in the interactive tier: the surface fills the screen in place, as a workbench's does; Escape puts it back     |
| `size`                        | `content` | `content` sizes to what it shows, with a cap (a record table scrolls inside `--fve-record-table-max-h`); `fill` fills its container — a whole-page embed, a wall screen |

**A board's filters, each in one of three modes** (`filterModes`, by filter name; `groupingMode` for the time grouping): `adjustable` — on the bar, the reader's to adjust for this viewing, as in the workbench, and the default; `locked` — on the bar as what it holds, with a lock and no control; `hidden` — not on the bar, still narrowing the panels wired to it. Locked and hidden filters are held by the runtime, so nothing the reader does — a value, **Clear**, a press that cross-filters — changes them. Their values are the page's own, `pageValues` (their default where it names none): in force from the first query, and followed as the prop changes — a customer page moving to the next customer takes the board with it. The reader's filters are your address's, `initialFilters` and `onFiltersChange`, read and reported exactly as `DashboardWorkbench` does. **A locked or hidden value never travels through the address**: an entry for one in `initialFilters` is ignored, and `onFiltersChange` reports only the filters the reader can set — otherwise a reader who edits the address changes the customer, the opposite of locking it. A board takes no condition tree (`EmbeddedDashboard` has no `scopeFilter`): to narrow it, declare the filter on the board and lock or hide it.

**Locking is not a security boundary.** The condition a page locks is put together in the browser and sent with the query; it only keeps the reader from changing it on screen, or seeing anything else there. Anyone who edits the page's script or calls the API directly can ask for another customer. Tenancy, ownership and permission must be enforced by the Wow backend — above all on a page outside your organisation. This package is a library in your host's process: it does not do what Metabase does with iframes, signed tokens or SSO, because identity and permission belong to your host and your backend.

Filling the screen: with `expandable` on, an interactive embed draws the control itself. To put it in your own chrome instead — or to fill the screen with a static wall screen — pass a `ref` and point `useViewExpansion` at it.

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

| Token                       | Role                                                      | Light default                                                        | Dark default                   |
| --------------------------- | --------------------------------------------------------- | -------------------------------------------------------------------- | ------------------------------ |
| `background`                | Surface behind everything                                 | `oklch(1 0 0deg)`                                                    | `oklch(0.145 0 0deg)`          |
| `foreground`                | Default text                                              | `oklch(0.145 0 0deg)`                                                | `oklch(0.985 0 0deg)`          |
| `card`                      | Card and panel surface                                    | `oklch(1 0 0deg)`                                                    | `oklch(0.205 0 0deg)`          |
| `card-foreground`           | Text on cards                                             | `oklch(0.145 0 0deg)`                                                | `oklch(0.985 0 0deg)`          |
| `popover`                   | Popup surface                                             | `oklch(1 0 0deg)`                                                    | `oklch(0.205 0 0deg)`          |
| `popover-foreground`        | Text in popups                                            | `oklch(0.145 0 0deg)`                                                | `oklch(0.985 0 0deg)`          |
| `primary`                   | Primary action fill                                       | `oklch(0.205 0 0deg)`                                                | `oklch(0.922 0 0deg)`          |
| `primary-foreground`        | Text on primary                                           | `oklch(0.985 0 0deg)`                                                | `oklch(0.205 0 0deg)`          |
| `secondary`                 | Secondary action fill                                     | `oklch(0.97 0 0deg)`                                                 | `oklch(0.269 0 0deg)`          |
| `secondary-foreground`      | Text on secondary                                         | `oklch(0.205 0 0deg)`                                                | `oklch(0.985 0 0deg)`          |
| `muted`                     | Muted surface                                             | `oklch(0.97 0 0deg)`                                                 | `oklch(0.269 0 0deg)`          |
| `muted-foreground`          | Secondary text                                            | `oklch(0.556 0 0deg)`                                                | `oklch(0.708 0 0deg)`          |
| `accent`                    | Hover and selected fill                                   | `oklch(0.97 0 0deg)`                                                 | `oklch(0.269 0 0deg)`          |
| `accent-foreground`         | Text on accent                                            | `oklch(0.205 0 0deg)`                                                | `oklch(0.985 0 0deg)`          |
| `sidebar`                   | Navigation column ground                                  | `oklch(0.97 0 0deg)`                                                 | `oklch(0.205 0 0deg)`          |
| `sidebar-foreground`        | Text in the navigation column                             | `oklch(0.145 0 0deg)`                                                | `oklch(0.985 0 0deg)`          |
| `sidebar-accent`            | Hovered row in the column                                 | `oklch(0.922 0 0deg)`                                                | `oklch(0.279 0 0deg)`          |
| `sidebar-accent-foreground` | Text on a hovered row                                     | `oklch(0.205 0 0deg)`                                                | `oklch(0.985 0 0deg)`          |
| `sidebar-border`            | The column's edge                                         | `oklch(0.898 0 0deg)`                                                | `oklch(1 0 0deg / 20%)`        |
| `destructive`               | Danger and delete                                         | `oklch(0.505 0.213 27.518deg)`                                       | `oklch(0.76 0.15 22.216deg)`   |
| `success`                   | Positive outcome                                          | `oklch(0.448 0.119 151.328deg)`                                      | `oklch(0.792 0.15 151.711deg)` |
| `warning`                   | Needs attention, not blocking                             | `oklch(0.473 0.137 46.201deg)`                                       | `oklch(0.828 0.15 84.429deg)`  |
| `border`                    | Borders and dividers                                      | `oklch(0.922 0 0deg)`                                                | `oklch(1 0 0deg / 20%)`        |
| `input`                     | Input and control borders                                 | `oklch(0.62 0 0deg)`                                                 | `oklch(1 0 0deg / 40%)`        |
| `ring`                      | Focus ring                                                | `oklch(0.62 0 0deg)`                                                 | `oklch(0.66 0 0deg)`           |
| `chart-1`                   | Chart slot 1, blue                                        | `#2675d3`                                                            | `#3987e5`                      |
| `chart-2`                   | Chart slot 2, orange                                      | `#eb6834`                                                            | `#d95926`                      |
| `chart-3`                   | Chart slot 3, aqua                                        | `#1baf7a`                                                            | `#199e70`                      |
| `chart-4`                   | Chart slot 4, yellow                                      | `#eda100`                                                            | `#c98500`                      |
| `chart-5`                   | Chart slot 5, magenta                                     | `#e87ba4`                                                            | `#d55181`                      |
| `chart-6`                   | Chart slot 6, green                                       | `#008300`                                                            | `#008300`                      |
| `chart-7`                   | Chart slot 7, violet                                      | `#4a3aa7`                                                            | `#9085e9`                      |
| `chart-8`                   | Chart slot 8, red                                         | `#e34948`                                                            | `#e66767`                      |
| `radius`                    | Corner radius, the rest scale off it                      | `0.625rem`                                                           | —                              |
| `text-ui`                   | The one size under the body text                          | `0.8125rem`                                                          | —                              |
| `rise`                      | A rise, by its direction                                  | `success` (see [change colours](#change-colours-rising-and-falling)) | `success`                      |
| `fall`                      | A fall, by its direction                                  | `destructive`                                                        | `destructive`                  |
| `shadow-sm`, `-md`, `-lg`   | The three lifts (a raised card, a popup, a dragged panel) | Tailwind's `shadow-sm` / `-md` / `-lg`                               | the same                       |

The type is the host's: the surface sets `font-family: var(--fve-font-sans)`, which, unset, leaves `font-family` inherited from the page as before. Set `--fve-font-sans` to a system font stack to give the views one of their own; a chart reads the computed family and follows. It has no dark half.

The five `sidebar*` tokens are shadcn's own names for the navigation column the workbench puts its view list in, so a host that already themes a shadcn sidebar themes this one with the same words. Only the five the column paints with are declared. The open view in that column is `background` on top of `sidebar`, and `sidebar-accent` is the hover, so the three have to stay apart from one another: a set where two of them resolve to the same grey is a list with no "you are here".

`input` and `ring` owe a line the others do not: a control's edge and the focus mark are what WCAG 1.4.11 asks 3:1 of against what is behind them, and both defaults are tuned to clear it in either mode (measured in the browser by the Storybook contrast stories). They are values of their own on purpose. The usual shadcn brand theme re-points them — `--ring: var(--primary)`, `--input: var(--border)` — and that hands the 3:1 to a brand colour and a divider grey that owe nothing of the kind: an unticked checkbox becomes a hairline, a focused row a faint tint. Setting `--fve-primary` or `--fve-border` leaves them alone; a host that sets `--fve-ring` / `--fve-input` (or their `--fve-dark-` halves) owes its theme the same 3:1 and should measure it.

A few tokens are derived rather than set: `quiet-foreground`, the quiet half of a summary row, is `foreground` at 70%, and `destructive-foreground` is `background`, so a host that moves `--fve-foreground` or `--fve-background` moves them too. Each can still be set on its own (`--fve-quiet-foreground`, `--fve-destructive-foreground` and the `--fve-dark-` halves).

A chart is drawn in colours read off these tokens, not in `var()`s, so it is told to read them again when something that can change them changes: a `class`, `data-theme`, `data-fve-preset`, `data-fve-change-colors` or `style` attribute on the surface or one of its ancestors. A colour a stylesheet derives — `color-mix()`, `oklch(from …)` — is resolved by the browser before the chart gets it. Switch a theme by one of those; a stylesheet swapped in with no attribute changing leaves the charts in the old colours.

`radius` and `text-ui` are the two tokens the dark block does not redeclare — a length is a length in either mode — so `--fve-radius` and `--fve-text-ui` set them for both and there is no `--fve-dark-` half. `text-ui` is the one step under the body size: the group labels, column headers, badges, pagination and every `sm` control are set in it, so a host that scales it moves them together.

The root paints `--background`, so an embedded view shows its own rectangle inside a host card — in dark mode `--card` is a step lighter than `--background`, and the embed reads as a darker block. Give it the colour of what it sits on: set `--fve-background` and `--fve-dark-background` to your card's colour on the card (they inherit, so the embed under it picks them up and nothing else does). Not `transparent`: the rows, a pinned column, the hover shade and the ink on a destructive button are drawn in `--background`, so a transparent one lets a scrolled column show through the pinned one and leaves that button's label invisible.

Popups — menus, lists, popovers, tooltips and dialogs — are portalled to `<body>` and paint at `z-index: 50`, above the page around them. A host whose own chrome stacks higher than that raises every one of them with a single variable:

```css
:root {
  --fve-popup-z-index: 2000;
}
```

#### Presets

A preset is a set of values for the `--fve-*` / `--fve-dark-*` variables above, keyed by a `data-fve-preset` attribute. The presets ship as an optional entry beside the theme:

```ts
import '@ahoo-wang/wow-view-engine/styles.css';
import '@ahoo-wang/wow-view-engine/themes.css';
```

```html
<html data-fve-preset="neutral"></html>
```

Put the attribute on `<html>` and every view and every popup takes the preset. To give one view its own, pin it with `preset` on `ViewSurface`, a workbench or an embed; its popups carry it out to `<body>` as they carry `data-theme`. A `data-fve-preset` on any other ancestor works too: the surface finds the nearest one and hands it to its popups.

- **The preset and the mode are separate.** A preset only supplies both halves of the values; light or dark is still decided by `.dark` or `theme`, as above.
- **Your own variables win.** Each preset is written as `:where([data-fve-preset='…'])`, which weighs nothing, so a `--fve-*` you set on `:root` beats the preset you chose, whichever stylesheet loads first — override one colour of a preset without restating the rest.
- **Chart patterns**: `--fve-chart-patterns: on | off` on any ancestor pins the patterns (decal) drawn over the chart series on or off; unset (or `auto`) they follow the reader's "increase contrast" setting (`prefers-contrast: more`). It is no colour, and no preset sets it.
- **What a preset gives**: every colour and `radius`, always; then three optional groups, each whole or not at all — the eight chart colours of both modes, the three shadows of both modes, and a system font stack (`--fve-font-sans`). A preset that leaves a group out leaves it to what is around it. A palette a preset brings is held to the same colour-vision and contrast gates as the default eight (`test/paletteDistance.test.ts`); a chart slot is an ordinal — "the third series" — not a hue, so a `ChartSpec.colors` entry written `var(--chart-3)` changes colour with the preset.
- **What a preset never changes**: `pin-shadow` (the mode's), `text-ui` (your typography) and `rise` / `fall` (your [change convention](#change-colours-rising-and-falling)). A host that sets `--fve-chart-*` itself owes its palette the measurements above.
- **Three built in.** `neutral` is the look of the theme itself: it puts every variable back to unset, so a view pinned to `neutral` inside a page on another preset looks as it would with no preset at all. `blue` is the neutral greys with a blue brand colour (shadcn's `blue` theme); `slate` is cool greys with a blue brand colour (the look of the compensation console). Neither turns `input` or `ring` into the brand colour (`slate` only cools their grey) nor touches the status or chart colours, and every text, control-edge and focus pair of every preset clears 4.5:1 / 3:1 in both modes (`test/presetContrast.test.ts`). What they set is in the table below.
- `themes.css` holds nothing but these variable assignments; `scripts/verify-package.mjs` checks on every build that each rule is a preset block, that each declaration is a `--fve-` variable, that every preset assigns the same required set, so one pinned inside another replaces all its colours, and that each optional group is given whole or not at all. `neutral` assigns every group too, as unset, so pinning `neutral` is a full reset.

What `blue` and `slate` set — a variable not listed keeps the neutral value in the token table above:

| Variable                                                                    | `blue` light                    | `blue` dark                     | `slate` light                   | `slate` dark                    |
| --------------------------------------------------------------------------- | ------------------------------- | ------------------------------- | ------------------------------- | ------------------------------- |
| `primary`                                                                   | `oklch(0.488 0.243 264.376deg)` | `oklch(0.707 0.165 254.624deg)` | `oklch(0.546 0.245 262.881deg)` | `oklch(0.707 0.165 254.624deg)` |
| `primary-foreground`                                                        | `oklch(0.97 0.014 254.604deg)`  | —                               | `oklch(0.984 0.003 247.858deg)` | `oklch(0.208 0.042 265.755deg)` |
| `background`                                                                | —                               | —                               | —                               | `oklch(0.129 0.042 264.695deg)` |
| `foreground`, `card-foreground`, `popover-foreground`, `sidebar-foreground` | —                               | —                               | `oklch(0.129 0.042 264.695deg)` | `oklch(0.984 0.003 247.858deg)` |
| `card`, `popover`                                                           | —                               | —                               | —                               | `oklch(0.208 0.042 265.755deg)` |
| `secondary`, `muted`, `accent`                                              | —                               | —                               | `oklch(0.968 0.007 247.896deg)` | `oklch(0.279 0.041 260.031deg)` |
| `secondary-foreground`, `accent-foreground`, `sidebar-accent-foreground`    | —                               | —                               | `oklch(0.208 0.042 265.755deg)` | `oklch(0.984 0.003 247.858deg)` |
| `muted-foreground`                                                          | —                               | —                               | `oklch(0.554 0.046 257.417deg)` | `oklch(0.704 0.04 256.788deg)`  |
| `sidebar`                                                                   | —                               | —                               | `oklch(0.968 0.007 247.896deg)` | `oklch(0.208 0.042 265.755deg)` |
| `sidebar-accent`                                                            | —                               | —                               | `oklch(0.929 0.013 255.508deg)` | `oklch(0.279 0.041 260.031deg)` |
| `sidebar-border`                                                            | —                               | —                               | `oklch(0.898 0.018 254deg)`     | —                               |
| `border`                                                                    | —                               | —                               | `oklch(0.929 0.013 255.508deg)` | —                               |
| `input`                                                                     | —                               | —                               | `oklch(0.62 0.043 257deg)`      | —                               |
| `ring`                                                                      | —                               | —                               | `oklch(0.62 0.043 257deg)`      | `oklch(0.66 0.042 257deg)`      |

The dark `primary` of both is blue-400 rather than shadcn's blue-800: `primary` is also a colour the surface writes with (a link in a cell) and a fill that carries state (a ticked box), and blue-800 reads about 2:1 on the dark card.

A host may write a preset of its own the same way — `:where([data-fve-preset='acme']) { --fve-primary: …; }` — and select it with the same attribute or prop.

#### Change colours: rising and falling

Two things on a view colour a change: a metric card's change from the period before, and a waterfall's steps. By default a change is coloured by whether it is **good** — a card whose metric went the good way (`lowerIsBetter` says which) wears `success`, the bad way `destructive` — and a waterfall's rise wears `success` and its fall `destructive`. Markets read it differently: mainland China's boards colour by **direction**, red for up; Hong Kong, Europe and the US by direction too, green for up. That is your call, by market and reader, and no preset's:

```html
<html data-fve-change-colors="red-up"></html>
```

| `data-fve-change-colors` | A metric card's change | `--rise` / `--fall` (a waterfall) |
| ------------------------ | ---------------------- | --------------------------------- |
| unset, or `semantic`     | good or bad            | `success` / `destructive`         |
| `green-up`               | up or down             | `success` / `destructive`         |
| `red-up`                 | up or down             | `destructive` / `success`         |

- The language is not the market, so nothing switches it for you: a Chinese interface over overseas sales and an English one over A-shares are both common.
- There is no prop: a page reads one market, and two conventions on one page would be read the wrong way round. Popups carry it out to `<body>` as they carry a preset.
- `--fve-rise` / `--fve-fall` (and their `--fve-dark-` halves) set the colours themselves; the convention only decides the pair they default to. A preset never sets them.
- Colour is never the only sign: a card's change leads with its arrow and writes its sign, and a waterfall's labels are signed, because red and green are one colour to a red–green colour-blind reader.

#### A host with a shadcn theme: `shadcn-bridge.css`

A host that already has a shadcn/ui theme — `--background`, `--primary`, `--radius` and the rest on its `:root`, with its dark values under `.dark` — needs neither a preset nor a copy of its colours. One more optional entry points every `--fve-*` / `--fve-dark-*` variable at the shadcn token of the same name:

```ts
import '@ahoo-wang/wow-view-engine/styles.css';
import '@ahoo-wang/wow-view-engine/shadcn-bridge.css';
```

- **Four kinds are not bridged**, and keep this package's values: `input` and `ring` (a shadcn theme's usual `--input: var(--border)` and `--ring: var(--primary)` owe nothing of the 3:1 a control's edge and a focus mark need), the status colours `destructive`, `success` and `warning` (text measured to 4.5:1; shadcn has no `success` or `warning`), and the eight chart colours. Set any of them yourself, one by one, if you want yours — and measure what you set. Nor the shadows, which shadcn has no standard name for; the type is bridged, `--fve-font-sans` from your `--font-sans`.
- **The mode is the host's.** The bridge is resolved on `<html>`, so it reads whatever your `:root` says in the mode `<html>` is in: keep your `.dark` on `<html>`, as shadcn does, and let the views follow it. A view pinned to the other mode with `theme` would get your current values in both halves; pin a mode only where it matches your page.
- **Your own `--fve-*` still win**, and a surface pinned with `preset` wears that preset instead.
- **The bridge or a preset, not both.** The bridge applies only while `<html>` names no preset: put `data-fve-preset` on `<html>` and you get the preset, whichever file was imported last.
- **The words are your theme's.** Text tokens are bridged as they are; if your `--muted-foreground` misses 4.5:1 on your `--background`, so do the views' quiet words.

The Storybook regression `ShadcnBridge.test.stories.tsx` hangs the compensation console's theme on a workbench with the bridge and measures its control edges and focus at ≥3:1 in both modes.

#### What an override owes

Every built-in preset holds these lines in both modes, measured on every token pair in a real browser by the Storybook **contrast matrix** (View Engine / 主题 / 预设 / 对比度矩阵), which also measures variables you paste into it:

| Line   | Tokens                                                                                                                                                                                                                               |
| ------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| ≥4.5:1 | every `*-foreground` on its ground; `muted-foreground` on `background`, `card` and `popover`; `foreground` on `muted` and `row-hover`; `quiet-foreground`; `destructive`, `success` and `warning` as text on `background` and `card` |
| ≥3:1   | `input` and `ring` on `background`, `card` and `popover` (and on a dark control's own `input/30` wash)                                                                                                                               |
| none   | `border` and `sidebar-border` (dividers), `radius`, `text-ui`                                                                                                                                                                        |

A host that sets one of these owes its theme the same line. The eight chart colours owe their own: colour-vision distance between slots and a legible ink on every mark, which a preset that brings its own palette is held to as well. The full guide is [Theming the view engine](https://wow.ahoo.me/guide/typescript/view-engine-theming).

#### The host's own chrome: `fve-tokens`

Every rule of the stylesheet is scoped at build time, so the theme's tokens and even the layout utilities (`grid`, `gap-4`, `bg-background`) paint inside a style boundary and nowhere else. There are two boundaries, and only one of them is a surface:

|                                       | `.fve-root`                                                  | `.fve-tokens`                                                            |
| ------------------------------------- | ------------------------------------------------------------ | ------------------------------------------------------------------------ |
| Rendered by                           | `ViewSurface`, and every workbench and embed                 | your own markup                                                          |
| Tokens, utilities, preflight          | yes                                                          | yes                                                                      |
| Paints a background and a text colour | yes                                                          | **no** — write `bg-background text-foreground` yourself if you want ours |
| Light or dark                         | a `.dark` ancestor, or `theme` pinning one with `data-theme` | a `.dark` ancestor, and nothing else                                     |
| Wording, locale, time zone, tooltips  | yes, through `ViewSurface`'s props                           | no                                                                       |

**What `fve-tokens` promises is the tokens and the utilities, not components.** The shadcn primitives this package renders with are vendored, updated with `shadcn add --diff`, and not part of its public surface — so build your chrome from your own components, or from your own copy of shadcn/ui, and let the boundary give them this theme's colours and spacing:

<!-- typecheck-context
import { ViewEngine } from '@ahoo-wang/wow-view-engine';
declare const engine: ViewEngine;
import { EmbeddedView } from '@ahoo-wang/wow-view-engine/ui';
declare const id: string;
-->

```tsx
<div className="fve-tokens flex flex-col gap-4">
  <header className="flex items-center gap-2 rounded-lg border bg-card p-4 text-card-foreground">
    …your own header, wearing this theme's tokens…
  </header>
  <EmbeddedView engine={engine} instanceId={id} theme="light" />
</div>
```

`fve-tokens` reads exactly one thing for the mode: a `.dark` class on an ancestor, the same one the surfaces follow — set it on `<html>`, on your app shell, wherever your application already keeps it. It reads no `data-theme` of its own: pinning a mode is what a surface is for. And it hands every element a surface answers for back to that surface, so the view above stays light inside a dark page, tokens and utilities together.

Preflight applies inside the boundary too: your own headings, lists and buttons in that region are reset the same way they would be inside a view. That is the price of the utilities, and it is why the class goes on the chrome that uses them rather than on the whole page.

#### What a workbench does hand over: the reading of a value

`/ui` exports no components of its own to build chrome from, but it does export what it reads a value _with_, so customising one cell never costs the whole workbench. `renderCell` overrides the column you care about and `cellValue` draws the rest exactly as the default does — enum labels from the definition's options, times on the surface's clock, numbers in the field's `numberFormat`:

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
  // Read off the surface the cell is inside: the wording in force, and the
  // language and time zone its values show in.
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
  // What you say about the record views — how a cell reads, whether rows
  // can be picked, what an empty result says — is one object, because none
  // of it means anything to an analysis view.
  record={{
    renderCell: cell => <OrderCell cell={cell} />,
    selectable: false,
    emptyTitle: 'Nothing is waiting to ship',
  }}
  // Every control is there by default; one turned off is absent, not
  // disabled. What a user may do is the store's permissions, separately.
  features={{ export: false }}
/>;
```

`cellText` is the same reading as one line of text — for a CSV, a copied selection, a `title` — and `displayValue` is the field kind's reading alone, `undefined` where the kind has nothing to add and your own rendering stands.

**Surfaces do not nest.** A root inside a root is unsupported: CSS has no nearest-ancestor selector, so an inner surface pinned to the opposite mode redeclares its own tokens but still takes the outer root's `dark:` utilities — light tokens under dark utilities, which nothing can render. Reach for `fve-tokens` instead of a second surface.

### 3b. Or compose your own UI

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
  // The id may name an analysis view or a dashboard; this page draws records.
  if (runtime.kind !== 'record' || !isRecordRuntime(runtime))
    return <NotFound />;
  return <OrdersView runtime={runtime} />;
}

function OrdersView({ runtime }: { runtime: RecordViewRuntime }) {
  const state = useViewRuntime(runtime); // draft, applied, result, issues, dirty
  const filter = useFilterEditor(runtime); // nodes, add/remove/edit, apply
  const table = useRecordTable(runtime); // columns, sort, selection, paging
  // Render any layout from these controllers. No engine internals required.
  return <OrdersLayout state={state} filter={filter} table={table} />;
}
```

### Core only, no React

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

// The kernels take a data definition; `orders` is one.
if (orders.kind !== 'data') throw new Error('orders is a data definition');
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

| Type             | Role                                                                                                                                                                                                                                                                                                                                                                                                                                               | Lives in |
| ---------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------- |
| `ViewDefinition` | Fields, kinds, operators, record and analysis capabilities. Declared or generated, never edited at runtime.                                                                                                                                                                                                                                                                                                                                        | code     |
| `ViewConfig`     | `RecordViewConfig` / `AnalysisViewConfig` / `DashboardViewConfig` discriminated union. A shared `FilterTree` describes scope. It is an intent model: it stores semantics such as "last 7 days" rather than compiled values, and never a UI component name. Analysis covers the whole Wow aggregation protocol; a dashboard panel shows data — a saved view, or an analysis the board owns — or content: a heading, Markdown text, an image, links. | data     |
| `ViewInstance`   | A saved `ViewConfig` plus id, title, scope and an opaque `revision`. Scope is system, shared or personal.                                                                                                                                                                                                                                                                                                                                          | store    |
| `ViewRuntime`    | One open view: draft, applied config, result, status, selection. `subscribe` / `getSnapshot`.                                                                                                                                                                                                                                                                                                                                                      | memory   |
| `ViewEngine`     | Registry of definitions, the store and open runtimes; entry point for open, save, list commands.                                                                                                                                                                                                                                                                                                                                                   | memory   |
| `ViewStore`      | Eight-method persistence port. Ship your own for your backend.                                                                                                                                                                                                                                                                                                                                                                                     | app      |
| `FieldKind`      | Operators, validation, compilation and editor descriptor for one field type.                                                                                                                                                                                                                                                                                                                                                                       | registry |

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

| Entry                        | Exports                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
| ---------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `@ahoo-wang/wow-view-engine` | Model types and constants; the four pure kernels whole (`validate*` / `compile*` / `project*` and the readings beside them); from the runtime, what a host holds and nothing it is built from — `ViewEngine`, `validateDefinition`, the runtime contracts `ViewRuntime`, `RecordViewRuntime`, `DashboardRuntime` and `AnyViewRuntime` with every type their signatures name, `hasResult`, `hasAsked`, `isRecordRuntime`, the write errors `ViewWriteError` and `ViewCommandError`, `ExportCancelled`, `RuntimeEnvironment`, `defaultRuntimeEnvironment`, `ViewSource`, `OptionSource`; the `ViewStore` port and `MemoryViewStore`                      |
| `/react`                     | Hooks and headless controllers with the types they return: `useViewEngine`, `useOpenView`, `useViewRuntime`, `useViewList`, `useViewManager`, `useWorkbench`, `useLeaveGuard`, `useFilterEditor`, `useRecordTable`, `useAnalysisEditor`, `useAnalysisResult`, `useDashboard`, `useSaveCommands`, `RecordActionSlots`, and the write-outcome vocabulary the save commands and the manager share                                                                                                                                                                                                                                                         |
| `/ui`                        | Default components, views and workbenches with their props: `DataWorkbench`, `DashboardWorkbench`, `DashboardEditExtensions`, `useDashboardExtensions`, `EmbeddedView`, `EmbeddedDashboard`, `ViewHeader`, `SaveActions`, `ViewManager`, `LeaveDialog`, `EditorBand`, `FilterPanel`, `StatusStrip`, `AppliedBar`, `ResultToolbar`, `RowActions`, `RecordTable`, `RecordCards`, `RecordPagination`, `AnalysisTable`, `AnalysisChart`, `DashboardGrid`, `HeadingPanel`, `MarkdownPanel`, `ImagePanel`, `LinksPanel`, `MessagesProvider`; the catalogues `defaultMessages` and `zhCN`; the reading of a value, `cellValue`, `cellText` and `displayValue` |
| `/styles.css`                | The theme. Import it explicitly; no JavaScript entry imports CSS, and nothing in it paints outside the two style boundaries `.fve-root` and `.fve-tokens` (preflight and utilities are scoped at build time), both checked by `scripts/verify-package.mjs` on every build.                                                                                                                                                                                                                                                                                                                                                                             |
| `/themes.css`                | The presets, optional: only `--fve-*` assignments keyed by `data-fve-preset` ([Presets](#presets)), checked by the same script.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| `/shadcn-bridge.css`         | Optional: a host's shadcn tokens read into the `--fve-*` variables, bar `input`, `ring`, the status and the chart colours and the shadows, and only while no preset is named ([the bridge](#a-host-with-a-shadcn-theme-shadcn-bridgecss)), checked by the same script.                                                                                                                                                                                                                                                                                                                                                                                 |

That is the public surface, and it is kept name by name. Each code entry's complete list — every name, and whether it is a type or a value — is in `test/surface/` (`root.txt`, `react.txt`, `ui.txt`): `test/publicSurface.test.ts` fails when an entry exports a name its list does not hold or stops exporting one it does, and `scripts/verify-package.mjs` holds each built JavaScript entry to the same list. A name added to a list or taken off one is a change to the public surface and is reviewed as one.

The runtime's own parts are not exported: the request scheduler, the store both runtimes are built on, the refresh timers, the listener sets, the runtime classes and their constructors. A runtime is opened or created through `ViewEngine` and held by its contract, never built by hand; `/react` and `/ui` reach those parts from inside the package, not through an entry.

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

Times read on the engine's clock, `environment.timeZone`: the zone "today" is resolved in, and the one a date histogram that names no `timeZone` is bucketed in, so a row shows the time it was filtered and grouped by.

An unknown key falls back along the dots and then to the key itself, so a gap shows up rather than rendering blank. A test fails when a new issue code has no entry. What a component may ask for is the `MessageKey` union, so a key the catalogue dropped is a compile error; a host's own `messages` stays an open string map.

## Extension points

| Axis        | Mechanism                                                                                                                                                                                                                                                                                                                     |
| ----------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Field type  | Register a `FieldKind` (operators, validation, `compile` to `FilterExpression`, editor descriptor). Its editor is one of the value controls `/ui` already has (`EDITOR_INPUTS`): there is no renderer registry, and a kind asking for another input is refused rather than guessed at                                         |
| Data source | `resolveSource(key)` returns a Wow query client                                                                                                                                                                                                                                                                               |
| Persistence | Implement `ViewStore`                                                                                                                                                                                                                                                                                                         |
| Actions     | Pass `actions` to a workbench — `global`, `bulk` and `row` render functions. They are code, so they are handed over rather than named in a config, and nothing about them is saved. A page fetches only the fields its view shows, so a field an action reads beyond those is declared in the definition's `record.rowFields` |
| Appearance  | CSS variables and theme files; replace components by composing `/react` hooks                                                                                                                                                                                                                                                 |

Built-in kinds: `string`, `number`, `boolean`, `date`, `datetime`, `enum`, `reference`, `array`, `elementMatch`, `search`, and the ones backed by Wow's metadata filters — `documentId`, `aggregateId`, `tenantId`, `ownerId`, `spaceId`, `deletion`.

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
pnpm --filter @ahoo-wang/wow-view-engine test          # unit tests, coverage and three tsc projects
pnpm --filter @ahoo-wang/wow-view-engine build         # build, then verify the published entries
pnpm --filter @ahoo-wang/wow-view-engine test:package  # entries, DOM-free types, no CSS from JS
pnpm storybook                                             # every state of every surface, under "View Engine"
```

`examples/` holds two consumers written against the public contracts rather
than against the internals: `PlainRecordWorkbench.tsx` drives the whole loop
with unstyled HTML, and `FetcherViewStore.ts` implements the `ViewStore` port
over HTTP with `@ahoo-wang/fetcher`.

`@ahoo-wang/fetcher-viewer` is deprecated in favor of this package. The two use different models and APIs.
