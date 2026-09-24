---
title: '聚合构造器'
description: '聚合构造器 — @ahoo-wang/wow-client'
---

# 聚合构造器

AggregationQuery 描述服务端聚合，不是 JavaScript reducer，至少提供一个 metric。`aggregate(query, attributes?, controller?)` 返回以 alias 为键的扁平行；`aggregateStream` 返回 JSON SSE 行，需要显式消费。泛型描述行但不校验内容。

| 构造器                                                         | 输入 / 结果                                                                                 |
| -------------------------------------------------------------- | ------------------------------------------------------------------------------------------- |
| aggregation.element(path, predicate?)                          | 选择数组/嵌套元素路径，可带元素相对 filter；predicate 内拒绝根元数据/search/deletion 过滤。 |
| field(field)、constant(number)                                 | 字段引用或有限数值常量表达式。                                                              |
| add/subtract/multiply/divide(left, right)                      | 二元表达式树，除法由后端求值，不在此执行。                                                  |
| terms(field, alias, missingKey?)                               | terms 分组。                                                                                |
| histogram(field, {interval, alias})                            | interval 必须有限且大于 0。                                                                 |
| dateHistogram(field, {unit, alias, timeZone?, dense?})         | AggregationDateUnit 从 YEAR 到 SECOND，时区默认 UTC；空时区或非法枚举抛错。                 |
| count(alias, predicate?)                                       | count 指标，没有字段参数。                                                                  |
| any(field, alias, predicate?)                                  | 后端选择的值，不保证是确定性的第一行。                                                      |
| sum/avg/min/max/stddev/variance(expression, alias, predicate?) | 对表达式进行数值聚合。                                                                      |

查询字段为 filter、elements、groupBy、metrics（非空元组）、sort、limit、having。根 filter 选择源文档，elements 描述嵌套元素遍历/过滤，分组和指标字段相对于该聚合作用域。结果仍为扁平行，elements 不表示嵌套响应。sort 使用输出 alias。省略 filter/groupBy/sort/limit 保持 undefined，没有隐藏客户端 limit 或分组。

字段语法由 filter 使用的逻辑字段校验器验证。alias 必须为合法单段，不能含点或以 `__wow` 开头。非法 alias、非有限常量、非法 histogram 选项抛 TypeError；不保证字段存在、为数值或后端支持，服务错误仍可拒绝。构造器无 I/O，无须清理；放弃流式读取时需取消并释放 reader。

## 完整示例

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

## 扩展聚合协议

对齐 Wow `main` 的 `fd1b3cd46`。旧构造器调用保持原 JSON 形状；未提供的指标过滤、`missingKey` 和 `dense` 不写入请求。

- `aggregation.distinctCount(expression, alias, predicate?)` 统计不同的非空贡献值；`aggregation.percentile(expression, percentile, alias, predicate?)` 只接受严格介于 0 与 100 之间的有限数值（中位数使用 50）。`stddev`、`variance` 计算总体标准差、总体方差。两种后端的百分位均为近似结果；Elasticsearch 去重计数可能近似，MongoDB 去重计数为精确值。
- 非派生指标可传入当前聚合范围内的 `FilterExpression`，只影响该指标。后端校验标量字段并拒绝不支持的过滤操作符。
- `aggregation.derived(expression, alias)` 使用 `DerivedExpression` 树（`METRIC_REF`、`CONSTANT`、`BINARY`）。只能引用之前声明的指标，不能引用 `ANY`。派生指标没有记录过滤，应过滤它所引用的指标。空操作数或除零产生 null。
- `having?: HavingExpression` 支持 `CONDITION`、`BETWEEN`、`IN`、`IS_NULL`、`AND`、`OR`，引用指标别名而非字段路径。`ComparisonOperator` 包含 `EQ`、`NE`、`GT`、`GTE`、`LT`、`LTE`。HAVING 必须有分组，不能引用 `ANY`，在排序与 limit 之前执行。非空集合/操作数由元组类型约束；有限数值、上下界、引用和深度由服务端校验。
- `terms(field, alias, missingKey?)` 可将缺失/null 值合并到非空白字符串桶键，后端校验字段是否支持字符串分组。`dateHistogram(field, { unit, alias, timeZone?, dense? })` 在 `dense` 为 true 时填充日期内部缺口，要求它是唯一分组维度。没有结果行时不生成日期范围。

仍需满足后端版本要求（日期补桶需要 MongoDB 5.1+，百分位需要 7.0+）；客户端不探测后端能力。直接构造的类型化表达式对象不提供运行时校验。

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

## 公开签名与类型

以下签名按当前根入口可达声明核对。`?` 表示可省略；泛型/接口只约束编译期，继承项与关联类型可从 [符号索引](./symbols) 定位。运行时默认值和失败行为以本页上文为准。

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
