---
title: 'Business errors and document utilities'
description: 'Business errors and document utilities — @ahoo-wang/wow-client'
---

# Business errors and document utilities

A Wow call can fail in three places, and each reaches your code differently:

| Where it fails                                  | What your code sees                                                                                                                                                              |
| ----------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| The server refuses the request (HTTP 4xx/5xx)   | The Promise rejects with the fetcher's error. `await toWowError(error)` turns it into a `WowError` carrying the server's `ErrorInfo` and the HTTP status.                         |
| A server-sent event stream fails midway         | Wow still answered HTTP 200; it sends one last event named after the error code with an `ErrorInfo` body. Every built-in stream then errors with a `WowError`, so `for await` throws. |
| The request never reached Wow, or was cancelled | The fetcher's own error (network, timeout, abort, a proxy's error page). `toWowError` returns `undefined`; handle or rethrow the original error.                                  |

A plain command whose processing failed is refused like any other request: the server answers the failed `CommandResult`, which is an `ErrorInfo`, with the HTTP status its `errorCode` maps to, so `send` rejects and `toWowError` reads it. In a `sendAndWaitStream`, a stage that failed is passed on as a result whose `errorCode` is not `ErrorCodes.SUCCEEDED` (`'Ok'`); check each result. The [Error Handling](../../../guide/typescript/error-handling.md) guide walks through every case.

| Contract                                   | Meaning, default and boundary                                                                                                                                                                                  |
| ------------------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `WowError`                                 | `Error` subclass, `name = 'WowError'`. `errorCode`, `errorMsg` (empty when absent), `bindingErrors` (empty array when absent), optional `status` (HTTP status) and `cause` (the fetcher's error). The message is `[errorCode] errorMsg`. |
| `toWowError(error)`                        | Async. Returns a `WowError` as is (or when it is the `cause`); otherwise reads the failed response of a fetcher error: its JSON `ErrorInfo` body through a clone, failing that its `Wow-Error-Code` header. `undefined` when Wow did not answer. |
| `isErrorInfo(value)`                       | Type guard: an object with a string `errorCode`, and `errorMsg` a string when present.                                                                                                                         |
| `ErrorCodes`                               | Frozen `as const` object of the codes Wow itself answers with — Kotlin's `ErrorCodes` plus `QUERY_SCHEMA_VALIDATION`/`CONFLICT`/`UNAVAILABLE` and `BATCH_TASK_ERROR`. Values are literal types.               |
| `WowErrorCode` / `ErrorCode`               | `WowErrorCode` is the union of `ErrorCodes` values; `ErrorCode` is `WowErrorCode` or any other string (`string & {}`), so your application's own codes type-check while editors still complete Wow's. `ErrorInfo.errorCode` is `ErrorCode`. |
| `ErrorInfo` / `BindingError`               | Required `errorCode`/`errorMsg`, optional `bindingErrors`; each `BindingError` has `name`/`msg` for a field-level validation issue.                                                                             |
| `RecoverableType`                          | `RECOVERABLE` (transient, retrying may succeed), `UNRECOVERABLE` (retrying will not help), `UNKNOWN` (cannot be determined). Metadata only: even `RECOVERABLE` does not prove a repeated command is idempotent. |
| `DynamicDocument` / `DynamicDocumentArray` | `Record<string, any>` / its array. Use only when the response schema is intentionally unknown; no runtime validation.                                                                                          |

`toWowError` is asynchronous because the error body has not been read when the fetcher throws. The response is cloned, so its body stays readable for other handlers. The helpers choose no retry policy and allocate nothing that needs cleanup.

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

A stream that fails midway throws the `WowError` from the loop itself; no conversion is needed:

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

The built-in stream methods — `listStream`, `listStateStream`, `aggregateStream`, the event client's `loadStream` and `CommandClient.sendAndWaitStream` — use the two exported result extractors below. A generated or hand-written command client can pass `CommandResultEventStreamResultExtractor` as its `resultExtractor` to get the same behaviour.

Read [command results](./commands) for the surrounding execution stage and [failure boundaries](https://fetcher.ahoo.me/architecture/failure-model) for transport/JSON errors.

## Exact contracts

### DynamicDocument {#api-DynamicDocument}

```ts
export type DynamicDocument = Record<string, any>;
```

[typescript/wow-client/src/query/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/types.ts)

### DynamicDocumentArray {#api-DynamicDocumentArray}

```ts
export type DynamicDocumentArray = DynamicDocument[];
```

[typescript/wow-client/src/query/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/types.ts)

### RecoverableType {#api-RecoverableType}

```ts
export enum RecoverableType {
  RECOVERABLE = 'RECOVERABLE',
  UNKNOWN = 'UNKNOWN',
  UNRECOVERABLE = 'UNRECOVERABLE',
}
```

[typescript/wow-client/src/types/error.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/error.ts)

### BindingError {#api-BindingError}

```ts
export interface BindingError {
  name: string;
  msg: string;
}
```

[typescript/wow-client/src/types/error.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/error.ts)

### ErrorInfo {#api-ErrorInfo}

```ts
export interface ErrorInfo {
  errorCode: ErrorCode;
  errorMsg: string;
  bindingErrors?: BindingError[];
}
```

[typescript/wow-client/src/types/error.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/error.ts)

### ErrorCodes {#api-ErrorCodes}

::: details Expand all fields and members

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

[typescript/wow-client/src/types/error.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/error.ts)

### WowErrorCode {#api-WowErrorCode}

```ts
export type WowErrorCode = (typeof ErrorCodes)[keyof typeof ErrorCodes];
```

[typescript/wow-client/src/types/error.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/error.ts)

### ErrorCode {#api-ErrorCode}

```ts
export type ErrorCode = WowErrorCode | (string & {});
```

[typescript/wow-client/src/types/error.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/error.ts)

### WowErrorOptions {#api-WowErrorOptions}

```ts
export interface WowErrorOptions {
  status?: number;
  cause?: unknown;
}
```

[typescript/wow-client/src/types/wowError.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/wowError.ts)

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

[typescript/wow-client/src/types/wowError.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/wowError.ts)

### isErrorInfo {#api-isErrorInfo}

```ts
export function isErrorInfo(value: unknown): value is ErrorInfo;
```

[typescript/wow-client/src/types/wowError.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/wowError.ts)

### toWowError {#api-toWowError}

```ts
export async function toWowError(error: unknown): Promise<WowError | undefined>;
```

[typescript/wow-client/src/types/wowError.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/wowError.ts)

### QueryEventStreamResultExtractor {#api-QueryEventStreamResultExtractor}

```ts
export const QueryEventStreamResultExtractor: ResultExtractor<
  ReadableStream<JsonServerSentEvent<any>>
>;
```

Parses the response as JSON server-sent events and passes the rows (events without an `event:` field). The first event with any other name errors the stream with a `WowError`.

[typescript/wow-client/src/eventStreams.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/eventStreams.ts)

### CommandResultEventStreamResultExtractor {#api-CommandResultEventStreamResultExtractor}

```ts
export const CommandResultEventStreamResultExtractor: ResultExtractor<
  ReadableStream<JsonServerSentEvent<CommandResult>>
>;
```

Passes the events named after a `CommandStage`, one per stage the command reached; any other event name errors the stream with a `WowError`.

[typescript/wow-client/src/eventStreams.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/eventStreams.ts)

[Complete symbol index](./symbols)
