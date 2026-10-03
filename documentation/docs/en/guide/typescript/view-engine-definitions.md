---
title: Writing a Definition
description: Write a view definition from the query descriptor — the facts it gives, words as keys, narrowing for the audience, record.rowFields, system views and boards, and a self-check with admit.
---

# Writing a Definition

This page answers: **with the service's query descriptor in hand, how do you write a definition that serves the right readers and that the engine admits cleanly?**

The definition in [Getting Started](./view-engine-getting-started.md) lists five fields and two system views, enough to get the list running. This page carries on with the same sales order (the aggregate `sales-order`) and lays every choice out: which fields to list, what to call them, what to narrow, which views and boards ship with the definition, and how a test proves it holds. Where a definition sits in the model: [Core Concepts](./view-engine-concepts.md#definitions).

## 1. Start from the descriptor, and commit it

A definition's facts come from the service's query descriptor: `GET /{aggregate}/snapshot/schema` for snapshots, `GET /{aggregate}/event/schema` for event streams. Save it as JSON beside the definition, commit the two together, and name its `version` in a comment or in the test:

<!-- typecheck: skip — the imported JSON is the descriptor snapshot committed beside it; Getting Started, step 4, shows an excerpt -->

```ts
// src/views/salesOrderDescriptor.ts
import type { QueryModelDescriptor } from '@ahoo-wang/wow-client';
// GET /sales-order/snapshot/schema, version sha256:a30adffc…
import snapshot from './salesOrderDescriptor.json';

export const salesOrderDescriptor = snapshot as unknown as QueryModelDescriptor;
```

Why commit a snapshot rather than read it at run time:

- **The definition is built synchronously when its module loads.** The page waits on no request, and a test reads the same file, so the result is reproducible.
- **Drift shows in review.** After a server upgrade, fetch it again over the old one: its `version` changes and the change is in the diff, and admission reports every field and view it touches (section 7).
- **The live descriptor is still read at run time.** When the source offers `describe`, the engine reads it from the service before the first query and narrows to what this deployment grants now (section 4); the snapshot only settles the facts.

## 2. Facts, capabilities and choices

[`defineView(descriptor, spec)`](../../reference/typescript/wow-view-engine/definitions.md#api-defineView) splits what the descriptor says into two kinds, and the definition adds a third:

| Kind | What | From | What the definition may do |
|---|---|---|---|
| Facts | Paths, value types, enum values, how time is kept (semantics), sensitivity, deprecation, an array's element structure | The committed snapshot, frozen into the definition | Choose and name only; a path or value the snapshot lacks is an admission error |
| Capabilities | Operators, sorting, aggregation, search, limits | The source's live descriptor; they change with the store | Left out, they follow the deployment; written, they can only narrow |
| Choices | Which fields, in what order, in what words, cells and tones, narrowing, the row's identity, a board's moment, system views | The definition | All yours |

One model has different capabilities on MongoDB and on Elasticsearch (a search inside an array's entries exists on Elasticsearch alone), so capabilities are not frozen from the snapshot: whatever you leave un-narrowed, one definition deployed on either store gets that store's.

A field's kind follows from the facts: an enum, or `options` you write, makes `enum`; an epoch or formatted time semantics makes `datetime`, a date semantics `date`; booleans, numbers and strings take their own; an array with `elements` is `elementMatch`. Whatever cannot be told (an object, a union) needs a `kind` from you, or admission reports `definition.field.kind-unknown`. A moment is kept as a number but means a time, so it groups only by the calendar (`DATE_HISTOGRAM`, `DATE_PART`) and has only an earliest and a latest (`MIN`, `MAX`).

## 3. A definition, choice by choice

The scenario: a warehouse team and a customer-service team work through sales orders every day. The warehouse clears "To ship"; customer service reads sales by city. Here is the definition written for them:

<!-- typecheck: file=salesOrders.ts -->
<!-- typecheck-context
import type { QueryModelDescriptor } from '@ahoo-wang/wow-client';
declare const salesOrderDescriptor: QueryModelDescriptor;
-->

```ts
// src/views/salesOrders.ts
import { AggregationDateUnit } from '@ahoo-wang/wow-client';
import { defineView, text } from '@ahoo-wang/wow-view-engine';
import { SALES_ORDER_VIEWS } from './salesOrderViews';

export const SALES_ORDERS = 'sales-orders';

export const salesOrders = defineView(salesOrderDescriptor, {
  id: SALES_ORDERS,
  // The key the host registers the source under.
  source: 'sales-order',
  title: text('orders.title'),
  // What an analysis counts in: "12 orders", not "12 records".
  recordNoun: text('orders.noun'),
  // A board's date filter means when the order was placed.
  timeField: 'firstEventTime',
  // Only what is listed appears, in this order: the field picker and the
  // default columns follow it.
  fields: {
    // Looked up and copied, never grouped by.
    aggregateId: {
      label: text('orders.id'),
      cell: 'copyable',
      analysis: false,
    },
    'state.status': {
      label: text('orders.status'),
      cell: 'status',
      // Every value a reader meets, worded; a tone only for a value that is
      // good, bad or waiting.
      options: {
        CREATED: { label: text('orders.created'), tone: 'neutral' },
        PAID: { label: text('orders.paid'), tone: 'warning' },
        SHIPPED: { label: text('orders.shipped'), tone: 'success' },
        RECEIVED: { label: text('orders.received'), tone: 'success' },
      },
    },
    'state.address.province': text('orders.province'),
    'state.address.city': text('orders.city'),
    // A total of the amounts means something; an average per page does not.
    'state.totalAmount': {
      label: text('orders.total'),
      summary: ['SUM'],
      more: { numberFormat: { style: 'currency', currency: 'CNY' } },
    },
    'state.paidAmount': {
      label: text('orders.paidAmount'),
      summary: ['SUM'],
      more: { numberFormat: { style: 'currency', currency: 'CNY' } },
    },
    // Readers follow trends by day and by month: wow-client's enum, not strings.
    firstEventTime: {
      label: text('orders.placedAt'),
      analysis: {
        dateUnits: [AggregationDateUnit.DAY, AggregationDateUnit.MONTH],
      },
    },
    // An order's lines, each named by its product.
    'state.items': {
      label: text('orders.items'),
      elementTitle: 'productId',
      elements: {
        productId: text('orders.product'),
        quantity: text('orders.quantity'),
        price: text('orders.price'),
      },
    },
    // Free text read in the detail only: never filtered, sorted or grouped.
    'state.address.detail': {
      label: text('orders.address'),
      cell: 'text',
      operators: [],
      sortable: false,
      analysis: false,
    },
  },
  record: {
    layouts: ['table', 'card'],
    // The host's "Ship" reads each row's status, whether or not the view
    // shows that column (section 5).
    rowFields: ['state.status'],
  },
  // The system views that ship with the definition (section 6).
  views: SALES_ORDER_VIEWS,
});
```

What is left out is a choice too: the tenant, the owner, the country and district, the balance due — neither team uses them, so they are not listed. A definition is a **selection**, not a copy of the descriptor; the longer the list, the harder the picker is to use.

The common choices, each against the question it answers:

| Question | Choice |
|---|---|
| Do readers read, filter or group by it? | List it if so, not otherwise. A descriptor that grows a field does not quietly grow a column |
| Is it how people name a record? | `cell: 'copyable'`, `analysis: false`; make it `record.rowKey` when it is unique, sortable and what people look records up by |
| Is it an id you count but never group by? | `analysis: { groups: [] }`: it still feeds a distinct count |
| Is it free text? | `cell: 'text'`, `sortable: false`, `analysis: false`; `operators: []` when nobody filters by it |
| Is it a category? | Word every value readers can see (`options`), hide one nobody meets with `false`; give a `tone` only to a value that is good, bad or waiting |
| Is it an amount or a count? | Only the totals readers actually read (`summary`), currency and units in `more.numberFormat` |
| Is it a moment? | Narrow `analysis.dateUnits` to the units readers think in; `timePrecision: 'second'` where seconds are what it is read for (an event stream's time) |
| Is it an array? | Its entries in `elements`, keys relative to the entry; `elementTitle` names the entry field that says which one it is, or a cell only says "3 items" |

For a long definition, `fieldGroups: [{ id, label, fields }]` groups the picker and the record detail, each group under a business name.

## 4. Words are keys; narrow for the audience

### Words

Every title, label, option, system view name and metric display name is written `text(key)`, with the words in one table per language, handed to the host ([`ViewHost`](../../reference/typescript/wow-view-engine/host.md#api-ViewHost)'s `messages`, or the engine's `text`). The definition, the configs and the store keep the keys; they are said only where something is shown, so one app has one engine and switching languages is only a redraw — nothing reopens, re-queries or turns "modified". A single-language host may still write plain strings.

<!-- typecheck: file=words.ts -->

```ts
// src/views/words.ts
/** The words of every key, in each language the host serves. `admit` reports a key without words (section 7). */
export const SALES_ORDER_WORDS = {
  'zh-CN': {
    'orders.title': '销售订单',
    'orders.noun': '订单',
    'orders.id': '订单号',
    'orders.status': '状态',
    'orders.created': '待付款',
    'orders.paid': '已付款',
    'orders.shipped': '已发货',
    'orders.received': '已签收',
    'orders.province': '省份',
    'orders.city': '城市',
    'orders.total': '金额',
    'orders.paidAmount': '实付',
    'orders.placedAt': '下单时间',
    'orders.items': '明细',
    'orders.product': '商品',
    'orders.quantity': '件数',
    'orders.price': '单价',
    'orders.address': '详细地址',
    // The system views' and the analysis' words.
    'orders.toShip': '待发货',
    'orders.all': '全部订单',
    'orders.cityTotals': '各城市销售额',
    'orders.count': '订单数',
    'orders.perOrder': '客单价',
    'orders.day': '日期',
    // The board.
    'overview.title': '订单概览',
    'overview.daily': '每日',
    'overview.today': '今天',
    'overview.dailyTotal': '每日销售额',
  },
  en: {
    'orders.title': 'Sales orders',
    'orders.noun': 'order',
    'orders.id': 'Order no.',
    'orders.status': 'Status',
    'orders.created': 'Awaiting payment',
    'orders.paid': 'Paid',
    'orders.shipped': 'Shipped',
    'orders.received': 'Delivered',
    'orders.province': 'Province',
    'orders.city': 'City',
    'orders.total': 'Amount',
    'orders.paidAmount': 'Amount paid',
    'orders.placedAt': 'Placed at',
    'orders.items': 'Items',
    'orders.product': 'Product',
    'orders.quantity': 'Quantity',
    'orders.price': 'Unit price',
    'orders.address': 'Street address',
    'orders.toShip': 'To ship',
    'orders.all': 'All orders',
    'orders.cityTotals': 'Sales by city',
    'orders.count': 'Order count',
    'orders.perOrder': 'Average order value',
    'orders.day': 'Day',
    'overview.title': 'Order overview',
    'overview.daily': 'Daily',
    'overview.today': 'Today',
    'overview.dailyTotal': 'Sales per day',
  },
} as const;
```

The words are the readers', one word per meaning: never a path (`state.totalAmount`), a constant (`PAID`), a class name, or a storage word (document, collection, snapshot, aggregate). A field left without words does not fail on screen; it falls back to the descriptor's `description`, then to the path itself — so readers see a program's word, and admission notes it as `definition.field.unlabelled`. Category values are the same: a value without words shows its description, or the raw constant when there is none.

### Narrowing

Capabilities you leave out follow the deployment: one definition offers a phrase search on Elasticsearch and none on MongoDB, and a limit raised on the server reaches the engine without a deploy. So **narrow for the audience, never to restate the store**:

- Narrow what would mislead readers: an average of order numbers, a sort by a stack trace, a group by an id. The `analysis: false`, `operators: []` and `sortable: false` above are all of this kind.
- Do not restate what the descriptor already withholds, and do not write the server's limits for it (`record.maxWindow`, `analysis.limits`) unless these readers really need a lower one.
- Narrowing past the snapshot (listing an operator this store lacks, say) is a warning, not an error: another store may grant it. At run time the engine intersects what you wrote with the live descriptor again; only a capability you **wrote** that this store takes away is reported, as a `capability.*` finding.

The engine guards two kinds of field for you: a field with `sensitivity` stays out of analysis (no dimension, metric input, expression operand or metric filter), and a confidential one takes no comparison and no sort. Yours is only whether it appears at all — list it only where readers must see the masked value, and add no derived field or condition that would recover what the mask hides. A deprecated path is replaced by the one its message names; to keep one, write `deprecated: { message: 'why' }`, or it keeps warning.

## 5. Records: the row's identity and `rowFields`

`record.rowKey` is each row's identity, by default the descriptor's `identity` (`aggregateId` for a Wow snapshot). It must be a listed, sortable field: every record query ends on it, so pages neither repeat nor skip a row. You may switch it to the business number people look orders up by; commands still address the aggregate id, though, so list the aggregate id too and put it in [`rowFields`](../../reference/typescript/wow-view-engine/definitions.md#api-RecordCapability).

**The engine queries only what a view shows.** A page asks for the visible columns, the card's fields, the fields its sort and summaries read, and the row's identity. A hidden column is not fetched, nor exported — which keeps every page of a wide table light. The price: when the host's code (a declared action's `available`, a bulk action, a custom cell) reads a field this view does not show, the row simply does not have it. "Ship" reads `state.status` to decide whether an order can ship; a reader hides the status column, or opens a system view that never showed it, and the check reads `undefined` — the button stays disabled.

So every field the host's code reads off a row, shown or not, goes in `record.rowFields`:

```ts
import type { RecordCapability } from '@ahoo-wang/wow-view-engine';

export const record: Partial<RecordCapability> = {
  layouts: ['table', 'card'],
  // "Ship" reads the status; the row's identity is always fetched.
  rowFields: ['state.status'],
};
```

Each entry of `rowFields` must be a field the definition lists (else `definition.record.row-field-unknown`), and one that holds a value, not a field-less kind such as a search box (`definition.record.row-field-not-a-path`).

## 6. System views and boards

### System views: the few readers open every day

A system view is a starting point that ships with the definition: visible to everyone, read-only, saved by readers as their own before they change it. Write the few these readers open every day, not every view they could build: one per queue, titled by what it holds ("To ship", not "Order list 2"), sorted by what decides which one is taken next, its columns in reading order, a total only where it means something.

A small helper keeps the views of one definition complete and alike:

<!-- typecheck: file=salesOrderViews.ts -->

```ts
// src/views/salesOrderViews.ts
import {
  text,
  type AnalysisViewConfig,
  type FilterNode,
  type RecordViewConfig,
  type SystemView,
} from '@ahoo-wang/wow-view-engine';

const COLUMNS = [
  'aggregateId',
  'state.status',
  'state.address.city',
  'state.totalAmount',
  'firstEventTime',
];

function queue(
  filter: FilterNode[],
  overrides: Partial<RecordViewConfig> = {},
): RecordViewConfig {
  return {
    kind: 'record',
    filter: { op: 'and', children: filter },
    filterMode: 'simple',
    refresh: { interval: null },
    sort: [{ field: 'firstEventTime', direction: 'DESC' }],
    pageSize: 20,
    summaries: [{ field: 'state.totalAmount', fn: 'SUM' }],
    layout: 'table',
    table: { columns: COLUMNS.map(field => ({ field })) },
    card: {
      title: 'aggregateId',
      fields: ['state.status', 'state.totalAmount'],
    },
    ...overrides,
  };
}

/** Sales by city: the last 30 days, resolved against the calendar on every run. */
const cityTotals: AnalysisViewConfig = {
  kind: 'analysis',
  filter: {
    op: 'and',
    children: [
      {
        field: 'firstEventTime',
        operator: 'BETWEEN',
        value: { type: 'relative', amount: 30, unit: 'day' },
      },
    ],
  },
  filterMode: 'simple',
  refresh: { interval: null },
  groups: [
    {
      type: 'TERMS',
      field: 'state.address.city',
      alias: 'city',
      label: text('orders.city'),
    },
  ],
  metrics: [
    { type: 'COUNT', alias: 'orders', label: text('orders.count') },
    {
      type: 'NUMERIC',
      alias: 'total',
      function: 'SUM',
      expression: { type: 'FIELD', field: 'state.totalAmount' },
      label: text('orders.total'),
    },
    {
      // A ratio is a metric over metrics, not a field.
      type: 'DERIVED',
      alias: 'perOrder',
      label: text('orders.perOrder'),
      expression: {
        type: 'BINARY',
        operator: 'DIVIDE',
        left: { type: 'METRIC_REF', metric: 'total' },
        right: { type: 'METRIC_REF', metric: 'orders' },
      },
      format: { style: 'currency', currency: 'CNY' },
    },
  ],
  sort: [{ alias: 'total', direction: 'DESC' }],
  limit: 20,
  layout: 'chart',
  table: { columns: [] },
  chart: {
    type: 'bar',
    cartesian: { x: 'city', series: [{ metric: 'total' }] },
  },
};

export const SALES_ORDER_VIEWS: SystemView[] = [
  {
    id: 'to-ship',
    title: text('orders.toShip'),
    // On the system view, not in its config: a queue is read whole, and a
    // board's date filter never reaches it.
    timeField: null,
    // The earliest paid ships first.
    config: queue(
      [{ field: 'state.status', operator: 'IN', value: ['PAID'] }],
      { sort: [{ field: 'firstEventTime', direction: 'ASC' }] },
    ),
  },
  { id: 'all', title: text('orders.all'), config: queue([]) },
  { id: 'city-totals', title: text('orders.cityTotals'), config: cityTotals },
];
```

- **The id** is unique within the definition and holds no `:`. The engine makes `system:<definition id>:<view id>` its instance id; `systemInstanceId('sales-orders', 'to-ship')` composes the same value, which boards and routes refer to it by.
- **Time is intent.** A period is a `BETWEEN` that moves with the calendar (`{ type: 'preset', preset: 'yesterday' }`, `{ type: 'relative', amount: 7, unit: 'day' }`); never write today's date into a config. A category offers `IN` and `NOT_IN`, not `EQ`.
- **`timeField`** goes on the [`SystemView`](../../reference/typescript/wow-view-engine/definitions.md#api-SystemView): another moment, or `null` for a view read whole, out of reach of a board's date filter.
- **Name an analysis by its question** ("Sales by city"), give every dimension and metric a display name, sort by the metric that answers the question, and pick the chart that answers it rather than the most striking one.

### Boards: the page readers glance at first

A board is a definition of its own (`kind: 'dashboard'`, no source), and its system views are the system boards. Ask what readers check first thing, in what order, and which number opens which queue.

<!-- typecheck: file=overview.ts -->

```ts
// src/views/overview.ts
import {
  emptyDashboardConfig,
  systemInstanceId,
  text,
  type AnalysisViewConfig,
  type DashboardDefinition,
} from '@ahoo-wang/wow-view-engine';
import { SALES_ORDERS } from './salesOrders';

/** One number: no dimension, one row over everything in range, so a limit of 1. */
function card(
  metric: AnalysisViewConfig['metrics'][number],
  filter: AnalysisViewConfig['filter']['children'] = [],
): AnalysisViewConfig {
  return {
    kind: 'analysis',
    filter: { op: 'and', children: filter },
    filterMode: 'simple',
    refresh: { interval: null },
    groups: [],
    metrics: [metric],
    sort: [],
    limit: 1,
    layout: 'chart',
    table: { columns: [] },
    chart: { type: 'metric', metric: { metric: metric.alias } },
  };
}

const total = {
  type: 'NUMERIC',
  alias: 'total',
  function: 'SUM',
  expression: { type: 'FIELD', field: 'state.totalAmount' },
  label: text('orders.total'),
} as const;

const dailyTotal: AnalysisViewConfig = {
  ...card(total),
  groups: [
    {
      type: 'DATE_HISTOGRAM',
      field: 'firstEventTime',
      alias: 'day',
      unit: 'DAY',
      label: text('orders.day'),
    },
  ],
  sort: [{ alias: 'day', direction: 'ASC' }],
  // A row a day: room for the longest window a reader picks.
  limit: 92,
  chart: { type: 'line', cartesian: { x: 'day', series: [{ metric: 'total' }] } },
};

export const overview: DashboardDefinition = {
  id: 'overview',
  title: text('overview.title'),
  kind: 'dashboard',
  views: [
    {
      id: 'daily',
      title: text('overview.daily'),
      config: {
        ...emptyDashboardConfig(),
        // The board's one date filter: it reaches each panel through that
        // definition's timeField.
        fields: [
          {
            name: 'window',
            label: text('orders.placedAt'),
            kind: 'datetime',
            default: { type: 'preset', preset: 'today' },
            required: true,
          },
        ],
        panels: [
          {
            id: 'today',
            kind: 'heading',
            content: text('overview.today'),
            layout: { x: 0, y: 0, w: 24, h: 1 },
          },
          {
            id: 'total',
            kind: 'view',
            title: text('orders.total'),
            bindings: [],
            layout: { x: 0, y: 1, w: 6, h: 2 },
            owned: { definitionId: SALES_ORDERS, config: card(total) },
          },
          {
            id: 'to-ship-count',
            kind: 'view',
            title: text('orders.toShip'),
            bindings: [],
            // What waits now, whenever it was paid.
            ignoresTime: true,
            layout: { x: 6, y: 1, w: 6, h: 2 },
            owned: {
              definitionId: SALES_ORDERS,
              config: card(
                { type: 'COUNT', alias: 'orders', label: text('orders.count') },
                [{ field: 'state.status', operator: 'IN', value: ['PAID'] }],
              ),
            },
            // "Open in workbench" opens the queue itself, not a count.
            opens: systemInstanceId(SALES_ORDERS, 'to-ship'),
          },
          {
            id: 'daily-total',
            kind: 'view',
            title: text('overview.dailyTotal'),
            bindings: [],
            layout: { x: 0, y: 3, w: 24, h: 6 },
            owned: { definitionId: SALES_ORDERS, config: dailyTotal },
          },
          {
            id: 'to-ship',
            kind: 'view',
            title: text('orders.toShip'),
            bindings: [],
            // Read whole: its system view says timeField: null.
            layout: { x: 0, y: 9, w: 24, h: 6 },
            instanceId: systemInstanceId(SALES_ORDERS, 'to-ship'),
          },
        ],
      },
    },
  ],
};
```

- The grid has 24 columns; panels in one row share a `y`.
- **The default date** is what readers check first: a daily report opens on "yesterday" (a whole day, settled), a live board on "today" or a short relative window, and a board of piles needs no date filter at all.
- **A board's date and a panel's own period are ANDed.** The board's window reaches every panel through `timeField` and is intersected with the panel view's own conditions: a view with its own "last 30 days" placed on a 7-day board reads the overlap. Leave the period off a view made for a board. A view read whole everywhere says `timeField: null` on its system view; one panel that should not follow says `ignoresTime: true` on the panel.
- **`opens`** makes "Open in workbench" open another view of the same definition: use it where a card counts what a queue lists.
- **A system board references only system views**: system views are visible to every tenant, a shared view belongs to one ([Core Concepts](./view-engine-concepts.md#where-system-views-come-from)). An analysis the board owns is not bound by this.

## 7. Self-check with `admit`

A definition has no compile-time check of its paths: a path is a string, and only the descriptor knows whether it is right. [`admit`](../../reference/typescript/wow-view-engine/testing.md#api-admit) from `/testing` adds that check: it admits your declarations by the rules the engine registers them by — every key said in the words given, each definition's own rules and every system view config, a board's references to the other definitions, and each data definition narrowed to the committed snapshot of its `source`. When all of it holds it returns `[]`.

One test admits everything the host registers, in every language the host serves:

<!-- typecheck-context
import type { QueryModelDescriptor } from '@ahoo-wang/wow-client';
declare const salesOrderDescriptor: QueryModelDescriptor;
declare const describe: (name: string, body: () => void) => void;
declare const it: { each<T>(cases: readonly T[]): (name: string, body: (value: T) => void) => void };
declare function expect(value: unknown): { toEqual(expected: unknown): void };
-->

```ts
// src/views/admit.test.ts
import { admit } from '@ahoo-wang/wow-view-engine/testing';
import { overview } from './overview';
import { salesOrders } from './salesOrders';
import { SALES_ORDER_WORDS } from './words';

describe('the order views', () => {
  it.each(['zh-CN', 'en'] as const)('are admitted in %s', locale => {
    const words: Readonly<Record<string, string>> = SALES_ORDER_WORDS[locale];
    expect(
      admit(
        // A board is admitted with the data definitions it reads, so its
        // references are checked.
        [salesOrders, overview],
        // By each data definition's source: the committed snapshots.
        { 'sales-order': salesOrderDescriptor },
        { text: key => words[key] },
      ),
    ).toEqual([]);
  });
});
```

Neither `admit` nor the definitions need a browser; run them in a Node environment. Where the host's Vitest is a browser or jsdom project, give the definitions a config of their own with `environment: 'node'`.

Every finding carries a `code`, `params`, a `path` to the place in the definition, and the `definition` it is about; `/ui`'s `en` and `zhCN` catalogues word every code. Fix each at the choice it names; do not silence it by widening `text`, leaving out the descriptor or filtering the result:

| Finding | Revisit |
|---|---|
| `definition.text.unknown` | A key without words in that language: add them, or fix the key's spelling |
| `definition.field.undescribed`, `definition.option.undescribed` | A path or value the descriptor does not list. Do not guess another path; drop it, and say what is missing and where it would come from (a read-model field, a model declaration) |
| `definition.field.unlabelled` | Give the field the readers' word |
| `definition.field.deprecated` | Move to the replacement path, or keep it and give the reason |
| `capability.field.protected` | A sensitive or confidential field is listed: keep it only where readers must see it masked, and assert the finding in the test |
| `definition.field.*-wider`, `capability.field.*-narrowed` | You narrowed to a capability this store lacks: remove it, or keep it only for another deployment that has it, and say so |
| `definition.record.row-key-*`, `definition.record.row-field-*` | The row's identity or a `rowFields` entry is not a listed, sortable field that holds a value |
| `definition.descriptor.missing` | `admit` was not given the snapshot for that `source` |
| `definition.view.*`, `filter.*`, `analysis.*`, `dashboard.*`, `record.*` | A system view's or a board's config: the field, operator, alias or panel it names |

A finding kept on purpose is asserted exactly as it is, with its reason beside it, so a new finding still fails the test.

What `admit` cannot judge is the readers: whether each listed field is one they read, filter or group by, in their order; whether the words are theirs, in every language; whether each narrowing protects readers or restates the store; whether the system views and boards open on what they do first. Those are for review.

## The full working version

- Storybook's [integration walk-through](/storybook/?path=/docs/view-engine-接入导览--docs): one order definition from declaration to page; [narrowing by deployment](/storybook/?path=/docs/view-engine-能力-随部署收窄--docs) pairs one definition with two stores' descriptors to show the capabilities following them.
- Its source: [`ordersDefinition.ts`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/storybook/stories/view-engine/integration/ordersDefinition.ts), [`ordersDescriptor.json`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/storybook/stories/view-engine/integration/ordersDescriptor.json), and [`integration.test.ts`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/storybook/stories/view-engine/integration/integration.test.ts), which checks them with `admit`.
- A real host: the compensation console's [`src/views/`](https://github.com/Ahoo-Wang/Wow/tree/main/compensation/dashboard/src/views), with its definitions, the words per language and the admission test.
- Design documents (in Chinese): [how `defineView` merges](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/docs/design/host-integration.md), [adopting the service's capability descriptor](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/docs/design/capabilities.md).
- For an agent writing definitions this way: the [`wow-view-definition`](https://github.com/Ahoo-Wang/Wow/blob/main/skills/wow-view-definition/SKILL.md) Skill.

## Next

| Next | Read |
|---|---|
| Where definitions, views, boards and the store sit in the model | [Core Concepts](./view-engine-concepts.md) |
| Wire the definition into an engine, `ViewHost` and one action | [Getting Started with the View Engine](./view-engine-getting-started.md) |
| Another look, or the host's own theme | [View Engine Theming](./view-engine-theming.md) |
