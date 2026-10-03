---
title: "Getting Started with the View Engine: Wire One Business Object"
description: Wire the example server's sales orders into the view engine from its Docker image — the query descriptor, defineView, the engine and its resources, ViewHost and one declared action.
---

# Getting Started with the View Engine: Wire One Business Object

This page answers: **how does a React application, starting from zero, wire one Wow business object into the view engine and get a list it can filter, sort, save views of and send commands from?**

It walks through the sales orders of Wow's example server (the `sales-order` aggregate): the server runs in Docker, the application is a Vite + React project. At the end, `/orders` is a whole order list — the system views "To ship" and "All orders", filters, columns, sorting, totals, saving a view of your own — with a "Ship" button on every paid order.

```mermaid
flowchart LR
    Descriptor["query descriptor<br>salesOrderDescriptor.json"] --> Definition["defineView<br>orders.ts"]
    Definition --> Engine["ViewEngine<br>engine.ts"]
    Source["wow-client snapshot queries"] --> Engine
    Engine --> Host["ViewHost<br>App.tsx"]
    Actions["declared action<br>actions.ts"] --> Host
    Host --> Workbench["DataWorkbench"]
```

## 1. Prerequisites

- Node.js **22.12** or later, TypeScript **6** or later, React **19.0** or later (the peer range of the view engine's `/react` and `/ui` entries; see [Compatibility and Versions](./compatibility.md#runtimes-and-peers)).
- A Vite + React + TypeScript project. If you have none, create one from Vite's `react-ts` template: `pnpm create vite orders-console --template react-ts`.
- Docker, to run the example server and its MongoDB.

## 2. Start the example server

The example server's image ships with every Wow release (`ahoowang/wow-example-server`, also on GHCR and Aliyun). The view engine sends snapshot queries, and snapshots must live in MongoDB: the image's own configuration keeps its stores in memory, where a snapshot query answers `QuerySchemaUnavailable`, so the environment below moves events and snapshots to MongoDB.

```bash
docker network create wow-example
docker run -d --name wow-example-mongo --network wow-example \
  -e MONGO_INITDB_ROOT_USERNAME=root -e MONGO_INITDB_ROOT_PASSWORD=root \
  -e GLIBC_TUNABLES=glibc.pthread.rseq=1 \
  mongo:8.3.11
docker run -d --name wow-example-server --network wow-example -p 8080:8080 \
  -e SPRING_AUTOCONFIGURE_EXCLUDE=org.springframework.boot.elasticsearch.autoconfigure.ElasticsearchClientAutoConfiguration,org.springframework.boot.elasticsearch.autoconfigure.ElasticsearchRestClientAutoConfiguration \
  -e 'SPRING_MONGODB_URI=mongodb://root:root@wow-example-mongo:27017/wow_example_db?authSource=admin' \
  -e WOW_EVENTSOURCING_STORE_STORAGE=mongo \
  -e WOW_EVENTSOURCING_SNAPSHOT_STORAGE=mongo \
  ahoowang/wow-example-server:9.2.0
```

- `SPRING_AUTOCONFIGURE_EXCLUDE` replaces the whole exclusion list of the image's configuration: that list excludes MongoDB's auto-configuration too, and this one excludes only Elasticsearch.
- `GLIBC_TUNABLES` sidesteps MongoDB 8 exiting at start on some Linux kernels (SERVER-121912); Wow's CI starts it the same way.

The server is ready when `curl http://localhost:8080/actuator/health/liveness` answers `{"status":"UP"}`. Then write three orders and pay the first two in full:

```bash
order() {
  curl -s http://localhost:8080/tenant/demo/owner/demo/sales-order \
    -H 'Content-Type: application/json' -H 'Command-Wait-Stage: SNAPSHOT' \
    -d "{\"items\":[{\"productId\":\"$1\",\"price\":10,\"quantity\":$2}],
         \"address\":{\"country\":\"China\",\"province\":\"Zhejiang\",
         \"city\":\"$3\",\"district\":\"$3\",\"detail\":\"No. 1\"},
         \"fromCart\":false}" \
    | sed -E 's/.*"aggregateId":"([^"]+)".*/\1/'
}
pay() {
  curl -s http://localhost:8080/tenant/demo/sales-order/$1/pay \
    -H 'Content-Type: application/json' -H 'Command-Wait-Stage: SNAPSHOT' \
    -d "{\"paymentId\":\"pay-$1\",\"amount\":$2}" > /dev/null
}
pay "$(order book 3 Hangzhou)" 30
pay "$(order pen 5 Ningbo)" 50
order cup 2 Wenzhou > /dev/null
```

The orders belong to the tenant `demo`; the example prices every product at 10 and refuses any other price.

## 3. Install

A minor release of the Wow packages may break, so first have pnpm save them with a `~` range, which keeps them on one minor ([version ranges](./compatibility.md#version-ranges)). Add this line to the project's `pnpm-workspace.yaml`, creating the file if the project has none:

```yaml
savePrefix: '~'
```

Then install:

```bash
pnpm add @ahoo-wang/wow-view-engine @ahoo-wang/wow-client \
  @ahoo-wang/fetcher @ahoo-wang/fetcher-decorator @ahoo-wang/fetcher-eventstream \
  react react-dom react-router
pnpm add -D vite @vitejs/plugin-react typescript @types/react @types/react-dom
```

The three `fetcher` packages are wow-client's peers; `react` and `react-dom` are the peers of the `/react` and `/ui` entries, and `react-router` only `/react-router` needs. pnpm leaves the packages Vite's template already installed where they are.

The project must import JSON (step 4's query descriptor) and read the types Vite declares for stylesheet imports. CI compiles this page's code with exactly this `tsconfig.json`, and checks that the install commands above add every package it imports:

<!-- typecheck: file=tsconfig.json -->

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "lib": ["ES2023", "DOM", "DOM.Iterable"],
    "module": "ESNext",
    "moduleResolution": "Bundler",
    "jsx": "react-jsx",
    "types": ["vite/client"],
    "resolveJsonModule": true,
    "strict": true,
    "skipLibCheck": true,
    "noEmit": true
  },
  "include": ["src"]
}
```

The example server sends no CORS headers, so during development Vite forwards `/api` to it and the browser talks only to Vite, on its own origin:

```ts
// vite.config.ts
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        rewrite: path => path.replace(/^\/api/, ''),
      },
    },
  },
});
```

## 4. Save the query descriptor

The server publishes a query descriptor for each aggregate (`GET /sales-order/snapshot/schema`): which fields there are, the type of each, an enumeration's values, and how each field filters, sorts and aggregates. Save it into the project, beside the definition:

```bash
curl -o src/salesOrderDescriptor.json http://localhost:8080/sales-order/snapshot/schema
```

The definition is built from this file when its module loads, and a test builds the same one; at run time the engine reads the server's descriptor again and offers only what this deployment answers today. Save it again after upgrading the server.

::: details What the file holds (an excerpt: the five fields the next step lists)
<!-- typecheck: file=src/salesOrderDescriptor.json -->

```json
{
  "model": "SNAPSHOT",
  "version": "sha256:a30adffc8907fe76ad70e44161b1aaa2e5448d8db1717a22f25653f9dbed6ebc",
  "timeZone": "UTC",
  "record": {
    "identity": "aggregateId",
    "paging": ["LIST", "PAGED", "CURSOR"],
    "defaultScope": "ACTIVE",
    "rootOperators": ["ID", "IDS", "AGGREGATE_ID", "AGGREGATE_IDS", "TENANT_ID", "OWNER_ID", "SPACE_ID", "DELETION", "EXPRESSION"]
  },
  "limits": {
    "maxListSize": 1000,
    "defaultListSize": 100,
    "maxPageSize": 100,
    "maxPageWindow": 10000,
    "maxFilterNodes": 128,
    "maxFilterValues": 1000,
    "maxSortFields": 32,
    "aggregation": {
      "maxGroups": 32,
      "maxMetrics": 64,
      "maxElements": 5,
      "maxLimit": 1000,
      "maxExpressionDepth": 8,
      "maxExpressionNodes": 256
    }
  },
  "analysis": {
    "metrics": ["COUNT", "NUMERIC", "ANY", "DISTINCT_COUNT", "PERCENTILE", "DERIVED", "FIRST", "LAST"],
    "approximate": ["PERCENTILE"],
    "expressions": true,
    "having": {
      "metrics": ["COUNT", "NUMERIC", "DISTINCT_COUNT", "PERCENTILE", "DERIVED"]
    },
    "sort": {"groups": true, "metrics": true},
    "dense": true,
    "dateUnits": ["YEAR", "QUARTER", "MONTH", "WEEK", "DAY", "HOUR", "MINUTE", "SECOND"],
    "dateParts": ["DAY_OF_WEEK", "DAY_OF_MONTH", "HOUR_OF_DAY", "MONTH_OF_YEAR"],
    "dateDiffUnits": ["SECOND", "MINUTE", "HOUR", "DAY"],
    "firstLastOrderBy": "eventTime"
  },
  "fields": [
    {
      "path": "aggregateId",
      "role": "AGGREGATE_ID",
      "types": ["STRING"],
      "kind": "SCALAR",
      "nullable": false,
      "project": true,
      "filter": {
        "operators": ["EQ", "NE", "GT", "GTE", "LT", "LTE", "CONTAINS", "STARTS_WITH", "ENDS_WITH", "IN", "NOT_IN", "BETWEEN", "IS_EMPTY_STRING", "IS_NOT_EMPTY_STRING", "IS_NULL", "IS_NOT_NULL", "EXISTS", "NOT_EXISTS"]
      },
      "sort": {"paged": true, "cursor": true},
      "aggregate": {
        "groups": ["TERMS"],
        "missingKey": true,
        "functions": [],
        "distinctCount": true,
        "percentile": false,
        "any": true,
        "firstLast": true,
        "expressionInput": false,
        "inMetricFilter": true
      },
      "aliases": []
    },
    {
      "path": "firstEventTime",
      "role": "FIRST_EVENT_TIME",
      "types": ["INTEGER"],
      "kind": "SCALAR",
      "nullable": false,
      "semantic": {"type": "TEMPORAL_EPOCH", "timeUnit": "MILLISECONDS"},
      "project": true,
      "filter": {
        "operators": ["EQ", "NE", "GT", "GTE", "LT", "LTE", "IN", "NOT_IN", "BETWEEN", "IS_NULL", "IS_NOT_NULL", "EXISTS", "NOT_EXISTS", "TODAY", "BEFORE_TODAY", "TOMORROW", "THIS_WEEK", "NEXT_WEEK", "LAST_WEEK", "THIS_MONTH", "LAST_MONTH", "RECENT_DAYS", "EARLIER_DAYS", "YESTERDAY", "NEXT_MONTH", "LAST_YEAR", "THIS_YEAR", "NEXT_YEAR", "BEFORE_NOW", "AFTER_NOW"]
      },
      "sort": {"paged": true, "cursor": true},
      "aggregate": {
        "groups": ["TERMS", "HISTOGRAM", "DATE_HISTOGRAM", "DATE_PART"],
        "missingKey": false,
        "functions": ["SUM", "AVG", "MIN", "MAX", "STDDEV", "VARIANCE"],
        "distinctCount": true,
        "percentile": true,
        "any": true,
        "firstLast": true,
        "expressionInput": true,
        "inMetricFilter": true
      },
      "aliases": []
    },
    {
      "path": "state.address.city",
      "types": ["STRING"],
      "kind": "SCALAR",
      "nullable": false,
      "project": true,
      "filter": {
        "operators": ["EQ", "NE", "GT", "GTE", "LT", "LTE", "CONTAINS", "STARTS_WITH", "ENDS_WITH", "IN", "NOT_IN", "BETWEEN", "IS_EMPTY_STRING", "IS_NOT_EMPTY_STRING", "IS_NULL", "IS_NOT_NULL", "EXISTS", "NOT_EXISTS"]
      },
      "sort": {"paged": true, "cursor": true},
      "aggregate": {
        "groups": ["TERMS"],
        "missingKey": true,
        "functions": [],
        "distinctCount": true,
        "percentile": false,
        "any": true,
        "firstLast": true,
        "expressionInput": false,
        "inMetricFilter": true
      },
      "aliases": []
    },
    {
      "path": "state.status",
      "types": ["STRING"],
      "kind": "SCALAR",
      "nullable": false,
      "enum": [
        {"value": "CREATED"},
        {"value": "PAID"},
        {"value": "SHIPPED"},
        {"value": "RECEIVED"}
      ],
      "project": true,
      "filter": {
        "operators": ["EQ", "NE", "GT", "GTE", "LT", "LTE", "CONTAINS", "STARTS_WITH", "ENDS_WITH", "IN", "NOT_IN", "BETWEEN", "IS_EMPTY_STRING", "IS_NOT_EMPTY_STRING", "IS_NULL", "IS_NOT_NULL", "EXISTS", "NOT_EXISTS"]
      },
      "sort": {"paged": true, "cursor": true},
      "aggregate": {
        "groups": ["TERMS"],
        "missingKey": true,
        "functions": [],
        "distinctCount": true,
        "percentile": false,
        "any": true,
        "firstLast": true,
        "expressionInput": false,
        "inMetricFilter": true
      },
      "aliases": []
    },
    {
      "path": "state.totalAmount",
      "types": ["DECIMAL"],
      "kind": "SCALAR",
      "nullable": false,
      "project": true,
      "filter": {
        "operators": ["EQ", "NE", "GT", "GTE", "LT", "LTE", "IN", "NOT_IN", "BETWEEN", "IS_NULL", "IS_NOT_NULL", "EXISTS", "NOT_EXISTS"]
      },
      "sort": {"paged": true, "cursor": true},
      "aggregate": {
        "groups": ["TERMS", "HISTOGRAM"],
        "missingKey": false,
        "functions": ["SUM", "AVG", "MIN", "MAX", "STDDEV", "VARIANCE"],
        "distinctCount": true,
        "percentile": true,
        "any": true,
        "firstLast": true,
        "expressionInput": true,
        "inMetricFilter": true
      },
      "aliases": []
    }
  ],
  "elements": [],
  "dynamic": [],
  "constraints": [
    {"type": "CURSOR_UNIQUE_SORT", "appended": "aggregateId"}
  ]
}
```
:::

## 5. Declare the definition: `defineView`

A definition says how this data **can** be observed. The facts — paths, types, enumeration values — are read from the query descriptor; [`defineView`](../../reference/typescript/wow-view-engine/definitions.md#api-defineView) writes only the choices: which fields to list, in what order, under what names, the tone of each status, and the system views that ship with the definition. A field not listed does not appear; a path or a value the descriptor lacks is reported by admission rather than thrown.

<!-- typecheck: file=orders.ts -->

```ts
// src/orders.ts
import type { QueryModelDescriptor } from '@ahoo-wang/wow-client';
import { defineView, text } from '@ahoo-wang/wow-view-engine';
import descriptor from './salesOrderDescriptor.json';

export const ORDERS = 'sales-orders';

export const ordersDefinition = defineView(
  descriptor as unknown as QueryModelDescriptor,
  {
    id: ORDERS,
    // The key the engine finds the data source under (step 6).
    source: 'sales-order',
    // Words are keys, said in the host's words (step 8).
    title: text('orders.title'),
    // Only the fields listed appear, in this order.
    fields: {
      aggregateId: { label: text('orders.id'), cell: 'copyable' },
      'state.status': {
        label: text('orders.status'),
        cell: 'status',
        options: {
          CREATED: { label: text('orders.created'), tone: 'neutral' },
          PAID: { label: text('orders.paid'), tone: 'warning' },
          SHIPPED: { label: text('orders.shipped'), tone: 'success' },
          RECEIVED: { label: text('orders.received'), tone: 'success' },
        },
      },
      'state.address.city': text('orders.city'),
      'state.totalAmount': { label: text('orders.total'), summary: ['SUM'] },
      firstEventTime: text('orders.placedAt'),
    },
    // Step 7's "Ship" reads every row's status, whether the list shows it
    // or not. A row is not the whole document: it carries the shown
    // columns, the row key, the card layout's fields (when the definition
    // allows cards) and the fields sorts and summaries read; the fields a
    // row must carry besides are named here.
    record: { rowFields: ['state.status'] },
    // System views: deployed with the definition, read-only for everyone,
    // the starting points readers save their own views from.
    views: [
      {
        id: 'to-ship',
        title: text('orders.toShip'),
        config: {
          kind: 'record',
          filter: {
            op: 'and',
            children: [
              { field: 'state.status', operator: 'IN', value: ['PAID'] },
            ],
          },
          filterMode: 'simple',
          refresh: { interval: null },
          sort: [{ field: 'firstEventTime', direction: 'ASC' }],
          pageSize: 20,
          summaries: [{ field: 'state.totalAmount', fn: 'SUM' }],
          layout: 'table',
          table: {
            columns: [
              { field: 'aggregateId' },
              { field: 'state.address.city' },
              { field: 'state.totalAmount' },
              { field: 'firstEventTime' },
            ],
          },
          card: { title: 'aggregateId', fields: ['state.totalAmount'] },
        },
      },
      {
        id: 'all',
        title: text('orders.all'),
        config: {
          kind: 'record',
          filter: { op: 'and', children: [] },
          filterMode: 'simple',
          refresh: { interval: null },
          sort: [{ field: 'firstEventTime', direction: 'DESC' }],
          pageSize: 20,
          summaries: [{ field: 'state.totalAmount', fn: 'SUM' }],
          layout: 'table',
          table: {
            columns: [
              { field: 'aggregateId' },
              { field: 'state.status' },
              { field: 'state.address.city' },
              { field: 'state.totalAmount' },
              { field: 'firstEventTime' },
            ],
          },
          card: { title: 'aggregateId', fields: ['state.status'] },
        },
      },
    ],
  },
);

/**
 * The words of the keys above and of step 7's action. A host with more
 * languages keeps one table per language.
 */
export const ORDER_WORDS = {
  'orders.title': 'Sales orders',
  'orders.id': 'Order',
  'orders.status': 'Status',
  'orders.created': 'Awaiting payment',
  'orders.paid': 'Paid',
  'orders.shipped': 'Shipped',
  'orders.received': 'Received',
  'orders.city': 'City',
  'orders.total': 'Amount',
  'orders.placedAt': 'Placed',
  'orders.toShip': 'To ship',
  'orders.all': 'All orders',
  'orders.ship': 'Ship',
  'orders.shipTitle': 'Ship {count} orders?',
  'orders.notPaid': 'Only a paid order can ship',
};
```

## 6. The engine and its resources

The engine sends only Wow queries (paged, cursor, aggregation), so wow-client's snapshot query client is the data source as it is; `describe` lets the engine read the server's current query descriptor before its first query. **One engine per application**, built once at start: each **resource** pairs a definition with its data source, and every page shares the query cache and the descriptors.

<!-- typecheck: file=engine.ts -->

```ts
// src/engine.ts
import { Fetcher } from '@ahoo-wang/fetcher';
import {
  QueryClientFactory,
  ResourceAttributionPathSpec,
} from '@ahoo-wang/wow-client';
import {
  MemoryViewStore,
  ViewEngine,
  type ViewSource,
} from '@ahoo-wang/wow-view-engine';
import { ordersDefinition } from './orders';

/** The tenant step 2 wrote the orders under. */
export const TENANT = 'demo';

/** The example server, through Vite's `/api` proxy (step 3). */
export const fetcher = new Fetcher({ baseURL: '/api' });

function salesOrderSource(): ViewSource {
  const factory = new QueryClientFactory({
    aggregateName: 'sales-order',
    // Queries `tenant/demo/sales-order/snapshot/...`: this tenant's orders.
    resourceAttribution: ResourceAttributionPathSpec.TENANT,
    urlParams: { path: { tenantId: TENANT } },
    fetcher,
  });
  const snapshots = factory.createSnapshotQueryClient();
  const descriptors = factory.createQueryDescriptorClient();
  return {
    paged: (query, attributes, abort) =>
      snapshots.paged(query, attributes, abort),
    cursor: (query, attributes, abort) =>
      snapshots.cursor(query, attributes, abort),
    aggregate: (query, attributes, abort) =>
      snapshots.aggregate(query, attributes, abort),
    describe: (previous, attributes, abort) =>
      descriptors.describeSnapshot(previous, attributes, abort),
  };
}

export const engine = new ViewEngine({
  resources: [{ definition: ordersDefinition, source: salesOrderSource() }],
  // The views readers save; `MemoryViewStore` forgets them on reload.
  store: new MemoryViewStore(),
});
```

[`MemoryViewStore`](../../reference/typescript/wow-view-engine/store.md#api-MemoryViewStore) keeps this page free of storage. To keep saved views, use [`WowViewStore`](../../reference/typescript/wow-view-store/) from `@ahoo-wang/wow-view-store`, which keeps them on Wow's view store server — the example server already embeds it.

## 7. Declare one action

The commands on a record are declared, not drawn: the host says **what** — the command, when it is available, why not when it is not, whether to ask first; the engine does the **how** — the primary action is a button in the row; over a selection the button reads "Ship 2/3" and lists the ones that cannot, with why; then the confirmation, progress and per-record results, and the view is read again when the run ends.

<!-- typecheck: file=actions.ts -->

```ts
// src/actions.ts
import {
  CommandClient,
  CommandStage,
  waitStrategy,
} from '@ahoo-wang/wow-client';
import { actions, text, type RecordRow } from '@ahoo-wang/wow-view-engine';
import { fetcher, TENANT } from './engine';

const commands = new CommandClient({
  fetcher,
  basePath: `tenant/${TENANT}/sales-order`,
});
// Answer once the snapshot is written: the engine reads the view again as
// soon as `run` resolves, and must see the order shipped.
const headers = waitStrategy({ stage: CommandStage.SNAPSHOT });

const paid = (row: RecordRow) =>
  (row.data.state as { status?: string } | undefined)?.status === 'PAID';

export const orderActions = actions([
  {
    id: 'ship',
    label: text('orders.ship'),
    // A button in the row; other actions go behind the row's "⋯" menu.
    primary: true,
    // `true`, or why not: the reason shows on the disabled button.
    available: row => (paid(row) ? true : text('orders.notPaid')),
    // One order ships at a press; a selection is counted and asked first.
    confirm: { title: text('orders.shipTitle'), ask: 'bulk' },
    // Rejects when the server refuses; the engine reports why on that order.
    run: async row => {
      await commands.send({
        path: `${row.key}/package`,
        method: 'POST',
        headers,
        body: {},
      });
    },
  },
]);
```

Shipping is the example server's `ShipOrder` command (`POST /tenant/{tenantId}/sales-order/{id}/package`). With a command client [generated by wow-generator](./quick-start.md), `run` calls its method instead, and the route and body are typed.

## 8. `ViewHost` and the page

[`ViewHost`](../../reference/typescript/wow-view-engine/host.md#api-ViewHost) is the one layer the host writes around its pages: the data (the engine), the router, the language and its words, the theme, and what each resource does in this host ([`bind`](../../reference/typescript/wow-view-engine/host.md#api-bind): which route it lives on, which actions it carries). The workbench needs only the definition's id.

<!-- typecheck: file=App.tsx -->

```tsx
// src/App.tsx
import '@ahoo-wang/wow-view-engine/styles.css';
import '@ahoo-wang/wow-view-engine/themes/porcelain.css';
import { useReactRouter } from '@ahoo-wang/wow-view-engine/react-router';
import {
  bind,
  DataWorkbench,
  en,
  ViewHost,
} from '@ahoo-wang/wow-view-engine/ui';
import { orderActions } from './actions';
import { engine } from './engine';
import { ORDER_WORDS, ORDERS } from './orders';

/** The engine's own English words, and the definition's keys. */
const MESSAGES = { ...en, ...ORDER_WORDS };

const BINDINGS = [
  bind(ORDERS, {
    // Every way to the orders goes through this route; the open view is
    // in `?view=`.
    route: view =>
      view === null ? '/orders' : `/orders?${new URLSearchParams({ view })}`,
    actions: orderActions,
  }),
];

export function App() {
  return (
    <ViewHost
      engine={engine}
      router={useReactRouter()}
      locale="en"
      messages={MESSAGES}
      bindings={BINDINGS}
      preset="porcelain"
    >
      <main style={{ height: '100vh' }}>
        <DataWorkbench definitionId={ORDERS} />
      </main>
    </ViewHost>
  );
}
```

<!-- typecheck: file=main.tsx -->

```tsx
// src/main.tsx
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router';
import { App } from './App';

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <BrowserRouter>
      <App />
    </BrowserRouter>
  </StrictMode>,
);
```

- **Router**: call `useReactRouter()` inside a React Router router component. With a router the engine keeps the address itself: the workbench's open view is in `?view=`, a record's detail follows `?id=`. Over another router library, write the two members of [`ViewRouter`](../../reference/typescript/wow-view-engine/host.md#api-ViewRouter) (`location` and `go`) yourself.
- **Theme**: `preset="porcelain"` wears one of the engine's built-in looks, whose stylesheet is imported; a host with a shadcn theme writes `theme="host"` and imports `shadcn-bridge.css` instead. See [Theming the View Engine](./view-engine-theming.md).
- **Light and dark**: `colorMode` is `system` by default; the engine follows the system and puts `.dark` on `<html>`.
- **Height**: the workbench fills its container, so the container needs a height.

## 9. Run it

```bash
pnpm dev
```

Open `http://localhost:5173/orders`:

- The first system view, "To ship", opens with the two paid orders, and their amounts totalled under the table.
- Press "Ship" on a row: the command is sent, the snapshot is written, the view is read again, and that order leaves "To ship". Press it on the other, or select several and press "Ship N", and the engine asks first.
- Switch to "All orders": all three are there; the third is "Awaiting payment", and its "Ship" button is disabled with the reason.
- Add a filter (a city, say) and the title says it is not saved; "Save as" keeps it as your own view, and the address's `?view=` follows.

## The full working version

- Storybook's [integration walk-through](/storybook/?path=/docs/view-engine-接入导览--docs) wires an order object in the same five steps, with navigation, words in more than one language, a second action and an in-memory router; the bottom of the page is it running.
- Its source files: [`ordersDefinition.ts`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/storybook/stories/view-engine/integration/ordersDefinition.ts), [`wowSource.ts`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/storybook/stories/view-engine/integration/wowSource.ts), [`ordersEngine.ts`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/storybook/stories/view-engine/integration/ordersEngine.ts), [`OrdersHost.tsx`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/storybook/stories/view-engine/integration/OrdersHost.tsx), [`orderActions.ts`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/storybook/stories/view-engine/integration/orderActions.ts), [`wowCommands.ts`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/storybook/stories/view-engine/integration/wowCommands.ts), [`OrdersPage.tsx`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/storybook/stories/view-engine/integration/OrdersPage.tsx), and [`integration.test.ts`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/storybook/stories/view-engine/integration/integration.test.ts), which holds them to `/testing`'s [`admit`](../../reference/typescript/wow-view-engine/testing.md#api-admit) and [`actionHarness`](../../reference/typescript/wow-view-engine/testing.md#api-actionHarness).
- A real host: the compensation console's [`src/views/`](https://github.com/Ahoo-Wang/Wow/tree/main/compensation/dashboard/src/views).

## Next steps

| Next | Read |
|---|---|
| What the view engine is and the facts it rests on | [View Engine](./view-engine.md) |
| The words this page used: definition, record and analysis views, boards, system / shared / personal views, revision, the store | [View Engine Core Concepts](./view-engine-concepts.md) |
| Finish step 5's definition: text keys, narrowing, [`rowFields`](../../reference/typescript/wow-view-engine/definitions.md#api-RecordCapability), system views and boards, checking it with `admit` | [Writing a Definition](./view-engine-definitions.md) |
| Wiring beyond step 8: `bind`, the router port, navigation, embeds, messages and locale, testing with `/testing` | [Fitting the View Engine into a Host](./view-engine-host.md) |
| Actions beyond step 7: placement, confirmation and forms, bulk, outcomes | [Declared Actions](./view-engine-actions.md) |
| Keep saved views on a Wow server, or write a store of your own | [Where Views Live](./view-engine-storage.md) |
| Run under a strict Content Security Policy | [Content Security Policy for the View Engine](./view-engine-csp.md) |
| Another look, a brand colour, a shadcn theme | [Theming the View Engine](./view-engine-theming.md) |
| Keyboard, screen readers and WCAG 2.2 AA | [Accessibility of the View Engine](./view-engine-accessibility.md) |
| Every public name's signature, and the issue codes | [wow-view-engine reference](../../reference/typescript/wow-view-engine/) |
