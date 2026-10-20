# Wow View Engine

Released with Wow, from the same tag and with the same version.

**Wow View Engine is a data view engine for Wow-based business applications.** The application declares in code _how a dataset can be observed_: fields, kinds, operators, available dimensions and metrics. Users decide in the UI _how to observe it this time_: filters, columns, sorting, dimensions and metrics, charts, panel composition. The engine compiles that way of observing into Wow queries, runs them, renders the result, and saves the ways worth keeping so they can be reopened with one click.

It is the presentation layer on top of `@ahoo-wang/wow-client` and the successor of `@ahoo-wang/fetcher-viewer`. The guides and the reference live on the [documentation site](https://wow.ahoo.me/guide/typescript/view-engine); this page covers installing the package, the shortest working example and its entries.

> **Compatibility.** A patch release never breaks the public surface: the exports of every entry, including the `ViewStore` port a backend implements; the CSS contract ([what is public](https://wow.ahoo.me/guide/typescript/view-engine-theming#what-is-public)); the message keys and issue codes; and the `wow-view-engine` command. A minor release may break it, and its release notes list every break with the steps to follow; keep the Wow packages on one minor, as [version ranges](https://wow.ahoo.me/guide/typescript/compatibility#version-ranges) explains. Views users saved in an older form keep opening: the engine migrates stored configs on read.

## Install

```bash
pnpm add @ahoo-wang/fetcher \
  @ahoo-wang/fetcher-eventstream @ahoo-wang/wow-client @ahoo-wang/wow-view-engine
# the /react and /ui entries
pnpm add react react-dom
```

The version follows Wow, so a minor release may contain breaking changes: keep the Wow packages on one minor with `save-prefix=~` or `--save-exact`, as [version ranges](https://wow.ahoo.me/guide/typescript/compatibility#version-ranges) explains.

Peer dependencies: `@ahoo-wang/wow-client` (and the fetcher packages it needs); `react` and `react-dom` only for the `/react` and `/ui` entries, `react-router` only for `/react-router`, and `mingo` only for `/testing`. The root entry runs in Node. To keep saved views on a Wow server, add [`@ahoo-wang/wow-view-store`](../wow-view-store/README.md) (its `/v10` entry on a Wow V10 service, with the V10 query routes as data sources). ES modules only; Node `>=22.12.0` or a current browser; TypeScript 6 or later.

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
import type { ViewSource } from '@ahoo-wang/wow-view-engine';
declare const sources: Record<string, ViewSource>;
-->

```ts
import { MemoryViewStore, ViewEngine } from '@ahoo-wang/wow-view-engine';

const engine = new ViewEngine({
  store: new MemoryViewStore(),
  // A ViewSource; viewSourceOf of @ahoo-wang/wow-view-store puts a Wow query route here
  resources: [{ definition: orders, source: sources.orders }],
});
```

Each **resource** pairs a definition with where its rows come from; register every resource once, at the application's start, in **one engine for the application**. To narrow the definitions to what the server admits, give the source a `describe` ([the engine and its resources](https://wow.ahoo.me/guide/typescript/view-engine-getting-started#_6-the-engine-and-its-resources)).

### 3. Render a workbench

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

Around the pages of a real application goes one `ViewHost`, which bridges the engine, your router, language, theme and commands: see [fitting the engine into a host](https://wow.ahoo.me/guide/typescript/view-engine-host).

## Documentation

| To                                                                      | Read                                                                                                                                |
| ----------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------- |
| Decide whether the engine fits, and its model                           | [Overview](https://wow.ahoo.me/guide/typescript/view-engine), [concepts](https://wow.ahoo.me/guide/typescript/view-engine-concepts) |
| Write definitions, from code or from the query descriptor               | [Definitions](https://wow.ahoo.me/guide/typescript/view-engine-definitions)                                                         |
| Fit the engine into a host: `ViewHost`, routes, embeds, messages, tests | [Host](https://wow.ahoo.me/guide/typescript/view-engine-host)                                                                       |
| Offer commands on records                                               | [Actions](https://wow.ahoo.me/guide/typescript/view-engine-actions)                                                                 |
| Keep saved views                                                        | [Storage](https://wow.ahoo.me/guide/typescript/view-engine-storage)                                                                 |
| Theme it, or check a host theme                                         | [Theming](https://wow.ahoo.me/guide/typescript/view-engine-theming)                                                                 |
| Run under a strict Content Security Policy                              | [CSP](https://wow.ahoo.me/guide/typescript/view-engine-csp)                                                                         |
| Accessibility                                                           | [Accessibility](https://wow.ahoo.me/guide/typescript/view-engine-accessibility)                                                     |
| Every export, by entry                                                  | [Reference](https://wow.ahoo.me/reference/typescript/wow-view-engine/)                                                              |

The design — the model, the kernels, the runtime and the UI decisions — is in [docs/design/](https://github.com/Ahoo-Wang/Wow/tree/main/typescript/wow-view-engine/docs/design/), the source of truth for the model.

## Entries

| Entry                        | Exports                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         |
| ---------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `@ahoo-wang/wow-view-engine` | Model types and constants; the four pure kernels (`validate*` / `compile*` / `project*` and the readings beside them); from the runtime, what a host holds and nothing it is built from — `ViewEngine`, `validateDefinition`, the runtime contracts `ViewRuntime`, `RecordViewRuntime`, `DashboardRuntime` and `AnyViewRuntime` with every type their signatures name, `hasResult`, `hasAsked`, `isRecordRuntime`, `actions` with the declared actions' types, the write errors `ViewWriteError` and `ViewCommandError`, `ExportCancelledError`, `RuntimeEnvironment`, `defaultRuntimeEnvironment`, `ViewSource`, `OptionSource`; the `ViewStore` port, `MemoryViewStore` and `localStorageSnapshot`            |
| `/react`                     | Hooks and headless controllers with the types they return: `useViewEngine`, `useOpenView`, `useViewRuntime`, `useViewList`, `useViewManager`, `useWorkbench`, `useLeaveGuard`, `useFilterEditor`, `useRecordTable`, `useAnalysisEditor`, `useAnalysisResult`, `useDashboard`, `useSaveCommands`, `useRecordActions` (the declared actions of one record surface: what each record and the selection are offered, the question or form waiting, the one runner), `RecordActionSlots`, and the two write-outcome types the save commands report (`SettledWrite`, `RecoveredWrite`)                                                                                                                                |
| `/ui`                        | Default components, views and workbenches with their props: `DataWorkbench`, `DashboardWorkbench`, `DashboardEditExtensions`, `useDashboardExtensions`, `EmbeddedView`, `EmbeddedDashboard`, `ViewHost`, `bind`, `useEngine`, `useViewNavigation`, `useColorMode`, `ViewHeader`, `SaveActions`, `ViewManager`, `LeaveDialog`, `EditorBand`, `FilterPanel`, `StatusStrip`, `AppliedBar`, `ResultToolbar`, `RowActions`, `RecordTable`, `RecordCards`, `RecordPagination`, `AnalysisTable`, `AnalysisChart`, `DashboardGrid`, `HeadingPanel`, `MarkdownPanel`, `ImagePanel`, `LinksPanel`, `MessagesProvider`; the catalogues `en` and `zhCN`; the reading of a value, `cellValue`, `cellText` and `displayValue` |
| `/testing`                   | `memorySource` and `matches`: an in-memory `ViewSource` with Wow's query semantics; `resolveNavigation`: the engine's own routing of a way off through your bindings, for your routing tests; `admit`: a host's declarations admitted over its committed descriptors; `actionHarness`: a host's declared actions read by the engine's rules, without a screen — for a host's tests ([testing a host](https://wow.ahoo.me/guide/typescript/view-engine-host#testing))                                                                                                                                                                                                                                            |
| `/react-router`              | `useReactRouter`: React Router as the router port a host hands its view host. React Router is an optional peer, and this is the one entry that loads it                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         |
| `/styles.css`                | The theme. Import it explicitly; no JavaScript entry imports CSS, and nothing in it paints outside the two style boundaries `.fve-root` and `.fve-tokens` (preflight and the base are scoped at build time at no extra weight, and every utility is `fve:`-prefixed, so it never shares a name with yours and your import order does not matter) — bar the preset reset, which only empties the `--fvp-*` layer where a preset is named — all checked by `scripts/verify-package.mjs` on every build.                                                                                                                                                                                                           |
| `/themes.css`                | The presets, optional: only `--fvp-*` assignments keyed by `data-fve-preset` ([Presets](https://wow.ahoo.me/guide/typescript/view-engine-theming#presets)), checked by the same script.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         |
| `/themes/<name>.css`         | One preset alone, for a host that wears one: the same block `themes.css` holds for it ([Presets](https://wow.ahoo.me/guide/typescript/view-engine-theming#presets)).                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| `/shadcn-bridge.css`         | Optional: a host's shadcn tokens read into the `--fvp-*` variables, bar `input`, `ring`, the status and the chart colours and the shadows, and only while no preset is named ([the bridge](https://wow.ahoo.me/guide/typescript/view-engine-theming#the-shadcn-bridge)), checked by the same script.                                                                                                                                                                                                                                                                                                                                                                                                            |

That is the public surface, and it is kept name by name. Each entry writes every name it exports, grouped by the file that declares it; none re-exports a whole module (`test/architecture.test.ts`), so an `export` a file writes for its neighbours never becomes public by accident. Each code entry's complete list — every name, and whether it is a type or a value — is in `test/surface/` (`root.txt`, `react.txt`, `ui.txt`, `testing.txt`, `react-router.txt`): `test/publicSurface.test.ts` fails when an entry exports a name its list does not hold or stops exporting one it does, and `scripts/verify-package.mjs` holds each built JavaScript entry to the same list. A name added to a list or taken off one is a change to the public surface and is reviewed as one. The command is surface too: `test/surface/bin.txt` lists the `bin` (`wow-view-engine`) and each subcommand it answers ([`theme-check`](https://wow.ahoo.me/guide/typescript/view-engine-theming#checking-a-theme)).

## Development

```bash
pnpm --filter @ahoo-wang/wow-view-engine test          # unit tests, coverage and three tsc projects
pnpm --filter @ahoo-wang/wow-view-engine build         # build, then verify the published entries
pnpm --filter @ahoo-wang/wow-view-engine test:package  # entries, DOM-free types, no CSS from JS
pnpm storybook                                             # every state of every surface, under "View Engine"
```

`examples/` in this repository (not in the npm package) holds three consumers
written against the public contracts rather than against the internals:
`quickstart.ts` is the quick start's definition as code, admitted by a test,
`PlainRecordWorkbench.tsx` drives the whole loop with unstyled HTML, and
`FetcherViewStore.ts` implements the `ViewStore` port with
`@ahoo-wang/fetcher` for a backend that is not Wow (a REST API with `If-Match`
revisions) — a Wow server's store is `WowViewStore`.

`@ahoo-wang/fetcher-viewer` is deprecated in favor of this package. The two use different models and APIs.
