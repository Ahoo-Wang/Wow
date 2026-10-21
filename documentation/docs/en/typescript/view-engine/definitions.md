---
title: Define Views
description: Write a view definition from a Wow query model's description — facts, capabilities and choices, words as keys, narrowing, the fields a row must carry, system views and boards — and check it with admit.
---

# Define Views

A definition says how one dataset can be looked at. This page writes one for a sales-order snapshot model, choice by choice, and shows how a test proves the engine will admit it. The concepts behind it are on [View engine concepts](./#definitions).

## 1. Start from the description, and commit it

Every Wow query model describes itself: `GET /{context}/{aggregate}/{model}/query` answers its description, with an `ETag` of its version. The generated query client reads it with `describe()`. Save the description as JSON beside the definition, and commit the two together:

<!-- typecheck: skip — the imported JSON is the description committed beside it -->

```ts
// src/views/salesOrderDescriptor.ts
import type { QueryModelDescriptor } from '@ahoo-wang/wow-client/dsl';
// GET /sales/sales-order/snapshot/query, version sha256:a30adffc…
import snapshot from './salesOrderDescriptor.json';

export const salesOrderDescriptor = snapshot as unknown as QueryModelDescriptor;
```

A committed copy, rather than a request at run time, because:

- **the definition is built synchronously** when its module loads, and a test reads the same file;
- **drift shows in review**: after the service changes, fetch it again over the old copy, and the diff and the admission test show every field and view it touches;
- **the live description is still read at run time**: through the source, the engine narrows to what this deployment grants before the first query.

## 2. Facts, capabilities and choices

[`defineView(descriptor, spec)`](../../reference/typescript/wow-view-engine/definitions.md#api-defineView) separates three kinds of thing:

| Kind | What | From | What the definition may do |
| --- | --- | --- | --- |
| Facts | Paths, value types, enum values, how times are kept, sensitivity, deprecation, an array's entries | The committed description | Choose and name only; a path or value it lacks is an admission error |
| Capabilities | Operators, sorting, aggregation, search, limits | The live description; they change with the store | Left out, they follow the deployment; written, they can only narrow |
| Choices | Which fields, in what order, in what words, cells and tones, narrowing, the row's identity, system views | The definition | All yours |

A field's kind follows from the facts: an enum, or `options` you write, makes `enum`; a time makes `datetime` or `date`; booleans, numbers and strings take their own; an array with entries is `elementMatch`. What cannot be told, such as an object, needs a `kind` from you.

## 3. A definition, choice by choice

A warehouse team clears orders to ship every day; customer service reads sales by city. The definition written for them:

<!-- typecheck: file=salesOrders.ts -->
<!-- typecheck-context
import type { QueryModelDescriptor } from '@ahoo-wang/wow-client/dsl';
declare const salesOrderDescriptor: QueryModelDescriptor;
-->

```ts
// src/views/salesOrders.ts
import { AggregationDateUnit } from '@ahoo-wang/wow-client/dsl';
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

What is left out is a choice too: the tenant, the owner, the district, the balance due. A definition is a **selection**, not a copy of the description: the longer the list, the harder the field picker is to use.

| Question | Choice |
| --- | --- |
| Do readers read, filter or group by it? | List it if so, not otherwise. |
| Is it how people name a record? | `cell: 'copyable'`, `analysis: false`; make it `record.rowKey` when it is unique and sortable. |
| Is it an id you count but never group by? | `analysis: { groups: [] }`: it still feeds a distinct count. |
| Is it free text? | `cell: 'text'`, `sortable: false`, `analysis: false`; `operators: []` when nobody filters by it. |
| Is it a category? | Word every value readers meet (`options`); a `tone` only for a value that is good, bad or waiting. |
| Is it an amount or a count? | Only the totals readers read (`summary`); currency and units in `more.numberFormat`. |
| Is it a moment? | Narrow `analysis.dateUnits` to the units readers think in. |
| Is it an array? | Its entries in `elements`; `elementTitle` names the entry field that says which one it is. |

A long definition groups its fields with `fieldGroups`, each group under a business name.

## 4. Words are keys; narrow for the audience

Every title, label, option and view name is written `text(key)`, with the words in one table per language that the host hands to [`ViewHost`](../../reference/typescript/wow-view-engine/host.md#api-ViewHost). The definition, the configs and the store keep only keys, so switching languages only redraws:

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

The words are the readers': never a path, a constant, a class name or a storage word. A field left without words falls back to the description, then to its path, and admission notes it.

**Narrow for the audience, never to restate the store.** Capabilities you leave out follow the deployment: one definition offers a phrase search on Elasticsearch and none on MongoDB. Narrow what would mislead readers, such as an average of order numbers or a group by an id. Do not repeat what the description already withholds or the server's limits. At run time the engine intersects what you wrote with the live description again.

The engine guards sensitive fields for you: a sensitive field stays out of analysis, and a confidential one takes no comparison and no sort. Yours is only whether to list it at all.

## 5. The fields a row must carry

The row's identity, `record.rowKey`, defaults to the description's identity, `aggregateId` for a snapshot. It must be a listed, sortable field: every record query ends its sort on it, so pages neither repeat nor skip a row.

**The engine queries only what a view shows**: the visible columns, the card's fields, what the sort and summaries read, and the identity. A hidden column is not fetched. So when the host's code reads a field off a row, such as an action that checks the status, that field may be missing in a view that does not show it. List every field the host's code reads in `record.rowFields`:

```ts
import type { RecordCapability } from '@ahoo-wang/wow-view-engine';

export const record: Partial<RecordCapability> = {
  layouts: ['table', 'card'],
  // "Ship" reads the status; the row's identity is always fetched.
  rowFields: ['state.status'],
};
```

### A kanban layout

Where records move through stages, offer a third layout, `'kanban'`: the cards in one lane per option of an enum field, each lane with its count and its own "Load more":

```ts
import type { RecordCapability } from '@ahoo-wang/wow-view-engine';

export const pipeline: Partial<RecordCapability> = {
  // A lane's next cards are its next page: a cursor reads them best.
  paging: 'cursor',
  layouts: ['kanban', 'table', 'card'],
};
```

Each lane is a query of its own, so a cursor reads its next cards best. Moving a card to another lane is a declared action that sets the lane field ([Actions](./actions#kanban)).

## 6. System views and boards

A system view ships with the definition: visible to everyone, read-only, saved as a reader's own before they change it. Write the few views readers open every day, titled by what they hold ("To ship"), sorted by what decides which record is next:

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

- **The id** is unique within the definition and holds no `:`. Its instance id is `system:<definition>:<view>`, which `systemInstanceId` composes for boards and routes.
- **Time is intent.** A period moves with the calendar (`{ type: 'relative', amount: 7, unit: 'day' }`); never write today's date into a config.
- **`timeField: null`** on a system view keeps it out of a board's date filter: a queue is read whole.
- **Name an analysis by its question**, and pick the chart that answers it.

A board is a definition of its own, `kind: 'dashboard'`, with no source; its system views are the system boards. Ask what readers check first, in what order, and which number opens which queue:

<!-- typecheck: file=overview.ts -->

```ts
// src/views/overview.ts
import {
  emptyDashboardConfig,
  inRows,
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
        panels: inRows(
          [
            {
              id: 'today',
              kind: 'heading',
              content: text('overview.today'),
              layout: { w: 24, h: 1 },
            },
          ],
          [
            {
              id: 'total',
              kind: 'view',
              title: text('orders.total'),
              bindings: [],
              layout: { w: 6, h: 2 },
              owned: { definitionId: SALES_ORDERS, config: card(total) },
            },
            {
              id: 'to-ship-count',
              kind: 'view',
              title: text('orders.toShip'),
              bindings: [],
              // What waits now, whenever it was paid.
              ignoresTime: true,
              layout: { w: 6, h: 2 },
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
          ],
          [
            {
              id: 'daily-total',
              kind: 'view',
              title: text('overview.dailyTotal'),
              bindings: [],
              layout: { w: 24, h: 6 },
              owned: { definitionId: SALES_ORDERS, config: dailyTotal },
            },
          ],
          [
            {
              id: 'to-ship',
              kind: 'view',
              title: text('orders.toShip'),
              bindings: [],
              // Read whole: its system view says timeField: null.
              layout: { w: 24, h: 6 },
              instanceId: systemInstanceId(SALES_ORDERS, 'to-ship'),
            },
          ],
        ),
      },
    },
  ],
};
```

- `inRows` lays panels out row by row from their sizes, so adding a panel needs no renumbering.
- **A board's date and a panel's own period are combined**: the board's window reaches each panel through `timeField` and is intersected with the view's own conditions. `ignoresTime` keeps one panel out.
- **`opens`** makes "Open in the workbench" open another view, such as the queue a count counts.
- **A system board shows only system views**, which every tenant can read. An analysis the board owns is not bound by this.

## 7. Check it with `admit`

A path is a string; only the description knows whether it is right. [`admit`](../../reference/typescript/wow-view-engine/testing.md#api-admit), from `/testing`, admits your declarations by the rules the engine registers them by: every key worded, every system view and board valid, every data definition narrowed to its committed description. It returns `[]` when all of it holds. One test admits everything the host registers, in every language it serves:

<!-- typecheck-context
import type { QueryModelDescriptor } from '@ahoo-wang/wow-client/dsl';
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

It needs no browser: run it in a Node environment. Each finding has a `code`, a `path` to the place in the definition and the definition it is about. Fix each at the choice it names, rather than silencing it:

| Finding | Revisit |
| --- | --- |
| `definition.text.unknown` | A key without words in that language |
| `definition.field.undescribed`, `definition.option.undescribed` | A path or value the description does not have: drop it; do not guess another |
| `definition.field.unlabelled` | Give the field the readers' word |
| `definition.field.deprecated` | Move to the replacement path, or keep it and give the reason |
| `capability.field.protected` | A sensitive field is listed: keep it only where readers must see it masked, and assert the finding |
| `definition.record.row-key-*`, `definition.record.row-field-*` | The identity or a `rowFields` entry is not a listed, sortable field that holds a value |
| `definition.view.*`, `filter.*`, `analysis.*`, `dashboard.*` | A system view's or a board's config |

A finding kept on purpose is asserted exactly, with its reason, so a new one still fails the test. What `admit` cannot judge is the readers: whether the fields, words, narrowing and system views are the right ones for them. That is for review.

## Working examples

- The ops console's definitions, words and actions, and the admission test in `parked.test.ts`: [`platform/ops/console/src/views/`](https://github.com/Ahoo-Wang/Wow/tree/v10/platform/ops/console/src/views).
- Storybook's [integration walkthrough](/storybook/?path=/docs/view-engine-接入导览--docs), one order definition from declaration to page.
