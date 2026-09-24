---
title: '消息载荷与状态元数据'
description: '消息载荷与状态元数据 — @ahoo-wang/wow-client'
---

# 消息载荷与状态元数据

这些小接口组合成命令、事件和快照信封。元数据由服务端提供；导入或赋值一个类型不会加载历史、执行处理器或生成时间戳。

| 字段组                                                                | 含义与约束                                                                                   |
| --------------------------------------------------------------------- | -------------------------------------------------------------------------------------------- |
| `BodyCapable<T>.body` / `StateCapable<S>.state`                       | 所选泛型类型的必填载荷；不验证 JSON、不克隆或初始化。消息体不自动等于完整聚合状态。          |
| `CreateTimeCapable.createTime`                                        | 创建时间，Unix 纪元起的毫秒数。                                                              |
| `EventTimeCapable.eventTime` / `FirstEventTimeCapable.firstEventTime` | 最后 / 首次事件时间，Unix 纪元起的毫秒数。                                                   |
| `SnapshotTimeCapable.snapshotTime`                                    | 快照时间，Unix 纪元起的毫秒数；不证明快照已经包含某个命令。                                  |
| `EventIdCapable.eventId`                                              | 字符串事件标识，与聚合标识不同。                                                             |
| `OperatorCapable.operator` / `FirstOperatorCapable.firstOperator`     | 最后 / 首次操作者的身份字符串。                                                              |
| `DeletedCapable.deleted`                                              | 必填布尔删除标记；该类型本身不会过滤已删除数据。                                             |
| `FunctionInfo`                                                        | 必填 contextName、name、functionKind、processorName；`FunctionInfoCapable.function` 嵌套它。 |
| `FunctionKind`                                                        | COMMAND、ERROR、EVENT、SOURCING、STATE_EVENT 分类；不是可调用的 JavaScript 函数。            |
| `MessageHeaderSqlType`                                                | MAP 或 STRING 存储表示标签；枚举不执行 SQL 序列化。                                          |

所有元数据接口均无运行时默认值。完整信封及其必填继承字段参见[事件与历史](./events-and-history)或[快照查询](./snapshot-queries)。

## 精确契约

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

[typescript/wow-client/src/types/function.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/function.ts)

### FunctionInfo {#api-FunctionInfo}

```ts
export interface FunctionInfo extends NamedBoundedContext, Named {
  functionKind: FunctionKind;
  processorName: string;
}
```

[typescript/wow-client/src/types/function.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/function.ts)

### FunctionInfoCapable {#api-FunctionInfoCapable}

```ts
export interface FunctionInfoCapable {
  function: FunctionInfo;
}
```

[typescript/wow-client/src/types/function.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/function.ts)

### BodyCapable {#api-BodyCapable}

```ts
export interface BodyCapable<T> {
  body: T;
}
```

[typescript/wow-client/src/types/messaging.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/messaging.ts)

### CreateTimeCapable {#api-CreateTimeCapable}

```ts
export interface CreateTimeCapable {
  createTime: number;
}
```

[typescript/wow-client/src/types/modeling.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/modeling.ts)

### DeletedCapable {#api-DeletedCapable}

```ts
export interface DeletedCapable {
  deleted: boolean;
}
```

[typescript/wow-client/src/types/modeling.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/modeling.ts)

### EventIdCapable {#api-EventIdCapable}

```ts
export interface EventIdCapable {
  eventId: string;
}
```

[typescript/wow-client/src/types/modeling.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/modeling.ts)

### EventTimeCapable {#api-EventTimeCapable}

```ts
export interface EventTimeCapable {
  eventTime: number;
}
```

[typescript/wow-client/src/types/modeling.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/modeling.ts)

### FirstEventTimeCapable {#api-FirstEventTimeCapable}

```ts
export interface FirstEventTimeCapable {
  firstEventTime: number;
}
```

[typescript/wow-client/src/types/modeling.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/modeling.ts)

### FirstOperatorCapable {#api-FirstOperatorCapable}

```ts
export interface FirstOperatorCapable {
  firstOperator: string;
}
```

[typescript/wow-client/src/types/modeling.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/modeling.ts)

### OperatorCapable {#api-OperatorCapable}

```ts
export interface OperatorCapable {
  operator: string;
}
```

[typescript/wow-client/src/types/modeling.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/modeling.ts)

### SnapshotTimeCapable {#api-SnapshotTimeCapable}

```ts
export interface SnapshotTimeCapable {
  snapshotTime: number;
}
```

[typescript/wow-client/src/types/modeling.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/modeling.ts)

### StateCapable {#api-StateCapable}

```ts
export interface StateCapable<S> {
  state: S;
}
```

[typescript/wow-client/src/types/modeling.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/modeling.ts)

### MessageHeaderSqlType {#api-MessageHeaderSqlType}

```ts
export enum MessageHeaderSqlType {
  MAP = 'MAP',
  STRING = 'STRING',
}
```

[typescript/wow-client/src/types/bi.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/bi.ts)

[完整符号索引](./symbols)
