---
title: 认证与拦截器
description: 用用户的 CoSec 令牌为生成的 Wow 客户端的请求签名、选择空间，并通过 Fetcher 的拦截器加上你自己的请求头。
---

# 认证与拦截器

生成的客户端自己不做认证。每个请求都经过创建它们时所用的 [Fetcher](https://fetcher.ahoo.me/)，请求除了路由和请求体之外携带的任何东西，无论是令牌还是你自己的请求头，都由这个 Fetcher 的拦截器加上。每个 Fetcher 配置一次，基于它的每个客户端都会继承。

服务根据这些凭据决定调用方是谁：它的租户、所有者、空间以及它能做什么（[身份](../concepts/identity)）。路由只指明聚合，从不指明它的租户或所有者，所以关于调用方的一切都不是客户端方法的参数。

## CoSec {#cosec}

[CoSec](https://github.com/Ahoo-Wang/CoSec) 是 Wow 服务用来认证的授权框架（[保护部署](../operate/security)）。CoSec 8 的客户端 `@ahoo-wang/cosec-fetcher` 为它配置 Fetcher。它随 CoSec 发布，版本号与 CoSec 相同；在 CoSec 8.0 发布之前，它的 `latest` 标签是一个占位版本：请安装你所运行的 CoSec 版本对应的标签（8.0.0 的 beta 用 `@beta`），或者写明确切版本。

```bash
pnpm add @ahoo-wang/cosec-fetcher@beta @ahoo-wang/fetcher-eventbus @ahoo-wang/fetcher-storage
```

```ts
import { Fetcher } from '@ahoo-wang/fetcher';
import {
  CoSecConfigurer,
  CoSecTokenRefresher,
  CoSecTokenRevoker,
} from '@ahoo-wang/cosec-fetcher';

export const service = new Fetcher({ baseURL: 'https://api.example.com' });
// Refresh and log out through a Fetcher without the CoSec interceptors.
const bare = new Fetcher({ baseURL: 'https://api.example.com' });

export const cosec = new CoSecConfigurer({
  tokenRefresher: new CoSecTokenRefresher({ fetcher: bare, endpoint: '/auth/refresh' }),
  tokenRevoker: new CoSecTokenRevoker({ fetcher: bare, endpoint: '/auth/logout' }),
  onUnauthorized: () => window.location.assign('/login'),
  onForbidden: async () => console.warn('Access denied'),
  onScopeRejected: async (_exchange, problem) =>
    console.warn('Not allowed in this app or space', problem.requestId),
});

cosec.applyTo(service);
```

这两个端点属于 CoSec 应用：它的刷新路由（`cosec.authentication.login.refresh.path`）与登出路由（`cosec.authentication.login.logout.path`）。`cosec.tokenManager?.logout()` 在服务端结束会话，并在本地忘掉令牌对。

登录之后保存令牌对；从此这个 Fetcher 的每个请求都会携带它：

```ts
import type { CoSecConfigurer } from '@ahoo-wang/cosec-fetcher';

export function onSignedIn(
  cosec: CoSecConfigurer,
  token: { accessToken: string; refreshToken: string },
) {
  cosec.tokenStorage.signIn(token);
}
```

`applyTo` 加上 `Authorization: Bearer <access token>`、一个 `CoSec-Device-Id` 和一个 `CoSec-Request-Id`，但只加在发往绝对 `baseURL` 的源或页面自身的源的请求上：由 `isTrusted` 决定，默认是 `sameOriginTrust`，所以指向其他源的链接不带它们发送，它的 401 也不会触发刷新。它在发送请求之前刷新已过期的访问令牌，并发的请求只刷新一次。被以 401 回答的请求，只有当 CoSec 的问题是 `token-expired`，或这个 401 不带 CoSec 的问题时，才会刷新并重试一次；`token-invalid` 与 `token-revoked` 改为移除保存的令牌对，`unauthorized` 不刷新。只有刷新路由回答的 401 会结束会话：其他原因失败的刷新保留会话，超过 10 秒的刷新会被中止。`onUnauthorized` 在不再重试的 401 上、以及刷新路由拒绝刷新时被调用；`onScopeRejected` 在 403 `scope-rejected` 上被调用（令牌不是为服务的应用签发的，或空间不是调用方在其中有角色的空间）；`onForbidden` 在其他 403 上被调用，没有 `onScopeRejected` 时 `scope-rejected` 也交给它；回答带有 CoSec 的问题时，每个回调以它作为第二个参数。

没有请求头指明应用：服务把 CoSec 决定这个请求时所在的应用记录为提交的 `app_id`：宿主自己的应用，或令牌签发给的应用（它的 `aud`），或在 CoSec 网关之后由网关转发的应用。

服务拒绝的请求以 `WowProblemError` reject：401 `wow.auth.unauthenticated`、403 `wow.auth.forbidden`，或者创建调用方作用域之外的聚合时的 403 `wow.aggregate.forbidden`。对这样的聚合的其他命令得到 404 `wow.aggregate.not-found`，与不存在的聚合相同。

## 空间与作用域 {#spaces-and-scopes}

属于多个空间的调用方，用 `space`（`Wow-Space`）为每个请求选择一个空间；不选时，它在自己唯一的空间中行事，或者不在任何空间中。CoSec 服务改从 `CoSec-Space-Id` 读取空间，由 `CoSecConfigurer` 的 `spaceIdProvider` 发送。被允许跨作用域行事的调用方，用 `scope`（`Wow-Scope`）指明另一个租户、所有者或空间；命令则用 `targetTenant` 和 `targetScope` 指明（[发送命令](./commands#lifecycle-commands-scope-and-space)）。两者都是每个客户端方法的请求选项：

<!-- typecheck-generated: examples/eshop/web/src/generated -->

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

<!-- sample: examples/eshop/web/src/shop/clients.ts#caller -->

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
import type { CoSecConfigurer } from '@ahoo-wang/cosec-fetcher';

export function services(cosec: CoSecConfigurer) {
  const ordering = new Fetcher({ baseURL: 'https://ordering.example.com' });
  const catalog = new Fetcher({ baseURL: 'https://catalog.example.com' });
  cosec.applyTo(ordering);
  cosec.applyTo(catalog);
  return { ordering, catalog };
}
```

在按上下文路由的网关之后，一个指向网关的 Fetcher 就能服务所有服务：每个路由都以它的上下文开头。
