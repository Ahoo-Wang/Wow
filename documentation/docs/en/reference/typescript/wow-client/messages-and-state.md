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

[typescript/wow-client/src/model/function.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/model/function.ts)

### FunctionInfo {#api-FunctionInfo}

```ts
export interface FunctionInfo extends NamedBoundedContext, Named {
  functionKind: FunctionKind;
  processorName: string;
}
```

[typescript/wow-client/src/model/function.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/model/function.ts)

### FunctionInfoCapable {#api-FunctionInfoCapable}

```ts
export interface FunctionInfoCapable {
  function: FunctionInfo;
}
```

[typescript/wow-client/src/model/function.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/model/function.ts)

### BodyCapable {#api-BodyCapable}

```ts
export interface BodyCapable<T> {
  body: T;
}
```

[typescript/wow-client/src/model/messaging.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/model/messaging.ts)

### CreateTimeCapable {#api-CreateTimeCapable}

```ts
export interface CreateTimeCapable {
  createTime: number;
}
```

[typescript/wow-client/src/model/modeling.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/model/modeling.ts)

### DeletedCapable {#api-DeletedCapable}

```ts
export interface DeletedCapable {
  deleted: boolean;
}
```

[typescript/wow-client/src/model/modeling.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/model/modeling.ts)

### EventIdCapable {#api-EventIdCapable}

```ts
export interface EventIdCapable {
  eventId: string;
}
```

[typescript/wow-client/src/model/modeling.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/model/modeling.ts)

### EventTimeCapable {#api-EventTimeCapable}

```ts
export interface EventTimeCapable {
  eventTime: number;
}
```

[typescript/wow-client/src/model/modeling.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/model/modeling.ts)

### FirstEventTimeCapable {#api-FirstEventTimeCapable}

```ts
export interface FirstEventTimeCapable {
  firstEventTime: number;
}
```

[typescript/wow-client/src/model/modeling.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/model/modeling.ts)

### FirstOperatorCapable {#api-FirstOperatorCapable}

```ts
export interface FirstOperatorCapable {
  firstOperator: string;
}
```

[typescript/wow-client/src/model/modeling.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/model/modeling.ts)

### OperatorCapable {#api-OperatorCapable}

```ts
export interface OperatorCapable {
  operator: string;
}
```

[typescript/wow-client/src/model/modeling.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/model/modeling.ts)

### SnapshotTimeCapable {#api-SnapshotTimeCapable}

```ts
export interface SnapshotTimeCapable {
  snapshotTime: number;
}
```

[typescript/wow-client/src/model/modeling.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/model/modeling.ts)

### StateCapable {#api-StateCapable}

```ts
export interface StateCapable<S> {
  state: S;
}
```

[typescript/wow-client/src/model/modeling.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/model/modeling.ts)

### MessageHeaderSqlType {#api-MessageHeaderSqlType}

```ts
export enum MessageHeaderSqlType {
  MAP = 'MAP',
  STRING = 'STRING',
}
```

[typescript/wow-client/src/model/bi.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/model/bi.ts)

[Complete symbol index](./symbols)
