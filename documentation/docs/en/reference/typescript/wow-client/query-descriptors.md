---
title: 'Query capability descriptors'
description: 'Query capability descriptors — @ahoo-wang/wow-client'
---

# Query capability descriptors

`QueryDescriptorClient(apiMetadata?)` reads what an aggregate's query models admit: `GET {aggregate}/snapshot/schema` and `GET {aggregate}/event/schema`, which Wow 9.2.0 and later answer with a `QueryModelDescriptor`. The contract is the server's: every operator, sort, paging mode, group and function it lists is admitted when used on its own, and anything it does not list is rejected. `constraints` names the rules about combinations that no single entry shows. A value, a scope or a policy can still reject a query; that rejection carries a [`QueryViolation`](./errors-and-utilities).

| Method              | Endpoint                          | Promise result          |
| ------------------- | --------------------------------- | ----------------------- |
| describeSnapshot    | GET {aggregate}/snapshot/schema   | QueryDescriptorResult   |
| describeEventStream | GET {aggregate}/event/schema      | QueryDescriptorResult   |

Each method takes `(previous?, attributes?, abort?)`. `previous` is the version of a descriptor already held: `descriptor.version` (`sha256:…`), or the ETag the server sent (`"sha256:…"`, or a weak `W/"sha256:…"`). The client sends it as `If-None-Match: "sha256:…"`; an unchanged descriptor answers 304 without a body and the method resolves to `{ notModified: true, version }`. Otherwise it resolves to `{ notModified: false, descriptor, version }`. A blank `previous` sends no header. Any other failure rejects as the query methods do, and `toWowError` reads it.

The schema routes have no tenant or owner segment, whatever routes the aggregate's queries take: the base path names the aggregate alone (`{contextAlias}/{aggregateName}`). `QueryClientFactory.createQueryDescriptorClient(options?)` builds it that way, leaving the factory's `resourceAttribution` out; an explicit `basePath` is used as it is. Headers such as `Wow-Space-Id` come from `ApiMetadata` as for the other clients.

A descriptor does not depend on the caller, so it can be shared and cached; its `version` is a hash of its content. The server reloads query schemas periodically (every 5 minutes by default), and a storage change such as a new text index can change the descriptor without a deployment, so revalidate a long-held copy instead of keeping it forever. The client keeps no cache of its own.

What the descriptor holds:

| Part          | Contract                                                                                                                                                  |
| ------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `record`      | `identity` (the row key), `paging` (`PagingMode`: LIST, PAGED, CURSOR), `defaultScope` (the deletion scope a query without one gets), `rootOperators` (operators that take no field) and `search` (modes and fields; absent when the model offers no full-text search). |
| `fields`      | Every queryable field by logical path, element fields included. Per field: `types`, `kind`, `nullable`, `semantic` (the temporal kinds), `enum`, `sensitivity` (`level`, `DISPLAY` or `CONFIDENTIAL`, and `comparable`: a field that is not comparable lists no operators, has `sort.paged` false and is left out of `record.search`), `project`, `filter.operators`, `sort.paged` / `sort.cursor`, `aggregate` (absent when it cannot be aggregated), `role` for system fields, and `scope`, the element it lives in. |
| `elements`    | Array fields whose elements `ELEMENT_MATCH` can filter or an aggregation can run over.                                                                     |
| `dynamic`     | Fields under map keys, one entry per pattern with `{key}`, resolved as the server resolves a concrete key (a map of arrays is one `ARRAY` entry); `excludedKeys` lists the keys declared as fields of their own, which take that field's entry instead. |
| `limits`      | The entry's effective limits: the protocol's and the HTTP budget, whichever is smaller. `null` is unlimited.                                             |
| `analysis`    | The metric types; `approximate`, those whose results this backend estimates (`PERCENTILE` on MongoDB, `DISTINCT_COUNT` and `PERCENTILE` on Elasticsearch); whether expressions, `having` and metric sort are admitted; whether date histograms fill empty buckets; and `dateUnits`, the `AggregationDateUnit`s a `DATE_HISTOGRAM` group may bucket by. |
| `constraints` | Combination rules: `CURSOR_UNIQUE_SORT` (with the field it appends), `COUNT_REQUIRES_FILTER`, `STARTS_WITH_REQUIRES_PREFIX`.                                |

Sets the server documents as plain strings are open in the types: `QueryModel`, `QueryValueType`, `QueryFieldRole`, `QueryConstraintType`, the aggregation groups and functions, and the metric types (`approximate` included) are a known union plus any string, so a newer server's value still type-checks. `QueryModels`, `QueryValueTypes`, `QueryFieldRoles` and `QueryConstraintTypes` hold the known values. Enumerations the server closes (`FilterOperator`, `PagingMode`, `QueryValueKind`, `SensitivityLevel`, `SearchMode`, `DeletionState`, `AggregationDateUnit`) are enums. The descriptor types are exported from `/dsl` too.

## Complete example

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

## API details

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
    level: SensitivityLevel;
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

### Open and closed value sets {#api-QueryModels}

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
export declare enum SensitivityLevel {
    DISPLAY = 'DISPLAY',
    CONFIDENTIAL = 'CONFIDENTIAL'
}
```

[typescript/wow-client/src/dsl/descriptor.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/descriptor.ts)

## Related topics

[Client configuration and metadata](./configuration) · [Snapshot queries](./snapshot-queries) · [Filter expressions](./filters) · [Aggregation builders](./aggregations) · [Errors](./errors-and-utilities)
