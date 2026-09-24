---
title: SSR and Node.js
description: Use the Wow TypeScript clients in Node.js scripts and servers and with server-side rendering such as the Next.js App Router, keeping each user's credentials apart.
---

# SSR and Node.js

This page answers: **can the Wow clients run on a server, and what changes when one process serves many users?**

`@ahoo-wang/wow-client` and generated clients run unchanged in Node.js: they use the platform's `fetch`, `ReadableStream` and `TextDecoderStream`, which Node has built in. What changes on a server is ownership. A browser tab belongs to one user, so a single configured Fetcher is safe there; a server process handles requests of many users at once, so anything that carries a user's identity must be created per request.

<!-- typecheck-generated: typescript/integration-test/src/generated -->

## Requirements

| Runtime | Requirement |
|---|---|
| Node.js | **22.12** or later: the packages declare `engines.node >=22.12.0` |
| Module format | `wow-client` and `wow-generator` ship ESM and CommonJS; `wow-react` and `wow-view-engine` ship ESM only |
| TypeScript | `experimentalDecorators: true` for generated clients; `module`/`moduleResolution` `NodeNext` or `Bundler` |
| React (for `wow-react`) | **19.3** or later, which the build imports as `react/compiler-runtime`; see [Compatibility](./compatibility.md) |

A script that runs the compiled output with Node needs `"type": "module"` for top-level `await`, as in the [Quick Start](./quick-start.md), which also runs there.

## One Fetcher per request that carries a user

Fetcher's `default` instance and any `NamedFetcher` are process-wide. On a server, never give them a user's token, and never apply a `CoSecConfigurer` to them: its token store would then serve every user of the process. Create a Fetcher for the request instead, pass it to the clients, and let it go with the request:

```ts
import { Fetcher } from '@ahoo-wang/fetcher';
import { cartQueryClientFactory } from './generated/index.js';

export async function loadCart(
  request: { authorization: string; ownerId: string },
  signal: AbortSignal,
) {
  // Created for this request: nothing here outlives it or reaches another user.
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

A service identity that is the same for every request, such as a machine token, may live on a shared Fetcher. Pass the incoming request's `AbortSignal` so that an abandoned page stops its Wow calls too.

## Streams in Node.js

Query streams and `sendAndWaitStream` work in Node as in a browser: `for await` reads them, and a failure midway throws a `WowError` (see [Error Handling](./error-handling.md)). Abort the signal, or `break` out of the loop, when the consumer goes away; an unread stream keeps its connection open.

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

## Next.js App Router and other React server rendering

- **Server Components and Route Handlers** can call `wow-client` and generated clients directly, with a per-request Fetcher as above, and pass plain data to the page.
- **Hooks run only in Client Components.** `@ahoo-wang/wow-react` exports hooks, and its build carries no `"use client"` directive, so the file that calls them starts with `'use client'`. See the [wow-react reference](../../reference/typescript/wow-react/) for the hooks themselves.
- **Components of `@ahoo-wang/wow-view-engine/ui`** (not released yet) use hooks too and also carry no directive. Re-export the ones you render from a file marked `'use client'`, and import them from there:

```tsx
'use client';
// app/components/view-engine.tsx
export { DataWorkbench, EmbeddedView } from '@ahoo-wang/wow-view-engine/ui';
```

- A Client Component that sends requests in the browser uses the browser's Fetcher and its CoSec configuration, as in [Authentication and Interceptors](./authentication.md). Create those module-level instances only in client code, so the server bundle never shares them between users.

## Where to read more

- [Authentication and Interceptors](./authentication.md): CoSec in the browser, named Fetchers, custom headers.
- [Compatibility and Versions](./compatibility.md): Node, React and TypeScript versions.
- [Fetcher documentation](https://fetcher.ahoo.me/): timeouts, interceptors and streams.
