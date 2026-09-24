---
title: 'Aggregation builders'
description: 'Aggregation builders — @ahoo-wang/wow-client'
---

# Aggregation builders

AggregationQuery describes a server aggregation, not a JavaScript reducer. Supply at least one metric. `aggregate(query, attributes?, controller?)` returns flat rows keyed by your aliases; `aggregateStream` returns JSON SSE rows and needs explicit stream consumption. Generics describe rows but do not validate their contents.

| Builder                                                        | Inputs / result                                                                                                                                  |
| -------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------ |
| aggregation.element(path, predicate?)                          | Select an array/nested element path, with optional element-relative filter; root metadata/search/deletion filters are rejected inside predicate. |
| field(field), constant(number)                                 | Field reference or finite numeric constant expression.                                                                                           |
| add/subtract/multiply/divide(left, right)                      | Binary expression tree; division is evaluated by the backend, not here.                                                                          |
| terms(field, alias, missingKey?)                               | Terms grouping.                                                                                                                                  |
| histogram(field, {interval, alias})                            | Finite interval &gt; 0.                                                                                                                          |
| dateHistogram(field, {unit, alias, timeZone?, dense?})         | AggregationDateUnit from YEAR to SECOND, timeZone defaults UTC; empty zone and invalid enum throw.                                               |
| count(alias, predicate?)                                       | Count metric; no field argument.                                                                                                                 |
| any(field, alias, predicate?)                                  | Backend-selected value; do not treat it as a deterministic first row.                                                                            |
| sum/avg/min/max/stddev/variance(expression, alias, predicate?) | Numeric metric over an expression.                                                                                                               |

Query fields are filter, elements, groupBy, metrics (nonempty tuple), sort, limit, having. Root filter selects source documents; elements describes nested element traversal/filtering, and group/metric fields refer to that aggregation scope. Output stays flat; elements does not mean a nested output response. Sort fields refer to output aliases. Omitted filter/groupBy/sort/limit are left undefined; there is no hidden client limit or grouping.

Field syntax is validated by the same logical-field validator as filter. Aliases must be one valid segment, cannot contain dots or begin `__wow`. Invalid aliases, nonfinite constants and invalid histogram options throw TypeError. These checks do not guarantee fields exist, are numeric, or that a backend supports the query; service errors still reject. Builders perform no I/O or cleanup. Streaming readers must be cancelled and released when abandoned.

## Complete example

```ts
import { aggregation, filter, desc } from '@ahoo-wang/wow-client';
import type { AggregationQuery } from '@ahoo-wang/wow-client';
export const revenue: AggregationQuery = {
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
};
```

## Extended aggregation protocol

Aligned with Wow `main` at `fd1b3cd46`. Existing builder calls keep their JSON shape; optional metric filters, `missingKey`, and `dense` are omitted unless supplied.

- `aggregation.distinctCount(expression, alias, predicate?)` counts distinct non-null contributions; `aggregation.percentile(expression, percentile, alias, predicate?)` accepts finite values strictly between 0 and 100 (use 50 for the median). `stddev` and `variance` compute population statistics. Percentiles are approximate on both backends; Elasticsearch distinct counts may be approximate, while MongoDB counts distinct values exactly.
- Non-derived metrics accept an optional `FilterExpression` in the current aggregation scope. It affects only that metric. The backend validates scalar fields and rejects unsupported filter operators.
- `aggregation.derived(expression, alias)` uses a `DerivedExpression` tree (`METRIC_REF`, `CONSTANT`, `BINARY`). References must name earlier metrics and cannot reference `ANY`. Derived metrics have no record filter; filter the referenced metrics instead. Null operands or division by zero produce null.
- `having?: HavingExpression` supports `CONDITION`, `BETWEEN`, `IN`, `IS_NULL`, `AND`, and `OR`, using metric aliases, not field paths. `ComparisonOperator` contains `EQ`, `NE`, `GT`, `GTE`, `LT`, `LTE`. HAVING requires grouping and cannot reference `ANY`; it runs before sorting and limit. Non-empty sets/operands are enforced by tuple types; finite values, bounds, references and depth are validated by the server.
- `terms(field, alias, missingKey?)` optionally merges missing/null values into a nonblank string bucket key; the backend validates string field support. `dateHistogram(field, { unit, alias, timeZone?, dense? })` fills interior date gaps when `dense` is true; it requires the only group dimension. No rows means no generated date range.

Backend requirements still apply (MongoDB 5.1+ for dense groups, 7.0+ for percentiles); the client performs no backend capability probing. Raw typed expression objects are not runtime validators.

```ts
import {
  aggregation,
  AggregationExpressionOperator,
  ComparisonOperator,
  DerivedExpressionType,
  HavingExpressionType,
  type AggregationQuery,
} from '@ahoo-wang/wow-client';

const query: AggregationQuery = {
  groupBy: [aggregation.terms('state.status', 'status', 'Unknown')],
  metrics: [
    aggregation.count('orders'),
    aggregation.sum(aggregation.field('state.amount'), 'revenue'),
    aggregation.derived(
      {
        type: DerivedExpressionType.BINARY,
        operator: AggregationExpressionOperator.DIVIDE,
        left: { type: DerivedExpressionType.METRIC_REF, metric: 'revenue' },
        right: { type: DerivedExpressionType.METRIC_REF, metric: 'orders' },
      },
      'averageOrderValue',
    ),
  ],
  having: {
    type: HavingExpressionType.CONDITION,
    metric: 'averageOrderValue',
    operator: ComparisonOperator.GTE,
    value: 100,
  },
};
```

## Public signatures and types

These signatures follow declarations reachable from the current root entry. `?` marks optional input; generics/interfaces only constrain compile-time types. Locate inherited and related types through the [symbol index](./symbols). Runtime defaults and failure behavior are described above.

### AggregationGroupType {#api-AggregationGroupType}

```ts
export enum AggregationGroupType {
  TERMS = 'TERMS',
  HISTOGRAM = 'HISTOGRAM',
  DATE_HISTOGRAM = 'DATE_HISTOGRAM',
}
```

[typescript/wow-client/src/query/aggregation.ts:22](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/aggregation.ts#L22)

### AggregationMetricType {#api-AggregationMetricType}

```ts
export enum AggregationMetricType {
  COUNT = 'COUNT',
  NUMERIC = 'NUMERIC',
  ANY = 'ANY',
  DISTINCT_COUNT = 'DISTINCT_COUNT',
  PERCENTILE = 'PERCENTILE',
  DERIVED = 'DERIVED',
}
```

[typescript/wow-client/src/query/aggregation.ts:28](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/aggregation.ts#L28)

### AggregationExpressionType {#api-AggregationExpressionType}

```ts
export enum AggregationExpressionType {
  FIELD = 'FIELD',
  CONSTANT = 'CONSTANT',
  BINARY = 'BINARY',
}
```

[typescript/wow-client/src/query/aggregation.ts:37](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/aggregation.ts#L37)

### AggregationExpressionOperator {#api-AggregationExpressionOperator}

```ts
export enum AggregationExpressionOperator {
  ADD = 'ADD',
  SUBTRACT = 'SUBTRACT',
  MULTIPLY = 'MULTIPLY',
  DIVIDE = 'DIVIDE',
}
```

[typescript/wow-client/src/query/aggregation.ts:43](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/aggregation.ts#L43)

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

[typescript/wow-client/src/query/aggregation.ts:50](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/aggregation.ts#L50)

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

[typescript/wow-client/src/query/aggregation.ts:61](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/aggregation.ts#L61)

### AggregationElement {#api-AggregationElement}

```ts
export interface AggregationElement {
  path: QueryField;
  filter?: ElementFilterExpression;
}
```

[typescript/wow-client/src/query/aggregation.ts:70](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/aggregation.ts#L70)

### TermsAggregationGroup {#api-TermsAggregationGroup}

```ts
export interface TermsAggregationGroup<
  FIELDS extends string = string,
> extends AggregationGroupBase<FIELDS> {
  type: AggregationGroupType.TERMS;
  missingKey?: string;
}
```

[typescript/wow-client/src/query/aggregation.ts:80](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/aggregation.ts#L80)

### HistogramAggregationGroup {#api-HistogramAggregationGroup}

```ts
export interface HistogramAggregationGroup<
  FIELDS extends string = string,
> extends AggregationGroupBase<FIELDS> {
  type: AggregationGroupType.HISTOGRAM;
  interval: number;
}
```

[typescript/wow-client/src/query/aggregation.ts:87](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/aggregation.ts#L87)

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

[typescript/wow-client/src/query/aggregation.ts:94](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/aggregation.ts#L94)

### AggregationGroup {#api-AggregationGroup}

```ts
export type AggregationGroup<FIELDS extends string = string> =
  | TermsAggregationGroup<FIELDS>
  | HistogramAggregationGroup<FIELDS>
  | DateHistogramAggregationGroup<FIELDS>;
```

[typescript/wow-client/src/query/aggregation.ts:103](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/aggregation.ts#L103)

### FieldAggregationExpression {#api-FieldAggregationExpression}

```ts
export interface FieldAggregationExpression<FIELDS extends string = string> {
  type: AggregationExpressionType.FIELD;
  field: QueryField<FIELDS>;
}
```

[typescript/wow-client/src/query/aggregation.ts:108](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/aggregation.ts#L108)

### ConstantAggregationExpression {#api-ConstantAggregationExpression}

```ts
export interface ConstantAggregationExpression {
  type: AggregationExpressionType.CONSTANT;
  value: number;
}
```

[typescript/wow-client/src/query/aggregation.ts:113](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/aggregation.ts#L113)

### BinaryAggregationExpression {#api-BinaryAggregationExpression}

```ts
export interface BinaryAggregationExpression<FIELDS extends string = string> {
  type: AggregationExpressionType.BINARY;
  operator: AggregationExpressionOperator;
  left: AggregationExpression<FIELDS>;
  right: AggregationExpression<FIELDS>;
}
```

[typescript/wow-client/src/query/aggregation.ts:118](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/aggregation.ts#L118)

### AggregationExpression {#api-AggregationExpression}

```ts
export type AggregationExpression<FIELDS extends string = string> =
  | FieldAggregationExpression<FIELDS>
  | ConstantAggregationExpression
  | BinaryAggregationExpression<FIELDS>;
```

[typescript/wow-client/src/query/aggregation.ts:125](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/aggregation.ts#L125)

### CountAggregationMetric {#api-CountAggregationMetric}

```ts
export interface CountAggregationMetric<FIELDS extends string = string> {
  filter?: FilterExpression<FIELDS>;
  type: AggregationMetricType.COUNT;
  alias: string;
}
```

[typescript/wow-client/src/query/aggregation.ts:130](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/aggregation.ts#L130)

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

[typescript/wow-client/src/query/aggregation.ts:136](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/aggregation.ts#L136)

### AnyAggregationMetric {#api-AnyAggregationMetric}

```ts
export interface AnyAggregationMetric<FIELDS extends string = string> {
  filter?: FilterExpression<FIELDS>;
  type: AggregationMetricType.ANY;
  field: QueryField<FIELDS>;
  alias: string;
}
```

[typescript/wow-client/src/query/aggregation.ts:144](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/aggregation.ts#L144)

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

[typescript/wow-client/src/query/aggregation.ts:151](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/aggregation.ts#L151)

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

[typescript/wow-client/src/query/aggregation.ts:160](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/aggregation.ts#L160)

### DerivedExpressionType {#api-DerivedExpressionType}

```ts
export enum DerivedExpressionType {
  METRIC_REF = 'METRIC_REF',
  CONSTANT = 'CONSTANT',
  BINARY = 'BINARY',
}
```

[typescript/wow-client/src/query/aggregation.ts:168](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/aggregation.ts#L168)

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

[typescript/wow-client/src/query/aggregation.ts:175](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/aggregation.ts#L175)

### DerivedAggregationMetric {#api-DerivedAggregationMetric}

```ts
export interface DerivedAggregationMetric {
  type: AggregationMetricType.DERIVED;
  expression: DerivedExpression;
  alias: string;
}
```

[typescript/wow-client/src/query/aggregation.ts:185](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/aggregation.ts#L185)

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

[typescript/wow-client/src/query/aggregation.ts:191](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/aggregation.ts#L191)

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

[typescript/wow-client/src/query/aggregation.ts:200](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/aggregation.ts#L200)

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

[typescript/wow-client/src/query/aggregation.ts:210](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/aggregation.ts#L210)

### AggregationMetric {#api-AggregationMetric}

```ts
export type AggregationMetric<FIELDS extends string = string> =
  | CountAggregationMetric<FIELDS>
  | NumericAggregationMetric<FIELDS>
  | AnyAggregationMetric<FIELDS>
  | DistinctCountAggregationMetric<FIELDS>
  | PercentileAggregationMetric<FIELDS>
  | DerivedAggregationMetric;
```

[typescript/wow-client/src/query/aggregation.ts:234](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/aggregation.ts#L234)

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

[typescript/wow-client/src/query/aggregation.ts:242](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/aggregation.ts#L242)

### HistogramAggregationOptions {#api-HistogramAggregationOptions}

```ts
export interface HistogramAggregationOptions {
  interval: number;
  alias: string;
}
```

[typescript/wow-client/src/query/aggregation.ts:258](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/aggregation.ts#L258)

### DateHistogramAggregationOptions {#api-DateHistogramAggregationOptions}

```ts
export interface DateHistogramAggregationOptions {
  unit: AggregationDateUnit;
  alias: string;
  timeZone?: string;
  dense?: boolean;
}
```

[typescript/wow-client/src/query/aggregation.ts:263](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/aggregation.ts#L263)

### aggregation {#api-aggregation}

```ts
export declare const aggregation: {
  element(
    path: string,
    predicate?: ElementFilterExpression,
  ): AggregationElement;
  field<FIELDS extends string>(
    field: FIELDS,
  ): FieldAggregationExpression<FIELDS>;
  constant(value: number): ConstantAggregationExpression;
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
    field: FIELDS,
    alias: string,
    missingKey?: string,
  ): TermsAggregationGroup<FIELDS>;
  histogram<FIELDS extends string>(
    field: FIELDS,
    { interval, alias }: HistogramAggregationOptions,
  ): HistogramAggregationGroup<FIELDS>;
  dateHistogram<FIELDS extends string>(
    field: FIELDS,
    { unit, alias, timeZone, dense }: DateHistogramAggregationOptions,
  ): DateHistogramAggregationGroup<FIELDS>;
  any<FIELDS extends string>(
    field: FIELDS,
    alias: string,
    predicate?: FilterExpression<FIELDS>,
  ): AnyAggregationMetric<FIELDS>;
  count<FIELDS extends string = string>(
    alias: string,
    predicate?: FilterExpression<FIELDS>,
  ): CountAggregationMetric<FIELDS>;
  sum: <FIELDS extends string>(
    expression: AggregationExpression<FIELDS>,
    alias: string,
    predicate?: FilterExpression<FIELDS>,
  ) => NumericAggregationMetric<FIELDS>;
  avg: <FIELDS extends string>(
    expression: AggregationExpression<FIELDS>,
    alias: string,
    predicate?: FilterExpression<FIELDS>,
  ) => NumericAggregationMetric<FIELDS>;
  min: <FIELDS extends string>(
    expression: AggregationExpression<FIELDS>,
    alias: string,
    predicate?: FilterExpression<FIELDS>,
  ) => NumericAggregationMetric<FIELDS>;
  max: <FIELDS extends string>(
    expression: AggregationExpression<FIELDS>,
    alias: string,
    predicate?: FilterExpression<FIELDS>,
  ) => NumericAggregationMetric<FIELDS>;
  stddev: <FIELDS extends string>(
    expression: AggregationExpression<FIELDS>,
    alias: string,
    predicate?: FilterExpression<FIELDS>,
  ) => NumericAggregationMetric<FIELDS>;
  variance: <FIELDS extends string>(
    expression: AggregationExpression<FIELDS>,
    alias: string,
    predicate?: FilterExpression<FIELDS>,
  ) => NumericAggregationMetric<FIELDS>;
  distinctCount<FIELDS extends string>(
    expression: AggregationExpression<FIELDS>,
    alias: string,
    predicate?: FilterExpression<FIELDS>,
  ): DistinctCountAggregationMetric<FIELDS>;
  percentile<FIELDS extends string>(
    expression: AggregationExpression<FIELDS>,
    percentile: number,
    alias: string,
    predicate?: FilterExpression<FIELDS>,
  ): PercentileAggregationMetric<FIELDS>;
  derived(
    expression: DerivedExpression,
    alias: string,
  ): DerivedAggregationMetric;
};
```

[typescript/wow-client/src/query/aggregation.ts:310](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/aggregation.ts#L310)
