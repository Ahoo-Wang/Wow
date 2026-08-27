# Fetcher Wow 查询 DSL 3.18 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 `@ahoo-wang/fetcher-wow` 中交付与 Wow 当前线协议一致、对 TypeScript 友好的函数式 Filter 与 Aggregation DSL，并将 monorepo 锁步更新到 `3.18.0`。

**Architecture:** 保留现有 `Condition` 体系不变，只在 `filter.ts` 与 `aggregation.ts` 上提供无状态纯函数。DSL 直接生成 Wow 请求 JSON；SnapshotQueryClient 只扩展类型签名以接收根字段与聚合字段分离的请求，不增加转换或验证层。

**Tech Stack:** TypeScript 6、Vitest 4、Vite 8、ESLint、Prettier、VitePress、pnpm 10。

**Spec:** `packages/wow/docs/superpowers/specs/2026-08-27-wow-query-dsl-design.md`

## Global Constraints

- 目标版本固定为 `3.18.0`，使用 `pnpm update-version 3.18.0` 更新版本。
- `Condition`、legacy `Operator` 与 operator locale 必须保持原样。
- `filter.ts` 与 `aggregation.ts` 不保留旧签名、兼容重载或兼容别名。
- 不新增 `snapshot/schema` 或 `snapshot/schema/refresh` 客户端。
- 不修改 generator、viewer、react 的运行时代码。
- 不新增依赖、package、根 TypeScript 配置或构建配置。
- 不实现链式 Builder、字段 Schema 图、查询解析器或完整客户端聚合验证器。
- 中英文 README 与 Wiki 必须同步；Skill API reference 必须与公共导出一致。

---

## 文件职责

- `packages/wow/src/query/filter.ts`：Wow FilterExpression 判别联合、校验与函数式 Filter DSL。
- `packages/wow/src/query/aggregation.ts`：聚合判别联合、字段泛型、叶节点校验与函数式 Aggregation DSL。
- `packages/wow/src/query/snapshot/snapshotQueryApi.ts`：公开 Snapshot 查询接口的聚合请求类型。
- `packages/wow/src/query/snapshot/snapshotQueryClient.ts`：具体 Snapshot 客户端的聚合请求类型；运行时请求逻辑保持不变。
- `packages/wow/test/query/filter.test.ts`：Filter JSON、边界校验和元素作用域类型测试。
- `packages/wow/test/query/aggregation.test.ts`：Aggregation JSON、辅助函数、递归表达式与字段泛型测试。
- `packages/wow/test/query/snapshot/snapshotQueryApi.test.ts`：Snapshot API 类型与请求 body 透传测试。
- `packages/wow/README.md`、`packages/wow/README.zh-CN.md`：包级入门与常用示例。
- `skills/fetcher-wow-cqrs/references/api.md`：完整公共 API 事实与签名。
- `wiki/packages/wow.md`、`wiki/zh/packages/wow.md`：中英文站点文档。
- 根、`packages/*` 与 `integration-test/package.json`：锁步 `3.18.0` 版本。

### Task 1: 对齐并重塑 Filter DSL

**Files:**

- Modify: `packages/wow/test/query/filter.test.ts:14-622`
- Modify: `packages/wow/src/query/filter.ts:21-898`

**Interfaces:**

- Consumes: 现有 `DeletionState`、`LogicalField`、`ElementFilterExpression` 与所有既有 `filter.*` 构造函数。
- Produces: `SearchMode`、`TimeUnit`、`SearchFilterOptions<FIELDS>`，五个新日期操作符，以及新的 `filter.search(query, options?)`。

- [ ] **Step 1: 写 Filter DSL 失败测试**

在 `filter.test.ts` 的导入中加入 `SearchMode` 与 `TimeUnit`，并加入以下测试。旧
`search(query, ...fields)` 的 `@ts-expect-error` 在实现前会因签名仍有效而导致类型测试
失败；新导出与新函数也会使 Vitest 编译失败。

```ts
it('builds search filters with explicit defaults and phrase mode', () => {
  expect(filter.search('wow')).toEqual({
    op: FilterOperator.SEARCH,
    query: 'wow',
    mode: SearchMode.TERMS,
    fields: [],
  });
  expect(
    filter.search('event sourcing', {
      mode: SearchMode.PHRASE,
      fields: ['state.title', 'state.description'],
    }),
  ).toEqual({
    op: FilterOperator.SEARCH,
    query: 'event sourcing',
    mode: SearchMode.PHRASE,
    fields: ['state.title', 'state.description'],
  });
});

const assertOldSearchSignatureRemoved = () => {
  // @ts-expect-error The new Filter DSL does not retain rest-field compatibility.
  filter.search('wow', 'state.name');
};
expectTypeOf(assertOldSearchSignatureRemoved).toBeFunction();

it.each([
  ['yesterday', filter.yesterday, FilterOperator.YESTERDAY],
  ['next month', filter.nextMonth, FilterOperator.NEXT_MONTH],
  ['last year', filter.lastYear, FilterOperator.LAST_YEAR],
  ['this year', filter.thisYear, FilterOperator.THIS_YEAR],
  ['next year', filter.nextYear, FilterOperator.NEXT_YEAR],
] as const)('builds %s filters', (_name, create, op) => {
  expect(create('createdAt')).toEqual({
    op,
    field: 'createdAt',
    timeUnit: TimeUnit.MILLISECONDS,
  });
});

it('emits every Wow relative-time unit', () => {
  expect(
    Object.values(TimeUnit).map(timeUnit =>
      filter.today('createdAt', { timeUnit }),
    ),
  ).toEqual(
    Object.values(TimeUnit).map(timeUnit => ({
      op: FilterOperator.TODAY,
      field: 'createdAt',
      timeUnit,
    })),
  );
});
```

在现有非法参数表中加入：

```ts
[
  'invalid search mode',
  () => Reflect.apply(filter.search, null, ['wow', { mode: 'INVALID' }]),
],
[
  'invalid time unit',
  () => filter.today('createdAt', { timeUnit: 'INVALID' as TimeUnit }),
],
```

- [ ] **Step 2: 运行测试并确认 RED**

Run:

```bash
pnpm --filter @ahoo-wang/fetcher-wow exec vitest run test/query/filter.test.ts
pnpm --filter @ahoo-wang/fetcher-wow test:type
```

Expected: FAIL，报告 `SearchMode`、`TimeUnit`、`yesterday` 等导出不存在，并报告旧
`search` 调用上的 `@ts-expect-error` 未使用。

- [ ] **Step 3: 实现最新 Filter 线类型与 options 校验**

在 `FilterOperator` 中加入：

```ts
YESTERDAY = 'YESTERDAY',
NEXT_MONTH = 'NEXT_MONTH',
LAST_YEAR = 'LAST_YEAR',
THIS_YEAR = 'THIS_YEAR',
NEXT_YEAR = 'NEXT_YEAR',
```

在 `StringComparison` 后加入：

```ts
export enum SearchMode {
  TERMS = 'TERMS',
  PHRASE = 'PHRASE',
}

export enum TimeUnit {
  NANOSECONDS = 'NANOSECONDS',
  MICROSECONDS = 'MICROSECONDS',
  MILLISECONDS = 'MILLISECONDS',
  SECONDS = 'SECONDS',
  MINUTES = 'MINUTES',
  HOURS = 'HOURS',
  DAYS = 'DAYS',
}
```

将搜索与相对时间 options 改为：

```ts
export type SearchFilter<FIELDS extends string = string> = {
  op: FilterOperator.SEARCH;
  query: string;
  fields?: LogicalField<FIELDS>[];
  mode?: SearchMode;
};

export interface SearchFilterOptions<FIELDS extends string = string> {
  fields?: readonly LogicalField<FIELDS>[];
  mode?: SearchMode;
}

export interface RelativeTimeFilterOptions {
  zoneId?: string;
  datePattern?: string;
  timeUnit?: TimeUnit;
}
```

让 `validateRelativeTimeOptions` 始终返回合法 `timeUnit`：

```ts
function validateRelativeTimeOptions({
  zoneId,
  datePattern,
  timeUnit = TimeUnit.MILLISECONDS,
}: RelativeTimeFilterOptions): RelativeTimeFilterOptions & {
  timeUnit: TimeUnit;
} {
  if (zoneId !== undefined) {
    if (typeof zoneId !== 'string' || !zoneId.trim()) {
      throw new TypeError('zoneId cannot be blank.');
    }
    if (
      OFFSET_ZONE_CANDIDATE_PATTERN.test(zoneId) &&
      !isValidOffsetZone(zoneId)
    ) {
      throw new TypeError(`zoneId is invalid: [${zoneId}].`);
    }
  }
  if (datePattern !== undefined) validateDatePattern(datePattern);
  if (!Object.values(TimeUnit).includes(timeUnit)) {
    throw new TypeError(`timeUnit is invalid: [${String(timeUnit)}].`);
  }
  return {
    ...(zoneId === undefined ? {} : { zoneId }),
    ...(datePattern === undefined ? {} : { datePattern }),
    timeUnit,
  };
}
```

- [ ] **Step 4: 替换 search 并补齐五个日期构造函数**

用以下函数替换旧的 rest-fields `search`：

```ts
search<FIELDS extends string>(
  query: string,
  {
    fields = [],
    mode = SearchMode.TERMS,
  }: SearchFilterOptions<FIELDS> = {},
): SearchFilter<FIELDS> {
  if (typeof query !== 'string' || !query.trim()) {
    throw new TypeError('SEARCH query cannot be blank.');
  }
  if (!Object.values(SearchMode).includes(mode)) {
    throw new TypeError(`SEARCH mode is invalid: [${String(mode)}].`);
  }
  return {
    op: FilterOperator.SEARCH,
    query,
    mode,
    fields: fields.map(logicalField),
  };
},
```

把五个新 operator 加入 `CalendarFilter` 联合，并在 `filter` 对象中加入：

```ts
yesterday<FIELDS extends string>(
  field: FIELDS,
  options: RelativeTimeFilterOptions = {},
): CalendarFilter<FIELDS> {
  return {
    ...validateRelativeTimeOptions(options),
    op: FilterOperator.YESTERDAY,
    field: logicalField(field),
  };
},
nextMonth<FIELDS extends string>(
  field: FIELDS,
  options: RelativeTimeFilterOptions = {},
): CalendarFilter<FIELDS> {
  return {
    ...validateRelativeTimeOptions(options),
    op: FilterOperator.NEXT_MONTH,
    field: logicalField(field),
  };
},
lastYear<FIELDS extends string>(
  field: FIELDS,
  options: RelativeTimeFilterOptions = {},
): CalendarFilter<FIELDS> {
  return {
    ...validateRelativeTimeOptions(options),
    op: FilterOperator.LAST_YEAR,
    field: logicalField(field),
  };
},
thisYear<FIELDS extends string>(
  field: FIELDS,
  options: RelativeTimeFilterOptions = {},
): CalendarFilter<FIELDS> {
  return {
    ...validateRelativeTimeOptions(options),
    op: FilterOperator.THIS_YEAR,
    field: logicalField(field),
  };
},
nextYear<FIELDS extends string>(
  field: FIELDS,
  options: RelativeTimeFilterOptions = {},
): CalendarFilter<FIELDS> {
  return {
    ...validateRelativeTimeOptions(options),
    op: FilterOperator.NEXT_YEAR,
    field: logicalField(field),
  };
},
```

现有 `today`、`beforeToday`、`tomorrow`、week/month、recent/earlier days 都继续展开
`validateRelativeTimeOptions`，所以其测试期望值必须加入
`timeUnit: TimeUnit.MILLISECONDS`；显式 `timeUnit` 时使用调用值。

- [ ] **Step 5: 运行 Filter 测试、类型检查与格式检查**

Run:

```bash
pnpm --filter @ahoo-wang/fetcher-wow exec vitest run test/query/filter.test.ts
pnpm --filter @ahoo-wang/fetcher-wow test:type
pnpm --package=prettier dlx prettier --check packages/wow/src/query/filter.ts packages/wow/test/query/filter.test.ts
```

Expected: PASS；旧 rest-fields 搜索只出现在带 `@ts-expect-error` 的破坏性边界测试中。

- [ ] **Step 6: 提交 Filter DSL**

```bash
git add packages/wow/src/query/filter.ts packages/wow/test/query/filter.test.ts
git commit -m "feat(wow): align filter DSL with Wow query API"
```

### Task 2: 实现类型安全的 Aggregation DSL

**Files:**

- Modify: `packages/wow/test/query/aggregation.test.ts:14-179`
- Modify: `packages/wow/src/query/aggregation.ts:14-115`

**Interfaces:**

- Consumes: Task 1 的 `filter`、`ElementFilterExpression<FIELDS>`、`FilterExpression<FIELDS>` 与 `LogicalField<FIELDS>`。
- Produces: `AggregationExpressionOperator`、递归 `AggregationExpression<FIELDS>`、字段泛型 Group/Metric、`AggregationQuery<ROOT_FIELDS, AGGREGATION_FIELDS>` 与扁平 `aggregation` 函数对象。

- [ ] **Step 1: 写 Aggregation DSL 失败测试**

替换 `aggregation.test.ts` 的枚举和完整请求测试，使其导入 `aggregation` 与
`AggregationExpressionOperator`，并验证以下对象：

```ts
type RootFields = 'state.status' | 'state.orders';
type ItemFields = 'status' | 'quantity' | 'productId' | 'amount' | 'createdAt';

it('builds arithmetic aggregation JSON through the functional DSL', () => {
  const revenue = aggregation.multiply(
    aggregation.field<ItemFields>('amount'),
    aggregation.constant(1.2),
  );
  const query: AggregationQuery<RootFields, ItemFields> = {
    filter: filter.eq('state.status', 'COMPLETED'),
    elements: [
      aggregation.element('state.orders', filter.eq('status', 'PAID')),
    ],
    groupBy: [
      aggregation.terms('productId', 'product'),
      aggregation.histogram('amount', {
        interval: 10,
        alias: 'amountBand',
      }),
      aggregation.dateHistogram('createdAt', {
        unit: AggregationDateUnit.MONTH,
        alias: 'month',
      }),
    ],
    metrics: [aggregation.count('count'), aggregation.sum(revenue, 'revenue')],
    sort: [{ field: 'revenue', direction: SortDirection.DESC }],
    limit: 20,
  };

  expect(query).toStrictEqual({
    filter: { op: 'EQ', field: 'state.status', value: 'COMPLETED' },
    elements: [
      {
        path: 'state.orders',
        filter: { op: 'EQ', field: 'status', value: 'PAID' },
      },
    ],
    groupBy: [
      { type: 'TERMS', field: 'productId', alias: 'product' },
      { type: 'HISTOGRAM', field: 'amount', interval: 10, alias: 'amountBand' },
      {
        type: 'DATE_HISTOGRAM',
        field: 'createdAt',
        unit: 'MONTH',
        alias: 'month',
        timeZone: 'UTC',
      },
    ],
    metrics: [
      { type: 'COUNT', alias: 'count' },
      {
        type: 'NUMERIC',
        function: 'SUM',
        expression: {
          type: 'BINARY',
          operator: 'MULTIPLY',
          left: { type: 'FIELD', field: 'amount' },
          right: { type: 'CONSTANT', value: 1.2 },
        },
        alias: 'revenue',
      },
    ],
    sort: [{ field: 'revenue', direction: 'DESC' }],
    limit: 20,
  });
});
```

把枚举断言改为：

```ts
expect(Object.values(AggregationExpressionType)).toEqual([
  'FIELD',
  'CONSTANT',
  'BINARY',
]);
expect(Object.values(AggregationExpressionOperator)).toEqual([
  'ADD',
  'SUBTRACT',
  'MULTIPLY',
  'DIVIDE',
]);
```

加入其余算术与数值指标 helper 的精确 JSON 测试：

```ts
it('builds every arithmetic and numeric metric helper', () => {
  const field = aggregation.field<'amount'>('amount');
  const constant = aggregation.constant(2);

  expect([
    aggregation.add(field, constant),
    aggregation.subtract(field, constant),
    aggregation.divide(field, constant),
  ]).toEqual([
    {
      type: 'BINARY',
      operator: 'ADD',
      left: { type: 'FIELD', field: 'amount' },
      right: { type: 'CONSTANT', value: 2 },
    },
    {
      type: 'BINARY',
      operator: 'SUBTRACT',
      left: { type: 'FIELD', field: 'amount' },
      right: { type: 'CONSTANT', value: 2 },
    },
    {
      type: 'BINARY',
      operator: 'DIVIDE',
      left: { type: 'FIELD', field: 'amount' },
      right: { type: 'CONSTANT', value: 2 },
    },
  ]);
  expect([
    aggregation.avg(field, 'average'),
    aggregation.min(field, 'minimum'),
    aggregation.max(field, 'maximum'),
  ]).toEqual([
    { type: 'NUMERIC', function: 'AVG', expression: field, alias: 'average' },
    { type: 'NUMERIC', function: 'MIN', expression: field, alias: 'minimum' },
    { type: 'NUMERIC', function: 'MAX', expression: field, alias: 'maximum' },
  ]);
});
```

把旧“允许省略 FIELD.type”测试替换为破坏性类型边界：

```ts
const assertFieldTypeIsRequired = () => {
  // @ts-expect-error FIELD expressions require their discriminator in 3.18.
  const expression: AggregationExpression = { field: 'amount' };
  void expression;
};
expectTypeOf(assertFieldTypeIsRequired).toBeFunction();
```

加入非法叶节点测试：

```ts
it.each([
  ['invalid field', () => aggregation.field('bad field')],
  ['non-finite constant', () => aggregation.constant(Number.NaN)],
  [
    'zero histogram interval',
    () => aggregation.histogram('amount', { interval: 0, alias: 'band' }),
  ],
  ['multi-segment alias', () => aggregation.terms('status', 'group.status')],
  ['reserved alias', () => aggregation.count('__wow_count')],
  [
    'blank time zone',
    () =>
      aggregation.dateHistogram('createdAt', {
        unit: AggregationDateUnit.DAY,
        alias: 'day',
        timeZone: ' ',
      }),
  ],
  [
    'root filter inside element',
    () => aggregation.element('state.items', filter.id('snapshot-1') as never),
  ],
])('rejects %s', (_name, create) => {
  expect(create).toThrow(TypeError);
});
```

- [ ] **Step 2: 运行 Aggregation 测试并确认 RED**

Run:

```bash
pnpm --filter @ahoo-wang/fetcher-wow exec vitest run test/query/aggregation.test.ts
pnpm --filter @ahoo-wang/fetcher-wow test:type
```

Expected: FAIL，报告 `aggregation`、`AggregationExpressionOperator` 与递归表达式类型
不存在，并报告旧 FIELD.type 可省略测试不再符合目标。

- [ ] **Step 3: 扩展聚合判别联合与字段泛型**

先把 `aggregation.ts` 的 Filter 导入改为运行时 `filter` 加类型导入：

```ts
import {
  filter,
  type ElementFilterExpression,
  type FilterExpression,
  type LogicalField,
} from './filter';
```

然后把现有类型改为：

```ts
export enum AggregationExpressionType {
  FIELD = 'FIELD',
  CONSTANT = 'CONSTANT',
  BINARY = 'BINARY',
}

export enum AggregationExpressionOperator {
  ADD = 'ADD',
  SUBTRACT = 'SUBTRACT',
  MULTIPLY = 'MULTIPLY',
  DIVIDE = 'DIVIDE',
}

interface AggregationGroupBase<FIELDS extends string = string> {
  field: LogicalField<FIELDS>;
  alias: string;
}

export interface FieldAggregationExpression<FIELDS extends string = string> {
  type: AggregationExpressionType.FIELD;
  field: LogicalField<FIELDS>;
}

export interface ConstantAggregationExpression {
  type: AggregationExpressionType.CONSTANT;
  value: number;
}

export interface BinaryAggregationExpression<FIELDS extends string = string> {
  type: AggregationExpressionType.BINARY;
  operator: AggregationExpressionOperator;
  left: AggregationExpression<FIELDS>;
  right: AggregationExpression<FIELDS>;
}

export type AggregationExpression<FIELDS extends string = string> =
  | FieldAggregationExpression<FIELDS>
  | ConstantAggregationExpression
  | BinaryAggregationExpression<FIELDS>;
```

给 `TermsAggregationGroup`、`HistogramAggregationGroup`、
`DateHistogramAggregationGroup`、`AggregationGroup`、`NumericAggregationMetric` 与
`AggregationMetric` 加同一个 `FIELDS extends string = string` 泛型。将顶层查询改为：

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
}
```

- [ ] **Step 4: 实现扁平 aggregation 函数对象**

加入 options 与内部校验：

```ts
export interface HistogramAggregationOptions {
  interval: number;
  alias: string;
}

export interface DateHistogramAggregationOptions {
  unit: AggregationDateUnit;
  alias: string;
  timeZone?: string;
}

const LOGICAL_FIELD_PATTERN =
  /^@?[A-Za-z_][A-Za-z0-9_-]*(\.(?:@?[A-Za-z_][A-Za-z0-9_-]*|[0-9]+))*$/;

function aggregationField<FIELDS extends string>(field: FIELDS): FIELDS {
  if (typeof field !== 'string' || !LOGICAL_FIELD_PATTERN.test(field)) {
    throw new TypeError(`Logical field is invalid: [${String(field)}].`);
  }
  return field;
}

function aggregationAlias(alias: string): string {
  aggregationField(alias);
  if (alias.includes('.')) {
    throw new TypeError('aggregation alias must contain one segment.');
  }
  if (alias.startsWith('__wow')) {
    throw new TypeError(
      'aggregation alias must not use the reserved __wow prefix.',
    );
  }
  return alias;
}
```

实现表达式和指标内部函数：

```ts
function binary<FIELDS extends string>(
  operator: AggregationExpressionOperator,
  left: AggregationExpression<FIELDS>,
  right: AggregationExpression<FIELDS>,
): BinaryAggregationExpression<FIELDS> {
  return { type: AggregationExpressionType.BINARY, operator, left, right };
}

function numeric<FIELDS extends string>(
  fn: AggregationFunction,
  expression: AggregationExpression<FIELDS>,
  alias: string,
): NumericAggregationMetric<FIELDS> {
  return {
    type: AggregationMetricType.NUMERIC,
    function: fn,
    expression,
    alias: aggregationAlias(alias),
  };
}
```

公开的扁平对象必须包含完整函数，不新增 class 或嵌套 namespace：

```ts
export const aggregation = {
  element(
    path: string,
    predicate?: ElementFilterExpression,
  ): AggregationElement {
    const validPath = aggregationField(path);
    if (predicate === undefined) return { path: validPath };
    filter.elementMatch(path, predicate);
    return { path: validPath, filter: predicate };
  },
  field<FIELDS extends string>(
    field: FIELDS,
  ): FieldAggregationExpression<FIELDS> {
    return {
      type: AggregationExpressionType.FIELD,
      field: aggregationField(field),
    };
  },
  constant(value: number): ConstantAggregationExpression {
    if (!Number.isFinite(value)) {
      throw new TypeError('aggregation constant must be finite.');
    }
    return { type: AggregationExpressionType.CONSTANT, value };
  },
  add: <FIELDS extends string>(
    left: AggregationExpression<FIELDS>,
    right: AggregationExpression<FIELDS>,
  ) => binary(AggregationExpressionOperator.ADD, left, right),
  subtract: <FIELDS extends string>(
    left: AggregationExpression<FIELDS>,
    right: AggregationExpression<FIELDS>,
  ) => binary(AggregationExpressionOperator.SUBTRACT, left, right),
  multiply: <FIELDS extends string>(
    left: AggregationExpression<FIELDS>,
    right: AggregationExpression<FIELDS>,
  ) => binary(AggregationExpressionOperator.MULTIPLY, left, right),
  divide: <FIELDS extends string>(
    left: AggregationExpression<FIELDS>,
    right: AggregationExpression<FIELDS>,
  ) => binary(AggregationExpressionOperator.DIVIDE, left, right),
  terms<FIELDS extends string>(
    field: FIELDS,
    alias: string,
  ): TermsAggregationGroup<FIELDS> {
    return {
      type: AggregationGroupType.TERMS,
      field: aggregationField(field),
      alias: aggregationAlias(alias),
    };
  },
  histogram<FIELDS extends string>(
    field: FIELDS,
    { interval, alias }: HistogramAggregationOptions,
  ): HistogramAggregationGroup<FIELDS> {
    if (!Number.isFinite(interval) || interval <= 0) {
      throw new TypeError(
        'histogram interval must be finite and greater than 0.',
      );
    }
    return {
      type: AggregationGroupType.HISTOGRAM,
      field: aggregationField(field),
      interval,
      alias: aggregationAlias(alias),
    };
  },
  dateHistogram<FIELDS extends string>(
    field: FIELDS,
    { unit, alias, timeZone = 'UTC' }: DateHistogramAggregationOptions,
  ): DateHistogramAggregationGroup<FIELDS> {
    if (typeof timeZone !== 'string' || !timeZone.trim()) {
      throw new TypeError('date histogram timeZone cannot be blank.');
    }
    return {
      type: AggregationGroupType.DATE_HISTOGRAM,
      field: aggregationField(field),
      unit,
      alias: aggregationAlias(alias),
      timeZone,
    };
  },
  count(alias: string): CountAggregationMetric {
    return {
      type: AggregationMetricType.COUNT,
      alias: aggregationAlias(alias),
    };
  },
  sum: <FIELDS extends string>(
    expression: AggregationExpression<FIELDS>,
    alias: string,
  ) => numeric(AggregationFunction.SUM, expression, alias),
  avg: <FIELDS extends string>(
    expression: AggregationExpression<FIELDS>,
    alias: string,
  ) => numeric(AggregationFunction.AVG, expression, alias),
  min: <FIELDS extends string>(
    expression: AggregationExpression<FIELDS>,
    alias: string,
  ) => numeric(AggregationFunction.MIN, expression, alias),
  max: <FIELDS extends string>(
    expression: AggregationExpression<FIELDS>,
    alias: string,
  ) => numeric(AggregationFunction.MAX, expression, alias),
};
```

- [ ] **Step 5: 锁定字段泛型边界**

在静态边界测试中加入：

```ts
const assertAggregationFields = () => {
  const valid: AggregationQuery<RootFields, ItemFields> = {
    filter: filter.eq('state.status', 'PAID'),
    groupBy: [aggregation.terms('productId', 'product')],
    metrics: [aggregation.sum(aggregation.field('amount'), 'total')],
  };
  const invalidAggregationField: AggregationQuery<RootFields, ItemFields> = {
    filter: filter.eq('state.status', 'PAID'),
    groupBy: [
      // @ts-expect-error unknown is not an ItemFields member.
      aggregation.terms('unknown', 'unknown'),
    ],
    metrics: [aggregation.count('count')],
  };
  void valid;
  void invalidAggregationField;
};
expectTypeOf(assertAggregationFields).toBeFunction();
```

- [ ] **Step 6: 运行 Aggregation 测试、类型检查与格式检查**

Run:

```bash
pnpm --filter @ahoo-wang/fetcher-wow exec vitest run test/query/aggregation.test.ts
pnpm --filter @ahoo-wang/fetcher-wow test:type
pnpm --package=prettier dlx prettier --check packages/wow/src/query/aggregation.ts packages/wow/test/query/aggregation.test.ts
```

Expected: PASS；表达式枚举为三个值，所有 helper 生成完整 discriminator，非法叶节点
抛 `TypeError`。

- [ ] **Step 7: 提交 Aggregation DSL**

```bash
git add packages/wow/src/query/aggregation.ts packages/wow/test/query/aggregation.test.ts
git commit -m "feat(wow): add functional aggregation DSL"
```

### Task 3: 让 Snapshot 客户端接受双字段域并证明 body 透传

**Files:**

- Modify: `packages/wow/test/query/snapshot/snapshotQueryApi.test.ts:14-77`
- Modify: `packages/wow/src/query/snapshot/snapshotQueryApi.ts:32-48`
- Modify: `packages/wow/src/query/snapshot/snapshotQueryClient.ts:129-150`

**Interfaces:**

- Consumes: Task 2 的 `AggregationQuery<ROOT_FIELDS, AGGREGATION_FIELDS>` 与 `aggregation`。
- Produces: 保持结果行泛型优先的 `aggregate<Row, AGGREGATION_FIELDS>` 与 `aggregateStream<Row, AGGREGATION_FIELDS>`。

- [ ] **Step 1: 写客户端类型与 body 透传失败测试**

在 `snapshotQueryApi.test.ts` 中加入 `NamedFetcher`、`vi`、`aggregation`、`filter` 与
`SnapshotQueryClient` 运行时导入，然后把查询改为双字段域：

```ts
import { NamedFetcher } from '@ahoo-wang/fetcher';
import type { SnapshotQueryApi } from '../../../src';
import {
  aggregation,
  filter,
  SnapshotQueryClient,
  type AggregationQuery,
  type DynamicDocument,
} from '../../../src';
```

```ts
type RootFields = 'state.status';
type ItemFields = 'productId' | 'amount';
const query: AggregationQuery<RootFields, ItemFields> = {
  filter: filter.eq('state.status', 'PAID'),
  groupBy: [aggregation.terms('productId', 'product')],
  metrics: [aggregation.sum(aggregation.field('amount'), 'total')],
};
```

把以上 types 与 `query` 放在 `describe` 作用域，使类型测试和 body 透传测试使用同一个
请求对象。现有结果类型断言改为：

```ts
const assertClientTypes = (
  client: SnapshotQueryClient<unknown, RootFields>,
) => {
  expectTypeOf(client.aggregate<AggregationRow>(query)).toEqualTypeOf<
    Promise<AggregationRow[]>
  >();
  expectTypeOf(client.aggregateStream<AggregationRow>(query)).toEqualTypeOf<
    Promise<ReadableStream<JsonServerSentEvent<AggregationRow>>>
  >();
};
```

加入真实 decorator/fetcher 管线的 unit test：

```ts
it('forwards aggregation DSL output as the request body', async () => {
  const fetcher = new NamedFetcher('aggregation-body-test');
  const exchange = vi
    .spyOn(fetcher.interceptors, 'exchange')
    .mockImplementation(async current => {
      const body =
        typeof current.request.body === 'string'
          ? JSON.parse(current.request.body)
          : current.request.body;
      expect(body).toEqual(query);
      current.extractResult = vi.fn().mockResolvedValue([]);
      return current;
    });
  const client = new SnapshotQueryClient<unknown, RootFields>({
    basePath: '/order',
    fetcher,
  });

  await client.aggregate(query);

  expect(exchange).toHaveBeenCalledOnce();
});
```

- [ ] **Step 2: 运行 Snapshot 测试并确认 RED**

Run:

```bash
pnpm --filter @ahoo-wang/fetcher-wow exec vitest run test/query/snapshot/snapshotQueryApi.test.ts
pnpm --filter @ahoo-wang/fetcher-wow test:type
```

Expected: FAIL，当前客户端把第二字段域默认成根字段域，拒绝
`AggregationQuery<RootFields, ItemFields>`。

- [ ] **Step 3: 扩展接口与具体客户端的类型参数**

在 `SnapshotQueryApi` 和 `SnapshotQueryClient` 的两个聚合方法上使用同一签名；保留
`Row` 为第一个泛型，使现有结果行写法仍直观，但第二泛型默认 `string`，不承担旧
`filter.ts` / `aggregation.ts` 的兼容义务：

```ts
aggregate?<
  Row extends DynamicDocument = DynamicDocument,
  AGGREGATION_FIELDS extends string = string,
>(
  query: AggregationQuery<FIELDS, AGGREGATION_FIELDS>,
  attributes?: Record<string, any>,
  abortController?: AbortController,
): Promise<Row[]>;

aggregateStream?<
  Row extends DynamicDocument = DynamicDocument,
  AGGREGATION_FIELDS extends string = string,
>(
  query: AggregationQuery<FIELDS, AGGREGATION_FIELDS>,
  attributes?: Record<string, any>,
  abortController?: AbortController,
): Promise<ReadableStream<JsonServerSentEvent<Row>>>;
```

具体类的方法移除 `?`，保留原有 decorator、endpoint、headers、resultExtractor 与函数
体，只替换泛型和 query 参数类型。

- [ ] **Step 4: 运行 Snapshot、Aggregation 与类型测试**

Run:

```bash
pnpm --filter @ahoo-wang/fetcher-wow exec vitest run test/query/aggregation.test.ts test/query/snapshot/snapshotQueryApi.test.ts
pnpm --filter @ahoo-wang/fetcher-wow test:type
```

Expected: PASS；mock exchange 收到与 `query` 深度相等的 body，JSON 与 SSE 返回类型保持
不变。

- [ ] **Step 5: 提交客户端类型接线**

```bash
git add packages/wow/src/query/snapshot/snapshotQueryApi.ts packages/wow/src/query/snapshot/snapshotQueryClient.ts packages/wow/test/query/snapshot/snapshotQueryApi.test.ts
git commit -m "feat(wow): type aggregation field scopes in snapshot client"
```

### Task 4: 同步 README、Skill 与双语 Wiki

**Files:**

- Modify: `packages/wow/README.md:155-185,311-479`
- Modify: `packages/wow/README.zh-CN.md:155-176,302-466`
- Modify: `skills/fetcher-wow-cqrs/references/api.md:74-164,311-430,544-676`
- Modify: `wiki/packages/wow.md:250-355,469-472`
- Modify: `wiki/zh/packages/wow.md:249-351,464-467`

**Interfaces:**

- Consumes: Tasks 1-3 的最终导出、函数签名、默认值和双字段域泛型。
- Produces: 五份只引用真实 `3.18.0` API 的同步文档；`Condition` 章节内容保持现状。

- [ ] **Step 1: 记录旧 API 文档命中作为 RED**

Run:

```bash
rg -n "filter\.search\('wow', 'state\.name'\)|expression: \{ field:|type: AggregationGroupType\.TERMS" packages/wow/README.md packages/wow/README.zh-CN.md skills/fetcher-wow-cqrs/references/api.md wiki/packages/wow.md wiki/zh/packages/wow.md
```

Expected: 至少命中五个目标文档，证明它们仍展示旧搜索签名或手写旧聚合表达式。

- [ ] **Step 2: 更新中英文 README 的主示例**

两份 README 使用同一 TypeScript 事实，文字分别为英文和中文。Filter 示例必须使用：

```ts
filter.search('event sourcing', {
  mode: SearchMode.PHRASE,
  fields: ['state.title', 'state.description'],
});

filter.yesterday('state.createdAt', {
  zoneId: 'Asia/Shanghai',
  timeUnit: TimeUnit.MILLISECONDS,
});
```

聚合示例必须使用：

```ts
const revenue = aggregation.multiply(
  aggregation.field<ItemFields>('price'),
  aggregation.field<ItemFields>('quantity'),
);

const aggregationQuery: AggregationQuery<CartFields, ItemFields> = {
  filter: filter.eq('state.status', 'COMPLETED'),
  elements: [aggregation.element('state.items', filter.gt('quantity', 0))],
  groupBy: [aggregation.terms('productId', 'product')],
  metrics: [
    aggregation.count('itemCount'),
    aggregation.sum(revenue, 'revenue'),
  ],
};
```

README 导入列表加入示例实际使用的 `aggregation`、`SearchMode` 与 `TimeUnit`；删除
“FIELD type 可省略”等过时说明。`AggregationExpressionOperator` 的完整导入与枚举值
放在 Skill API reference。不要删除或改写 `Condition Builder` 章节。

- [ ] **Step 3: 更新 Skill API reference**

在统一导入与目录中加入：

```ts
aggregation,
AggregationExpressionOperator,
SearchMode,
TimeUnit,
type SearchFilterOptions,
type HistogramAggregationOptions,
type DateHistogramAggregationOptions,
```

Filter 章节完整记录：

```ts
filter.search(query, options?: SearchFilterOptions)
filter.yesterday(field, options?)
filter.nextMonth(field, options?)
filter.lastYear(field, options?)
filter.thisYear(field, options?)
filter.nextYear(field, options?)
```

Aggregation 章节记录 `AggregationQuery<ROOT_FIELDS, AGGREGATION_FIELDS =
ROOT_FIELDS>`、三个表达式 discriminator、四个算术 operator、全部 `aggregation.*`
函数及默认 `UTC`。明确整体 alias/sort/depth 约束由 Wow 服务端校验。

- [ ] **Step 4: 同步更新英文与中文 Wiki**

将 Wiki 的旧 Filter 搜索与手写 Aggregation JSON 替换成 README 的相同调用形状；导出
表加入 `aggregation`、`SearchMode`、`TimeUnit`、
`AggregationExpressionOperator`。英文和中文页面必须拥有相同的代码示例、默认值与
非目标说明。不要修改 frontmatter、导航、Mermaid 图或生成产物。

- [ ] **Step 5: 验证文档无旧签名且 Wiki 可构建**

Run:

```bash
if rg -n "filter\.search\('wow', 'state\.name'\)|expression: \{ field:" packages/wow/README.md packages/wow/README.zh-CN.md skills/fetcher-wow-cqrs/references/api.md wiki/packages/wow.md wiki/zh/packages/wow.md; then exit 1; fi
pnpm --package=prettier dlx prettier --check packages/wow/README.md packages/wow/README.zh-CN.md skills/fetcher-wow-cqrs/references/api.md wiki/packages/wow.md wiki/zh/packages/wow.md
pnpm --dir wiki build
git diff --check
```

Expected: 旧签名扫描无输出，Prettier、VitePress 与 diff 检查全部 PASS；不生成受版本
控制的 `llms-full.txt`、`llms.txt` 或 `.vitepress/dist` 变更。

- [ ] **Step 6: 提交文档同步**

```bash
git add packages/wow/README.md packages/wow/README.zh-CN.md skills/fetcher-wow-cqrs/references/api.md wiki/packages/wow.md wiki/zh/packages/wow.md
git commit -m "docs(wow): document query DSL 3.18"
```

### Task 5: 锁步更新 3.18.0 并通过全部门禁

**Files:**

- Modify: `package.json`
- Modify: `integration-test/package.json`
- Modify: `packages/cosec/package.json`
- Modify: `packages/decorator/package.json`
- Modify: `packages/eventbus/package.json`
- Modify: `packages/eventstream/package.json`
- Modify: `packages/fetcher/package.json`
- Modify: `packages/generator/package.json`
- Modify: `packages/openai/package.json`
- Modify: `packages/openapi/package.json`
- Modify: `packages/react/package.json`
- Modify: `packages/storage/package.json`
- Modify: `packages/viewer/package.json`
- Modify: `packages/wow/package.json`

**Interfaces:**

- Consumes: Tasks 1-4 的实现、测试与文档。
- Produces: 所有发布包和 integration-test 均声明 `3.18.0`，全仓门禁通过的候选提交。

- [ ] **Step 1: 运行仓库版本脚本**

```bash
pnpm update-version 3.18.0
```

Expected: 输出根、12 个 `packages/*` 和 `integration-test` 均更新到 `3.18.0`；
`pnpm-lock.yaml` 不发生变化。

- [ ] **Step 2: 精确验证所有版本文件**

Run:

```bash
node <<'NODE'
const fs = require('node:fs');
const files = [
  'package.json',
  'integration-test/package.json',
  ...fs
    .readdirSync('packages', { withFileTypes: true })
    .filter(entry => entry.isDirectory())
    .map(entry => `packages/${entry.name}/package.json`)
    .filter(file => fs.existsSync(file)),
];
for (const file of files) {
  const { version } = JSON.parse(fs.readFileSync(file, 'utf8'));
  if (version !== '3.18.0') throw new Error(`${file}: ${version}`);
}
console.log(`verified ${files.length} package versions`);
NODE
```

Expected: `verified 14 package versions`。

- [ ] **Step 3: 运行格式、lint 与定向门禁**

Run:

```bash
pnpm --package=prettier dlx prettier --write packages/wow/src/query/filter.ts packages/wow/src/query/aggregation.ts packages/wow/src/query/snapshot/snapshotQueryApi.ts packages/wow/src/query/snapshot/snapshotQueryClient.ts packages/wow/test/query/filter.test.ts packages/wow/test/query/aggregation.test.ts packages/wow/test/query/snapshot/snapshotQueryApi.test.ts packages/wow/README.md packages/wow/README.zh-CN.md skills/fetcher-wow-cqrs/references/api.md wiki/packages/wow.md wiki/zh/packages/wow.md
pnpm lint
pnpm --filter @ahoo-wang/fetcher-wow exec vitest run test/query/filter.test.ts test/query/aggregation.test.ts test/query/snapshot/snapshotQueryApi.test.ts
pnpm --filter @ahoo-wang/fetcher-wow test:type
```

Expected: 全部 PASS；lint 不产生任务范围外的源文件变更。

- [ ] **Step 4: 运行完整构建、单元测试与 Wiki 构建**

Run:

```bash
pnpm build
pnpm test:unit
pnpm --dir wiki build
```

Expected: 三条命令退出码均为 0。`pnpm test:unit` 包含 Wow Vitest 与
`test/tsconfig.types.json` 类型检查。

- [ ] **Step 5: 检查范围、生成产物与 diff**

Run:

```bash
git diff --check
git diff --name-only origin/main...HEAD -- packages/generator/src packages/viewer/src packages/react/src
git status --short
```

Expected:

- `git diff --check` 无输出。
- generator/viewer/react runtime 路径扫描无输出。
- `git status --short` 只包含 Task 5 的 14 个 `package.json`；build、coverage、Wiki dist
  与 llms 产物均不在状态列表中。

- [ ] **Step 6: 提交版本更新**

```bash
git add package.json integration-test/package.json packages/*/package.json
git commit -m "chore(release): bump version to 3.18.0"
```

- [ ] **Step 7: 记录最终候选证据**

Run:

```bash
git status --short --branch
git log --oneline -5
git diff --check origin/main...HEAD
```

Expected: 工作树干净；最近提交依次覆盖 Filter、Aggregation、Snapshot 类型接线、文档
和版本；range diff 检查通过。不要在本任务中 push、创建 PR、发布 npm 或部署。
