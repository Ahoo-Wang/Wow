# Authentication and identity

Wow's clients send whatever their Fetcher sends: authentication is the Fetcher's interceptors, never a client option. The service reads the caller (tenant, user, roles, application) from the request, so no path and no body names a tenant or an owner.

## CoSec

`@ahoo-wang/fetcher-cosec` configures a Fetcher for a CoSec-protected service: the `Authorization: Bearer` token (refreshed on a separate Fetcher without the CoSec interceptors), `CoSec-App-Id` (the application every request is recorded and authorized in), `CoSec-Device-Id` and a fresh `CoSec-Request-Id`.

```ts
import { Fetcher } from '@ahoo-wang/fetcher';
import { CoSecConfigurer } from '@ahoo-wang/fetcher-cosec';
import { OrderCommandClient } from './generated/index.js';

const fetcher = new Fetcher({ baseURL: 'https://api.example.com', timeout: 30_000 });
new CoSecConfigurer({ appId: 'order-console' }).applyTo(fetcher);
export const orders = new OrderCommandClient({ fetcher });
```

- Confirm `CoSecConfigurer`'s options (token storage, refresher) in the installed `@ahoo-wang/fetcher-cosec`.
- A 401 `wow.auth.unauthenticated` is a missing or expired token; 403 `wow.auth.forbidden` the caller may not do it (a role, a tenant, a `Wow-Scope` it may not use), `wow.aggregate.forbidden` another owner's aggregate.

## Acting elsewhere: scope and space

`RequestOptions.scope` sends `Wow-Scope` (a tenant, an owner) and is honoured only for a caller the service lets act across scopes (an administrator, a service identity); `space` sends `Wow-Space`, one of the caller's own spaces. Leave both out for an ordinary user.

## Request facts

The service records `app_id`, and on its public port the client's IP and user agent, with every command. Other headers become facts only when the service lists them (`wow.http.request-facts`); add them with `RequestOptions.headers`. Never send personal data the user did not agree to record: events are permanent.

## On a server

A Server Component, route handler or BFF creates a Fetcher per request that carries the user's credentials, never the process-wide default Fetcher, so one user's token never reaches another's request.
