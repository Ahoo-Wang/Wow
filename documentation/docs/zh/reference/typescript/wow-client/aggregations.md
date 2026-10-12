---
title: '聚合构造器'
description: '聚合构造器 — @ahoo-wang/wow-client'
---

# 聚合构造器

`AggregationQuery` 请服务端对记录分组并计量，它不是 JavaScript 的 reducer。`aggregate(query, attributes?, abort?)` 返回以别名为键的扁平行，`aggregateStream` 返回同样这些行的 `ReadableStream`，由你自己读取和取消。传入的行类型只是编译期的描述，不会据此校验行的内容。

一个查询由以下几部分组成，下面按通常书写的顺序逐一说明：

| 部分            | 回答的问题             | 用什么构造                                                         |
| --------------- | ---------------------- | ------------------------------------------------------------------ |
| `filter`        | 哪些记录参与           | `filter.*`                                                         |
| `elements`      | 哪些嵌套元素作为记录   | `aggregation.element`                                              |
| `groupBy`       | 行怎样形成             | `terms`、`histogram`、`dateHistogram`、`datePart`                  |
| `metrics`       | 每行计量什么（至少一个） | `count`、`sum` … `percentile`、`any`、`first`、`last`、`derived` |
| `having`        | 保留哪些行             | `aggregation.having.*`                                             |
| `sort`、`limit` | 行的顺序与数量         | `asc`、`desc`、一个数字                                            |

每个分组与指标都是目标在前、别名第二，其余选项作为末尾的对象传入。省略的部分保持 `undefined`：客户端不会暗中补上 limit、分组或排序。

## 分组并计量

已付款订单按 SKU 统计营收，从高到低：

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

无论组成部分如何，结果都是扁平的：每行一个对象，以分组与指标的别名为键。

## 选择记录

- `filter` 在根上选择源文档，用的是与列表查询相同的 `filter.*` 构造器。
- `elements` 把嵌套数组变成记录：`aggregation.element(path, predicate?)` 进入元素路径，可选的 predicate 相对元素过滤。只适用于根的过滤（元数据、全文搜索、删除状态）在 predicate 里会被拒绝。有了 `elements` 之后，分组与指标的字段相对元素读取，就像上例的 `sku` 与 `price`。

## 形成行

| 构造器                                                     | 行                                                                                                                                                                         |
| ---------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `terms(field \| expression, alias, { missingKey? })`      | 每个不同的取值一行。用表达式代替字段需要 Wow 9.2，代价高。`missingKey` 把缺失与 null 值归入一个非空白的字符串键；它只能配字段，后端会检查字段是否为字符串。               |
| `histogram(field \| expression, alias, { interval })`     | 每个桶一行，`interval` 须为大于 0 的有限数；可以用表达式代替字段（Wow 9.2）。                                                                                               |
| `dateHistogram(field, alias, { unit, timeZone?, dense? })` | 在 `timeZone`（默认 UTC）下每个日历 `unit`（`AggregationDateUnit`，从 `YEAR` 到 `SECOND`）一行。                                                                            |
| `datePart(field, alias, { part, timeZone?, dense? })`      | 日期部分（`AggregationDatePart`）的每个取值一行，键为整数：`DAY_OF_WEEK` 按 ISO（1 为周一、7 为周日），`DAY_OF_MONTH` 为 1–31，`HOUR_OF_DAY` 为该时区墙上时间的 0–23，`MONTH_OF_YEAR` 为 1–12。 |

空时区或未知的枚举值会抛错。

每个分组构造器还接受 `scope`（`AggregationGroupScope`，Wow 9.9）：有 `elements` 时，`ROOT` 把元素按它所属根记录上的字段分组，例如展开 `state.events` 后 `dateHistogram('createTime', 'day', { unit: AggregationDateUnit.DAY, scope: AggregationGroupScope.ROOT })`。它只能配字段，不能配表达式，须排在元素分组之前，并且需要服务端描述符的 `analysis.rootGroups` 为真。

`dense: true` 补齐缺口，要求该分组是唯一的分组。对 `dateHistogram`，它补齐日期之间的空档；没有结果行时没有范围可补。对 `datePart`，它返回该部分固定取值域的每个键，空的也补上。多个 `datePart` 分组可以组合，例如星期 × 小时：

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

## 计量每一行

| 构造器                                                                        | 值                                                                                                                                         |
| ----------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------ |
| `count(alias, { filter? })`                                                  | 记录数，没有字段参数。                                                                                                                     |
| `sum`、`avg`、`min`、`max`、`stddev`、`variance` `(expression, alias, { filter? })` | 对表达式求数值函数。`stddev` 与 `variance` 为总体标准差、总体方差。                                                                  |
| `distinctCount(expression, alias, { filter? })`                              | 不同的非空取值个数：MongoDB 上精确，Elasticsearch 上可能是近似值。                                                                         |
| `percentile(expression, alias, { percentile, filter? })`                     | 严格介于 0 与 100 之间的百分位（中位数用 50）；两种后端都是近似值。                                                                        |
| `any(field, alias, { filter? })`                                             | 后端从组内选出的一个值，不保证是确定的第一行。                                                                                             |
| `first(field, alias, { orderBy?, filter? })`、`last(…)`                      | 组内按 `orderBy`（默认是模型的事件时间，即描述中的 `analysis.firstLastOrderBy`）最早或最晚的记录上该字段的值，类型与字段相同，可能为 null。 |

数值指标接受一个表达式，由以下部分构成：

- `aggregation.field(path)` 与 `aggregation.constant(number)`（须有限）；
- `add`、`subtract`、`multiply`、`divide(left, right)`，由后端求值，除法也是；
- `dateDiff(from, to, unit)`：以 `DateDiffUnit`（`SECOND`、`MINUTE`、`HOUR`、`DAY`，`DAY` 恰为 24 小时）计的 `to − from`，为有符号小数；任一时间缺失时没有值（Wow 9.2）。

除 `derived` 外，每个指标都可以传 `{ filter }`：当前聚合范围内的 `FilterExpression`，只限定这个指标统计哪些记录。后端校验标量字段，并拒绝它不支持的过滤操作符。

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

## 由其他指标派生

`aggregation.derived(d => …, alias)` 按别名由之前的指标算出一个指标。回调收到一个 `DerivedExpressionDsl`（对应 Kotlin 的同名类型）：`d.ref(metric)`、`d.constant(value)`（须有限）、`d.add`、`d.subtract`、`d.multiply`、`d.divide`，构造出 `DerivedExpression` 树（`METRIC_REF`、`CONSTANT`、`BINARY`）。`aggregation.derived(tree, alias)` 仍接受手搭的树。

派生指标只能引用它之前的指标，不能引用 `any`、`first` 或 `last`，也没有记录过滤：要过滤，就过滤它所引用的指标。空操作数或除以零得到 null。

同一个回调也构造跨组的窗口（Wow 9.9 起，能力描述的 `analysis.windows` 列出支持的函数）：`d.total(metric, ...within)`、`d.running(metric, order, { within, frame, function })`、`d.previous(metric, along, { shift, period })`、`d.rank(metric, { within, direction })`，分别构造 `TOTAL`、`RUNNING`、`PREVIOUS`、`RANK` 节点，可与四则运算组合（`d.divide(d.ref('gmv'), d.total('gmv', 'province'))` 即占比）。Wow 对 filter 选出的全部分组计算它们，在 `having`、`sort`、`limit` 之前；窗口读取的是先于它声明的非派生数值指标，一个查询至多 `AGGREGATION_LIMITS.MAX_WINDOWS` 个窗口。规则见[窗口](../../../guide/query/aggregation-query.md#windows)。

## 只保留部分行

`having` 在分组之后过滤行，引用的是指标别名而不是字段路径。用 `aggregation.having`（`HavingDsl`，对应 Kotlin 的同名类型）构造：`eq`、`ne`、`gt`、`gte`、`lt`、`lte`、`between`、`isIn`、`isNull`、`isNotNull`、`and([…])`、`or([…])`。结果是 `HavingExpression`（`CONDITION`、`BETWEEN`、`IN`、`IS_NULL`、`AND`、`OR`；比较用 `ComparisonOperator` 的 `EQ` … `LTE`）。

`having` 需要 `groupBy`，不能引用 `any`、`first` 或 `last`，在 `sort` 与 `limit` 之前执行。每个构造器遇到非有限数值、`lower > upper` 或空列表，都按 Wow 的报错文字抛出。

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

## 排序并限制行数

`sort` 用的是输出别名，不是字段路径：`asc('revenue')`、`desc('revenue')`。`limit` 限制行数；不给时服务端返回 `AGGREGATION_LIMITS.DEFAULT_LIMIT`（100）行，任何查询都不能超过 `MAX_LIMIT`（10,000）。

## 发送之前检查

`aggregation.query(query)` 按 Wow 执行的规则检查组装好的查询并返回副本：`AGGREGATION_LIMITS` 的各项上限、别名冲突，以及 `sort`、`having` 与派生指标引用了什么，包括它们的分组与深度。各个构造器已经检查过自己的参数：

- 别名是一个合法的段：不含点，也不以 `__wow` 开头。
- 字段路径经过与 `filter` 相同的逻辑字段校验。
- 非法别名、非有限常量或非法的 histogram 选项抛出 `TypeError`。

这些检查不能证明字段存在、是数值，也不能证明后端支持这个查询；服务端会再全部检查一遍，它拒绝的调用照样被拒绝。后端还有各自的要求：`dense` 分组需要 MongoDB 5.1 或更高版本，百分位需要 7.0 或更高版本；客户端不探测后端能力。构造器不做任何 I/O，手写的表达式对象在运行时不受校验。

可选部分让线上形状保持精简：指标的 `filter`、`missingKey` 与 `dense` 只在你提供时才发送，所以以前的构造器调用产生的 JSON 不变。

## 公开签名与类型

以下签名按当前根入口可达声明核对。`?` 表示可省略；泛型/接口只约束编译期，继承项与关联类型可从 [符号索引](./symbols) 定位。运行时默认值和失败行为以本页上文为准。

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

Wow 的 `AggregationQuery` 强制执行的大小上限；`aggregation.query()` 在发送前检查。`DEFAULT_LIMIT` 是查询未指定 limit 时服务端返回的行数。

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
