---
title: '事件与历史状态'
description: '事件与历史状态 — @ahoo-wang/wow-client'
---

# 事件与历史状态

事件查询返回 Wow 存储的事件流记录；历史状态加载器通过专用端点重建/选择聚合状态。类型名称带 stream 不表示自动订阅未来所有领域变化。

| 客户端 / 方法                               | 端点 / 结果                                                                  |
| ------------------------------------------- | ---------------------------------------------------------------------------- |
| EventStreamQueryClient.list / paged / count | POST event/list、event/paged、event/count → 记录数组、PagedList、number      |
| listStream                                  | POST event/list，Accept text/event-stream → 事件流记录的流，每个服务端事件一条 |
| cursor                                      | POST event/cursor → CursorPage，仅接受新 filter 查询                         |
| aggregate / aggregateStream                 | POST event/aggregation → 扁平聚合行，或这些行的流                            |
| load(id, headVersion, tailVersion)          | GET `{id}/event/{headVersion}/{tailVersion}` → 该聚合这些版本的事件流，两端均包含 |
| loadStream(id, headVersion, tailVersion)    | 同一路由，Accept text/event-stream → 事件流记录的流，每个服务端事件一条      |
| LoadStateAggregateClient.load(id)           | GET `{id}/state` → S                                                         |
| loadVersioned(id, version)                  | GET `{id}/state/{version}` → S                                               |
| loadTimeBased(id, createTime)               | GET `{id}/state/time/{createTime}` → S                                       |
| LoadOwnerStateAggregateClient               | 无 id 的对应 load 变体，端点从 state 开始，owner/tenant 归属放在配置路由中。 |

所有具体方法在必填参数后接受可选 attributes 和 `abort`；`abort` 是 `AbortController` 或 `AbortSignal`（`AbortSignal.timeout(ms)`、数据请求库传入的 `signal`）。EventStreamQueryApi 明确省略 single。`load`/`loadStream` 按版本顺序重放单个聚合，适用于审计轨迹或事件溯源视图：`headVersion` 从 1 开始，服务端把该范围视为列表查询，超过其最大列表条数（默认 1000）会被拒绝。该路由默认带租户段、不带所有者段，与加载状态的路由一致。服务端中途失败时，流（`listStream`、`aggregateStream`、`loadStream`）以 `WowError` 出错，`for await` 会抛出，参见[错误](./errors-and-utilities)。`GET {id}/state/tracing` 没有对应的客户端方法，请直接通过 Fetcher 调用。网络、状态和解析失败会拒绝；加载器不安装本地事件存储，也不校验版本/时间范围。createTime 为直接放入路径的数值时间戳，不转换单位，应使用服务端 epoch 毫秒契约。

DomainEvent 含 id/name/body/bodyType/revision；DomainEventStream 含流身份、聚合归属、owner/space、commandId/requestId、createTime/version、header 和 DomainEvent 数组 body。header 支持已知命令/trace 字段以及字符串扩展。StateEvent 增加 state、首操作者/时间和 deleted。MetadataFields 提供准确逻辑路径（含 body.body）。流式方法返回 `ReadableStream<DomainEventStream>`（元素就是记录本身，不是服务端事件信封），不是 Promise，也不会自动遍历。提前退出需取消并释放 reader，取消 HTTP 控制器本身也不表示确认领域事件。

## 完整示例

```ts
import {
  LoadStateAggregateClient,
  EventStreamQueryClient,
} from '@ahoo-wang/wow-client';
interface Account {
  balance: number;
}
const history = new LoadStateAggregateClient<Account>({
  basePath: 'account',
});
const events = new EventStreamQueryClient({ basePath: 'account' });
export async function audit(id: string, signal = AbortSignal.timeout(10_000)) {
  const state = await history.loadVersioned(id, 3, undefined, signal);
  const records = await events.load(id, 1, 3, undefined, signal);
  return { state, records };
}
```

示例中的服务 URL 需要应用实现；类型检查不代表已经访问外部服务。

## 公开签名与类型

以下签名按当前根入口可达声明核对。`?` 表示可省略；泛型/接口只约束编译期，继承项与关联类型可从 [符号索引](./symbols) 定位。运行时默认值和失败行为以本页上文为准。

### DomainEvent {#api-DomainEvent}

```ts
export interface DomainEvent<BODY>
  extends Identifier, Named, BodyCapable<BODY> {
  bodyType: string;
  revision: string;
}
```

[typescript/wow-client/src/client/query/event/domainEventStream.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/client/query/event/domainEventStream.ts)

### DomainEventStreamHeader {#api-DomainEventStreamHeader}

```ts
export interface DomainEventStreamHeader {
  command_operator?: string;
  command_wait_endpoint?: string;
  command_wait_stage?: CommandStage;
  local_first?: string;
  remote_ip?: string;
  user_agent?: string;
  trace_id?: string;
  [key: string]: string | undefined;
}
```

[typescript/wow-client/src/client/query/event/domainEventStream.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/client/query/event/domainEventStream.ts)

### DomainEventStream {#api-DomainEventStream}

```ts
export interface DomainEventStream<DomainEventBody = unknown>
  extends
    Identifier,
    AggregateId,
    OwnerId,
    SpaceIdCapable,
    CommandId,
    CreateTimeCapable,
    RequestId,
    Version,
    BodyCapable<DomainEvent<DomainEventBody>[]> {
  header: DomainEventStreamHeader;
}
```

[typescript/wow-client/src/client/query/event/domainEventStream.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/client/query/event/domainEventStream.ts)

### StateEvent {#api-StateEvent}

```ts
export interface StateEvent<DomainEventBody = unknown, S = unknown>
  extends
    DomainEventStream<DomainEventBody>,
    StateCapable<S>,
    FirstOperatorCapable,
    FirstEventTimeCapable,
    DeletedCapable {}
```

[typescript/wow-client/src/client/query/event/domainEventStream.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/client/query/event/domainEventStream.ts)

### DomainEventStreamMetadataFields {#api-DomainEventStreamMetadataFields}

```ts
export const DomainEventStreamMetadataFields = Object.freeze({
  HEADER: 'header',
  COMMAND_OPERATOR: 'header.command_operator',
  AGGREGATE_ID: 'aggregateId',
  TENANT_ID: 'tenantId',
  OWNER_ID: 'ownerId',
  SPACE_ID: 'spaceId',
  COMMAND_ID: 'commandId',
  REQUEST_ID: 'requestId',
  VERSION: 'version',
  BODY: 'body',
  BODY_ID: 'body.id',
  BODY_NAME: 'body.name',
  BODY_TYPE: 'body.bodyType',
  BODY_REVISION: 'body.revision',
  BODY_BODY: 'body.body',
  CREATE_TIME: 'createTime',
} as const);
```

[typescript/wow-client/src/client/query/event/domainEventStream.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/client/query/event/domainEventStream.ts)

### EventStreamQueryApi {#api-EventStreamQueryApi}

```ts
export interface EventStreamQueryApi<
  DomainEventBody = unknown,
  FIELDS extends string = string,
> extends Omit<QueryApi<DomainEventStream<DomainEventBody>, FIELDS>, 'single'> {
  load<
    T extends Partial<DomainEventStream<DomainEventBody>> =
      DomainEventStream<DomainEventBody>,
  >(
    id: string,
    headVersion: number,
    tailVersion: number,
    attributes?: Record<string, unknown>,
    abort?: AbortController | AbortSignal,
  ): Promise<T[]>;
  loadStream<
    T extends Partial<DomainEventStream<DomainEventBody>> =
      DomainEventStream<DomainEventBody>,
  >(
    id: string,
    headVersion: number,
    tailVersion: number,
    attributes?: Record<string, unknown>,
    abort?: AbortController | AbortSignal,
  ): Promise<ReadableStream<T>>;
}
```

[typescript/wow-client/src/client/query/event/eventStreamQueryApi.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/client/query/event/eventStreamQueryApi.ts)

### EventStreamQueryClient {#api-EventStreamQueryClient}

```ts
export class EventStreamQueryClient<DomainEventBody = unknown, FIELDS extends string = string> implements EventStreamQueryApi<DomainEventBody, FIELDS>, ApiMetadataCapable {
    constructor(public readonly apiMetadata?: ApiMetadata);
    aggregate<Row extends object = DynamicDocument, AGGREGATION_FIELDS extends string = string>(query: AggregationQuery<FIELDS, AGGREGATION_FIELDS>, attributes?: Record<string, unknown>, abort?: AbortController | AbortSignal): Promise<Row[]>;
    aggregateStream<Row extends object = DynamicDocument, AGGREGATION_FIELDS extends string = string>(query: AggregationQuery<FIELDS, AGGREGATION_FIELDS>, attributes?: Record<string, unknown>, abort?: AbortController | AbortSignal): Promise<ReadableStream<Row>>;
    cursor<T extends Partial<DomainEventStream<DomainEventBody>> = DomainEventStream<DomainEventBody>>(query: CursorQuery<FIELDS>, attributes?: Record<string, unknown>, abort?: AbortController | AbortSignal): Promise<CursorPage<T>>;
    count(filter: FilterExpression<FIELDS> | Condition<FIELDS>, attributes?: Record<string, unknown>, abort?: AbortController | AbortSignal): Promise<number>;
    list<T extends Partial<DomainEventStream<DomainEventBody>> = DomainEventStream<DomainEventBody>>(listQuery: ListQueryRequest<FIELDS>, attributes?: Record<string, unknown>, abort?: AbortController | AbortSignal): Promise<T[]>;
    listStream<T extends Partial<DomainEventStream<DomainEventBody>> = DomainEventStream<DomainEventBody>>(listQuery: ListQueryRequest<FIELDS>, attributes?: Record<string, unknown>, abort?: AbortController | AbortSignal): Promise<ReadableStream<T>>;
    paged<T extends Partial<DomainEventStream<DomainEventBody>> = DomainEventStream<DomainEventBody>>(pagedQuery: PagedQueryRequest<FIELDS>, attributes?: Record<string, unknown>, abort?: AbortController | AbortSignal): Promise<PagedList<T>>;
    load<T extends Partial<DomainEventStream<DomainEventBody>> = DomainEventStream<DomainEventBody>>(id: string, headVersion: number, tailVersion: number, attributes?: Record<string, unknown>, abort?: AbortController | AbortSignal): Promise<T[]>;
    loadStream<T extends Partial<DomainEventStream<DomainEventBody>> = DomainEventStream<DomainEventBody>>(id: string, headVersion: number, tailVersion: number, attributes?: Record<string, unknown>, abort?: AbortController | AbortSignal): Promise<ReadableStream<T>>;
}
```

[typescript/wow-client/src/client/query/event/eventStreamQueryClient.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/client/query/event/eventStreamQueryClient.ts)

### LoadStateAggregateApi {#api-LoadStateAggregateApi}

```ts
export interface LoadStateAggregateApi<S> {
  load(
    id: string,
    attributes?: Record<string, unknown>,
    abort?: AbortController | AbortSignal,
  ): Promise<S>;
  loadVersioned(
    id: string,
    version: number,
    attributes?: Record<string, unknown>,
    abort?: AbortController | AbortSignal,
  ): Promise<S>;
  loadTimeBased(
    id: string,
    createTime: number,
    attributes?: Record<string, unknown>,
    abort?: AbortController | AbortSignal,
  ): Promise<S>;
}
```

[typescript/wow-client/src/client/query/state/loadStateAggregateApi.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/client/query/state/loadStateAggregateApi.ts)

### LoadStateAggregateClient {#api-LoadStateAggregateClient}

```ts
export class LoadStateAggregateClient<S> implements LoadStateAggregateApi<S>, ApiMetadataCapable {
    constructor(public readonly apiMetadata?: ApiMetadata);
    load(id: string, attributes?: Record<string, unknown>, abort?: AbortController | AbortSignal): Promise<S>;
    loadVersioned(id: string, version: number, attributes?: Record<string, unknown>, abort?: AbortController | AbortSignal): Promise<S>;
    loadTimeBased(id: string, createTime: number, attributes?: Record<string, unknown>, abort?: AbortController | AbortSignal): Promise<S>;
}
```

[typescript/wow-client/src/client/query/state/loadStateAggregateClient.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/client/query/state/loadStateAggregateClient.ts)

### LoadOwnerStateAggregateApi {#api-LoadOwnerStateAggregateApi}

```ts
export interface LoadOwnerStateAggregateApi<S> {
  load(
    attributes?: Record<string, unknown>,
    abort?: AbortController | AbortSignal,
  ): Promise<S>;
  loadVersioned(
    version: number,
    attributes?: Record<string, unknown>,
    abort?: AbortController | AbortSignal,
  ): Promise<S>;
  loadTimeBased(
    createTime: number,
    attributes?: Record<string, unknown>,
    abort?: AbortController | AbortSignal,
  ): Promise<S>;
}
```

[typescript/wow-client/src/client/query/state/loadOwnerStateAggregateApi.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/client/query/state/loadOwnerStateAggregateApi.ts)

### LoadOwnerStateAggregateClient {#api-LoadOwnerStateAggregateClient}

```ts
export class LoadOwnerStateAggregateClient<S> implements LoadOwnerStateAggregateApi<S>, ApiMetadataCapable {
    constructor(public readonly apiMetadata?: ApiMetadata);
    load(attributes?: Record<string, unknown>, abort?: AbortController | AbortSignal): Promise<S>;
    loadVersioned(version: number, attributes?: Record<string, unknown>, abort?: AbortController | AbortSignal): Promise<S>;
    loadTimeBased(createTime: number, attributes?: Record<string, unknown>, abort?: AbortController | AbortSignal): Promise<S>;
}
```

[typescript/wow-client/src/client/query/state/loadOwnerStateAggregateClient.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/client/query/state/loadOwnerStateAggregateClient.ts)

## 相关专题

[客户端配置与元数据](./configuration) · [命令与等待结果](./commands) · [快照查询](./snapshot-queries) · [过滤表达式与旧条件](./filters) · [投影、排序与分页](./query-options) · [游标查询](./cursor-queries) · [聚合构造器](./aggregations) · [身份与资源归属](./identity-and-attribution)
