# Recipes

Each recipe assumes the descriptor is in hand and every path below was found in it. Paths are examples; use the ones the descriptor lists.

## Where definitions live

- **Downstream app:** next to the engine setup that registers them (`new ViewEngine({ definitions, … })`), one module per dataset, with the committed descriptor JSON beside them and a test that admits them.
- **Wow repository** (the one place inside the framework repository this Skill applies):
  - Storybook scenarios: `typescript/storybook/stories/view-engine/` (the retail scenario's `retail/definitions.ts`, `retail/views.ts`, `retail/boards.ts`; the scenario brief `typescript/storybook/docs/scenarios.md`). A story is a `*.stories.tsx` file with a `*.test.stories.tsx` twin that asserts in the browser.
  - Compensation console: `compensation/dashboard/src/views/` (definitions per locale, committed descriptors under the dashboard's e2e support descriptors, `descriptors.test.ts` admitting every system view).
  - Changes to `typescript/wow-view-engine` itself are not this Skill's work.

Start system view configs from `defaultRecordConfig(definition)` or `defaultAnalysisConfig(definition)` and override the question members, so every required member is present.

## Record queue: a list people work through

Scenario: 客服每天处理待发货的订单，最早付款的先处理。

1. Fields: the ones the queue shows or filters on: order number (`cell: 'copyable'`), status (`enum` with labels and tones), paid time (`datetime`, `temporal` from `semantic`), amount (`number`, `numberFormat` currency), warehouse, buyer.
2. `record`: `rowKey` the descriptor sorts (the order number or `aggregateId`), `paging: 'paged'` when a total is wanted and `PAGED` is listed, `cursor` for long append-only lists (event streams); `rowFields` for fields a row action reads.
3. System view 「待发货」: filter `state.status IN ['PAID']`, sort paid time ascending, columns in the order the agent reads them, a summary only where the field grants the function.

```ts
{
  id: 'to-ship',
  title: '待发货',
  config: {
    ...defaultRecordConfig(orders),
    filter: { op: 'and', children: [{ field: 'state.status', operator: 'IN', value: ['PAID'] }] },
    sort: [{ field: 'state.paidAt', direction: 'ASC' }],
  },
}
```

An `enum` offers `IN`/`NOT_IN`, not `EQ`. Check each operator used in a system view is in the field's declared (narrowed) operators.

## Analysis: a breakdown and a trend

Scenario: 运营看每个渠道每天的订单数和实付。

1. `analysis.count: true` (the descriptor's `analysis.metrics` lists `COUNT`).
2. `fields`: the channel with `groups: ['TERMS']`; the paid amount with `functions: ['SUM', 'AVG']` (only those `aggregate.functions` lists); the paid time with `groups: ['DATE_HISTOGRAM']` and `dateUnits` within `analysis.dateUnits`.
3. System view: groups and metrics with display names in the audience's words.

```ts
{
  id: 'channel-daily',
  title: '各渠道每日订单',
  config: {
    ...defaultAnalysisConfig(orders),
    groups: [
      { type: 'DATE_HISTOGRAM', field: 'state.paidAt', alias: 'day', unit: 'DAY', label: '付款日' },
      { type: 'TERMS', field: 'state.channel', alias: 'channel', label: '渠道' },
    ],
    metrics: [
      { type: 'COUNT', alias: 'orders', label: '订单数' },
      { type: 'NUMERIC', alias: 'paid', function: 'SUM', expression: { type: 'FIELD', field: 'state.paidAmount' }, label: '实付' },
    ],
    sort: [{ alias: 'day', direction: 'ASC' }],
    limit: 500,
  },
}
```

Ratios such as 客单价 or 退款率 are `DERIVED` metrics over other metrics (`expressions` must be granted), with `format` for how they read; they are not fields. Line items are an `analysis.elements` expansion of an array the descriptor's `elements[]` aggregates, with element-relative field names.

## Event stream: by event type

Scenario: 看每天有多少订单流走到了「付款成功」，以及大额付款。

1. The source is the event-stream model; its descriptor answers `GET {base}/{aggregate}/event/schema`.
2. Root fields: `aggregateId` (the order), `createTime` (the event time), `version`, `ownerId` when `OWNER_ID` is a root operator. `record.paging: 'cursor'` when listed; `rowKey` equal to the `CURSOR_UNIQUE_SORT` appended identity (usually `id`).
3. `body`: `elementMatch` with `elementTitle: 'bodyType'`; `bodyType` is an `enum` whose options are the `variants.values[].value` with business labels (「下单」「付款成功」); payload fields come from the variant, relative to the event.
4. A condition on an event's payload keeps both conditions in one element predicate:

```ts
{
  field: 'body',
  operator: 'ELEMENT_MATCH',
  value: {
    op: 'and',
    children: [
      { field: 'body.bodyType', operator: 'IN', value: ['com.example.order.OrderPaid'] },
      { field: 'body.body.paidAmount', operator: 'GT', value: 1000 },
    ],
  },
}
```

Two separate root conditions (`body.bodyType` and `body.body.paidAmount`) would match a stream where one event is OrderPaid and another event has a large amount; the element predicate is the only correct shape. A metric that counts one event type puts the same `ELEMENT_MATCH` in its own `filter`. An analysis by event type expands `body` (`analysis.elements: [{ path: 'body', aggregations: [{ field: 'bodyType', groups: ['TERMS'], functions: [] }] }]`) and counts events.

## Dashboard

1. The panels show saved views (`instanceId`) or own an analysis (`owned: { definitionId, config }`), each admitted like a system view of its data definition.
2. Board filters (`fields`) have business labels; wire them to panel fields of the same filter type, and give a required filter a `default`.
3. Start from `emptyDashboardConfig`; the grid has 24 columns.

## A story (Wow repository)

1. Put the definition and its system views in the scenario's module; feed the story's source a `describe` that returns the committed descriptor, so the story runs the same narrowing as production.
2. Write the `*.stories.tsx` story and its `*.test.stories.tsx` twin: open the view, assert what the audience sees (labels, the event-type picker, a column of masked values), and assert no `view.definition.invalid` state.
3. Run the Storybook gates (type check, lint, interaction tests) and the scenario's unit tests.

## Revising after the descriptor changed

1. Fetch the new descriptor (dev or staging), diff it against the committed one: removed fields, removed operators or groups, new `sensitivity`, new `deprecated`, changed `semantic`, new constraints, aliases.
2. For each change, narrow the definition and every system view config that used the capability; move a deprecated or aliased path to its replacement; drop analysis use of a newly protected field.
3. Never widen a definition merely because the new descriptor grants more; add a capability only when the scenario asks for it.
4. Commit the new descriptor with its version and rerun the self-check; a saved user view that still names a removed capability is reported by the engine at open time (the user can remove unavailable conditions), so mention the affected saved views in the report.
