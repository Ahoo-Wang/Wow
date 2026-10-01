# System views, analyses and boards

A system view is a starting point the definition ships: visible to everyone, read-only, saved as a reader's own. Write the few the audience opens every day, not every view they could build. The config shapes are in [model.md](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/docs/design/model.md) and [model-shapes.md](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/docs/design/model-shapes.md); `admit` checks every config against its definition, so this page is about which views to write and how they read.

## Record views: the queues people work

Ask: what does the audience clear, in what order, and what do they need on the row to act? One view per queue, titled by what it holds (「待发货」, not 「订单列表 2」), sorted by what decides the next one to take, with columns in the order they are read and a footer total only where it means something.

A small helper keeps every view of a definition complete and alike:

<!-- typecheck: file=orderViews.ts -->

```ts
import {
  text,
  type FilterNode,
  type RecordViewConfig,
  type SystemView,
} from '@ahoo-wang/wow-view-engine';

const COLUMNS = [
  'state.orderNo',
  'state.status',
  'state.paidAt',
  'state.channel',
  'state.warehouse',
  'state.paidAmount',
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
    sort: [{ field: 'state.paidAt', direction: 'DESC' }],
    pageSize: 20,
    layout: 'table',
    summaries: [{ field: 'state.paidAmount', fn: 'SUM' }],
    table: { columns: COLUMNS.map(field => ({ field })) },
    card: {
      title: 'state.orderNo',
      fields: ['state.status', 'state.paidAmount', 'state.paidAt'],
    },
    ...overrides,
  };
}

export const ORDER_QUEUES: SystemView[] = [
  {
    // The earliest paid ships first.
    id: 'to-ship',
    title: text('orders.toShip'),
    // On the system view, not in its config: a queue is read whole, so a
    // board's date filter never reaches it, whoever places it.
    timeField: null,
    config: queue(
      [{ field: 'state.status', operator: 'IN', value: ['PAID'] }],
      { sort: [{ field: 'state.paidAt', direction: 'ASC' }] },
    ),
  },
  { id: 'all', title: text('orders.all'), config: queue([]) },
];
```

A system view's `timeField` (on the `SystemView`, beside `id` and `title`, never inside `config`) overrides the definition's for that view: another moment, or `null` for a view read whole. Pass them as the definition's `views` (`views: [...ORDER_QUEUES, CHANNEL_SALES]`). A category offers `IN` and `NOT_IN`, not `EQ`. A period is a `BETWEEN` whose value moves with the calendar, and `BEFORE_NOW` / `AFTER_NOW` compare with the service's clock: each keeps a saved view current, so never write today's date into a config.

```ts
import type { FilterNode } from '@ahoo-wang/wow-view-engine';

// A calendar preset: 'today', 'yesterday', 'lastWeek', 'monthToDate', …
export const PAID_YESTERDAY: FilterNode = {
  field: 'state.paidAt',
  operator: 'BETWEEN',
  value: { type: 'preset', preset: 'yesterday' },
};

// A window from now: the past unless `direction: 'future'` (what is due).
export const PAID_LAST_7_DAYS: FilterNode = {
  field: 'state.paidAt',
  operator: 'BETWEEN',
  value: { type: 'relative', amount: 7, unit: 'day' },
};
```

## Analysis views: the questions people ask

Ask: what is measured (a metric), by what (a dimension), over which period, and how is the answer read (a table, a trend, a ranking)? Name the view by the question in the audience's words (「各渠道实付」), give each dimension and metric a display name, sort by what answers the question, and keep the limit to what is read.

<!-- typecheck-context
import { text } from '@ahoo-wang/wow-view-engine';
-->

```ts
import type { AnalysisViewConfig, SystemView } from '@ahoo-wang/wow-view-engine';

const byChannel: AnalysisViewConfig = {
  kind: 'analysis',
  filter: {
    op: 'and',
    children: [
      {
        // The last 30 days, resolved on every run: never a date written in.
        field: 'state.paidAt',
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
      field: 'state.channel',
      alias: 'channel',
      label: text('orders.channel'),
    },
  ],
  metrics: [
    { type: 'COUNT', alias: 'orders', label: text('orders.count') },
    {
      type: 'NUMERIC',
      alias: 'paid',
      function: 'SUM',
      expression: { type: 'FIELD', field: 'state.paidAmount' },
      label: text('orders.paidAmount'),
    },
    {
      // A ratio is a metric over metrics, not a field.
      type: 'DERIVED',
      alias: 'perOrder',
      label: text('orders.perOrder'),
      expression: {
        type: 'BINARY',
        operator: 'DIVIDE',
        left: { type: 'METRIC_REF', metric: 'paid' },
        right: { type: 'METRIC_REF', metric: 'orders' },
      },
      format: { style: 'currency', currency: 'CNY' },
    },
  ],
  sort: [{ alias: 'paid', direction: 'DESC' }],
  limit: 20,
  layout: 'chart',
  table: { columns: [] },
  chart: {
    type: 'bar',
    cartesian: { x: 'channel', series: [{ metric: 'paid' }] },
  },
};

export const CHANNEL_SALES: SystemView = {
  id: 'channel-sales',
  title: text('orders.channelSales'),
  config: byChannel,
};
```

- A trend groups a moment by `DATE_HISTOGRAM` with the unit the audience thinks in, written as the string (`unit: 'DAY'`; the board below has one); the period is a relative filter, so it moves with the calendar.
- Line items are an `elements` expansion of the array, its fields relative to the entry.
- Chart types and their specs are in [ui/analysis.md](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/docs/design/ui/analysis.md); choose the one that answers the question, not the most striking.

## Boards: what people glance at

A board is its own definition (`kind: 'dashboard'`, no source) whose system boards hold panels: a saved view (`instanceId`, a system view's is `systemInstanceId(definitionId, viewId)`) or an analysis the board owns (`owned: { definitionId, config }`), plus headings and text. Ask what someone checks first thing, in what order, and which number opens which queue.

<!-- typecheck: file=overview.ts -->

```ts
import {
  emptyDashboardConfig,
  systemInstanceId,
  text,
  type AnalysisViewConfig,
  type DashboardDefinition,
} from '@ahoo-wang/wow-view-engine';

/** One number over the orders `filter` keeps: a metric card. */
function card(
  metric: AnalysisViewConfig['metrics'][number],
  filter: AnalysisViewConfig['filter']['children'] = [],
): AnalysisViewConfig {
  return {
    kind: 'analysis',
    filter: { op: 'and', children: filter },
    filterMode: 'simple',
    refresh: { interval: null },
    // No dimension: one row over everything in range, so a limit of 1.
    groups: [],
    metrics: [metric],
    sort: [],
    limit: 1,
    layout: 'chart',
    table: { columns: [] },
    chart: { type: 'metric', metric: { metric: metric.alias } },
  };
}

const dailyPaid: AnalysisViewConfig = {
  ...card({
    type: 'NUMERIC',
    alias: 'paid',
    function: 'SUM',
    expression: { type: 'FIELD', field: 'state.paidAmount' },
    label: text('orders.paidAmount'),
  }),
  groups: [
    {
      type: 'DATE_HISTOGRAM',
      field: 'state.paidAt',
      alias: 'day',
      unit: 'DAY',
      label: text('orders.day'),
    },
  ],
  sort: [{ alias: 'day', direction: 'ASC' }],
  // One row a day: room for the longest window a reader picks.
  limit: 92,
  chart: { type: 'line', cartesian: { x: 'day', series: [{ metric: 'paid' }] } },
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
        // The board's one date filter: it reaches each panel through its
        // definition's `timeField`.
        fields: [
          {
            name: 'window',
            label: text('orders.paidAt'),
            kind: 'datetime',
            default: { type: 'relative', amount: 7, unit: 'day' },
            required: true,
          },
        ],
        panels: [
          {
            id: 'today',
            kind: 'heading',
            // Words a reader sees are keys here too; admit checks them.
            content: text('overview.today'),
            layout: { x: 0, y: 0, w: 24, h: 1 },
          },
          {
            id: 'paid',
            kind: 'view',
            title: text('orders.paidAmount'),
            bindings: [],
            layout: { x: 0, y: 1, w: 6, h: 2 },
            owned: {
              definitionId: 'orders',
              config: card({
                type: 'NUMERIC',
                alias: 'paid',
                function: 'SUM',
                expression: { type: 'FIELD', field: 'state.paidAmount' },
                label: text('orders.paidAmount'),
              }),
            },
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
              definitionId: 'orders',
              config: card(
                { type: 'COUNT', alias: 'orders', label: text('orders.count') },
                [{ field: 'state.status', operator: 'IN', value: ['PAID'] }],
              ),
            },
            // 「在工作台中打开」 opens the queue itself, not a count.
            opens: systemInstanceId('orders', 'to-ship'),
          },
          {
            id: 'daily-paid',
            kind: 'view',
            title: text('overview.dailyPaid'),
            bindings: [],
            layout: { x: 0, y: 3, w: 24, h: 6 },
            owned: { definitionId: 'orders', config: dailyPaid },
          },
          {
            id: 'to-ship',
            kind: 'view',
            title: text('orders.toShip'),
            bindings: [],
            // Read whole by its system view's `timeField: null`.
            layout: { x: 0, y: 9, w: 24, h: 6 },
            instanceId: systemInstanceId('orders', 'to-ship'),
          },
        ],
      },
    },
  ],
};
```

- The grid has 24 columns; panels in one row share a `y`.
- **The board's default date** is what its reader checks first: a daily report opens on `{ type: 'preset', preset: 'yesterday' }` (a whole day, settled), a live board on `'today'` or a short relative window, and a board of piles needs no date filter at all.
- **The board's date filter and a panel's own period:** the board's window reaches every panel whose definition has a `timeField` and is **ANDed** with the panel view's own conditions. A panel view with its own period (`CHANNEL_SALES`'s last 30 days) placed on a 7-day board reads the overlap, the last 7 days. Leave the period off a view made for a board. A view read whole everywhere says `timeField: null` on its system view; one panel that should not follow the board says `ignoresTime: true`, as the count card above does.
- `opens` names the view 「在工作台中打开」 opens instead of the panel's own, a view of the same definition; use it where a card counts what a queue lists.
- Board filters other than the date are declared on the board and wired to panel fields of the same kind; give a required one a `default`. See [ui/dashboard.md](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/docs/design/ui/dashboard.md).
- `admit` resolves every panel's view against the other definitions you pass it, so register and admit the board together with the data definitions it reads.

## Where definitions live

- **Downstream app:** one module per dataset with its descriptor JSON and its words beside it, a board module per board, and one test that admits them all (`references/admit.md`). The host registers them (`wow-view-host`).
- **Wow repository** (the one place in the framework repository this Skill applies): the compensation console's `compensation/dashboard/src/views/` (definitions, words per locale, `admit.test.ts`) and the Storybook scenarios under `typescript/storybook/stories/view-engine/` (the integration walkthrough in `integration/`, the retail scenario in `retail/`). A story is a `*.stories.tsx` with a `*.test.stories.tsx` twin that asserts in the browser. Changes to `typescript/wow-view-engine` itself are not this Skill's work.

## Revising after the descriptor changed

1. Fetch the new descriptor (development or staging) and commit it over the old one with its new `version`.
2. Run `admit`: a removed path, value or capability, a new protection or deprecation, a changed time encoding each come back as a finding at the field or view that used it.
3. Fix each at its choice: move to the replacement path, drop the analysis use of a newly protected field, remove a condition from a system view. Never widen a definition merely because the new descriptor grants more; add a field or a view only when the scenario asks for it.
4. Saved views are the readers'; the engine reports a removed capability in one when it is opened. Name the affected system views in the report.
