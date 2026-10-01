# Wow View Engine

Released with Wow 9.2.0, from the same tag and with the same version.

> **Compatibility.** From 9.2.0 on, a patch release (`9.2.x`) never breaks the public surface: the exports of every entry, including the `ViewStore` port a backend implements; the CSS contract ([what is public](https://wow.ahoo.me/guide/typescript/view-engine-theming#what-is-public)); the message keys and issue codes; and the `wow-view-engine` command. A minor release may break it, and its release notes list every break with the steps to follow; keep the Wow packages on one minor, as [version ranges](https://wow.ahoo.me/guide/typescript/compatibility#version-ranges) explains. A break is a clean change, never a compatibility layer. Views users saved in an older form keep opening: the engine migrates stored configs on read. [docs/design/](https://github.com/Ahoo-Wang/Wow/tree/main/typescript/wow-view-engine/docs/design/) is the source of truth for the model, and this page describes the API as it is now.

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

```bash
pnpm add @ahoo-wang/fetcher @ahoo-wang/fetcher-decorator \
  @ahoo-wang/fetcher-eventstream @ahoo-wang/wow-client @ahoo-wang/wow-view-engine
# the /react and /ui entries
pnpm add react react-dom
```

The version follows Wow, so a minor release may contain breaking changes: keep the Wow packages on one minor with `save-prefix=~` or `--save-exact`, as [version ranges](https://wow.ahoo.me/guide/typescript/compatibility#version-ranges) explains.

Peer dependencies: `@ahoo-wang/wow-client` (and the fetcher packages it needs); `react` and `react-dom` only for the `/react` and `/ui` entries, `react-router` only for `/react-router`, and `mingo` only for `/testing`. The root entry runs in Node. To keep saved views on a Wow server, add [`@ahoo-wang/wow-view-store`](../wow-view-store/README.md). ES modules only; Node `>=22.12.0` or a current browser; TypeScript 6 or later.

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

### Defining a view from the descriptor

A definition written by hand repeats what the service's query descriptor already says: which paths there are, what each holds, what it sorts, filters and aggregates by. `defineView(descriptor, spec)` takes those facts from a descriptor snapshot you commit beside the definition, and leaves `spec` only the choices: which fields a reader sees, in what order and under what words, the categories' wording and tone, the cells, and whatever you narrow. Its result is an ordinary `DataViewDefinition`, so nothing else in the engine knows how it was written.

<!-- typecheck-context
import type { QueryModelDescriptor } from '@ahoo-wang/wow-client';
declare const ordersDescriptor: QueryModelDescriptor;
-->

```ts
import { defineView, text } from '@ahoo-wang/wow-view-engine';

export const orders = defineView(ordersDescriptor, {
  id: 'orders',
  source: 'orders',
  title: text('orders.title'),
  // What a board's time filter reaches its panels through.
  timeField: 'createdAt',
  fields: {
    id: { label: text('orders.id'), cell: 'copyable', analysis: false },
    status: {
      label: text('orders.status'),
      cell: 'status',
      options: {
        PENDING: { label: text('orders.pending'), tone: 'warning' },
        SHIPPED: { label: text('orders.shipped'), tone: 'success' },
      },
    },
    amount: { label: text('orders.amount'), summary: ['SUM'] },
    createdAt: text('orders.createdAt'),
  },
  record: { layouts: ['table', 'card'] },
});
```

- **Facts from the snapshot, capabilities from the source.** A field you do not list does not appear. Its kind, its values, its sensitivity and an array's entries are the model's facts, read from the snapshot; a path or a value it lacks is an admission error (`validateDefinition`, `onIssue`) — the definition still loads, and says where. What a path sorts, filters and aggregates by is the store's: where you narrow nothing the definition takes whatever the source it runs on grants (a search inside an array's entries on Elasticsearch, none on MongoDB), and where you narrow — `operators`, `sortable: false`, `summary`, `analysis` or `analysis: false` — your subset of that. A narrowing beyond the snapshot is a warning, since another store may grant it. A deprecated path warns until you give its reason; a sensitive one is kept out of analyses, a confidential one out of every comparison; a moment groups by the calendar and has an earliest and a latest.
- **The snapshot is committed.** The definition is built when the module loads, the same in a test. A source that answers a descriptor at run time fills what you left open from it and narrows the rest, as every definition is narrowed; one that answers none runs on the snapshot's capabilities.
- **Words are keys.** `text(key)` stands where a label goes, so one definition serves every language. The keys stay everywhere the engine keeps or hands anything: the definition, every view's state and snapshot, `list()`, what an editor is given and gives back, and the store — a view saved from a system view is saved with its keys, so it stays clean, and reverts, in whatever language comes next. They are said only where text is shown or leaves the engine — a cell, a header, a chart's option, an export, an accessible name, a title — in the words of the nearest `ViewHost`'s `messages`, else the ones `ViewEngineOptions.text` gives; so switching the language redraws what is open and reopens, re-queries and dirties nothing. A component of your own says them with `useSay()` from `/ui`, and code outside React with `say(label, words)`; `cellText` and `displayValue` say an option's label through their context's `say`, and a `renderCell` is handed raw values, keys included. An editor of your own shows a label in words and gives the key back where the words were left as shown: `keptKey(typed, original, say, shown)` when it commits a draft (`shown` is the words it opened on, so a language switch while it is open does not turn an untouched label into words), `useSaidText(value)` for a box that writes on every keystroke. The starting words reach what a `ViewHost`, a workbench or an embed draws; a `ViewSurface` of your own around parts, with no host above it, takes `engine={engine}` for them. Names the UI makes up — 「Copy of …」, a new tab's, a new analysis's — are words in the language in force when they are made, and stay so. A key the words lack reads as itself; the outermost `ViewHost` that names the engine reports it once per language (`definition.text.unknown`, or `definition.text.fallback` where the starting words filled it). A literal string is still a label.
- **Time.** `timeField` — or a system view's own, `null` for one read whole — is what a board's one date filter reaches a panel through when the panel has no wire to it; a wire written by hand wins, and `ignoresTime: true` on a panel keeps the range off it.
- `admit` from `/testing` checks all of it in a host's test ([Testing a host](#testing-a-host-an-in-memory-source)).

### 2. Create an engine

<!-- typecheck-context
import { orders } from './orders';
import type { QueryApi } from '@ahoo-wang/wow-client';
declare const queryClients: Record<string, Pick<QueryApi<any>, 'paged' | 'cursor' | 'aggregate'>>;
-->

```ts
import { MemoryViewStore, ViewEngine } from '@ahoo-wang/wow-view-engine';

const engine = new ViewEngine({
  store: new MemoryViewStore(),
  // Pick<QueryApi, 'paged' | 'cursor' | 'aggregate'> from @ahoo-wang/wow-client
  resources: [{ definition: orders, source: queryClients.orders }],
});
```

Each **resource** pairs a definition with where its rows come from. A board's definition has no source — it queries nothing of its own. Sources are found by the key a data definition names (`definition.source`), so two definitions over one query model share it; a data definition registered with no source anywhere is refused at registration (`definition.source.unregistered`), so its workbench says so and only the board panels over it are put out. Register every resource once, at the application's start: **one engine for the application**, whose pages share its queries, preferences and descriptors. The query queue makes room for a board by itself — an open board holds room for its panels on top of `maxQueuedQueries` — so a large board opens whole without raising the limit.

Findings with nothing thrown behind them — a definition's admission, what a descriptor took away — go to `onIssue`. Left out, a development build (`NODE_ENV` of `development`) prints them to the console, one collapsed group per resource, each with how to fix it; production and test runs stay silent.

**What the server admits: `describe`.** A definition is code and cannot know which store it is deployed on: a phrase search that works on Elasticsearch is refused by MongoDB without a text index, and a server whose query guard was raised admits more than the engine's defaults. Give the source a `describe` — wow-client's `describeSnapshot` (or `describeEventStream`) fits as it is — and the engine reads the server's capability descriptor before the first view over that source runs, narrows every definition to what the descriptor admits (an operator, a sort, a search, a group or a metric it does not list is not offered: hidden, not greyed out) and takes the source budgets (`maxPageSize`, `maxPageWindow`, `maxAnalysisRows`, `maxQueryFilterNodes`, `maxFilterValues`) from it; a query over the last two, counted on the compiled query as the server's guard counts it, is refused before it is sent. It checks the descriptor again, with the version it holds, on a refresh and when the page comes back, at most every five minutes. What narrowing took away is told to `onIssue`, once per descriptor version; where the descriptor contradicts the definition — a paging mode the source lacks, a row key it cannot sort, a time kept in another unit — the definition is refused as one failing admission is. Without `describe`, a view runs on the definition and the default limits as before.

<!-- typecheck-context
import { orders } from './orders';
import type { QueryApi, QueryDescriptorApi } from '@ahoo-wang/wow-client';
declare const snapshots: Pick<QueryApi<any>, 'paged' | 'cursor' | 'aggregate'>;
declare const descriptors: QueryDescriptorApi;
-->

```ts
import { MemoryViewStore, ViewEngine } from '@ahoo-wang/wow-view-engine';

// factory.createSnapshotQueryClient() and factory.createQueryDescriptorClient():
// the schema route has no tenant or owner segment, so they are two clients.
const engine = new ViewEngine({
  store: new MemoryViewStore(),
  resources: [
    {
      definition: orders,
      source: {
        paged: snapshots.paged,
        cursor: snapshots.cursor,
        aggregate: snapshots.aggregate,
        describe: descriptors.describeSnapshot,
      },
    },
  ],
});
```

`limits` lays your budgets over `DEFAULT_RUNTIME_LIMITS`: pass only what you change. A source budget you pass only lowers the descriptor's; spreading `DEFAULT_RUNTIME_LIMITS` into it would pin them at the defaults again.

**Exported files neutralize formulas.** A CSV leaves the page and is opened in a spreadsheet, often by someone other than whoever exported it, so every export — a record view's rows and an analysis's **Export data…** — writes a cell whose text starts with `=`, `+`, `-`, `@`, a tab or a carriage return with a leading `'` (OWASP, CSV Injection), header labels included. A cell whose value is a number, and one whose text is a plain number such as `-12.5`, is left as it is: a spreadsheet reads it as a number, never as a formula. Where the file never reaches a spreadsheet, turn it off with `limits: { exportNeutralizeFormulas: false }`, or with `{ neutralizeFormulas: false }` when you call `serializeCsv` yourself.

**Hearing about failures.** A query, a store call, an export, a render, a chart or a declared action's command that fails is said on screen where it happens; for your logs or monitoring, give the environment an `onError`. It is told once per failure, with what was thrown as it was and where it happened; whatever it throws is dropped, and without it nothing is logged anywhere.

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
  store: new MemoryViewStore(),
  resources: [{ definition: orders, source: queryClients.orders }],
  // `defaultRuntimeEnvironment({ onError })` without React.
  environment: browserRuntimeEnvironment({
    onError: ({ kind, error, context }) =>
      sendToMonitoring({ kind, error, ...context }),
  }),
});
```

| `kind`   | Told when                                                                                           | `context.operation`                                             |
| -------- | --------------------------------------------------------------------------------------------------- | --------------------------------------------------------------- |
| `query`  | A view's query failed, or one beside it: its summary row, an analysis's total, a condition's values | `query`, `summaries`, `totals`, `split`, `record`, `candidates` |
| `store`  | A `ViewStore` call rejected: a list, an open, a write (each retry again, under one `requestId`)     | the port's method: `list`, `get`, `create`, `save`, …           |
| `export` | An export's rows could not be fetched, or its file could not be made or handed over                 | `fetch`, `deliver`, `image`                                     |
| `render` | A part of a workbench or an embed threw while drawing — often your action slot                      | `render`                                                        |
| `chart`  | The chart library did not load, or threw drawing                                                    | `load`, `draw`                                                  |
| `action` | A declared action's `run` (or a slot's command) threw on a record, or timed out — not a refusal     | the action's `id`; `context.recordKey` is the record            |

`context` also names the view where it is known — `definitionId`, `instanceId`, `runtimeId` — and, for `render` and `chart`, the `boundary`, the `panelId` and React's `componentStack`. A request called off (superseded by the next one, or cancelled) is not a failure and is not told. `onRenderFailure` on a workbench, a grid or an embed stays: it is that surface's own callback and receives the same `error`; `onError` is the whole engine's. `onIssue` on the engine is for findings with nothing thrown behind them, such as a definition's admission.

### 3. Put the engine above your pages: `ViewHost`

`ViewHost` is the one thing a host writes around its pages. It is made of ports, each a bridge to something the host already has — the **data** (the engine, with its `resources` and `store`), the **router**, the **language**, the **theme**, and the **commands** on each resource (`bind`) — and your router, your i18n and your theme stay yours:

<!-- typecheck-context
import { ViewEngine } from '@ahoo-wang/wow-view-engine';
import type { ReactNode } from 'react';
declare const engine: ViewEngine;
declare const locale: string;
declare const ordersWords: Record<string, string>;
declare const orderActions: import('@ahoo-wang/wow-view-engine').RecordActions;
-->

```tsx
import '@ahoo-wang/wow-view-engine/styles.css';
import '@ahoo-wang/wow-view-engine/themes/porcelain.css';
import { useReactRouter } from '@ahoo-wang/wow-view-engine/react-router';
import { bind, ViewHost } from '@ahoo-wang/wow-view-engine/ui';

const bindings = [
  bind('orders', {
    route: view => (view ? `/orders?view=${view}` : '/orders'),
    reading: { title: row => `Order ${String(row.key)}` },
    actions: orderActions,
  }),
  bind('overview', {
    route: board => (board ? `/boards?view=${board}` : '/boards'),
  }),
];

export function Host({ children }: { children: ReactNode }) {
  return (
    <ViewHost
      engine={engine}
      router={useReactRouter()}
      locale={locale}
      messages={ordersWords}
      bindings={bindings}
      preset="porcelain"
      rememberColorMode="my-app.color-mode"
    >
      {children}
    </ViewHost>
  );
}
```

| Prop                             | Port     | What it does                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| -------------------------------- | -------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `engine`                         | data     | The application's engine, built once. The host that names it says its definitions' keys in its `messages`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| `router`                         | router   | Your router: `useReactRouter()` from `/react-router` (React Router is an optional peer only that entry loads), or the two members of `ViewRouter` over another                                                                                                                                                                                                                                                                                                                                                                                                                             |
| `locale`, `messages`             | language | The language values show in, and wording merged over what is in force — the engine's own (`zhCN`, your rewording) and your definitions' keys alike; a change redraws every open view in place, the same runtimes, no query run again                                                                                                                                                                                                                                                                                                                                                       |
| `bindings`                       | commands | `bind(definitionId, { route, reading, actions, slots })`: `route(instanceId, target?)` is the path of the page that opens it (`instanceId` is `null` for a view nobody saved; there is no `target` where the engine asks for a link, as `useViewNavigation` does); `reading` is the record detail's options (`render`, `title`, `sections`); `actions` are your commands on its records, declared ([Integrating a host](#integrating-a-host-declared-actions)), and `slots` your own markup beside them — on its workbench, in its record detail and on every board's record panel over it |
| `theme`, `preset`, `brand`       | theme    | One of the [two paths](#theme-pick-one-of-two-paths)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| `colorMode`, `rememberColorMode` | theme    | Light or dark on `<html>`: `system` by default, followed live; `light` or `dark` to start pinned; `host` where you paint it yourself. `useColorMode()` gives your switch `{ mode, setMode }`, kept under the `localStorage` key you name                                                                                                                                                                                                                                                                                                                                                   |
| `navigate`                       | router   | Every way off in your own hands rather than the router's: handed resolved through the target's `route` — `{ kind: 'route', path, state }` — or, for a URL and a resource with no route, as it came                                                                                                                                                                                                                                                                                                                                                                                         |

**With a router, the engine keeps the address.** Every way off a board or a view — **Open in the workbench**, a follow-up on a group, a panel's destination, the way back to a board — goes to the route of the resource it leads to, with what the page opens with as the history entry's state (`ViewRouteState`: the view handed over, a board's `filters` and `tab`); a path of yours goes through the router too, and another site opens apart. A `DataWorkbench` or `DashboardWorkbench` given no `instanceId` opens the address's `?view=` — a view of its own definition, so two on one page leave each other's alone — and writes the reader's pick back, a new entry per view; one given no `handOver` opens the view the entry holds; a board — a workbench's or an `EmbeddedDashboard` — given neither half of its filters' pair (`initialFilters`, `onFiltersChange`) or of its tab's reads them from the entry and keeps them there as the reader changes them, each board under its own, so several on one page each find theirs again. A bound resource's record detail follows `?id=`, unless its `reading` holds `open` itself. Your own props still win, each pair on its own. Another router is two members:

<!-- typecheck-context
type ViewRouter = import('@ahoo-wang/wow-view-engine/ui').ViewRouter;
declare const pathname: string;
declare const search: string;
declare const state: unknown;
declare function push(path: string, state: unknown, replace: boolean): void;
-->

```ts
const router: ViewRouter = {
  location: { pathname, search, state },
  go: (path, options) => push(path, options?.state, options?.replace ?? false),
};
```

Make it a new object whenever the location moves, and the same one while it does not — `useMemo` over the location's parts, as `useReactRouter` does: everything that reads the address reads it again when, and only when, the router object is new.

**Your navigation is data.** The engine draws views and leaves the page to you, so there is no page shell; `useViewNavigation()` gives your shell its places instead — each resource you bound a `route` to, in the order you registered them: `{ id, kind, title, path, current, views }`, `views` being its system views (a dashboard definition's system boards), each `{ id, title, path, current }`. The titles are said in the words in force, and `current` reads the router's address. Draw it with your own components — a shadcn `Sidebar`, a top bar — under whatever names you give your places:

<!-- typecheck: skip — Sidebar, SidebarMenu and Link are the host's own components -->

```tsx
function AppSidebar() {
  const places = useViewNavigation();
  return (
    <Sidebar>
      <SidebarMenu>
        {places.map(place => (
          <SidebarMenuItem key={place.id}>
            <SidebarMenuButton
              isActive={place.current}
              render={<Link to={place.path} />}
            >
              {place.title}
            </SidebarMenuButton>
          </SidebarMenuItem>
        ))}
      </SidebarMenu>
    </Sidebar>
  );
}
```

A surface under it takes only what differs where it stands — `<DataWorkbench definitionId="orders" />`, `<EmbeddedDashboard instanceId="…" />`. Its own `engine`, `messages`, `locale`, `onNavigate` or `record` still win, and hosts nest, the inner one's bindings winning by id: a page with two engines is written as before. An engine's definitions speak the `messages` of the outermost host that names it — one engine speaks one language at a time — so an inner host in another language — naming the engine again at any depth, or naming none — rewords the engine's own messages under it, not the definitions' keys; two sibling hosts naming one engine must give it the same words. `EmbeddedView`'s `detail` reads records the bound way (`render`, `title`, `sections`) but holds its own open record: two embeds of one definition never open the same one, and never write the binding's `open`.

#### Integrating a host: declared actions

A command on a record is declared, not drawn. You say **what** — which commands a record takes, when, what a refusal says, whether to ask first and what to ask for; the engine does **where and how**:

| You declare                                                                                                    | The engine does                                                                                                                                                  |
| -------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| The commands, each `run` over your generated command client                                                    | Where they go: the primary one as a button in the row (and on the card), the rest behind the record's 「⋯」 menu, all of them over a selection and in the detail |
| When one is available (`available` returns `true` or the reason), and when that flips on its own (`changesAt`) | Disabled with the reason (a tooltip, the button's description, atop the menu); asked again when the rule said, with no timer of yours                            |
| Its words, its `tone`, whether to ask first (`confirm`), what it needs (`form`)                                | The question and the form (the condition editor's value controls), focus held inside, keyboard, and the start and end said aloud                                 |
| When it is done: `run` resolves once the read model shows it                                                   | Over a selection: 「3 of 5 can take it」 with the refused and why, one press to pick only those; a few at a time, progress, stop, the outcome, a refresh after   |

<!-- typecheck-context
declare const commands: {
  ship(id: string): Promise<void>;
  cancel(id: string): Promise<void>;
  prioritize(id: string, priority: string): Promise<void>;
};
declare function holdOf(row: import('@ahoo-wang/wow-view-engine').RecordRow): number;
-->

```ts
import { actions, text } from '@ahoo-wang/wow-view-engine';

export const orderActions = actions([
  {
    id: 'ship',
    label: text('orders.ship'),
    primary: true,
    // `true`, or why not — a key, said in the reader's language.
    available: (row, { now }) =>
      holdOf(row) > now ? text('orders.onHold') : true,
    // When that flips by itself; the engine asks again then.
    changesAt: (row, { now }) => (holdOf(row) > now ? holdOf(row) + 1 : null),
    // One order ships at a press; a selection is counted first.
    confirm: { title: text('orders.shipTitle'), ask: 'bulk' },
    run: row => commands.ship(String(row.key)),
  },
  {
    id: 'cancel',
    label: text('orders.cancel'),
    tone: 'danger',
    // `{count}` is how many it is for, with a `-one` key where a language says one apart.
    confirm: {
      title: text('orders.cancelTitle'),
      body: text('orders.cancelBody'),
    },
    run: row => commands.cancel(String(row.key)),
  },
  {
    id: 'priority',
    label: text('orders.priority'),
    // One field of options is a choice: its options are the menu's items.
    form: {
      priority: {
        label: text('orders.priorityField'),
        options: [
          { value: 'HIGH', label: text('orders.high') },
          { value: 'NORMAL', label: text('orders.normal') },
        ],
      },
    },
    // Asked with the option in hand: the one the order has is not offered.
    available: (row, { input }) =>
      input?.priority === row.data.priority
        ? text('orders.samePriority')
        : true,
    run: (row, { priority }) =>
      commands.prioritize(String(row.key), String(priority)),
  },
]);
```

- **`run` resolves after the read model reflects the command.** The engine reads the view again right after, and a refresh that ran ahead of the command shows the old state. A Wow command waits for `CommandStage.SNAPSHOT` (`waitStrategy({ stage: CommandStage.SNAPSHOT })` as the request's headers), or the stage your projection needs. What it throws is read for the service's own reason.
- `run` takes one record; a selection is run a few at a time and stopped between records. There is no **runMany** until a command has a batch form.
- **Make each command idempotent** — send the aggregate version the row showed (`commandHeaders({ aggregateVersion })`, so a second send after the first took is refused as a conflict) and a request id the service deduplicates a retry by (`requestId`). A `run` that times out, is aborted or loses the network after sending has an outcome nobody knows: the engine reports it as 「outcome unknown, refresh to check first」 and lets go of those rows rather than leaving them selected for a blind rerun, but a reader who checks and presses again must not refund twice. `timeout: ms` on an action gives each record a deadline (past it the outcome is unknown); without one, Stop pressed a second time stops waiting. A record the action refused when its turn came is reported as not run, not as failed, and stays selected.
- `on: ['row', 'bulk', 'detail']` narrows where one is offered (every place by default); `hidden: row => …` leaves it off a record the reader may not act on, where `available` shows it disabled with why.
- A `form` of more than one field (or one without options) opens a form in the question: text, a number or yes/no, each field `required` unless it says `required: false`, `initial` for what it opens with; `run` gets the values by name.
- Every word is a key or plain words, said where it is shown (`text(key)`, [Wording and language](#wording-and-language)).
- **Slots are the escape hatch**, drawn after the declared actions: `slots: { row, bulk, global }` render your own markup — a link out, a control a declaration cannot say. A slot that sends a command anyway calls `run` on its context, so it reports on the surface's line: `row: ({ row, run, busy }) => …`, `run({ title, each: key => … })`.

Your tests read the declarations the way the engine does, without a screen — `actionHarness` from `/testing` ([Testing a host](#testing-a-host-an-in-memory-source)).

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

The theme follows the host through a `.dark` class on any ancestor; pass `theme="light"` or `theme="dark"` to `ViewSurface` (or to a workbench or an embed) to pin one view, or `theme="system"` to follow the reader's `prefers-color-scheme`, live, on a page with no switch of its own. Popups portalled to `<body>` carry the mode the surface resolved, so the class does not have to sit on `<html>`. A preset is chosen the same way — see [Presets](https://wow.ahoo.me/guide/typescript/view-engine-theming#presets).

**Import order does not matter, and your classes work inside a surface.** `styles.css` may come before or after your own Tailwind stylesheet. Its utilities carry the prefix `fve:` (`fve:flex`, `fve:md:w-64`), so they never share a name with yours: our `fve:md:w-64` and your global `.w-full` are different rules in either order, and a breakpoint class of yours on your own markup inside a surface (`sm:grid-cols-3`) is weighed only against your own. The rest of the stylesheet — preflight, the base and the tokens — stays inside the two boundaries, at no extra weight (`scripts/verify-package.mjs` checks that every class it styles is prefixed and every other rule is scoped). The `fve:` utilities are the engine's own, not for your markup: the stylesheet holds one only while a component of the engine writes it. What is public is the tokens — see [What is public](https://wow.ahoo.me/guide/typescript/view-engine-theming#what-is-public).

#### A page that has its own `main`

A workbench's column is the page's `main` landmark, named after the open view: a workbench is usually what the page is for. If your page already has a `<main>` and the workbench sits inside it, two `main`s is one too many (axe `landmark-no-duplicate-main`, `landmark-main-is-top-level`), so say `landmark="region"` on `DataWorkbench` or `DashboardWorkbench`:

| Prop       | Default  | Renders                                                                                                                           |
| ---------- | -------- | --------------------------------------------------------------------------------------------------------------------------------- |
| `landmark` | `'main'` | `'main'`: `<main>` named after the open view. `'region'`: `<section>` with the same name — the definition's while no view is open |

The layout is the same either way: the stylesheet reads the column by an attribute of its own, never by its tag. That attribute, like every `data-slot`, is the engine's, not a selector for your stylesheet. Embeds draw no `main` at all and take no such prop.

<!-- typecheck-context
import { ViewEngine } from '@ahoo-wang/wow-view-engine';
declare const engine: ViewEngine;
import { DataWorkbench } from '@ahoo-wang/wow-view-engine/ui';
-->

```tsx
export function OrdersPage() {
  return (
    <main>
      <h1>Orders</h1>
      <DataWorkbench engine={engine} definitionId="orders" landmark="region" />
    </main>
  );
}
```

#### Which view is open, and your route

One data definition holds its record views and its analysis views, and `DataWorkbench` lists them together: the user switches between a table of orders and a chart of them as between any two views, and the "new view" button asks which kind to make. A host that wants a page of one kind narrows it — `viewKinds={['record']}` — and the other kind is neither listed nor openable there.

A view somebody opened is a link they can send, so both workbenches take `instanceId` and `onInstanceChange` — the two directions in and out of your router. `DataWorkbench` and `DashboardWorkbench` share the contract exactly. Under a `ViewHost` with a `router` you write neither: the workbench keeps the open view in the address's `?view=` itself. The props are for a host that keeps it elsewhere, or a page that decides more than the view (the console's failed executions narrow a view by a link's `?cluster=`).

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

**The switches** — each absent, not greyed, when off. The tier is the ceiling and a switch opts in within it: search, export, a record's detail and fill-the-screen are reader controls, so they have no effect in the static tier:

| Prop                          | Default   | What it does                                                                                                                                                                                                                                                                                                                                                                             |
| ----------------------------- | --------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `withTitle`                   | off       | Draws the view's or board's title                                                                                                                                                                                                                                                                                                                                                        |
| `headingLevel`                | `2`       | The heading level the embed titles at: its own title, and a board's panels one level under it (or at it, with no title). Your page owns its `h1`                                                                                                                                                                                                                                         |
| `withPanelTitles` (dashboard) | on        | Off, each panel's title is kept for screen readers only                                                                                                                                                                                                                                                                                                                                  |
| `withSearch` (record)         | off       | The view's search box, where its definition declares a search field; interactive tier only                                                                                                                                                                                                                                                                                               |
| `withExport` (record)         | off       | The export button and window, rows picked with it; interactive tier only                                                                                                                                                                                                                                                                                                                 |
| `detail` (record)             | off       | A row opens the record's detail, read whole and read-only — no row commands, nothing written; `true` (read as the definition's binding's `reading` says, where there is one), or the workbench's `record.detail` options (`open`/`onOpenChange`, your `sections`). Inside a drawer it opens as a nested sheet: Escape closes it alone, focus goes back to the row. Interactive tier only |
| `withExport` (dashboard)      | off       | **Export data…** in a panel's "⋯" menu, the same export window; interactive tier only                                                                                                                                                                                                                                                                                                    |
| `autoRefresh`                 | on        | Refreshes on the interval its author saved; off, never on its own                                                                                                                                                                                                                                                                                                                        |
| `withRefresh` (dashboard)     | off       | **Updated 10:32** in the first row — when the panels on screen were read, the earliest of them — and, in the interactive tier, the refresh button beside it (no interval menu); refreshing writes nothing                                                                                                                                                                                |
| `caption` (dashboard)         | none      | What the board's numbers are read as (a `ReactNode`, e.g. "cards read Sep 21 (yesterday), vs the day before"): never drawn on the page, where the host draws it by its own title; drawn under the board's title while it fills the screen, when the board also titles itself even with `withTitle` off (D70)                                                                             |
| `openInWorkbench`             | on        | Whether **Open in the workbench** is offered in the interactive tier                                                                                                                                                                                                                                                                                                                     |
| `expandable`                  | off       | **Fill the screen** at the end of the embed's first row, in the interactive tier: the surface fills the screen in place, as a workbench's does; Escape puts it back                                                                                                                                                                                                                      |
| `size`                        | `content` | `content` sizes to what it shows, with a cap (a record or analysis table scrolls inside `--fve-record-table-max-h`); `fill` fills its container — a whole-page embed, a wall screen                                                                                                                                                                                                      |

**A board's filters, each in one of three modes** (`filterModes`, by filter name; `groupingMode` for the time grouping): `adjustable` — on the bar, the reader's to adjust for this viewing, as in the workbench, and the default; `locked` — on the bar as what it holds, with a lock and no control; `hidden` — not on the bar, still narrowing the panels wired to it. Locked and hidden filters are held by the runtime, so nothing the reader does — a value, **Clear**, a press that cross-filters — changes them. Their values are the page's own, `pageValues` (their default where it names none): in force from the first query, and followed as the prop changes — a customer page moving to the next customer takes the board with it. The reader's filters are your address's, `initialFilters` and `onFiltersChange`, read and reported exactly as `DashboardWorkbench` does. **A locked or hidden value never travels through the address**: an entry for one in `initialFilters` is ignored, and `onFiltersChange` reports only the filters the reader can set — otherwise a reader who edits the address changes the customer, the opposite of locking it. A board takes no condition tree (`EmbeddedDashboard` has no `scopeFilter`): to narrow it, declare the filter on the board and lock or hide it.

**Your commands on a board's record panels** come from what you bound to the definition the panel's view is over (`bind(definitionId, { actions, slots })` on the `ViewHost`), on `EmbeddedDashboard` and `DashboardWorkbench` alike: the same declared `actions` a record workbench takes — a record's, drawn in both tiers, and a selection's, drawn only where rows can be picked (the interactive tier) — with the run's progress and outcome above the panel's rows; after a command the whole board is read again. They are your commands against your service: the embed still writes nothing. A record panel also says how many rows its query matched, with the pages in the interactive tier.

**Locking is not a security boundary.** The condition a page locks is put together in the browser and sent with the query; it only keeps the reader from changing it on screen, or seeing anything else there. Anyone who edits the page's script or calls the API directly can ask for another customer. Tenancy, ownership and permission must be enforced by the Wow backend — above all on a page outside your organisation. This package is a library in your host's process: it does not do what Metabase does with iframes, signed tokens or SSO, because identity and permission belong to your host and your backend.

Filling the screen: with `expandable` on, an interactive embed draws the control itself. To put it in your own chrome instead — or to fill the screen with a static wall screen — pass a `ref` and point `useViewExpansion` at it.

#### Theme: pick one of two paths

> **Already switching light and dark yourself** (next-themes, a `.dark` toggle of your own)? Write **`colorMode="host"`** on `ViewHost`. Left at its default, the engine paints `<html>`'s `.dark` too, and the two fight over it.

Import `styles.css`, then make one choice on `ViewHost`:

| Path                   | For                                                 | Write                                                                              | The engine                                                                                                         |
| ---------------------- | --------------------------------------------------- | ---------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------ |
| The engine follows you | A host with a shadcn theme (Tailwind v4)            | `theme="host"`, and import `shadcn-bridge.css`                                     | Reads your shadcn tokens into its own; keeps its own `input`, `ring`, status and chart colours, for their contrast |
| You follow the engine  | A host with no theme, or happy to wear the engine's | `preset="porcelain"` (import `themes/porcelain.css`), optionally `brand="#1d4ed8"` | Names the preset and your brand on `<html>`, and lends your own chrome the same shadcn names through `fve-tokens`  |

```ts
import '@ahoo-wang/wow-view-engine/styles.css';
// The engine follows you:
import '@ahoo-wang/wow-view-engine/shadcn-bridge.css';
// …or you follow the engine:
// import '@ahoo-wang/wow-view-engine/themes/porcelain.css';
```

**Light and dark are `ViewHost`'s.** `colorMode="system"`, the default, paints `<html>`'s `.dark` and `color-scheme` before the first paint and follows the system; `light` or `dark` start pinned; `rememberColorMode` keeps the reader's pick, made through `useColorMode()`; `host`, as above, follows yours. `theme="light"`, `"dark"` or `"system"` on a surface still pins that one view. Dark values of your own are `--fve-dark-*`, not `--fve-*` under `.dark`: a view pinned light on a dark page would inherit those.

**Your own chrome** wears the theme with `className="fve-tokens"` on the shell that uses it — not on `<body>`: the boundary brings a preflight — and on the portal of each popup of your own, which leaves the shell for `<body>` (`<Menu.Portal className="fve-tokens">`). Inside it, your own classes read the theme's tokens under their shadcn names (`bg-card` mapped to `var(--card)`); the engine's `fve:` utilities are not public ([What is public](https://wow.ahoo.me/guide/typescript/view-engine-theming#what-is-public)).

Everything else — one `--fve-*` override and the full token table, `tokens` on one surface, the presets one by one, a brand's bounds, roles and links, density, the rise and fall colours, the bridge's fine print, what an override owes, and `theme-check` for your CI — is in the guide, [Theming the view engine](https://wow.ahoo.me/guide/typescript/view-engine-theming).

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

#### The record detail: your sections, and the record in your address

A press on a row (or Enter on it) opens the record whole in a side panel, laid out by the definition's field groups. `record.detail` lets you add to it and hold it:

- **`sections(context)`** returns your own sections for the record open — each an `id`, a `title` and a `render()` — beside the engine's. `placement` puts one at the `'start'`, at the `'end'` (the default) or `{ after: '<field group id>' }`. `render` is called only while the record is on screen, so an `EmbeddedView` in a section reads its data when the reader opens the record; each section is its own labelled region, drawn inside a render boundary of its own (`'detail'`). The context carries the `row` (the page's row until the whole record has come, `complete` from then on), the `runtime` and `refresh`, as a row action's does. A section that shows a definition field its own way names it in `fields` (`fields: ['state.error.stackTrace']`): the engine's groups leave that field out, so the record does not read it twice, and a group left empty is not drawn.
- **`render(context)`** reads the record your way instead: whatever it returns is the whole body, in place of the definition's groups and your `sections` — for a record that answers one question (why did this fail, and will trying again help) better told in a layout of its own than as fields. The panel stays the engine's: it opens at once from the row, reads the record whole, says when it is gone, refused or unreadable, carries the row's commands, focus and the way back. Same context, same render boundary. **`title(row)`** names the record in the header in place of its key, which then stands above the name.
- **`open` / `onOpenChange`** hold which record is open, the way `instanceId` / `onInstanceChange` hold the open view — under a `ViewHost` with a `router`, a bound resource's detail holds it in the address's `?id=` already, and this is for a host that keeps it elsewhere: leave `open` out and the workbench owns it; pass a key (or `null`) and every value opens what it names — a record on another page too, which is read on its own (`runtime.fetchRecord`, within the injected scope only) with its own reading, not-there, not-permitted (HTTP 401/403) and failed-with-retry states. A press and a close only ask, through `onOpenChange`, which is told in either mode.

<!-- typecheck-context
import { ViewEngine } from '@ahoo-wang/wow-view-engine';
import { DataWorkbench, EmbeddedView } from '@ahoo-wang/wow-view-engine/ui';
declare const engine: ViewEngine;
declare const params: URLSearchParams;
declare function setId(id: string | null): void;
declare function RetryForm(props: { id: string }): React.ReactNode;
-->

```tsx
<DataWorkbench
  engine={engine}
  definitionId="failures"
  record={{
    detail: {
      // `?id=` opens that record, and opening or closing one writes it back.
      open: params.get('id'),
      onOpenChange: key => setId(key === null ? null : String(key)),
      sections: ({ row }) => [
        {
          id: 'retry',
          title: 'Execution context',
          placement: 'start',
          render: () => <RetryForm id={String(row.key)} />,
        },
        {
          id: 'history',
          title: 'Execution history',
          render: () => (
            <EmbeddedView
              engine={engine}
              instanceId="execution-history"
              interaction="interactive"
              scopeFilter={{
                op: 'and',
                children: [
                  { field: 'aggregateId', operator: 'EQ', value: row.key },
                ],
              }}
            />
          ),
        },
      ],
    },
  }}
/>
```

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

## Testing a host: an in-memory source

A host's own tests — a page that opens a view, a board, a demo without a backend — need a source that answers the queries the engine really sends. `@ahoo-wang/wow-view-engine/testing` has one: `memorySource(documents, options?)` is a `ViewSource` over JSON documents held in memory that filters, sorts, pages, projects and aggregates the way a Wow service over MongoDB does. Its answers are held to the server's own semantics — the `FilterSemantics` matrix in `wow-tck` and the aggregation cases of the query TCK — by this package's suites, so a test sees the engine's query answered as production answers it, not a canned result that shows rows the filter excludes.

<!-- typecheck-context
import { orders } from './orders';
-->

```ts
import { FilterOperator } from '@ahoo-wang/wow-client';
import { MemoryViewStore, ViewEngine } from '@ahoo-wang/wow-view-engine';
import { matches, memorySource } from '@ahoo-wang/wow-view-engine/testing';

// Snapshots as the service returns them: the envelope, `state`, epoch-ms times.
const documents = [
  {
    aggregateId: 'O-1',
    eventTime: 1_790_000_000_000,
    state: { status: 'PAID', total: 120 },
  },
  {
    aggregateId: 'O-2',
    eventTime: 1_790_000_060_000,
    state: { status: 'CANCELLED', total: 80 },
  },
];
const source = memorySource(documents, {
  // The clock BEFORE_NOW / AFTER_NOW compare against: pin it with the page's.
  now: () => Date.parse('2026-09-27T00:00:00Z'),
});
const engine = new ViewEngine({
  resources: [{ definition: orders, source }],
  store: new MemoryViewStore(),
});

// A test's own condition, asked of one document the same way.
const paid = matches(documents[0], {
  op: FilterOperator.EQ,
  field: 'state.status',
  value: 'PAID',
});
```

What it promises: every filter operator the engine compiles, with MongoDB's treatment of a missing field, an explicit `null`, an empty string or array, array elements and case; `DELETION` over a `deleted` flag (a document with `deleted: true` is left out unless the filter asks); paging, cursor (an offset) and sort; projection; and aggregation — `elements` (paths and gate filters relative to the element), `TERMS`, `HISTOGRAM`, `DATE_HISTOGRAM` and `DATE_PART` in a zone (UTC by default), `dense`, every metric with its own filter, `DERIVED`, `having`, the order Wow gives groups (the sort, then each group alias ascending) and its default `limit` of 100. `PERCENTILE` is exact, where a server's is an estimate between the same two ranks. What it has no reading of — `ID`, `TENANT_ID`, `SPACE_ID`, the calendar filters the engine never sends — is refused with an error, so a query a test starts to send fails instead of getting a plausible wrong answer. `timeField` keeps a large set in the order of one epoch-ms column and cuts a range on it by binary search; `remember` answers a repeated aggregation from memory, for documents that never change. The entry is headless — no React, no DOM, no stylesheet. It evaluates filters with `mingo`, MongoDB's query language in JavaScript, which is an optional peer dependency: the package does not install it for you, so a host that imports `/testing` adds it to its own dev dependencies — `pnpm add -D mingo` (or `npm install -D mingo`). No other entry loads it.

`admit(definitions, descriptors, { text })` admits everything a host declares as the engine would — each definition's keys said, its own rules, its boards against every other definition, and each data definition narrowed to its committed descriptor (by `source`) — and returns every finding with the definition it is about, `[]` when all of it holds. It takes definitions, or resources holding one (`{ definition }`), as they are registered:

<!-- typecheck-context
import type { QueryModelDescriptor } from '@ahoo-wang/wow-client';
import type { DataViewDefinition, DashboardDefinition, TextResolver } from '@ahoo-wang/wow-view-engine';
declare const orders: DataViewDefinition;
declare const overview: DashboardDefinition;
declare const ordersDescriptor: QueryModelDescriptor;
declare const english: TextResolver;
declare function expect(value: unknown): { toEqual(expected: unknown): void };
-->

```ts
import { admit } from '@ahoo-wang/wow-view-engine/testing';

expect(
  admit([orders, overview], { orders: ordersDescriptor }, { text: english }),
).toEqual([]);
```

`actionHarness(actions, rows, { now })` reads a host's declared actions by the engine's own rules, without rendering: where each is offered (`at`), whether a record takes one and why not (`state`), how a selection splits (`bulk`), what pressing it asks (`asks`), its form or choice (`form`, `choice`, `missing`), when a record flips on its own (`changesAt`), and `run` as the engine sends it — refused with the reason (`ActionRefused`) where the record does not take it:

<!-- typecheck-context
import { text } from '@ahoo-wang/wow-view-engine';
import type { RecordActions, RecordRow } from '@ahoo-wang/wow-view-engine';
declare const orderActions: RecordActions;
declare const rows: RecordRow[];
declare function expect(value: unknown): { toEqual(expected: unknown): void; toBe(expected: unknown): void };
-->

```ts
import { actionHarness } from '@ahoo-wang/wow-view-engine/testing';

const orders = actionHarness(orderActions, rows, {
  now: Date.parse('2026-09-28'),
});
expect(orders.state('ship', 'O-2').reason).toBe(text('orders.onHold'));
expect(orders.bulk('ship').able).toEqual(['O-1']);
expect(orders.asks('cancel', 'row').asks).toBe(true);
```

## Concepts

| Type             | Role                                                                                                                                                                                                                                                                                                                                                                                                                                               | Lives in |
| ---------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------- |
| `ViewDefinition` | Fields, kinds, operators, record and analysis capabilities. Declared or generated, never edited at runtime.                                                                                                                                                                                                                                                                                                                                        | code     |
| `ViewConfig`     | `RecordViewConfig` / `AnalysisViewConfig` / `DashboardViewConfig` discriminated union. A shared `FilterTree` describes scope. It is an intent model: it stores semantics such as "last 7 days" rather than compiled values, and never a UI component name. Analysis covers the whole Wow aggregation protocol; a dashboard panel shows data — a saved view, or an analysis the board owns — or content: a heading, Markdown text, an image, links. | data     |
| `ViewInstance`   | A saved `ViewConfig` plus id, title, scope and an opaque `revision`. Scope is system, shared or personal.                                                                                                                                                                                                                                                                                                                                          | store    |
| `ViewRuntime`    | One open view: draft, applied config, result, status, selection. `subscribe` / `getSnapshot`.                                                                                                                                                                                                                                                                                                                                                      | memory   |
| `ViewEngine`     | Registry of definitions, the store and open runtimes; entry point for open, save, list commands.                                                                                                                                                                                                                                                                                                                                                   | memory   |
| `ViewStore`      | Eight-method persistence port, plus an optional `changeAudience`. Ship your own for your backend.                                                                                                                                                                                                                                                                                                                                                  | app      |
| `FieldKind`      | Operators, validation, compilation and editor descriptor for one field type.                                                                                                                                                                                                                                                                                                                                                                       | registry |

Three names read two ways, so here is which is which:

- **`*Spec`** is either what a host offers or what a view stores. `DefineViewSpec`, `FieldSpec`, `FieldAnalysisSpec` and `AnalysisSpec` are a host's input to `defineView` — the fields, operators and analyses it offers. `ChartSpec`, `RecordTableSpec`, `AnalysisTableSpec` and every chart family's `*Spec` are parts of a stored `ViewConfig`.
- **`RecordProjection` / `AnalysisProjection`** are what `projectRecord` / `projectAnalysis` make of a result: the columns, rows and series a surface draws. A _view_ is a saved `ViewInstance`; `/ui`'s `RecordViewProps` are the workbench's `record` prop, not a projection's.
- **`kinds`** on `ViewEngineOptions` and `ViewEngine` is the field-kind registry; `DataWorkbench`'s `viewKinds` is which kinds of view (`'record'`, `'analysis'`) it lists.

## View management

- **Lifecycle.** Create, save, save as, rename and delete are all `ViewEngine` commands; the default UI and custom compositions share one path. Only the config is saved, never selection, page or results.
- **Three scopes.** `system` views are configured by developers or operators as the baseline and common views of a definition: visible to everyone, read-only, can be saved as; declare them in code under `definition.views` or return them from the server. `shared` views are created by users with permission and visible to everyone on the definition. `personal` views are visible to their owner only.
- **Two questions, one value.** The three scopes are the legal combinations of who a view is for and whether a user configured it: a system view is always a shared view, and a personal system view cannot be written down. `audienceOf(scope)` answers the first, `isSystemScope(scope)` the second, and everything from the dashboard reference rule to the sidebar's grouping asks through them. What a user creates is a `ViewAudience`, never a scope.
- **The sidebar.** Views are grouped by audience — personal first, then shared, with system views among the shared ones under a `system` tag — and each row is prefixed by its kind, because one data definition holds record and analysis views together. The heading is the definition's own title. `ViewInstanceSummary` carries `kind` for this: it is the config's tag, projected, so a store answers `list` from the configs it holds.
- **Permissions.** `store.permissions()` supplies permissions synchronously and only drives button availability. The server is the authority. Shared views you cannot edit and system views can still be saved as personal ones.
- **List and preferences.** List, preferences and permissions load independently and never block each other; personal ordering and the default view live in `ViewPreferences`, and deleting an instance never rewrites them.
- **Conflicts and unknown outcomes.** On a revision conflict choose reload or overwrite, or save as; when a request was sent but its outcome is unknown, retry with the same `requestId`. The draft is always kept.
- **Leave protection.** Closing a view with an unsaved draft or an unknown write asks for confirmation; navigation never cancels an in-flight write.

Details in [docs/design/management.md](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/docs/design/management.md).

## Entries

| Entry                        | Exports                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         |
| ---------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `@ahoo-wang/wow-view-engine` | Model types and constants; the four pure kernels (`validate*` / `compile*` / `project*` and the readings beside them); from the runtime, what a host holds and nothing it is built from — `ViewEngine`, `validateDefinition`, the runtime contracts `ViewRuntime`, `RecordViewRuntime`, `DashboardRuntime` and `AnyViewRuntime` with every type their signatures name, `hasResult`, `hasAsked`, `isRecordRuntime`, `actions` with the declared actions' types, the write errors `ViewWriteError` and `ViewCommandError`, `ExportCancelled`, `RuntimeEnvironment`, `defaultRuntimeEnvironment`, `ViewSource`, `OptionSource`; the `ViewStore` port, `MemoryViewStore` and `localStorageSnapshot`                 |
| `/react`                     | Hooks and headless controllers with the types they return: `useViewEngine`, `useOpenView`, `useViewRuntime`, `useViewList`, `useViewManager`, `useWorkbench`, `useLeaveGuard`, `useFilterEditor`, `useRecordTable`, `useAnalysisEditor`, `useAnalysisResult`, `useDashboard`, `useSaveCommands`, `useRecordActions` (the declared actions of one record surface: what each record and the selection are offered, the question or form waiting, the one runner), `RecordActionSlots`, and the two write-outcome types the save commands report (`SettledWrite`, `RecoveredWrite`)                                                                                                                                |
| `/ui`                        | Default components, views and workbenches with their props: `DataWorkbench`, `DashboardWorkbench`, `DashboardEditExtensions`, `useDashboardExtensions`, `EmbeddedView`, `EmbeddedDashboard`, `ViewHost`, `bind`, `useEngine`, `useViewNavigation`, `useColorMode`, `ViewHeader`, `SaveActions`, `ViewManager`, `LeaveDialog`, `EditorBand`, `FilterPanel`, `StatusStrip`, `AppliedBar`, `ResultToolbar`, `RowActions`, `RecordTable`, `RecordCards`, `RecordPagination`, `AnalysisTable`, `AnalysisChart`, `DashboardGrid`, `HeadingPanel`, `MarkdownPanel`, `ImagePanel`, `LinksPanel`, `MessagesProvider`; the catalogues `en` and `zhCN`; the reading of a value, `cellValue`, `cellText` and `displayValue` |
| `/testing`                   | `memorySource` and `matches`: an in-memory `ViewSource` with Wow's query semantics; `resolveNavigation`: the engine's own routing of a way off through your bindings, for your routing tests; `admit`: a host's declarations admitted over its committed descriptors; `actionHarness`: a host's declared actions read by the engine's rules, without a screen — for a host's tests ([Testing a host](#testing-a-host-an-in-memory-source))                                                                                                                                                                                                                                                                      |
| `/react-router`              | `useReactRouter`: React Router as the router port a host hands its view host. React Router is an optional peer, and this is the one entry that loads it                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         |
| `/styles.css`                | The theme. Import it explicitly; no JavaScript entry imports CSS, and nothing in it paints outside the two style boundaries `.fve-root` and `.fve-tokens` (preflight and the base are scoped at build time at no extra weight, and every utility is `fve:`-prefixed, so it never shares a name with yours and your import order does not matter) — bar the preset reset, which only empties the `--fvp-*` layer where a preset is named — all checked by `scripts/verify-package.mjs` on every build.                                                                                                                                                                                                           |
| `/themes.css`                | The presets, optional: only `--fvp-*` assignments keyed by `data-fve-preset` ([Presets](https://wow.ahoo.me/guide/typescript/view-engine-theming#presets)), checked by the same script.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         |
| `/themes/<name>.css`         | One preset alone, for a host that wears one: the same block `themes.css` holds for it ([Presets](https://wow.ahoo.me/guide/typescript/view-engine-theming#presets)).                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| `/shadcn-bridge.css`         | Optional: a host's shadcn tokens read into the `--fvp-*` variables, bar `input`, `ring`, the status and the chart colours and the shadows, and only while no preset is named ([the bridge](https://wow.ahoo.me/guide/typescript/view-engine-theming#the-shadcn-bridge)), checked by the same script.                                                                                                                                                                                                                                                                                                                                                                                                            |

That is the public surface, and it is kept name by name. Each entry writes every name it exports, grouped by the file that declares it; none re-exports a whole module (`test/architecture.test.ts`), so an `export` a file writes for its neighbours never becomes public by accident. Each code entry's complete list — every name, and whether it is a type or a value — is in `test/surface/` (`root.txt`, `react.txt`, `ui.txt`, `testing.txt`, `react-router.txt`): `test/publicSurface.test.ts` fails when an entry exports a name its list does not hold or stops exporting one it does, and `scripts/verify-package.mjs` holds each built JavaScript entry to the same list. A name added to a list or taken off one is a change to the public surface and is reviewed as one. The command is surface too: `test/surface/bin.txt` lists the `bin` (`wow-view-engine`) and each subcommand it answers ([`theme-check`](https://wow.ahoo.me/guide/typescript/view-engine-theming#checking-a-theme)).

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
  // Optional: without it the manager offers no “Make shared” / “Make personal”.
  changeAudience?(
    id: string,
    audience: ViewAudience,
    revision: string,
    ctx: WriteContext,
  ): Promise<ViewInstance>;
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

`changeAudience` moves a saved view between personal and shared in place — the id stays, so a dashboard that shows it keeps showing it. It keeps the same two rules, answers a request for the audience the view already has with the view unchanged (no new revision), and refuses to make a view personal while a shared dashboard shows it (`INVALID`, with the dashboards' titles in `boards`, which the engine says in its own words). A store without it simply has no such button in the view manager; one with it gets “Make shared” or “Make personal” on each row the store's `permissions` allow — `instance(id).changeAudience` (absent reads as allowed) and the create permission of the audience the view goes to.

A store implementation can run the port's conformance suite, `test/conformance/viewStoreConformance.ts` in this package's repository (not published): `describeViewStoreConformance({ name, capabilities, connect })` registers the cases every store must pass — lists, visibility, the writes, stale revisions, system views, replays, preferences — and skips those a declared capability rules out.

The package ships `MemoryViewStore` for tests, examples and query-only use. Business applications implement `ViewStore` against their own API with their own fetcher; mapping HTTP status codes to `ViewStoreError.code` belongs there. Authorization, visibility filtering and deduplication are server responsibilities; `permissions` only drives button availability.

### Local storage, until a backend holds the views

For development and single-user hosts, `localStorageSnapshot(key)` keeps a `MemoryViewStore` in the browser's `localStorage`, as one JSON document under `key`. It is one browser's views, not shared ones; the real home of saved views is a backend behind `ViewStore` (phase 6).

```ts
import {
  localStorageSnapshot,
  MemoryViewStore,
} from '@ahoo-wang/wow-view-engine';

const store = new MemoryViewStore({
  snapshot: localStorageSnapshot('my-app:views'),
});
```

- **A write storage refuses fails.** A full quota or blocked storage undoes the write and rejects it with `ViewStoreError` code `UNAVAILABLE`: the environment's `onError` hears it as a `store` failure, and the screen shows a save that did not land, with a retry.
- **Tabs do not overwrite each other.** The store re-reads the document before every write and checks the write's `revision` against it, instance by instance and per definition's preferences. A write merges into what another tab stored — a board saved in one tab survives a reorder in another — and a write another tab has moved past is a `CONFLICT`, as a stale write always is. Another tab's change also reloads the store through the `storage` event.
- A missing or unreadable document starts empty rather than breaking the page, and the next write replaces it.

### Wording and language

The model carries `code` and `params` and no copy, so `/ui` owns the words. `en` gives every issue an English sentence, and `messages` — on `ViewSurface` and on every workbench — is merged over the wording already in force, the same seam for rewording and translation. A `ViewHost` (or a bare `MessagesProvider`) around the application sets it once for every view inside, with the language values show in (`locale`). `zhCN` is a second catalogue, key for key: hand it over whole, or spread it and change what you like (`{ ...zhCN, 'label.filter.apply': '确定' }`).

Values show as their fields say: an enum by its option's label, a `datetime` or a `date` through `Intl.DateTimeFormat`, a date-histogram key as the year, quarter, month or day it starts. A table cell writes a `datetime` short — to the minute, the year only outside the current one (`Sep 17, 9:19 PM`, Chinese `09-17 21:19`) — with the whole time in its `title` and in the record's detail; a field whose seconds matter, an event stream's time, declares `timePrecision: 'second'`. `locale` is the language they show in, the runtime's when left out; it is the same choice as `messages`, made for values rather than words:

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

The message keys and the issue codes are public surface, listed in `test/surface/messages.txt` and `test/surface/issues.txt`: renaming or removing one is a breaking change, like removing an export. Write a rewording of the engine's own keys with `satisfies MessageOverrides`, so a key the engine renames fails your build instead of quietly falling back to the engine's sentence; keys of your own (a definition's `text(key)`) go beside it in a plain `ViewMessages`:

```ts
import type { MessageOverrides } from '@ahoo-wang/wow-view-engine/ui';

const wording = {
  'label.filter.apply': 'Apply filters',
} satisfies MessageOverrides;
```

## Extension points

| Axis        | Mechanism                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| ----------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Field type  | Register a `FieldKind` (operators, validation, `compile` to `FilterExpression`, editor descriptor). Its editor is one of the value controls `/ui` already has (`EDITOR_INPUTS`): there is no renderer registry, and a kind asking for another input is refused rather than guessed at                                                                                                                                                                   |
| Data source | Each entry of `resources` pairs a definition with its source, a Wow query client                                                                                                                                                                                                                                                                                                                                                                        |
| Persistence | Implement `ViewStore`                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| Actions     | Declare them (`actions()`) and bind them (`bind(id, { actions })`), or pass `record.actions` to a workbench; `slots` — `global`, `bulk` and `row` render functions — are the escape hatch beside them. They are code, so they are handed over rather than named in a config, and nothing about them is saved. A page fetches only the fields its view shows, so a field an action reads beyond those is declared in the definition's `record.rowFields` |
| Appearance  | CSS variables and theme files; replace components by composing `/react` hooks                                                                                                                                                                                                                                                                                                                                                                           |
| Maps        | `registerChartMap` from `/ui` offers the map chart a geography — see [Maps](#maps)                                                                                                                                                                                                                                                                                                                                                                      |

Built-in kinds: `string`, `number`, `boolean`, `date`, `datetime`, `enum`, `reference`, `array`, `elementMatch`, `search`, and the ones backed by Wow's metadata filters — `documentId`, `aggregateId`, `tenantId`, `ownerId`, `spaceId`, `deletion`.

## Maps

The map chart draws a map the host registers. **This package ships no map data**: which borders a map shows, and whether it may be published at all, is the law of the place it is shown. A map of China, for one, is published in China only with an approval number (审图号) from the standard map service. The geography is the host's to choose and to answer for.

```ts
import { registerChartMap } from '@ahoo-wang/wow-view-engine/ui';

// Once, at start-up. `load` runs the first time a map chart draws this map,
// and its answer is kept; a failed load is asked for again next time.
const unregister = registerChartMap({
  name: 'world',
  label: 'World',
  load: () => fetch('/maps/world.geo.json').then(response => response.json()),
});
```

- `load` answers a GeoJSON `FeatureCollection`. Each feature's `properties.name` is a region's name.
- A region dimension's values are matched against those names **as their column shows them**. For an `enum` field, that is its label. A region the map has no area for is counted and said over the chart, never guessed at.
- With several maps registered, the chart's data page offers a choice. A chart that names none draws the first map registered. With none registered, the picker greys the map tile.
- Registering a second map under the same name replaces the first. The returned function takes the map back.

## Layering

```text
model → filter → record | analysis | dashboard → runtime → react → ui
store → model
```

`validateDefinition` admits a definition once, where the engine registers it: field names against Wow's query syntax, unique ids free of `:`, capabilities that `default*Config` can actually build from, and every system view through its own kernel. A definition with an error stays in the registry but is refused at the point of use, so a mistake in code surfaces as a reported issue rather than as a `TypeError` when a user opens a view.

Six dependency rules are enforced by architecture tests: `model` imports nothing; `filter` imports only `model`; `record`, `analysis` and `dashboard` import only `model` and `filter`; `runtime` never imports `react` or `ui`; `store` imports only `model`; `react` never imports `ui`. Everything up to `store` is free of React and DOM. Only the non-deprecated `FilterExpression` based Wow APIs are used.

## Out of scope

View-kind plugins, a definition CRUD backend, write-receipt reconciliation or read fences, resource budgets beyond concurrency and page size, SSR preloading, generic region or event buses, cross-page select-all, cell editing and nested dashboards.

## Content Security Policy

The package runs under a strict policy — `script-src 'self'` and `style-src 'self'` with no `'unsafe-inline'` and no `'unsafe-eval'` — with three things to allow:

- **The stylesheet** is a file (`styles.css`, and `themes.css` where used): serve it from an allowed origin rather than inlining it. Nothing the components draw carries a `style` attribute in markup: inline styles go through the DOM's style object, which no policy blocks, and a chart tooltip's colour swatch is an SVG `fill` (a test holds the tooltip's HTML to having no `style=`). Nothing loads a `data:` image either: the cells a board shows while it is built are drawn in the page.
- **The styles the bundled libraries add** while they work, each a `<style>` in `<head>`: the drag-and-drop library while a list is dragged (the column settings, the sort, the view list, a board's filters and tabs — a grabbing cursor, no text selection), the grid's drag library while a board's panel is moved or resized (no text selection), and Base UI while a select's list is open (its scrollbar hidden behind the scroll arrows). Under `style-src 'self'` alone they are blocked, so publish the response's nonce the way Vite does — `<meta property="csp-nonce" nonce="…">` (what Vite's `html.cspNonce` writes; a `content` attribute is read too) — and allow `'nonce-…'` in the policy. The engine hands it to all three. Without the meta everything still works; those few rules are refused, and each reports a violation.
- **Exporting a chart as a PNG** draws the chart's SVG onto a canvas by loading it as an image from a `blob:` URL, so `img-src` must include `blob:`. Without it the PNG is not made and the toolbar says so; the SVG export needs nothing. Neither export evaluates code or writes an inline script.

```
Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self' 'nonce-<new every response>'; img-src 'self' blob:
```

```html
<meta property="csp-nonce" nonce="<the same nonce>" />
```

Two runs hold the package to this policy, and neither allows one violation. Storybook walks the engine itself under it (`typescript/storybook/stories/view-engine/StrictCsp.test.stories.tsx`, in CI): the record workbench with a column dragged, a summary picked from a select, a column widened and a record's detail opened; every chart type with its tooltip; the SVG, PNG and CSV exports; and a board read, filtered from a chart, then built — a panel moved and resized, a tab dragged, an analysis added — and saved. The compensation console runs its end-to-end tests under it (`compensation/dashboard/e2e/csp.spec.ts`): every place walked and a drag made.

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
