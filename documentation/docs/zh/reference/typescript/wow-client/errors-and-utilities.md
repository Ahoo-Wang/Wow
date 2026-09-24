---
title: '业务错误与文档工具'
description: '业务错误与文档工具 — @ahoo-wang/wow-client'
---

# 业务错误与文档工具

HTTP 成功响应也可能携带失败的 Wow 业务结果。确认写入完成前应检查 errorCode。这些工具分类传输数据，不替你抛出领域异常或选择重试策略。

| 契约                                               | 含义、默认值与边界                                                                                     |
| -------------------------------------------------- | ------------------------------------------------------------------------------------------------------ |
| `ErrorInfo`                                        | 必填 errorCode/errorMsg，可选 bindingErrors 数组；每个 `BindingError` 用 name/msg 表达字段级验证问题。 |
| `ErrorCodes.isSucceeded(code)`                     | 严格等于 `SUCCEEDED`，即字符串 `'Ok'`；成功消息常量为空字符串。这不是 HTTP 状态检查。                  |
| `ErrorCodes.isError(code)`                         | isSucceeded 的取反；未知代码也视为错误。下方保留精确服务端标识，包括重复请求与版本冲突。               |
| `RecoverableType`                                  | RECOVERABLE、UNKNOWN、UNRECOVERABLE 元数据；即使 RECOVERABLE 也不能证明重复命令幂等。                  |
| `DynamicDocument` / `DynamicDocumentArray`         | `Record<string, any>` 及其数组；用于响应结构确实未知的场景，不做运行时验证。                           |
| `getPropertyValue<T>(object, path, defaultValue?)` | 点分字符串或路径段数组；最终值缺失/null 时返回 defaultValue（未传则 undefined）；空路径返回对象本身。  |

点分字符串会移除空段，路径段数组则保留空段。数字段用于数组下标；无效下标或缺失/null 中间值返回默认值。普通对象访问包括继承属性，不捕获抛错的 getter。不要把该工具用于不可信路径的授权过滤；它不分配需要清理的资源。

```ts
import { ErrorCodes, getPropertyValue } from '@ahoo-wang/wow-client';
console.assert(ErrorCodes.isSucceeded('Ok'));
console.assert(ErrorCodes.isError('DuplicateRequestId'));
console.assert(
  getPropertyValue({ rows: [{ name: 'Ada' }] }, 'rows.0.name') === 'Ada',
);
console.assert(
  getPropertyValue({ name: null }, 'name', 'unknown') === 'unknown',
);
```

所在执行阶段参见[命令结果](./commands)，传输/JSON 错误参见[失败边界](https://fetcher.ahoo.me/zh/architecture/failure-model)。

## 精确契约

### DynamicDocument {#api-DynamicDocument}

```ts
export type DynamicDocument = Record<string, any>;
```

[typescript/wow-client/src/query/types.ts:14](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/types.ts#L14)

### DynamicDocumentArray {#api-DynamicDocumentArray}

```ts
export type DynamicDocumentArray = DynamicDocument[];
```

[typescript/wow-client/src/query/types.ts:16](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/types.ts#L16)

### RecoverableType {#api-RecoverableType}

```ts
export enum RecoverableType {
  RECOVERABLE = 'RECOVERABLE',
  UNKNOWN = 'UNKNOWN',
  UNRECOVERABLE = 'UNRECOVERABLE',
}
```

[typescript/wow-client/src/types/error.ts:22](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/error.ts#L22)

### BindingError {#api-BindingError}

```ts
export interface BindingError {
  name: string;
  msg: string;
}
```

[typescript/wow-client/src/types/error.ts:55](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/error.ts#L55)

### ErrorInfo {#api-ErrorInfo}

```ts
export interface ErrorInfo {
  errorCode: string;
  errorMsg: string;
  bindingErrors?: BindingError[];
}
```

[typescript/wow-client/src/types/error.ts:66](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/error.ts#L66)

### ErrorCodes {#api-ErrorCodes}

::: details 展开完整字段与成员

```ts
export class ErrorCodes {
  static readonly SUCCEEDED = 'Ok';
  static readonly SUCCEEDED_MESSAGE = '';
  static readonly NOT_FOUND = 'NotFound';
  static readonly NOT_FOUND_MESSAGE = 'Not found resource!';
  static readonly BAD_REQUEST = 'BadRequest';
  static readonly ILLEGAL_ARGUMENT = 'IllegalArgument';
  static readonly ILLEGAL_STATE = 'IllegalState';
  static readonly REQUEST_TIMEOUT = 'RequestTimeout';
  static readonly TOO_MANY_REQUESTS = 'TooManyRequests';
  static readonly DUPLICATE_REQUEST_ID = 'DuplicateRequestId';
  static readonly COMMAND_VALIDATION = 'CommandValidation';
  static readonly REWRITE_NO_COMMAND = 'RewriteNoCommand';
  static readonly EVENT_VERSION_CONFLICT = 'EventVersionConflict';
  static readonly DUPLICATE_AGGREGATE_ID = 'DuplicateAggregateId';
  static readonly COMMAND_EXPECT_VERSION_CONFLICT =
    'CommandExpectVersionConflict';
  static readonly SOURCING_VERSION_CONFLICT = 'SourcingVersionConflict';
  static readonly ILLEGAL_ACCESS_DELETED_AGGREGATE =
    'IllegalAccessDeletedAggregate';
  static readonly ILLEGAL_ACCESS_OWNER_AGGREGATE =
    'IllegalAccessOwnerAggregate';
  static readonly ILLEGAL_ACCESS_SPACE_AGGREGATE =
    'IllegalAccessSpaceAggregate';
  static readonly INTERNAL_SERVER_ERROR = 'InternalServerError';
  static isSucceeded(errorCode: string): boolean;
  static isError(errorCode: string): boolean;
}
```

:::

[typescript/wow-client/src/types/error.ts:85](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/error.ts#L85)

### getPropertyValue {#api-getPropertyValue}

```ts
export function getPropertyValue<T = any>(
  object: any,
  propertyName: string | string[],
  defaultValue?: T,
): T | undefined;
```

[typescript/wow-client/src/getPropertyValue.ts:50](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/getPropertyValue.ts#L50)

[完整符号索引](./symbols)
