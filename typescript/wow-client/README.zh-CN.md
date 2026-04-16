# @ahoo-wang/fetcher-wow

[![npm version](https://img.shields.io/npm/v/@ahoo-wang/fetcher-wow.svg)](https://www.npmjs.com/package/@ahoo-wang/fetcher-wow)
[![Build Status](https://github.com/Ahoo-Wang/fetcher/actions/workflows/ci.yml/badge.svg)](https://github.com/Ahoo-Wang/fetcher/actions)
[![codecov](https://codecov.io/gh/Ahoo-Wang/fetcher/graph/badge.svg?token=JGiWZ52CvJ)](https://codecov.io/gh/Ahoo-Wang/fetcher)
[![License](https://img.shields.io/npm/l/@ahoo-wang/fetcher-wow.svg)](https://github.com/Ahoo-Wang/fetcher/blob/main/LICENSE)
[![npm downloads](https://img.shields.io/npm/dm/@ahoo-wang/fetcher-wow.svg)](https://www.npmjs.com/package/@ahoo-wang/fetcher-wow)
[![npm bundle size](https://img.shields.io/bundlephobia/minzip/%40ahoo-wang%2Ffetcher-wow)](https://www.npmjs.com/package/@ahoo-wang/fetcher-wow)
[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/Ahoo-Wang/fetcher)
[![Storybook](https://img.shields.io/badge/Storybook-交互式文档-FF4785)](https://fetcher.ahoo.me/?path=/docs/wow-introduction--docs)

为 [Wow](https://github.com/Ahoo-Wang/Wow) 框架提供支持。提供用于与 Wow CQRS/DDD 框架配合使用的 TypeScript 类型和工具。

## 🌟 特性

- **📦 完整的 TypeScript 支持**：为所有 Wow 框架实体提供完整的类型定义，包括命令、事件和查询
- **🚀 命令客户端**：用于向 Wow 服务发送命令的高级客户端，支持同步和流式响应
- **🔍 强大的查询 DSL**：丰富的查询条件构建器，支持全面的操作符用于复杂查询
- **📡 实时事件流**：内置对服务器发送事件的支持，用于接收实时命令结果和数据更新
- **🔄 CQRS 模式实现**：对命令查询责任分离架构模式的一流支持
- **🧱 DDD 基础构件**：基本的领域驱动设计构建块，包括聚合、事件和值对象
- **🔍 查询客户端**：专门用于查询快照和事件流数据的客户端，支持全面的查询操作：
  - 资源计数
  - 资源列表查询
  - 以服务器发送事件形式流式传输资源
  - 资源分页
  - 单个资源检索

## 🚀 快速开始

### 安装

```bash
# 使用 npm
npm install @ahoo-wang/fetcher-wow

# 使用 pnpm
pnpm add @ahoo-wang/fetcher-wow

# 使用 yarn
yarn add @ahoo-wang/fetcher-wow
```

## 📚 API 参考

### 命令模块

#### CommandResult

表示命令执行结果的接口：

```typescript
import { CommandResult, CommandStage } from '@ahoo-wang/fetcher-wow';
```

#### CommandClient

用于向 Wow 框架发送命令的 HTTP 客户端。该客户端提供了同步或流式接收命令结果的方法。

```typescript
import {
  Fetcher,
  FetchExchange,
  RequestInterceptor,
  URL_RESOLVE_INTERCEPTOR_ORDER,
} from '@ahoo-wang/fetcher';
import '@ahoo-wang/fetcher-eventstream';
import {
  CommandClient,
  CommandRequest,
  HttpMethod,
  CommandHttpHeaders,
  CommandStage,
} from '@ahoo-wang/fetcher-wow';
import { idGenerator } from '@ahoo-wang/fetcher-cosec';

// 使用基础配置创建 fetcher 实例
const exampleFetcher = new Fetcher({
  baseURL: 'http://localhost:8080/',
});

// 定义当前用户 ID
const currentUserId = idGenerator.generateId();

// 创建处理 URL 参数的拦截器
class AppendOwnerId implements RequestInterceptor {
  readonly name: string = 'AppendOwnerId';
  readonly order: number = URL_RESOLVE_INTERCEPTOR_ORDER - 1;

  intercept(exchange: FetchExchange) {
    const urlParams = exchange.ensureRequestUrlParams();
    urlParams.path['ownerId'] = currentUserId;
  }
}

// 注册拦截器
exampleFetcher.interceptors.request.use(new AppendOwnerId());

// 创建命令客户端
const cartCommandClient = new CommandClient({
  fetcher: exampleFetcher,
  basePath: 'owner/{ownerId}/cart',
});

// 定义命令端点
class CartCommandEndpoints {
  static readonly addCartItem = 'add_cart_item';
}

// 定义命令接口
interface AddCartItem {
  productId: string;
  quantity: number;
}

type AddCartItemCommand = CommandRequest<AddCartItem>;

// 创建命令请求
const addCartItemCommand: AddCartItemCommand = {
  method: HttpMethod.POST,
  headers: {
    [CommandHttpHeaders.WAIT_STAGE]: CommandStage.SNAPSHOT,
  },
  body: {
    productId: 'productId',
    quantity: 1,
  },
};

// 发送命令并等待结果
const commandResult = await cartCommandClient.send(
  CartCommandEndpoints.addCartItem,
  addCartItemCommand,
);

// 发送命令并接收流式结果
const commandResultStream = await cartCommandClient.sendAndWaitStream(
  CartCommandEndpoints.addCartItem,
  addCartItemCommand,
);
for await (const commandResultEvent of commandResultStream) {
  console.log('收到命令结果:', commandResultEvent.data);
}
```

##### 方法

- `send(path: string, commandRequest: CommandRequest): Promise<CommandResult>` - 发送命令并等待结果。
- `sendAndWaitStream(path: string, commandRequest: CommandRequest): Promise<CommandResultEventStream>` -
  发送命令并以服务器发送事件的形式返回结果流。

### 查询模块

#### 条件构建器

支持操作符的综合查询条件构建器：

```typescript
import {
  and,
  or,
  eq,
  ne,
  gt,
  lt,
  gte,
  lte,
  contains,
  isIn,
  notIn,
  between,
  allIn,
  startsWith,
  endsWith,
  match,
  elemMatch,
  isNull,
  notNull,
  isTrue,
  isFalse,
  exists,
  raw,
  today,
  beforeToday,
  tomorrow,
  thisWeek,
  nextWeek,
  lastWeek,
  thisMonth,
  lastMonth,
  recentDays,
  earlierDays,
  active,
  all,
  id,
  ids,
  aggregateId,
  aggregateIds,
  tenantId,
  ownerId,
} from '@ahoo-wang/fetcher-wow';

// 简单条件
const simpleConditions = [
  eq('name', 'John'),
  ne('status', 'inactive'),
  gt('age', 18),
  lt('score', 100),
  gte('rating', 4.0),
  lte('price', 100),
];

// 字符串条件
const stringConditions = [
  contains('email', '@company.com'),
  startsWith('username', 'j'),
  endsWith('domain', '.com'),
  isIn('status', 'active', 'pending'),
  notIn('role', 'guest', 'banned'),
  match('description', 'search keywords'),
];

// 空值检查
const nullConditions = [
  isNull('deletedAt'),
  notNull('email'),
  isTrue('isActive'),
  isFalse('isDeleted'),
  exists('phoneNumber'),
];

// 数组条件
const arrayConditions = [
  allIn('tags', 'react', 'typescript'),
  elemMatch('items', eq('quantity', 0)),
];

// 日期条件
const dateConditions = [
  today('createdAt'),
  beforeToday('lastLogin', 7), // 7天前（即过去7天内）
  tomorrow('scheduledDate'),
  thisWeek('updatedAt'),
  nextWeek('startDate'),
  lastWeek('endDate'),
  thisMonth('createdDate'),
  lastMonth('expirationDate'),
  recentDays('createdAt', 5), // 最近5天，包括今天
  earlierDays('createdAt', 3), // 3天之前
];

// 复杂条件
const complexCondition = and(
  eq('tenantId', 'tenant-123'),
  or(
    contains('email', '@company.com'),
    isIn('department', 'engineering', 'marketing'),
  ),
  between('salary', 50000, 100000),
  today('createdAt'),
  active(),
);

// 高级用法的原始条件
const rawCondition = raw({ $text: { $search: 'keywords' } });
```

**操作符参考：**

| 类别 | 操作符 |
|----------|-----------|
| 逻辑 | `and`, `or`, `nor` |
| 比较 | `eq`, `ne`, `gt`, `lt`, `gte`, `lte` |
| 字符串 | `contains`, `startsWith`, `endsWith`, `match` |
| 集合 | `isIn`, `notIn`, `allIn`, `elemMatch` |
| 空值/布尔 | `isNull`, `notNull`, `isTrue`, `isFalse`, `exists` |
| 日期 | `today`, `beforeToday(days)`, `tomorrow`, `thisWeek`, `nextWeek`, `lastWeek`, `thisMonth`, `lastMonth`, `recentDays(days)`, `earlierDays(days)` |
| ID | `id`, `ids`, `aggregateId`, `aggregateIds`, `tenantId`, `ownerId` |
| 状态 | `active`, `all`, `deleted` |
| 特殊 | `raw`（用于高级数据库特定查询）

#### SnapshotQueryClient

用于查询物化快照的客户端，支持全面的查询操作：

```typescript
import {
  Fetcher,
  FetchExchange,
  RequestInterceptor,
  URL_RESOLVE_INTERCEPTOR_ORDER,
} from '@ahoo-wang/fetcher';
import '@ahoo-wang/fetcher-eventstream';
import {
  SnapshotQueryClient,
  all,
  ListQuery,
  PagedQuery,
  SingleQuery,
} from '@ahoo-wang/fetcher-wow';
import { idGenerator } from '@ahoo-wang/fetcher-cosec';

interface CartItem {
  productId: string;
  quantity: number;
}

interface CartState extends Identifier {
  items: CartItem[];
}

// 使用基础配置创建 fetcher 实例
const exampleFetcher = new Fetcher({
  baseURL: 'http://localhost:8080/',
});

// 定义当前用户 ID
const currentUserId = idGenerator.generateId();

// 创建处理 URL 参数的拦截器
class AppendOwnerId implements RequestInterceptor {
  readonly name: string = 'AppendOwnerId';
  readonly order: number = URL_RESOLVE_INTERCEPTOR_ORDER - 1;

  intercept(exchange: FetchExchange) {
    const urlParams = exchange.ensureRequestUrlParams();
    urlParams.path['ownerId'] = currentUserId;
  }
}

// 注册拦截器
exampleFetcher.interceptors.request.use(new AppendOwnerId());

// 创建快照查询客户端
const cartSnapshotQueryClient = new SnapshotQueryClient<CartState>({
  fetcher: exampleFetcher,
  basePath: 'owner/{ownerId}/cart',
});

// 统计快照数量
const count = await cartSnapshotQueryClient.count(all());

// 列出快照
const listQuery: ListQuery = {
  condition: all(),
};
const list = await cartSnapshotQueryClient.list(listQuery);

// 以流的形式列出快照
const listStream = await cartSnapshotQueryClient.listStream(listQuery);
for await (const event of listStream) {
  const snapshot = event.data;
  console.log('收到快照:', snapshot);
}

// 列出快照状态
const stateList = await cartSnapshotQueryClient.listState(listQuery);

// 以流的形式列出快照状态
const stateStream = await cartSnapshotQueryClient.listStateStream(listQuery);
for await (const event of stateStream) {
  const state = event.data;
  console.log('收到状态:', state);
}

// 分页查询快照
const pagedQuery: PagedQuery = {
  condition: all(),
};
const paged = await cartSnapshotQueryClient.paged(pagedQuery);

// 分页查询快照状态
const pagedState = await cartSnapshotQueryClient.pagedState(pagedQuery);

// 查询单个快照
const singleQuery: SingleQuery = {
  condition: all(),
};
const single = await cartSnapshotQueryClient.single(singleQuery);

// 查询单个快照状态
const singleState = await cartSnapshotQueryClient.singleState(singleQuery);
```

##### 方法

- `count(condition: Condition): Promise<number>` - 统计匹配给定条件的快照数量。
- `list(listQuery: ListQuery): Promise<Partial<MaterializedSnapshot<S>>[]>` - 检索物化快照列表。
- `listStream(listQuery: ListQuery): Promise<ReadableStream<JsonServerSentEvent<Partial<MaterializedSnapshot<S>>>>>` -
  以服务器发送事件的形式检索物化快照流。
- `listState(listQuery: ListQuery): Promise<Partial<S>[]>` - 检索快照状态列表。
- `listStateStream(listQuery: ListQuery): Promise<ReadableStream<JsonServerSentEvent<Partial<S>>>>` -
  以服务器发送事件的形式检索快照状态流。
- `paged(pagedQuery: PagedQuery): Promise<PagedList<Partial<MaterializedSnapshot<S>>>>` - 检索物化快照的分页列表。
- `pagedState(pagedQuery: PagedQuery): Promise<PagedList<Partial<S>>>` - 检索快照状态的分页列表。
- `single(singleQuery: SingleQuery): Promise<Partial<MaterializedSnapshot<S>>>` - 检索单个物化快照。
- `singleState(singleQuery: SingleQuery): Promise<Partial<S>>` - 检索单个快照状态。

#### QueryClientFactory

用于创建预配置查询客户端的工厂。当您需要具有共享配置的多个客户端时，这非常有用。

```typescript
import {
  QueryClientFactory,
  ResourceAttributionPathSpec,
  all,
} from '@ahoo-wang/fetcher-wow';
import { idGenerator } from '@ahoo-wang/fetcher-cosec';

// 使用默认选项创建工厂
const factory = new QueryClientFactory({
  contextAlias: 'example',
  aggregateName: 'cart',
  resourceAttribution: ResourceAttributionPathSpec.OWNER,
  fetcher: exampleFetcher,
});

// 创建快照查询客户端
const snapshotClient = factory.createSnapshotQueryClient({
  aggregateName: 'cart',
});
const carts = await snapshotClient.listState({ condition: all() });

// 创建状态聚合客户端
const stateClient = factory.createLoadStateAggregateClient({
  aggregateName: 'cart',
});
const cart = await stateClient.load('cart-123');

// 创建事件流查询客户端
const eventClient = factory.createEventStreamQueryClient({
  aggregateName: 'cart',
});
const events = await eventClient.list({ condition: all() });
```

**方法：**

- `createSnapshotQueryClient(options?: QueryClientOptions): SnapshotQueryClient` - 创建用于查询快照的客户端。
- `createLoadStateAggregateClient(options?: QueryClientOptions): LoadStateAggregateClient` - 创建用于按 ID 加载聚合状态的客户端。
- `createOwnerLoadStateAggregateClient(options?: QueryClientOptions): LoadOwnerStateAggregateClient` - 创建用于加载当前所有者聚合状态的客户端。
- `createEventStreamQueryClient(options?: QueryClientOptions): EventStreamQueryClient` - 创建用于查询事件流的客户端。

#### EventStreamQueryClient

用于查询领域事件流的客户端，支持全面的查询操作：

```typescript
import {
  Fetcher,
  FetchExchange,
  RequestInterceptor,
  URL_RESOLVE_INTERCEPTOR_ORDER,
} from '@ahoo-wang/fetcher';
import '@ahoo-wang/fetcher-eventstream';
import {
  EventStreamQueryClient,
  all,
  ListQuery,
  PagedQuery,
} from '@ahoo-wang/fetcher-wow';
import { idGenerator } from '@ahoo-wang/fetcher-cosec';

// 使用基础配置创建 fetcher 实例
const exampleFetcher = new Fetcher({
  baseURL: 'http://localhost:8080/',
});

// 定义当前用户 ID
const currentUserId = idGenerator.generateId();

// 创建处理 URL 参数的拦截器
class AppendOwnerId implements RequestInterceptor {
  readonly name: string = 'AppendOwnerId';
  readonly order: number = URL_RESOLVE_INTERCEPTOR_ORDER - 1;

  intercept(exchange: FetchExchange) {
    const urlParams = exchange.ensureRequestUrlParams();
    urlParams.path['ownerId'] = currentUserId;
  }
}

// 注册拦截器
exampleFetcher.interceptors.request.use(new AppendOwnerId());

// 创建事件流查询客户端
const cartEventStreamQueryClient = new EventStreamQueryClient({
  fetcher: exampleFetcher,
  basePath: 'owner/{ownerId}/cart',
});

// 统计事件流数量
const count = await cartEventStreamQueryClient.count(all());

// 列出事件流
const listQuery: ListQuery = {
  condition: all(),
};
const list = await cartEventStreamQueryClient.list(listQuery);

// 以流的形式列出事件流
const listStream = await cartEventStreamQueryClient.listStream(listQuery);
for await (const event of listStream) {
  const domainEventStream = event.data;
  console.log('收到事件流:', domainEventStream);
}

// 分页查询事件流
const pagedQuery: PagedQuery = {
  condition: all(),
};
const paged = await cartEventStreamQueryClient.paged(pagedQuery);
```

##### 方法

- `count(condition: Condition): Promise<number>` - 统计匹配给定条件的领域事件流数量。
- `list(listQuery: ListQuery): Promise<Partial<DomainEventStream>[]>` - 检索领域事件流列表。
- `listStream(listQuery: ListQuery): Promise<ReadableStream<JsonServerSentEvent<Partial<DomainEventStream>>>>` -
  以服务器发送事件的形式检索领域事件流。
- `paged(pagedQuery: PagedQuery): Promise<PagedList<Partial<DomainEventStream>>>` - 检索领域事件流的分页列表。

## 🛠️ 高级用法

### 完整的命令和查询流程示例

```typescript
import {
  Fetcher,
  FetchExchange,
  RequestInterceptor,
  URL_RESOLVE_INTERCEPTOR_ORDER,
} from '@ahoo-wang/fetcher';
import '@ahoo-wang/fetcher-eventstream';
import {
  CommandClient,
  CommandRequest,
  CommandHttpHeaders,
  CommandStage,
  HttpMethod,
  SnapshotQueryClient,
  all,
  ListQuery,
} from '@ahoo-wang/fetcher-wow';
import { idGenerator } from '@ahoo-wang/fetcher-cosec';

interface CartItem {
  productId: string;
  quantity: number;
}

interface CartState {
  id: string;
  items: CartItem[];
}

// 创建 fetcher 实例
const exampleFetcher = new Fetcher({
  baseURL: 'http://localhost:8080/',
});

// 定义当前用户 ID
const currentUserId = idGenerator.generateId();

// 创建处理 URL 参数的拦截器
class AppendOwnerId implements RequestInterceptor {
  readonly name: string = 'AppendOwnerId';
  readonly order: number = URL_RESOLVE_INTERCEPTOR_ORDER - 1;

  intercept(exchange: FetchExchange) {
    const urlParams = exchange.ensureRequestUrlParams();
    urlParams.path['ownerId'] = currentUserId;
  }
}

// 注册拦截器
exampleFetcher.interceptors.request.use(new AppendOwnerId());

// 创建客户端
const cartCommandClient = new CommandClient({
  fetcher: exampleFetcher,
  basePath: 'owner/{ownerId}/cart',
});

const cartSnapshotQueryClient = new SnapshotQueryClient<CartState>({
  fetcher: exampleFetcher,
  basePath: 'owner/{ownerId}/cart',
});

// 定义命令端点
class CartCommandEndpoints {
  static readonly addCartItem = 'add_cart_item';
}

// 定义命令接口
interface AddCartItem {
  productId: string;
  quantity: number;
}

type AddCartItemCommand = CommandRequest<AddCartItem>;

// 1. 发送命令添加商品到购物车
const addItemCommand: AddCartItemCommand = {
  method: HttpMethod.POST,
  headers: {
    [CommandHttpHeaders.WAIT_STAGE]: CommandStage.SNAPSHOT,
  },
  body: {
    productId: 'product-123',
    quantity: 2,
  },
};

const commandResult = await cartCommandClient.send(
  CartCommandEndpoints.addCartItem,
  addItemCommand,
);
console.log('命令执行完成:', commandResult);

// 2. 查询更新后的购物车
const listQuery: ListQuery = {
  condition: all(),
};
const carts = await cartSnapshotQueryClient.list(listQuery);

for (const cart of carts) {
  console.log('购物车:', cart.state);
}

// 3. 流式监听购物车更新
const listStream = await cartSnapshotQueryClient.listStream(listQuery);
for await (const event of listStream) {
  const cart = event.data;
  console.log('购物车更新:', cart.state);
}
```

## 🧪 测试

```bash
# 运行测试
pnpm test

# 运行带覆盖率的测试
pnpm test --coverage
```

## 🤝 贡献

欢迎贡献！请查看
[贡献指南](https://github.com/Ahoo-Wang/fetcher/blob/main/CONTRIBUTING.md) 获取更多详情。

## 📄 许可证

Apache-2.0

---

<p align="center">
  <a href="https://github.com/Ahoo-Wang/fetcher">Fetcher</a> 生态系统的一部分
</p>
