# @ahoo-wang/fetcher-wow

[![npm version](https://img.shields.io/npm/v/@ahoo-wang/fetcher-wow.svg)](https://www.npmjs.com/package/@ahoo-wang/fetcher-wow)
[![Build Status](https://github.com/Ahoo-Wang/fetcher/actions/workflows/ci.yml/badge.svg)](https://github.com/Ahoo-Wang/fetcher/actions)
[![codecov](https://codecov.io/gh/Ahoo-Wang/fetcher/graph/badge.svg?token=JGiWZ52CvJ)](https://codecov.io/gh/Ahoo-Wang/fetcher)
[![License](https://img.shields.io/npm/l/@ahoo-wang/fetcher-wow.svg)](https://github.com/Ahoo-Wang/fetcher/blob/main/LICENSE)
[![npm downloads](https://img.shields.io/npm/dm/@ahoo-wang/fetcher-wow.svg)](https://www.npmjs.com/package/@ahoo-wang/fetcher-wow)
[![npm bundle size](https://img.shields.io/bundlephobia/minzip/%40ahoo-wang%2Ffetcher-wow)](https://www.npmjs.com/package/@ahoo-wang/fetcher-wow)
[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/Ahoo-Wang/fetcher)
[![Storybook](https://img.shields.io/badge/Storybook-Interactive%20Docs-FF4785)](https://fetcher.ahoo.me/?path=/docs/wow-introduction--docs)

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
- **🔍 Powerful Query DSL**: Typed `FilterExpression` builders with comprehensive operator support
- **📊 Snapshot Aggregation**: Group and aggregate snapshot data with typed queries
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
import {
  Fetcher,
  FetchExchange,
  HttpMethod,
  RequestInterceptor,
  URL_RESOLVE_INTERCEPTOR_ORDER,
} from '@ahoo-wang/fetcher';
import '@ahoo-wang/fetcher-eventstream';
import {
  CommandClient,
  CommandRequest,
  CommandHeaders,
  CommandStage,
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
    const urlParams = exchange.ensureRequestUrlParams();
    urlParams.path['ownerId'] = currentUserId;
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

type AddCartItemCommand = CommandRequest<AddCartItem>;

// Create a command request
const addCartItemCommand: AddCartItemCommand = {
  method: HttpMethod.POST,
  headers: {
    [CommandHeaders.WAIT_STAGE]: CommandStage.SNAPSHOT,
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

#### Filter Expression Builder

Wow 8.11+ queries use `FilterExpression`:

```typescript
import {
  DeletionState,
  filter,
  SearchMode,
  TimeUnit,
} from '@ahoo-wang/fetcher-wow';

const expression = filter.and([
  filter.deletion(DeletionState.ACTIVE),
  filter.eq('state.status', 'PAID'),
  filter.elementMatch('state.items', filter.gt('quantity', 0)),
  filter.search('event sourcing', {
    mode: SearchMode.PHRASE,
    fields: ['state.title', 'state.description'],
  }),
  filter.yesterday('state.createdAt', {
    zoneId: 'Asia/Shanghai',
    timeUnit: TimeUnit.MILLISECONDS,
  }),
]);
```

Builders are grouped under `filter`: `matchAll`, `matchNone`, `and`, `or`,
`nor`, comparisons, string/collection predicates, presence checks,
`elementMatch`, `search`, deletion scope, and relative-time filters.
`and`, `or`, `nor`, `ids`, `aggregateIds`, `isIn`, `notIn`, and
`containsAll` accept one non-empty `readonly` array and throw `TypeError` for
an empty array.

#### Condition Builder (Deprecated)

The legacy Condition API remains available for compatibility with older Wow
servers. New code should use `FilterExpression` and `filter.*`.

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

// Simple conditions
const simpleConditions = [
  eq('name', 'John'),
  ne('status', 'inactive'),
  gt('age', 18),
  lt('score', 100),
  gte('rating', 4.0),
  lte('price', 100),
];

// String conditions
const stringConditions = [
  contains('email', '@company.com'),
  startsWith('username', 'j'),
  endsWith('domain', '.com'),
  isIn('status', 'active', 'pending'),
  notIn('role', 'guest', 'banned'),
  match('description', 'search keywords'),
];

// Null checks
const nullConditions = [
  isNull('deletedAt'),
  notNull('email'),
  isTrue('isActive'),
  isFalse('isDeleted'),
  exists('phoneNumber'),
];

// Array conditions
const arrayConditions = [
  allIn('tags', 'react', 'typescript'),
  elemMatch('items', eq('quantity', 0)),
];

// Date conditions
const dateConditions = [
  today('createdAt'),
  beforeToday('lastLogin', '09:30'),
  tomorrow('scheduledDate'),
  thisWeek('updatedAt'),
  nextWeek('startDate'),
  lastWeek('endDate'),
  thisMonth('createdDate'),
  lastMonth('expirationDate'),
  recentDays('createdAt', 5), // Last 5 days including today
  earlierDays('createdAt', 3), // More than 3 days ago
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

// Raw condition for advanced use cases
const rawCondition = raw({ $text: { $search: 'keywords' } });
```

**Operator Reference:**

| Category     | Operators                                                                                                                                       |
| ------------ | ----------------------------------------------------------------------------------------------------------------------------------------------- |
| Logical      | `and`, `or`, `nor`                                                                                                                              |
| Comparison   | `eq`, `ne`, `gt`, `lt`, `gte`, `lte`                                                                                                            |
| String       | `contains`, `startsWith`, `endsWith`, `match`                                                                                                   |
| Collection   | `isIn`, `notIn`, `allIn`, `elemMatch`                                                                                                           |
| Null/Boolean | `isNull`, `notNull`, `isTrue`, `isFalse`, `exists`                                                                                              |
| Date         | `today`, `beforeToday(time)`, `tomorrow`, `thisWeek`, `nextWeek`, `lastWeek`, `thisMonth`, `lastMonth`, `recentDays(days)`, `earlierDays(days)` |
| ID           | `id`, `ids`, `aggregateId`, `aggregateIds`, `tenantId`, `ownerId`                                                                               |
| State        | `active`, `all`, `deleted`                                                                                                                      |
| Special      | `raw` (for advanced database-specific queries)                                                                                                  |

#### SnapshotQueryClient

Client for querying materialized snapshots with comprehensive query operations:

```typescript
import {
  Fetcher,
  FetchExchange,
  RequestInterceptor,
  URL_RESOLVE_INTERCEPTOR_ORDER,
} from '@ahoo-wang/fetcher';
import '@ahoo-wang/fetcher-eventstream';
import {
  aggregation,
  SnapshotQueryClient,
  filter,
  FilterListQuery,
  FilterPagedQuery,
  FilterSingleQuery,
  type AggregationQuery,
  Identifier,
} from '@ahoo-wang/fetcher-wow';
import { idGenerator } from '@ahoo-wang/fetcher-cosec';

interface CartItem {
  productId: string;
  price: number;
  quantity: number;
}

interface CartState extends Identifier {
  status: string;
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
    const urlParams = exchange.ensureRequestUrlParams();
    urlParams.path['ownerId'] = currentUserId;
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
const count = await cartSnapshotQueryClient.count(filter.matchAll());

// List snapshots
const listQuery: FilterListQuery = {
  filter: filter.matchAll(),
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
const pagedQuery: FilterPagedQuery = {
  filter: filter.matchAll(),
};
const paged = await cartSnapshotQueryClient.paged(pagedQuery);

// Paged snapshot states
const pagedState = await cartSnapshotQueryClient.pagedState(pagedQuery);

// Single snapshot
const singleQuery: FilterSingleQuery = {
  filter: filter.matchAll(),
};
const single = await cartSnapshotQueryClient.single(singleQuery);

// Single snapshot state
const singleState = await cartSnapshotQueryClient.singleState(singleQuery);

type CartFields = 'state.status' | 'state.items';
type ItemFields = 'productId' | 'price' | 'quantity';

type ProductSummary = {
  product: string;
  representativeProduct: string | null;
  itemCount: number;
  revenue: number;
};

const revenue = aggregation.multiply(
  aggregation.field<ItemFields>('price'),
  aggregation.field<ItemFields>('quantity'),
);

const aggregationQuery: AggregationQuery<CartFields, ItemFields> = {
  filter: filter.eq('state.status', 'COMPLETED'),
  elements: [aggregation.element('state.items', filter.gt('quantity', 0))],
  groupBy: [aggregation.terms('productId', 'product')],
  metrics: [
    aggregation.any('productId', 'representativeProduct'),
    aggregation.count('itemCount'),
    aggregation.sum(revenue, 'revenue'),
  ],
};

const summaries =
  await cartSnapshotQueryClient.aggregate<ProductSummary>(aggregationQuery);
const summaryStream =
  await cartSnapshotQueryClient.aggregateStream<ProductSummary>(
    aggregationQuery,
  );
for await (const event of summaryStream) {
  console.log(event.data);
}
```

`aggregation.any(field, alias)` adds a metric, not another group. It returns one
non-null scalar from the current group, or `null` when no value exists. The
selected value is intentionally unspecified and may differ across backends or
executions; use it only when every candidate is interchangeable. Its field is
relative to the innermost element, and Wow rejects collection or non-terms-capable
fields at runtime. Sorting by an `ANY` alias is an expensive metric sort.

##### Methods

- `count(filter: FilterExpression): Promise<number>` - Counts snapshots matching the filter expression.
- `list(listQuery: FilterListQuery): Promise<Partial<MaterializedSnapshot<S>>[]>` - Retrieves a list of materialized
  snapshots.
- `listStream(listQuery: FilterListQuery): Promise<ReadableStream<JsonServerSentEvent<Partial<MaterializedSnapshot<S>>>>>` -
  Retrieves a stream of materialized snapshots as Server-Sent Events.
- `listState(listQuery: FilterListQuery): Promise<Partial<S>[]>` - Retrieves a list of snapshot states.
- `listStateStream(listQuery: FilterListQuery): Promise<ReadableStream<JsonServerSentEvent<Partial<S>>>>` - Retrieves a stream
  of snapshot states as Server-Sent Events.
- `paged(pagedQuery: FilterPagedQuery): Promise<PagedList<Partial<MaterializedSnapshot<S>>>>` - Retrieves a paged list of
  materialized snapshots.
- `pagedState(pagedQuery: FilterPagedQuery): Promise<PagedList<Partial<S>>>` - Retrieves a paged list of snapshot states.
- `single(singleQuery: FilterSingleQuery): Promise<Partial<MaterializedSnapshot<S>>>` - Retrieves a single materialized
  snapshot.
- `singleState(singleQuery: FilterSingleQuery): Promise<Partial<S>>` - Retrieves a single snapshot state.
- `aggregate<Row extends DynamicDocument = DynamicDocument, AGGREGATION_FIELDS extends string = string>(query: AggregationQuery<FIELDS, AGGREGATION_FIELDS>): Promise<Row[]>` - Runs a
  snapshot aggregation and requests `snapshot/aggregation`.
- `aggregateStream<Row extends DynamicDocument = DynamicDocument, AGGREGATION_FIELDS extends string = string>(query: AggregationQuery<FIELDS, AGGREGATION_FIELDS>): Promise<ReadableStream<JsonServerSentEvent<Row>>>` - Runs a snapshot aggregation and requests `snapshot/aggregation` using Server-Sent Events (SSE).

#### QueryClientFactory

Factory for creating pre-configured query clients. Useful when you need multiple clients with shared configuration.

```typescript
import {
  filter,
  QueryClientFactory,
  ResourceAttributionPathSpec,
} from '@ahoo-wang/fetcher-wow';
import { idGenerator } from '@ahoo-wang/fetcher-cosec';

// Create a factory with default options
const factory = new QueryClientFactory({
  contextAlias: 'example',
  aggregateName: 'cart',
  resourceAttribution: ResourceAttributionPathSpec.OWNER,
  fetcher: exampleFetcher,
});

// Create a snapshot query client
const snapshotClient = factory.createSnapshotQueryClient({
  aggregateName: 'cart',
});
const carts = await snapshotClient.listState({ filter: filter.matchAll() });

// Create a state aggregate client
const stateClient = factory.createLoadStateAggregateClient({
  aggregateName: 'cart',
});
const cart = await stateClient.load('cart-123');

// Create an event stream query client
const eventClient = factory.createEventStreamQueryClient({
  aggregateName: 'cart',
});
const events = await eventClient.list({ filter: filter.matchAll() });
```

**Methods:**

- `createSnapshotQueryClient(options?: QueryClientOptions): SnapshotQueryClient` - Creates a client for querying snapshots.
- `createLoadStateAggregateClient(options?: QueryClientOptions): LoadStateAggregateClient` - Creates a client for loading aggregate state by ID.
- `createOwnerLoadStateAggregateClient(options?: QueryClientOptions): LoadOwnerStateAggregateClient` - Creates a client for loading the current owner's aggregate state.
- `createEventStreamQueryClient(options?: QueryClientOptions): EventStreamQueryClient` - Creates a client for querying event streams.

#### EventStreamQueryClient

Client for querying domain event streams with comprehensive query operations:

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
  filter,
  FilterListQuery,
  FilterPagedQuery,
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
    const urlParams = exchange.ensureRequestUrlParams();
    urlParams.path['ownerId'] = currentUserId;
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
const count = await cartEventStreamQueryClient.count(filter.matchAll());

// List event streams
const listQuery: FilterListQuery = {
  filter: filter.matchAll(),
};
const list = await cartEventStreamQueryClient.list(listQuery);

// List event streams as stream
const listStream = await cartEventStreamQueryClient.listStream(listQuery);
for await (const event of listStream) {
  const domainEventStream = event.data;
  console.log('Received event stream:', domainEventStream);
}

// Paged event streams
const pagedQuery: FilterPagedQuery = {
  filter: filter.matchAll(),
};
const paged = await cartEventStreamQueryClient.paged(pagedQuery);
```

##### Methods

- `count(filter: FilterExpression): Promise<number>` - Counts domain event streams matching the filter expression.
- `list(listQuery: FilterListQuery): Promise<Partial<DomainEventStream>[]>` - Retrieves a list of domain event streams.
- `listStream(listQuery: FilterListQuery): Promise<ReadableStream<JsonServerSentEvent<Partial<DomainEventStream>>>>` -
  Retrieves a stream of domain event streams as Server-Sent Events.
- `paged(pagedQuery: FilterPagedQuery): Promise<PagedList<Partial<DomainEventStream>>>` - Retrieves a paged list of domain
  event streams.

## 🚀 Advanced Usage Examples

### Custom Command Builders and Validators

Create type-safe command builders with validation:

```typescript
import { CommandClient, CommandRequest } from '@ahoo-wang/fetcher-wow';

// Command type definitions
interface CreateUserCommand {
  commandType: 'CreateUser';
  commandId: string;
  aggregateId: string;
  name: string;
  email: string;
  role: 'admin' | 'user' | 'moderator';
}

interface UpdateUserProfileCommand {
  commandType: 'UpdateUserProfile';
  commandId: string;
  aggregateId: string;
  displayName?: string;
  bio?: string;
  avatarUrl?: string;
}

// Command builder with validation
class UserCommandBuilder {
  private commandClient: CommandClient;

  constructor(commandClient: CommandClient) {
    this.commandClient = commandClient;
  }

  async createUser(params: {
    name: string;
    email: string;
    role: 'admin' | 'user' | 'moderator';
    ownerId: string;
  }): Promise<any> {
    // Validate input
    this.validateCreateUserParams(params);

    const command: CreateUserCommand = {
      commandType: 'CreateUser',
      commandId: crypto.randomUUID(),
      aggregateId: crypto.randomUUID(), // New user gets new aggregate ID
      ...params,
    };

    return this.commandClient.send(command, { ownerId: params.ownerId });
  }

  async updateProfile(params: {
    userId: string;
    displayName?: string;
    bio?: string;
    avatarUrl?: string;
    ownerId: string;
  }): Promise<any> {
    // Validate input
    this.validateUpdateProfileParams(params);

    const command: UpdateUserProfileCommand = {
      commandType: 'UpdateUserProfile',
      commandId: crypto.randomUUID(),
      aggregateId: params.userId, // User ID is the aggregate ID
      displayName: params.displayName,
      bio: params.bio,
      avatarUrl: params.avatarUrl,
    };

    return this.commandClient.send(command, { ownerId: params.ownerId });
  }

  private validateCreateUserParams(params: any) {
    if (!params.name || params.name.length < 2) {
      throw new Error('Name must be at least 2 characters');
    }
    if (!params.email || !this.isValidEmail(params.email)) {
      throw new Error('Valid email is required');
    }
    if (!['admin', 'user', 'moderator'].includes(params.role)) {
      throw new Error('Invalid role');
    }
  }

  private validateUpdateProfileParams(params: any) {
    if (!params.userId) {
      throw new Error('User ID is required');
    }
    if (params.displayName && params.displayName.length > 50) {
      throw new Error('Display name too long');
    }
    if (params.bio && params.bio.length > 500) {
      throw new Error('Bio too long');
    }
  }

  private isValidEmail(email: string): boolean {
    const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
    return emailRegex.test(email);
  }
}

// Usage
const commandClient = new CommandClient({ basePath: '/api/commands' });
const userCommands = new UserCommandBuilder(commandClient);

try {
  // Create a new user
  const result = await userCommands.createUser({
    name: 'John Doe',
    email: 'john@example.com',
    role: 'user',
    ownerId: 'user-123',
  });
  console.log('User created:', result);

  // Update user profile
  await userCommands.updateProfile({
    userId: result.aggregateId,
    displayName: 'Johnny',
    bio: 'Software developer',
    ownerId: 'user-123',
  });
} catch (error) {
  console.error('Command failed:', error);
}
```

### Advanced Query Composition with Reactive Updates

Create complex queries with reactive real-time updates:

```typescript
import {
  EventStreamQueryClient,
  filter,
  SnapshotQueryClient,
} from '@ahoo-wang/fetcher-wow';

// Advanced query manager with reactive updates
class ReactiveQueryManager {
  private snapshotClient: SnapshotQueryClient;
  private streamClient: EventStreamQueryClient;
  private listeners: Map<string, (data: any) => void> = new Map();

  constructor(basePath: string) {
    this.snapshotClient = new SnapshotQueryClient({ basePath });
    this.streamClient = new EventStreamQueryClient({ basePath });
  }

  // Subscribe to real-time updates for a query
  subscribeToQuery(
    queryId: string,
    initialQuery: any,
    callback: (data: any) => void,
  ) {
    this.listeners.set(queryId, callback);

    // Start streaming updates
    this.startStreaming(queryId, initialQuery);
  }

  // Unsubscribe from updates
  unsubscribe(queryId: string) {
    this.listeners.delete(queryId);
    // Close stream if needed
  }

  private async startStreaming(queryId: string, query: any) {
    try {
      const stream = await this.streamClient.listStream(query);

      for await (const event of stream) {
        const listener = this.listeners.get(queryId);
        if (listener) {
          listener(event);
        }
      }
    } catch (error) {
      console.error(`Stream error for ${queryId}:`, error);
    }
  }

  // Complex query with aggregations
  async getUserDashboardStats(userId: string) {
    const [userProfile, recentActivity, stats] = await Promise.all([
      this.snapshotClient.single({
        filter: filter.eq('aggregateId', userId),
      }),
      this.snapshotClient.list({
        filter: filter.and([
          filter.eq('state.userId', userId),
          filter.eq('state.type', 'activity'),
        ]),
        limit: 10,
      }),
      this.snapshotClient.count(filter.eq('state.userId', userId)),
    ]);

    return {
      profile: userProfile,
      recentActivity,
      totalActions: stats,
      lastActivity: recentActivity[0]?.timestamp,
    };
  }
}

// Usage
const queryManager = new ReactiveQueryManager('/api/queries');

// Get dashboard data
const dashboard = await queryManager.getUserDashboardStats('user-123');
console.log('Dashboard:', dashboard);

// Subscribe to real-time updates
queryManager.subscribeToQuery(
  'user-activity',
  {
    filter: filter.and([
      filter.eq('state.userId', 'user-123'),
      filter.eq('state.type', 'activity'),
    ]),
  },
  update => {
    console.log('New activity:', update);
    // Update UI with new data
  },
);
```

## 🛠️ Advanced Usage

```typescript
import {
  Fetcher,
  FetchExchange,
  HttpMethod,
  RequestInterceptor,
  URL_RESOLVE_INTERCEPTOR_ORDER,
} from '@ahoo-wang/fetcher';
import '@ahoo-wang/fetcher-eventstream';
import {
  CommandClient,
  CommandRequest,
  CommandHeaders,
  CommandStage,
  SnapshotQueryClient,
  filter,
  FilterListQuery,
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
    const urlParams = exchange.ensureRequestUrlParams();
    urlParams.path['ownerId'] = currentUserId;
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

type AddCartItemCommand = CommandRequest<AddCartItem>;

// 1. Send command to add item to cart
const addItemCommand: AddCartItemCommand = {
  method: HttpMethod.POST,
  headers: {
    [CommandHeaders.WAIT_STAGE]: CommandStage.SNAPSHOT,
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
console.log('Command executed:', commandResult);

// 2. Query the updated cart
const listQuery: FilterListQuery = {
  filter: filter.matchAll(),
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
the [contributing guide](https://github.com/Ahoo-Wang/fetcher/blob/main/wiki/guide/contributing.md) for more details.

## 📄 License

Apache-2.0

---

<p align="center">
  Part of the <a href="https://github.com/Ahoo-Wang/fetcher">Fetcher</a> ecosystem
</p>
