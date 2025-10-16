# @ahoo-wang/fetcher-generator

[![npm version](https://img.shields.io/npm/v/@ahoo-wang/fetcher-generator.svg)](https://www.npmjs.com/package/@ahoo-wang/fetcher-generator)
[![Build Status](https://github.com/Ahoo-Wang/fetcher/actions/workflows/ci.yml/badge.svg)](https://github.com/Ahoo-Wang/fetcher/actions)
[![codecov](https://codecov.io/gh/Ahoo-Wang/fetcher/graph/badge.svg?token=JGiWZ52CvJ)](https://codecov.io/gh/Ahoo-Wang/fetcher)
[![License](https://img.shields.io/npm/l/@ahoo-wang/fetcher-generator.svg)](https://github.com/Ahoo-Wang/fetcher/blob/main/LICENSE)
[![npm downloads](https://img.shields.io/npm/dm/@ahoo-wang/fetcher-generator.svg)](https://www.npmjs.com/package/@ahoo-wang/fetcher-generator)
[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/Ahoo-Wang/fetcher)

一个功能强大的 TypeScript 代码生成工具，能够基于 OpenAPI 规范自动生成类型安全的 API 客户端代码。不仅适用于通用场景，还专门为 [Wow](https://github.com/Ahoo-Wang/Wow) 领域驱动设计框架 深度优化，原生支持 CQRS 架构模式。

**[Wow](https://github.com/Ahoo-Wang/Wow) 框架**：一个领域驱动设计框架，提供事件溯源、CQRS（命令查询责任分离）和聚合模式，用于构建可扩展的分布式系统。此生成器为 Wow 的 CQRS 架构提供增强支持，同时保持与标准 REST API 的兼容性。

## 🌟 特性

- **🎯 OpenAPI 3.0+ 支持**：完整支持 OpenAPI 3.0+ 规范（JSON/YAML）
- **📦 TypeScript 代码生成**：生成类型安全的 TypeScript 接口、枚举和类

- **🔧 CLI 工具**：易用的命令行界面，用于代码生成
- **🎨 装饰器式 API**：生成装饰器式的客户端类，实现清晰的 API 交互
- **📋 全面的模型**：处理复杂的模式，包括联合、交集、枚举和引用
- **🚀 Fetcher 生态集成**：无缝集成 Fetcher 生态系统包
- **📊 进度日志**：生成过程中的友好日志记录和进度指示器
- **📁 自动索引生成**：自动生成 index.ts 文件，实现清晰的模块组织
- **🌐 远程规范支持**：直接从 HTTP/HTTPS URL 加载 OpenAPI 规范
- **🎭 事件流**：生成常规和事件流命令客户端
- **🏗️ 领域驱动设计支持**：为 Wow 框架提供专门支持，支持聚合、命令、查询和领域事件（CQRS 模式）

## 🚀 快速开始

### 安装

```bash
# 使用 npm
npm install -g @ahoo-wang/fetcher-generator

# 使用 pnpm
pnpm add -g @ahoo-wang/fetcher-generator

# 使用 yarn
yarn global add @ahoo-wang/fetcher-generator
```

### 基本用法

```bash
# 从 OpenAPI 规范生成 TypeScript 代码
fetcher-generator generate -i ./openapi-spec.json -o ./generated
```

## 📖 使用方法

### 命令行界面

```bash
fetcher-generator generate [options]
```

#### 选项

- `-i, --input <path>`：输入 OpenAPI 规范文件路径或 URL（必需）
  - 支持本地文件路径（例如：`./api-spec.json`、`./api-spec.yaml`）
  - 支持 HTTP/HTTPS URL（例如：`https://api.example.com/openapi.json`）
- `-o, --output <path>`：输出目录路径（默认为 `src/generated`）
- `-c, --config <file>`：配置文件路径（可选）
- `-h, --help`：显示帮助信息
- `-v, --version`：显示版本号

#### 示例

```bash
# 从本地 OpenAPI JSON 文件生成代码
fetcher-generator generate -i ./api-spec.json -o ./src/generated

# 从 YAML 规范生成代码
fetcher-generator generate -i ./api-spec.yaml -o ./src/generated

# 从远程 OpenAPI 规范通过 HTTPS 生成代码
fetcher-generator generate -i https://api.example.com/openapi.json -o ./src/generated

# 从远程 YAML 规范通过 HTTP 生成代码
fetcher-generator generate -i http://localhost:8080/api-spec.yaml -o ./src/generated
```

### 生成的代码结构

生成器在输出目录中创建以下结构：

```
output/
├── {bounded-context}/
│   ├── index.ts                   # 自动生成的索引文件，导出所有聚合和 API 客户端
│   ├── boundedContext.ts          # 有界上下文别名常量
│   ├── types.ts                   # 有界上下文的共享类型
│   ├── {Tag}ApiClient.ts          # API 客户端类，用于自定义端点（每个 OpenAPI 标签一个）
│   └── {aggregate}/               # 聚合特定文件
│       ├── index.ts               # 聚合的自动生成索引文件
│       ├── types.ts               # 聚合特定类型、模型和枚举
│       ├── queryClient.ts         # 查询客户端工厂，用于状态和事件查询
│       └── commandClient.ts       # 命令客户端类（常规和流式）
├── index.ts                       # 根索引文件，导出所有有界上下文
└── tsconfig.json                  # 生成代码的 TypeScript 配置
```

#### 索引文件生成

生成器自动创建 `index.ts` 文件，为便捷的模块导出提供支持：

- **根 index.ts**：导出所有有界上下文
- **有界上下文 index.ts**：导出上下文中的所有聚合和 API 客户端
- **聚合 index.ts**：导出聚合中的所有文件

这允许干净的导入，例如：

```typescript
// 导入有界上下文的所有内容
import * as example from './generated/example';

// 导入特定聚合和 API 客户端
import { cart, CartApiClient } from './generated/example';

// 导入特定文件
import { CartState } from './generated/example/cart';
```

## 🎯 生成的代码示例

### 模型

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

### 查询客户端

```typescript
// 生成的查询客户端工厂，用于领域驱动设计
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

### 命令客户端

```typescript
// 生成的命令客户端，具有装饰器式 API
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

生成器还创建流式命令客户端，用于事件驱动的交互：

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
  // ... 其他流式方法
}
```

### API 客户端

生成器还为不遵循领域驱动命令模式的自定义端点创建 API 客户端类。这些基于 OpenAPI 标签生成（每个标签一个客户端类）：

```typescript
// 生成的 API 客户端，用于自定义端点
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

/** 购物车 */
@api()
export class CartApiClient implements ApiMetadataCapable {
  constructor(
    public readonly apiMetadata: ApiMetadata = { basePath: 'example' },
  ) {}

  /** 自定义发送命令 */
  @post('/cart/{userId}/customize-send-cmd')
  customizeSendCmd(
    @path('userId') userId: string,
    @request() httpRequest?: ParameterRequest,
    @attribute() attributes?: Record<string, any>,
  ): Promise<CommandResult> {
    throw autoGeneratedError(userId, httpRequest, attributes);
  }

  /** 加入购物车（带事件流） */
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

  /** 获取当前用户的购物车 */
  @get('/cart/me')
  me(): Promise<CartData> {
    throw autoGeneratedError();
  }

  /** 获取当前用户的购物车（同步） */
  @get('/cart/me/sync')
  meSync(): Promise<CartData> {
    throw autoGeneratedError();
  }
}
```

## 🔧 与 Fetcher 集成

生成的代码设计为与 Fetcher 生态系统无缝集成：

```typescript
import { Fetcher } from '@ahoo-wang/fetcher';
import { all } from '@ahoo-wang/fetcher-wow';
import { cartQueryClientFactory } from './generated/example/cart/queryClient';
import { CartCommandClient } from './generated/example/cart/commandClient';
import { CartApiClient } from './generated/example/CartApiClient';

// 创建 fetcher 实例
const fetcher = new Fetcher({
  baseURL: 'https://api.example.com',
});

// 使用生成的查询客户端工厂
const snapshotClient = cartQueryClientFactory.createSnapshotQueryClient({ fetcher: fetcher });
const cartState = await snapshotClient.singleState({ condition: all() });

// 使用生成的命令客户端
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

// 使用生成的 API 客户端用于自定义端点（基于 OpenAPI 标签 "cart"）
const apiClient = new CartApiClient({ fetcher: fetcher });
const cartData = await apiClient.me();
```

## 📋 OpenAPI 规范要求

生成器期望 OpenAPI 3.0+ 规范具有 WOW 领域驱动设计框架的特定模式：

### 聚合定义

聚合通过遵循 `{context}.{aggregate}` 模式的标签进行识别。

### 操作模式

生成器通过 `operationId` 后缀识别操作：

- **状态快照**：以 `.snapshot_state.single` 结尾的操作
- **事件查询**：以 `.event.list_query` 结尾的操作
- **字段查询**：以 `.snapshot.count` 结尾的操作
- **命令**：任何具有有效命令请求/响应结构的 HTTP 操作

### 命令和查询

- **命令**：具有 `POST`、`PUT`、`DELETE` 方法的操作，返回 `wow.CommandOk` 响应
- **查询**：具有 `GET` 方法的操作，用于检索聚合状态或事件
- **事件**：返回事件流数组的操作，具有领域事件结构

### 模式约定

- 为模式使用描述性名称
- 避免以 `wow.` 为前缀的模式（保留供内部框架使用）
- 命令请求正文应引用 `components/schemas` 中的模式
- 状态和事件模式应遵循域建模的预期结构

## 🛠️ 开发

### 构建

```bash
# 构建包
pnpm build

# 运行测试
pnpm test

# 运行 linting
pnpm lint
```

### 测试生成器

```bash
# 生成测试输出
pnpm generate
```

## 🤝 贡献

欢迎贡献！请随时提交拉取请求。对于重大更改，请先打开 issue 进行讨论。

## 📄 许可证

本项目采用 Apache License 2.0 许可证 - 查看 [LICENSE](../../LICENSE) 文件获取详情。

## 🔗 链接

- [Fetcher 核心](https://github.com/Ahoo-Wang/fetcher/tree/main/packages/fetcher)
- [Fetcher 装饰器](https://github.com/Ahoo-Wang/fetcher/tree/main/packages/decorator)
- [Fetcher 事件流](https://github.com/Ahoo-Wang/fetcher/tree/main/packages/eventstream)
- [GitHub 仓库](https://github.com/Ahoo-Wang/fetcher)
- [NPM 包](https://www.npmjs.com/package/@ahoo-wang/fetcher-generator)
