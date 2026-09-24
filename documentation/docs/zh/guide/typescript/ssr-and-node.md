---
title: SSR 与 Node.js
description: 在 Node.js 脚本和服务端、以及 Next.js App Router 等服务端渲染中使用 Wow TypeScript 客户端，并把每个用户的凭据隔离开。
---

# SSR 与 Node.js

本页回答：**Wow 客户端能不能跑在服务端？一个进程服务多个用户时有什么不同？**

`@ahoo-wang/wow-client` 和生成的客户端在 Node.js 中无需改动即可运行：它们用的是平台自带的 `fetch`、`ReadableStream` 和 `TextDecoderStream`，Node 都内置了。服务端真正不同的是归属。浏览器标签页只属于一个用户，所以在那里共用一个配置好的 Fetcher 是安全的；服务端进程同时处理许多用户的请求，所以凡是携带用户身份的东西都必须按请求创建。

<!-- typecheck-generated: typescript/integration-test/src/generated -->

## 要求

| 运行环境 | 要求 |
|---|---|
| Node.js | **22.12** 或更高：各包声明了 `engines.node >=22.12.0` |
| 模块格式 | `wow-client` 与 `wow-generator` 同时提供 ESM 和 CommonJS；`wow-react` 与 `wow-view-engine` 只提供 ESM |
| TypeScript | 生成的客户端需要 `experimentalDecorators: true`；`module`/`moduleResolution` 用 `NodeNext` 或 `Bundler` |
| React（`wow-react`） | **19.3** 或更高，构建产物导入 `react/compiler-runtime`；见[兼容性](./compatibility.md) |

用 Node 运行编译产物的脚本需要 `"type": "module"` 才能使用顶层 `await`，[快速开始](./quick-start.md)就是这样运行的。

## 携带用户身份的请求，每个请求一个 Fetcher

Fetcher 的 `default` 实例和所有 `NamedFetcher` 都是进程级的。在服务端，永远不要把用户的令牌交给它们，也不要对它们应用 `CoSecConfigurer`：否则它的令牌存储会服务进程里的所有用户。改为按请求创建 Fetcher，传给客户端，随请求一起丢弃：

```ts
import { Fetcher } from '@ahoo-wang/fetcher';
import { cartQueryClientFactory } from './generated/index.js';

export async function loadCart(
  request: { authorization: string; ownerId: string },
  signal: AbortSignal,
) {
  // 为这个请求创建：这里的一切都不会比请求活得更久，也不会到达别的用户。
  const fetcher = new Fetcher({
    baseURL: process.env.CART_SERVICE_URL ?? 'http://localhost:8080',
    headers: { Authorization: request.authorization },
    timeout: 5_000,
  });
  const snapshots = cartQueryClientFactory.createSnapshotQueryClient({
    fetcher,
    urlParams: { path: { ownerId: request.ownerId } },
  });
  return snapshots.getStateById(request.ownerId, undefined, signal);
}
```

对每个请求都相同的服务身份（例如机器令牌）可以放在共享的 Fetcher 上。把传入请求的 `AbortSignal` 传下去，这样页面被放弃时它的 Wow 调用也会停止。

## Node.js 中的流

查询流和 `sendAndWaitStream` 在 Node 中与浏览器中一样：用 `for await` 读取，中途失败时抛出 `WowError`（见[错误处理](./error-handling.md)）。使用方离开时中止 signal 或 `break` 出循环；没读完的流会一直占着连接。

```ts
import { listQuery, type SnapshotQueryClient } from '@ahoo-wang/wow-client';

export async function countActive<S>(snapshots: SnapshotQueryClient<S>, signal: AbortSignal) {
  let count = 0;
  for await (const _event of await snapshots.listStateStream(listQuery({ limit: 1_000 }), undefined, signal)) {
    count++;
  }
  return count;
}
```

## Next.js App Router 与其他 React 服务端渲染

- **Server Component 和 Route Handler** 可以直接调用 `wow-client` 和生成的客户端（按上文每个请求一个 Fetcher），把普通数据传给页面。
- **Hook 只能在 Client Component 中运行。** `@ahoo-wang/wow-react` 导出的是 Hook，其构建产物没有 `"use client"` 指令，所以调用它们的文件要以 `'use client'` 开头。Hook 本身见 [wow-react 参考](../../reference/typescript/wow-react/)。
- **`@ahoo-wang/wow-view-engine/ui` 的组件**（尚未发布）同样使用 Hook，也没有该指令。在一个标了 `'use client'` 的文件里重新导出要渲染的组件，再从那里导入：

```tsx
'use client';
// app/components/view-engine.tsx
export { DataWorkbench, EmbeddedView } from '@ahoo-wang/wow-view-engine/ui';
```

- 在浏览器里发请求的 Client Component 使用浏览器端的 Fetcher 及其 CoSec 配置，见[认证与拦截器](./authentication.md)。这些模块级实例只在客户端代码里创建，服务端的打包产物就不会在用户之间共享它们。

## 延伸阅读

- [认证与拦截器](./authentication.md)：浏览器中的 CoSec、具名 Fetcher、自定义请求头。
- [兼容性与版本](./compatibility.md)：Node、React 与 TypeScript 的版本。
- [Fetcher 文档](https://fetcher.ahoo.me/)：超时、拦截器与流。
