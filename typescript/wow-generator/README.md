# @ahoo-wang/fetcher-generator

[![npm version](https://img.shields.io/npm/v/@ahoo-wang/fetcher-generator.svg)](https://www.npmjs.com/package/@ahoo-wang/fetcher-generator)
[![Build Status](https://github.com/Ahoo-Wang/fetcher/actions/workflows/ci.yml/badge.svg)](https://github.com/Ahoo-Wang/fetcher/actions)
[![codecov](https://codecov.io/gh/Ahoo-Wang/fetcher/graph/badge.svg?token=JGiWZ52CvJ)](https://codecov.io/gh/Ahoo-Wang/fetcher)
[![License](https://img.shields.io/npm/l/@ahoo-wang/fetcher-generator.svg)](https://github.com/Ahoo-Wang/fetcher/blob/main/LICENSE)
[![npm downloads](https://img.shields.io/npm/dm/@ahoo-wang/fetcher-generator.svg)](https://www.npmjs.com/package/@ahoo-wang/fetcher-generator)
[![npm bundle size](https://img.shields.io/bundlephobia/minzip/%40ahoo-wang%2Ffetcher-generator)](https://www.npmjs.com/package/@ahoo-wang/fetcher-generator)
[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/Ahoo-Wang/fetcher)

TypeScript code generator from OpenAPI specs for WOW domain-driven design framework. Generates type-safe models, query
clients, and command clients from OpenAPI specifications.

**WOW Framework**: A domain-driven design framework that provides event sourcing, CQRS (Command Query Responsibility
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
│   ├── index.ts                   # Auto-generated index file exporting all aggregates
│   ├── boundedContext.ts          # Bounded context alias constant
│   ├── types.ts                   # Shared types for the bounded context
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
- **Bounded context index.ts**: Exports all aggregates within the context
- **Aggregate index.ts**: Exports all files within the aggregate

This allows for clean imports like:

```typescript
// Import everything from a bounded context
import * as compensation from './generated/compensation';

// Import specific aggregates
import { executionFailed } from './generated/compensation';

// Import specific files
import { ExecutionFailedState } from './generated/compensation/execution_failed';
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

## 🔧 Integration with Fetcher

The generated code is designed to work seamlessly with the Fetcher ecosystem:

```typescript
import { Fetcher } from '@ahoo-wang/fetcher';
import { cartQueryClientFactory } from './generated/example/cart/queryClient';
import { CartCommandClient } from './generated/example/cart/commandClient';

// Create a fetcher instance
const fetcher = new Fetcher({
  baseURL: 'https://api.example.com',
});

// Register the fetcher (if using named fetchers)
Fetcher.register('api', fetcher);

// Use the generated query client factory
const queryClient = cartQueryClientFactory.createQueryClient();
const cartState = await queryClient.loadAggregate('cart-id');

// Use the generated command client
const commandClient = new CartCommandClient();
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
