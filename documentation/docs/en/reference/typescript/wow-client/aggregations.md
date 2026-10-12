---
title: 'Aggregation builders'
description: 'Aggregation builders — @ahoo-wang/wow-client'
---

# Aggregation builders

An `AggregationQuery` asks the server to group and measure records; it is not a JavaScript reducer. `aggregate(query, attributes?, abort?)` resolves to flat rows keyed by your aliases, and `aggregateStream` to a `ReadableStream` of the same rows, which you read and cancel yourself. The row type you pass is a compile-time description; nothing checks the rows against it.

A query has these parts, each covered below in the order you usually write them:

| Part       | Answers                                   | Built with                                   |
| ---------- | ----------------------------------------- | -------------------------------------------- |
| `filter`   | Which records take part                   | `filter.*`                                   |
| `elements` | Which nested elements are the records     | `aggregation.element`                        |
| `groupBy`  | How rows are formed                       | `terms`, `histogram`, `dateHistogram`, `datePart` |
| `metrics`  | What each row measures (at least one)     | `count`, `sum` … `percentile`, `any`, `first`, `last`, `derived` |
| `having`   | Which rows are kept                       | `aggregation.having.*`                       |
| `sort`, `limit` | Row order and count                  | `asc`, `desc`, a number                      |

Every group and metric takes its target first, its alias second, and any further options as a trailing object. A part you leave out stays `undefined`: the client adds no hidden limit, grouping or sort.

## Group and measure

Revenue per SKU over paid orders, highest first:

```ts
import {
  aggregation,
  desc,
  filter,
  type SnapshotQueryClient,
} from '@ahoo-wang/wow-client';

interface Order {
  status: string;
}

export function revenueBySku(orders: SnapshotQueryClient<Order>) {
  return orders.aggregate<{ sku: string; revenue: number; rows: number }>(
    aggregation.query({
      filter: filter.eq('state.status', 'PAID'),
      elements: [aggregation.element('state.lines', filter.gt('quantity', 0))],
      groupBy: [aggregation.terms('sku', 'sku')],
      metrics: [
        aggregation.sum(
          aggregation.multiply(
            aggregation.field('price'),
            aggregation.field('quantity'),
          ),
          'revenue',
        ),
        aggregation.count('rows'),
      ],
      sort: [desc('revenue')],
      limit: 20,
    }),
  );
}
```

The output stays flat whatever the parts: one object per row, keyed by the group and metric aliases.

## Choose the records

- `filter` selects source documents at the root, with the same `filter.*` builders as a list query.
- `elements` turns a nested array into the records: `aggregation.element(path, predicate?)` steps into the element path, and its optional predicate filters elements relative to it. Root-only filters (metadata, search, deletion) are rejected inside the predicate. After `elements`, group and metric fields are read relative to the element, as `sku` and `price` are above.

## Form the rows

| Builder                                                   | Rows                                                                                                                                                                                                 |
| --------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `terms(field \| expression, alias, { missingKey? })`      | One per distinct value. An expression in place of the field needs Wow 9.2 and is expensive. `missingKey` collects missing and null values under a non-blank string key; it needs a field, and the backend checks the field is a string. |
| `histogram(field \| expression, alias, { interval })`     | One per bucket of a finite `interval` greater than 0; an expression may replace the field (Wow 9.2).                                                                                                  |
| `dateHistogram(field, alias, { unit, timeZone?, dense? })` | One per calendar `unit` (`AggregationDateUnit`, `YEAR` to `SECOND`) in `timeZone`, UTC by default.                                                                                                 |
| `datePart(field, alias, { part, timeZone?, dense? })`     | One per value of a date part (`AggregationDatePart`): integer keys, `DAY_OF_WEEK` ISO (1 Monday to 7 Sunday), `DAY_OF_MONTH` 1–31, `HOUR_OF_DAY` 0–23 on the zone's wall clock, `MONTH_OF_YEAR` 1–12. |

An empty time zone or an unknown enum value throws.

Each group builder also takes `scope` (`AggregationGroupScope`, Wow 9.9): with `elements`, `ROOT` groups the elements by a field of their root record, such as `dateHistogram('createTime', 'day', { unit: AggregationDateUnit.DAY, scope: AggregationGroupScope.ROOT })` after expanding `state.events`. It needs a field, not an expression, and a server whose descriptor has `analysis.rootGroups`.

`dense: true` fills the gaps, and needs the group to be the only one. For `dateHistogram` it fills interior date gaps; with no rows there is no range to fill. For `datePart` it returns every key of the part's fixed domain, the empty ones filled in. Several `datePart` groups combine, such as weekday by hour:

```ts
import {
  AggregationDatePart,
  aggregation,
  type AggregationQuery,
} from '@ahoo-wang/wow-client';

export const ordersByWeekdayAndHour: AggregationQuery = aggregation.query({
  groupBy: [
    aggregation.datePart('state.createTime', 'weekday', {
      part: AggregationDatePart.DAY_OF_WEEK,
      timeZone: 'Asia/Shanghai',
    }),
    aggregation.datePart('state.createTime', 'hour', {
      part: AggregationDatePart.HOUR_OF_DAY,
      timeZone: 'Asia/Shanghai',
    }),
  ],
  metrics: [aggregation.count('orders')],
});
```

## Measure each row

| Builder                                                                     | Value                                                                                                                                                                             |
| --------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `count(alias, { filter? })`                                                 | The number of records; takes no field.                                                                                                                                            |
| `sum`, `avg`, `min`, `max`, `stddev`, `variance` `(expression, alias, { filter? })` | A numeric function of an expression. `stddev` and `variance` are population statistics.                                                                                  |
| `distinctCount(expression, alias, { filter? })`                             | The number of distinct non-null values: exact on MongoDB, possibly approximate on Elasticsearch.                                                                                  |
| `percentile(expression, alias, { percentile, filter? })`                    | A percentile strictly between 0 and 100 (50 for the median); approximate on both backends.                                                                                       |
| `any(field, alias, { filter? })`                                            | A value the backend picks from the group; not a deterministic first row.                                                                                                         |
| `first(field, alias, { orderBy?, filter? })`, `last(…)`                     | The field's value on the group's earliest or latest record by `orderBy` (by default the model's event time, the descriptor's `analysis.firstLastOrderBy`), in the field's own type or null. |

The numeric metrics take an expression, built from:

- `aggregation.field(path)` and `aggregation.constant(number)` (finite);
- `add`, `subtract`, `multiply`, `divide(left, right)`, evaluated by the backend, division included;
- `dateDiff(from, to, unit)`: `to − from` in a `DateDiffUnit` (`SECOND`, `MINUTE`, `HOUR`, `DAY`, a `DAY` being exactly 24 h), a signed decimal, with no value when either instant is absent (Wow 9.2).

Every metric except `derived` takes `{ filter }`, a `FilterExpression` in the current aggregation scope that limits the records that metric counts, and only that metric. The backend validates scalar fields and rejects filter operators it does not support.

```ts
import {
  aggregation,
  filter,
  type AggregationQuery,
} from '@ahoo-wang/wow-client';

export const refundsByStatus: AggregationQuery = aggregation.query({
  groupBy: [
    aggregation.terms('state.status', 'status', { missingKey: 'Unknown' }),
  ],
  metrics: [
    aggregation.count('orders'),
    aggregation.count('refunded', {
      filter: filter.eq('state.refunded', true),
    }),
    aggregation.percentile(aggregation.field('state.amount'), 'p95Amount', {
      percentile: 95,
    }),
  ],
});
```

## Derive a metric from others

`aggregation.derived(d => …, alias)` computes a metric from earlier metrics by alias. The callback receives a `DerivedExpressionDsl` (after Kotlin's): `d.ref(metric)`, `d.constant(value)` (finite), `d.add`, `d.subtract`, `d.multiply` and `d.divide`; it builds a `DerivedExpression` tree (`METRIC_REF`, `CONSTANT`, `BINARY`). `aggregation.derived(tree, alias)` still takes a tree built by hand.

A derived metric references only metrics before it, never `any`, `first` or `last`, and takes no record filter: filter the metrics it references. A null operand or a division by zero gives null.

The same builder makes windows across the groups (Wow 9.9 and later, where the descriptor's `analysis.windows` lists the function): `d.total(metric, ...within)`, `d.running(metric, order, { within, frame, function })`, `d.previous(metric, along, { shift, period })` and `d.rank(metric, { within, direction })`, building `TOTAL`, `RUNNING`, `PREVIOUS` and `RANK` nodes that compose with the arithmetic (`d.divide(d.ref('gmv'), d.total('gmv', 'province'))` is a share). Wow computes them over every group the filter selects, before `having`, `sort` and `limit`; a window reads a non-derived numeric metric declared before it, and a query computes at most `AGGREGATION_LIMITS.MAX_WINDOWS` of them. The rules are in [Windows](../../../guide/query/aggregation-query.md#windows).

## Keep only some rows

`having` filters the rows after grouping, on metric aliases rather than field paths. Build it with `aggregation.having` (`HavingDsl`, after Kotlin's): `eq`, `ne`, `gt`, `gte`, `lt`, `lte`, `between`, `isIn`, `isNull`, `isNotNull`, `and([…])`, `or([…])`. It produces a `HavingExpression` (`CONDITION`, `BETWEEN`, `IN`, `IS_NULL`, `AND`, `OR`; comparisons use `ComparisonOperator` `EQ` … `LTE`).

`having` needs a `groupBy`, cannot reference `any`, `first` or `last`, and runs before `sort` and `limit`. Each builder throws Wow's message for a non-finite number, `lower > upper` or an empty list.

```ts
import {
  aggregation,
  type AggregationQuery,
} from '@ahoo-wang/wow-client';

export const bigSpenders: AggregationQuery = aggregation.query({
  groupBy: [aggregation.terms('state.customerId', 'customer')],
  metrics: [
    aggregation.count('orders'),
    aggregation.sum(aggregation.field('state.amount'), 'revenue'),
    aggregation.derived(
      d => d.divide(d.ref('revenue'), d.ref('orders')),
      'averageOrderValue',
    ),
  ],
  having: aggregation.having.and([
    aggregation.having.gte('orders', 3),
    aggregation.having.gte('averageOrderValue', 100),
  ]),
});
```

## Order and cap the rows

`sort` names output aliases, not field paths: `asc('revenue')`, `desc('revenue')`. `limit` caps the row count; without one the server returns `AGGREGATION_LIMITS.DEFAULT_LIMIT` (100) rows, and no query may ask for more than `MAX_LIMIT` (10,000).

## Check before sending

`aggregation.query(query)` checks the assembled query against the rules Wow enforces and returns a copy: the `AGGREGATION_LIMITS` sizes, alias collisions, and what `sort`, `having` and derived metrics reference, including their grouping and depth. Each builder already checks its own arguments:

- An alias is one valid segment: no dots, and not starting with `__wow`.
- Field paths go through the same logical-field validator as `filter`.
- An invalid alias, a non-finite constant or an invalid histogram option throws a `TypeError`.

These checks do not prove that a field exists or is numeric, or that the backend supports the query; the server checks everything again and a call it rejects still rejects. Backend requirements apply on top: MongoDB 5.1 or later for `dense` groups and 7.0 or later for percentiles; the client does not probe backend capabilities. Builders do no I/O, and an expression object you write by hand is not validated at run time.

Optional parts keep the wire shape small: a metric `filter`, `missingKey` or `dense` is sent only when you supply it, so earlier builder calls produce the same JSON as before.

## Public signatures and types

These signatures follow declarations reachable from the current root entry. `?` marks optional input; generics/interfaces only constrain compile-time types. Locate inherited and related types through the [symbol index](./symbols). Runtime defaults and failure behavior are described above.

### AggregationGroupType {#api-AggregationGroupType}

```ts
export enum AggregationGroupType {
  TERMS = 'TERMS',
  HISTOGRAM = 'HISTOGRAM',
  DATE_HISTOGRAM = 'DATE_HISTOGRAM',
  DATE_PART = 'DATE_PART',
}
```

[typescript/wow-client/src/dsl/aggregation/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/aggregation/types.ts)

### AggregationMetricType {#api-AggregationMetricType}

```ts
export enum AggregationMetricType {
  COUNT = 'COUNT',
  NUMERIC = 'NUMERIC',
  ANY = 'ANY',
  DISTINCT_COUNT = 'DISTINCT_COUNT',
  PERCENTILE = 'PERCENTILE',
  DERIVED = 'DERIVED',
  FIRST = 'FIRST',
  LAST = 'LAST',
}
```

[typescript/wow-client/src/dsl/aggregation/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/aggregation/types.ts)

### AggregationExpressionType {#api-AggregationExpressionType}

```ts
export enum AggregationExpressionType {
  FIELD = 'FIELD',
  CONSTANT = 'CONSTANT',
  BINARY = 'BINARY',
  DATE_DIFF = 'DATE_DIFF',
}
export enum DateDiffUnit {
  SECOND = 'SECOND',
  MINUTE = 'MINUTE',
  HOUR = 'HOUR',
  DAY = 'DAY',
}
```

[typescript/wow-client/src/dsl/aggregation/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/aggregation/types.ts)

### AggregationExpressionOperator {#api-AggregationExpressionOperator}

```ts
export enum AggregationExpressionOperator {
  ADD = 'ADD',
  SUBTRACT = 'SUBTRACT',
  MULTIPLY = 'MULTIPLY',
  DIVIDE = 'DIVIDE',
}
```

[typescript/wow-client/src/dsl/aggregation/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/aggregation/types.ts)

### AggregationDateUnit {#api-AggregationDateUnit}

```ts
export enum AggregationDateUnit {
  YEAR = 'YEAR',
  QUARTER = 'QUARTER',
  MONTH = 'MONTH',
  WEEK = 'WEEK',
  DAY = 'DAY',
  HOUR = 'HOUR',
  MINUTE = 'MINUTE',
  SECOND = 'SECOND',
}
```

[typescript/wow-client/src/dsl/aggregation/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/aggregation/types.ts)

### AggregationDatePart {#api-AggregationDatePart}

```ts
export enum AggregationDatePart {
  DAY_OF_WEEK = 'DAY_OF_WEEK',
  DAY_OF_MONTH = 'DAY_OF_MONTH',
  HOUR_OF_DAY = 'HOUR_OF_DAY',
  MONTH_OF_YEAR = 'MONTH_OF_YEAR',
}
```

[typescript/wow-client/src/dsl/aggregation/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/aggregation/types.ts)

### AggregationFunction {#api-AggregationFunction}

```ts
export enum AggregationFunction {
  SUM = 'SUM',
  AVG = 'AVG',
  MIN = 'MIN',
  MAX = 'MAX',
  STDDEV = 'STDDEV',
  VARIANCE = 'VARIANCE',
}
```

[typescript/wow-client/src/dsl/aggregation/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/aggregation/types.ts)

### AggregationElement {#api-AggregationElement}

```ts
export interface AggregationElement {
  path: QueryField;
  filter?: ElementFilterExpression | ExpressionFilter;
}
```

[typescript/wow-client/src/dsl/aggregation/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/aggregation/types.ts)

### TermsAggregationGroup {#api-TermsAggregationGroup}

```ts
export type AggregationGroupInput<FIELDS extends string = string> =
  | {
      field: QueryField<FIELDS>;
      expression?: undefined;
    }
  | {
      field?: undefined;
      expression: AggregationExpression<FIELDS>;
    };
export type TermsAggregationGroup<FIELDS extends string = string> = {
  type: AggregationGroupType.TERMS;
  alias: string;
  missingKey?: string;
} & AggregationGroupInput<FIELDS>;
```

[typescript/wow-client/src/dsl/aggregation/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/aggregation/types.ts)

### HistogramAggregationGroup {#api-HistogramAggregationGroup}

```ts
export type HistogramAggregationGroup<FIELDS extends string = string> = {
  type: AggregationGroupType.HISTOGRAM;
  alias: string;
  interval: number;
} & AggregationGroupInput<FIELDS>;
```

[typescript/wow-client/src/dsl/aggregation/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/aggregation/types.ts)

### DateHistogramAggregationGroup {#api-DateHistogramAggregationGroup}

```ts
export interface DateHistogramAggregationGroup<
  FIELDS extends string = string,
> extends AggregationGroupBase<FIELDS> {
  type: AggregationGroupType.DATE_HISTOGRAM;
  unit: AggregationDateUnit;
  timeZone?: string;
  dense?: boolean;
}
```

[typescript/wow-client/src/dsl/aggregation/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/aggregation/types.ts)

### DatePartAggregationGroup {#api-DatePartAggregationGroup}

```ts
export interface DatePartAggregationGroup<
  FIELDS extends string = string,
> extends AggregationGroupBase<FIELDS> {
  type: AggregationGroupType.DATE_PART;
  part: AggregationDatePart;
  timeZone?: string;
  dense?: boolean;
}
```

[typescript/wow-client/src/dsl/aggregation/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/aggregation/types.ts)

### AggregationGroup {#api-AggregationGroup}

```ts
export type AggregationGroup<FIELDS extends string = string> =
  | TermsAggregationGroup<FIELDS>
  | HistogramAggregationGroup<FIELDS>
  | DateHistogramAggregationGroup<FIELDS>
  | DatePartAggregationGroup<FIELDS>;
```

[typescript/wow-client/src/dsl/aggregation/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/aggregation/types.ts)

### FieldAggregationExpression {#api-FieldAggregationExpression}

```ts
export interface FieldAggregationExpression<FIELDS extends string = string> {
  type: AggregationExpressionType.FIELD;
  field: QueryField<FIELDS>;
}
```

[typescript/wow-client/src/dsl/aggregation/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/aggregation/types.ts)

### ConstantAggregationExpression {#api-ConstantAggregationExpression}

```ts
export interface ConstantAggregationExpression {
  type: AggregationExpressionType.CONSTANT;
  value: number;
}
```

[typescript/wow-client/src/dsl/aggregation/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/aggregation/types.ts)

### BinaryAggregationExpression {#api-BinaryAggregationExpression}

```ts
export interface BinaryAggregationExpression<FIELDS extends string = string> {
  type: AggregationExpressionType.BINARY;
  operator: AggregationExpressionOperator;
  left: AggregationExpression<FIELDS>;
  right: AggregationExpression<FIELDS>;
}
```

[typescript/wow-client/src/dsl/aggregation/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/aggregation/types.ts)

### DateDiffAggregationExpression {#api-DateDiffAggregationExpression}

```ts
export interface DateDiffAggregationExpression<FIELDS extends string = string> {
  type: AggregationExpressionType.DATE_DIFF;
  from: QueryField<FIELDS>;
  to: QueryField<FIELDS>;
  unit: DateDiffUnit;
}
```

[typescript/wow-client/src/dsl/aggregation/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/aggregation/types.ts)

### AggregationExpression {#api-AggregationExpression}

```ts
export type AggregationExpression<FIELDS extends string = string> =
  | FieldAggregationExpression<FIELDS>
  | ConstantAggregationExpression
  | BinaryAggregationExpression<FIELDS>
  | DateDiffAggregationExpression<FIELDS>;
```

[typescript/wow-client/src/dsl/aggregation/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/aggregation/types.ts)

### CountAggregationMetric {#api-CountAggregationMetric}

```ts
export interface CountAggregationMetric<FIELDS extends string = string> {
  filter?: FilterExpression<FIELDS>;
  type: AggregationMetricType.COUNT;
  alias: string;
}
```

[typescript/wow-client/src/dsl/aggregation/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/aggregation/types.ts)

### NumericAggregationMetric {#api-NumericAggregationMetric}

```ts
export interface NumericAggregationMetric<FIELDS extends string = string> {
  filter?: FilterExpression<FIELDS>;
  type: AggregationMetricType.NUMERIC;
  function: AggregationFunction;
  expression: AggregationExpression<FIELDS>;
  alias: string;
}
```

[typescript/wow-client/src/dsl/aggregation/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/aggregation/types.ts)

### AnyAggregationMetric {#api-AnyAggregationMetric}

```ts
export interface AnyAggregationMetric<FIELDS extends string = string> {
  filter?: FilterExpression<FIELDS>;
  type: AggregationMetricType.ANY;
  field: QueryField<FIELDS>;
  alias: string;
}
```

[typescript/wow-client/src/dsl/aggregation/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/aggregation/types.ts)

### DistinctCountAggregationMetric {#api-DistinctCountAggregationMetric}

```ts
export interface DistinctCountAggregationMetric<
  FIELDS extends string = string,
> {
  type: AggregationMetricType.DISTINCT_COUNT;
  expression: AggregationExpression<FIELDS>;
  alias: string;
  filter?: FilterExpression<FIELDS>;
}
```

[typescript/wow-client/src/dsl/aggregation/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/aggregation/types.ts)

### EdgeAggregationMetric {#api-EdgeAggregationMetric}

```ts
export interface EdgeAggregationMetric<FIELDS extends string = string> {
  type: AggregationMetricType.FIRST | AggregationMetricType.LAST;
  field: QueryField<FIELDS>;
  orderBy?: QueryField<FIELDS>;
  alias: string;
  filter?: FilterExpression<FIELDS>;
}
```

[typescript/wow-client/src/dsl/aggregation/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/aggregation/types.ts)

### PercentileAggregationMetric {#api-PercentileAggregationMetric}

```ts
export interface PercentileAggregationMetric<FIELDS extends string = string> {
  type: AggregationMetricType.PERCENTILE;
  expression: AggregationExpression<FIELDS>;
  percentile: number;
  alias: string;
  filter?: FilterExpression<FIELDS>;
}
```

[typescript/wow-client/src/dsl/aggregation/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/aggregation/types.ts)

### DerivedExpressionType {#api-DerivedExpressionType}

```ts
export enum DerivedExpressionType {
  METRIC_REF = 'METRIC_REF',
  CONSTANT = 'CONSTANT',
  BINARY = 'BINARY',
}
```

[typescript/wow-client/src/dsl/aggregation/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/aggregation/types.ts)

### DerivedExpression {#api-DerivedExpression}

```ts
export type DerivedExpression =
  | { type: DerivedExpressionType.METRIC_REF; metric: string }
  | { type: DerivedExpressionType.CONSTANT; value: number }
  | {
      type: DerivedExpressionType.BINARY;
      operator: AggregationExpressionOperator;
      left: DerivedExpression;
      right: DerivedExpression;
    };
```

[typescript/wow-client/src/dsl/aggregation/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/aggregation/types.ts)

### DerivedAggregationMetric {#api-DerivedAggregationMetric}

```ts
export interface DerivedAggregationMetric {
  type: AggregationMetricType.DERIVED;
  expression: DerivedExpression;
  alias: string;
}
```

[typescript/wow-client/src/dsl/aggregation/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/aggregation/types.ts)

### HavingExpressionType {#api-HavingExpressionType}

```ts
export enum HavingExpressionType {
  CONDITION = 'CONDITION',
  BETWEEN = 'BETWEEN',
  IN = 'IN',
  IS_NULL = 'IS_NULL',
  AND = 'AND',
  OR = 'OR',
}
```

[typescript/wow-client/src/dsl/aggregation/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/aggregation/types.ts)

### ComparisonOperator {#api-ComparisonOperator}

```ts
export enum ComparisonOperator {
  EQ = 'EQ',
  NE = 'NE',
  GT = 'GT',
  GTE = 'GTE',
  LT = 'LT',
  LTE = 'LTE',
}
```

[typescript/wow-client/src/dsl/aggregation/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/aggregation/types.ts)

### HavingExpression {#api-HavingExpression}

```ts
export type HavingExpression =
  | {
      type: HavingExpressionType.CONDITION;
      metric: string;
      operator: ComparisonOperator;
      value: number;
    }
  | {
      type: HavingExpressionType.BETWEEN;
      metric: string;
      lower: number;
      upper: number;
    }
  | {
      type: HavingExpressionType.IN;
      metric: string;
      values: [number, ...number[]];
    }
  | { type: HavingExpressionType.IS_NULL; metric: string; negated?: boolean }
  | {
      type: HavingExpressionType.AND | HavingExpressionType.OR;
      operands: [HavingExpression, ...HavingExpression[]];
    };
```

[typescript/wow-client/src/dsl/aggregation/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/aggregation/types.ts)

### AggregationMetric {#api-AggregationMetric}

```ts
export type AggregationMetric<FIELDS extends string = string> =
  | CountAggregationMetric<FIELDS>
  | NumericAggregationMetric<FIELDS>
  | AnyAggregationMetric<FIELDS>
  | DistinctCountAggregationMetric<FIELDS>
  | PercentileAggregationMetric<FIELDS>
  | DerivedAggregationMetric
  | EdgeAggregationMetric<FIELDS>;
```

[typescript/wow-client/src/dsl/aggregation/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/aggregation/types.ts)

### AggregationQuery {#api-AggregationQuery}

```ts
export interface AggregationQuery<
  ROOT_FIELDS extends string = string,
  AGGREGATION_FIELDS extends string = ROOT_FIELDS,
> {
  filter?: FilterExpression<ROOT_FIELDS>;
  elements?: AggregationElement[];
  groupBy?: AggregationGroup<AGGREGATION_FIELDS>[];
  metrics: [
    AggregationMetric<AGGREGATION_FIELDS>,
    ...AggregationMetric<AGGREGATION_FIELDS>[],
  ];
  sort?: FieldSort[];
  limit?: number;
  having?: HavingExpression;
}
```

[typescript/wow-client/src/dsl/aggregation/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/aggregation/types.ts)

### AggregationMetricOptions {#api-AggregationMetricOptions}

```ts
export interface AggregationMetricOptions<FIELDS extends string = string> {
  filter?: FilterExpression<FIELDS>;
}
```

[typescript/wow-client/src/dsl/aggregation/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/aggregation/types.ts)

### TermsAggregationOptions {#api-TermsAggregationOptions}

```ts
export interface TermsAggregationOptions {
  missingKey?: string;
}
```

[typescript/wow-client/src/dsl/aggregation/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/aggregation/types.ts)

### HistogramAggregationOptions {#api-HistogramAggregationOptions}

```ts
export interface HistogramAggregationOptions {
  interval: number;
}
```

[typescript/wow-client/src/dsl/aggregation/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/aggregation/types.ts)

### DateHistogramAggregationOptions {#api-DateHistogramAggregationOptions}

```ts
export interface DateHistogramAggregationOptions {
  unit: AggregationDateUnit;
  timeZone?: string;
  dense?: boolean;
}
```

[typescript/wow-client/src/dsl/aggregation/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/aggregation/types.ts)

### DatePartAggregationOptions {#api-DatePartAggregationOptions}

```ts
export interface DatePartAggregationOptions {
  part: AggregationDatePart;
  timeZone?: string;
  dense?: boolean;
}
```

[typescript/wow-client/src/dsl/aggregation/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/aggregation/types.ts)

### EdgeAggregationOptions {#api-EdgeAggregationOptions}

```ts
export interface EdgeAggregationOptions<
  FIELDS extends string = string,
> extends AggregationMetricOptions<FIELDS> {
  orderBy?: FIELDS;
}
```

[typescript/wow-client/src/dsl/aggregation/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/aggregation/types.ts)

### PercentileAggregationOptions {#api-PercentileAggregationOptions}

```ts
export interface PercentileAggregationOptions<
  FIELDS extends string = string,
> extends AggregationMetricOptions<FIELDS> {
  percentile: number;
}
```

[typescript/wow-client/src/dsl/aggregation/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/aggregation/types.ts)

### HavingDsl {#api-HavingDsl}

```ts
export interface HavingDsl {
  eq(metric: string, value: number): HavingExpression;
  ne(metric: string, value: number): HavingExpression;
  gt(metric: string, value: number): HavingExpression;
  gte(metric: string, value: number): HavingExpression;
  lt(metric: string, value: number): HavingExpression;
  lte(metric: string, value: number): HavingExpression;
  between(metric: string, lower: number, upper: number): HavingExpression;
  isIn(metric: string, values: readonly number[]): HavingExpression;
  isNull(metric: string): HavingExpression;
  isNotNull(metric: string): HavingExpression;
  and(operands: readonly HavingExpression[]): HavingExpression;
  or(operands: readonly HavingExpression[]): HavingExpression;
}
```

[typescript/wow-client/src/dsl/aggregation/having.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/aggregation/having.ts)

### DerivedExpressionDsl {#api-DerivedExpressionDsl}

```ts
export interface DerivedExpressionDsl {
  ref(metric: string): DerivedExpression;
  constant(value: number): DerivedExpression;
  add(left: DerivedExpression, right: DerivedExpression): DerivedExpression;
  subtract(
    left: DerivedExpression,
    right: DerivedExpression,
  ): DerivedExpression;
  multiply(
    left: DerivedExpression,
    right: DerivedExpression,
  ): DerivedExpression;
  divide(left: DerivedExpression, right: DerivedExpression): DerivedExpression;
}
```

[typescript/wow-client/src/dsl/aggregation/derived.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/aggregation/derived.ts)

### AGGREGATION_LIMITS {#api-AGGREGATION_LIMITS}

```ts
export const AGGREGATION_LIMITS = Object.freeze({
  DEFAULT_LIMIT: 100,
  MAX_LIMIT: 10_000,
  MAX_ELEMENTS: 5,
  MAX_GROUPS: 32,
  MAX_METRICS: 64,
  MAX_SORT_FIELDS: 32,
  MAX_EXPRESSION_DEPTH: 8,
  MAX_EXPRESSION_NODES: 256,
});
```

The sizes Wow's `AggregationQuery` enforces; `aggregation.query()` checks them before sending. `DEFAULT_LIMIT` is the row count the server returns when a query names no limit.

[typescript/wow-client/src/dsl/aggregation/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/aggregation/types.ts)

### aggregation {#api-aggregation}

```ts
export declare const aggregation: {
  element(
    path: string,
    predicate?: ElementFilterExpression | ExpressionFilter,
  ): AggregationElement;
  field<FIELDS extends string>(
    field: FIELDS,
  ): FieldAggregationExpression<FIELDS>;
  constant(value: number): ConstantAggregationExpression;
  dateDiff<FIELDS extends string>(
    from: FIELDS,
    to: FIELDS,
    unit: DateDiffUnit,
  ): DateDiffAggregationExpression<FIELDS>;
  add: <FIELDS extends string>(
    left: AggregationExpression<FIELDS>,
    right: AggregationExpression<FIELDS>,
  ) => BinaryAggregationExpression<FIELDS>;
  subtract: <FIELDS extends string>(
    left: AggregationExpression<FIELDS>,
    right: AggregationExpression<FIELDS>,
  ) => BinaryAggregationExpression<FIELDS>;
  multiply: <FIELDS extends string>(
    left: AggregationExpression<FIELDS>,
    right: AggregationExpression<FIELDS>,
  ) => BinaryAggregationExpression<FIELDS>;
  divide: <FIELDS extends string>(
    left: AggregationExpression<FIELDS>,
    right: AggregationExpression<FIELDS>,
  ) => BinaryAggregationExpression<FIELDS>;
  terms<FIELDS extends string>(
    input: FIELDS | AggregationExpression<FIELDS>,
    alias: string,
    options?: TermsAggregationOptions,
  ): TermsAggregationGroup<FIELDS>;
  histogram<FIELDS extends string>(
    input: FIELDS | AggregationExpression<FIELDS>,
    alias: string,
    options: HistogramAggregationOptions,
  ): HistogramAggregationGroup<FIELDS>;
  dateHistogram<FIELDS extends string>(
    field: FIELDS,
    alias: string,
    options: DateHistogramAggregationOptions,
  ): DateHistogramAggregationGroup<FIELDS>;
  datePart<FIELDS extends string>(
    field: FIELDS,
    alias: string,
    options: DatePartAggregationOptions,
  ): DatePartAggregationGroup<FIELDS>;
  any<FIELDS extends string>(
    field: FIELDS,
    alias: string,
    options?: AggregationMetricOptions<FIELDS>,
  ): AnyAggregationMetric<FIELDS>;
  first<FIELDS extends string>(
    field: FIELDS,
    alias: string,
    options?: EdgeAggregationOptions<FIELDS>,
  ): EdgeAggregationMetric<FIELDS>;
  last<FIELDS extends string>(
    field: FIELDS,
    alias: string,
    options?: EdgeAggregationOptions<FIELDS>,
  ): EdgeAggregationMetric<FIELDS>;
  count<FIELDS extends string = string>(
    alias: string,
    options?: AggregationMetricOptions<FIELDS>,
  ): CountAggregationMetric<FIELDS>;
  sum: <FIELDS extends string>(
    expression: AggregationExpression<FIELDS>,
    alias: string,
    options?: AggregationMetricOptions<FIELDS>,
  ) => NumericAggregationMetric<FIELDS>;
  avg: <FIELDS extends string>(
    expression: AggregationExpression<FIELDS>,
    alias: string,
    options?: AggregationMetricOptions<FIELDS>,
  ) => NumericAggregationMetric<FIELDS>;
  min: <FIELDS extends string>(
    expression: AggregationExpression<FIELDS>,
    alias: string,
    options?: AggregationMetricOptions<FIELDS>,
  ) => NumericAggregationMetric<FIELDS>;
  max: <FIELDS extends string>(
    expression: AggregationExpression<FIELDS>,
    alias: string,
    options?: AggregationMetricOptions<FIELDS>,
  ) => NumericAggregationMetric<FIELDS>;
  stddev: <FIELDS extends string>(
    expression: AggregationExpression<FIELDS>,
    alias: string,
    options?: AggregationMetricOptions<FIELDS>,
  ) => NumericAggregationMetric<FIELDS>;
  variance: <FIELDS extends string>(
    expression: AggregationExpression<FIELDS>,
    alias: string,
    options?: AggregationMetricOptions<FIELDS>,
  ) => NumericAggregationMetric<FIELDS>;
  distinctCount<FIELDS extends string>(
    expression: AggregationExpression<FIELDS>,
    alias: string,
    options?: AggregationMetricOptions<FIELDS>,
  ): DistinctCountAggregationMetric<FIELDS>;
  percentile<FIELDS extends string>(
    expression: AggregationExpression<FIELDS>,
    alias: string,
    options: PercentileAggregationOptions<FIELDS>,
  ): PercentileAggregationMetric<FIELDS>;
  derived(
    expression:
      | DerivedExpression
      | ((d: DerivedExpressionDsl) => DerivedExpression),
    alias: string,
  ): DerivedAggregationMetric;
  having: HavingDsl;
  query<
    ROOT_FIELDS extends string = string,
    AGGREGATION_FIELDS extends string = ROOT_FIELDS,
  >(
    query: AggregationQuery<ROOT_FIELDS, AGGREGATION_FIELDS>,
  ): AggregationQuery<ROOT_FIELDS, AGGREGATION_FIELDS>;
};
```

[typescript/wow-client/src/dsl/aggregation/builders.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/aggregation/builders.ts)
