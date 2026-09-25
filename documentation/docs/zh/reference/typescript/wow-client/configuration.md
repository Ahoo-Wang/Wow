---
title: '客户端配置与元数据'
description: '客户端配置与元数据 — @ahoo-wang/wow-client'
---

# 客户端配置与元数据

当快照、事件和历史状态客户端共享聚合路由时，使用 `QueryClientFactory<S,FIELDS,DomainEventBody>`。构造时传入 `QueryClientOptions`，各 create 方法可覆盖。构造只组合元数据，不发现服务端、不请求 schema，也不验证部署。

| 输入 / 方法                                      | 契约                                                 |
| ------------------------------------------------ | ---------------------------------------------------- |
| contextAlias、resourceAttribution、aggregateName | 按此顺序拼接为 basePath，缺失部分为空。              |
| basePath                                         | 显式值覆盖组合路径，包括显式空字符串。               |
| 其他 ApiMetadata 选项                            | 交给装饰器执行，包含 fetcher 和请求选项。            |
| createSnapshotQueryClient                        | SnapshotQueryClient&lt;S,FIELDS&gt;                  |
| createLoadStateAggregateClient                   | LoadStateAggregateClient&lt;S&gt;                    |
| createLoadOwnerStateAggregateClient              | LoadOwnerStateAggregateClient&lt;S&gt;               |
| createEventStreamQueryClient&lt;EVENT_FIELDS&gt; | EventStreamQueryClient&lt;DomainEventBody,EVENT_FIELDS&gt;；事件字段独立于工厂的快照 FIELDS。 |
| createQueryDescriptorClient                      | [QueryDescriptorClient](./query-descriptors)；schema 路由没有租户、所有者段，所以 basePath 不带 resourceAttribution。 |

单次选项浅覆盖构造默认值。路由组合逻辑是内部实现；需要时从客户端的 `apiMetadata.basePath` 读取结果。ResourceAttributionPathSpec 含 `{tenantId}` 或 `{ownerId}` 时，通过请求配置提供真实值。缺失 Fetcher 注册和请求失败属于装饰器/Fetcher 执行阶段；创建包装器不产生 I/O，也无须清理。

`WowMetadata` 是带 description 和按名称索引 contexts 的数据模型。BoundedContext 包含 alias、scopes、aggregates；Aggregate 包含 type、tenantId、id、scopes、命令名和事件名。可空元数据显式使用 null。`new WowMetadataClient(apiMetadata?).metadata(attributes?, abort?)` 从 `GET /wow/metadata`（相对 Fetcher 的 baseURL）读取它；不会自动读取，本模块也没有导出 `wow()` 装饰器。执行见 [命令](./commands) 和 [快照查询](./snapshot-queries)。

## 完整示例

```ts
import { Fetcher } from '@ahoo-wang/fetcher';
import { QueryClientFactory, WowMetadataClient } from '@ahoo-wang/wow-client';
interface User {
  id: string;
  name: string;
}
const fetcher = new Fetcher({ baseURL: 'https://api.example.com/' });
const factory = new QueryClientFactory<User>({
  fetcher,
  contextAlias: 'accounts',
  aggregateName: 'user',
});
const snapshots = factory.createSnapshotQueryClient(); // basePath accounts/user
const history = factory.createLoadStateAggregateClient();
export async function listContexts() {
  const metadata = await new WowMetadataClient({ fetcher }).metadata();
  return Object.keys(metadata.contexts);
}
export { snapshots, history };
```

示例中的服务 URL 需要应用实现；类型检查不代表已经访问外部服务。

## 公开签名与类型

以下签名按当前根入口可达声明核对。`?` 表示可省略；泛型/接口只约束编译期，继承项与关联类型可从 [符号索引](./symbols) 定位。运行时默认值和失败行为以本页上文为准。

### ScopesCapable {#api-ScopesCapable}

```ts
export interface ScopesCapable {
  scopes: string[];
}
```

[typescript/wow-client/src/client/metadata/wowMetadata.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/client/metadata/wowMetadata.ts)

### Aggregate {#api-Aggregate}

```ts
export interface Aggregate extends ScopesCapable {
  type: string | null;
  tenantId: string | null;
  id: string | null;
  commands: string[];
  events: string[];
}
```

[typescript/wow-client/src/client/metadata/wowMetadata.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/client/metadata/wowMetadata.ts)

### BoundedContext {#api-BoundedContext}

```ts
export interface BoundedContext extends ScopesCapable, DescriptionCapable {
  alias: string | null;
  aggregates: Record<string, Aggregate>;
}
```

[typescript/wow-client/src/client/metadata/wowMetadata.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/client/metadata/wowMetadata.ts)

### WowMetadata {#api-WowMetadata}

```ts
export interface WowMetadata extends DescriptionCapable {
  contexts: Record<string, BoundedContext>;
}
```

[typescript/wow-client/src/client/metadata/wowMetadata.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/client/metadata/wowMetadata.ts)

### WowMetadataClient {#api-WowMetadataClient}

```ts
export class WowMetadataClient implements ApiMetadataCapable {
    constructor(public readonly apiMetadata?: ApiMetadata);
    metadata(attributes?: Record<string, unknown>, abort?: AbortController | AbortSignal): Promise<WowMetadata>;
}
```

[typescript/wow-client/src/client/metadata/wowMetadataClient.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/client/metadata/wowMetadataClient.ts)

### QueryClientOptions {#api-QueryClientOptions}

```ts
export interface QueryClientOptions
  extends
    PartialBy<ApiMetadata, 'basePath'>,
    Partial<AliasBoundedContext>,
    Partial<AggregateNameCapable> {
  contextAlias?: string;
  resourceAttribution?: ResourceAttributionPathSpec;
}
```

[typescript/wow-client/src/client/query/factory.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/client/query/factory.ts)

### QueryClientFactory {#api-QueryClientFactory}

```ts
export class QueryClientFactory<S, FIELDS extends string = string, DomainEventBody = unknown> {
    constructor(private readonly defaultOptions: QueryClientOptions);
    createSnapshotQueryClient(options?: QueryClientOptions): SnapshotQueryClient<S, FIELDS>;
    createLoadStateAggregateClient(options?: QueryClientOptions): LoadStateAggregateClient<S>;
    createLoadOwnerStateAggregateClient(options?: QueryClientOptions): LoadOwnerStateAggregateClient<S>;
    createEventStreamQueryClient<EVENT_FIELDS extends string = string>(options?: QueryClientOptions): EventStreamQueryClient<DomainEventBody, EVENT_FIELDS>;
    createQueryDescriptorClient(options?: QueryClientOptions): QueryDescriptorClient;
}
```

[typescript/wow-client/src/client/query/factory.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/client/query/factory.ts)

## 相关专题

[命令与等待结果](./commands) · [快照查询](./snapshot-queries) · [过滤表达式与旧条件](./filters) · [投影、排序与分页](./query-options) · [游标查询](./cursor-queries) · [聚合构造器](./aggregations) · [事件与历史状态](./events-and-history) · [身份与资源归属](./identity-and-attribution)
