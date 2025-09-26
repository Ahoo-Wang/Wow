# @ahoo-wang/fetcher-generator

[![npm version](https://img.shields.io/npm/v/@ahoo-wang/fetcher-generator.svg)](https://www.npmjs.com/package/@ahoo-wang/fetcher-generator)
[![Build Status](https://github.com/Ahoo-Wang/fetcher/actions/workflows/ci.yml/badge.svg)](https://github.com/Ahoo-Wang/fetcher/actions)
[![codecov](https://codecov.io/gh/Ahoo-Wang/fetcher/graph/badge.svg?token=JGiWZ52CvJ)](https://codecov.io/gh/Ahoo-Wang/fetcher)
[![License](https://img.shields.io/npm/l/@ahoo-wang/fetcher-generator.svg)](https://github.com/Ahoo-Wang/fetcher/blob/main/LICENSE)
[![npm downloads](https://img.shields.io/npm/dm/@ahoo-wang/fetcher-generator.svg)](https://www.npmjs.com/package/@ahoo-wang/fetcher-generator)
[![npm bundle size](https://img.shields.io/bundlephobia/minzip/%40ahoo-wang%2Ffetcher-generator)](https://www.npmjs.com/package/@ahoo-wang/fetcher-generator)
[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/Ahoo-Wang/fetcher)

OpenAPI Specification TypeScript code generator for Fetcher. Generates data models, request/response types, and
ApiClient from OpenAPI specifications, specifically tailored for the WOW (domain-driven design) framework.

## 🌟 Features

- **🎯 OpenAPI 3.0+ Support**: Full support for OpenAPI 3.0+ specifications
- **📦 TypeScript Code Generation**: Generates type-safe TypeScript interfaces, enums, and classes
- **🏗️ Domain-Driven Design**: Specialized for WOW framework with aggregates, commands, and queries
- **🔧 CLI Tool**: Easy-to-use command-line interface for code generation
- **🎨 Decorator-Based APIs**: Generates decorator-based client classes for clean API interactions
- **📋 Comprehensive Models**: Handles complex schemas including unions, intersections, and references
- **🚀 Fetcher Integration**: Seamlessly integrates with the Fetcher ecosystem
- **📊 Progress Logging**: Friendly logging with progress indicators and emojis

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
- `-o, --output <path>`: Output directory path (required)
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
│   ├── boundedContext.ts          # Bounded context constants
│   ├── types.ts                   # Shared types for the bounded context
│   └── {aggregate}/               # Aggregate-specific files
│       ├── types.ts               # Aggregate-specific types and models
│       ├── queryClient.ts         # Query client classes
│       └── commandClient.ts       # Command client classes
└── tsconfig.json                  # TypeScript configuration
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

const DEFAULT_QUERY_CLIENT_OPTIONS: QueryClientOptions = {
  contextAlias: 'compensation',
  aggregateName: 'execution_failed',
  resourceAttribution: ResourceAttributionPathSpec.NONE,
};

type DOMAIN_EVENT_TYPES =
  | CompensationPrepared
  | ExecutionFailedApplied
  | ExecutionFailedCreated;

export const executionFailedQueryClientFactory = new QueryClientFactory<
  ExecutionFailedState,
  ExecutionFailedAggregatedFields | string,
  DOMAIN_EVENT_TYPES
>(DEFAULT_QUERY_CLIENT_OPTIONS);
```

### Command Clients

```typescript
// Generated command client with decorator-based API
import {
  api,
  post,
  put,
  path,
  request,
  attribute,
  autoGeneratedError,
} from '@ahoo-wang/fetcher-decorator';

const COMMAND_ENDPOINT_PATHS = {
  CREATE_EXECUTION_FAILED: '/execution_failed',
  PREPARE_COMPENSATION: '/execution_failed/{id}/prepare_compensation',
} as const;

@api()
export class ExecutionFailedCommandClient implements ApiMetadataCapable {
  constructor(
    public readonly apiMetadata: ApiMetadata = { basePath: 'compensation' },
  ) {}

  /** create_execution_failed */
  @post(COMMAND_ENDPOINT_PATHS.CREATE_EXECUTION_FAILED)
  createExecutionFailed(
    @request() commandRequest: CommandRequest<CreateExecutionFailed>,
    @attribute() attributes: Record<string, any>,
  ): Promise<CommandResult> {
    throw autoGeneratedError(commandRequest, attributes);
  }

  /** prepare_compensation */
  @put(COMMAND_ENDPOINT_PATHS.PREPARE_COMPENSATION)
  prepareCompensation(
    @path('id') id: string,
    @request() commandRequest: CommandRequest<PrepareCompensation>,
    @attribute() attributes: Record<string, any>,
  ): Promise<CommandResult> {
    throw autoGeneratedError(id, commandRequest, attributes);
  }
}
```

## 🔧 Integration with Fetcher

The generated code is designed to work seamlessly with the Fetcher ecosystem:

```typescript
import { Fetcher } from '@ahoo-wang/fetcher';
import { executionFailedQueryClientFactory } from './generated/compensation/execution_failed/queryClient';
import { ExecutionFailedCommandClient } from './generated/compensation/execution_failed/commandClient';

// Create a fetcher instance
const fetcher = new Fetcher({
  baseURL: 'https://api.example.com',
});

// Register the fetcher (if using named fetchers)
Fetcher.register('api', fetcher);

// Use the generated query client factory
const queryClient = executionFailedQueryClientFactory.createQueryClient();
const state = await queryClient.loadAggregate('aggregate-id');

// Use the generated command client
const commandClient = new ExecutionFailedCommandClient();
const result = await commandClient.createExecutionFailed(
  {
    command: {
      /* command data */
    },
  },
  {
    /* attributes */
  },
);
```

## 📋 OpenAPI Specification Requirements

The generator expects OpenAPI 3.0+ specifications with specific patterns for WOW framework:

### Aggregate Definition

Aggregates are identified by operation IDs following the pattern:

- `{context}.{aggregate}.*`

### Commands and Queries

- **Commands**: Operations with `POST`, `PUT`, `DELETE` methods
- **Queries**: Operations with `GET` method
- **Events**: Operations returning event streams

### Schema Naming

- Use descriptive names for schemas
- Avoid `wow.` prefixed schemas (reserved for internal use)

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
# Generate test output
pnpm generate
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
