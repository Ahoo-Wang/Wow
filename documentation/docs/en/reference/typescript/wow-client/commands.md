---
title: 'Commands and wait results'
description: 'Commands and wait results — @ahoo-wang/wow-client'
---

# Commands and wait results

`CommandClient` is a decorator-backed transport. It is not generic: the body type is chosen per call, so one client sends every command of an aggregate. `send<C>(request, attributes?)` returns Promise&lt;CommandResult&gt; for the stage the command waited for. `sendAndWaitStream<C>(request, attributes?)` returns Promise&lt;ReadableStream&lt;JsonServerSentEvent&lt;CommandResult&gt;&gt;&gt; — one result per stage the command reaches — with Accept text/event-stream. Supply ApiMetadata and/or a CommandRequest with the endpoint required by your service; no universal command URL is inferred from `C`.

## Requests and wait stages

CommandRequest extends ParameterRequest. Its body is `CommandBody<C>` (the command's writable fields), with optional `path` (endpoint path override, not `url`), `method`, URL path parameters, headers and other request data. The transport does not synthesize an idempotency key or choose a wait stage for you.

`CommandHeaders` and `WowHeaders` are frozen `as const` objects of header names, so each value is a string literal type. `CommandHeaders.SPACE_ID` is `Wow-Space-Id` (`WowHeaders.SPACE_ID`), which the server shares with queries; `Command-Header-` is the prefix of custom headers the server copies into the command header. `CommandRequestHeaders` types every known command header by what the server parses — each is optional:

| Header                                                       | Value type                                           |
| ------------------------------------------------------------ | ---------------------------------------------------- |
| `Command-Wait-Stage`, `Command-Wait-Tail-Stage`               | `CommandStageName` — a `CommandStage` or its name     |
| `Command-Aggregate-Version`, `Command-Wait-Timeout`          | `` `${number}` ``, an integer as a string             |
| `Command-Local-First`                                        | `'true'` or `'false'`                                 |
| tenant, owner, space, aggregate id, request id, wait context/processor/function, tail selectors, `/wow/command/send` context/name/type | `string` |

Any other header (`Authorization`, a `Command-Header-*` extension) is still allowed as a string. The generic `/wow/command/send` route has no method of its own: send to it with `CommandClient` and the `COMMAND_AGGREGATE_CONTEXT`, `COMMAND_AGGREGATE_NAME` and `COMMAND_TYPE` headers. Build the headers with two helpers instead of by hand; both omit the headers whose options you leave out and throw `TypeError` on invalid input:

- `commandHeaders({ tenantId?, ownerId?, spaceId?, aggregateId?, aggregateVersion?, requestId?, localFirst? })` says who the command is for. `aggregateVersion` must be a non-negative integer; `localFirst` becomes `'true'`/`'false'`.
- `waitStrategy({ stage?, context?, processor?, function?, timeoutMs?, tail? })` says what the server waits for before it answers — Kotlin's `WaitingFor`. A stage must be a `CommandStage`; `timeoutMs` must be a positive integer. Without a stage the server waits for `PROCESSED` in the command's own bounded context.
- A `tail` makes it a wait chain: wait for a saga to handle the command's events (`stage: 'SAGA_HANDLED'`, optionally narrowed by `processor`), then for the command that saga sends to reach `tail.stage`, optionally in `tail.context`/`tail.processor`/`tail.function`. A `tail` with any other stage is a type error and throws at runtime, because the server would ignore it.

| CommandStage  | Signal represented  |
| ------------- | ------------------- |
| SENT          | Command sent        |
| PROCESSED     | Command processed   |
| SNAPSHOT      | Snapshot stage      |
| PROJECTED     | Projection stage    |
| EVENT_HANDLED | Event handler stage |
| SAGA_HANDLED  | Saga handler stage  |

These values are distinct server wait targets, not a client-side guarantee that every downstream consumer is caught up. The client sets no wait timeout default. A CommandResult carries identity, aggregate attribution, stage, command/request/wait IDs, signal time, optional aggregateVersion, function info, result map and errorCode/errorMsg/bindingErrors. `WaitSignal` carries the corresponding signal model with nested aggregateId. BatchResult contains after/size and ErrorInfo; it does not implement batch iteration.

## Failures

- A request the server refuses (validation, a version conflict, a duplicate request id, …) rejects the Promise; `await toWowError(error)` reads the server's `ErrorInfo` into a `WowError`. See [errors](./errors-and-utilities).
- A command whose processing failed is refused the same way: the server answers the failed `CommandResult`, an `ErrorInfo`, with the HTTP status its `errorCode` maps to (for example 400 `IllegalArgument`), so `send` resolves only with `errorCode` `'Ok'`.
- `sendAndWaitStream` errors the stream with a `WowError` when the server fails midway — for example when the wait times out — so a `for await` over it throws. Results whose own `errorCode` is not `Ok` are still passed on as results. The consumer must cancel/release a reader on early exit.

DeleteAggregate/RecoverAggregate are empty command-body contracts; resource-tag commands carry tags. They do not delete/recover anything until a server endpoint executes them.

## Reading a command result {#result-fields}

| Field                                                     | Interpretation                                                                                                |
| --------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------- |
| `id`, `commandId`, `requestId`, `waitCommandId`           | Distinct signal/command/request/wait correlation IDs. Do not substitute one for the server's idempotency key. |
| `contextName`, `aggregateName`, `tenantId`, `aggregateId` | Flat aggregate identity in CommandResult; WaitSignal instead nests the AggregateId object.                    |
| `stage`                                                   | The emitted wait stage, not a boolean covering every projection.                                              |
| `aggregateVersion?`                                       | Optional version reported by the service; absence does not establish a visibility barrier.                    |
| `signalTime`                                              | Numeric signal timestamp. It is not a client timeout duration.                                                |
| `function`                                                | Processor/function metadata; see [message metadata](./messages-and-state).                                    |
| `result`                                                  | Service-provided result map, not the command body C.                                                          |
| `errorCode`, `errorMsg`, `bindingErrors?`                 | Business outcome and optional field errors; see [error classification](./errors-and-utilities).               |

Use [identity and attribution](./identity-and-attribution) for nested/flat identity details. No result interface assigns defaults or validates JSON. A stream carries successive CommandResult payloads in `event.data`; its initial HTTP success is not completion of every event or stage.

## Complete example

```ts
import {
  CommandClient,
  CommandStage,
  ErrorCodes,
  commandHeaders,
  toWowError,
  waitStrategy,
} from '@ahoo-wang/wow-client';

const client = new CommandClient({ basePath: 'user' });

export async function rename(id: string, name: string, version: number) {
  try {
    const result = await client.send<{ name: string }>({
      path: '{id}/rename',
      method: 'POST',
      urlParams: { path: { id } },
      headers: {
        ...commandHeaders({
          aggregateVersion: version,
          requestId: crypto.randomUUID(),
        }),
        ...waitStrategy({ stage: CommandStage.SNAPSHOT, timeoutMs: 10_000 }),
      },
      body: { name },
    });
    return result;
  } catch (error) {
    const wowError = await toWowError(error);
    if (wowError?.errorCode === ErrorCodes.COMMAND_EXPECT_VERSION_CONFLICT) {
      return undefined; // reload the aggregate and let the user retry
    }
    throw wowError ?? error;
  }
}
```

A wait chain — answer once the saga has handled the transfer and the command it sent has been processed in the `account` context:

```ts
import { CommandStage, waitStrategy } from '@ahoo-wang/wow-client';

export const transferWait = waitStrategy({
  stage: CommandStage.SAGA_HANDLED,
  processor: 'TransferSaga',
  tail: { stage: CommandStage.PROCESSED, context: 'account' },
  timeoutMs: 30_000,
});
```

Service URLs in examples require application endpoints; type checking does not imply an external service was contacted.

## Public signatures and types

These signatures follow declarations reachable from the current root entry. `?` marks optional input; generics/interfaces only constrain compile-time types. Locate inherited and related types through the [symbol index](./symbols). Runtime defaults and failure behavior are described above.

### CommandClient {#api-CommandClient}

```ts
export class CommandClient implements ApiMetadataCapable {
    constructor(public readonly apiMetadata?: ApiMetadata);
    send<C extends object = object>(commandRequest: CommandRequest<C>, attributes?: Record<string, unknown>): Promise<CommandResult>;
    sendAndWaitStream<C extends object = object>(commandRequest: CommandRequest<C>, attributes?: Record<string, unknown>): Promise<CommandResultEventStream>;
}
```

[typescript/wow-client/src/command/commandClient.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/command/commandClient.ts)

### CommandHeaders {#api-CommandHeaders}

::: details Expand all fields and members

```ts
export const CommandHeaders = Object.freeze({
  COMMAND_HEADERS_PREFIX: 'Command-',
  TENANT_ID: 'Command-Tenant-Id',
  OWNER_ID: 'Command-Owner-Id',
  SPACE_ID: WowHeaders.SPACE_ID, // 'Wow-Space-Id'
  AGGREGATE_ID: 'Command-Aggregate-Id',
  AGGREGATE_VERSION: 'Command-Aggregate-Version',
  WAIT_PREFIX: 'Command-Wait-',
  WAIT_TIME_OUT: 'Command-Wait-Timeout',
  WAIT_STAGE: 'Command-Wait-Stage',
  WAIT_CONTEXT: 'Command-Wait-Context',
  WAIT_PROCESSOR: 'Command-Wait-Processor',
  WAIT_FUNCTION: 'Command-Wait-Function',
  WAIT_TAIL_PREFIX: 'Command-Wait-Tail-',
  WAIT_TAIL_STAGE: 'Command-Wait-Tail-Stage',
  WAIT_TAIL_CONTEXT: 'Command-Wait-Tail-Context',
  WAIT_TAIL_PROCESSOR: 'Command-Wait-Tail-Processor',
  WAIT_TAIL_FUNCTION: 'Command-Wait-Tail-Function',
  REQUEST_ID: 'Command-Request-Id',
  LOCAL_FIRST: 'Command-Local-First',
  COMMAND_AGGREGATE_CONTEXT: 'Command-Aggregate-Context',
  COMMAND_AGGREGATE_NAME: 'Command-Aggregate-Name',
  COMMAND_TYPE: 'Command-Type',
  COMMAND_HEADER_X_PREFIX: 'Command-Header-',
} as const);
```

:::

[typescript/wow-client/src/command/commandHeaders.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/command/commandHeaders.ts)

### WowHeaders {#api-WowHeaders}

::: details Expand all fields and members

```ts
export const WowHeaders = Object.freeze({
  WOW_HEADERS_PREFIX: 'Wow-',
  SPACE_ID: 'Wow-Space-Id',
  ERROR_CODE: 'Wow-Error-Code',
} as const);
```

:::

[typescript/wow-client/src/types/headers.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/headers.ts)

### CommandStageName {#api-CommandStageName}

```ts
export type CommandStageName = CommandStage | `${CommandStage}`;
```

[typescript/wow-client/src/command/commandRequest.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/command/commandRequest.ts)

### CommandRequestHeaders {#api-CommandRequestHeaders}

::: details Expand all fields and members

```ts
export interface CommandRequestHeaders extends RequestHeaders {
  [CommandHeaders.TENANT_ID]?: string;
  [CommandHeaders.OWNER_ID]?: string;
  [CommandHeaders.SPACE_ID]?: string;
  [CommandHeaders.AGGREGATE_ID]?: string;
  [CommandHeaders.AGGREGATE_VERSION]?: `${number}`;
  [CommandHeaders.WAIT_TIME_OUT]?: `${number}`;
  [CommandHeaders.WAIT_STAGE]?: CommandStageName;
  [CommandHeaders.WAIT_CONTEXT]?: string;
  [CommandHeaders.WAIT_PROCESSOR]?: string;
  [CommandHeaders.WAIT_FUNCTION]?: string;
  [CommandHeaders.WAIT_TAIL_STAGE]?: CommandStageName;
  [CommandHeaders.WAIT_TAIL_CONTEXT]?: string;
  [CommandHeaders.WAIT_TAIL_PROCESSOR]?: string;
  [CommandHeaders.WAIT_TAIL_FUNCTION]?: string;
  [CommandHeaders.REQUEST_ID]?: string;
  [CommandHeaders.LOCAL_FIRST]?: 'true' | 'false';
  [CommandHeaders.COMMAND_AGGREGATE_CONTEXT]?: string;
  [CommandHeaders.COMMAND_AGGREGATE_NAME]?: string;
  [CommandHeaders.COMMAND_TYPE]?: string;
}
```

:::

[typescript/wow-client/src/command/commandRequest.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/command/commandRequest.ts)

### CommandHeaderOptions {#api-CommandHeaderOptions}

```ts
export interface CommandHeaderOptions {
  tenantId?: string;
  ownerId?: string;
  spaceId?: string;
  aggregateId?: string;
  aggregateVersion?: number;
  requestId?: string;
  localFirst?: boolean;
}
```

[typescript/wow-client/src/command/commandRequest.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/command/commandRequest.ts)

### WaitFunction {#api-WaitFunction}

```ts
export interface WaitFunction {
  context?: string;
  processor?: string;
  function?: string;
}
```

[typescript/wow-client/src/command/commandRequest.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/command/commandRequest.ts)

### WaitStageOptions {#api-WaitStageOptions}

```ts
export interface WaitStageOptions extends WaitFunction {
  stage?: CommandStageName;
  timeoutMs?: number;
  tail?: never;
}
```

[typescript/wow-client/src/command/commandRequest.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/command/commandRequest.ts)

### WaitChainOptions {#api-WaitChainOptions}

```ts
export interface WaitChainOptions extends WaitFunction {
  stage: CommandStage.SAGA_HANDLED | 'SAGA_HANDLED';
  tail: WaitFunction & { stage: CommandStageName };
  timeoutMs?: number;
}
```

[typescript/wow-client/src/command/commandRequest.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/command/commandRequest.ts)

### WaitStrategyOptions {#api-WaitStrategyOptions}

```ts
export type WaitStrategyOptions = WaitStageOptions | WaitChainOptions;
```

[typescript/wow-client/src/command/commandRequest.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/command/commandRequest.ts)

### commandHeaders {#api-commandHeaders}

```ts
export function commandHeaders(
  options: CommandHeaderOptions,
): CommandRequestHeaders;
```

[typescript/wow-client/src/command/commandRequest.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/command/commandRequest.ts)

### waitStrategy {#api-waitStrategy}

```ts
export function waitStrategy(
  options: WaitStrategyOptions,
): CommandRequestHeaders;
```

[typescript/wow-client/src/command/commandRequest.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/command/commandRequest.ts)

### CommandUrlParams {#api-CommandUrlParams}

```ts
export interface CommandUrlParams extends Omit<UrlParams, 'path' | 'query'> {
  path?: UrlPathParams;
}
```

[typescript/wow-client/src/command/commandRequest.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/command/commandRequest.ts)

### CommandRequest {#api-CommandRequest}

```ts
export interface CommandRequest<
  C extends object = object,
> extends ParameterRequest<CommandBody<C>> {
  urlParams?: CommandUrlParams;
  headers?: CommandRequestHeaders;
  body?: CommandBody<C>;
}
```

[typescript/wow-client/src/command/commandRequest.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/command/commandRequest.ts)

### WaitSignal {#api-WaitSignal}

```ts
export interface WaitSignal
  extends
    Identifier,
    WaitCommandIdCapable,
    CommandId,
    AggregateIdCapable,
    NullableAggregateVersionCapable,
    ErrorInfo,
    SignalTimeCapable,
    CommandResultCapable,
    FunctionInfoCapable {}
```

[typescript/wow-client/src/command/commandResult.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/command/commandResult.ts)

### CommandResult {#api-CommandResult}

```ts
export interface CommandResult
  extends
    Identifier,
    WaitCommandIdCapable,
    CommandStageCapable,
    NamedBoundedContext,
    AggregateNameCapable,
    AggregateId,
    CommandId,
    RequestId,
    ErrorInfo,
    FunctionInfoCapable,
    CommandResultCapable,
    SignalTimeCapable,
    NullableAggregateVersionCapable {}
```

[typescript/wow-client/src/command/commandResult.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/command/commandResult.ts)

### CommandResultArray {#api-CommandResultArray}

```ts
export type CommandResultArray = CommandResult[];
```

[typescript/wow-client/src/command/commandResult.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/command/commandResult.ts)

### CommandResultEventStream {#api-CommandResultEventStream}

```ts
export type CommandResultEventStream = ReadableStream<
  JsonServerSentEvent<CommandResult>
>;
```

[typescript/wow-client/src/command/commandResult.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/command/commandResult.ts)

### CommandId {#api-CommandId}

```ts
export interface CommandId {
  commandId: string;
}
```

[typescript/wow-client/src/command/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/command/types.ts)

### WaitCommandIdCapable {#api-WaitCommandIdCapable}

```ts
export interface WaitCommandIdCapable {
  waitCommandId: string;
}
```

[typescript/wow-client/src/command/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/command/types.ts)

### RequestId {#api-RequestId}

```ts
export interface RequestId {
  requestId: string;
}
```

[typescript/wow-client/src/command/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/command/types.ts)

### CommandStage {#api-CommandStage}

```ts
export enum CommandStage {
  SENT = 'SENT',
  PROCESSED = 'PROCESSED',
  SNAPSHOT = 'SNAPSHOT',
  PROJECTED = 'PROJECTED',
  EVENT_HANDLED = 'EVENT_HANDLED',
  SAGA_HANDLED = 'SAGA_HANDLED',
}
```

[typescript/wow-client/src/command/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/command/types.ts)

### CommandStageCapable {#api-CommandStageCapable}

```ts
export interface CommandStageCapable {
  stage: CommandStage;
}
```

[typescript/wow-client/src/command/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/command/types.ts)

### CommandResultCapable {#api-CommandResultCapable}

```ts
export interface CommandResultCapable {
  result: Record<string, any>;
}
```

[typescript/wow-client/src/command/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/command/types.ts)

### SignalTimeCapable {#api-SignalTimeCapable}

```ts
export interface SignalTimeCapable {
  signalTime: number;
}
```

[typescript/wow-client/src/command/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/command/types.ts)

### NullableAggregateVersionCapable {#api-NullableAggregateVersionCapable}

```ts
export interface NullableAggregateVersionCapable {
  aggregateVersion?: number;
}
```

[typescript/wow-client/src/command/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/command/types.ts)

### CompensationTarget {#api-CompensationTarget}

```ts
export interface CompensationTarget
  extends PartialBy<Identifier, 'id'>, FunctionInfoCapable {}
```

[typescript/wow-client/src/command/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/command/types.ts)

### DeleteAggregate {#api-DeleteAggregate}

```ts
export interface DeleteAggregate {}
```

[typescript/wow-client/src/command/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/command/types.ts)

### CommandBody {#api-CommandBody}

```ts
export type CommandBody<C> = RemoveReadonlyFields<C>;
```

[typescript/wow-client/src/command/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/command/types.ts)

### DeleteAggregateCommand {#api-DeleteAggregateCommand}

```ts
export type DeleteAggregateCommand = CommandBody<DeleteAggregate>;
```

[typescript/wow-client/src/command/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/command/types.ts)

### RecoverAggregate {#api-RecoverAggregate}

```ts
export interface RecoverAggregate {}
```

[typescript/wow-client/src/command/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/command/types.ts)

### RecoverAggregateCommand {#api-RecoverAggregateCommand}

```ts
export type RecoverAggregateCommand = CommandBody<RecoverAggregate>;
```

[typescript/wow-client/src/command/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/command/types.ts)

### ApplyResourceTags {#api-ApplyResourceTags}

```ts
export interface ApplyResourceTags extends ApplyAbacTags {}
```

[typescript/wow-client/src/command/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/command/types.ts)

### ApplyResourceTagsCommand {#api-ApplyResourceTagsCommand}

```ts
export type ApplyResourceTagsCommand = CommandBody<ApplyResourceTags>;
```

[typescript/wow-client/src/command/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/command/types.ts)

### BatchResult {#api-BatchResult}

```ts
export interface BatchResult extends ErrorInfo {
  after: string;
  size: number;
}
```

[typescript/wow-client/src/command/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/command/types.ts)

## Related topics

[Client configuration and metadata](./configuration) · [Snapshot queries](./snapshot-queries) · [Filter expressions and legacy conditions](./filters) · [Projection, sorting and pagination](./query-options) · [Cursor queries](./cursor-queries) · [Aggregation builders](./aggregations) · [Events and historical state](./events-and-history) · [Identity and resource attribution](./identity-and-attribution)
