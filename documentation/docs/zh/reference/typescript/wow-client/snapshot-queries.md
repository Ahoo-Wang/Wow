---
title: '快照查询'
description: '快照查询 — @ahoo-wang/wow-client'
---

# 快照查询

`SnapshotQueryClient<S,FIELDS>(apiMetadata?)` 查询物化快照，所有查询方法均为 POST。末尾可选 controller 用于取消；attributes 是执行元数据，不是 JSON body 字段。投影可令结果不完整，泛型 T 必须如实描述所选字段，不要断言缺失字段存在。

| 方法                         | 相对端点                               | Promise 结果                                      |
| ---------------------------- | -------------------------------------- | ------------------------------------------------- |
| single / singleState         | snapshot/single、snapshot/single/state | MaterializedSnapshot&lt;S&gt; / S                 |
| list / listState             | snapshot/list、snapshot/list/state     | 快照数组 / S[]                                    |
| listStream / listStateStream | 同 list 端点，SSE Accept               | JSON SSE 快照/状态的 ReadableStream               |
| paged / pagedState           | snapshot/paged、snapshot/paged/state   | 带 total/list 的 PagedList                        |
| count                        | snapshot/count                         | number，body 直接为 Condition 或 FilterExpression |
| cursor / cursorState         | snapshot/cursor、snapshot/cursor/state | 带 list/nextCursor 的 CursorPage                  |
| aggregate / aggregateStream  | snapshot/aggregation                   | DynamicDocument[] 或 JSON SSE 行                  |

具体查询方法接收 `(query, attributes?, abortController?)`；getById/getStateById 接收 id，构造旧 aggregateId condition 后调用 single/singleState。getByIds/getStateByIds 接收 string[]，使用新 aggregateIds filter 和等于输入数量的 limit。空 ID 数组直接 Promise.resolve([])，不发请求；不要假设服务端保持输入 ID 顺序。

MaterializedSnapshot 将 state S 与 context/aggregate/tenant/owner/space 身份、version、事件/时间/操作者、tags、deleted 组合。MediumMaterializedSnapshot 省略部分完整快照字段；SmallMaterializedSnapshot 保留 state、命名聚合、version、firstEventTime。它们是结构模型，不是转换器。SnapshotMetadataFields 提供准确逻辑元数据字段名，状态字段通常位于 `state` 下。

客户端不增加结果缓存或一致性等待。命令后的可见性取决于服务端投影和等待阶段。single 缺失仍声明为 Promise&lt;T&gt;，本包不会统一转换为 T | null；应处理实际端点 HTTP/结果契约和 Fetcher 错误。流消费和 reader 清理由调用者负责。另见 [查询选项](./query-options)、[游标](./cursor-queries)、[聚合](./aggregations)。

## 完整示例

```ts
import {
  SnapshotQueryClient,
  pagedQuery,
  filter,
  asc,
} from '@ahoo-wang/wow-client';
interface User {
  id: string;
  name: string;
}
const client = new SnapshotQueryClient<User>({ basePath: '/users' });
export async function firstPage(controller = new AbortController()) {
  return client.pagedState(
    pagedQuery({
      filter: filter.eq('state.active', true),
      pagination: { index: 1, size: 20 },
      sort: [asc('state.name')],
    }),
    undefined,
    controller,
  );
}
```

示例中的服务 URL 需要应用实现；类型检查不代表已经访问外部服务。

## 公开签名与类型

以下签名按当前根入口可达声明核对。`?` 表示可省略；泛型/接口只约束编译期，继承项与关联类型可从 [符号索引](./symbols) 定位。运行时默认值和失败行为以本页上文为准。

### QueryApi {#api-QueryApi}

::: details 展开完整字段与成员

```ts
export interface QueryApi<R, FIELDS extends string = string> {
  cursor<T extends Partial<R> = R>(
    query: CursorQuery<FIELDS>,
    attributes?: Record<string, unknown>,
    abortController?: AbortController,
  ): Promise<CursorPage<T>>;
  aggregate<
    Row extends DynamicDocument = DynamicDocument,
    AGGREGATION_FIELDS extends string = string,
  >(
    query: AggregationQuery<FIELDS, AGGREGATION_FIELDS>,
    attributes?: Record<string, any>,
    abortController?: AbortController,
  ): Promise<Row[]>;
  aggregateStream<
    Row extends DynamicDocument = DynamicDocument,
    AGGREGATION_FIELDS extends string = string,
  >(
    query: AggregationQuery<FIELDS, AGGREGATION_FIELDS>,
    attributes?: Record<string, any>,
    abortController?: AbortController,
  ): Promise<ReadableStream<JsonServerSentEvent<Row>>>;
  single<T extends Partial<R> = R>(
    singleQuery: SingleQueryRequest<FIELDS>,
    attributes?: Record<string, any>,
  ): Promise<T>;
  list<T extends Partial<R> = R>(
    listQuery: ListQueryRequest<FIELDS>,
    attributes?: Record<string, any>,
  ): Promise<T[]>;
  listStream<T extends Partial<R> = R>(
    listQuery: ListQueryRequest<FIELDS>,
    attributes?: Record<string, any>,
  ): Promise<ReadableStream<JsonServerSentEvent<T>>>;
  paged<T extends Partial<R> = R>(
    pagedQuery: PagedQueryRequest<FIELDS>,
    attributes?: Record<string, any>,
  ): Promise<PagedList<T>>;
  count(
    filter: FilterExpression<FIELDS> | Condition<FIELDS>,
    attributes?: Record<string, any>,
  ): Promise<number>;
}
```

:::

[typescript/wow-client/src/query/queryApi.ts:36](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/queryApi.ts#L36)

### MaterializedSnapshot {#api-MaterializedSnapshot}

```ts
export interface MaterializedSnapshot<S>
  extends
    StateCapable<S>,
    AggregateId,
    TenantId,
    OwnerId,
    SpaceIdCapable,
    Version,
    EventIdCapable,
    FirstOperatorCapable,
    OperatorCapable,
    FirstEventTimeCapable,
    EventTimeCapable,
    SnapshotTimeCapable,
    AbacTaggable,
    DeletedCapable {}
```

[typescript/wow-client/src/query/snapshot/snapshot.ts:35](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/snapshot/snapshot.ts#L35)

### MediumMaterializedSnapshot {#api-MediumMaterializedSnapshot}

```ts
export interface MediumMaterializedSnapshot<S>
  extends
    StateCapable<S>,
    NamedAggregate,
    TenantId,
    OwnerId,
    SpaceIdCapable,
    Version,
    EventIdCapable,
    FirstOperatorCapable,
    OperatorCapable,
    FirstEventTimeCapable,
    EventTimeCapable,
    AbacTaggable {}
```

[typescript/wow-client/src/query/snapshot/snapshot.ts:60](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/snapshot/snapshot.ts#L60)

### SmallMaterializedSnapshot {#api-SmallMaterializedSnapshot}

```ts
export interface SmallMaterializedSnapshot<S>
  extends StateCapable<S>, NamedAggregate, Version, FirstEventTimeCapable {}
```

[typescript/wow-client/src/query/snapshot/snapshot.ts:80](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/snapshot/snapshot.ts#L80)

### SnapshotMetadataFields {#api-SnapshotMetadataFields}

```ts
export class SnapshotMetadataFields {
  static readonly VERSION = 'version';
  static readonly TENANT_ID = 'tenantId';
  static readonly OWNER_ID = 'ownerId';
  static readonly SPACE_ID = 'spaceId';
  static readonly EVENT_ID = 'eventId';
  static readonly FIRST_EVENT_TIME = 'firstEventTime';
  static readonly EVENT_TIME = 'eventTime';
  static readonly FIRST_OPERATOR = 'firstOperator';
  static readonly OPERATOR = 'operator';
  static readonly SNAPSHOT_TIME = 'snapshotTime';
  static readonly TAGS = 'tags';
  static readonly DELETED = 'deleted';
  static readonly STATE = 'state';
}
```

[typescript/wow-client/src/query/snapshot/snapshot.ts:89](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/snapshot/snapshot.ts#L89)

### SnapshotQueryApi {#api-SnapshotQueryApi}

::: details 展开完整字段与成员

```ts
export interface SnapshotQueryApi<
  S,
  FIELDS extends string = string,
> extends QueryApi<MaterializedSnapshot<S>, FIELDS> {
  cursorState<T extends Partial<S> = S>(
    query: CursorQuery<FIELDS>,
    attributes?: Record<string, unknown>,
    abortController?: AbortController,
  ): Promise<CursorPage<T>>;
  singleState<T extends Partial<S> = S>(
    singleQuery: SingleQueryRequest<FIELDS>,
    attributes?: Record<string, any>,
    abortController?: AbortController,
  ): Promise<T>;
  listState<T extends Partial<S> = S>(
    listQuery: ListQueryRequest<FIELDS>,
    attributes?: Record<string, any>,
    abortController?: AbortController,
  ): Promise<T[]>;
  listStateStream<T extends Partial<S> = S>(
    listQuery: ListQueryRequest<FIELDS>,
    attributes?: Record<string, any>,
    abortController?: AbortController,
  ): Promise<ReadableStream<JsonServerSentEvent<T>>>;
  pagedState<T extends Partial<S> = S>(
    pagedQuery: PagedQueryRequest<FIELDS>,
    attributes?: Record<string, any>,
    abortController?: AbortController,
  ): Promise<PagedList<T>>;
}
```

:::

[typescript/wow-client/src/query/snapshot/snapshotQueryApi.ts:31](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/snapshot/snapshotQueryApi.ts#L31)

### SnapshotQueryEndpointPaths {#api-SnapshotQueryEndpointPaths}

```ts
export class SnapshotQueryEndpointPaths {
  static readonly SNAPSHOT_RESOURCE_NAME = 'snapshot';
  static readonly AGGREGATION = `${SnapshotQueryEndpointPaths.SNAPSHOT_RESOURCE_NAME}/aggregation`;
  static readonly COUNT = `${SnapshotQueryEndpointPaths.SNAPSHOT_RESOURCE_NAME}/count`;
  static readonly LIST = `${SnapshotQueryEndpointPaths.SNAPSHOT_RESOURCE_NAME}/list`;
  static readonly LIST_STATE = `${SnapshotQueryEndpointPaths.LIST}/state`;
  static readonly PAGED = `${SnapshotQueryEndpointPaths.SNAPSHOT_RESOURCE_NAME}/paged`;
  static readonly PAGED_STATE = `${SnapshotQueryEndpointPaths.PAGED}/state`;
  static readonly CURSOR = `${SnapshotQueryEndpointPaths.SNAPSHOT_RESOURCE_NAME}/cursor`;
  static readonly CURSOR_STATE = `${SnapshotQueryEndpointPaths.CURSOR}/state`;
  static readonly SINGLE = `${SnapshotQueryEndpointPaths.SNAPSHOT_RESOURCE_NAME}/single`;
  static readonly SINGLE_STATE = `${SnapshotQueryEndpointPaths.SINGLE}/state`;
}
```

[typescript/wow-client/src/query/snapshot/snapshotQueryApi.ts:118](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/snapshot/snapshotQueryApi.ts#L118)

### SnapshotQueryClient {#api-SnapshotQueryClient}

::: details 展开完整字段与成员

```ts
export class SnapshotQueryClient<S, FIELDS extends string = string> implements SnapshotQueryApi<S, FIELDS>, ApiMetadataCapable {
    constructor(public readonly apiMetadata?: ApiMetadata);
    aggregate<Row extends DynamicDocument = DynamicDocument, AGGREGATION_FIELDS extends string = string>(query: AggregationQuery<FIELDS, AGGREGATION_FIELDS>, attributes?: Record<string, any>, abortController?: AbortController): Promise<Row[]>;
    aggregateStream<Row extends DynamicDocument = DynamicDocument, AGGREGATION_FIELDS extends string = string>(query: AggregationQuery<FIELDS, AGGREGATION_FIELDS>, attributes?: Record<string, unknown>, abortController?: AbortController): Promise<ReadableStream<JsonServerSentEvent<Row>>>;
    cursor<T extends Partial<MaterializedSnapshot<S>> = MaterializedSnapshot<S>>(query: CursorQuery<FIELDS>, attributes?: Record<string, unknown>, abortController?: AbortController): Promise<CursorPage<T>>;
    cursorState<T extends Partial<S> = S>(query: CursorQuery<FIELDS>, attributes?: Record<string, unknown>, abortController?: AbortController): Promise<CursorPage<T>>;
    count(filter: FilterExpression<FIELDS> | Condition<FIELDS>, attributes?: Record<string, any>, abortController?: AbortController): Promise<number>;
    list<T extends Partial<MaterializedSnapshot<S>> = MaterializedSnapshot<S>>(listQuery: ListQueryRequest<FIELDS>, attributes?: Record<string, any>, abortController?: AbortController): Promise<T[]>;
    listStream<T extends Partial<MaterializedSnapshot<S>> = MaterializedSnapshot<S>>(listQuery: ListQueryRequest<FIELDS>, attributes?: Record<string, any>, abortController?: AbortController): Promise<ReadableStream<JsonServerSentEvent<T>>>;
    listState<T extends Partial<S> = S>(listQuery: ListQueryRequest<FIELDS>, attributes?: Record<string, any>, abortController?: AbortController): Promise<T[]>;
    listStateStream<T extends Partial<S> = S>(listQuery: ListQueryRequest<FIELDS>, attributes?: Record<string, any>, abortController?: AbortController): Promise<ReadableStream<JsonServerSentEvent<T>>>;
    paged<T extends Partial<MaterializedSnapshot<S>> = MaterializedSnapshot<S>>(pagedQuery: PagedQueryRequest<FIELDS>, attributes?: Record<string, any>, abortController?: AbortController): Promise<PagedList<T>>;
    pagedState<T extends Partial<S> = S>(pagedQuery: PagedQueryRequest<FIELDS>, attributes?: Record<string, any>, abortController?: AbortController): Promise<PagedList<T>>;
    single<T extends Partial<MaterializedSnapshot<S>> = MaterializedSnapshot<S>>(singleQuery: SingleQueryRequest<FIELDS>, attributes?: Record<string, any>, abortController?: AbortController): Promise<T>;
    singleState<T extends Partial<S> = S>(singleQuery: SingleQueryRequest<FIELDS>, attributes?: Record<string, any>, abortController?: AbortController): Promise<T>;
    getById(id: string, attributes?: Record<string, any>, abortController?: AbortController): Promise<MaterializedSnapshot<S>>;
    getStateById(id: string, attributes?: Record<string, any>, abortController?: AbortController): Promise<S>;
    getByIds(ids: string[], attributes?: Record<string, any>, abortController?: AbortController): Promise<MaterializedSnapshot<S>[]>;
    getStateByIds(ids: string[], attributes?: Record<string, any>, abortController?: AbortController): Promise<S[]>;
}
```

:::

[typescript/wow-client/src/query/snapshot/snapshotQueryClient.ts:121](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/snapshot/snapshotQueryClient.ts#L121)

## 相关专题

[客户端配置与元数据](./configuration) · [命令与等待结果](./commands) · [过滤表达式与旧条件](./filters) · [投影、排序与分页](./query-options) · [游标查询](./cursor-queries) · [聚合构造器](./aggregations) · [事件与历史状态](./events-and-history) · [身份与资源归属](./identity-and-attribution)
