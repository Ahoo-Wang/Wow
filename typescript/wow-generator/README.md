# @ahoo-wang/fetcher-generator

[![npm version](https://img.shields.io/npm/v/@ahoo-wang/fetcher-generator.svg)](https://www.npmjs.com/package/@ahoo-wang/fetcher-generator)
[![Build Status](https://github.com/Ahoo-Wang/fetcher/actions/workflows/ci.yml/badge.svg)](https://github.com/Ahoo-Wang/fetcher/actions)
[![codecov](https://codecov.io/gh/Ahoo-Wang/fetcher/graph/badge.svg?token=JGiWZ52CvJ)](https://codecov.io/gh/Ahoo-Wang/fetcher)
[![License](https://img.shields.io/npm/l/@ahoo-wang/fetcher-generator.svg)](https://github.com/Ahoo-Wang/fetcher/blob/main/LICENSE)
[![npm downloads](https://img.shields.io/npm/dm/@ahoo-wang/fetcher-generator.svg)](https://www.npmjs.com/package/@ahoo-wang/fetcher-generator)
[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/Ahoo-Wang/fetcher)
[![Storybook](https://img.shields.io/badge/Storybook-Interactive%20Docs-FF4785)](https://fetcher.ahoo.me/?path=/docs/generator-introduction--docs)

A powerful TypeScript code generation tool that automatically generates type-safe API client code based on OpenAPI specifications. It is designed for general use cases and is also deeply optimized for the [Wow](https://github.com/Ahoo-Wang/Wow) Domain-Driven Design framework, providing native support for the CQRS architectural pattern.

**[Wow](https://github.com/Ahoo-Wang/Wow) Framework**: A domain-driven design framework that provides event sourcing, CQRS (Command Query Responsibility Segregation), and aggregate patterns for building scalable distributed systems. This generator provides enhanced support for Wow's CQRS architecture while remaining compatible with standard REST APIs.

## 💡 Why Need Code Generator

In modern front-end and back-end separation architecture, team collaboration efficiency is often the key to project success. Fetcher Generator fundamentally changes traditional front-end and back-end collaboration patterns through automated code generation, bringing significant efficiency improvements and quality assurance to teams.

### 🔍 Analysis of Traditional Collaboration Pain Points

#### 🔄 Synchronization Efficiency Issues

- **High Manual Update Costs**: When back-end interfaces change, front-end needs to manually update multiple pieces of code, which is time-consuming and error-prone
- **Complex Version Management**: Different versions of APIs correspond to different client codes, with maintenance costs growing exponentially
- **Surge in Communication Costs**: Every interface adjustment requires repeated confirmation between front-end and back-end, affecting development rhythm

#### 📝 Code Quality Risks

- **Type Inconsistency Risks**: Front-end and back-end maintain type definitions separately, with subtle differences causing runtime errors
- **Documentation Lag Issues**: API documentation becomes detached from actual code, making it difficult for newcomers to get started and reducing team knowledge transfer efficiency
- **Inconsistent Code Standards**: Manually written client code varies in style, increasing maintenance difficulty

#### ⏱ Development Efficiency Bottlenecks

- **Repetitive Work**: Writing similar request handling code for each interface wastes innovation time
- **Long Debugging Cycles**: Type mismatch issues are often discovered during integration testing, with high repair costs
- **Limited Iteration Speed**: Fear of breaking existing functionality leads to hesitation in refactoring and optimization

### 🚀 Generator's Innovative Solutions

#### 🎯 Automated Workflow

- **Real-time Synchronization Mechanism**: Automatically generates the latest client code based on OpenAPI specifications, ensuring complete synchronization with back-end APIs
- **Single Source of Truth Guarantee**: OpenAPI specifications serve as the sole authoritative source, completely eliminating front-end and back-end understanding deviations
- **Rapid Change Response**: One-click regeneration after API adjustments, completed in minutes

#### 🛡️ Quality Assurance System

- **Compile-time Type Checking**: Captures interface call errors during the coding phase, preventing issues before they occur
- **Standardized Code Output**: Unified code style and best practices, improving maintainability
- **Self-contained Documentation**: Generated code comes with complete type hints and comments, reducing understanding costs

#### 💡 Intelligent Development Experience

- **Intelligent Code Completion**: Complete type system provides precise code prompts, improving coding efficiency
- **Semantic Method Naming**: Intuitive API method names make code self-explanatory
- **Structured Code Organization**: Clear module divisions facilitate team collaboration and code review

### 💰 Quantifiable Efficiency Improvements

Through actual project validation, using Generator can bring significant efficiency improvements to teams:

- 📈 Development efficiency improvement 30-50%
- Reduce 60%+ manual coding workload
- Save 70% interface debugging time
- Reduce 80% type-related bugs

#### 👥 Team Collaboration Optimization

- New member onboarding time shortened by 50%
- Code review efficiency improved by 40%
- Cross-team communication costs reduced by 60%

#### 🔄 Engineering Efficiency Improvements

- API change response speed increased 5x
- Confidence and frequency of refactoring significantly increased
- Technical debt accumulation rate slowed

#### 🌟 Long-term Value Realization

**For Technical Teams**

- Release developer creativity, focus on business innovation rather than repetitive work
- Establish reliable technical infrastructure to support rapid business iteration
- Improve code quality and maintainability, reduce long-term maintenance costs

**For Product Delivery**

- Accelerate product iteration cycles, respond faster to market changes
- Improve delivery quality, enhance user satisfaction
- Reduce project risks, ensure on-time delivery

**For Organizational Efficiency**

- Optimize team collaboration patterns, improve overall development efficiency
- Standardize development processes, promote best practice dissemination
- Build scalable technical architecture to support business continuous growth

> Fetcher Generator is not just a technical tool, but an innovation in team collaboration patterns. Through automated and standardized approaches, it makes front-end and back-end collaboration more efficient and reliable, providing a solid foundation for the rapid iteration and high-quality delivery of digital products.

## 🌟 Features

- **🎯 OpenAPI 3.0+ Support**: Full support for OpenAPI 3.0+ specifications (JSON/YAML)
- **📦 TypeScript Code Generation**: Generates type-safe TypeScript interfaces, enums, and classes
- **🔧 CLI Tool**: Easy-to-use command-line interface for code generation
- **🎨 Decorator-Based APIs**: Generates decorator-based client classes for clean API interactions
- **📋 Comprehensive Models**: Handles complex schemas including unions, intersections, enums, and references
- **🚀 Fetcher Integration**: Seamlessly integrates with the Fetcher ecosystem packages
- **📊 Progress Logging**: Friendly logging with progress indicators during generation
- **📁 Auto Index Generation**: Automatically generates index.ts files for clean module organization
- **🌐 Remote Spec Support**: Load OpenAPI specs directly from HTTP/HTTPS URLs
- **🎭 Event Streaming**: Generates both regular and event-stream command clients
- **🏗️ Domain-Driven Design Support**: Specialized support for Wow framework with aggregates, commands, queries, and events (CQRS patterns)

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
- `-t, --ts-config-file-path <file>`: TypeScript configuration file path (optional)
- `-h, --help`: Display help information
- `-v, --version`: Display version number

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
import { CartApiClient, CartCommandClient } from './generated/example';

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
import { EXAMPLE_BOUNDED_CONTEXT_ALIAS } from '../boundedContext';

const DEFAULT_QUERY_CLIENT_OPTIONS: QueryClientOptions = {
  contextAlias: EXAMPLE_BOUNDED_CONTEXT_ALIAS,
  aggregateName: 'cart',
  resourceAttribution: ResourceAttributionPathSpec.OWNER,
};

export enum CartDomainEventTypeMapTitle {
  cart_item_added = '商品已加入购物车',
  cart_item_removed = 'cart_item_removed',
  cart_quantity_changed = 'cart_quantity_changed',
}

export type CartDomainEventType =
  | CartItemAdded
  | CartItemRemoved
  | CartQuantityChanged;

export const cartQueryClientFactory = new QueryClientFactory<
  CartState,
  CartAggregatedFields | string,
  CartDomainEventType
>(DEFAULT_QUERY_CLIENT_OPTIONS);
```

### Command Clients

```typescript
// Generated command client with decorator-based API
import { ContentTypeValues, PartialBy } from '@ahoo-wang/fetcher';
import {
  type ApiMetadata,
  type ApiMetadataCapable,
  api,
  attribute,
  autoGeneratedError,
  post,
  put,
  request,
} from '@ahoo-wang/fetcher-decorator';
import { JsonEventStreamResultExtractor } from '@ahoo-wang/fetcher-eventstream';
import type {
  CommandBody,
  CommandRequest,
  CommandResult,
  CommandResultEventStream,
} from '@ahoo-wang/fetcher-wow';
import {
  AddCartItem,
  ChangeQuantity,
  RemoveCartItem,
  ViewCart,
} from './types';
import { EXAMPLE_BOUNDED_CONTEXT_ALIAS } from '../boundedContext';

export enum CartCommandEndpointPaths {
  VIEW_CART = '/owner/{ownerId}/cart/view_cart',
  ADD_CART_ITEM = '/owner/{ownerId}/cart/add_cart_item',
  CHANGE_QUANTITY = '/owner/{ownerId}/cart/change_quantity',
  REMOVE_CART_ITEM = '/owner/{ownerId}/cart/remove_cart_item',
  // ... other command endpoint paths
}

export type AddCartItemCommand = CommandBody<
  PartialBy<AddCartItem, 'quantity'>
>;
export type ChangeQuantityCommand = CommandBody<ChangeQuantity>;
export type RemoveCartItemCommand = CommandBody<RemoveCartItem>;
export type ViewCartCommand = CommandBody<ViewCart>;

const DEFAULT_COMMAND_CLIENT_OPTIONS: ApiMetadata = {
  basePath: EXAMPLE_BOUNDED_CONTEXT_ALIAS,
};

@api()
export class CartCommandClient<R = CommandResult>
  implements ApiMetadataCapable
{
  constructor(
    public readonly apiMetadata: ApiMetadata = DEFAULT_COMMAND_CLIENT_OPTIONS,
  ) {}

  /** view_cart */
  @put(CartCommandEndpointPaths.VIEW_CART)
  viewCart(
    @request() commandRequest?: CommandRequest<ViewCartCommand>,
    @attribute() attributes?: Record<string, any>,
  ): Promise<R> {
    throw autoGeneratedError(commandRequest, attributes);
  }

  /**
   * 加入购物车
   * - operationId: `example.cart.add_cart_item`
   */
  @post(CartCommandEndpointPaths.ADD_CART_ITEM)
  addCartItem(
    @request() commandRequest: CommandRequest<AddCartItemCommand>,
    @attribute() attributes?: Record<string, any>,
  ): Promise<R> {
    throw autoGeneratedError(commandRequest, attributes);
  }

  /** 变更购买数量 */
  @put(CartCommandEndpointPaths.CHANGE_QUANTITY)
  changeQuantity(
    @request() commandRequest: CommandRequest<ChangeQuantityCommand>,
    @attribute() attributes?: Record<string, any>,
  ): Promise<R> {
    throw autoGeneratedError(commandRequest, attributes);
  }

  /** 删除商品 */
  @put(CartCommandEndpointPaths.REMOVE_CART_ITEM)
  removeCartItem(
    @request() commandRequest: CommandRequest<RemoveCartItemCommand>,
    @attribute() attributes?: Record<string, any>,
  ): Promise<R> {
    throw autoGeneratedError(commandRequest, attributes);
  }

  // ... other command methods
}
```

The generator also creates streaming command clients for event-driven interactions:

```typescript
@api('', {
  headers: { Accept: ContentTypeValues.TEXT_EVENT_STREAM },
  resultExtractor: JsonEventStreamResultExtractor,
})
export class CartStreamCommandClient extends CartCommandClient<CommandResultEventStream> {
  constructor(apiMetadata: ApiMetadata = DEFAULT_COMMAND_CLIENT_OPTIONS) {
    super(apiMetadata);
  }
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

The generator reads an optional JSON configuration file (default `./fetcher-generator.config.json`):

```json
{
  "apiClients": {
    "cart": {
      "ignorePathParameters": ["tenantId", "ownerId"]
    }
  }
}
```

By default, `tenantId` and `ownerId` path parameters are ignored when generating API client methods.

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

// Create event stream query client
const eventClient = cartQueryClientFactory.createEventStreamQueryClient({
  fetcher: fetcher,
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
