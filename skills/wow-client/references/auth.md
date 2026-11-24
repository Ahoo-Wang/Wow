# Authentication and identity

Wow's clients send whatever their Fetcher sends: authentication is the Fetcher's interceptors, never a client option. The service reads the caller (tenant, user, roles, application) from the request, so no path and no body names a tenant or an owner.

## CoSec

`@ahoo-wang/cosec-fetcher`, CoSec 8's client, configures a Fetcher for a CoSec-protected service: the `Authorization: Bearer` token (refreshed on a separate Fetcher without the CoSec interceptors), `CoSec-Device-Id` and a fresh `CoSec-Request-Id`, sent only to the origin of an absolute `baseURL` and the page's own origin (`isTrusted`, `sameOriginTrust` by default). No header names the application: CoSec 8 reads no `CoSec-App-Id`, and the app every request is recorded and authorized in is the one the token is issued for (its `aud`), or the one a gateway's route names. Until CoSec 8.0 is released its `latest` tag is a placeholder: install `@ahoo-wang/cosec-fetcher@beta` (an 8.0.0 beta) or an exact version, with `@ahoo-wang/fetcher-eventbus` and `@ahoo-wang/fetcher-storage`.

```ts
import { Fetcher } from '@ahoo-wang/fetcher';
import { CoSecConfigurer, CoSecTokenRefresher } from '@ahoo-wang/cosec-fetcher';
import { OrderCommandClient } from './generated/index.js';

const fetcher = new Fetcher({ baseURL: 'https://api.example.com', timeout: 30_000 });
new CoSecConfigurer({
  // The CoSec application's refresh route (`cosec.authentication.login.refresh.path`).
  tokenRefresher: new CoSecTokenRefresher({
    fetcher: new Fetcher({ baseURL: 'https://api.example.com' }),
    endpoint: '/auth/refresh',
  }),
  onUnauthorized: () => window.location.assign('/login'),
}).applyTo(fetcher);
export const orders = new OrderCommandClient({ fetcher });
```

- Without a `tokenRefresher` the configurer sends no token, only the device and request headers. A 401 is refreshed and retried once only for CoSec's `token-expired` (or a 401 without CoSec's problem); `token-invalid` and `token-revoked` remove the stored pair; only a 401 from the refresh route ends the session. A 403 `scope-rejected` (a token for another app, a space without a role) goes to `onScopeRejected`, else `onForbidden`. Confirm the options in the installed `@ahoo-wang/cosec-fetcher`.
- A 401 `wow.auth.unauthenticated` is a missing or expired token; 403 `wow.auth.forbidden` the caller may not do it (a role, a tenant, a `Wow-Scope` it may not use), `wow.aggregate.forbidden` a creation of another owner's aggregate (any other command on one is 404 `wow.aggregate.not-found`, as a missing aggregate is).

## Acting elsewhere: scope and space

`RequestOptions.scope` sends `Wow-Scope` (a tenant, an owner; a command sets `targetTenant` and `targetScope: { ownerId?, spaceId? }` instead) and is honoured only for a caller the service lets act across scopes (an administrator, a service identity); `space` sends `Wow-Space`, one of the caller's own spaces; a CoSec service reads the space from `CoSec-Space-Id` instead, which `CoSecConfigurer`'s `spaceIdProvider` sends. Leave both out for an ordinary user.

## Request facts

The service records `app_id`, and on its public port the client's IP and user agent, with every command. Other headers become facts only when the service lists them (`wow.http.request-facts`); add them with `RequestOptions.headers`. Never send personal data the user did not agree to record: events are permanent.

## On a server

A Server Component, route handler or BFF creates a Fetcher per request that carries the user's credentials, never the process-wide default Fetcher, so one user's token never reaches another's request.
