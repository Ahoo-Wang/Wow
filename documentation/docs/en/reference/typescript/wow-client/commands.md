---
title: 'Commands and wait results'
description: 'Commands and wait results — @ahoo-wang/wow-client'
---

# Commands and wait results

`CommandClient<C>` is a decorator-backed transport. `send(request, attributes?)` returns Promise&lt;CommandResult&gt;. `sendAndWaitStream` returns Promise&lt;ReadableStream&lt;JsonServerSentEvent&lt;CommandResult&gt;&gt;&gt; with Accept text/event-stream and JSON SSE extraction. Supply ApiMetadata and/or a CommandRequest with the endpoint required by your service; no universal command URL is inferred from C.

## Requests and wait stages

CommandRequest extends ParameterRequest. Its body is `CommandBody<C>` (the command's writable fields), with optional `path` (endpoint path override, not `url`), `method`, URL path parameters, headers and other request data. `CommandHeaders` is the exact HTTP header-name catalog. Header values are strings: tenant/owner/space/aggregate attribution, expected aggregate version, Request-Id, Local-First, command context/name/type, wait timeout, stage/context/processor/function, and the corresponding chain-tail selectors. The space header is the exception: `CommandHeaders.SPACE_ID` is `Wow-Space-Id` (`WowHeaders.SPACE_ID`), which the server shares with queries. `Command-Header-` is the extension prefix. The generic CommandRequestHeaders declaration lists required known keys; ordinary partial header sets can also be supplied through the underlying request/metadata options used by generated clients. The transport does not synthesize an idempotency key or choose a wait stage for you.

| CommandStage  | Signal represented  |
| ------------- | ------------------- |
| SENT          | Command sent        |
| PROCESSED     | Command processed   |
| SNAPSHOT      | Snapshot stage      |
| PROJECTED     | Projection stage    |
| EVENT_HANDLED | Event handler stage |
| SAGA_HANDLED  | Saga handler stage  |

These values are distinct server wait targets, not a client-side guarantee that every downstream consumer is caught up. The client sets no numeric wait timeout default. A CommandResult carries identity, aggregate attribution, stage, command/request/wait IDs, signal time, optional aggregateVersion, function info, result map and errorCode/errorMsg/bindingErrors. HTTP success alone does not imply command success: test `ErrorCodes.isSucceeded(result.errorCode)`. `WaitSignal` carries the corresponding signal model with nested aggregateId. BatchResult contains after/size and ErrorInfo; it does not implement batch iteration.

Transport or extraction failure rejects the Promise. Stream errors may instead occur during reader.read after the initial Promise resolves; the consumer must cancel/release the reader on early exit. DeleteAggregate/RecoverAggregate are empty command-body contracts; resource-tag commands carry tags. They do not delete/recover anything until a server endpoint executes them.

## Reading a command result {#result-fields}

| Field                                                     | Interpretation                                                                                                |
| --------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------- |
| `id`, `commandId`, `requestId`, `waitCommandId`           | Distinct signal/command/request/wait correlation IDs. Do not substitute one for the server's idempotency key. |
| `contextName`, `aggregateName`, `tenantId`, `aggregateId` | Flat aggregate identity in CommandResult; WaitSignal instead nests the AggregateId object.                    |
| `stage`                                                   | The emitted wait stage, not a boolean covering every projection.                                              |
| `aggregateVersion?`                                       | Optional version reported by the service; absence does not establish a visibility barrier.                    |
| `signalTime`                                              | Numeric signal timestamp. It is not a client timeout duration.                                                |
| `function`                                                | Processor/function metadata; see [message metadata](./messages-and-state).                                    |
| `result`                                                  | Service-provided result map, not the generic command body C.                                                  |
| `errorCode`, `errorMsg`, `bindingErrors?`                 | Business outcome and optional field errors; see [error classification](./errors-and-utilities).               |

Use [identity and attribution](./identity-and-attribution) for nested/flat identity details. No result interface assigns defaults or validates JSON. A stream carries successive CommandResult payloads in `event.data`; its initial HTTP success is not completion of every event or stage.

## Complete example

```ts
import { CommandClient, ErrorCodes } from '@ahoo-wang/wow-client';
const client = new CommandClient<{ name: string }>({ basePath: '/users' });
export async function rename() {
  const result = await client.send({
    path: '1/rename',
    method: 'POST',
    body: { name: 'Ada' },
  });
  if (!ErrorCodes.isSucceeded(result.errorCode)) {
    throw new Error(`${result.errorCode}: ${result.errorMsg}`);
  }
  return result;
}
```

Service URLs in examples require application endpoints; type checking does not imply an external service was contacted.

## Public signatures and types

These signatures follow declarations reachable from the current root entry. `?` marks optional input; generics/interfaces only constrain compile-time types. Locate inherited and related types through the [symbol index](./symbols). Runtime defaults and failure behavior are described above.

### CommandClient {#api-CommandClient}

```ts
export class CommandClient<C extends object = object> implements ApiMetadataCapable {
    constructor(public readonly apiMetadata?: ApiMetadata);
    send(commandRequest: CommandRequest<C>, attributes?: Record<string, any>): Promise<CommandResult>;
    sendAndWaitStream(commandRequest: CommandRequest<C>, attributes?: Record<string, any>): Promise<CommandResultEventStream>;
}
```

[typescript/wow-client/src/command/commandClient.ts:76](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/command/commandClient.ts#L76)

### CommandHeaders {#api-CommandHeaders}

::: details Expand all fields and members

```ts
export class CommandHeaders {
  static readonly COMMAND_HEADERS_PREFIX = 'Command-';
  static readonly TENANT_ID = `${CommandHeaders.COMMAND_HEADERS_PREFIX}Tenant-Id`;
  static readonly OWNER_ID = `${CommandHeaders.COMMAND_HEADERS_PREFIX}Owner-Id`;
  static readonly SPACE_ID = WowHeaders.SPACE_ID;
  static readonly AGGREGATE_ID = `${CommandHeaders.COMMAND_HEADERS_PREFIX}Aggregate-Id`;
  static readonly AGGREGATE_VERSION = `${CommandHeaders.COMMAND_HEADERS_PREFIX}Aggregate-Version`;
  static readonly WAIT_PREFIX = `${CommandHeaders.COMMAND_HEADERS_PREFIX}Wait-`;
  static readonly WAIT_TIME_OUT = `${CommandHeaders.WAIT_PREFIX}Timeout`;
  static readonly WAIT_STAGE = `${CommandHeaders.WAIT_PREFIX}Stage`;
  static readonly WAIT_CONTEXT = `${CommandHeaders.WAIT_PREFIX}Context`;
  static readonly WAIT_PROCESSOR = `${CommandHeaders.WAIT_PREFIX}Processor`;
  static readonly WAIT_FUNCTION = `${CommandHeaders.WAIT_PREFIX}Function`;
  static readonly WAIT_TAIL_PREFIX = `${CommandHeaders.WAIT_PREFIX}Tail-`;
  static readonly WAIT_TAIL_STAGE = `${CommandHeaders.WAIT_TAIL_PREFIX}Stage`;
  static readonly WAIT_TAIL_CONTEXT = `${CommandHeaders.WAIT_TAIL_PREFIX}Context`;
  static readonly WAIT_TAIL_PROCESSOR = `${CommandHeaders.WAIT_TAIL_PREFIX}Processor`;
  static readonly WAIT_TAIL_FUNCTION = `${CommandHeaders.WAIT_TAIL_PREFIX}Function`;
  static readonly REQUEST_ID = `${CommandHeaders.COMMAND_HEADERS_PREFIX}Request-Id`;
  static readonly LOCAL_FIRST = `${CommandHeaders.COMMAND_HEADERS_PREFIX}Local-First`;
  static readonly COMMAND_AGGREGATE_CONTEXT = `${CommandHeaders.COMMAND_HEADERS_PREFIX}Aggregate-Context`;
  static readonly COMMAND_AGGREGATE_NAME = `${CommandHeaders.COMMAND_HEADERS_PREFIX}Aggregate-Name`;
  static readonly COMMAND_TYPE = `${CommandHeaders.COMMAND_HEADERS_PREFIX}Type`;
  static readonly COMMAND_HEADER_X_PREFIX = `${CommandHeaders.COMMAND_HEADERS_PREFIX}Header-`;
}
```

:::

[typescript/wow-client/src/command/commandHeaders.ts:38](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/command/commandHeaders.ts#L38)

### WowHeaders {#api-WowHeaders}

::: details Expand all fields and members

```ts
export class WowHeaders {
  static readonly WOW_HEADERS_PREFIX = 'Wow-';
  static readonly SPACE_ID = `${WowHeaders.WOW_HEADERS_PREFIX}Space-Id`;
  static readonly ERROR_CODE = `${WowHeaders.WOW_HEADERS_PREFIX}Error-Code`;
}
```

:::

[typescript/wow-client/src/types/headers.ts:32](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/headers.ts#L32)

### CommandRequestHeaders {#api-CommandRequestHeaders}

::: details Expand all fields and members

```ts
export interface CommandRequestHeaders extends RequestHeaders {
  [CommandHeaders.TENANT_ID]: string;
  [CommandHeaders.OWNER_ID]: string;
  [CommandHeaders.SPACE_ID]: string;
  [CommandHeaders.AGGREGATE_ID]: string;
  [CommandHeaders.AGGREGATE_VERSION]: string;
  [CommandHeaders.WAIT_TIME_OUT]: string;
  [CommandHeaders.WAIT_STAGE]: string;
  [CommandHeaders.WAIT_CONTEXT]: string;
  [CommandHeaders.WAIT_PROCESSOR]: string;
  [CommandHeaders.WAIT_FUNCTION]: string;
  [CommandHeaders.WAIT_TAIL_STAGE]: string;
  [CommandHeaders.WAIT_TAIL_CONTEXT]: string;
  [CommandHeaders.WAIT_TAIL_PROCESSOR]: string;
  [CommandHeaders.WAIT_TAIL_FUNCTION]: string;
  [CommandHeaders.REQUEST_ID]: string;
  [CommandHeaders.LOCAL_FIRST]: string;
  [CommandHeaders.COMMAND_AGGREGATE_CONTEXT]: string;
  [CommandHeaders.COMMAND_AGGREGATE_NAME]: string;
  [CommandHeaders.COMMAND_TYPE]: string;
}
```

:::

[typescript/wow-client/src/command/commandRequest.ts:36](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/command/commandRequest.ts#L36)

### CommandUrlParams {#api-CommandUrlParams}

```ts
export interface CommandUrlParams extends Omit<UrlParams, 'path' | 'query'> {
  path?: UrlPathParams;
}
```

[typescript/wow-client/src/command/commandRequest.ts:152](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/command/commandRequest.ts#L152)

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

[typescript/wow-client/src/command/commandRequest.ts:162](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/command/commandRequest.ts#L162)

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

[typescript/wow-client/src/command/commandResult.ts:52](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/command/commandResult.ts#L52)

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
    ErrorInfo,
    CommandId,
    RequestId,
    ErrorInfo,
    FunctionInfoCapable,
    CommandResultCapable,
    SignalTimeCapable,
    NullableAggregateVersionCapable {}
```

[typescript/wow-client/src/command/commandResult.ts:74](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/command/commandResult.ts#L74)

### CommandResultArray {#api-CommandResultArray}

```ts
export type CommandResultArray = CommandResult[];
```

[typescript/wow-client/src/command/commandResult.ts:91](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/command/commandResult.ts#L91)

### CommandResultEventStream {#api-CommandResultEventStream}

```ts
export type CommandResultEventStream = ReadableStream<
  JsonServerSentEvent<CommandResult>
>;
```

[typescript/wow-client/src/command/commandResult.ts:108](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/command/commandResult.ts#L108)

### CommandId {#api-CommandId}

```ts
export interface CommandId {
  commandId: string;
}
```

[typescript/wow-client/src/command/types.ts:27](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/command/types.ts#L27)

### WaitCommandIdCapable {#api-WaitCommandIdCapable}

```ts
export interface WaitCommandIdCapable {
  waitCommandId: string;
}
```

[typescript/wow-client/src/command/types.ts:36](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/command/types.ts#L36)

### RequestId {#api-RequestId}

```ts
export interface RequestId {
  requestId: string;
}
```

[typescript/wow-client/src/command/types.ts:45](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/command/types.ts#L45)

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

[typescript/wow-client/src/command/types.ts:54](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/command/types.ts#L54)

### CommandStageCapable {#api-CommandStageCapable}

```ts
export interface CommandStageCapable {
  stage: CommandStage;
}
```

[typescript/wow-client/src/command/types.ts:91](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/command/types.ts#L91)

### CommandResultCapable {#api-CommandResultCapable}

```ts
export interface CommandResultCapable {
  result: Record<string, any>;
}
```

[typescript/wow-client/src/command/types.ts:100](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/command/types.ts#L100)

### SignalTimeCapable {#api-SignalTimeCapable}

```ts
export interface SignalTimeCapable {
  signalTime: number;
}
```

[typescript/wow-client/src/command/types.ts:109](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/command/types.ts#L109)

### NullableAggregateVersionCapable {#api-NullableAggregateVersionCapable}

```ts
export interface NullableAggregateVersionCapable {
  aggregateVersion?: number;
}
```

[typescript/wow-client/src/command/types.ts:118](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/command/types.ts#L118)

### CompensationTarget {#api-CompensationTarget}

```ts
export interface CompensationTarget
  extends PartialBy<Identifier, 'id'>, FunctionInfoCapable {}
```

[typescript/wow-client/src/command/types.ts:135](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/command/types.ts#L135)

### DeleteAggregate {#api-DeleteAggregate}

```ts
export interface DeleteAggregate {}
```

[typescript/wow-client/src/command/types.ts:146](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/command/types.ts#L146)

### CommandBody {#api-CommandBody}

```ts
export type CommandBody<C> = RemoveReadonlyFields<C>;
```

[typescript/wow-client/src/command/types.ts:148](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/command/types.ts#L148)

### DeleteAggregateCommand {#api-DeleteAggregateCommand}

```ts
export type DeleteAggregateCommand = CommandBody<DeleteAggregate>;
```

[typescript/wow-client/src/command/types.ts:150](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/command/types.ts#L150)

### RecoverAggregate {#api-RecoverAggregate}

```ts
export interface RecoverAggregate {}
```

[typescript/wow-client/src/command/types.ts:160](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/command/types.ts#L160)

### RecoverAggregateCommand {#api-RecoverAggregateCommand}

```ts
export type RecoverAggregateCommand = CommandBody<RecoverAggregate>;
```

[typescript/wow-client/src/command/types.ts:162](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/command/types.ts#L162)

### ApplyResourceTags {#api-ApplyResourceTags}

```ts
export interface ApplyResourceTags extends ApplyAbacTags {}
```

[typescript/wow-client/src/command/types.ts:165](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/command/types.ts#L165)

### ApplyResourceTagsCommand {#api-ApplyResourceTagsCommand}

```ts
export type ApplyResourceTagsCommand = CommandBody<ApplyResourceTags>;
```

[typescript/wow-client/src/command/types.ts:167](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/command/types.ts#L167)

### BatchResult {#api-BatchResult}

```ts
export interface BatchResult extends ErrorInfo {
  after: string;
  size: number;
}
```

[typescript/wow-client/src/command/types.ts:175](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/command/types.ts#L175)

## Related topics

[Client configuration and metadata](./configuration) · [Snapshot queries](./snapshot-queries) · [Filter expressions and legacy conditions](./filters) · [Projection, sorting and pagination](./query-options) · [Cursor queries](./cursor-queries) · [Aggregation builders](./aggregations) · [Events and historical state](./events-and-history) · [Identity and resource attribution](./identity-and-attribution)
