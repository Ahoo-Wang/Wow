---
title: '聚合构造器'
description: '聚合构造器 — @ahoo-wang/wow-client'
---

# 聚合构造器

AggregationQuery 描述服务端聚合，不是 JavaScript reducer，至少提供一个 metric。`aggregate(query, attributes?, abort?)` 返回以 alias 为键的扁平行；`aggregateStream` 返回 JSON SSE 行，需要显式消费。泛型描述行但不校验内容。

| 构造器                                                                    | 输入 / 结果                                                                                 |
| ------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------- |
| aggregation.element(path, predicate?)                                     | 选择数组/嵌套元素路径，可带元素相对 filter；predicate 内拒绝根元数据/search/deletion 过滤。 |
| field(field)、constant(number)                                            | 字段引用或有限数值常量表达式。                                                              |
| add/subtract/multiply/divide(left, right)                                 | 二元表达式树，除法由后端求值，不在此执行。                                                  |
| terms(field, alias, { missingKey? })                                      | terms 分组；`missingKey` 不能为空白。                                                       |
| histogram(field, alias, { interval })                                     | interval 必须有限且大于 0。                                                                 |
| dateHistogram(field, alias, { unit, timeZone?, dense? })                  | AggregationDateUnit 从 YEAR 到 SECOND，时区默认 UTC；空时区或非法枚举抛错。                 |
| count(alias, { filter? })                                                 | count 指标，没有字段参数。                                                                  |
| any(field, alias, { filter? })                                            | 后端选择的值，不保证是确定性的第一行。                                                      |
| sum/avg/min/max/stddev/variance/distinctCount(expression, alias, { filter? }) | 对表达式进行数值聚合。                                                                  |
| percentile(expression, alias, { percentile, filter? })                    | 百分位必须严格介于 0 与 100 之间。                                                          |
| derived(d => …, alias)、derived(expression, alias)                        | 按别名由之前的指标计算出的指标；回调用 `d.ref`、`d.constant`、`d.add`/`subtract`/`multiply`/`divide` 构造算式。 |
| having.eq/ne/gt/gte/lt/lte(metric, value)、having.between(metric, lower, upper)、having.isIn(metric, values)、having.isNull/isNotNull(metric)、having.and/or(operands) | 查询的 `having`，引用指标别名。非有限数值、上下界颠倒、空列表按 Wow 的报错文字抛出。 |
| query(query)                                                              | 按 Wow 接收时执行的规则（上限、别名冲突、sort 与 having 的引用）检查组装好的查询，返回副本。 |

每个分组与指标都是目标在前、别名第二，其余选项作为末尾的对象传入。

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

- `aggregation.distinctCount(expression, alias, { filter? })` 统计不同的非空贡献值；`aggregation.percentile(expression, alias, { percentile, filter? })` 只接受严格介于 0 与 100 之间的有限数值（中位数使用 50）。`stddev`、`variance` 计算总体标准差、总体方差。两种后端的百分位均为近似结果；Elasticsearch 去重计数可能近似，MongoDB 去重计数为精确值。
- 非派生指标可传入 `{ filter }` 选项，即当前聚合范围内的 `FilterExpression`，只影响该指标。后端校验标量字段并拒绝不支持的过滤操作符。
- `aggregation.derived(d => …, alias)` 用交给回调的 `DerivedExpressionDsl`（对应 Kotlin 的 `DerivedExpressionDsl`）构造 `DerivedExpression` 树（`METRIC_REF`、`CONSTANT`、`BINARY`）：`d.ref(metric)`、`d.constant(value)`（须有限）、`d.add`、`d.subtract`、`d.multiply`、`d.divide`。`aggregation.derived(tree, alias)` 仍接受手搭的树。只能引用之前声明的指标，不能引用 `ANY`。派生指标没有记录过滤，应过滤它所引用的指标。空操作数或除零产生 null。
- `having?: HavingExpression` 支持 `CONDITION`、`BETWEEN`、`IN`、`IS_NULL`、`AND`、`OR`，引用指标别名而非字段路径。`ComparisonOperator` 包含 `EQ`、`NE`、`GT`、`GTE`、`LT`、`LTE`。HAVING 必须有分组，不能引用 `ANY`，在排序与 limit 之前执行。用 `aggregation.having`（`HavingDsl`，对应 Kotlin 的 `HavingDsl`）构造：`eq`、`ne`、`gt`、`gte`、`lt`、`lte`、`between`、`isIn`、`isNull`、`isNotNull`、`and([…])`、`or([…])`。每个构造器按 Wow 的报错文字拒绝非有限数值、`lower > upper` 与空列表；`aggregation.query()` 检查引用、分组与深度，服务端会再全部检查一遍。
- `terms(field, alias, { missingKey })` 可将缺失/null 值合并到非空白字符串桶键，后端校验字段是否支持字符串分组。`dateHistogram(field, alias, { unit, timeZone?, dense: true })` 填充日期内部缺口，要求它是唯一分组维度。没有结果行时不生成日期范围。

仍需满足后端版本要求（日期补桶需要 MongoDB 5.1+，百分位需要 7.0+）；客户端不探测后端能力。直接构造的类型化表达式对象不提供运行时校验。

```ts
import {
  aggregation,
  filter,
  type AggregationQuery,
} from '@ahoo-wang/wow-client';

const query: AggregationQuery = {
  groupBy: [
    aggregation.terms('state.status', 'status', { missingKey: 'Unknown' }),
  ],
  metrics: [
    aggregation.count('orders'),
    aggregation.count('refunded', {
      filter: filter.eq('state.refunded', true),
    }),
    aggregation.sum(aggregation.field('state.amount'), 'revenue'),
    aggregation.percentile(aggregation.field('state.amount'), 'p95Amount', {
      percentile: 95,
    }),
    aggregation.derived(
      d => d.divide(d.ref('revenue'), d.ref('orders')),
      'averageOrderValue',
    ),
  ],
  having: aggregation.having.gte('averageOrderValue', 100),
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
}
```

[typescript/wow-client/src/dsl/aggregation/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/aggregation/types.ts)

### AggregationExpressionType {#api-AggregationExpressionType}

```ts
export enum AggregationExpressionType {
  FIELD = 'FIELD',
  CONSTANT = 'CONSTANT',
  BINARY = 'BINARY',
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
  filter?: ElementFilterExpression;
}
```

[typescript/wow-client/src/dsl/aggregation/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/aggregation/types.ts)

### TermsAggregationGroup {#api-TermsAggregationGroup}

```ts
export interface TermsAggregationGroup<
  FIELDS extends string = string,
> extends AggregationGroupBase<FIELDS> {
  type: AggregationGroupType.TERMS;
  missingKey?: string;
}
```

[typescript/wow-client/src/dsl/aggregation/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/aggregation/types.ts)

### HistogramAggregationGroup {#api-HistogramAggregationGroup}

```ts
export interface HistogramAggregationGroup<
  FIELDS extends string = string,
> extends AggregationGroupBase<FIELDS> {
  type: AggregationGroupType.HISTOGRAM;
  interval: number;
}
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

### AggregationGroup {#api-AggregationGroup}

```ts
export type AggregationGroup<FIELDS extends string = string> =
  | TermsAggregationGroup<FIELDS>
  | HistogramAggregationGroup<FIELDS>
  | DateHistogramAggregationGroup<FIELDS>;
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

### AggregationExpression {#api-AggregationExpression}

```ts
export type AggregationExpression<FIELDS extends string = string> =
  | FieldAggregationExpression<FIELDS>
  | ConstantAggregationExpression
  | BinaryAggregationExpression<FIELDS>;
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
  | DerivedAggregationMetric;
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

Wow 的 `AggregationQuery` 强制执行的大小上限；`aggregation.query()` 在发送前检查。`DEFAULT_LIMIT` 是查询未指定 limit 时服务端返回的行数。

[typescript/wow-client/src/dsl/aggregation/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/aggregation/types.ts)

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
    options?: TermsAggregationOptions,
  ): TermsAggregationGroup<FIELDS>;
  histogram<FIELDS extends string>(
    field: FIELDS,
    alias: string,
    options: HistogramAggregationOptions,
  ): HistogramAggregationGroup<FIELDS>;
  dateHistogram<FIELDS extends string>(
    field: FIELDS,
    alias: string,
    options: DateHistogramAggregationOptions,
  ): DateHistogramAggregationGroup<FIELDS>;
  any<FIELDS extends string>(
    field: FIELDS,
    alias: string,
    options?: AggregationMetricOptions<FIELDS>,
  ): AnyAggregationMetric<FIELDS>;
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
