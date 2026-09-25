---
title: '业务错误与文档工具'
description: '业务错误与文档工具 — @ahoo-wang/wow-client'
---

# 业务错误与文档工具

Wow 调用可能在三处失败，到达你代码的方式各不相同：

| 失败位置                           | 代码看到的内容                                                                                                                              |
| ---------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------- |
| 服务端拒绝请求（HTTP 4xx/5xx）     | Promise 以 fetcher 的错误拒绝。`await toWowError(error)` 把它转成携带服务端 `ErrorInfo` 与 HTTP 状态的 `WowError`。                         |
| 服务端推送事件流中途失败           | Wow 仍以 HTTP 200 应答，最后发送一个以错误码命名、数据为 `ErrorInfo` 的事件。所有内置流随即以 `WowError` 出错，`for await` 会抛出。         |
| 请求没有到达 Wow，或已被取消       | fetcher 自身的错误（网络、超时、中止、代理的错误页）。`toWowError` 返回 `undefined`，请处理或重新抛出原错误。                               |

处理失败的普通命令与其他被拒绝的请求一样：服务端把失败的 `CommandResult`（它是 `ErrorInfo`）按其 `errorCode` 映射的 HTTP 状态应答，所以 `send` 拒绝，`toWowError` 能读出它。在 `sendAndWaitStream` 中，失败的阶段仍作为结果传出，其 `errorCode` 不是 `ErrorCodes.SUCCEEDED`（`'Ok'`），需要逐个检查。各种情况见[错误处理](../../../guide/typescript/error-handling.md)指南。

| 契约                                       | 含义、默认值与边界                                                                                                                                              |
| ------------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `WowError`                                 | `Error` 子类，`name = 'WowError'`。含 `errorCode`、`errorMsg`（缺省为空串）、`bindingErrors`（缺省为空数组），以及可选的 `status`（HTTP 状态）与 `cause`（fetcher 的错误）。`violation` 取第一个带 `code` 的绑定错误，为 `{ code, path, message }`（被拒绝的查询才有），否则为 `undefined`。消息为 `[errorCode] errorMsg`。 |
| `toWowError(error)`                        | 异步。`WowError` 本身（或作为 `cause`）原样返回；否则读取 fetcher 错误的失败响应：先经克隆读取 JSON `ErrorInfo` 响应体，再退回 `Wow-Error-Code` 响应头。不是 Wow 应答的错误返回 `undefined`。 |
| `isErrorInfo(value)`                       | 类型守卫：对象，`errorCode` 为字符串，`errorMsg` 存在时为字符串。                                                                                               |
| `ErrorCodes`                               | 冻结的 `as const` 对象，列出 Wow 自身应答的错误码——对应 Kotlin 的 `ErrorCodes`，另含 `QUERY_SCHEMA_VALIDATION`/`CONFLICT`/`UNAVAILABLE` 与 `BATCH_TASK_ERROR`；值为字面量类型。 |
| `WowErrorCode` / `ErrorCode`               | `WowErrorCode` 是 `ErrorCodes` 值的联合；`ErrorCode` 是 `WowErrorCode` 或任意其他字符串（`string & {}`），应用自定义错误码也能通过类型检查，编辑器仍能补全 Wow 的错误码。`ErrorInfo.errorCode` 的类型是 `ErrorCode`。 |
| `ErrorInfo` / `BindingError`               | 必填 errorCode/errorMsg，可选 bindingErrors 数组；每个 `BindingError` 用 name/msg 表达字段级验证问题；被拒绝的查询还带 `code`。                                                           |
| `QueryErrorCodes` / `QueryErrorCode` / `QueryViolation` | 冻结的 `as const` 对象，对应 Kotlin 的 `QueryErrorCodes`：被拒绝的查询放在 `BindingError.code` 里的 30 个码。`QueryErrorCode` 是这些码或任意其他字符串：服务端只增不改名，未知的码按通用拒绝处理并显示 `errorMsg`。`QueryViolation` 是 `WowError.violation` 的类型。 |
| `RecoverableType`                          | `RECOVERABLE`（暂时性，重试可能成功）、`UNRECOVERABLE`（重试无济于事）、`UNKNOWN`（无法判断）。仅是元数据：即使 RECOVERABLE 也不能证明重复命令幂等。              |
| `DynamicDocument` / `DynamicDocumentArray` | `Record<string, unknown>` 及其数组：值要先收窄再读。`aggregate<Row>` 的 `Row` 可以是任意对象类型，接口也行。                                                                                    |

`toWowError` 是异步的，因为 fetcher 抛错时尚未读取错误响应体。响应会被克隆，其他处理器仍可读取响应体。这些工具不替你选择重试策略，也不分配需要清理的资源。

```ts
import {
  ErrorCodes,
  toWowError,
  type SnapshotQueryClient,
} from '@ahoo-wang/wow-client';

export async function findCart<S>(client: SnapshotQueryClient<S>, id: string) {
  try {
    return await client.getStateById(id);
  } catch (error) {
    const wowError = await toWowError(error);
    if (wowError?.errorCode === ErrorCodes.NOT_FOUND) return undefined;
    throw wowError ?? error;
  }
}
```

中途失败的流会在循环里直接抛出 `WowError`，无需转换：

```ts
import {
  WowError,
  listQuery,
  type SnapshotQueryClient,
} from '@ahoo-wang/wow-client';

export async function readAll<S>(client: SnapshotQueryClient<S>) {
  const rows: S[] = [];
  try {
    for await (const row of await client.listStateStream(listQuery({ limit: 1_000 }))) {
      rows.push(row);
    }
  } catch (error) {
    if (error instanceof WowError) console.warn(error.errorCode, error.errorMsg);
    throw error;
  }
  return rows;
}
```

内置流方法——`listStream`、`listStateStream`、`aggregateStream`、事件客户端的 `loadStream` 与 `CommandClient.sendAndWaitStream`——通过端点预设 `QUERY_STREAM_ENDPOINT` 与 `COMMAND_STREAM_ENDPOINT` 使用下方导出的两个结果提取器。生成的或手写的装饰器客户端要读流时，把预设传给 `@api` 或端点装饰器，就同时得到 `Accept: text/event-stream` 请求头和提取器。

所在执行阶段参见[命令结果](./commands)，传输/JSON 错误参见[失败边界](https://fetcher.ahoo.me/zh/architecture/failure-model)。

## 精确契约

### DynamicDocument {#api-DynamicDocument}

```ts
export type DynamicDocument = Record<string, unknown>;
```

[typescript/wow-client/src/dsl/documents.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/documents.ts)

### DynamicDocumentArray {#api-DynamicDocumentArray}

```ts
export type DynamicDocumentArray = DynamicDocument[];
```

[typescript/wow-client/src/dsl/documents.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/documents.ts)

### RecoverableType {#api-RecoverableType}

```ts
export enum RecoverableType {
  RECOVERABLE = 'RECOVERABLE',
  UNKNOWN = 'UNKNOWN',
  UNRECOVERABLE = 'UNRECOVERABLE',
}
```

[typescript/wow-client/src/error/errorInfo.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/error/errorInfo.ts)

### BindingError {#api-BindingError}

```ts
export interface BindingError {
  name: string;
  msg: string;
  code?: QueryErrorCode;
}
```

[typescript/wow-client/src/error/errorInfo.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/error/errorInfo.ts)

### ErrorInfo {#api-ErrorInfo}

```ts
export interface ErrorInfo {
  errorCode: ErrorCode;
  errorMsg: string;
  bindingErrors?: BindingError[];
}
```

[typescript/wow-client/src/error/errorInfo.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/error/errorInfo.ts)

### ErrorCodes {#api-ErrorCodes}

::: details 展开完整字段与成员

```ts
export const ErrorCodes = Object.freeze({
  SUCCEEDED: 'Ok',
  NOT_FOUND: 'NotFound',
  BAD_REQUEST: 'BadRequest',
  ILLEGAL_ARGUMENT: 'IllegalArgument',
  ILLEGAL_STATE: 'IllegalState',
  REQUEST_TIMEOUT: 'RequestTimeout',
  TOO_MANY_REQUESTS: 'TooManyRequests',
  DUPLICATE_REQUEST_ID: 'DuplicateRequestId',
  COMMAND_VALIDATION: 'CommandValidation',
  REWRITE_NO_COMMAND: 'RewriteNoCommand',
  EVENT_VERSION_CONFLICT: 'EventVersionConflict',
  DUPLICATE_AGGREGATE_ID: 'DuplicateAggregateId',
  COMMAND_EXPECT_VERSION_CONFLICT: 'CommandExpectVersionConflict',
  SOURCING_VERSION_CONFLICT: 'SourcingVersionConflict',
  ILLEGAL_ACCESS_DELETED_AGGREGATE: 'IllegalAccessDeletedAggregate',
  ILLEGAL_ACCESS_OWNER_AGGREGATE: 'IllegalAccessOwnerAggregate',
  ILLEGAL_ACCESS_SPACE_AGGREGATE: 'IllegalAccessSpaceAggregate',
  ILLEGAL_ACCESS_QUERY_SCOPE: 'IllegalAccessQueryScope',
  INTERNAL_SERVER_ERROR: 'InternalServerError',
  QUERY_SCHEMA_VALIDATION: 'QuerySchemaValidation',
  QUERY_SCHEMA_CONFLICT: 'QuerySchemaConflict',
  QUERY_SCHEMA_UNAVAILABLE: 'QuerySchemaUnavailable',
  BATCH_TASK_ERROR: 'BatchTaskError',
} as const);
```

:::

[typescript/wow-client/src/error/errorInfo.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/error/errorInfo.ts)

### WowErrorCode {#api-WowErrorCode}

```ts
export type WowErrorCode = (typeof ErrorCodes)[keyof typeof ErrorCodes];
```

[typescript/wow-client/src/error/errorInfo.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/error/errorInfo.ts)

### ErrorCode {#api-ErrorCode}

```ts
export type ErrorCode = WowErrorCode | (string & {});
```

[typescript/wow-client/src/error/errorInfo.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/error/errorInfo.ts)

### QueryErrorCodes {#api-QueryErrorCodes}

::: details 展开完整字段与成员

```ts
export const QueryErrorCodes = Object.freeze({
  INVALID_JSON: 'INVALID_JSON',
  BODY_NOT_OBJECT: 'BODY_NOT_OBJECT',
  EMPTY_BODY: 'EMPTY_BODY',
  UNKNOWN_PROPERTY: 'UNKNOWN_PROPERTY',
  UNKNOWN_TYPE: 'UNKNOWN_TYPE',
  UNKNOWN_VALUE: 'UNKNOWN_VALUE',
  INVALID_VALUE: 'INVALID_VALUE',
  INVALID_REQUEST: 'INVALID_REQUEST',
  CURSOR_SORT_DUPLICATE: 'CURSOR_SORT_DUPLICATE',
  CURSOR_SORT_TOO_MANY: 'CURSOR_SORT_TOO_MANY',
  UNKNOWN_FIELD: 'UNKNOWN_FIELD',
  UNSUPPORTED_CAPABILITY: 'UNSUPPORTED_CAPABILITY',
  ELEMENT_SCOPE_REQUIRED: 'ELEMENT_SCOPE_REQUIRED',
  VALUE_MISMATCH: 'VALUE_MISMATCH',
  NOT_COLLECTION: 'NOT_COLLECTION',
  NOT_SINGLE_STRING: 'NOT_SINGLE_STRING',
  MODEL_SEARCH_UNSUPPORTED: 'MODEL_SEARCH_UNSUPPORTED',
  CURSOR_NOT_ALLOWED: 'CURSOR_NOT_ALLOWED',
  PROTECTED_AGGREGATION: 'PROTECTED_AGGREGATION',
  PROTECTED_COMPARISON: 'PROTECTED_COMPARISON',
  MISSING_KEY_REQUIRES_STRING: 'MISSING_KEY_REQUIRES_STRING',
  ANY_REQUIRES_SINGLE_VALUE: 'ANY_REQUIRES_SINGLE_VALUE',
  INCOMPLETE_PROJECTION: 'INCOMPLETE_PROJECTION',
  METRIC_FILTER_SEARCH: 'METRIC_FILTER_SEARCH',
  METRIC_FILTER_ELEMENT_MATCH: 'METRIC_FILTER_ELEMENT_MATCH',
  METRIC_FILTER_ARRAY_FIELD: 'METRIC_FILTER_ARRAY_FIELD',
  NOT_PROJECTABLE: 'NOT_PROJECTABLE',
  EVENT_PROJECTION_TYPE_REQUIRED: 'EVENT_PROJECTION_TYPE_REQUIRED',
  TEMPORAL_REPRESENTATION_REQUIRED: 'TEMPORAL_REPRESENTATION_REQUIRED',
  TEMPORAL_CONFIGURATION_CONFLICT: 'TEMPORAL_CONFIGURATION_CONFLICT',
} as const);
```

:::

[typescript/wow-client/src/error/queryErrorCodes.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/error/queryErrorCodes.ts)

### KnownQueryErrorCode {#api-KnownQueryErrorCode}

```ts
export type KnownQueryErrorCode = (typeof QueryErrorCodes)[keyof typeof QueryErrorCodes];
```

[typescript/wow-client/src/error/queryErrorCodes.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/error/queryErrorCodes.ts)

### QueryErrorCode {#api-QueryErrorCode}

```ts
export type QueryErrorCode = KnownQueryErrorCode | (string & {});
```

[typescript/wow-client/src/error/queryErrorCodes.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/error/queryErrorCodes.ts)

### QueryViolation {#api-QueryViolation}

```ts
export interface QueryViolation {
  code: QueryErrorCode;
  path: string;
  message: string;
}
```

[typescript/wow-client/src/error/queryErrorCodes.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/error/queryErrorCodes.ts)

### WowErrorOptions {#api-WowErrorOptions}

```ts
export interface WowErrorOptions {
  status?: number;
  cause?: unknown;
}
```

[typescript/wow-client/src/error/wowError.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/error/wowError.ts)

### WowError {#api-WowError}

```ts
export class WowError extends Error implements ErrorInfo {
  override readonly name = 'WowError';
  readonly errorCode: ErrorCode;
  readonly errorMsg: string;
  readonly bindingErrors: BindingError[];
  readonly status?: number;
  readonly cause?: unknown;
  readonly violation?: QueryViolation;
  constructor(errorInfo: ErrorInfo, options?: WowErrorOptions);
}
```

[typescript/wow-client/src/error/wowError.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/error/wowError.ts)

### isErrorInfo {#api-isErrorInfo}

```ts
export function isErrorInfo(value: unknown): value is ErrorInfo;
```

[typescript/wow-client/src/error/wowError.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/error/wowError.ts)

### toWowError {#api-toWowError}

```ts
export async function toWowError(error: unknown): Promise<WowError | undefined>;
```

[typescript/wow-client/src/error/wowError.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/error/wowError.ts)

### COMMAND_STREAM_ENDPOINT {#api-COMMAND_STREAM_ENDPOINT}

```ts
export const COMMAND_STREAM_ENDPOINT: {
  readonly headers: { readonly Accept: 'text/event-stream' };
  readonly resultExtractor: ResultExtractor<
    ReadableStream<CommandResult>
  >;
};
```

以服务端推送事件流应答的命令端点选项：`Accept: text/event-stream` 加上一个结果提取器：它逐个产出命令到达的每个阶段的命令结果，服务端发来错误事件时以 `WowError` 结束流。`CommandClient.sendAndWaitStream` 用它；所有读流的装饰器命令客户端也应当用它——整个类用 `@api('', COMMAND_STREAM_ENDPOINT)`，单个端点用 `@post(path, COMMAND_STREAM_ENDPOINT)`。对象已冻结，要加选项请展开后再加。

[typescript/wow-client/src/transport/endpoints.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/transport/endpoints.ts)

### QUERY_STREAM_ENDPOINT {#api-QUERY_STREAM_ENDPOINT}

```ts
export const QUERY_STREAM_ENDPOINT: {
  readonly headers: { readonly Accept: 'text/event-stream' };
  readonly resultExtractor: ResultExtractor<
    ReadableStream<unknown>
  >;
};
```

以服务端推送事件流应答的查询端点选项：`Accept: text/event-stream` 加上一个结果提取器：它产出各行（没有 `event:` 字段的事件），遇到第一个其他名字的事件时以 `WowError` 结束流。查询客户端的各个 `*Stream` 方法用它。与 `COMMAND_STREAM_ENDPOINT` 一样已冻结。

[typescript/wow-client/src/transport/endpoints.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/transport/endpoints.ts)

[完整符号索引](./symbols)
