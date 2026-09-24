---
title: '命令与等待结果'
description: '命令与等待结果 — @ahoo-wang/wow-client'
---

# 命令与等待结果

`CommandClient<C>` 是由装饰器实现的传输客户端。`send(request, attributes?)` 返回 Promise&lt;CommandResult&gt;；`sendAndWaitStream` 返回 Promise&lt;ReadableStream&lt;JsonServerSentEvent&lt;CommandResult&gt;&gt;&gt;，使用 Accept text/event-stream 和 JSON SSE 提取。必须在 ApiMetadata 和/或 CommandRequest 中提供服务所需端点，不会根据 C 推导通用命令 URL。

## 请求与等待阶段

CommandRequest 扩展 ParameterRequest；body 为命令可写字段 `CommandBody<C>`，还可带 `path`（端点路径覆盖，不是 `url`）、`method`、路径参数、头和其他请求数据。`CommandHeaders` 给出准确 HTTP 头名。头值为字符串，覆盖租户/所有者/空间/聚合归属、预期聚合版本、Request-Id、Local-First、命令 context/name/type、等待超时、stage/context/processor/function 及对应链尾选择器。`Command-Header-` 是扩展前缀。通用 CommandRequestHeaders 声明把已知头列为必填；常规部分头集合也可通过生成客户端所用的底层请求/元数据选项提供。传输层不会替你生成幂等键或选择等待阶段。

| CommandStage  | 表示的信号     |
| ------------- | -------------- |
| SENT          | 命令已发送     |
| PROCESSED     | 命令处理阶段   |
| SNAPSHOT      | 快照阶段       |
| PROJECTED     | 投影阶段       |
| EVENT_HANDLED | 事件处理器阶段 |
| SAGA_HANDLED  | Saga 处理阶段  |

这些是不同服务端等待目标，并不保证所有下游消费者都已追上。客户端未设置数值等待超时默认值。CommandResult 包含身份、聚合归属、stage、command/request/wait ID、signalTime、可选 aggregateVersion、function、result 映射和 errorCode/errorMsg/bindingErrors。HTTP 成功不等于命令成功，应检查 `ErrorCodes.isSucceeded(result.errorCode)`。`WaitSignal` 对应信号模型，其 aggregateId 为嵌套对象。BatchResult 含 after/size 与 ErrorInfo，本身不实现批次遍历。

传输或提取失败拒绝 Promise；流错误也可能在初始 Promise 完成后的 reader.read 才出现，提前退出必须取消并释放 reader。DeleteAggregate/RecoverAggregate 是空命令体契约，资源标签命令带 tags；它们在服务端执行端点前不产生删除/恢复效果。

## 读取命令结果 {#result-fields}

| 字段                                                      | 解释                                                                 |
| --------------------------------------------------------- | -------------------------------------------------------------------- |
| `id`、`commandId`、`requestId`、`waitCommandId`           | 不同的信号/命令/请求/等待关联 ID，不应互相替代服务端幂等键。         |
| `contextName`、`aggregateName`、`tenantId`、`aggregateId` | CommandResult 中是平铺聚合身份；WaitSignal 则嵌套 AggregateId 对象。 |
| `stage`                                                   | 当前信号的等待阶段，不是覆盖所有投影的布尔成功标记。                 |
| `aggregateVersion?`                                       | 服务端报告的可选版本；缺失时不能建立可见性屏障。                     |
| `signalTime`                                              | 数字信号时间戳，不是客户端超时时长。                                 |
| `function`                                                | 处理器/函数元数据，参见[消息元数据](./messages-and-state)。          |
| `result`                                                  | 服务端提供的结果映射，不是泛型命令体 C。                             |
| `errorCode`、`errorMsg`、`bindingErrors?`                 | 业务结果与可选字段错误，参见[错误分类](./errors-and-utilities)。     |

嵌套/平铺身份细节参见[身份与归属](./identity-and-attribution)。结果接口不赋默认值、不验证 JSON。流通过 `event.data` 携带连续 CommandResult 载荷；初始 HTTP 成功不表示所有事件或阶段均已完成。

## 完整示例

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

示例中的服务 URL 需要应用实现；类型检查不代表已经访问外部服务。

## 公开签名与类型

以下签名按当前根入口可达声明核对。`?` 表示可省略；泛型/接口只约束编译期，继承项与关联类型可从 [符号索引](./symbols) 定位。运行时默认值和失败行为以本页上文为准。

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

::: details 展开完整字段与成员

```ts
export class CommandHeaders {
  static readonly COMMAND_HEADERS_PREFIX = 'Command-';
  static readonly TENANT_ID = `${CommandHeaders.COMMAND_HEADERS_PREFIX}Tenant-Id`;
  static readonly OWNER_ID = `${CommandHeaders.COMMAND_HEADERS_PREFIX}Owner-Id`;
  static readonly SPACE_ID = `${CommandHeaders.COMMAND_HEADERS_PREFIX}Space-Id`;
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

[typescript/wow-client/src/command/commandHeaders.ts:33](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/command/commandHeaders.ts#L33)

### CommandRequestHeaders {#api-CommandRequestHeaders}

::: details 展开完整字段与成员

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

[typescript/wow-client/src/command/commandRequest.ts:148](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/command/commandRequest.ts#L148)

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

[typescript/wow-client/src/command/commandRequest.ts:158](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/command/commandRequest.ts#L158)

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

## 相关专题

[客户端配置与元数据](./configuration) · [快照查询](./snapshot-queries) · [过滤表达式与旧条件](./filters) · [投影、排序与分页](./query-options) · [游标查询](./cursor-queries) · [聚合构造器](./aggregations) · [事件与历史状态](./events-and-history) · [身份与资源归属](./identity-and-attribution)
