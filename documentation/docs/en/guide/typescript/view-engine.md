---
title: View Engine
description: What the unreleased wow-view-engine package does, the facts its design rests on, and the shape of its API.
---

# View Engine

::: warning Not released
`@ahoo-wang/wow-view-engine` has not been published to npm. It is in active development with no compatibility promise: any export may still change shape, and no change carries a compatibility layer. This page describes the target usage so you can evaluate it; do not depend on it in production yet.
:::

The view engine is a data view engine for Wow-based business applications. The application declares in code _how a dataset can be observed_: fields, kinds, operators, available dimensions, and metrics. Users decide in the UI _how to observe it this time_: filters, columns, sorting, dimensions and metrics, charts, and panel composition. The engine compiles that choice into Wow queries, runs them through `@ahoo-wang/wow-client`, renders the result, and saves the views worth keeping so they reopen with one click.

It succeeds `@ahoo-wang/fetcher-viewer`, which stays on Fetcher 5.x and is not documented here.

## The problem it solves

Most pages in a business system are the same page: a list with filters, sorting, and paging, sometimes with a chart. Each business object gets its own copy, and each request such as "add one more filter" or "break this down by warehouse" becomes a code change and a release. The data did not change; only the way of observing it did.

| For | What they get |
|---|---|
| Business users | Adjust scope, organization, and presentation within the declared capabilities, save the views that matter, and reopen them with one click |
| Developers | One definition and one query client per business object instead of one page per list, analysis, and overview; filtering, paging, saving, and conflict handling are implemented once |
| The product | Presentation changes within the supported range become configuration; adding a business object adds a definition, never a branch inside the engine |

It is not a database, a permission system, a general low-code page builder, or a BI modeling tool. Data, aggregation capabilities, and authorization come from the Wow services.

## Three facts

The design fixes three facts and derives the rest from them:

| Fact | Consequence |
|---|---|
| Definitions are code | No definition service or definition versions. A definition change is a deploy; saved views are validated when opened |
| Configs are data | Only `ViewInstance` and personal preferences persist. Consistency is an optimistic revision plus an idempotent `requestId` |
| Runtime state is transient | Drafts, results, paging, and selection live in one open `ViewRuntime` and are never persisted |

```mermaid
flowchart LR
    Definition["ViewDefinition<br>code"] --> Engine["ViewEngine"]
    Store["ViewStore<br>saved ViewInstance"] --> Engine
    Engine --> Runtime["ViewRuntime<br>draft, result, selection"]
    Runtime --> Query["wow-client query<br>paged, cursor, aggregate"]
    Query --> Server["Wow snapshot query API"]
    Runtime --> UI["Workbench or your own UI"]
```

## Views

| View | What the user does |
|---|---|
| Record view | Filter status = pending, sort by creation time, keep only the needed columns, save as "Pending today" |
| Analysis view | Take warehouse as the dimension, count orders and sum amounts as metrics, switch to a bar chart |
| Dashboard | Place several views on one page and constrain them with a global time range |
| Embedded view or dashboard | Show a saved view inside a business page, for example a customer's orders, without the workbench |
| System views | Declare "All", "Pending", and "New this week" in the definition so users open a usable view at once |

## Target usage

The package is not installable yet. Once published, installation will be:

```sh
pnpm add @ahoo-wang/wow-view-engine @ahoo-wang/wow-client
```

`react` and `react-dom` are needed only for the `/react` and `/ui` entries; the root entry runs in Node.

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
    { name: 'amount', label: 'Amount', kind: 'number', summary: ['SUM', 'AVG'] },
    { name: 'createdAt', label: 'Created', kind: 'datetime', sortable: true },
  ],
  // The row key must be sortable: every record query ends its sort on it.
  record: { rowKey: 'id', paging: 'paged', layouts: ['table', 'card'] },
};
```

### 2. Create an engine

<!-- typecheck-context
import type { ViewSource } from '@ahoo-wang/wow-view-engine';
import { orders } from './orders';
declare const queryClients: Record<string, ViewSource>;
-->

```ts
import { MemoryViewStore, ViewEngine } from '@ahoo-wang/wow-view-engine';

const engine = new ViewEngine({
  store: new MemoryViewStore(),
  // Pick<QueryApi, 'paged' | 'cursor' | 'aggregate'> from @ahoo-wang/wow-client
  resources: [{ definition: orders, source: queryClients.orders }],
});
```

`MemoryViewStore` suits tests and examples. A business application implements the `ViewStore` port against its own backend; see the [reference](../../reference/typescript/wow-view-engine/#persistence).

### 3. Render the workbench, or compose your own UI

<!-- typecheck-context
import type { ViewEngine } from '@ahoo-wang/wow-view-engine';
declare const engine: ViewEngine;
-->

```tsx
import '@ahoo-wang/wow-view-engine/styles.css';
import { DataWorkbench } from '@ahoo-wang/wow-view-engine/ui';

export function OrdersPage() {
  return <DataWorkbench engine={engine} definitionId="orders" />;
}
```

Views follow your page's light or dark mode and take their colours from CSS variables. To wear a built-in look, add one import and name it — here `azure`, the Chinese enterprise admin style; `porcelain` (native desktop) and `contrast` (high contrast) are the others:

<!-- typecheck-context
import type { ViewEngine } from '@ahoo-wang/wow-view-engine';
declare const engine: ViewEngine;
-->

```tsx
import '@ahoo-wang/wow-view-engine/styles.css';
import '@ahoo-wang/wow-view-engine/themes/azure.css';
import { DataWorkbench } from '@ahoo-wang/wow-view-engine/ui';

export function OrdersPage() {
  return <DataWorkbench engine={engine} definitionId="orders" preset="azure" />;
}
```

Or put `data-fve-preset="azure"` on `<html>` and every view and popup takes it. Have a brand colour? Put `--fve-brand: <your colour>` on `<html>` beside any preset: the primary and its tints derive from that one colour, every contrast line of that preset held ([I have a brand colour](./view-engine-theming.md#i-have-a-brand-colour)). The catalogue, a "which preset fits my brand" table, host variables, `theme="system"`, pinning and the shadcn bridge are in [Theming the View Engine](./view-engine-theming.md).

A custom layout uses the headless hooks of the `/react` entry, such as `useOpenView`, `useViewRuntime`, `useFilterEditor`, and `useRecordTable`, and renders any markup from them without reaching into engine internals.

The first complete example walks through the record view workbench: filter pending orders, adjust columns and sorting, save a personal view, and reopen it. It is published together with the package.

## Content Security Policy

The engine runs under a strict Content Security Policy: `script-src 'self'` and `style-src 'self'`, with neither `'unsafe-inline'` nor `'unsafe-eval'`. Three things need allowing:

- **The stylesheet is a file.** Serve `styles.css` (and `themes.css` if you use a preset) from an allowed origin instead of inlining it. Nothing the engine draws carries a `style` attribute in its markup: inline styles are written through the DOM's style object, which no policy blocks, and a chart tooltip's colour swatch is an SVG `fill`. Nothing loads a `data:` image either; the cells a board shows while it is built are drawn in the page.
- **The styles the bundled libraries add carry the page's nonce.** Three libraries add a `<style>` to `<head>` while they work: the drag-and-drop library while a list is dragged (a grabbing cursor, no text selection), the grid's drag library while a dashboard panel is moved or resized (no text selection), and Base UI while a select's list is open (the scrollbar hidden behind its scroll arrows). Publish the response's nonce the way Vite's `html.cspNonce` does, as `<meta property="csp-nonce" nonce="…">` (a `content` attribute is read too), and allow `'nonce-…'` in `style-src`; the engine hands it to all three. Without the meta everything still works, but those few rules are refused and each reports a violation.
- **The PNG export loads a `blob:` image.** The chart's SVG is loaded as an image from a `blob:` URL and drawn onto a canvas, so `img-src` must include `blob:`. Without it the PNG is not made and the toolbar says so. The SVG export needs nothing, and neither export evaluates code or writes an inline script.

```text
Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self' 'nonce-<new every response>'; img-src 'self' blob:
```

```html
<meta property="csp-nonce" nonce="<the same nonce>" />
```

Two test runs hold the engine to exactly this policy and fail on a single violation. In Storybook, [`StrictCsp.test.stories.tsx`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/storybook/stories/view-engine/StrictCsp.test.stories.tsx) walks the record workbench (a column dragged, a summary picked from a select, a column widened, a record's detail opened), every chart type with its tooltip, the SVG, PNG and CSV exports, and a dashboard read, filtered from a chart, built (a panel moved and resized, a tab dragged, an analysis added) and saved; it runs in CI with the other stories. The compensation console runs its end-to-end tests under the same policy ([`e2e/csp.spec.ts`](https://github.com/Ahoo-Wang/Wow/blob/main/compensation/dashboard/e2e/csp.spec.ts)).

## Try it in Storybook

Each view runs in [Storybook](/storybook/) against in-memory fixtures, inside a host application shell. Saving, renaming, and deleting write to a fresh in-memory store on every visit. The Storybook is written in Chinese only.

To see what the engine draws first, open the [chart showcase board](/storybook/?path=/docs/view-engine-业务场景-图型全景--docs): one retail dashboard that uses each of the 22 chart types once, every chart titled with the analytical question it answers, on four tabs (trend, mix, spread and relationships, regions and conversion). Pressing a province on the map or the bar chart, or a payment method on the pie, filters the whole board; the docs page's "Show code" holds the board's whole configuration.

| View | Storybook |
|---|---|
| Record view | [Record workbench](/storybook/?path=/docs/view-engine-组件状态-记录工作台--docs) and its [filter editor](/storybook/?path=/docs/view-engine-组件状态-筛选编辑器--docs) |
| Analysis view | [Analysis workbench](/storybook/?path=/docs/view-engine-组件状态-分析工作台--docs) |
| Dashboard | [Dashboard](/storybook/?path=/docs/view-engine-组件状态-仪表盘--docs) |
| Embedded view or dashboard | [EmbeddedView](/storybook/?path=/docs/view-engine-组件状态-embeddedview--docs) and [EmbeddedDashboard](/storybook/?path=/docs/view-engine-组件状态-embeddeddashboard--docs) |
| Themes | [Theme gallery and contrast matrix](/storybook/?path=/docs/view-engine-能力-主题与预设--docs) |

## Where to read more

- [wow-view-engine reference](../../reference/typescript/wow-view-engine/): entries, concepts, persistence port, and extension points.
- [Theming the View Engine](./view-engine-theming.md): presets, host variables, light, dark and system mode, the shadcn bridge, and the contrast an override owes.
- [Accessibility of the View Engine](./view-engine-accessibility.md): the WCAG 2.2 AA conformance statement, the keyboard and screen-reader walkthroughs, and the known gaps.
- [Design documents](https://github.com/Ahoo-Wang/Wow/tree/main/typescript/wow-view-engine/docs/design): the source of truth while the package is unreleased.
- [Package README](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/README.md): the API as it stands today.
