---
title: 错误处理
description: Wow 调用在哪里失败、TypeScript 客户端在每种情况下看到什么，以及怎样读取 WowError、字段校验错误、流中的错误、超时与重试。
---

# 错误处理

本页回答：**调用 Wow 服务失败时，我的代码收到的是什么，又该怎样处理？**

Wow 服务用 HTTP 错误状态加 `ErrorInfo` 响应体表示被拒绝的请求。Fetcher 会拒绝（reject）每个非 2xx 响应，所以**业务失败是异常，不是返回值**。`@ahoo-wang/wow-client` 把这个异常转成带 Wow 错误码的 `WowError`。流是例外中的例外：出错时服务端早已应答 HTTP 200，所以失败只能在流内部传递。

## 在哪里失败

| 失败位置 | 代码收到什么 | `await toWowError(error)` |
|---|---|---|
| 请求没有得到 Wow 的应答：网络故障、DNS、CORS、fetcher 的 `timeout`、中止、代理自己的错误页 | Promise 以 fetcher 的 `ExchangeError` 拒绝（或 `FetchTimeoutError`，或以中止的 `AbortError` 为 cause） | `undefined` |
| Wow 拒绝了请求：校验失败、命令处理函数失败、版本冲突、聚合不存在、无法执行的查询 | Promise 以 fetcher 的 `ExchangeError` 拒绝；其响应带映射后的 HTTP 状态、`Wow-Error-Code` 响应头和 `ErrorInfo` 响应体 | 带 `errorCode`、`errorMsg`、`bindingErrors` 与 `status` 的 `WowError` |
| 查询流或 `sendAndWaitStream` 开始后失败 | 读流时抛出 `WowError`：`for await` 抛出，`reader.read()` 拒绝 | 不需要，错误本身就是 `WowError` |
| 流式命令的某个阶段失败 | 一个 `errorCode` 不是 `'Ok'` 的 `CommandResult` 事件；流继续直到结束 | 不适用 |

普通命令（`send`，或生成的 `…CommandClient` 的方法）不会以失败结果完成：服务端按错误码映射的状态应答失败的 `CommandResult`，所以 Promise 拒绝。响应体仍是那个 `CommandResult`，它本身就是 `ErrorInfo`，`toWowError` 同样能读出。

下面是示例服务对几种失败的应答，以及 `toWowError` 读出的内容：

| 调用 | `status` | `errorCode` | `bindingErrors` |
|---|---|---|---|
| `addCartItem({ productId: '', quantity: 0 })` | 400 | `CommandValidation` | `quantity`、`productId` |
| 对购物车中没有的商品 `changeQuantity` | 400 | `IllegalArgument` | 无 |
| 对不存在的 id 调用 `getStateById` | 404 | `NotFound` | 无 |
| 对 Wow 8.11～9.1.3 调用不带 `limit` 的 `listState(listQuery())` | 400 | `IllegalArgument`；`WowError` 的消息会补一句「省略 `limit` 需要 Wow 9.1.5」 | 无 |
| 命令等待的阶段在 `timeoutMs` 内没有到达 | 408 | `RequestTimeout` | 无 |
| 请求一个没有服务监听的端口 | — | `toWowError` 返回 `undefined` | — |

## 读取被拒绝的请求

```ts
import { ErrorCodes, toWowError, type SnapshotQueryClient } from '@ahoo-wang/wow-client';

interface CartState {
  items: Array<{ productId: string; quantity: number }>;
}

export async function findCart(
  snapshots: SnapshotQueryClient<CartState>,
  id: string,
): Promise<CartState | undefined> {
  try {
    return await snapshots.getStateById(id);
  } catch (error) {
    const wowError = await toWowError(error);
    if (wowError?.errorCode === ErrorCodes.NOT_FOUND) return undefined;
    throw wowError ?? error;
  }
}
```

- `toWowError` 是异步的：它要读取错误响应体，读的是克隆，其他处理者仍能读原响应。
- 重新抛出 `wowError ?? error`：Wow 没有应答时，原始错误才是值得记录的那个。
- 按 `errorCode` 对照 `ErrorCodes` 分支，不要按 HTTP 状态：好几个错误码共用 400。映射关系在 [`ErrorHttpStatusMapping`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/exception/ErrorHttpStatusMapping.kt)；服务自行注册的错误码默认映射为 400。

## 在表单上展示校验错误

`CommandValidation` 为每个不合法的字段带一个 `BindingError`：`name` 是属性路径，`msg` 是服务端的提示：

```ts
import { ErrorCodes, toWowError } from '@ahoo-wang/wow-client';

export async function submit(
  send: () => Promise<unknown>,
  showFieldError: (field: string, message: string) => void,
): Promise<boolean> {
  try {
    await send();
    return true;
  } catch (error) {
    const wowError = await toWowError(error);
    if (wowError?.errorCode !== ErrorCodes.COMMAND_VALIDATION) throw wowError ?? error;
    for (const { name, msg } of wowError.bindingErrors) showFieldError(name, msg);
    return false;
  }
}
```

## 流

查询流（`listStream`、`listStateStream`、`aggregateStream`、事件客户端的 `loadStream`）和 `CommandClient.sendAndWaitStream` 在服务端中途失败时以 `WowError` 出错。此时 Wow 已经应答了 HTTP 200，于是发出最后一个以错误码命名的事件并关闭；客户端把这个事件变成错误：

```ts
import { WowError, listQuery, type SnapshotQueryClient } from '@ahoo-wang/wow-client';

export async function readAll<S>(snapshots: SnapshotQueryClient<S>, signal: AbortSignal) {
  const states: S[] = [];
  try {
    for await (const row of await snapshots.listStateStream(listQuery({ limit: 1_000 }), undefined, signal)) {
      states.push(row);
    }
  } catch (error) {
    if (error instanceof WowError) console.warn(error.errorCode, error.errorMsg);
    throw error;
  }
  return states;
}
```

流式命令把到达的每个阶段报告为一个 `CommandResult`。处理函数失败的阶段是一个带自己 `errorCode` 的结果，而不是错误，所以要逐个检查：

```ts
import { CommandStage, ErrorCodes, waitStrategy, type CommandClient } from '@ahoo-wang/wow-client';

export async function addAndFollow(commands: CommandClient) {
  const results = await commands.sendAndWaitStream({
    path: 'add_cart_item',
    method: 'POST',
    headers: waitStrategy({ stage: CommandStage.PROJECTED, timeoutMs: 10_000 }),
    body: { productId: 'book-1', quantity: 1 },
  });
  for await (const result of results) {
    if (result.errorCode !== ErrorCodes.SUCCEEDED) {
      throw new Error(`${result.stage} failed: ${result.errorCode} ${result.errorMsg}`);
    }
  }
}
```

生成的 `…StreamCommandClient` 行为相同：它们使用 wow-client 的 `COMMAND_STREAM_ENDPOINT`，所以结束流的服务端错误（例如等待超时 `RequestTimeout`、命令校验失败）会让流以 `WowError` 报错，`for await` 会抛出。自身 `errorCode` 不是 `Ok` 的命令结果仍然是一条结果，同上。

## 超时与取消

每个查询方法的最后一个参数接受 `AbortController` 或 `AbortSignal`；命令在请求中接受 `signal` 或 `timeout`。被中止或超时的调用以 fetcher 的错误拒绝，`toWowError` 返回 `undefined`：服务端可能已经处理，也可能没有。

```ts
import type { SnapshotQueryClient } from '@ahoo-wang/wow-client';

export function loadWithin<S>(snapshots: SnapshotQueryClient<S>, id: string, ms: number) {
  return snapshots.getStateById(id, undefined, AbortSignal.timeout(ms));
}
```

命令自己的 `waitStrategy({ timeoutMs })` 是另一回事：它告诉服务端等待该阶段多久，阶段没有到达时服务端应答 `RequestTimeout`（408）。命令本身之后仍可能完成。

## 重试

| 情况 | 能否重试 |
|---|---|
| **查询**没有得到 Wow 应答（网络、超时、中止） | 可以 |
| **命令**没有得到 Wow 应答 | 只能用**同一个** `requestId`：服务端对一个请求 ID 只执行一次，重复的请求应答 `DuplicateRequestId` |
| 命令收到 `RequestTimeout`（408） | 不要：服务端已收到命令，它仍可能完成。读取状态来了解结果 |
| `DuplicateRequestId` | 不要重发；第一次请求已经到达服务端。读取状态来了解结果 |
| `TooManyRequests`（429） | 可以，稍等之后 |
| `EventVersionConflict`、`CommandExpectVersionConflict`（409） | 重新加载状态后再决定；原样重发只会再次冲突 |
| `CommandValidation`、`IllegalArgument`、`NotFound` 等 4xx | 不要：请求本身有误 |
| `InternalServerError` 等 5xx | 只对幂等调用重试，并限制次数 |

```ts
import {
  CommandStage,
  commandHeaders,
  toWowError,
  waitStrategy,
  type CommandRequestHeaders,
} from '@ahoo-wang/wow-client';

export async function sendOnce<R>(
  send: (headers: CommandRequestHeaders) => Promise<R>,
  attempts = 3,
): Promise<R> {
  const headers: CommandRequestHeaders = {
    ...commandHeaders({ requestId: crypto.randomUUID() }),
    ...waitStrategy({ stage: CommandStage.PROCESSED }),
  };
  for (let attempt = 1; ; attempt++) {
    try {
      return await send(headers); // 每次尝试都用同一个 requestId
    } catch (error) {
      if ((await toWowError(error)) || attempt >= attempts) throw error; // Wow 已应答：不要重发
    }
  }
}
```

## 延伸阅读

- [错误与文档工具](../../reference/typescript/wow-client/errors-and-utilities.md)：`WowError`、`toWowError`、`ErrorCodes` 与流提取器的逐个签名。
- [命令与等待结果](../../reference/typescript/wow-client/commands.md)：`CommandResult` 与等待阶段。
- 服务端的[完成语义](../command/completion.md)和[故障排查](../troubleshooting.md)。
- [TypeScript 客户端排障](./troubleshooting.md)：按报错信息查找。
