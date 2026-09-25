---
title: '命令与等待结果'
description: '命令与等待结果 — @ahoo-wang/wow-client'
---

# 命令与等待结果

`CommandClient` 是由装饰器实现的传输客户端。它不再是泛型类：命令体类型按每次调用选择，一个客户端即可发送聚合的全部命令。`send<C>(request, attributes?)` 返回所等待阶段的 Promise&lt;CommandResult&gt;；`sendAndWaitStream<C>(request, attributes?)` 返回 Promise&lt;ReadableStream&lt;JsonServerSentEvent&lt;CommandResult&gt;&gt;&gt;——命令每到达一个阶段推送一个结果——并使用 Accept text/event-stream。必须在 ApiMetadata 和/或 CommandRequest 中提供服务所需端点，不会根据 `C` 推导通用命令 URL。

## 请求与等待阶段

CommandRequest 扩展 ParameterRequest；body 为命令可写字段 `CommandBody<C>`，还可带 `path`（端点路径覆盖，不是 `url`）、`method`、路径参数、头和其他请求数据。传输层不会替你生成幂等键或选择等待阶段。

`CommandHeaders` 与 `WowHeaders` 是冻结的 `as const` 头名对象，每个值都是字符串字面量类型。`CommandHeaders.SPACE_ID` 为 `Wow-Space-Id`（即 `WowHeaders.SPACE_ID`），服务端的命令与查询共用它；`Command-Header-` 是服务端复制进命令头的自定义头前缀。`CommandRequestHeaders` 按服务端的解析方式为每个已知命令头定型，且全部可选：

| 头                                                           | 值类型                                           |
| ------------------------------------------------------------ | ------------------------------------------------ |
| `Command-Wait-Stage`、`Command-Wait-Tail-Stage`               | `CommandStageName`——`CommandStage` 或其名称      |
| `Command-Aggregate-Version`、`Command-Wait-Timeout`          | `` `${number}` ``，字符串形式的整数               |
| `Command-Local-First`                                        | `'true'` 或 `'false'`                            |
| 租户、所有者、空间、聚合 ID、请求 ID、等待 context/processor/function、链尾选择器、`/wow/command/send` 的 context/name/type | `string` |

其他头（`Authorization`、`Command-Header-*` 扩展）仍可作为字符串传入。通用的 `/wow/command/send` 路由没有专门方法：用 `CommandClient` 发送，并带上 `COMMAND_AGGREGATE_CONTEXT`、`COMMAND_AGGREGATE_NAME`、`COMMAND_TYPE` 头。建议用两个构造函数代替手写；二者都省略未提供选项对应的头，输入非法时抛出 `TypeError`：

- `commandHeaders({ tenantId?, ownerId?, spaceId?, aggregateId?, aggregateVersion?, requestId?, localFirst? })` 说明命令的归属。`aggregateVersion` 必须是非负整数；`localFirst` 转为 `'true'`/`'false'`。
- `waitStrategy({ stage?, context?, processor?, function?, timeoutMs?, tail? })` 说明服务端应答前等待什么，对应 Kotlin 的 `WaitingFor`。stage 必须是 `CommandStage`；`timeoutMs` 必须是正整数。不指定 stage 时，服务端在命令自身限界上下文中等待 `PROCESSED`。
- 带 `tail` 即为等待链：先等待 Saga 处理完命令产生的事件（`stage: 'SAGA_HANDLED'`，可用 `processor` 收窄），再等待该 Saga 发出的命令到达 `tail.stage`，可用 `tail.context`/`tail.processor`/`tail.function` 限定。其他 stage 搭配 `tail` 是类型错误，运行时也会抛出，因为服务端会忽略它。

| CommandStage  | 表示的信号     |
| ------------- | -------------- |
| SENT          | 命令已发送     |
| PROCESSED     | 命令处理阶段   |
| SNAPSHOT      | 快照阶段       |
| PROJECTED     | 投影阶段       |
| EVENT_HANDLED | 事件处理器阶段 |
| SAGA_HANDLED  | Saga 处理阶段  |

这些是不同服务端等待目标，并不保证所有下游消费者都已追上。客户端未设置等待超时默认值。CommandResult 包含身份、聚合归属、stage、command/request/wait ID、signalTime、可选 aggregateVersion、function、result 映射和 errorCode/errorMsg/bindingErrors。`WaitSignal` 对应信号模型，其 aggregateId 为嵌套对象。BatchResult 含 after/size 与 ErrorInfo，本身不实现批次遍历。

## 失败

- 服务端拒绝的请求（验证失败、版本冲突、请求 ID 重复等）会拒绝 Promise；`await toWowError(error)` 把服务端的 `ErrorInfo` 读成 `WowError`，参见[错误](./errors-and-utilities)。
- 处理失败的命令也以同样方式被拒绝：服务端把失败的 `CommandResult`（它是 `ErrorInfo`）按其 `errorCode` 映射的 HTTP 状态应答（例如 400 `IllegalArgument`），所以 `send` 只会以 `errorCode` 为 `'Ok'` 的结果完成。
- 服务端中途失败时（例如等待超时），`sendAndWaitStream` 以 `WowError` 使流出错，`for await` 会抛出。自身 `errorCode` 不是 `Ok` 的结果仍作为结果传出。提前退出必须取消并释放 reader。

DeleteAggregate/RecoverAggregate 是空命令体契约，资源标签命令带 tags；它们在服务端执行端点前不产生删除/恢复效果。

## 读取命令结果 {#result-fields}

| 字段                                                      | 解释                                                                 |
| --------------------------------------------------------- | -------------------------------------------------------------------- |
| `id`、`commandId`、`requestId`、`waitCommandId`           | 不同的信号/命令/请求/等待关联 ID，不应互相替代服务端幂等键。         |
| `contextName`、`aggregateName`、`tenantId`、`aggregateId` | CommandResult 中是平铺聚合身份；WaitSignal 则嵌套 AggregateId 对象。 |
| `stage`                                                   | 当前信号的等待阶段，不是覆盖所有投影的布尔成功标记。                 |
| `aggregateVersion?`                                       | 服务端报告的可选版本；缺失时不能建立可见性屏障。                     |
| `signalTime`                                              | 数字信号时间戳，不是客户端超时时长。                                 |
| `function`                                                | 处理器/函数元数据，参见[消息元数据](./messages-and-state)。          |
| `result`                                                  | 服务端提供的结果映射，不是命令体 C。                                 |
| `errorCode`、`errorMsg`、`bindingErrors?`                 | 业务结果与可选字段错误，参见[错误分类](./errors-and-utilities)。     |

嵌套/平铺身份细节参见[身份与归属](./identity-and-attribution)。结果接口不赋默认值、不验证 JSON。流通过 `event.data` 携带连续 CommandResult 载荷；初始 HTTP 成功不表示所有事件或阶段均已完成。

## 完整示例

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
      return undefined; // 重新加载聚合，让用户重试
    }
    throw wowError ?? error;
  }
}
```

等待链——Saga 处理完转账、且它发出的命令已在 `account` 上下文处理后再应答：

```ts
import { CommandStage, waitStrategy } from '@ahoo-wang/wow-client';

export const transferWait = waitStrategy({
  stage: CommandStage.SAGA_HANDLED,
  processor: 'TransferSaga',
  tail: { stage: CommandStage.PROCESSED, context: 'account' },
  timeoutMs: 30_000,
});
```

示例中的服务 URL 需要应用实现；类型检查不代表已经访问外部服务。

## 公开签名与类型

以下签名按当前根入口可达声明核对。`?` 表示可省略；泛型/接口只约束编译期，继承项与关联类型可从 [符号索引](./symbols) 定位。运行时默认值和失败行为以本页上文为准。

### CommandClient {#api-CommandClient}

```ts
export class CommandClient implements ApiMetadataCapable {
    constructor(public readonly apiMetadata?: ApiMetadata);
    send<C extends object = object>(commandRequest: CommandRequest<C>, attributes?: Record<string, unknown>): Promise<CommandResult>;
    sendAndWaitStream<C extends object = object>(commandRequest: CommandRequest<C>, attributes?: Record<string, unknown>): Promise<CommandResultEventStream>;
}
```

[typescript/wow-client/src/client/command/commandClient.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/client/command/commandClient.ts)

### CommandHeaders {#api-CommandHeaders}

::: details 展开完整字段与成员

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

[typescript/wow-client/src/client/command/commandHeaders.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/client/command/commandHeaders.ts)

### WowHeaders {#api-WowHeaders}

::: details 展开完整字段与成员

```ts
export const WowHeaders = Object.freeze({
  WOW_HEADERS_PREFIX: 'Wow-',
  SPACE_ID: 'Wow-Space-Id',
  ERROR_CODE: 'Wow-Error-Code',
} as const);
```

:::

[typescript/wow-client/src/error/headers.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/error/headers.ts)

### CommandStageName {#api-CommandStageName}

```ts
export type CommandStageName = CommandStage | `${CommandStage}`;
```

[typescript/wow-client/src/client/command/commandRequest.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/client/command/commandRequest.ts)

### CommandRequestHeaders {#api-CommandRequestHeaders}

::: details 展开完整字段与成员

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

[typescript/wow-client/src/client/command/commandRequest.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/client/command/commandRequest.ts)

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

[typescript/wow-client/src/client/command/commandRequest.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/client/command/commandRequest.ts)

### WaitFunction {#api-WaitFunction}

```ts
export interface WaitFunction {
  context?: string;
  processor?: string;
  function?: string;
}
```

[typescript/wow-client/src/client/command/commandRequest.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/client/command/commandRequest.ts)

### WaitStageOptions {#api-WaitStageOptions}

```ts
export interface WaitStageOptions extends WaitFunction {
  stage?: CommandStageName;
  timeoutMs?: number;
  tail?: never;
}
```

[typescript/wow-client/src/client/command/commandRequest.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/client/command/commandRequest.ts)

### WaitChainOptions {#api-WaitChainOptions}

```ts
export interface WaitChainOptions extends WaitFunction {
  stage: CommandStage.SAGA_HANDLED | 'SAGA_HANDLED';
  tail: WaitFunction & { stage: CommandStageName };
  timeoutMs?: number;
}
```

[typescript/wow-client/src/client/command/commandRequest.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/client/command/commandRequest.ts)

### WaitStrategyOptions {#api-WaitStrategyOptions}

```ts
export type WaitStrategyOptions = WaitStageOptions | WaitChainOptions;
```

[typescript/wow-client/src/client/command/commandRequest.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/client/command/commandRequest.ts)

### commandHeaders {#api-commandHeaders}

```ts
export function commandHeaders(
  options: CommandHeaderOptions,
): CommandRequestHeaders;
```

[typescript/wow-client/src/client/command/commandRequest.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/client/command/commandRequest.ts)

### waitStrategy {#api-waitStrategy}

```ts
export function waitStrategy(
  options: WaitStrategyOptions,
): CommandRequestHeaders;
```

[typescript/wow-client/src/client/command/commandRequest.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/client/command/commandRequest.ts)

### CommandUrlParams {#api-CommandUrlParams}

```ts
export interface CommandUrlParams extends Omit<UrlParams, 'path' | 'query'> {
  path?: UrlPathParams;
}
```

[typescript/wow-client/src/client/command/commandRequest.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/client/command/commandRequest.ts)

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

[typescript/wow-client/src/client/command/commandRequest.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/client/command/commandRequest.ts)

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

[typescript/wow-client/src/model/command.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/model/command.ts)

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

[typescript/wow-client/src/model/command.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/model/command.ts)

### CommandResultArray {#api-CommandResultArray}

```ts
export type CommandResultArray = CommandResult[];
```

[typescript/wow-client/src/model/command.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/model/command.ts)

### CommandResultEventStream {#api-CommandResultEventStream}

```ts
export type CommandResultEventStream = ReadableStream<
  JsonServerSentEvent<CommandResult>
>;
```

[typescript/wow-client/src/client/command/commandResult.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/client/command/commandResult.ts)

### CommandId {#api-CommandId}

```ts
export interface CommandId {
  commandId: string;
}
```

[typescript/wow-client/src/model/command.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/model/command.ts)

### WaitCommandIdCapable {#api-WaitCommandIdCapable}

```ts
export interface WaitCommandIdCapable {
  waitCommandId: string;
}
```

[typescript/wow-client/src/model/command.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/model/command.ts)

### RequestId {#api-RequestId}

```ts
export interface RequestId {
  requestId: string;
}
```

[typescript/wow-client/src/model/command.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/model/command.ts)

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

[typescript/wow-client/src/model/command.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/model/command.ts)

### CommandStageCapable {#api-CommandStageCapable}

```ts
export interface CommandStageCapable {
  stage: CommandStage;
}
```

[typescript/wow-client/src/model/command.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/model/command.ts)

### CommandResultCapable {#api-CommandResultCapable}

```ts
export interface CommandResultCapable {
  result: Record<string, unknown>;
}
```

[typescript/wow-client/src/model/command.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/model/command.ts)

### SignalTimeCapable {#api-SignalTimeCapable}

```ts
export interface SignalTimeCapable {
  signalTime: number;
}
```

[typescript/wow-client/src/model/command.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/model/command.ts)

### NullableAggregateVersionCapable {#api-NullableAggregateVersionCapable}

```ts
export interface NullableAggregateVersionCapable {
  aggregateVersion?: number;
}
```

[typescript/wow-client/src/model/command.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/model/command.ts)

### CompensationTarget {#api-CompensationTarget}

```ts
export interface CompensationTarget
  extends PartialBy<Identifier, 'id'>, FunctionInfoCapable {}
```

[typescript/wow-client/src/client/command/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/client/command/types.ts)

### DeleteAggregate {#api-DeleteAggregate}

```ts
export interface DeleteAggregate {}
```

[typescript/wow-client/src/client/command/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/client/command/types.ts)

### CommandBody {#api-CommandBody}

```ts
export type CommandBody<C> = RemoveReadonlyFields<C>;
```

[typescript/wow-client/src/client/command/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/client/command/types.ts)

### DeleteAggregateCommand {#api-DeleteAggregateCommand}

```ts
export type DeleteAggregateCommand = CommandBody<DeleteAggregate>;
```

[typescript/wow-client/src/client/command/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/client/command/types.ts)

### RecoverAggregate {#api-RecoverAggregate}

```ts
export interface RecoverAggregate {}
```

[typescript/wow-client/src/client/command/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/client/command/types.ts)

### RecoverAggregateCommand {#api-RecoverAggregateCommand}

```ts
export type RecoverAggregateCommand = CommandBody<RecoverAggregate>;
```

[typescript/wow-client/src/client/command/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/client/command/types.ts)

### ApplyResourceTags {#api-ApplyResourceTags}

```ts
export interface ApplyResourceTags extends ApplyAbacTags {}
```

[typescript/wow-client/src/client/command/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/client/command/types.ts)

### ApplyResourceTagsCommand {#api-ApplyResourceTagsCommand}

```ts
export type ApplyResourceTagsCommand = CommandBody<ApplyResourceTags>;
```

[typescript/wow-client/src/client/command/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/client/command/types.ts)

### BatchResult {#api-BatchResult}

```ts
export interface BatchResult extends ErrorInfo {
  after: string;
  size: number;
}
```

[typescript/wow-client/src/client/command/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/client/command/types.ts)

## 相关专题

[客户端配置与元数据](./configuration) · [快照查询](./snapshot-queries) · [过滤表达式与旧条件](./filters) · [投影、排序与分页](./query-options) · [游标查询](./cursor-queries) · [聚合构造器](./aggregations) · [事件与历史状态](./events-and-history) · [身份与资源归属](./identity-and-attribution)
