---
title: '查询能力描述'
description: '查询能力描述 — @ahoo-wang/wow-client'
---

# 查询能力描述

`QueryDescriptorClient(apiMetadata?)` 读取聚合的查询模型接受什么：`GET {aggregate}/snapshot/schema` 与 `GET {aggregate}/event/schema`，Wow 9.2.0 及以上以 `QueryModelDescriptor` 应答。契约由服务端给出：它列出的每个算子、排序、分页方式、分组与函数单独使用都会被接受，没列出的都会被拒绝；`constraints` 写明单项看不出的组合规则。值、作用域或策略仍可能拒绝一个查询，这种拒绝带 [`QueryViolation`](./errors-and-utilities)。

| 方法                | 端点                              | Promise 结果            |
| ------------------- | --------------------------------- | ----------------------- |
| describeSnapshot    | GET {aggregate}/snapshot/schema   | QueryDescriptorResult   |
| describeEventStream | GET {aggregate}/event/schema      | QueryDescriptorResult   |

每个方法接受 `(previous?, attributes?, abort?)`。`previous` 是已持有描述的版本：`descriptor.version`（`sha256:…`），或服务端发来的 ETag（`"sha256:…"`，或弱标签 `W/"sha256:…"`）。客户端把它作为 `If-None-Match: "sha256:…"` 发送；描述未变时服务端答 304、不带正文，方法得到 `{ notModified: true, version }`；否则得到 `{ notModified: false, descriptor, version }`。空白的 `previous` 不发送该头。其他失败与查询方法一样拒绝，用 `toWowError` 读取。

无论聚合的查询走哪条路由，schema 路由都没有租户、所有者段：基础路径只含聚合（`{contextAlias}/{aggregateName}`）。`QueryClientFactory.createQueryDescriptorClient(options?)` 就这样拼路径，不带工厂的 `resourceAttribution`；显式的 `basePath` 原样使用。`Wow-Space-Id` 等请求头与其他客户端一样来自 `ApiMetadata`。

描述与调用者无关，可以共享和缓存；`version` 是其内容的哈希。服务端定期（默认每 5 分钟）重新加载查询 schema，存储的变化（例如新建全文索引）不经部署也会改变描述，所以长期持有的副本要重新验证，不要永久保留。客户端自身不做缓存。

描述包含：

| 部分          | 契约                                                                                                                                  |
| ------------- | ------------------------------------------------------------------------------------------------------------------------------------- |
| `record`      | `identity`（行键）、`paging`（`PagingMode`：LIST、PAGED、CURSOR）、`defaultScope`（未写删除范围的查询得到的范围）、`rootOperators`（不带字段的算子）与 `search`（模式与字段；模型不提供全文检索时缺省）。 |
| `fields`      | 按逻辑路径列出每个可查询字段，含元素内的字段。每个字段有 `types`、`kind`、`nullable`、`semantic`（时间语义）、`enum`、`sensitivity`、`project`、`filter.operators`、`sort.paged` / `sort.cursor`、`aggregate`（不能聚合时缺省）、系统字段的 `role`，以及所在元素 `scope`。 |
| `elements`    | 可以用 `ELEMENT_MATCH` 逐元素过滤、或在其元素上聚合的数组字段。                                                                        |
| `dynamic`     | map 键下的字段，每个带 `{key}` 的模式一条，按服务端解析具体键的方式解析（值为数组的 map 是一条 `ARRAY`）；`excludedKeys` 列出另行声明为字段的键，这些键按该字段自己的条目处理。 |
| `limits`      | 该入口的有效上限：协议上限与 HTTP 预算取较小者。`null` 表示不限。                                                                       |
| `analysis`    | 指标类型；`approximate`：该后端估算而非精确计算的指标类型（MongoDB 上是 `PERCENTILE`，Elasticsearch 上是 `DISTINCT_COUNT` 与 `PERCENTILE`）；是否接受表达式、`having` 与按指标排序；日期直方图是否补空桶；`dateUnits`：`DATE_HISTOGRAM` 分组可用的 `AggregationDateUnit`。 |
| `constraints` | 组合规则：`CURSOR_UNIQUE_SORT`（带它追加的字段）、`COUNT_REQUIRES_FILTER`、`STARTS_WITH_REQUIRES_PREFIX`。                              |

服务端文档里是普通字符串的集合，在类型里是开放的：`QueryModel`、`QueryValueType`、`QueryFieldRole`、`QueryConstraintType`、聚合的分组与函数、指标类型（含 `approximate`）都是「已知联合 + 任意字符串」，更新的服务端发来的新值仍能通过类型检查；`QueryModels`、`QueryValueTypes`、`QueryFieldRoles`、`QueryConstraintTypes` 给出已知值。服务端封闭的枚举（`FilterOperator`、`PagingMode`、`QueryValueKind`、`SearchMode`、`DeletionState`、`AggregationDateUnit`）是 enum。描述的类型也从 `/dsl` 导出。

## 完整示例

```ts
import type { Fetcher } from '@ahoo-wang/fetcher';
import {
  FilterOperator,
  QueryClientFactory,
  type QueryModelDescriptor,
} from '@ahoo-wang/wow-client';

let held: QueryModelDescriptor | undefined;

export async function operatorsOf(
  fetcher: Fetcher,
  path: string,
  signal = AbortSignal.timeout(10_000),
): Promise<FilterOperator[]> {
  const descriptors = new QueryClientFactory({
    fetcher,
    contextAlias: 'example',
    aggregateName: 'cart',
  }).createQueryDescriptorClient();
  const result = await descriptors.describeSnapshot(
    held?.version,
    undefined,
    signal,
  );
  if (!result.notModified) held = result.descriptor;
  return held?.fields.find(field => field.path === path)?.filter.operators ?? [];
}
```

## API 详情

### QueryDescriptorClient {#api-QueryDescriptorClient}

```ts
export class QueryDescriptorClient implements QueryDescriptorApi, ApiMetadataCapable {
    constructor(public readonly apiMetadata?: ApiMetadata);
    describeSnapshot(previous?: string, attributes?: Record<string, unknown>, abort?: AbortController | AbortSignal): Promise<QueryDescriptorResult>;
    describeEventStream(previous?: string, attributes?: Record<string, unknown>, abort?: AbortController | AbortSignal): Promise<QueryDescriptorResult>;
}
```

[typescript/wow-client/src/client/query/descriptor/queryDescriptorClient.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/client/query/descriptor/queryDescriptorClient.ts)

### QueryDescriptorApi {#api-QueryDescriptorApi}

```ts
export interface QueryDescriptorApi {
    describeSnapshot(previous?: string, attributes?: Record<string, unknown>, abort?: AbortController | AbortSignal): Promise<QueryDescriptorResult>;
    describeEventStream(previous?: string, attributes?: Record<string, unknown>, abort?: AbortController | AbortSignal): Promise<QueryDescriptorResult>;
}
export interface QueryDescriptorRead {
    notModified: false;
    descriptor: QueryModelDescriptor;
    version: string;
}
export interface QueryDescriptorNotModified {
    notModified: true;
    version: string;
}
export type QueryDescriptorResult = QueryDescriptorRead | QueryDescriptorNotModified;
```

[typescript/wow-client/src/client/query/descriptor/queryDescriptorApi.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/client/query/descriptor/queryDescriptorApi.ts)

### QueryModelDescriptor {#api-QueryModelDescriptor}

```ts
export interface QueryModelDescriptor {
    model: QueryModel;
    version: string;
    timeZone: string;
    record: RecordDescriptor;
    limits: LimitsDescriptor;
    analysis: AnalysisDescriptor;
    fields: FieldDescriptor[];
    elements: ElementDescriptor[];
    dynamic: DynamicFieldDescriptor[];
    constraints: ConstraintDescriptor[];
}
export interface RecordDescriptor {
    identity: string;
    paging: PagingMode[];
    defaultScope?: DeletionState;
    rootOperators: FilterOperator[];
    search?: SearchDescriptor;
}
export interface SearchDescriptor {
    modes: SearchMode[];
    fields: string[];
}
export interface LimitsDescriptor {
    maxListSize: number | null;
    defaultListSize: number | null;
    maxPageSize: number | null;
    maxPageWindow: number | null;
    maxFilterNodes: number | null;
    maxFilterValues: number | null;
    maxSortFields: number;
    aggregation: AggregationLimitsDescriptor;
}
export interface AggregationLimitsDescriptor {
    maxGroups: number;
    maxMetrics: number;
    maxElements: number;
    maxLimit: number;
    maxExpressionDepth: number;
    maxExpressionNodes: number;
}
export interface AnalysisDescriptor {
    metrics: (AggregationMetricType | (string & {}))[];
    approximate: (AggregationMetricType | (string & {}))[];
    expressions: boolean;
    having: HavingDescriptor;
    sort: AnalysisSortDescriptor;
    dense: boolean;
    dateUnits: AggregationDateUnit[];
}
export interface HavingDescriptor {
    metrics: (AggregationMetricType | (string & {}))[];
}
export interface AnalysisSortDescriptor {
    groups: boolean;
    metrics: boolean;
}
export interface ConstraintDescriptor {
    type: QueryConstraintType;
    appended?: string;
}
```

[typescript/wow-client/src/dsl/descriptor.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/descriptor.ts)

### FieldDescriptor {#api-FieldDescriptor}

```ts
export interface FieldDescriptor {
    path: string;
    role?: QueryFieldRole;
    types: QueryValueType[];
    kind: QueryValueKind;
    nullable: boolean;
    semantic?: QuerySemanticType;
    enum?: EnumValueDescriptor[];
    description?: string;
    sensitivity?: SensitivityDescriptor;
    project: boolean;
    filter: FieldFilterDescriptor;
    sort: FieldSortDescriptor;
    aggregate?: FieldAggregateDescriptor;
    scope?: string;
}
export interface FieldFilterDescriptor {
    operators: FilterOperator[];
}
export interface FieldSortDescriptor {
    paged: boolean;
    cursor: boolean;
}
export interface FieldAggregateDescriptor {
    groups: (AggregationGroupType | (string & {}))[];
    missingKey: boolean;
    functions: (AggregationFunction | (string & {}))[];
    distinctCount: boolean;
    percentile: boolean;
    any: boolean;
    expressionInput: boolean;
    inMetricFilter: boolean;
}
export interface EnumValueDescriptor {
    value: unknown;
    description?: string;
}
export interface SensitivityDescriptor {
    level: 'DISPLAY' | (string & {});
    comparable: boolean;
}
export interface ElementDescriptor {
    path: string;
    filter: boolean;
    aggregate: boolean;
}
export interface DynamicFieldDescriptor {
    pattern: string;
    types: QueryValueType[];
    kind: QueryValueKind;
    filter: FieldFilterDescriptor;
    excludedKeys?: string[];
}
export type QuerySemanticType = TemporalDate | TemporalEpoch | TemporalFormatted;
export interface TemporalDate {
    type: 'TEMPORAL_DATE';
}
export interface TemporalEpoch {
    type: 'TEMPORAL_EPOCH';
    timeUnit?: TimeUnit;
}
export interface TemporalFormatted {
    type: 'TEMPORAL_FORMATTED';
    pattern: string;
}
```

[typescript/wow-client/src/dsl/descriptor.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/descriptor.ts)

### 开放与封闭的取值集合 {#api-QueryModels}

```ts
export declare const QueryModels: Readonly<{
    readonly SNAPSHOT: 'SNAPSHOT';
    readonly EVENT_STREAM: 'EVENT_STREAM';
}>;
export type QueryModel = (typeof QueryModels)[keyof typeof QueryModels] | (string & {});
export declare const QueryValueTypes: Readonly<{
    readonly STRING: 'STRING';
    readonly INTEGER: 'INTEGER';
    readonly DECIMAL: 'DECIMAL';
    readonly BOOLEAN: 'BOOLEAN';
    readonly OBJECT: 'OBJECT';
}>;
export type QueryValueType = (typeof QueryValueTypes)[keyof typeof QueryValueTypes] | (string & {});
export declare const QueryFieldRoles: Readonly<{
    readonly IDENTITY: 'IDENTITY';
    readonly AGGREGATE_ID: 'AGGREGATE_ID';
    readonly TENANT_ID: 'TENANT_ID';
    readonly OWNER_ID: 'OWNER_ID';
    readonly SPACE_ID: 'SPACE_ID';
    readonly DELETED: 'DELETED';
}>;
export type QueryFieldRole = (typeof QueryFieldRoles)[keyof typeof QueryFieldRoles] | (string & {});
export declare const QueryConstraintTypes: Readonly<{
    readonly CURSOR_UNIQUE_SORT: 'CURSOR_UNIQUE_SORT';
    readonly COUNT_REQUIRES_FILTER: 'COUNT_REQUIRES_FILTER';
    readonly STARTS_WITH_REQUIRES_PREFIX: 'STARTS_WITH_REQUIRES_PREFIX';
}>;
export type QueryConstraintType = (typeof QueryConstraintTypes)[keyof typeof QueryConstraintTypes] | (string & {});
export declare enum PagingMode {
    LIST = 'LIST',
    PAGED = 'PAGED',
    CURSOR = 'CURSOR'
}
export declare enum QueryValueKind {
    UNKNOWN = 'UNKNOWN',
    NULL = 'NULL',
    SCALAR = 'SCALAR',
    OBJECT = 'OBJECT',
    ARRAY = 'ARRAY',
    UNION = 'UNION'
}
```

[typescript/wow-client/src/dsl/descriptor.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/descriptor.ts)

## 相关主题

[客户端配置与元数据](./configuration) · [快照查询](./snapshot-queries) · [过滤表达式](./filters) · [聚合构建器](./aggregations) · [错误](./errors-and-utilities)
