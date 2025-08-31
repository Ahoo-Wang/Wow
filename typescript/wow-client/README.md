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
  operations

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
import { Fetcher, URL_RESOLVE_INTERCEPTOR_ORDER } from '@ahoo-wang/fetcher';
import '@ahoo-wang/fetcher-eventstream';
import { CommandClient, CommandRequest, HttpMethod, CommandHttpHeaders, CommandStage } from '@ahoo-wang/fetcher-wow';
import { idGenerator } from '@ahoo-wang/fetcher-cosec';

// Create a fetcher instance
const wowFetcher = new Fetcher({
  baseURL: 'http://localhost:8080/',
});

// Add interceptor to handle URL parameters
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

// Create the command client
const commandClient = new CommandClient({
  fetcher: wowFetcher,
  basePath: 'owner/{ownerId}/cart'
});

// Define a command request
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

// Send command and wait for result
const commandResult = await commandClient.send('add_cart_item', command);

// Send command and receive results as a stream of events
const commandResultStream = await commandClient.sendAndWaitStream('add_cart_item', command);
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

Client for querying materialized snapshots:

```typescript
import { Fetcher, URL_RESOLVE_INTERCEPTOR_ORDER } from '@ahoo-wang/fetcher';
import '@ahoo-wang/fetcher-eventstream';
import {
  SnapshotQueryClient,
  all,
  ListQuery,
  PagedQuery,
  SingleQuery
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
const wowFetcher = new Fetcher({
  baseURL: 'http://localhost:8080/',
});

// Add interceptor to handle URL parameters
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

// Create the snapshot query client
const snapshotQueryClient = new SnapshotQueryClient<CartState>({
  fetcher: wowFetcher,
  basePath: 'owner/{ownerId}/cart'
});

// Count snapshots
const count = await snapshotQueryClient.count(all());

// List snapshots
const listQuery: ListQuery = {
  condition: all(),
};
const list = await snapshotQueryClient.list(listQuery);

// List snapshots as stream
const listStream = await snapshotQueryClient.listStream(listQuery);
for await (const event of listStream) {
  const snapshot = event.data;
  console.log('Received snapshot:', snapshot);
}

// List snapshot states
const stateList = await snapshotQueryClient.listState(listQuery);

// List snapshot states as stream
const stateStream = await snapshotQueryClient.listStateStream(listQuery);
for await (const event of stateStream) {
  const state = event.data;
  console.log('Received state:', state);
}

// Paged snapshots
const pagedQuery: PagedQuery = {
  condition: all(),
};
const paged = await snapshotQueryClient.paged(pagedQuery);

// Paged snapshot states
const pagedState = await snapshotQueryClient.pagedState(pagedQuery);

// Single snapshot
const singleQuery: SingleQuery = {
  condition: all(),
};
const single = await snapshotQueryClient.single(singleQuery);

// Single snapshot state
const singleState = await snapshotQueryClient.singleState(singleQuery);
```

#### EventStreamQueryClient

Client for querying domain event streams:

```typescript
import { Fetcher, URL_RESOLVE_INTERCEPTOR_ORDER } from '@ahoo-wang/fetcher';
import '@ahoo-wang/fetcher-eventstream';
import {
  EventStreamQueryClient,
  all,
  ListQuery,
  PagedQuery
} from '@ahoo-wang/fetcher-wow';
import { idGenerator } from '@ahoo-wang/fetcher-cosec';

// Create a fetcher instance
const wowFetcher = new Fetcher({
  baseURL: 'http://localhost:8080/',
});

// Add interceptor to handle URL parameters
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

// Create the event stream query client
const eventStreamQueryClient = new EventStreamQueryClient({
  fetcher: wowFetcher,
  basePath: 'owner/{ownerId}/cart'
});

// Count event streams
const count = await eventStreamQueryClient.count(all());

// List event streams
const listQuery: ListQuery = {
  condition: all(),
};
const list = await eventStreamQueryClient.list(listQuery);

// List event streams as stream
const listStream = await eventStreamQueryClient.listStream(listQuery);
for await (const event of listStream) {
  const domainEventStream = event.data;
  console.log('Received event stream:', domainEventStream);
}

// Paged event streams
const pagedQuery: PagedQuery = {
  condition: all(),
};
const paged = await eventStreamQueryClient.paged(pagedQuery);
```

## 🛠️ Advanced Usage

### Complete Example with Command and Query Flow

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
  ListQuery
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
const wowFetcher = new Fetcher({
  baseURL: 'http://localhost:8080/',
});

// Add interceptor to handle URL parameters
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

// Create clients
const commandClient = new CommandClient({
  fetcher: wowFetcher,
  basePath: 'owner/{ownerId}/cart'
});

const snapshotQueryClient = new SnapshotQueryClient<CartState>({
  fetcher: wowFetcher,
  basePath: 'owner/{ownerId}/cart'
});

// 1. Send command to add item to cart
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
console.log('Command executed:', commandResult);

// 2. Query the updated cart
const listQuery: ListQuery = {
  condition: all(),
};
const carts = await snapshotQueryClient.list(listQuery);

for (const cart of carts) {
  console.log('Cart:', cart.state);
}

// 3. Stream cart updates
const listStream = await snapshotQueryClient.listStream(listQuery);
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
