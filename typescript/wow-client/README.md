# @ahoo-wang/fetcher-wow

[![npm version](https://img.shields.io/npm/v/@ahoo-wang/fetcher-wow.svg)](https://www.npmjs.com/package/@ahoo-wang/fetcher-wow)
[![Build Status](https://github.com/Ahoo-Wang/fetcher/actions/workflows/ci.yml/badge.svg)](https://github.com/Ahoo-Wang/fetcher/actions)
[![codecov](https://codecov.io/gh/Ahoo-Wang/fetcher/graph/badge.svg?token=JGiWZ52CvJ)](https://codecov.io/gh/Ahoo-Wang/fetcher)
[![License](https://img.shields.io/npm/l/@ahoo-wang/fetcher-wow.svg)](https://github.com/Ahoo-Wang/fetcher/blob/main/LICENSE)
[![npm downloads](https://img.shields.io/npm/dm/@ahoo-wang/fetcher-wow.svg)](https://www.npmjs.com/package/@ahoo-wang/fetcher-wow)
[![npm bundle size](https://img.shields.io/bundlephobia/minzip/%40ahoo-wang%2Ffetcher-wow)](https://www.npmjs.com/package/@ahoo-wang/fetcher-wow)
[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/Ahoo-Wang/fetcher)

Support for [Wow](https://github.com/Ahoo-Wang/Wow) in Fetcher. Provides TypeScript types and utilities for working with
the Wow CQRS/DDD framework.

## 🌟 Features

- **📦 Comprehensive Type Definitions**: Full TypeScript support for Wow framework entities
- **🔧 Command Utilities**: Helpers for working with Wow commands and command results
- **🔍 Query DSL**: Rich query condition builders with operator support
- **📡 Event Stream Support**: Integration with Server-Sent Events for real-time command results
- **🔄 CQRS Pattern**: Support for Command Query Responsibility Segregation patterns
- **🧱 DDD Building Blocks**: Domain-driven design types for aggregates, events, and more

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

#### CommandHeaders

Constants for standard HTTP headers used in Wow command processing:

```typescript
import { CommandHeaders } from '@ahoo-wang/fetcher-wow';

// Example usage
const request = {
  method: 'POST',
  headers: {
    [CommandHeaders.TENANT_ID]: 'tenant-123',
    [CommandHeaders.AGGREGATE_ID]: 'aggregate-456',
    [CommandHeaders.REQUEST_ID]: 'request-789',
  },
  body: JSON.stringify(command),
};
```

Key headers include:

- `TENANT_ID` - Tenant identifier
- `OWNER_ID` - Owner identifier
- `AGGREGATE_ID` - Aggregate root identifier
- `AGGREGATE_VERSION` - Expected aggregate version
- `REQUEST_ID` - Request tracking ID
- `WAIT_*` - Various wait condition headers
- `LOCAL_FIRST` - Local processing preference
- And many more...

#### CommandRequest

Interface for command requests with full configuration options:

```typescript
import { CommandRequest, CommandHeaders } from '@ahoo-wang/fetcher-wow';

const commandRequest: CommandRequest = {
  path: '/commands/CreateUser',
  method: 'POST',
  headers: {
    [CommandHeaders.TENANT_ID]: 'tenant-123',
  },
  body: {
    name: 'John Doe',
    email: 'john@example.com',
  },
  timeout: 5000,
  aggregateId: 'user-456',
  requestId: 'req-789',
  localFirst: true,
  stream: false,
};
```

#### CommandResult

Interface representing the result of command execution:

```typescript
import { CommandResult, CommandStage } from '@ahoo-wang/fetcher-wow';

const commandResult: CommandResult = {
  id: 'result-123',
  commandId: 'cmd-456',
  requestId: 'req-789',
  stage: CommandStage.PROCESSED,
  contextName: 'user-context',
  aggregateName: 'User',
  aggregateId: 'user-456',
  aggregateVersion: 1,
  errorCode: 'Ok',
  errorMsg: '',
  function: {
    functionKind: 'COMMAND',
    contextName: 'user-context',
    processorName: 'UserProcessor',
    name: 'CreateUser',
  },
  signalTime: Date.now(),
};
```

#### CommandResultEventStream

Utilities for working with command result event streams:

```typescript
import {
  toCommandResultEventStream,
  CommandResultEvent,
} from '@ahoo-wang/fetcher-wow';
import { fetchEventStream } from '@ahoo-wang/fetcher-eventstream';

// Convert ServerSentEventStream to CommandResultEventStream
const eventStream = fetchEventStream('/commands/stream');
const commandResultStream = toCommandResultEventStream(eventStream);

// Process command results as they arrive
const reader = commandResultStream.getReader();
while (true) {
  const { done, value } = await reader.read();
  if (done) break;

  const commandResult: CommandResult = value.data;
  console.log('Command result:', commandResult);
}
```

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

#### Operators

Full operator enumeration for query building:

```typescript
import { Operator } from '@ahoo-wang/fetcher-wow';

// Logical operators
(Operator.AND, Operator.OR, Operator.NOR);

// Comparison operators
(Operator.EQ,
  Operator.NE,
  Operator.GT,
  Operator.LT,
  Operator.GTE,
  Operator.LTE);

// Membership operators
(Operator.IN, Operator.NOT_IN, Operator.ALL_IN, Operator.BETWEEN);

// String operators
(Operator.CONTAINS, Operator.STARTS_WITH, Operator.ENDS_WITH);

// Existence operators
(Operator.NULL, Operator.NOT_NULL, Operator.EXISTS);

// Boolean operators
(Operator.TRUE, Operator.FALSE);

// Date operators
(Operator.TODAY,
  Operator.BEFORE_TODAY,
  Operator.TOMORROW,
  Operator.THIS_WEEK,
  Operator.NEXT_WEEK,
  Operator.LAST_WEEK,
  Operator.THIS_MONTH,
  Operator.LAST_MONTH,
  Operator.RECENT_DAYS,
  Operator.EARLIER_DAYS);

// Special operators
(Operator.ID,
  Operator.IDS,
  Operator.AGGREGATE_ID,
  Operator.AGGREGATE_IDS,
  Operator.TENANT_ID,
  Operator.OWNER_ID,
  Operator.DELETED,
  Operator.ALL,
  Operator.ELEM_MATCH,
  Operator.RAW);
```

#### Queryable Interface

Interfaces for building queries with sorting, pagination, and projection:

```typescript
import {
  Queryable,
  SortDirection,
  DEFAULT_PAGINATION,
} from '@ahoo-wang/fetcher-wow';

const query: Queryable = {
  condition: eq('status', 'active'),
  sort: [
    { field: 'createdAt', direction: SortDirection.DESC },
    { field: 'name', direction: SortDirection.ASC },
  ],
  projection: {
    include: ['id', 'name', 'email', 'status'],
    exclude: ['password', 'internalNotes'],
  },
};

const pagedQuery = {
  ...query,
  pagination: {
    index: 2,
    size: 20,
  },
};
```

### Types Module

#### Core Types

Essential types for domain modeling:

```typescript
import {
  Identifier,
  Version,
  TenantId,
  OwnerId,
  NamedAggregate,
  AggregateId,
  StateCapable,
} from '@ahoo-wang/fetcher-wow';

interface User
  extends Identifier,
    Version,
    TenantId,
    OwnerId,
    NamedAggregate,
    StateCapable<UserState> {
  id: string;
  version: number;
  tenantId: string;
  ownerId: string;
  contextName: string;
  aggregateName: string;
  state: UserState;
}

interface UserState {
  name: string;
  email: string;
  status: 'active' | 'inactive';
  createdAt: number;
}
```

#### Error Handling

Standard error types and codes:

```typescript
import { ErrorInfo, ErrorCodes, RecoverableType } from '@ahoo-wang/fetcher-wow';

const errorInfo: ErrorInfo = {
  errorCode: ErrorCodes.NOT_FOUND,
  errorMsg: 'User not found',
  bindingErrors: [],
};

// Check error types
if (ErrorCodes.isSucceeded(errorInfo.errorCode)) {
  console.log('Operation successful');
} else {
  console.error('Operation failed:', errorInfo.errorMsg);
}
```

#### Function Types

Function information for event and command handlers:

```typescript
import { FunctionInfo, FunctionKind } from '@ahoo-wang/fetcher-wow';

const functionInfo: FunctionInfo = {
  functionKind: FunctionKind.COMMAND,
  contextName: 'user-context',
  processorName: 'UserProcessor',
  name: 'CreateUser',
};
```

## 🛠️ Advanced Usage

### Complete Command Flow Example

```typescript
import {
  CommandRequest,
  CommandHeaders,
  CommandResult,
  CommandStage,
  toCommandResultEventStream,
} from '@ahoo-wang/fetcher-wow';
import { fetchEventStream } from '@ahoo-wang/fetcher-eventstream';

// 1. Create command request
const commandRequest: CommandRequest = {
  path: '/commands/user/CreateUser',
  method: 'POST',
  headers: {
    [CommandHeaders.TENANT_ID]: 'tenant-123',
    [CommandHeaders.REQUEST_ID]: 'req-' + Date.now(),
  },
  body: {
    name: 'John Doe',
    email: 'john@example.com',
  },
  timeout: 10000,
  localFirst: true,
};

// 2. Execute command and wait for result
async function executeCommand(request: CommandRequest): Promise<CommandResult> {
  // Implementation depends on your HTTP client
  // This is just an example structure
  const response = await fetch('/api' + request.path, {
    method: request.method,
    headers: request.headers,
    body: JSON.stringify(request.body),
  });

  return response.json();
}

// 3. Stream command results in real-time
async function streamCommandResults() {
  const eventStream = fetchEventStream('/commands/stream');
  const commandResultStream = toCommandResultEventStream(eventStream);

  const reader = commandResultStream.getReader();
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;

      const result: CommandResult = value.data;

      // Handle different stages
      switch (result.stage) {
        case CommandStage.SENT:
          console.log('Command sent to bus');
          break;
        case CommandStage.PROCESSED:
          console.log('Command processed by aggregate');
          break;
        case CommandStage.SNAPSHOT:
          console.log('Snapshot generated');
          break;
        case CommandStage.PROJECTED:
          console.log('Events projected to read model');
          break;
      }
    }
  } finally {
    reader.releaseLock();
  }
}
```

### Complex Query Building

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
  notIn,
  between,
  startsWith,
  endsWith,
  elemMatch,
  isNull,
  notNull,
  exists,
  today,
  thisWeek,
  recentDays,
} from '@ahoo-wang/fetcher-wow';

// Build a complex query for user search
const userSearchQuery = {
  condition: and(
    eq('tenantId', 'tenant-123'),
    ne('status', 'deleted'),
    or(
      // Search by name or email
      contains('name', 'john'),
      contains('email', 'john'),
    ),
    // Age and score filters
    gt('age', 18),
    between('score', 50, 100),

    // Department filters
    isIn('departments', 'engineering', 'marketing'),
    notIn('blockedDepartments', 'hr', 'finance'),

    // String pattern matching
    startsWith('employeeId', 'EMP-'),
    endsWith('domain', '.com'),

    // Array matching
    elemMatch('roles', eq('name', 'admin')),

    // Date filters
    recentDays('lastLogin', 30),
    thisWeek('createdAt'),

    // Existence checks
    exists('phoneNumber'),
    notNull('address'),
  ),

  sort: [
    { field: 'score', direction: 'DESC' },
    { field: 'lastLogin', direction: 'DESC' },
  ],

  projection: {
    include: ['id', 'name', 'email', 'score', 'lastLogin', 'departments'],
  },

  pagination: {
    index: 1,
    size: 50,
  },
};
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
