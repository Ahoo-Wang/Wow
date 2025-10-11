# @ahoo-wang/fetcher-generator

[![npm version](https://img.shields.io/npm/v/@ahoo-wang/fetcher-generator.svg)](https://www.npmjs.com/package/@ahoo-wang/fetcher-generator)
[![Build Status](https://github.com/Ahoo-Wang/fetcher/actions/workflows/ci.yml/badge.svg)](https://github.com/Ahoo-Wang/fetcher/actions)
[![codecov](https://codecov.io/gh/Ahoo-Wang/fetcher/graph/badge.svg?token=JGiWZ52CvJ)](https://codecov.io/gh/Ahoo-Wang/fetcher)
[![License](https://img.shields.io/npm/l/@ahoo-wang/fetcher-generator.svg)](https://github.com/Ahoo-Wang/fetcher/blob/main/LICENSE)
[![npm downloads](https://img.shields.io/npm/dm/@ahoo-wang/fetcher-generator.svg)](https://www.npmjs.com/package/@ahoo-wang/fetcher-generator)
[![npm bundle size](https://img.shields.io/bundlephobia/minzip/%40ahoo-wang%2Ffetcher-generator)](https://www.npmjs.com/package/@ahoo-wang/fetcher-generator)
[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/Ahoo-Wang/fetcher)

TypeScript code generator from OpenAPI specs for [Wow](https://github.com/Ahoo-Wang/Wow) domain-driven design framework.
Generates type-safe models, query
clients, and command clients from OpenAPI specifications.

**[Wow](https://github.com/Ahoo-Wang/Wow) Framework**: A domain-driven design framework that provides event sourcing,
CQRS (Command Query Responsibility
Segregation),
and aggregate patterns for building scalable distributed systems.

## 🌟 Features

- **🎯 OpenAPI 3.0+ Support**: Full support for OpenAPI 3.0+ specifications (JSON/YAML)
- **📦 TypeScript Code Generation**: Generates type-safe TypeScript interfaces, enums, and classes
- **🏗️ Domain-Driven Design**: Specialized for WOW framework with aggregates, commands, queries, and events
- **🔧 CLI Tool**: Easy-to-use command-line interface for code generation
- **🎨 Decorator-Based APIs**: Generates decorator-based client classes for clean API interactions
- **📋 Comprehensive Models**: Handles complex schemas including unions, intersections, enums, and references
- **🚀 Fetcher Integration**: Seamlessly integrates with the Fetcher ecosystem packages
- **📊 Progress Logging**: Friendly logging with progress indicators during generation
- **📁 Auto Index Generation**: Automatically generates index.ts files for clean module organization
- **🌐 Remote Spec Support**: Load OpenAPI specs directly from HTTP/HTTPS URLs
- **🎭 Event Streaming**: Generates both regular and event-stream command clients

## 🚀 Quick Start

### Installation

```bash
# Using npm
npm install -g @ahoo-wang/fetcher-generator

# Using pnpm
pnpm add -g @ahoo-wang/fetcher-generator

# Using yarn
yarn global add @ahoo-wang/fetcher-generator
```

### Basic Usage

```bash
# Generate TypeScript code from OpenAPI spec
fetcher-generator generate -i ./openapi-spec.json -o ./generated
```

## 📖 Usage

### Command Line Interface

```bash
fetcher-generator generate [options]
```

#### Options

- `-i, --input <path>`: Input OpenAPI specification file path or URL (required)
  - Supports local file paths (e.g., `./api-spec.json`, `/path/to/spec.yaml`)
  - Supports HTTP/HTTPS URLs (e.g., `https://api.example.com/openapi.json`)
- `-o, --output <path>`: Output directory path (default: `src/generated`)
- `-c, --config <file>`: Configuration file path (optional)
- `-v, --verbose`: Enable verbose logging during generation
- `--dry-run`: Show what would be generated without writing files (reserved for future use)
- `-h, --help`: Display help information
- `-V, --version`: Display version number

#### Examples

```bash
# Generate code from a local OpenAPI JSON file
fetcher-generator generate -i ./api-spec.json -o ./src/generated

# Generate code from a YAML specification
fetcher-generator generate -i ./api-spec.yaml -o ./src/generated

# Generate code from a remote OpenAPI specification via HTTPS
fetcher-generator generate -i https://api.example.com/openapi.json -o ./src/generated

# Generate code from a remote YAML specification via HTTP
fetcher-generator generate -i http://localhost:8080/api-spec.yaml -o ./src/generated
```

### Generated Code Structure

The generator creates the following structure in your output directory:

```
output/
├── {bounded-context}/
│   ├── index.ts                   # Auto-generated index file exporting all aggregates and API clients
│   ├── boundedContext.ts          # Bounded context alias constant
│   ├── types.ts                   # Shared types for the bounded context
│   ├── {Tag}ApiClient.ts          # API client classes for custom endpoints (one per OpenAPI tag)
│   └── {aggregate}/               # Aggregate-specific files
│       ├── index.ts               # Auto-generated index file for aggregate
│       ├── types.ts               # Aggregate-specific types, models, and enums
│       ├── queryClient.ts         # Query client factory for state and event queries
│       └── commandClient.ts       # Command client classes (regular and streaming)
├── index.ts                       # Root index file exporting all bounded contexts
└── tsconfig.json                  # TypeScript configuration for generated code
```

#### Index File Generation

The generator automatically creates `index.ts` files in all directories to provide convenient module exports:

- **Root index.ts**: Exports all bounded contexts
- **Bounded context index.ts**: Exports all aggregates and API clients (based on OpenAPI tags) within the context
- **Aggregate index.ts**: Exports all files within the aggregate

This allows for clean imports like:

```typescript
// Import everything from a bounded context
import * as example from './generated/example';

// Import specific aggregates and API clients (API clients are generated per OpenAPI tag)
import { cart, CartApiClient } from './generated/example';

// Import specific files
import { CartState } from './generated/example/cart';
```

## 🎯 Generated Code Examples

### Models

```typescript
/** apply_execution_failed */
export interface ApplyExecutionFailed {
  error: ErrorDetails;
  executeAt: number;
  recoverable: RecoverableType | undefined;
}

/** apply_execution_success */
export interface ApplyExecutionSuccess {
  executeAt: number;
}

/** execution_failed_status */
export enum ExecutionFailedStatus {
  PREPARED = 'PREPARED',
  SUCCEEDED = 'SUCCEEDED',
  FAILED = 'FAILED',
  COMPENSATED = 'COMPENSATED',
}
```

### Query Clients

```typescript
// Generated query client factory for domain-driven design
import {
  QueryClientFactory,
  QueryClientOptions,
  ResourceAttributionPathSpec,
} from '@ahoo-wang/fetcher-wow';
import {
  CartAggregatedFields,
  CartItemAdded,
  CartItemRemoved,
  CartQuantityChanged,
  CartState,
} from './types';

const DEFAULT_QUERY_CLIENT_OPTIONS: QueryClientOptions = {
  contextAlias: 'example',
  aggregateName: 'cart',
  resourceAttribution: ResourceAttributionPathSpec.OWNER,
};

type DOMAIN_EVENT_TYPES = CartItemAdded | CartItemRemoved | CartQuantityChanged;

export const cartQueryClientFactory = new QueryClientFactory<
  CartState,
  CartAggregatedFields | string,
  DOMAIN_EVENT_TYPES
>(DEFAULT_QUERY_CLIENT_OPTIONS);
```

### Command Clients

```typescript
// Generated command client with decorator-based API
import { ContentTypeValues } from '@ahoo-wang/fetcher';
import {
  type ApiMetadata,
  type ApiMetadataCapable,
  api,
  attribute,
  autoGeneratedError,
  del,
  path,
  post,
  put,
  request,
} from '@ahoo-wang/fetcher-decorator';
import { JsonEventStreamResultExtractor } from '@ahoo-wang/fetcher-eventstream';
import type {
  CommandRequest,
  CommandResult,
  CommandResultEventStream,
  DeleteAggregate,
  RecoverAggregate,
} from '@ahoo-wang/fetcher-wow';
import {
  AddCartItem,
  ChangeQuantity,
  MockVariableCommand,
  MountedCommand,
  RemoveCartItem,
  ViewCart,
} from './types';

enum COMMAND_ENDPOINT_PATHS {
  VIEW_CART = '/owner/{ownerId}/cart/view_cart',
  ADD_CART_ITEM = '/owner/{ownerId}/cart/add_cart_item',
  CHANGE_QUANTITY = '/owner/{ownerId}/cart/change_quantity',
  REMOVE_CART_ITEM = '/owner/{ownerId}/cart/remove_cart_item',
  MOUNTED_COMMAND = '/owner/{ownerId}/cart/mounted_command',
  MOCK_VARIABLE_COMMAND = '/tenant/{tenantId}/owner/{ownerId}/cart/{id}/{customerId}/{mockEnum}',
  DEFAULT_DELETE_AGGREGATE = '/owner/{ownerId}/cart',
  DEFAULT_RECOVER_AGGREGATE = '/owner/{ownerId}/cart/recover',
}

const DEFAULT_COMMAND_CLIENT_OPTIONS: ApiMetadata = {
  basePath: 'example',
};

@api()
export class CartCommandClient implements ApiMetadataCapable {
  constructor(
    public readonly apiMetadata: ApiMetadata = DEFAULT_COMMAND_CLIENT_OPTIONS,
  ) {}

  /** view_cart */
  @put(COMMAND_ENDPOINT_PATHS.VIEW_CART)
  viewCart(
    @request() commandRequest: CommandRequest<ViewCart>,
    @attribute() attributes: Record<string, any>,
  ): Promise<CommandResult> {
    throw autoGeneratedError(commandRequest, attributes);
  }

  /**
   * 加入购物车
   * 加入购物车
   */
  @post(COMMAND_ENDPOINT_PATHS.ADD_CART_ITEM)
  addCartItem(
    @request() commandRequest: CommandRequest<AddCartItem>,
    @attribute() attributes: Record<string, any>,
  ): Promise<CommandResult> {
    throw autoGeneratedError(commandRequest, attributes);
  }

  /** 变更购买数量 */
  @put(COMMAND_ENDPOINT_PATHS.CHANGE_QUANTITY)
  changeQuantity(
    @request() commandRequest: CommandRequest<ChangeQuantity>,
    @attribute() attributes: Record<string, any>,
  ): Promise<CommandResult> {
    throw autoGeneratedError(commandRequest, attributes);
  }

  /** 删除商品 */
  @put(COMMAND_ENDPOINT_PATHS.REMOVE_CART_ITEM)
  removeCartItem(
    @request() commandRequest: CommandRequest<RemoveCartItem>,
    @attribute() attributes: Record<string, any>,
  ): Promise<CommandResult> {
    throw autoGeneratedError(commandRequest, attributes);
  }
}
```

The generator also creates streaming command clients for event-driven interactions:

```typescript
@api('', {
  headers: { Accept: ContentTypeValues.TEXT_EVENT_STREAM },
  resultExtractor: JsonEventStreamResultExtractor,
})
export class CartStreamCommandClient implements ApiMetadataCapable {
  constructor(
    public readonly apiMetadata: ApiMetadata = DEFAULT_COMMAND_CLIENT_OPTIONS,
  ) {}

  /** view_cart */
  @put(COMMAND_ENDPOINT_PATHS.VIEW_CART)
  viewCart(
    @request() commandRequest: CommandRequest<ViewCart>,
    @attribute() attributes: Record<string, any>,
  ): Promise<CommandResultEventStream> {
    throw autoGeneratedError(commandRequest, attributes);
  }
  // ... other streaming methods
}
```

### API Clients

The generator also creates API client classes for custom endpoints that don't follow the domain-driven command pattern.
These are generated based on OpenAPI tags (one client class per tag):

```typescript
// Generated API client for custom endpoints
import {
  type ApiMetadata,
  type ApiMetadataCapable,
  ParameterRequest,
  api,
  attribute,
  autoGeneratedError,
  get,
  path,
  post,
  request,
} from '@ahoo-wang/fetcher-decorator';
import { CommandResult } from '@ahoo-wang/fetcher-wow';
import { CartData } from './cart/types';
import { ContentTypeValues } from '@ahoo-wang/fetcher';
import {
  JsonEventStreamResultExtractor,
  JsonServerSentEventStream,
} from '@ahoo-wang/fetcher-eventstream';

/** Shopping Cart */
@api()
export class CartApiClient implements ApiMetadataCapable {
  constructor(
    public readonly apiMetadata: ApiMetadata = { basePath: 'example' },
  ) {}

  /** Custom command sending */
  @post('/cart/{userId}/customize-send-cmd')
  customizeSendCmd(
    @path('userId') userId: string,
    @request() httpRequest?: ParameterRequest,
    @attribute() attributes?: Record<string, any>,
  ): Promise<CommandResult> {
    throw autoGeneratedError(userId, httpRequest, attributes);
  }

  /** Add cart item with event streaming */
  @post('/cart/{userId}/add-cart-item', {
    headers: { Accept: ContentTypeValues.TEXT_EVENT_STREAM },
    resultExtractor: JsonEventStreamResultExtractor,
  })
  addCartItem(
    @path('userId') userId: string,
    @request() httpRequest?: ParameterRequest,
    @attribute() attributes?: Record<string, any>,
  ): Promise<JsonServerSentEventStream<CommandResult>> {
    throw autoGeneratedError(userId, httpRequest, attributes);
  }

  /** Get current user's cart */
  @get('/cart/me')
  me(): Promise<CartData> {
    throw autoGeneratedError();
  }

  /** Get current user's cart (sync) */
  @get('/cart/me/sync')
  meSync(): Promise<CartData> {
    throw autoGeneratedError();
  }
}
```

## 🔧 Integration with Fetcher

The generated code is designed to work seamlessly with the Fetcher ecosystem:

```typescript
import { fetcher, Fetcher } from '@ahoo-wang/fetcher';
import { all } from '@ahoo-wang/fetcher-wow';
import { cartQueryClientFactory } from './generated/example/cart/queryClient';
import { CartCommandClient } from './generated/example/cart/commandClient';
import { CartApiClient } from './generated/example/CartApiClient';

// Create a fetcher instance
const fetcher = new Fetcher({
  baseURL: 'https://api.example.com',
});

// Use the generated query client factory
const snapshotClient = cartQueryClientFactory.createSnapshotQueryClient({
  fetcher: fetcher,
});
const cartState = await snapshotClient.singleState({ condition: all() });

// Use the generated command client
const commandClient = new CartCommandClient({ fetcher: fetcher });
const result = await commandClient.addCartItem(
  {
    command: {
      productId: 'product-123',
      quantity: 2,
    },
  },
  {
    ownerId: 'user-456',
  },
);

// Use the generated API client for custom endpoints (based on OpenAPI tag "cart")
const apiClient = new CartApiClient({ fetcher: fetcher });
const cartData = await apiClient.me();
```

## 🚀 Advanced Usage Examples

### Custom Configuration

Create a `.fetcherrc.json` configuration file for advanced generation options:

```json
{
  "generator": {
    "targetFramework": "wow",
    "outputFormat": "typescript",
    "basePath": "api/v1",
    "generateIndexFiles": true,
    "verbose": true,
    "typeMappings": {
      "string": "string",
      "integer": "number",
      "boolean": "boolean",
      "number": "number"
    },
    "schemaTransformers": [
      {
        "pattern": "^wow\\.",
        "transform": "removePrefix"
      }
    ]
  }
}
```

### Multi-Context Generation

Generate code for multiple bounded contexts from a single OpenAPI spec:

```bash
# Generate all contexts
fetcher-generator generate -i ./multi-context-api.json -o ./src/generated

# Generated structure:
# src/generated/
# ├── ecommerce/
# │   ├── index.ts
# │   ├── boundedContext.ts
# │   ├── cart/
# │   ├── order/
# │   └── product/
# └── inventory/
#     ├── index.ts
#     ├── boundedContext.ts
#     └── stock/
```

### Event-Driven Architecture

Generated code supports event streaming for real-time updates:

```typescript
import { cartQueryClientFactory } from './generated/ecommerce/cart/queryClient';
import { CartStreamCommandClient } from './generated/ecommerce/cart/commandClient';

// Create streaming query client
const eventClient = cartQueryClientFactory.createEventQueryClient({
  fetcher: fetcher,
  onEvent: event => {
    console.log('Cart event:', event);
    // Handle real-time cart updates
  },
});

// Use streaming command client
const streamCommandClient = new CartStreamCommandClient({ fetcher });

const eventStream = await streamCommandClient.addCartItem(
  {
    command: { productId: 'item-123', quantity: 1 },
  },
  { ownerId: 'user-456' },
);

// Process streaming events
for await (const event of eventStream) {
  console.log('Command event:', event);
}
```

### Custom Type Guards and Validation

Generated code includes runtime type validation:

```typescript
import { CartState, isCartState } from './generated/ecommerce/cart/types';

// Runtime type checking
function processCartData(data: unknown) {
  if (isCartState(data)) {
    // TypeScript knows data is CartState here
    console.log('Valid cart state:', data.items.length);
  } else {
    throw new Error('Invalid cart data');
  }
}
```

### Integration with State Management

Use generated clients with popular state management libraries:

```typescript
// Redux Toolkit integration
import { createAsyncThunk, createSlice } from '@reduxjs/toolkit';
import { cartQueryClientFactory } from './generated/ecommerce/cart/queryClient';

const fetchCartState = createAsyncThunk(
  'cart/fetchState',
  async (ownerId: string) => {
    const snapshotClient = cartQueryClientFactory.createSnapshotQueryClient({
      fetcher: fetcher,
    });
    return await snapshotClient.singleState({ condition: { ownerId } });
  },
);

const cartSlice = createSlice({
  name: 'cart',
  initialState: { items: [], loading: false },
  reducers: {},
  extraReducers: builder => {
    builder.addCase(fetchCartState.pending, state => {
      state.loading = true;
    });
    builder.addCase(fetchCartState.fulfilled, (state, action) => {
      state.items = action.payload.items;
      state.loading = false;
    });
  },
});
```

### Error Handling and Retry Logic

Generated clients work seamlessly with retry mechanisms:

```typescript
import { Fetcher } from '@ahoo-wang/fetcher';
import { CartCommandClient } from './generated/ecommerce/cart/commandClient';

// Configure fetcher with retry logic
const fetcher = new Fetcher({
  baseURL: 'https://api.example.com',
  retryConfig: {
    maxRetries: 3,
    retryDelay: 1000,
    retryCondition: error => error.status >= 500,
  },
});

const commandClient = new CartCommandClient({ fetcher });

// Commands automatically retry on server errors
try {
  const result = await commandClient.addCartItem(
    {
      command: { productId: 'item-123', quantity: 1 },
    },
    { ownerId: 'user-456' },
  );
} catch (error) {
  console.error('Failed to add item after retries:', error);
}
```

### Testing Generated Code

Write unit tests for generated clients:

```typescript
import { describe, it, expect, vi } from 'vitest';
import { CartCommandClient } from './generated/ecommerce/cart/commandClient';

describe('CartCommandClient', () => {
  it('should add item to cart', async () => {
    const mockFetcher = {
      request: vi.fn().mockResolvedValue({ success: true }),
    };

    const client = new CartCommandClient({
      fetcher: mockFetcher as any,
    });

    // This will throw autoGeneratedError in generated code
    // In real usage, you'd use a decorator interceptor
    expect(() =>
      client.addCartItem(
        {
          command: { productId: 'test', quantity: 1 },
        },
        { ownerId: 'user' },
      ),
    ).toThrow();
  });
});
```

## 📋 OpenAPI Specification Requirements

The generator expects OpenAPI 3.0+ specifications with specific patterns for WOW domain-driven design framework:

### Aggregate Definition

Aggregates are identified by operation tags that follow the pattern:

- `{context}.{aggregate}`

### Operation Patterns

The generator recognizes operations by their `operationId` suffixes:

- **State Snapshots**: Operations ending with `.snapshot_state.single`
- **Event Queries**: Operations ending with `.event.list_query`
- **Field Queries**: Operations ending with `.snapshot.count`
- **Commands**: Any operation with a valid command request/response structure

### Commands and Queries

- **Commands**: Operations with `POST`, `PUT`, `DELETE` methods that return `wow.CommandOk` responses
- **Queries**: Operations with `GET` method for retrieving aggregate state or events
- **Events**: Operations returning event stream arrays with domain event structures

### Schema Conventions

- Use descriptive names for schemas
- Avoid `wow.` prefixed schemas (reserved for internal framework schemas)
- Command request bodies should reference schemas in `components/schemas`
- State and event schemas should follow the expected structure for domain modeling

## 🛠️ Development

### Building

```bash
# Build the package
pnpm build

# Run tests
pnpm test

# Run linting
pnpm lint
```

### Testing the Generator

```bash
# Generate test output using the demo spec
pnpm generate

# Run tests
pnpm test

# Run tests with coverage
pnpm test -- --coverage
```

## 🤝 Contributing

Contributions are welcome! Please feel free to submit a Pull Request. For major changes, please open an issue first to
discuss what you would like to change.

## 📄 License

This project is licensed under the Apache License 2.0 - see the [LICENSE](../../LICENSE) file for details.

## 🔗 Links

- [Fetcher Core](https://github.com/Ahoo-Wang/fetcher/tree/main/packages/fetcher)
- [Fetcher Decorator](https://github.com/Ahoo-Wang/fetcher/tree/main/packages/decorator)
- [Fetcher EventStream](https://github.com/Ahoo-Wang/fetcher/tree/main/packages/eventstream)
- [GitHub Repository](https://github.com/Ahoo-Wang/fetcher)
- [NPM Package](https://www.npmjs.com/package/@ahoo-wang/fetcher-generator)
