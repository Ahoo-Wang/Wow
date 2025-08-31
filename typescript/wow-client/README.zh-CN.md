# @ahoo-wang/fetcher-wow

[![npm version](https://img.shields.io/npm/v/@ahoo-wang/fetcher-wow.svg)](https://www.npmjs.com/package/@ahoo-wang/fetcher-wow)
[![Build Status](https://github.com/Ahoo-Wang/fetcher/actions/workflows/ci.yml/badge.svg)](https://github.com/Ahoo-Wang/fetcher/actions)
[![codecov](https://codecov.io/gh/Ahoo-Wang/fetcher/graph/badge.svg?token=JGiWZ52CvJ)](https://codecov.io/gh/Ahoo-Wang/fetcher)
[![License](https://img.shields.io/npm/l/@ahoo-wang/fetcher-wow.svg)](https://github.com/Ahoo-Wang/fetcher/blob/main/LICENSE)
[![npm downloads](https://img.shields.io/npm/dm/@ahoo-wang/fetcher-wow.svg)](https://www.npmjs.com/package/@ahoo-wang/fetcher-wow)
[![npm bundle size](https://img.shields.io/bundlephobia/minzip/%40ahoo-wang%2Ffetcher-wow)](https://www.npmjs.com/package/@ahoo-wang/fetcher-wow)
[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/Ahoo-Wang/fetcher)

为 [Wow](https://github.com/Ahoo-Wang/Wow) 框架提供支持。提供用于与 Wow CQRS/DDD 框架配合使用的 TypeScript 类型和工具。

## 🌟 特性

- **📦 完整的 TypeScript 支持**：为所有 Wow 框架实体提供完整的类型定义，包括命令、事件和查询
- **🚀 命令客户端**：用于向 Wow 服务发送命令的高级客户端，支持同步和流式响应
- **🔍 强大的查询 DSL**：丰富的查询条件构建器，支持全面的操作符用于复杂查询
- **📡 实时事件流**：内置对服务器发送事件的支持，用于接收实时命令结果和数据更新
- **🔄 CQRS 模式实现**：对命令查询责任分离架构模式的一流支持
- **🧱 DDD 基础构件**：基本的领域驱动设计构建块，包括聚合、事件和值对象
- **🔍 查询客户端**：专门用于查询快照和事件流数据的客户端，支持全面的查询操作

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
import { Fetcher, URL_RESOLVE_INTERCEPTOR_ORDER } from '@ahoo-wang/fetcher';
import '@ahoo-wang/fetcher-eventstream';
import {
  CommandClient,
  CommandRequest,
  HttpMethod,
  CommandHttpHeaders,
  CommandStage,
} from '@ahoo-wang/fetcher-wow';
import { idGenerator } from '@ahoo-wang/fetcher-cosec';

// 创建 fetcher 实例
const wowFetcher = new Fetcher({
  baseURL: 'http://localhost:8080/',
});

// 添加拦截器处理 URL 参数
const ownerId = idGenerator.generateId();
wowFetcher.interceptors.request.use({
  name: 'AppendOwnerId',
  order: URL_RESOLVE_INTERCEPTOR_ORDER - 1,
  intercept(exchange) {
    exchange.request.urlParams = {
      path: {
        ...exchange.request.urlParams?.path,
        ownerId,
      },
      query: exchange.request.urlParams?.query,
    };
  },
});

// 创建命令客户端
const commandClient = new CommandClient({
  fetcher: wowFetcher,
  basePath: 'owner/{ownerId}/cart',
});

// 定义命令请求
const command: CommandRequest = {
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
const commandResult = await commandClient.send('add_cart_item', command);

// 发送命令并接收流式结果
const commandResultStream = await commandClient.sendAndWaitStream(
  'add_cart_item',
  command,
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
  contains,
  isIn,
  between,
  today,
  active,
} from '@ahoo-wang/fetcher-wow';

// 简单条件
const simpleConditions = [
  eq('name', 'John'),
  ne('status', 'inactive'),
  gt('age', 18),
  lt('score', 100),
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

// 日期条件
const dateConditions = [
  today('createdAt'),
  beforeToday('lastLogin', 7), // 最近7天内
  thisWeek('updatedAt'),
  lastMonth('createdDate'),
];
```

#### SnapshotQueryClient

用于查询物化快照的客户端：

```typescript
import { Fetcher, URL_RESOLVE_INTERCEPTOR_ORDER } from '@ahoo-wang/fetcher';
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

interface CartState {
  id: string;
  items: CartItem[];
}

// 创建 fetcher 实例
const wowFetcher = new Fetcher({
  baseURL: 'http://localhost:8080/',
});

// 添加拦截器处理 URL 参数
const ownerId = idGenerator.generateId();
wowFetcher.interceptors.request.use({
  name: 'AppendOwnerId',
  order: URL_RESOLVE_INTERCEPTOR_ORDER - 1,
  intercept(exchange) {
    exchange.request.urlParams = {
      path: {
        ...exchange.request.urlParams?.path,
        ownerId,
      },
      query: exchange.request.urlParams?.query,
    };
  },
});

// 创建快照查询客户端
const snapshotQueryClient = new SnapshotQueryClient<CartState>({
  fetcher: wowFetcher,
  basePath: 'owner/{ownerId}/cart',
});

// 统计快照数量
const count = await snapshotQueryClient.count(all());

// 列出快照
const listQuery: ListQuery = {
  condition: all(),
};
const list = await snapshotQueryClient.list(listQuery);

// 以流的形式列出快照
const listStream = await snapshotQueryClient.listStream(listQuery);
for await (const event of listStream) {
  const snapshot = event.data;
  console.log('收到快照:', snapshot);
}

// 列出快照状态
const stateList = await snapshotQueryClient.listState(listQuery);

// 以流的形式列出快照状态
const stateStream = await snapshotQueryClient.listStateStream(listQuery);
for await (const event of stateStream) {
  const state = event.data;
  console.log('收到状态:', state);
}

// 分页查询快照
const pagedQuery: PagedQuery = {
  condition: all(),
};
const paged = await snapshotQueryClient.paged(pagedQuery);

// 分页查询快照状态
const pagedState = await snapshotQueryClient.pagedState(pagedQuery);

// 查询单个快照
const singleQuery: SingleQuery = {
  condition: all(),
};
const single = await snapshotQueryClient.single(singleQuery);

// 查询单个快照状态
const singleState = await snapshotQueryClient.singleState(singleQuery);
```

#### EventStreamQueryClient

用于查询领域事件流的客户端：

```typescript
import { Fetcher, URL_RESOLVE_INTERCEPTOR_ORDER } from '@ahoo-wang/fetcher';
import '@ahoo-wang/fetcher-eventstream';
import {
  EventStreamQueryClient,
  all,
  ListQuery,
  PagedQuery,
} from '@ahoo-wang/fetcher-wow';
import { idGenerator } from '@ahoo-wang/fetcher-cosec';

// 创建 fetcher 实例
const wowFetcher = new Fetcher({
  baseURL: 'http://localhost:8080/',
});

// 添加拦截器处理 URL 参数
const ownerId = idGenerator.generateId();
wowFetcher.interceptors.request.use({
  name: 'AppendOwnerId',
  order: URL_RESOLVE_INTERCEPTOR_ORDER - 1,
  intercept(exchange) {
    exchange.request.urlParams = {
      path: {
        ...exchange.request.urlParams?.path,
        ownerId,
      },
      query: exchange.request.urlParams?.query,
    };
  },
});

// 创建事件流查询客户端
const eventStreamQueryClient = new EventStreamQueryClient({
  fetcher: wowFetcher,
  basePath: 'owner/{ownerId}/cart',
});

// 统计事件流数量
const count = await eventStreamQueryClient.count(all());

// 列出事件流
const listQuery: ListQuery = {
  condition: all(),
};
const list = await eventStreamQueryClient.list(listQuery);

// 以流的形式列出事件流
const listStream = await eventStreamQueryClient.listStream(listQuery);
for await (const event of listStream) {
  const domainEventStream = event.data;
  console.log('收到事件流:', domainEventStream);
}

// 分页查询事件流
const pagedQuery: PagedQuery = {
  condition: all(),
};
const paged = await eventStreamQueryClient.paged(pagedQuery);
```

## 🛠️ 高级用法

### 完整的命令和查询流程示例

```typescript
import { Fetcher, URL_RESOLVE_INTERCEPTOR_ORDER } from '@ahoo-wang/fetcher';
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
const wowFetcher = new Fetcher({
  baseURL: 'http://localhost:8080/',
});

// 添加拦截器处理 URL 参数
const ownerId = idGenerator.generateId();
wowFetcher.interceptors.request.use({
  name: 'AppendOwnerId',
  order: URL_RESOLVE_INTERCEPTOR_ORDER - 1,
  intercept(exchange) {
    exchange.request.urlParams = {
      path: {
        ...exchange.request.urlParams?.path,
        ownerId,
      },
      query: exchange.request.urlParams?.query,
    };
  },
});

// 创建客户端
const commandClient = new CommandClient({
  fetcher: wowFetcher,
  basePath: 'owner/{ownerId}/cart',
});

const snapshotQueryClient = new SnapshotQueryClient<CartState>({
  fetcher: wowFetcher,
  basePath: 'owner/{ownerId}/cart',
});

// 1. 发送命令添加商品到购物车
const addItemCommand: CommandRequest = {
  method: HttpMethod.POST,
  headers: {
    [CommandHttpHeaders.WAIT_STAGE]: CommandStage.SNAPSHOT,
  },
  body: {
    productId: 'product-123',
    quantity: 2,
  },
};

const commandResult = await commandClient.send('add_cart_item', addItemCommand);
console.log('命令执行完成:', commandResult);

// 2. 查询更新后的购物车
const listQuery: ListQuery = {
  condition: all(),
};
const carts = await snapshotQueryClient.list(listQuery);

for (const cart of carts) {
  console.log('购物车:', cart.state);
}

// 3. 流式监听购物车更新
const listStream = await snapshotQueryClient.listStream(listQuery);
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
