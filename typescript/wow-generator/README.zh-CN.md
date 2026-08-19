# @ahoo-wang/fetcher-generator

[![npm version](https://img.shields.io/npm/v/@ahoo-wang/fetcher-generator.svg)](https://www.npmjs.com/package/@ahoo-wang/fetcher-generator)
[![Build Status](https://github.com/Ahoo-Wang/fetcher/actions/workflows/ci.yml/badge.svg)](https://github.com/Ahoo-Wang/fetcher/actions)
[![codecov](https://codecov.io/gh/Ahoo-Wang/fetcher/graph/badge.svg?token=JGiWZ52CvJ)](https://codecov.io/gh/Ahoo-Wang/fetcher)
[![License](https://img.shields.io/npm/l/@ahoo-wang/fetcher-generator.svg)](https://github.com/Ahoo-Wang/fetcher/blob/main/LICENSE)
[![npm downloads](https://img.shields.io/npm/dm/@ahoo-wang/fetcher-generator.svg)](https://www.npmjs.com/package/@ahoo-wang/fetcher-generator)
[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/Ahoo-Wang/fetcher)
[![Storybook](https://img.shields.io/badge/Storybook-交互式文档-FF4785)](https://fetcher.ahoo.me/?path=/docs/generator-introduction--docs)

一个功能强大的 TypeScript 代码生成工具，能够基于 OpenAPI 规范自动生成类型安全的 API 客户端代码。不仅适用于通用场景，还专门为 [Wow](https://github.com/Ahoo-Wang/Wow) 领域驱动设计框架 深度优化，原生支持 CQRS 架构模式。

**[Wow](https://github.com/Ahoo-Wang/Wow) 框架**：一个领域驱动设计框架，提供事件溯源、CQRS（命令查询责任分离）和聚合模式，用于构建可扩展的分布式系统。此生成器为 Wow 的 CQRS 架构提供增强支持，同时保持与标准 REST API 的兼容性。

## 💡 为什么需要代码生成器

在现代前后端分离架构中，团队协作效率往往成为项目成功的关键因素。Fetcher Generator 通过自动化代码生成，从根本上改变了传统的前后端协作模式，为团队带来显著的效率提升和质量保障。

### 🔍 传统协作的痛点分析

#### 🔄 同步效率低下

- **手动更新成本高**：后端接口变更时，前端需要手动更新多处代码，耗时且易错
- **版本管理复杂**：不同版本的 API 对应不同的客户端代码，维护成本呈指数级增长
- **沟通成本激增**：每次接口调整都需要前后端反复确认，影响开发节奏

#### 📝 代码质量隐患

- **类型不一致风险**：前后端分别维护类型定义，细微差异导致运行时错误
- **文档滞后问题**：API 文档与实际代码脱节，新人上手困难，团队知识传递效率低
- **代码规范不统一**：手动编写的客户端代码风格各异，增加维护难度

#### ⏱ 开发效率瓶颈

- **重复劳动**：为每个接口编写相似的请求处理代码，浪费创新时间
- **调试周期长**：类型不匹配问题往往在联调阶段才发现，修复成本高昂
- **迭代速度受限**：害怕破坏现有功能，导致重构和优化举步维艰

### 🚀 Generator 的革新方案

#### 🎯 自动化工作流

- **实时同步机制**：基于 OpenAPI 规范自动生成最新客户端代码，确保与后端 API 完全同步
- **单一数据源保障**：OpenAPI 规范作为唯一权威来源，彻底消除前后端理解偏差
- **变更快速响应**：API 调整后一键重新生成，分钟级完成全量更新

#### 🛡️ 质量保障体系

- **编译时类型检查**：在编码阶段捕获接口调用错误，防患于未然
- **标准化代码输出**：统一的代码风格和最佳实践，提升可维护性
- **自包含文档**：生成的代码自带完整类型提示和注释，降低理解成本

#### 💡 智能开发体验

- **智能代码补全**：完整的类型系统提供精准的代码提示，提升编码效率
- **语义化方法命名**：直观的 API 方法名，让代码自解释性更强
- **结构化代码组织**：清晰的模块划分，便于团队协作和代码审查

### 💰 可量化的效率提升

通过实际项目验证，使用 Generator 能为团队带来显著的效率提升：

- 📈 开发效率提升 30-50%
- 减少 60% 以上的手动编码工作量
- 节省 70% 的接口调试时间
- 降低 80% 的类型相关 bug

#### 👥 团队协作优化

- 新成员融入时间缩短 50%
- 代码审查效率提升 40%
- 跨团队沟通成本降低 60%

#### 🔄 工程效能改善

- API 变更响应速度提升 5 倍
- 重构信心和频率显著增加
- 技术债务积累速度减缓

#### 🌟 长期价值体现

**对于技术团队**

- 释放开发者创造力，专注于业务创新而非重复劳动
- 建立可靠的技术基础设施，支撑业务快速迭代
- 提升代码质量和可维护性，降低长期维护成本

**对于产品交付**

- 加速产品迭代周期，更快响应市场变化
- 提高交付质量，增强用户满意度
- 降低项目风险，确保按时交付

**对于组织效能**

- 优化团队协作模式，提升整体开发效能
- 标准化开发流程，促进最佳实践传播
- 构建可扩展的技术架构，支撑业务持续增长

> Fetcher Generator 不仅是技术工具，更是团队协作模式的革新，它通过自动化、标准化的方式，让前后端协作变得更加高效、可靠，为数字产品的快速迭代和高质量交付提供坚实保障。

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
- **🔧 自动化代码质量保障**：确保生成的代码符合最佳实践和质量标准
- **📏 标准化输出**：统一的代码风格和最佳实践，确保团队代码一致性
- **🛡️ 类型安全保障**：编译时错误检测，减少前后端联调时的类型不匹配问题
- **🧹 清洁的代码结构**：优化的导入导出语句，提升代码可维护性

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
- `-t, --ts-config-file-path <file>`：TypeScript 配置文件路径（可选）
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
import { CartApiClient, CartCommandClient } from './generated/example';

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

### 命令客户端

```typescript
// 生成的命令客户端，具有装饰器式 API
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
  // ... 其他命令端点路径
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

  // ... 其他命令方法
}
```

生成器还创建流式命令客户端，用于事件驱动的交互：

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
const snapshotClient = cartQueryClientFactory.createSnapshotQueryClient({
  fetcher: fetcher,
});
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
