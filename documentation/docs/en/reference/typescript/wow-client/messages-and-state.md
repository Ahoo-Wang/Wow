---
title: 'Message payloads and state metadata'
description: 'Message payloads and state metadata — @ahoo-wang/wow-client'
---

# Message payloads and state metadata

These small interfaces compose command, event and snapshot envelopes. They carry metadata supplied by the service; importing or assigning a type never loads history, executes a processor or creates timestamps.

| Field family                                                          | Meaning and constraint                                                                                                                                        |
| --------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `BodyCapable<T>.body` / `StateCapable<S>.state`                       | Required payload of the selected generic type; no JSON validation, cloning or initialization. A message body is not automatically a complete aggregate state. |
| `CreateTimeCapable.createTime`                                        | Creation time, milliseconds since Unix epoch.                                                                                                                 |
| `EventTimeCapable.eventTime` / `FirstEventTimeCapable.firstEventTime` | Last / first event time, milliseconds since Unix epoch.                                                                                                       |
| `SnapshotTimeCapable.snapshotTime`                                    | Snapshot timestamp, milliseconds since Unix epoch; does not prove the snapshot contains a particular command.                                                 |
| `EventIdCapable.eventId`                                              | String event identifier; distinct from an aggregate identifier.                                                                                               |
| `OperatorCapable.operator` / `FirstOperatorCapable.firstOperator`     | Last / first operator identity string.                                                                                                                        |
| `DeletedCapable.deleted`                                              | Required boolean deletion marker. This type does not filter deleted records by itself.                                                                        |
| `FunctionInfo`                                                        | Required contextName, name, functionKind, processorName. `FunctionInfoCapable.function` nests it.                                                             |
| `FunctionKind`                                                        | COMMAND, ERROR, EVENT, SOURCING or STATE_EVENT classification; not a JavaScript callable function.                                                            |
| `MessageHeaderSqlType`                                                | MAP or STRING storage representation label; no SQL serialization is performed by the enum.                                                                    |

All metadata interfaces have no runtime defaults. For full envelopes and their required inherited fields continue to [events/history](./events-and-history) or [snapshot queries](./snapshot-queries).

## Exact contracts

### FunctionKind {#api-FunctionKind}

```ts
export enum FunctionKind {
  COMMAND = 'COMMAND',
  ERROR = 'ERROR',
  EVENT = 'EVENT',
  SOURCING = 'SOURCING',
  STATE_EVENT = 'STATE_EVENT',
}
```

[typescript/wow-client/src/types/function.ts:21](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/function.ts#L21)

### FunctionInfo {#api-FunctionInfo}

```ts
export interface FunctionInfo extends NamedBoundedContext, Named {
  functionKind: FunctionKind;
  processorName: string;
}
```

[typescript/wow-client/src/types/function.ts:51](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/function.ts#L51)

### FunctionInfoCapable {#api-FunctionInfoCapable}

```ts
export interface FunctionInfoCapable {
  function: FunctionInfo;
}
```

[typescript/wow-client/src/types/function.ts:59](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/function.ts#L59)

### BodyCapable {#api-BodyCapable}

```ts
export interface BodyCapable<T> {
  body: T;
}
```

[typescript/wow-client/src/types/messaging.ts:14](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/messaging.ts#L14)

### CreateTimeCapable {#api-CreateTimeCapable}

```ts
export interface CreateTimeCapable {
  createTime: number;
}
```

[typescript/wow-client/src/types/modeling.ts:19](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/modeling.ts#L19)

### DeletedCapable {#api-DeletedCapable}

```ts
export interface DeletedCapable {
  deleted: boolean;
}
```

[typescript/wow-client/src/types/modeling.ts:29](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/modeling.ts#L29)

### EventIdCapable {#api-EventIdCapable}

```ts
export interface EventIdCapable {
  eventId: string;
}
```

[typescript/wow-client/src/types/modeling.ts:39](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/modeling.ts#L39)

### EventTimeCapable {#api-EventTimeCapable}

```ts
export interface EventTimeCapable {
  eventTime: number;
}
```

[typescript/wow-client/src/types/modeling.ts:49](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/modeling.ts#L49)

### FirstEventTimeCapable {#api-FirstEventTimeCapable}

```ts
export interface FirstEventTimeCapable {
  firstEventTime: number;
}
```

[typescript/wow-client/src/types/modeling.ts:59](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/modeling.ts#L59)

### FirstOperatorCapable {#api-FirstOperatorCapable}

```ts
export interface FirstOperatorCapable {
  firstOperator: string;
}
```

[typescript/wow-client/src/types/modeling.ts:69](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/modeling.ts#L69)

### OperatorCapable {#api-OperatorCapable}

```ts
export interface OperatorCapable {
  operator: string;
}
```

[typescript/wow-client/src/types/modeling.ts:122](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/modeling.ts#L122)

### SnapshotTimeCapable {#api-SnapshotTimeCapable}

```ts
export interface SnapshotTimeCapable {
  snapshotTime: number;
}
```

[typescript/wow-client/src/types/modeling.ts:146](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/modeling.ts#L146)

### StateCapable {#api-StateCapable}

```ts
export interface StateCapable<S> {
  state: S;
}
```

[typescript/wow-client/src/types/modeling.ts:163](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/modeling.ts#L163)

### MessageHeaderSqlType {#api-MessageHeaderSqlType}

```ts
export enum MessageHeaderSqlType {
  MAP = 'MAP',
  STRING = 'STRING',
}
```

[typescript/wow-client/src/types/bi.ts:14](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/bi.ts#L14)

[Complete symbol index](./symbols)
