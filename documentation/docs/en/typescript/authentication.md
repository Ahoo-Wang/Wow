---
title: Authentication and Interceptors
description: Sign the requests of the generated Wow clients with the user's CoSec token, pick a space, and add headers of your own through the Fetcher's interceptors.
---

# Authentication and Interceptors

The generated clients do not authenticate by themselves. Every request goes through the [Fetcher](https://fetcher.ahoo.me/) they were created with, and whatever it carries besides its route and body, a token or a header of your own, is added by that Fetcher's interceptors. Configure them once per Fetcher, and every client over it inherits them.

The service decides who the caller is from those credentials: its tenant, its owner, its spaces and what it may do ([Identity](../concepts/identity)). A route names only the aggregate, never its tenant or owner, so nothing about the caller is a parameter of a client method.

## CoSec

[CoSec](https://github.com/Ahoo-Wang/CoSec) is the authorization framework a Wow service authenticates with ([Secure a Deployment](../operate/security)). `@ahoo-wang/cosec-fetcher`, CoSec 8's client, configures a Fetcher for it. It is released with CoSec at CoSec's version, and until CoSec 8.0 is released its `latest` tag is a placeholder: install the tag of the CoSec release you run (`@beta` for an 8.0.0 beta) or an exact version.

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

The endpoints are the CoSec application's: its refresh route (`cosec.authentication.login.refresh.path`) and its logout route (`cosec.authentication.login.logout.path`). `cosec.tokenManager?.logout()` ends the session at the server and forgets the pair here.

After sign-in, store the token pair; from then on every request of that Fetcher carries it:

```ts
import type { CoSecConfigurer } from '@ahoo-wang/cosec-fetcher';

export function onSignedIn(
  cosec: CoSecConfigurer,
  token: { accessToken: string; refreshToken: string },
) {
  cosec.tokenStorage.signIn(token);
}
```

`applyTo` adds `Authorization: Bearer <access token>`, a `CoSec-Device-Id` and a `CoSec-Request-Id`, only to a request on the origin of an absolute `baseURL` or on the page's own origin: `isTrusted` decides, `sameOriginTrust` by default, so a link to another origin is sent without them, and its 401 refreshes nothing. It refreshes an expired access token before a request is sent, once for concurrent requests. A request answered 401 is refreshed and retried once only when CoSec's problem is `token-expired`, or when the 401 carries no CoSec problem; `token-invalid` and `token-revoked` remove the stored pair instead, and `unauthorized` is not refreshed. Only a 401 from the refresh route ends the session: a refresh that fails otherwise keeps it, and one that takes longer than 10 seconds is cut short. `onUnauthorized` is called on a 401 that is not retried and when the refresh route refuses a refresh, `onScopeRejected` on a 403 `scope-rejected` (the token is not for the service's app, or the space is not one the caller has a role in), and `onForbidden` on any other 403, or on `scope-rejected` too when there is no `onScopeRejected`; each receives CoSec's problem, when the answer carries one, as its second argument.

No header names the application: the service records as the commit's `app_id` the app CoSec decided the request in, the host's own, the one the token is issued for (its `aud`), or behind a CoSec gateway the app the gateway forwarded.

A request the service refuses rejects with a `WowProblemError`: 401 `wow.auth.unauthenticated`, 403 `wow.auth.forbidden`, or 403 `wow.aggregate.forbidden` for a creation of an aggregate outside the caller's scope. Any other command on such an aggregate is answered 404 `wow.aggregate.not-found`, as a missing one is.

## Spaces and scopes

A caller that belongs to several spaces picks one per request with `space` (`Wow-Space`); without it, it acts in its only space, or in none. A CoSec service reads the space from `CoSec-Space-Id` instead, which the `CoSecConfigurer`'s `spaceIdProvider` sends. A caller allowed to act across scopes names another tenant, owner or space with `scope` (`Wow-Scope`); a command names it with `targetTenant` and `targetScope` ([Send Commands](./commands#lifecycle-commands-scope-and-space)). Both are request options of every client method:

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

A caller that may not act in the scope it names is refused with 403.

## An interceptor of your own

A request interceptor sees every request of its Fetcher before it is sent. The eShop's web UI signs its requests with headers its development servers trust instead of a token, through one:

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

and registers it with `fetcher.interceptors.request.use(new CallerHeaders(user))`. Those headers mean nothing to a production service: there, only an authenticated principal is a caller. Response and error interceptors work the same way ([Fetcher interceptors](https://fetcher.ahoo.me/reference/fetcher/interceptors)). An error interceptor does not see a `WowProblemError`; the client turns the failed response into one after the interceptors ran.

## Several services

Give each service a Fetcher of its own and apply CoSec to each. One `CoSecConfigurer` applied to several Fetchers keeps one token store, so the user signs in once:

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

Behind a gateway that routes by context, one Fetcher for the gateway serves every service: each route starts with its context.
