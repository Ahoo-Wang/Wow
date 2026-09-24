---
title: Authentication and Interceptors
description: Wire CoSec authentication, tenant and owner attribution, and custom headers into the Fetcher that Wow's TypeScript clients use, for one service or several.
---

# Authentication and Interceptors

This page answers: **how do the generated Wow clients send the user's token, and where do `{tenantId}` and `{ownerId}` come from?**

Wow clients do not authenticate by themselves. Every request goes through a [Fetcher](https://fetcher.ahoo.me/), and whatever a request needs besides its path and body — a token, attribution headers, a refresh when the token expires — is added by that Fetcher's interceptors. Configure them once per service, and every client that uses the Fetcher inherits them.

<!-- typecheck-generated: typescript/integration-test/src/generated -->

## Which Fetcher a client uses

| The client is created with | It sends through |
|---|---|
| `{ fetcher: someFetcher }` | That instance |
| `{ fetcher: 'orders' }` | The Fetcher registered under that name (`new NamedFetcher('orders', …)`) |
| Neither (`new CartCommandClient()`, `cartQueryClientFactory.createSnapshotQueryClient()`) | The Fetcher registered as `default`: the `fetcher` export of `@ahoo-wang/fetcher`, which has no base URL, unless the application registers its own |

Generated clients merge what you pass over their defaults, so `{ fetcher }` keeps the bounded-context base path. An application that talks to one Wow service can therefore register that service as the default and create every client without options:

```ts
import { NamedFetcher } from '@ahoo-wang/fetcher';

// Registers itself under 'default', replacing Fetcher's own default instance.
export const wowService = new NamedFetcher('default', {
  baseURL: 'https://api.example.com',
  timeout: 10_000,
});
```

Import this module once, before any client sends a request.

## CoSec

[CoSec](https://github.com/Ahoo-Wang/CoSec) is the authorization framework Wow services use. `@ahoo-wang/fetcher-cosec` configures a Fetcher for it:

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
  // Refresh on a separate Fetcher, without the CoSec interceptors.
  tokenRefresher: new CoSecTokenRefresher({
    fetcher: new Fetcher({ baseURL: 'https://api.example.com' }),
    endpoint: '/auth/refresh',
  }),
  onUnauthorized: () => window.location.assign('/login'),
  onForbidden: async () => console.warn('Access denied'),
});

cosec.applyTo(wowService);
```

After sign-in, store the token pair; from then on every request carries it:

```ts
import type { CoSecConfigurer } from '@ahoo-wang/fetcher-cosec';

export function onSignedIn(
  cosec: CoSecConfigurer,
  token: { accessToken: string; refreshToken: string },
) {
  cosec.tokenStorage.signIn(token);
}
```

What `applyTo` adds to every request of that Fetcher:

| Interceptor | Effect |
|---|---|
| CoSec request headers | `CoSec-App-Id`, a persistent `CoSec-Device-Id`, a fresh `CoSec-Request-Id` |
| Authorization (only with a `tokenRefresher`) | `Authorization: Bearer <access token>`; refreshes the token before it expires, once for concurrent requests, and retries a request answered 401 after a refresh |
| Resource attribution | Fills the `{tenantId}` path variable from the token's `tenantId` claim and `{ownerId}` from its `sub` claim, when the path has the variable and the request did not set it |
| `onUnauthorized`, `onForbidden` | Called on a 401 the refresh could not fix, and on a 403 |

Resource attribution is why generated clients leave `tenantId` and `ownerId` out of their method parameters: for a tenant- or owner-scoped aggregate (`/tenant/{tenantId}/…`, `/owner/{ownerId}/…`), the signed-in user supplies them. Without CoSec, supply them yourself through `urlParams.path`, per client or per request:

```ts
import { CartCommandClient } from './generated/index.js';

export const commandsFor = (ownerId: string) =>
  new CartCommandClient({ urlParams: { path: { ownerId } } });
```

The query side works the same way: `cartQueryClientFactory.createSnapshotQueryClient({ urlParams: { path: { ownerId } } })`.

## Wow's space

A spaced aggregate reads its space from the `Wow-Space-Id` header, on commands and queries alike. CoSec's own `spaceIdProvider` sends `CoSec-Space-Id`, which Wow does not read. Send Wow's header per command with `commandHeaders({ spaceId })`, or for every request of a client through its `headers`:

```ts
import { WowHeaders } from '@ahoo-wang/wow-client';
import { cartQueryClientFactory } from './generated/index.js';

export const storeSnapshots = (spaceId: string) =>
  cartQueryClientFactory.createSnapshotQueryClient({
    headers: { [WowHeaders.SPACE_ID]: spaceId },
  });
```

## Your own interceptor

A request interceptor sees every request before it is sent. Order it before URL resolution (`URL_RESOLVE_INTERCEPTOR_ORDER`) when it sets a path variable, anywhere before the fetch when it only sets headers:

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

Response and error interceptors work the same way; see [Fetcher interceptors](https://fetcher.ahoo.me/reference/fetcher/interceptors). An error interceptor cannot turn a failed call into a `WowError` — read failures with `toWowError`, as in [Error Handling](./error-handling.md).

## Several Wow services

Give each service its own Fetcher and apply CoSec to each. Register them by name and pass the name, or pass the instance:

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

One `CoSecConfigurer` applied to several Fetchers shares one token store, so the user signs in once. Behind a gateway that routes by bounded context, one Fetcher for the gateway is enough: the generated clients' context prefix selects the service.

## Where to read more

- [CoSec with Fetcher](https://fetcher.ahoo.me/guides/integrations/cosec) and the [fetcher-cosec reference](https://fetcher.ahoo.me/reference/cosec): token storage, refresh and the individual interceptors.
- [Identity and resource attribution](../../reference/typescript/wow-client/identity-and-attribution.md): the Wow side of tenant, owner and space.
- [SSR and Node.js](./ssr-and-node.md): credentials on a server, where one Fetcher serves many users.
