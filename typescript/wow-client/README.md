# @ahoo-wang/fetcher-wow

[![npm version](https://img.shields.io/npm/v/@ahoo-wang/fetcher-wow.svg)](https://www.npmjs.com/package/@ahoo-wang/fetcher-wow)
[![Build Status](https://github.com/Ahoo-Wang/fetcher/actions/workflows/ci.yml/badge.svg)](https://github.com/Ahoo-Wang/fetcher/actions)
[![codecov](https://codecov.io/gh/Ahoo-Wang/fetcher/graph/badge.svg?token=JGiWZ52CvJ)](https://codecov.io/gh/Ahoo-Wang/fetcher)
[![License](https://img.shields.io/npm/l/@ahoo-wang/fetcher-wow.svg)](https://github.com/Ahoo-Wang/fetcher/blob/main/LICENSE)
[![npm downloads](https://img.shields.io/npm/dm/@ahoo-wang/fetcher-wow.svg)](https://www.npmjs.com/package/@ahoo-wang/fetcher-wow)
[![npm bundle size](https://img.shields.io/bundlephobia/minzip/%40ahoo-wang%2Ffetcher-wow)](https://www.npmjs.com/package/@ahoo-wang/fetcher-wow)
[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/Ahoo-Wang/fetcher)

Support for [Wow](https://github.com/Ahoo-Wang/Wow) framework in Fetcher. Provides TypeScript types and utilities for
working with the Wow CQRS/DDD framework.

## 🌟 Features

- **🔄 CQRS Pattern Implementation**: First-class support for Command Query Responsibility Segregation architectural
  pattern
- **🧱 DDD Primitives**: Essential Domain-Driven Design building blocks including aggregates, events, and value objects
- **📦 Complete TypeScript Support**: Full type definitions for all Wow framework entities including commands, events,
  and queries
- **📡 Real-time Event Streaming**: Built-in support for Server-Sent Events to receive real-time command results and data
  updates
- **🚀 Command Client**: High-level client for sending commands to Wow services with both synchronous and streaming
  responses
- **🔍 Powerful Query DSL**: Rich query condition builder with comprehensive operator support for complex querying
- **🔍 Query Clients**: Specialized clients for querying snapshot and event stream data with comprehensive query
  operations:
    - Counting resources
    - Listing resources
    - Streaming resources as Server-Sent Events
    - Paging resources
    - Retrieving single resources

## 🚀 Quick Start

### Installation

```bash
# Using npm
npm install @ahoo-wang/fetcher-wow

# Using pnpm
pnpm add @ahoo-wang/fetcher-wow

# Using yarn
yarn add @ahoo-wang/fetcher-wow
```

## 📚 API Reference

### Command Module

#### CommandResult

Interface representing the result of command execution:

```typescript
import { CommandResult, CommandStage } from '@ahoo-wang/fetcher-wow';
```

#### CommandClient

HTTP client for sending commands to the Wow framework. The client provides methods to send commands and receive results
either synchronously or as a stream of events.

```typescript
import { Fetcher, FetchExchange, RequestInterceptor, URL_RESOLVE_INTERCEPTOR_ORDER } from '@ahoo-wang/fetcher';
import '@ahoo-wang/fetcher-eventstream';
import {
  CommandClient,
  CommandRequest,
  HttpMethod,
  CommandHttpHeaders,
  CommandStage
} from '@ahoo-wang/fetcher-wow';
import { idGenerator } from '@ahoo-wang/fetcher-cosec';

// Create a fetcher instance with base configuration
const exampleFetcher = new Fetcher({
  baseURL: 'http://localhost:8080/',
});

// Define current user ID
const currentUserId = idGenerator.generateId();

// Create an interceptor to handle URL parameters
class AppendOwnerId implements RequestInterceptor {
  readonly name: string = 'AppendOwnerId';
  readonly order: number = URL_RESOLVE_INTERCEPTOR_ORDER - 1;

  intercept(exchange: FetchExchange) {
    exchange.request.urlParams = {
      path: {
        ...exchange.request.urlParams?.path,
        ownerId: currentUserId,
      },
      query: exchange.request.urlParams?.query,
    };
  }
}

// Register the interceptor
exampleFetcher.interceptors.request.use(new AppendOwnerId());

// Create the command client
const cartCommandClient = new CommandClient({
  fetcher: exampleFetcher,
  basePath: 'owner/{ownerId}/cart',
});

// Define command endpoints
class CartCommandEndpoints {
  static readonly addCartItem = 'add_cart_item';
}

// Define command interfaces
interface AddCartItem {
  productId: string;
  quantity: number;
}

type AddCartItemCommand = CommandRequest<AddCartItem>

// Create a command request
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

// Send command and wait for result
const commandResult = await cartCommandClient.send(
  CartCommandEndpoints.addCartItem,
  addCartItemCommand,
);

// Send command and receive results as a stream of events
const commandResultStream = await cartCommandClient.sendAndWaitStream(
  CartCommandEndpoints.addCartItem,
  addCartItemCommand,
);
for await (const commandResultEvent of commandResultStream) {
  console.log('Received command result:', commandResultEvent.data);
}
```

##### Methods

- `send(path: string, commandRequest: CommandRequest): Promise<CommandResult>` - Sends a command and waits for the
  result.
- `sendAndWaitStream(path: string, commandRequest: CommandRequest): Promise<CommandResultEventStream>` - Sends a command
  and returns a stream of results as Server-Sent Events.

### Query Module

#### Condition Builder

Comprehensive query condition builder with operator support:

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

// Simple conditions
const simpleConditions = [
  eq('name', 'John'),
  ne('status', 'inactive'),
  gt('age', 18),
  lt('score', 100),
];

// Complex conditions
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

// Date conditions
const dateConditions = [
  today('createdAt'),
  beforeToday('lastLogin', 7), // Within last 7 days
  thisWeek('updatedAt'),
  lastMonth('createdDate'),
];
```

#### SnapshotQueryClient

Client for querying materialized snapshots with comprehensive query operations:

```typescript
import { Fetcher, FetchExchange, RequestInterceptor, URL_RESOLVE_INTERCEPTOR_ORDER } from '@ahoo-wang/fetcher';
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

// Create a fetcher instance with base configuration
const exampleFetcher = new Fetcher({
  baseURL: 'http://localhost:8080/',
});

// Define current user ID
const currentUserId = idGenerator.generateId();

// Create an interceptor to handle URL parameters
class AppendOwnerId implements RequestInterceptor {
  readonly name: string = 'AppendOwnerId';
  readonly order: number = URL_RESOLVE_INTERCEPTOR_ORDER - 1;

  intercept(exchange: FetchExchange) {
    exchange.request.urlParams = {
      path: {
        ...exchange.request.urlParams?.path,
        ownerId: currentUserId,
      },
      query: exchange.request.urlParams?.query,
    };
  }
}

// Register the interceptor
exampleFetcher.interceptors.request.use(new AppendOwnerId());

// Create the snapshot query client
const cartSnapshotQueryClient = new SnapshotQueryClient<CartState>({
  fetcher: exampleFetcher,
  basePath: 'owner/{ownerId}/cart',
});

// Count snapshots
const count = await cartSnapshotQueryClient.count(all());

// List snapshots
const listQuery: ListQuery = {
  condition: all(),
};
const list = await cartSnapshotQueryClient.list(listQuery);

// List snapshots as stream
const listStream = await cartSnapshotQueryClient.listStream(listQuery);
for await (const event of listStream) {
  const snapshot = event.data;
  console.log('Received snapshot:', snapshot);
}

// List snapshot states
const stateList = await cartSnapshotQueryClient.listState(listQuery);

// List snapshot states as stream
const stateStream = await cartSnapshotQueryClient.listStateStream(listQuery);
for await (const event of stateStream) {
  const state = event.data;
  console.log('Received state:', state);
}

// Paged snapshots
const pagedQuery: PagedQuery = {
  condition: all(),
};
const paged = await cartSnapshotQueryClient.paged(pagedQuery);

// Paged snapshot states
const pagedState = await cartSnapshotQueryClient.pagedState(pagedQuery);

// Single snapshot
const singleQuery: SingleQuery = {
  condition: all(),
};
const single = await cartSnapshotQueryClient.single(singleQuery);

// Single snapshot state
const singleState = await cartSnapshotQueryClient.singleState(singleQuery);
```

##### Methods

- `count(condition: Condition): Promise<number>` - Counts the number of snapshots that match the given condition.
- `list(listQuery: ListQuery): Promise<Partial<MaterializedSnapshot<S>>[]>` - Retrieves a list of materialized
  snapshots.
- `listStream(listQuery: ListQuery): Promise<ReadableStream<JsonServerSentEvent<Partial<MaterializedSnapshot<S>>>>>` -
  Retrieves a stream of materialized snapshots as Server-Sent Events.
- `listState(listQuery: ListQuery): Promise<Partial<S>[]>` - Retrieves a list of snapshot states.
- `listStateStream(listQuery: ListQuery): Promise<ReadableStream<JsonServerSentEvent<Partial<S>>>>` - Retrieves a stream
  of snapshot states as Server-Sent Events.
- `paged(pagedQuery: PagedQuery): Promise<PagedList<Partial<MaterializedSnapshot<S>>>>` - Retrieves a paged list of
  materialized snapshots.
- `pagedState(pagedQuery: PagedQuery): Promise<PagedList<Partial<S>>>` - Retrieves a paged list of snapshot states.
- `single(singleQuery: SingleQuery): Promise<Partial<MaterializedSnapshot<S>>>` - Retrieves a single materialized
  snapshot.
- `singleState(singleQuery: SingleQuery): Promise<Partial<S>>` - Retrieves a single snapshot state.

#### EventStreamQueryClient

Client for querying domain event streams with comprehensive query operations:

```typescript
import { Fetcher, FetchExchange, RequestInterceptor, URL_RESOLVE_INTERCEPTOR_ORDER } from '@ahoo-wang/fetcher';
import '@ahoo-wang/fetcher-eventstream';
import {
  EventStreamQueryClient,
  all,
  ListQuery,
  PagedQuery,
} from '@ahoo-wang/fetcher-wow';
import { idGenerator } from '@ahoo-wang/fetcher-cosec';

// Create a fetcher instance with base configuration
const exampleFetcher = new Fetcher({
  baseURL: 'http://localhost:8080/',
});

// Define current user ID
const currentUserId = idGenerator.generateId();

// Create an interceptor to handle URL parameters
class AppendOwnerId implements RequestInterceptor {
  readonly name: string = 'AppendOwnerId';
  readonly order: number = URL_RESOLVE_INTERCEPTOR_ORDER - 1;

  intercept(exchange: FetchExchange) {
    exchange.request.urlParams = {
      path: {
        ...exchange.request.urlParams?.path,
        ownerId: currentUserId,
      },
      query: exchange.request.urlParams?.query,
    };
  }
}

// Register the interceptor
exampleFetcher.interceptors.request.use(new AppendOwnerId());

// Create the event stream query client
const cartEventStreamQueryClient = new EventStreamQueryClient({
  fetcher: exampleFetcher,
  basePath: 'owner/{ownerId}/cart',
});

// Count event streams
const count = await cartEventStreamQueryClient.count(all());

// List event streams
const listQuery: ListQuery = {
  condition: all(),
};
const list = await cartEventStreamQueryClient.list(listQuery);

// List event streams as stream
const listStream = await cartEventStreamQueryClient.listStream(listQuery);
for await (const event of listStream) {
  const domainEventStream = event.data;
  console.log('Received event stream:', domainEventStream);
}

// Paged event streams
const pagedQuery: PagedQuery = {
  condition: all(),
};
const paged = await cartEventStreamQueryClient.paged(pagedQuery);
```

##### Methods

- `count(condition: Condition): Promise<number>` - Counts the number of domain event streams that match the given
  condition.
- `list(listQuery: ListQuery): Promise<Partial<DomainEventStream>[]>` - Retrieves a list of domain event streams.
- `listStream(listQuery: ListQuery): Promise<ReadableStream<JsonServerSentEvent<Partial<DomainEventStream>>>>` -
  Retrieves a stream of domain event streams as Server-Sent Events.
- `paged(pagedQuery: PagedQuery): Promise<PagedList<Partial<DomainEventStream>>>` - Retrieves a paged list of domain
  event streams.

## 🛠️ Advanced Usage

### Complete Example with Command and Query Flow

```typescript
import { Fetcher, FetchExchange, RequestInterceptor, URL_RESOLVE_INTERCEPTOR_ORDER } from '@ahoo-wang/fetcher';
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

// Create a fetcher instance
const exampleFetcher = new Fetcher({
  baseURL: 'http://localhost:8080/',
});

// Define current user ID
const currentUserId = idGenerator.generateId();

// Create an interceptor to handle URL parameters
class AppendOwnerId implements RequestInterceptor {
  readonly name: string = 'AppendOwnerId';
  readonly order: number = URL_RESOLVE_INTERCEPTOR_ORDER - 1;

  intercept(exchange: FetchExchange) {
    exchange.request.urlParams = {
      path: {
        ...exchange.request.urlParams?.path,
        ownerId: currentUserId,
      },
      query: exchange.request.urlParams?.query,
    };
  }
}

// Register the interceptor
exampleFetcher.interceptors.request.use(new AppendOwnerId());

// Create clients
const cartCommandClient = new CommandClient({
  fetcher: exampleFetcher,
  basePath: 'owner/{ownerId}/cart',
});

const cartSnapshotQueryClient = new SnapshotQueryClient<CartState>({
  fetcher: exampleFetcher,
  basePath: 'owner/{ownerId}/cart',
});

// Define command endpoints
class CartCommandEndpoints {
  static readonly addCartItem = 'add_cart_item';
}

// Define command interfaces
interface AddCartItem {
  productId: string;
  quantity: number;
}

type AddCartItemCommand = CommandRequest<AddCartItem>

// 1. Send command to add item to cart
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
  addItemCommand
);
console.log('Command executed:', commandResult);

// 2. Query the updated cart
const listQuery: ListQuery = {
  condition: all(),
};
const carts = await cartSnapshotQueryClient.list(listQuery);

for (const cart of carts) {
  console.log('Cart:', cart.state);
}

// 3. Stream cart updates
const listStream = await cartSnapshotQueryClient.listStream(listQuery);
for await (const event of listStream) {
  const cart = event.data;
  console.log('Cart updated:', cart.state);
}
```

## 🧪 Testing

```bash
# Run tests
pnpm test

# Run tests with coverage
pnpm test --coverage
```

## 🤝 Contributing

Contributions are welcome! Please see
the [contributing guide](https://github.com/Ahoo-Wang/fetcher/blob/main/CONTRIBUTING.md) for more details.

## 📄 License

Apache-2.0

---

<p align="center">
  Part of the <a href="https://github.com/Ahoo-Wang/fetcher">Fetcher</a> ecosystem
</p>