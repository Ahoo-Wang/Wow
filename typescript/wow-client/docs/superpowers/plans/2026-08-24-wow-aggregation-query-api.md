# Fetcher Wow 聚合查询 API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 `@ahoo-wang/fetcher-wow` 中对齐 Wow 最新 `AggregationQuery` 线协议，并通过现有快照查询客户端提供 JSON 与 SSE 两种聚合查询。

**Architecture:** 新增一个只负责聚合请求类型的 `query/aggregation.ts`，复用现有 `FilterExpression`、`ElementFilterExpression`、`LogicalField` 与 `FieldSort`。在既有 `SnapshotQueryApi` 上声明可选聚合方法，`SnapshotQueryClient` 提供必需具体方法；两个方法共用 `snapshot/aggregation` 路径，JSON 使用默认提取器，SSE 使用已有 `JsonEventStreamResultExtractor`。

**Tech Stack:** TypeScript strict mode、Fetcher decorators、Fetcher eventstream、Vitest、TypeScript compiler、Vite、ESLint、pnpm。

**Spec:** `packages/wow/docs/superpowers/specs/2026-08-24-wow-aggregation-query-api-design.md`

## Global Constraints

- 只修改 `packages/wow`；不修改 generator、integration-test、Skill、Wiki 或其他 package。
- 不新增依赖、客户端、工厂方法、DSL、构建器、运行时查询校验或配置项。
- 根 filter 使用 `FilterExpression<FIELDS>`；Element、group 与 metric 的相对字段保持通用 `LogicalField`。
- `metrics` 必填且在类型层非空；其他查询字段可省略，由 Wow 补默认值。
- 同一路由必须同时提供 `aggregate<Row>()` JSON 数组和 `aggregateStream<Row>()` SSE 流。
- `SnapshotQueryApi` 的聚合成员可选，避免破坏下游 mock 与 adapter；`SnapshotQueryClient` 的聚合方法保持必需。
- 结果泛型默认 `DynamicDocument`，只提供静态类型，不增加运行时解码。
- 所有源文件保留 Apache 2.0 头，使用单引号、分号、尾随逗号和 type-only imports。
- 每次提交前运行根 `pnpm test:unit`，并确认 `git diff --check` 通过。

---

## File Structure

- Create `packages/wow/src/query/aggregation.ts`: Wow 聚合查询的枚举、判别联合和请求类型。
- Modify `packages/wow/src/query/index.ts`: 从 package 公共入口导出聚合类型。
- Create `packages/wow/test/query/aggregation.test.ts`: 验证线协议字符串、完整请求形状和静态约束。
- Modify `packages/wow/test/tsconfig.types.json`: 把新增类型测试纳入 `tsc --noEmit`。
- Modify `packages/wow/src/query/snapshot/snapshotQueryApi.ts`: 增加 API 方法签名与聚合端点常量。
- Modify `packages/wow/src/query/snapshot/snapshotQueryClient.ts`: 用现有装饰器实现 JSON/SSE 两个声明式客户端方法。
- Modify `packages/wow/test/query/snapshot/snapshotQueryApi.test.ts`: 验证端点和两个方法的泛型返回类型。

### Task 1: 定义并导出 AggregationQuery 公共类型

**Files:**
- Create: `packages/wow/src/query/aggregation.ts`
- Modify: `packages/wow/src/query/index.ts:14-28`
- Create: `packages/wow/test/query/aggregation.test.ts`
- Modify: `packages/wow/test/tsconfig.types.json:10`

**Interfaces:**
- Consumes: `LogicalField`, `FilterExpression`, `ElementFilterExpression` from `query/filter.ts`; `FieldSort` from `query/sort.ts`.
- Produces: `AggregationQuery<FIELDS>`, `AggregationElement`, `AggregationGroup`, `AggregationMetric`, `AggregationExpression` and their public enums/subtypes for Task 2.

- [ ] **Step 1: Write the failing aggregation contract test**

Create `packages/wow/test/query/aggregation.test.ts` with the package Apache header and this body:

```ts
import { describe, expect, expectTypeOf, it } from 'vitest';
import {
  AggregationDateUnit,
  AggregationExpressionType,
  AggregationFunction,
  AggregationGroupType,
  AggregationMetricType,
  filter,
  SortDirection,
  type AggregationElement,
  type AggregationExpression,
  type AggregationQuery,
} from '../../src';

type RootFields = 'state.status' | 'state.orders';

describe('AggregationQuery', () => {
  it('uses the Wow wire enum values', () => {
    expect({
      group: Object.values(AggregationGroupType),
      metric: Object.values(AggregationMetricType),
      expression: Object.values(AggregationExpressionType),
      dateUnit: Object.values(AggregationDateUnit),
      function: Object.values(AggregationFunction),
    }).toEqual({
      group: ['TERMS', 'HISTOGRAM', 'DATE_HISTOGRAM'],
      metric: ['COUNT', 'NUMERIC'],
      expression: ['FIELD'],
      dateUnit: [
        'YEAR',
        'QUARTER',
        'MONTH',
        'WEEK',
        'DAY',
        'HOUR',
        'MINUTE',
        'SECOND',
      ],
      function: ['SUM', 'AVG', 'MIN', 'MAX'],
    });
  });

  it('represents the complete Wow request shape', () => {
    const query: AggregationQuery<RootFields> = {
      filter: filter.eq('state.status', 'COMPLETED'),
      elements: [
        {
          path: 'state.orders',
          filter: filter.eq('status', 'PAID'),
        },
        {
          path: 'lines',
          filter: filter.gt('quantity', 0),
        },
      ],
      groupBy: [
        {
          type: AggregationGroupType.TERMS,
          field: 'productId',
          alias: 'product',
        },
        {
          type: AggregationGroupType.HISTOGRAM,
          field: 'amount',
          alias: 'amountBand',
          interval: 10,
        },
        {
          type: AggregationGroupType.DATE_HISTOGRAM,
          field: 'createdAt',
          alias: 'month',
          unit: AggregationDateUnit.MONTH,
          timeZone: 'UTC',
        },
      ],
      metrics: [
        { type: AggregationMetricType.COUNT, alias: 'count' },
        {
          type: AggregationMetricType.NUMERIC,
          function: AggregationFunction.SUM,
          expression: { field: 'amount' },
          alias: 'total',
        },
      ],
      sort: [{ field: 'total', direction: SortDirection.DESC }],
      limit: 20,
    };

    expect(query).toStrictEqual({
      filter: { op: 'EQ', field: 'state.status', value: 'COMPLETED' },
      elements: [
        {
          path: 'state.orders',
          filter: { op: 'EQ', field: 'status', value: 'PAID' },
        },
        {
          path: 'lines',
          filter: { op: 'GT', field: 'quantity', value: 0 },
        },
      ],
      groupBy: [
        { type: 'TERMS', field: 'productId', alias: 'product' },
        {
          type: 'HISTOGRAM',
          field: 'amount',
          alias: 'amountBand',
          interval: 10,
        },
        {
          type: 'DATE_HISTOGRAM',
          field: 'createdAt',
          alias: 'month',
          unit: 'MONTH',
          timeZone: 'UTC',
        },
      ],
      metrics: [
        { type: 'COUNT', alias: 'count' },
        {
          type: 'NUMERIC',
          function: 'SUM',
          expression: { field: 'amount' },
          alias: 'total',
        },
      ],
      sort: [{ field: 'total', direction: 'DESC' }],
      limit: 20,
    });
  });

  it('accepts defaulted and explicit field expression types', () => {
    const expressions: AggregationExpression[] = [
      { field: 'amount' },
      { type: AggregationExpressionType.FIELD, field: 'amount' },
    ];

    expect(expressions).toEqual([
      { field: 'amount' },
      { type: 'FIELD', field: 'amount' },
    ]);
  });

  it('enforces the static request boundaries', () => {
    const assertInvalidQueries = () => {
      const invalidRootFilter: AggregationQuery<RootFields> = {
        // @ts-expect-error Root filter fields must belong to RootFields.
        filter: filter.eq('state.unknown', 'value'),
        metrics: [{ type: AggregationMetricType.COUNT, alias: 'count' }],
      };
      const invalidElementFilter: AggregationElement = {
        path: 'lines',
        // @ts-expect-error Root metadata filters cannot scope an Element.
        filter: filter.id('snapshot-1'),
      };
      const invalidEmptyMetrics: AggregationQuery = {
        // @ts-expect-error Aggregation metrics must be non-empty.
        metrics: [],
      };
      void invalidRootFilter;
      void invalidElementFilter;
      void invalidEmptyMetrics;
    };

    expectTypeOf(assertInvalidQueries).toBeFunction();
  });
});
```

Change `packages/wow/test/tsconfig.types.json` so the failing type test is included immediately:

```json
"include": [
  "../src/**/*",
  "./query/aggregation.test.ts",
  "./query/filter.test.ts"
]
```

- [ ] **Step 2: Run the focused test and type check to verify failure**

Run:

```bash
pnpm --filter @ahoo-wang/fetcher-wow exec vitest run test/query/aggregation.test.ts
pnpm --filter @ahoo-wang/fetcher-wow test:type
```

Expected: FAIL because `AggregationQuery` and its enums are not exported; `tsc` reports the same missing symbols.

- [ ] **Step 3: Add the minimal aggregation type module and export it**

Create `packages/wow/src/query/aggregation.ts` with the package Apache header and this body:

```ts
import type {
  ElementFilterExpression,
  FilterExpression,
  LogicalField,
} from './filter';
import type { FieldSort } from './sort';

export enum AggregationGroupType {
  TERMS = 'TERMS',
  HISTOGRAM = 'HISTOGRAM',
  DATE_HISTOGRAM = 'DATE_HISTOGRAM',
}

export enum AggregationMetricType {
  COUNT = 'COUNT',
  NUMERIC = 'NUMERIC',
}

export enum AggregationExpressionType {
  FIELD = 'FIELD',
}

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

export enum AggregationFunction {
  SUM = 'SUM',
  AVG = 'AVG',
  MIN = 'MIN',
  MAX = 'MAX',
}

export interface AggregationElement {
  path: LogicalField;
  filter?: ElementFilterExpression;
}

interface AggregationGroupBase {
  field: LogicalField;
  alias: string;
}

export interface TermsAggregationGroup extends AggregationGroupBase {
  type: AggregationGroupType.TERMS;
}

export interface HistogramAggregationGroup extends AggregationGroupBase {
  type: AggregationGroupType.HISTOGRAM;
  interval: number;
}

export interface DateHistogramAggregationGroup
  extends AggregationGroupBase {
  type: AggregationGroupType.DATE_HISTOGRAM;
  unit: AggregationDateUnit;
  timeZone?: string;
}

export type AggregationGroup =
  | TermsAggregationGroup
  | HistogramAggregationGroup
  | DateHistogramAggregationGroup;

export interface FieldAggregationExpression {
  type?: AggregationExpressionType.FIELD;
  field: LogicalField;
}

export type AggregationExpression = FieldAggregationExpression;

export interface CountAggregationMetric {
  type: AggregationMetricType.COUNT;
  alias: string;
}

export interface NumericAggregationMetric {
  type: AggregationMetricType.NUMERIC;
  function: AggregationFunction;
  expression: AggregationExpression;
  alias: string;
}

export type AggregationMetric =
  | CountAggregationMetric
  | NumericAggregationMetric;

export interface AggregationQuery<FIELDS extends string = string> {
  filter?: FilterExpression<FIELDS>;
  elements?: AggregationElement[];
  groupBy?: AggregationGroup[];
  metrics: [AggregationMetric, ...AggregationMetric[]];
  sort?: FieldSort[];
  limit?: number;
}
```

Add this export near the other query contracts in `packages/wow/src/query/index.ts`:

```ts
export * from './aggregation';
```

- [ ] **Step 4: Run formatting, focused checks and the package build**

Run:

```bash
pnpm --filter @ahoo-wang/fetcher-wow lint
pnpm --filter @ahoo-wang/fetcher-wow exec vitest run test/query/aggregation.test.ts
pnpm --filter @ahoo-wang/fetcher-wow test
pnpm --filter @ahoo-wang/fetcher-wow build
```

Expected: all commands PASS; the type test confirms the three `@ts-expect-error` sites are real errors.

- [ ] **Step 5: Run the repository unit gate and commit Task 1**

Run:

```bash
pnpm test:unit
git diff --check
git status --short
```

Expected: root unit tests PASS, `git diff --check` prints nothing, and status contains only the Task 1 files.

Commit:

```bash
git add packages/wow/src/query/aggregation.ts \
  packages/wow/src/query/index.ts \
  packages/wow/test/query/aggregation.test.ts \
  packages/wow/test/tsconfig.types.json
git commit -m "feat(wow): add aggregation query types"
```

### Task 2: 在 SnapshotQueryClient 上提供 JSON 与 SSE 聚合查询

**Files:**
- Modify: `packages/wow/src/query/snapshot/snapshotQueryApi.ts:14-118`
- Modify: `packages/wow/src/query/snapshot/snapshotQueryClient.ts:14-216`
- Modify: `packages/wow/test/query/snapshot/snapshotQueryApi.test.ts:14-30`
- Modify: `packages/wow/test/tsconfig.types.json:10-14`

**Interfaces:**
- Consumes: `AggregationQuery<FIELDS>` from Task 1, `DynamicDocument`, `JsonServerSentEvent`, existing Fetcher decorators and `JsonEventStreamResultExtractor`.
- Produces: `SnapshotQueryEndpointPaths.AGGREGATION`, optional `SnapshotQueryApi.aggregate<Row>()` and `SnapshotQueryApi.aggregateStream<Row>()`, and required matching `SnapshotQueryClient` methods.

- [ ] **Step 1: Write the failing endpoint and client type tests**

Replace the body of `packages/wow/test/query/snapshot/snapshotQueryApi.test.ts` after its Apache header with:

```ts
import type { JsonServerSentEvent } from '@ahoo-wang/fetcher-eventstream';
import { describe, expect, expectTypeOf, it } from 'vitest';
import type {
  SnapshotQueryApi,
  SnapshotQueryClient,
} from '../../../src';
import {
  AggregationMetricType,
  SnapshotQueryEndpointPaths,
  type AggregationQuery,
  type DynamicDocument,
} from '../../../src';

describe('SnapshotQueryEndpointPaths', () => {
  it('should have correct endpoint path values', () => {
    expect(SnapshotQueryEndpointPaths.SNAPSHOT_RESOURCE_NAME).toBe('snapshot');
    expect(SnapshotQueryEndpointPaths.AGGREGATION).toBe('snapshot/aggregation');
    expect(SnapshotQueryEndpointPaths.COUNT).toBe('snapshot/count');
    expect(SnapshotQueryEndpointPaths.LIST).toBe('snapshot/list');
    expect(SnapshotQueryEndpointPaths.LIST_STATE).toBe('snapshot/list/state');
    expect(SnapshotQueryEndpointPaths.PAGED).toBe('snapshot/paged');
    expect(SnapshotQueryEndpointPaths.PAGED_STATE).toBe('snapshot/paged/state');
    expect(SnapshotQueryEndpointPaths.SINGLE).toBe('snapshot/single');
    expect(SnapshotQueryEndpointPaths.SINGLE_STATE).toBe(
      'snapshot/single/state',
    );
  });

  it('exposes typed JSON and SSE aggregation results', () => {
    type RootFields = 'state.status';
    type AggregationRow = DynamicDocument & {
      product: string;
      total: number;
    };
    type SnapshotApiHasAggregationKeys =
      'aggregate' extends keyof SnapshotQueryApi<unknown, RootFields>
        ? 'aggregateStream' extends keyof SnapshotQueryApi<
            unknown,
            RootFields
          >
          ? true
          : false
        : false;
    type SnapshotApiRequiresAggregation = SnapshotQueryApi<
      unknown,
      RootFields
    > extends {
      aggregate: unknown;
      aggregateStream: unknown;
    }
      ? true
      : false;

    const query: AggregationQuery<RootFields> = {
      metrics: [{ type: AggregationMetricType.COUNT, alias: 'total' }],
    };
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

    expectTypeOf<SnapshotApiHasAggregationKeys>().toEqualTypeOf<true>();
    expectTypeOf<SnapshotApiRequiresAggregation>().toEqualTypeOf<false>();
    expectTypeOf(assertClientTypes).toBeFunction();
  });
});
```

Add the snapshot API test to `packages/wow/test/tsconfig.types.json`:

```json
"include": [
  "../src/**/*",
  "./query/aggregation.test.ts",
  "./query/filter.test.ts",
  "./query/snapshot/snapshotQueryApi.test.ts"
]
```

- [ ] **Step 2: Run the focused test and type check to verify failure**

Run:

```bash
pnpm --filter @ahoo-wang/fetcher-wow exec vitest run test/query/snapshot/snapshotQueryApi.test.ts
pnpm --filter @ahoo-wang/fetcher-wow test:type
```

Expected: Vitest FAILS because `SnapshotQueryEndpointPaths.AGGREGATION` is undefined; `tsc` also reports that `aggregate` and `aggregateStream` do not exist.

- [ ] **Step 3: Extend SnapshotQueryApi with optional aggregation methods and its endpoint path**

Add these type imports to `packages/wow/src/query/snapshot/snapshotQueryApi.ts`:

```ts
import type { AggregationQuery } from '../aggregation';
import type { DynamicDocument } from '../types';
```

Add optional aggregation methods at the start of `SnapshotQueryApi<S, FIELDS>` before `singleState`:

```ts
  /** Runs a snapshot aggregation and returns all result rows. */
  aggregate?<Row extends DynamicDocument = DynamicDocument>(
    query: AggregationQuery<FIELDS>,
    attributes?: Record<string, any>,
    abortController?: AbortController,
  ): Promise<Row[]>;

  /** Runs a snapshot aggregation and streams result rows as SSE. */
  aggregateStream?<Row extends DynamicDocument = DynamicDocument>(
    query: AggregationQuery<FIELDS>,
    attributes?: Record<string, any>,
    abortController?: AbortController,
  ): Promise<ReadableStream<JsonServerSentEvent<Row>>>;
```

Add the endpoint immediately after `SNAPSHOT_RESOURCE_NAME`:

```ts
  static readonly AGGREGATION = `${SnapshotQueryEndpointPaths.SNAPSHOT_RESOURCE_NAME}/aggregation`;
```

- [ ] **Step 4: Add the two minimal decorated methods to SnapshotQueryClient**

Add these type imports to `packages/wow/src/query/snapshot/snapshotQueryClient.ts`:

```ts
import type { AggregationQuery } from '../aggregation';
import type { DynamicDocument } from '../types';
```

Keep `SnapshotQueryClient<S, FIELDS>` implementing only `SnapshotQueryApi<S, FIELDS>` and `ApiMetadataCapable`; its concrete aggregation methods remain required. Optional interface members preserve downstream implementations while concrete-client callers retain the required API.

Add these methods after the constructor and before `count`:

```ts
  /** Runs a snapshot aggregation and returns all result rows. */
  @post(SnapshotQueryEndpointPaths.AGGREGATION)
  aggregate<Row extends DynamicDocument = DynamicDocument>(
    @body() query: AggregationQuery<FIELDS>,
    @attribute() attributes?: Record<string, any>,
    abortController?: AbortController,
  ): Promise<Row[]> {
    throw autoGeneratedError(query, attributes, abortController);
  }

  /** Runs a snapshot aggregation and streams result rows as SSE. */
  @post(SnapshotQueryEndpointPaths.AGGREGATION, {
    headers: { Accept: ContentTypeValues.TEXT_EVENT_STREAM },
    resultExtractor: JsonEventStreamResultExtractor,
  })
  aggregateStream<Row extends DynamicDocument = DynamicDocument>(
    @body() query: AggregationQuery<FIELDS>,
    @attribute() attributes?: Record<string, any>,
    abortController?: AbortController,
  ): Promise<ReadableStream<JsonServerSentEvent<Row>>> {
    throw autoGeneratedError(query, attributes, abortController);
  }
```

Do not modify `QueryClientFactory`; its existing `createSnapshotQueryClient()` return type exposes the new methods automatically.

- [ ] **Step 5: Run formatting and focused verification**

Run:

```bash
pnpm --filter @ahoo-wang/fetcher-wow lint
pnpm --filter @ahoo-wang/fetcher-wow exec vitest run \
  test/query/aggregation.test.ts \
  test/query/snapshot/snapshotQueryApi.test.ts
pnpm --filter @ahoo-wang/fetcher-wow test
pnpm --filter @ahoo-wang/fetcher-wow build
```

Expected: all commands PASS. The snapshot endpoint test proves the route string; `tsc` proves the requested JSON and SSE generic return types.

- [ ] **Step 6: Run the full repository unit gate and inspect the final diff**

Run:

```bash
pnpm test:unit
git diff --check
git status --short
git diff --stat HEAD
```

Expected: root unit tests PASS, no whitespace errors, and the uncommitted diff contains only Task 2 files under `packages/wow`.

- [ ] **Step 7: Commit Task 2**

```bash
git add packages/wow/src/query/snapshot/snapshotQueryApi.ts \
  packages/wow/src/query/snapshot/snapshotQueryClient.ts \
  packages/wow/test/query/snapshot/snapshotQueryApi.test.ts \
  packages/wow/test/tsconfig.types.json
git commit -m "feat(wow): add snapshot aggregation client"
```

After committing, run `git status --short`; expected output is empty.
