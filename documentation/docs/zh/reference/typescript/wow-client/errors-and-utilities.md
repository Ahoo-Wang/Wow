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

`errorCode` 不是 `ErrorCodes.SUCCEEDED`（`'Ok'`）的命令结果即使以结果形式返回，也表示命令失败：确认写入完成前应比较 `result.errorCode`。`sendAndWaitStream` 中带这种错误码的结果仍作为结果传出，不会抛出。

| 契约                                       | 含义、默认值与边界                                                                                                                                              |
| ------------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `WowError`                                 | `Error` 子类，`name = 'WowError'`。含 `errorCode`、`errorMsg`（缺省为空串）、`bindingErrors`（缺省为空数组），以及可选的 `status`（HTTP 状态）与 `cause`（fetcher 的错误）。消息为 `[errorCode] errorMsg`。 |
| `toWowError(error)`                        | 异步。`WowError` 本身（或作为 `cause`）原样返回；否则读取 fetcher 错误的失败响应：先经克隆读取 JSON `ErrorInfo` 响应体，再退回 `Wow-Error-Code` 响应头。不是 Wow 应答的错误返回 `undefined`。 |
| `isErrorInfo(value)`                       | 类型守卫：对象，`errorCode` 为字符串，`errorMsg` 存在时为字符串。                                                                                               |
| `ErrorCodes`                               | 冻结的 `as const` 对象，列出 Wow 自身应答的错误码——对应 Kotlin 的 `ErrorCodes`，另含 `QUERY_SCHEMA_VALIDATION`/`CONFLICT`/`UNAVAILABLE` 与 `BATCH_TASK_ERROR`；值为字面量类型。 |
| `WowErrorCode` / `ErrorCode`               | `WowErrorCode` 是 `ErrorCodes` 值的联合；`ErrorCode` 是 `WowErrorCode` 或任意其他字符串（`string & {}`），应用自定义错误码也能通过类型检查，编辑器仍能补全 Wow 的错误码。`ErrorInfo.errorCode` 的类型是 `ErrorCode`。 |
| `ErrorInfo` / `BindingError`               | 必填 errorCode/errorMsg，可选 bindingErrors 数组；每个 `BindingError` 用 name/msg 表达字段级验证问题。                                                           |
| `RecoverableType`                          | `RECOVERABLE`（暂时性，重试可能成功）、`UNRECOVERABLE`（重试无济于事）、`UNKNOWN`（无法判断）。仅是元数据：即使 RECOVERABLE 也不能证明重复命令幂等。              |
| `DynamicDocument` / `DynamicDocumentArray` | `Record<string, any>` 及其数组；用于响应结构确实未知的场景，不做运行时验证。                                                                                    |

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
    for await (const event of await client.listStateStream(listQuery())) {
      rows.push(event.data);
    }
  } catch (error) {
    if (error instanceof WowError) console.warn(error.errorCode, error.errorMsg);
    throw error;
  }
  return rows;
}
```

内置流方法——`listStream`、`listStateStream`、`aggregateStream`、事件客户端的 `loadStream` 与 `CommandClient.sendAndWaitStream`——使用下方导出的两个结果提取器。生成的或手写的命令客户端可以把 `CommandResultEventStreamResultExtractor` 作为 `resultExtractor`，获得相同行为。

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
  errorCode: ErrorCode;
  errorMsg: string;
  bindingErrors?: BindingError[];
}
```

[typescript/wow-client/src/types/error.ts:66](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/error.ts#L66)

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
  INTERNAL_SERVER_ERROR: 'InternalServerError',
  QUERY_SCHEMA_VALIDATION: 'QuerySchemaValidation',
  QUERY_SCHEMA_CONFLICT: 'QuerySchemaConflict',
  QUERY_SCHEMA_UNAVAILABLE: 'QuerySchemaUnavailable',
  BATCH_TASK_ERROR: 'BatchTaskError',
} as const);
```

:::

[typescript/wow-client/src/types/error.ts:94](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/error.ts#L94)

### WowErrorCode {#api-WowErrorCode}

```ts
export type WowErrorCode = (typeof ErrorCodes)[keyof typeof ErrorCodes];
```

[typescript/wow-client/src/types/error.ts:142](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/error.ts#L142)

### ErrorCode {#api-ErrorCode}

```ts
export type ErrorCode = WowErrorCode | (string & {});
```

[typescript/wow-client/src/types/error.ts:149](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/error.ts#L149)

### WowErrorOptions {#api-WowErrorOptions}

```ts
export interface WowErrorOptions {
  status?: number;
  cause?: unknown;
}
```

[typescript/wow-client/src/types/wowError.ts:18](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/wowError.ts#L18)

### WowError {#api-WowError}

```ts
export class WowError extends Error implements ErrorInfo {
  override readonly name = 'WowError';
  readonly errorCode: ErrorCode;
  readonly errorMsg: string;
  readonly bindingErrors: BindingError[];
  readonly status?: number;
  readonly cause?: unknown;
  constructor(errorInfo: ErrorInfo, options?: WowErrorOptions);
}
```

[typescript/wow-client/src/types/wowError.ts:51](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/wowError.ts#L51)

### isErrorInfo {#api-isErrorInfo}

```ts
export function isErrorInfo(value: unknown): value is ErrorInfo;
```

[typescript/wow-client/src/types/wowError.ts:80](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/wowError.ts#L80)

### toWowError {#api-toWowError}

```ts
export async function toWowError(error: unknown): Promise<WowError | undefined>;
```

[typescript/wow-client/src/types/wowError.ts:124](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/wowError.ts#L124)

### QueryEventStreamResultExtractor {#api-QueryEventStreamResultExtractor}

```ts
export const QueryEventStreamResultExtractor: ResultExtractor<
  ReadableStream<JsonServerSentEvent<any>>
>;
```

把响应解析为 JSON 服务端推送事件，传出数据行（没有 `event:` 字段的事件）；遇到第一个其他名称的事件时以 `WowError` 使流出错。

[typescript/wow-client/src/eventStreams.ts:70](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/eventStreams.ts#L70)

### CommandResultEventStreamResultExtractor {#api-CommandResultEventStreamResultExtractor}

```ts
export const CommandResultEventStreamResultExtractor: ResultExtractor<
  ReadableStream<JsonServerSentEvent<CommandResult>>
>;
```

传出以 `CommandStage` 命名的事件，命令每到达一个阶段一个；其他事件名以 `WowError` 使流出错。

[typescript/wow-client/src/eventStreams.ts:86](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/eventStreams.ts#L86)

[完整符号索引](./symbols)
