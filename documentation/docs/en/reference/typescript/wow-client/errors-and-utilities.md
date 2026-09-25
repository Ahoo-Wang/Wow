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
| `WowError`                                 | `Error` subclass, `name = 'WowError'`. `errorCode`, `errorMsg` (empty when absent), `bindingErrors` (empty array when absent), optional `status` (HTTP status) and `cause` (the fetcher's error). `violation` is `{ code, path, message }` of the first binding error with a `code` (a rejected query), `undefined` otherwise. The message is `[errorCode] errorMsg`. |
| `toWowError(error)`                        | Async. Returns a `WowError` as is (or when it is the `cause`); otherwise reads the failed response of a fetcher error: its JSON `ErrorInfo` body through a clone, failing that its `Wow-Error-Code` header. `undefined` when Wow did not answer. |
| `isErrorInfo(value)`                       | Type guard: an object with a string `errorCode`, and `errorMsg` a string when present.                                                                                                                         |
| `ErrorCodes`                               | Frozen `as const` object of the codes Wow itself answers with — Kotlin's `ErrorCodes` plus `QUERY_SCHEMA_VALIDATION`/`CONFLICT`/`UNAVAILABLE` and `BATCH_TASK_ERROR`. Values are literal types.               |
| `WowErrorCode` / `ErrorCode`               | `WowErrorCode` is the union of `ErrorCodes` values; `ErrorCode` is `WowErrorCode` or any other string (`string & {}`), so your application's own codes type-check while editors still complete Wow's. `ErrorInfo.errorCode` is `ErrorCode`. |
| `ErrorInfo` / `BindingError`               | Required `errorCode`/`errorMsg`, optional `bindingErrors`; each `BindingError` has `name`/`msg` for a field-level validation issue, and a rejected query's also a `code`.                                                                             |
| `QueryErrorCodes` / `QueryErrorCode` / `QueryViolation` | Frozen `as const` object mirroring Kotlin's `QueryErrorCodes`, the 30 codes a rejected query carries as `BindingError.code`. `QueryErrorCode` is those or any other string: the server adds codes and never renames one, so treat an unknown code as generic and show `errorMsg`. `QueryViolation` is what `WowError.violation` returns. |
| `RecoverableType`                          | `RECOVERABLE` (transient, retrying may succeed), `UNRECOVERABLE` (retrying will not help), `UNKNOWN` (cannot be determined). Metadata only: even `RECOVERABLE` does not prove a repeated command is idempotent. |
| `DynamicDocument` / `DynamicDocumentArray` | `Record<string, unknown>` / its array: narrow a value before reading it. `aggregate<Row>` takes any object type as `Row`, interfaces included.                                                                                          |

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

The built-in stream methods — `listStream`, `listStateStream`, `aggregateStream`, the event client's `loadStream` and `CommandClient.sendAndWaitStream` — use the two exported result extractors below, through the endpoint presets `QUERY_STREAM_ENDPOINT` and `COMMAND_STREAM_ENDPOINT`. A generated or hand-written decorated client that streams passes the preset to `@api` or to an endpoint decorator, and gets the `Accept: text/event-stream` header and the extractor together.

Read [command results](./commands) for the surrounding execution stage and [failure boundaries](https://fetcher.ahoo.me/architecture/failure-model) for transport/JSON errors.

## Exact contracts

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

::: details Expand all fields and members

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

The endpoint options of a command answered with a server-sent event stream: `Accept: text/event-stream` and a result extractor that yields the command results, one per stage the command reached, and errors the stream with a `WowError` when the server sends an error event. `CommandClient.sendAndWaitStream` uses it, and so should every decorated command client that streams — pass it to `@api('', COMMAND_STREAM_ENDPOINT)` for a whole class, or to `@post(path, COMMAND_STREAM_ENDPOINT)` for one endpoint. The object is frozen; spread it to add options.

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

The endpoint options of a query answered with a server-sent event stream: `Accept: text/event-stream` and a result extractor that yields the rows (events without an `event:` field) and errors the stream with a `WowError` at the first event with any other name. The `*Stream` methods of the query clients use it. Frozen, like `COMMAND_STREAM_ENDPOINT`.

[typescript/wow-client/src/transport/endpoints.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/transport/endpoints.ts)

[Complete symbol index](./symbols)
