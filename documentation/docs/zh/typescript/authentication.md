---
title: 认证与拦截器
description: 用用户的 CoSec 令牌为生成的 Wow 客户端的请求签名、选择空间，并通过 Fetcher 的拦截器加上你自己的请求头。
---

# 认证与拦截器

生成的客户端自己不做认证。每个请求都经过创建它们时所用的 [Fetcher](https://fetcher.ahoo.me/)，请求除了路由和请求体之外携带的任何东西，无论是令牌还是你自己的请求头，都由这个 Fetcher 的拦截器加上。每个 Fetcher 配置一次，基于它的每个客户端都会继承。

服务根据这些凭据决定调用方是谁：它的租户、所有者、空间以及它能做什么（[身份](../concepts/identity)）。路由只指明聚合，从不指明它的租户或所有者，所以关于调用方的一切都不是客户端方法的参数。

## CoSec {#cosec}

[CoSec](https://github.com/Ahoo-Wang/CoSec) 是 Wow 服务用来认证的授权框架（[保护部署](../operate/security)）。`@ahoo-wang/fetcher-cosec` 为它配置 Fetcher：

```bash
pnpm add @ahoo-wang/fetcher-cosec @ahoo-wang/fetcher-eventbus @ahoo-wang/fetcher-storage
```

```ts
import { Fetcher } from '@ahoo-wang/fetcher';
import { CoSecConfigurer, CoSecTokenRefresher } from '@ahoo-wang/fetcher-cosec';

export const service = new Fetcher({ baseURL: 'https://api.example.com' });

export const cosec = new CoSecConfigurer({
  appId: 'order-console',
  // Refresh through a Fetcher without the CoSec interceptors.
  tokenRefresher: new CoSecTokenRefresher({
    fetcher: new Fetcher({ baseURL: 'https://api.example.com' }),
    endpoint: '/auth/refresh',
  }),
  onUnauthorized: () => window.location.assign('/login'),
  onForbidden: async () => console.warn('Access denied'),
});

cosec.applyTo(service);
```

登录之后保存令牌对；从此这个 Fetcher 的每个请求都会携带它：

```ts
import type { CoSecConfigurer } from '@ahoo-wang/fetcher-cosec';

export function onSignedIn(
  cosec: CoSecConfigurer,
  token: { accessToken: string; refreshToken: string },
) {
  cosec.tokenStorage.signIn(token);
}
```

`applyTo` 加上 `Authorization: Bearer <access token>`，在令牌过期之前刷新它（并发的请求只刷新一次），在刷新之后重试被以 401 回答的请求，在刷新也解决不了的 401 时调用 `onUnauthorized`，在 403 时调用 `onForbidden`。它还发送 `CoSec-App-Id`、一个 `CoSec-Device-Id` 和一个 `CoSec-Request-Id`。只有当 CoSec 通过该应用的角色授权了请求时，服务才把这个应用记录为提交的 `app_id`：这个请求头本身不被信任。

服务拒绝的请求以 `WowProblemError` reject：401 `wow.auth.unauthenticated`、403 `wow.auth.forbidden`，或者对调用方作用域之外的聚合的 403 `wow.aggregate.forbidden`。

## 空间与作用域 {#spaces-and-scopes}

属于多个空间的调用方，用 `space`（`Wow-Space`）为每个请求选择一个空间；不选时，它在自己唯一的空间中行事，或者不在任何空间中。被允许跨作用域行事的调用方，用 `scope`（`Wow-Scope`）指明另一个租户、所有者或空间；命令则用 `targetTenant` 和 `targetScope` 指明（[发送命令](./commands#lifecycle-commands-scope-and-space)）。两者都是每个客户端方法的请求选项：

<!-- typecheck-generated: example/eshop/web/src/generated -->

<!-- typecheck-context
import { OrderSnapshotQueryClient } from './generated/ordering/index.js';
declare const orderQueries: OrderSnapshotQueryClient;
-->

```ts
import { filter, listQuery } from '@ahoo-wang/wow-client/dsl';

const query = listQuery({ filter: filter.eq('state.status', 'PAID'), limit: 20 });
const inStore = await orderQueries.list(query, { space: 'store-42' });
const forTenant = await orderQueries.list(query, { scope: { tenantId: 'tenant-b' } });
```

不能在它所指明的作用域中行事的调用方，会以 403 被拒绝。

## 你自己的拦截器 {#an-interceptor-of-your-own}

请求拦截器在其 Fetcher 的每个请求发送之前看到它。eShop 的 Web 界面不用令牌，而是通过一个拦截器，用它的开发服务器所信任的请求头为请求签名：

<!-- typecheck: skip — compiled with the rest of the eShop web UI -->

<!-- sample: example/eshop/web/src/shop/clients.ts#caller -->

```ts
/** Sends the caller's headers on every request, as a host's CoSec interceptor sends its token. */
class CallerHeaders implements RequestInterceptor {
  readonly name = "EshopCaller";
  readonly order = 0;

  constructor(private readonly user: User) {}

  intercept(exchange: FetchExchange): void {
    Object.assign(exchange.ensureRequestHeaders(), callerHeaders(this.user));
  }
}
```

然后用 `fetcher.interceptors.request.use(new CallerHeaders(user))` 注册它。这些请求头对生产服务没有意义：在那里，只有已认证的主体才是调用方。响应拦截器和错误拦截器的用法相同（[Fetcher 拦截器](https://fetcher.ahoo.me/reference/fetcher/interceptors)）。错误拦截器看不到 `WowProblemError`；客户端在拦截器运行之后，才把失败的响应变成它。

## 多个服务 {#several-services}

给每个服务一个自己的 Fetcher，并对每个都应用 CoSec。应用到多个 Fetcher 上的同一个 `CoSecConfigurer` 保持同一个令牌存储，所以用户只需登录一次：

```ts
import { Fetcher } from '@ahoo-wang/fetcher';
import type { CoSecConfigurer } from '@ahoo-wang/fetcher-cosec';

export function services(cosec: CoSecConfigurer) {
  const ordering = new Fetcher({ baseURL: 'https://ordering.example.com' });
  const catalog = new Fetcher({ baseURL: 'https://catalog.example.com' });
  cosec.applyTo(ordering);
  cosec.applyTo(catalog);
  return { ordering, catalog };
}
```

在按上下文路由的网关之后，一个指向网关的 Fetcher 就能服务所有服务：每个路由都以它的上下文开头。
