---
title: '事件与历史状态'
description: '事件与历史状态 — @ahoo-wang/wow-client'
---

# 事件与历史状态

事件查询返回 Wow 存储的事件流记录；历史状态加载器通过专用端点重建/选择聚合状态。类型名称带 stream 不表示自动订阅未来所有领域变化。

| 客户端 / 方法                               | 端点 / 结果                                                                  |
| ------------------------------------------- | ---------------------------------------------------------------------------- |
| EventStreamQueryClient.list / paged / count | POST event/list、event/paged、event/count → 记录数组、PagedList、number      |
| listStream                                  | POST event/list，Accept text/event-stream → JSON SSE 事件流记录              |
| cursor                                      | POST event/cursor → CursorPage，仅接受新 filter 查询                         |
| aggregate / aggregateStream                 | POST event/aggregation → 扁平聚合行或 JSON SSE 行                            |
| LoadStateAggregateClient.load(id)           | GET `{id}/state` → S                                                         |
| loadVersioned(id, version)                  | GET `{id}/state/{version}` → S                                               |
| loadTimeBased(id, createTime)               | GET `{id}/state/time/{createTime}` → S                                       |
| LoadOwnerStateAggregateClient               | 无 id 的对应 load 变体，端点从 state 开始，owner/tenant 归属放在配置路由中。 |

所有具体方法在必填参数后接受可选 attributes 和 AbortController。EventStreamQueryApi 明确省略 single。EndpointPaths 类提供准确相对路径。网络、状态和解析失败会拒绝；加载器不安装本地事件存储，也不校验版本/时间范围。createTime 为直接放入路径的数值时间戳，不转换单位，应使用服务端 epoch 毫秒契约。

DomainEvent 含 id/name/body/bodyType/revision；DomainEventStream 含流身份、聚合归属、owner/space、commandId/requestId、createTime/version、header 和 DomainEvent 数组 body。header 支持已知命令/trace 字段以及字符串扩展。StateEvent 增加 state、首操作者/时间和 deleted。MetadataFields 提供准确逻辑路径（含 body.body）。ReadableDomainEventStream 是 JSON SSE 信封的 ReadableStream，不是 Promise，也不会自动遍历。提前退出需取消并释放 reader，取消 HTTP 控制器本身也不表示确认领域事件。

## 完整示例

```ts
import {
  LoadStateAggregateClient,
  EventStreamQueryClient,
  listQuery,
  filter,
} from '@ahoo-wang/wow-client';
interface Account {
  balance: number;
}
const history = new LoadStateAggregateClient<Account>({
  basePath: '/accounts',
});
const events = new EventStreamQueryClient({ basePath: '/accounts' });
export async function audit(id: string, controller = new AbortController()) {
  const state = await history.loadVersioned(id, 3, undefined, controller);
  const records = await events.list(
    listQuery({
      filter: filter.aggregateId(id),
      limit: 100,
    }),
    undefined,
    controller,
  );
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

[typescript/wow-client/src/query/event/domainEventStream.ts:37](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/event/domainEventStream.ts#L37)

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

[typescript/wow-client/src/query/event/domainEventStream.ts:54](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/event/domainEventStream.ts#L54)

### DomainEventStream {#api-DomainEventStream}

```ts
export interface DomainEventStream<DomainEventBody = any>
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

[typescript/wow-client/src/query/event/domainEventStream.ts:95](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/event/domainEventStream.ts#L95)

### StateEvent {#api-StateEvent}

```ts
export interface StateEvent<DomainEventBody = any, S = any>
  extends
    DomainEventStream<DomainEventBody>,
    StateCapable<S>,
    FirstOperatorCapable,
    FirstEventTimeCapable,
    DeletedCapable {}
```

[typescript/wow-client/src/query/event/domainEventStream.ts:112](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/event/domainEventStream.ts#L112)

### DomainEventStreamMetadataFields {#api-DomainEventStreamMetadataFields}

```ts
export class DomainEventStreamMetadataFields {
  static readonly HEADER = 'header';
  static readonly COMMAND_OPERATOR = `${DomainEventStreamMetadataFields.HEADER}.command_operator`;
  static readonly AGGREGATE_ID = 'aggregateId';
  static readonly TENANT_ID = 'tenantId';
  static readonly OWNER_ID = 'ownerId';
  static readonly SPACE_ID = 'spaceId';
  static readonly COMMAND_ID = 'commandId';
  static readonly REQUEST_ID = 'requestId';
  static readonly VERSION = 'version';
  static readonly BODY = 'body';
  static readonly BODY_ID = `${DomainEventStreamMetadataFields.BODY}.id`;
  static readonly BODY_NAME = `${DomainEventStreamMetadataFields.BODY}.name`;
  static readonly BODY_TYPE = `${DomainEventStreamMetadataFields.BODY}.bodyType`;
  static readonly BODY_REVISION = `${DomainEventStreamMetadataFields.BODY}.revision`;
  static readonly BODY_BODY = `${DomainEventStreamMetadataFields.BODY}.body`;
  static readonly CREATE_TIME = 'createTime';
}
```

[typescript/wow-client/src/query/event/domainEventStream.ts:127](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/event/domainEventStream.ts#L127)

### ReadableDomainEventStream {#api-ReadableDomainEventStream}

```ts
export type ReadableDomainEventStream = ReadableStream<
  JsonServerSentEvent<DomainEventStream>
>;
```

[typescript/wow-client/src/query/event/domainEventStream.ts:152](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/event/domainEventStream.ts#L152)

### EventStreamQueryApi {#api-EventStreamQueryApi}

```ts
export interface EventStreamQueryApi<
  DomainEventBody = any,
  FIELDS extends string = string,
> extends Omit<
  QueryApi<DomainEventStream<DomainEventBody>, FIELDS>,
  'single'
> {}
```

[typescript/wow-client/src/query/event/eventStreamQueryApi.ts:24](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/event/eventStreamQueryApi.ts#L24)

### EventStreamQueryEndpointPaths {#api-EventStreamQueryEndpointPaths}

```ts
export class EventStreamQueryEndpointPaths {
  static readonly EVENT_STREAM_RESOURCE_NAME = 'event';
  static readonly AGGREGATION = `${EventStreamQueryEndpointPaths.EVENT_STREAM_RESOURCE_NAME}/aggregation`;
  static readonly COUNT = `${EventStreamQueryEndpointPaths.EVENT_STREAM_RESOURCE_NAME}/count`;
  static readonly LIST = `${EventStreamQueryEndpointPaths.EVENT_STREAM_RESOURCE_NAME}/list`;
  static readonly PAGED = `${EventStreamQueryEndpointPaths.EVENT_STREAM_RESOURCE_NAME}/paged`;
  static readonly CURSOR = `${EventStreamQueryEndpointPaths.EVENT_STREAM_RESOURCE_NAME}/cursor`;
}
```

[typescript/wow-client/src/query/event/eventStreamQueryApi.ts:39](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/event/eventStreamQueryApi.ts#L39)

### EventStreamQueryClient {#api-EventStreamQueryClient}

```ts
export class EventStreamQueryClient<DomainEventBody = any, FIELDS extends string = string> implements EventStreamQueryApi<DomainEventBody, FIELDS>, ApiMetadataCapable {
    constructor(public readonly apiMetadata?: ApiMetadata);
    aggregate<Row extends DynamicDocument = DynamicDocument, AGGREGATION_FIELDS extends string = string>(query: AggregationQuery<FIELDS, AGGREGATION_FIELDS>, attributes?: Record<string, any>, abortController?: AbortController): Promise<Row[]>;
    aggregateStream<Row extends DynamicDocument = DynamicDocument, AGGREGATION_FIELDS extends string = string>(query: AggregationQuery<FIELDS, AGGREGATION_FIELDS>, attributes?: Record<string, any>, abortController?: AbortController): Promise<ReadableStream<JsonServerSentEvent<Row>>>;
    cursor<T extends Partial<DomainEventStream<DomainEventBody>> = DomainEventStream<DomainEventBody>>(query: CursorQuery<FIELDS>, attributes?: Record<string, unknown>, abortController?: AbortController): Promise<CursorPage<T>>;
    count(filter: FilterExpression<FIELDS> | Condition<FIELDS>, attributes?: Record<string, any>, abortController?: AbortController): Promise<number>;
    list<T extends Partial<DomainEventStream<DomainEventBody>> = DomainEventStream<DomainEventBody>>(listQuery: ListQueryRequest<FIELDS>, attributes?: Record<string, any>, abortController?: AbortController): Promise<T[]>;
    listStream<T extends Partial<DomainEventStream<DomainEventBody>> = DomainEventStream<DomainEventBody>>(listQuery: ListQueryRequest<FIELDS>, attributes?: Record<string, any>, abortController?: AbortController): Promise<ReadableStream<JsonServerSentEvent<T>>>;
    paged<T extends Partial<DomainEventStream<DomainEventBody>> = DomainEventStream<DomainEventBody>>(pagedQuery: PagedQueryRequest<FIELDS>, attributes?: Record<string, any>, abortController?: AbortController): Promise<PagedList<T>>;
}
```

[typescript/wow-client/src/query/event/eventStreamQueryClient.ts:85](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/event/eventStreamQueryClient.ts#L85)

### LoadStateAggregateEndpointPaths {#api-LoadStateAggregateEndpointPaths}

```ts
export class LoadStateAggregateEndpointPaths {
  static readonly LOAD = '{id}/state';
  static readonly LOAD_VERSIONED = `${LoadStateAggregateEndpointPaths.LOAD}/{version}`;
  static readonly LOAD_TIME_BASED = `${LoadStateAggregateEndpointPaths.LOAD}/time/{createTime}`;
}
```

[typescript/wow-client/src/query/state/loadStateAggregateClient.ts:26](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/state/loadStateAggregateClient.ts#L26)

### LoadStateAggregateClient {#api-LoadStateAggregateClient}

```ts
export class LoadStateAggregateClient<S> implements ApiMetadataCapable {
    constructor(public readonly apiMetadata?: ApiMetadata);
    load(id: string, attributes?: Record<string, any>, abortController?: AbortController): Promise<S>;
    loadVersioned(id: string, version: number, attributes?: Record<string, any>, abortController?: AbortController): Promise<S>;
    loadTimeBased(id: string, createTime: number, attributes?: Record<string, any>, abortController?: AbortController): Promise<S>;
}
```

[typescript/wow-client/src/query/state/loadStateAggregateClient.ts:32](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/state/loadStateAggregateClient.ts#L32)

### LoadOwnerStateAggregateEndpointPaths {#api-LoadOwnerStateAggregateEndpointPaths}

```ts
export class LoadOwnerStateAggregateEndpointPaths {
  static readonly LOAD = 'state';
  static readonly LOAD_VERSIONED = `${LoadOwnerStateAggregateEndpointPaths.LOAD}/{version}`;
  static readonly LOAD_TIME_BASED = `${LoadOwnerStateAggregateEndpointPaths.LOAD}/time/{createTime}`;
}
```

[typescript/wow-client/src/query/state/loadOwnerStateAggregateClient.ts:26](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/state/loadOwnerStateAggregateClient.ts#L26)

### LoadOwnerStateAggregateClient {#api-LoadOwnerStateAggregateClient}

```ts
export class LoadOwnerStateAggregateClient<S> implements ApiMetadataCapable {
    constructor(public readonly apiMetadata?: ApiMetadata);
    load(attributes?: Record<string, any>, abortController?: AbortController): Promise<S>;
    loadVersioned(version: number, attributes?: Record<string, any>, abortController?: AbortController): Promise<S>;
    loadTimeBased(createTime: number, attributes?: Record<string, any>, abortController?: AbortController): Promise<S>;
}
```

[typescript/wow-client/src/query/state/loadOwnerStateAggregateClient.ts:32](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/state/loadOwnerStateAggregateClient.ts#L32)

## 相关专题

[客户端配置与元数据](./configuration) · [命令与等待结果](./commands) · [快照查询](./snapshot-queries) · [过滤表达式与旧条件](./filters) · [投影、排序与分页](./query-options) · [游标查询](./cursor-queries) · [聚合构造器](./aggregations) · [身份与资源归属](./identity-and-attribution)
