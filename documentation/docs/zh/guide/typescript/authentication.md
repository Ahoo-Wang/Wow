---
title: 认证与拦截器
description: 把 CoSec 认证、租户与所有者归属以及自定义请求头接到 Wow TypeScript 客户端使用的 Fetcher 上，覆盖单个服务与多个服务。
---

# 认证与拦截器

本页回答：**生成的 Wow 客户端怎样带上用户的令牌，`{tenantId}` 和 `{ownerId}` 又从哪里来？**

Wow 客户端自己不做认证。每个请求都经过一个 [Fetcher](https://fetcher.ahoo.me/)，请求在路径和请求体之外还需要的东西——令牌、归属请求头、令牌过期时的刷新——都由这个 Fetcher 的拦截器添加。每个服务配置一次，使用该 Fetcher 的所有客户端都会继承。

<!-- typecheck-generated: typescript/integration-test/src/generated -->

## 客户端使用哪个 Fetcher

| 创建客户端时传入 | 请求经由 |
|---|---|
| `{ fetcher: someFetcher }` | 这个实例 |
| `{ fetcher: 'orders' }` | 以这个名字注册的 Fetcher（`new NamedFetcher('orders', …)`） |
| 都不传（`new CartCommandClient()`、`cartQueryClientFactory.createSnapshotQueryClient()`） | 注册为 `default` 的 Fetcher：除非应用注册了自己的，否则就是 `@ahoo-wang/fetcher` 导出的 `fetcher`，它没有基础地址 |

生成的客户端把传入的选项合并到默认值之上，所以 `{ fetcher }` 会保留限界上下文的基础路径。因此只对接一个 Wow 服务的应用可以把这个服务注册为默认实例，然后不带选项地创建每个客户端：

```ts
import { NamedFetcher } from '@ahoo-wang/fetcher';

// 以 'default' 注册自己，替换 Fetcher 自带的默认实例。
export const wowService = new NamedFetcher('default', {
  baseURL: 'https://api.example.com',
  timeout: 10_000,
});
```

在任何客户端发出请求之前导入这个模块一次。

## CoSec

[CoSec](https://github.com/Ahoo-Wang/CoSec) 是 Wow 服务使用的授权框架。`@ahoo-wang/fetcher-cosec` 为它配置 Fetcher：

```bash
pnpm add @ahoo-wang/fetcher-cosec @ahoo-wang/fetcher-eventbus @ahoo-wang/fetcher-storage
```

```ts
import { Fetcher, NamedFetcher } from '@ahoo-wang/fetcher';
import { CoSecConfigurer, CoSecTokenRefresher } from '@ahoo-wang/fetcher-cosec';

export const wowService = new NamedFetcher('default', {
  baseURL: 'https://api.example.com',
});

export const cosec = new CoSecConfigurer({
  appId: 'order-console',
  // 刷新令牌用单独的 Fetcher，不带 CoSec 拦截器。
  tokenRefresher: new CoSecTokenRefresher({
    fetcher: new Fetcher({ baseURL: 'https://api.example.com' }),
    endpoint: '/auth/refresh',
  }),
  onUnauthorized: () => window.location.assign('/login'),
  onForbidden: async () => console.warn('Access denied'),
});

cosec.applyTo(wowService);
```

登录后保存令牌对；此后每个请求都会带上它：

```ts
import type { CoSecConfigurer } from '@ahoo-wang/fetcher-cosec';

export function onSignedIn(
  cosec: CoSecConfigurer,
  token: { accessToken: string; refreshToken: string },
) {
  cosec.tokenStorage.signIn(token);
}
```

`applyTo` 为该 Fetcher 的每个请求添加：

| 拦截器 | 作用 |
|---|---|
| CoSec 请求头 | `CoSec-App-Id`、持久的 `CoSec-Device-Id`、每次新生成的 `CoSec-Request-Id` |
| 授权（仅在配置了 `tokenRefresher` 时） | `Authorization: Bearer <访问令牌>`；在令牌过期前刷新（并发请求只刷新一次），并在刷新后重试一次被 401 拒绝的请求 |
| 资源归属 | 路径含有该变量且请求没有设置时，用令牌的 `tenantId` 声明填入 `{tenantId}`，用 `sub` 声明填入 `{ownerId}` |
| `onUnauthorized`、`onForbidden` | 刷新也解决不了的 401 时调用前者，403 时调用后者 |

资源归属正是生成的客户端不把 `tenantId`、`ownerId` 放进方法参数的原因：对按租户或所有者划分的聚合（`/tenant/{tenantId}/…`、`/owner/{ownerId}/…`），由登录用户提供它们。不用 CoSec 时，就自己通过 `urlParams.path` 按客户端或按请求提供：

```ts
import { CartCommandClient } from './generated/index.js';

export const commandsFor = (ownerId: string) =>
  new CartCommandClient({ urlParams: { path: { ownerId } } });
```

查询侧同理：`cartQueryClientFactory.createSnapshotQueryClient({ urlParams: { path: { ownerId } } })`。

## Wow 的空间

带空间的聚合从 `Wow-Space-Id` 请求头读取空间，命令与查询都一样。CoSec 自己的 `spaceIdProvider` 发送的是 `CoSec-Space-Id`，Wow 不读它。按命令用 `commandHeaders({ spaceId })` 发送 Wow 的请求头，或者通过客户端的 `headers` 让它的每个请求都带上：

```ts
import { WowHeaders } from '@ahoo-wang/wow-client';
import { cartQueryClientFactory } from './generated/index.js';

export const storeSnapshots = (spaceId: string) =>
  cartQueryClientFactory.createSnapshotQueryClient({
    headers: { [WowHeaders.SPACE_ID]: spaceId },
  });
```

## 自定义拦截器

请求拦截器在每个请求发出前看到它。要设置路径变量时排在 URL 解析（`URL_RESOLVE_INTERCEPTOR_ORDER`）之前；只设置请求头时排在真正发出请求之前的任何位置：

```ts
import {
  URL_RESOLVE_INTERCEPTOR_ORDER,
  type FetchExchange,
  type Fetcher,
  type RequestInterceptor,
} from '@ahoo-wang/fetcher';

export class TraceparentInterceptor implements RequestInterceptor {
  readonly name = 'TraceparentInterceptor';
  readonly order = URL_RESOLVE_INTERCEPTOR_ORDER - 1;

  constructor(private readonly traceparent: () => string | undefined) {}

  intercept(exchange: FetchExchange) {
    const value = this.traceparent();
    if (value) exchange.ensureRequestHeaders()['traceparent'] = value;
  }
}

export function trace(fetcher: Fetcher, traceparent: () => string | undefined) {
  fetcher.interceptors.request.use(new TraceparentInterceptor(traceparent));
}
```

响应拦截器和错误拦截器的用法相同，见 [Fetcher 拦截器](https://fetcher.ahoo.me/reference/fetcher/interceptors)。错误拦截器无法把失败的调用变成 `WowError`——请像[错误处理](./error-handling.md)那样用 `toWowError` 读取失败。

## 多个 Wow 服务

每个服务一个 Fetcher，并对每个都应用 CoSec。按名字注册后传名字，或者直接传实例：

```ts
import { NamedFetcher } from '@ahoo-wang/fetcher';
import type { CoSecConfigurer } from '@ahoo-wang/fetcher-cosec';

export function registerServices(cosec: CoSecConfigurer) {
  for (const [name, baseURL] of [
    ['orders', 'https://orders.example.com'],
    ['inventory', 'https://inventory.example.com'],
  ] as const) {
    cosec.applyTo(new NamedFetcher(name, { baseURL }));
  }
}
// new OrderCommandClient({ fetcher: 'orders' })
```

同一个 `CoSecConfigurer` 应用到多个 Fetcher 时共享一个令牌存储，用户只需登录一次。服务都在按限界上下文路由的网关之后时，为网关配一个 Fetcher 就够了：生成客户端的上下文前缀会选中对应的服务。

## 延伸阅读

- [在 Fetcher 中使用 CoSec](https://fetcher.ahoo.me/guides/integrations/cosec) 与 [fetcher-cosec 参考](https://fetcher.ahoo.me/reference/cosec)：令牌存储、刷新以及各个拦截器。
- [身份与资源归属](../../reference/typescript/wow-client/identity-and-attribution.md)：租户、所有者与空间在 Wow 一侧的含义。
- [SSR 与 Node.js](./ssr-and-node.md)：一个 Fetcher 服务多个用户的服务端怎样处理凭据。
