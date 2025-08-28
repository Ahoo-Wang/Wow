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

- **📦 全面的类型定义**：为 Wow 框架实体提供完整的 TypeScript 支持
- **🔧 命令工具**：用于处理 Wow 命令和命令结果的辅助工具
- **🔍 查询 DSL**：丰富的查询条件构建器，支持多种操作符
- **📡 事件流支持**：与服务器发送事件集成，实现实时命令结果
- **🔄 CQRS 模式**：支持命令查询责任分离模式
- **🧱 DDD 构建块**：用于聚合、事件等的领域驱动设计类型

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

#### CommandHeaders

Wow 命令处理中使用的标准 HTTP 头部常量：

```typescript
import { CommandHeaders } from '@ahoo-wang/fetcher-wow';

// 使用示例
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

关键头部包括：

- `TENANT_ID` - 租户标识符
- `OWNER_ID` - 所有者标识符
- `AGGREGATE_ID` - 聚合根标识符
- `AGGREGATE_VERSION` - 预期聚合版本
- `REQUEST_ID` - 请求跟踪 ID
- `WAIT_*` - 各种等待条件头部
- `LOCAL_FIRST` - 本地处理偏好
- 以及更多...

#### CommandRequest

具有完整配置选项的命令请求接口：

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

表示命令执行结果的接口：

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

命令结果事件流的类型别名：

```typescript
import { CommandResult } from '@ahoo-wang/fetcher-wow';
import { JsonServerSentEventStream } from '@ahoo-wang/fetcher-eventstream';

// CommandResultEventStream 是 CommandResult 的 JsonServerSentEventStream
type CommandResultEventStream = JsonServerSentEventStream<CommandResult>;
```

while (true) {
const { done, value } = await reader.read();
if (done) break;

const commandResult: CommandResult = value.data;
console.log('命令结果:', commandResult);
}

````

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
````

#### 操作符

用于查询构建的完整操作符枚举：

```typescript
import { Operator } from '@ahoo-wang/fetcher-wow';

// 逻辑操作符
(Operator.AND, Operator.OR, Operator.NOR);

// 比较操作符
(Operator.EQ,
  Operator.NE,
  Operator.GT,
  Operator.LT,
  Operator.GTE,
  Operator.LTE);

// 成员操作符
(Operator.IN, Operator.NOT_IN, Operator.ALL_IN, Operator.BETWEEN);

// 字符串操作符
(Operator.CONTAINS, Operator.STARTS_WITH, Operator.ENDS_WITH);

// 存在性操作符
(Operator.NULL, Operator.NOT_NULL, Operator.EXISTS);

// 布尔操作符
(Operator.TRUE, Operator.FALSE);

// 日期操作符
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

// 特殊操作符
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

#### 可查询接口

用于构建带排序、分页和投影的查询的接口：

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

### 类型模块

#### 核心类型

领域建模的基本类型：

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

#### 错误处理

标准错误类型和代码：

```typescript
import { ErrorInfo, ErrorCodes, RecoverableType } from '@ahoo-wang/fetcher-wow';

const errorInfo: ErrorInfo = {
  errorCode: ErrorCodes.NOT_FOUND,
  errorMsg: '用户未找到',
  bindingErrors: [],
};

// 检查错误类型
if (ErrorCodes.isSucceeded(errorInfo.errorCode)) {
  console.log('操作成功');
} else {
  console.error('操作失败:', errorInfo.errorMsg);
}
```

#### 函数类型

事件和命令处理程序的函数信息：

```typescript
import { FunctionInfo, FunctionKind } from '@ahoo-wang/fetcher-wow';

const functionInfo: FunctionInfo = {
  functionKind: FunctionKind.COMMAND,
  contextName: 'user-context',
  processorName: 'UserProcessor',
  name: 'CreateUser',
};
```

## 🛠️ 高级用法

### 完整命令流程示例

```typescript
import {
  CommandRequest,
  CommandHeaders,
  CommandResult,
  CommandStage,
} from '@ahoo-wang/fetcher-wow';
import { fetchEventStream } from '@ahoo-wang/fetcher-eventstream';

// 1. 创建命令请求
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

// 2. 执行命令并等待结果
async function executeCommand(request: CommandRequest): Promise<CommandResult> {
  // 实现依赖于您的 HTTP 客户端
  // 这只是一个示例结构
  const response = await fetch('/api' + request.path, {
    method: request.method,
    headers: request.headers,
    body: JSON.stringify(request.body),
  });

  return response.json();
}

// 3. 实时流式处理命令结果
async function streamCommandResults() {
  const eventStream = fetchEventStream('/commands/stream');
  const commandResultStream = eventStream as CommandResultEventStream;

  const reader = commandResultStream.getReader();
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;

      const result: CommandResult = value.data;

      // 处理不同阶段
      switch (result.stage) {
        case CommandStage.SENT:
          console.log('命令已发送到总线');
          break;
        case CommandStage.PROCESSED:
          console.log('命令已被聚合根处理');
          break;
        case CommandStage.SNAPSHOT:
          console.log('已生成快照');
          break;
        case CommandStage.PROJECTED:
          console.log('事件已投影到读模型');
          break;
      }
    }
  } finally {
    reader.releaseLock();
  }
}
```

### 复杂查询构建

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

// 构建用户搜索的复杂查询
const userSearchQuery = {
  condition: and(
    eq('tenantId', 'tenant-123'),
    ne('status', 'deleted'),
    or(
      // 按姓名或邮箱搜索
      contains('name', 'john'),
      contains('email', 'john'),
    ),
    // 年龄和分数过滤
    gt('age', 18),
    between('score', 50, 100),

    // 部门过滤
    isIn('departments', 'engineering', 'marketing'),
    notIn('blockedDepartments', 'hr', 'finance'),

    // 字符串模式匹配
    startsWith('employeeId', 'EMP-'),
    endsWith('domain', '.com'),

    // 数组匹配
    elemMatch('roles', eq('name', 'admin')),

    // 日期过滤
    recentDays('lastLogin', 30),
    thisWeek('createdAt'),

    // 存在性检查
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
