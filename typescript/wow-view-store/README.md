# `@ahoo-wang/wow-view-store`

[简体中文](./README.zh-CN.md)

The view engine's `ViewStore` on a Wow server: the saved views and preferences
of [`@ahoo-wang/wow-view-engine`](../wow-view-engine/README.md), kept by the
[view store](../../view-store/README.md) — two Wow aggregates, served by the
standalone `wow-view-store-server` or by any Wow service that embeds
`wow-view-store-starter`.

Released with Wow 9.2.0, from the same tag and with the same version, together
with the view engine and the view store's server modules.

> **Compatibility.** From 9.2.0 on, a patch release never breaks the public
> surface (the exports of the entry and the error codes); a minor release may,
> and its release notes list every break with the steps to follow. Keep the Wow
> packages on one minor, as [version ranges](https://wow.ahoo.me/guide/typescript/compatibility#version-ranges)
> explains.

## Use

Peer dependencies: `@ahoo-wang/fetcher`, `@ahoo-wang/wow-client` and
`@ahoo-wang/wow-view-engine`, and the peers those declare, which a host installs
with them: `@ahoo-wang/fetcher-decorator` and `@ahoo-wang/fetcher-eventstream`
(wow-client's), and the view engine's optional `react`, `react-dom`,
`react-router` and `mingo` where its own entries need them. Node `>=22.12.0` or
a current browser; TypeScript 6 or later.

`WowViewStore` takes a fetcher and, optionally, the permissions that drive the
buttons. It takes no tenant, user or application: the fetcher's interceptors
carry who is asking, as they do for every other Wow request of the host.

<!-- typecheck-context
import type { QueryApi } from '@ahoo-wang/wow-client';
import type { ViewDefinition, ViewPermissions } from '@ahoo-wang/wow-view-engine';
declare const orders: ViewDefinition;
declare const ordersSource: Pick<QueryApi<any>, 'paged' | 'cursor' | 'aggregate'>;
declare const tokenStorage: TokenStorage;
declare const permissionsFromRoles: (definitionId: string) => ViewPermissions;
-->

```ts
import { Fetcher } from '@ahoo-wang/fetcher';
import {
  ResourceAttributionRequestInterceptor,
  type TokenStorage,
} from '@ahoo-wang/fetcher-cosec';
import { ViewEngine } from '@ahoo-wang/wow-view-engine';
import { WowViewStore } from '@ahoo-wang/wow-view-store';

// The gateway in front of the view store. In a host, the fetcher already
// carries CoSec's interceptors: the authorization, `CoSec-App-Id`
// (CoSecRequestInterceptor) and the path's {tenantId} and {ownerId}
// (ResourceAttributionRequestInterceptor, from the token).
const fetcher = new Fetcher({ baseURL: 'https://api.example.com' });
fetcher.interceptors.request.use(
  new ResourceAttributionRequestInterceptor({ tokenStorage }),
);

const engine = new ViewEngine({
  store: new WowViewStore({ fetcher, permissions: permissionsFromRoles }),
  resources: [{ definition: orders, source: ordersSource }],
});
```

`permissions` decides which buttons are enabled, never what a write may do: the
server trusts its paths, and the CoSec gateway decides who may use which. Give
`createShared` and `changeAudience` by the role that may write
`owner/(shared)` — creating a shared view, claiming one and sharing a personal
one all need it. Left out, everything is allowed.

### A host nobody signs in to

Without a token, nothing fills the path's tenant and owner. Such a host adds an
interceptor of its own that fills defaults where the request gives none, and
names its application; with the owner `(shared)` (`SHARED_OWNER_ID`) it has
shared views and shared preferences only, so its permissions leave
`createPersonal` off.

<!-- typecheck-context
import { Fetcher } from '@ahoo-wang/fetcher';
declare const fetcher: Fetcher;
-->

```ts
import type { FetchExchange, RequestInterceptor } from '@ahoo-wang/fetcher';
import { SHARED_OWNER_ID } from '@ahoo-wang/wow-view-store';

class ConsoleDefaults implements RequestInterceptor {
  readonly name = 'ConsoleDefaults';
  readonly order = 0;

  intercept(exchange: FetchExchange): void {
    const path = exchange.ensureRequestUrlParams().path;
    path.tenantId ??= '(0)';
    path.ownerId ??= SHARED_OWNER_ID;
    exchange.ensureRequestHeaders()['CoSec-App-Id'] = 'console';
  }
}

fetcher.interceptors.request.use(new ConsoleDefaults());
```

## How it maps the port

Every route is under `/view-store/tenant/{tenantId}/owner/{ownerId}`, and **the
owner segment is the audience**: a personal view lives on the caller's own path,
a shared one on `owner/(shared)`.

| Port                               | Request                                                                                                                                                                                |
| ---------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `list`                             | The snapshot list on the caller's path and on `(shared)`, projected to the summary (`state.config.kind`, not the config), and `GET (shared)/system-views?definitionId=`, sent together |
| `get`                              | The snapshot of one id where the view was last seen; an unknown id is looked up on the caller's path, then `(shared)`, then the system views                                           |
| `create`                           | `POST …/view` on the path of its `scope`; the server generates the id                                                                                                                  |
| `save`, `rename`, `delete`         | `PUT …/view/{id}/save`, `/rename`, `DELETE …/view/{id}` on the view's path                                                                                                             |
| `changeAudience('shared')`         | `PUT …/view/{id}/share` on the view's path                                                                                                                                             |
| `changeAudience('personal')`       | `PUT …/view/{id}/claim` on the caller's own path                                                                                                                                       |
| `getPreferences`, `setPreferences` | `GET`, `PUT …/definitions/{definitionId}/preferences` on the caller's path                                                                                                             |

A list answers in the port's order — the system views, then the shared views,
then the caller's, each audience oldest first (the server sorts it by
`firstEventTime`) — and reads at most 1,000 views of each audience, the server's
query budget: the oldest 1,000. A view past the cut is still read by its id, and
the workbench asks for one an address names before it sets it aside.

**Writes** send the port's `requestId` as `Command-Request-Id`, the `revision`
as `Command-Aggregate-Version` (`'0'` for preferences never written), and wait
for the snapshot. The answer is the view read back at the version the write
left. `share` and `delete` send `{}`, `claim` no body.

**A retry answers the first outcome.** A write the server refuses as a stale
version or a repeated request id is looked up by its request id first
(`GET …/view/requests/{requestId}`, on the path it was sent to and then the
other one): if the first attempt landed, its outcome is the answer. Only then is
a stale version `CONFLICT`, carrying the view as it is now — or `NOT_FOUND`,
when the view is gone. A lookup the server fails is no answer: on the path the
write went to, the outcome is unknown (`UNAVAILABLE`, and a retry is safe); on the
other path — `(shared)` refuses a caller without the shared role — the write is
taken as not replayed, and the refusal stands. A write that landed and was moved
on by another writer before the read back answers the view as the replay route
gives it, or, if that route cannot be asked, as it was read. Preferences have no such route: the store answers a retry
with what its own first attempt answered, and a retry whose first answer was
lost with what is stored, when that is what it wrote.

**Creating is not idempotent on the server**, which generates the id. The store
remembers the request ids of its own creates (the last 256), and a retry of one
first asks the replay route on the path of its `scope`: if the first attempt
landed, that view is the answer, and nothing is posted again. A retry from
another store instance — another tab, a reload — makes a second view.

**A view that moved.** When another tab shared a view this store remembers as
personal (or the other way round), the write is refused on the old path; the
store looks the view up again and sends the write once more to where it is.

## Errors

Every rejection is the engine's `ViewStoreError`, read by Wow's error code — the
HTTP status only when an answer carries no code the store knows:

| Server                                                                                                                                                                                                                                                                     | Port          |
| -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------- |
| `CommandExpectVersionConflict`, `EventVersionConflict`, `SourcingVersionConflict`; without a known code, 409 and 412                                                                                                                                                       | `CONFLICT`    |
| `NotFound` (also a view of another application), `IllegalAccessDeletedAggregate`; without a known code, 404 and 410                                                                                                                                                        | `NOT_FOUND`   |
| `IllegalAccessOwnerAggregate`, `IllegalAccessSpaceAggregate`, `IllegalAccessQueryScope`, `SystemViewReadOnly`, `ViewEventStreamClosed`; without a known code, 401 and 403                                                                                                  | `FORBIDDEN`   |
| `ViewInvalid`, `ViewAppRequired`, `ViewScopeRequired`, `BadRequest`, `CommandValidation`, `IllegalArgument`, `DuplicateAggregateId`, `QuerySchemaValidation`; without a known code, 400 and 422; a path variable the fetcher's interceptors never filled (nothing is sent) | `INVALID`     |
| `IllegalState`, `RequestTimeout`, `TooManyRequests`, `InternalServerError`, `QuerySchemaUnavailable`, `QuerySchemaConflict`; without a known code, any other status; no answer at all                                                                                      | `UNAVAILABLE` |
| `404` from a server with no view store at all (one released before it): a list, or a read whose every place is `404` while the server's system views are too                                                                                                               | `UNSUPPORTED` |

Every `ViewStoreError` keeps what it was read from: the request's failure as
`cause`, the server's `errorCode` as `detail.code` (so a host tells
`ViewAppRequired` from `ViewInvalid`, both `INVALID`), and on an `UNAVAILABLE`
the server answered — a 5xx, a timeout it reported, a page that is not JSON —
`reachable: true`, which the engine says as 「服务端暂时无法处理」 rather than
「无法连接服务端」.

A repeated request id (`DuplicateRequestId`) is not an error of its own: the
store looks the first attempt up (above). A path variable left unfilled is the
host's set-up — its interceptors do not fill `{tenantId}`, or `{ownerId}` on a
personal path — so it is `INVALID`, as the server's own `ViewScopeRequired` is:
the request is wrong as built, and a retry would send it the same.

A claim the server refuses because shared dashboards show the view carries those
boards' **titles** in the error's `boards`, as stored, and the view engine says
the refusal in its own words around them: a title written as a key stays a key,
said where the refusal is shown. The `message` keeps the server's words, for
logs.

`WowViewStoreErrorCodes` names the view store's own codes.

## Testing

- `pnpm --filter @ahoo-wang/wow-view-store test` — unit tests against a fake
  server, the public surface and the API report.
- The port's conformance suite runs over `WowViewStore` against a view store
  server in `typescript/integration-test` (`test/view-store/`), with the tenant
  and application isolation.

Licensed under the Apache License, Version 2.0.
