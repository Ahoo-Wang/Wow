# Storage

Saved views and preferences live behind the `ViewStore` port; definitions and system views are code and never stored. Reference: README [Persistence](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/README.md#persistence), the [`@ahoo-wang/wow-view-store` README](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-store/README.md), and the server's [view store README](https://github.com/Ahoo-Wang/Wow/blob/main/view-store/README.md).

## Choose by who must see a saved view

| Store | For | Note |
| --- | --- | --- |
| `MemoryViewStore` | Tests, demos, a query-only page | Forgets on reload |
| `new MemoryViewStore({ snapshot: localStorageSnapshot(key) })` | Development, a single-user tool | One browser's views; another tab's write is merged or a `CONFLICT` |
| `WowViewStore` (`@ahoo-wang/wow-view-store`) | Everyone else: personal and shared views kept by a Wow service | The service embeds `wow-view-store-starter`, or the standalone `wow-view-store-server` runs behind the CoSec gateway |

Do not write a `ViewStore` of your own for a Wow backend. Implement the port only for another backend, against the two rules the README states (optimistic `revision`, idempotent `requestId`); the engine repository's conformance suite (`typescript/wow-view-engine/test/conformance/`, not published) lists the cases a store must pass.

```ts
import {
  localStorageSnapshot,
  MemoryViewStore,
} from '@ahoo-wang/wow-view-engine';

export const devStore = new MemoryViewStore({
  snapshot: localStorageSnapshot('my-app:views'),
});
```

## WowViewStore

`WowViewStore` takes a fetcher and, optionally, the permissions that enable buttons. It takes **no tenant, owner, user or application**: the fetcher's interceptors carry who is asking, as for every other Wow request of the host. Routes are `/view-store/tenant/{tenantId}/owner/{ownerId}/…`, and the owner segment is the audience: a personal view on the caller's own path, a shared one on `owner/(shared)`.

<!-- typecheck: file=viewStore.ts -->
<!-- typecheck-context
declare function hasRole(role: string): boolean;
-->

```ts
import { Fetcher } from '@ahoo-wang/fetcher';
import { CoSecConfigurer } from '@ahoo-wang/fetcher-cosec';
import {
  isSystemInstanceId,
  type ViewPermissions,
} from '@ahoo-wang/wow-view-engine';
import { WowViewStore } from '@ahoo-wang/wow-view-store';

// The gateway in front of the view store. CoSec adds the token and
// `CoSec-App-Id`, and fills the path's {tenantId} and {ownerId} from it.
const fetcher = new Fetcher({ baseURL: 'https://api.example.com' });
new CoSecConfigurer({ appId: 'my-app' }).applyTo(fetcher);

/** The same role the gateway checks for writing `owner/(shared)`. */
const SHARED_WRITER = 'view-store:shared-writer';

function permissions(): ViewPermissions {
  const sharedWriter = hasRole(SHARED_WRITER);
  return {
    createPersonal: true,
    createShared: sharedWriter,
    reorder: true,
    setDefault: true,
    instance: id => {
      const editable = !isSystemInstanceId(id);
      return {
        save: editable,
        rename: editable,
        delete: editable,
        // Claiming a shared view and sharing a personal one both need it.
        changeAudience: editable && sharedWriter,
      };
    },
  };
}

export const store = new WowViewStore({ fetcher, permissions });
```

- `permissions` only enables buttons; the gateway decides. Give `createShared` and `changeAudience` by the role that may write `owner/(shared)`; whether a shared view is the reader's to save is the host's rule too (`instance(id)`). Left out, everything is enabled.
- The engine already handles what the store reports: conflicts (reload, overwrite or save as), an unknown outcome (retry with the same request id), a refused claim while a shared board shows the view. Do not wrap them.

### A host nobody signs in to

With no token, nothing fills the tenant and owner. Add an interceptor that fills defaults only where the request has none, after CoSec's resource attribution (so a token, if one is ever put in front, still wins), and name the application. With the owner `(shared)` the host has shared views and shared preferences only, so its permissions leave `createPersonal` off and `changeAudience` false:

<!-- typecheck: file=consoleDefaults.ts -->

```ts
import type { FetchExchange, RequestInterceptor } from '@ahoo-wang/fetcher';
import {
  CoSecHeaders,
  RESOURCE_ATTRIBUTION_REQUEST_INTERCEPTOR_ORDER,
} from '@ahoo-wang/fetcher-cosec';
import { SHARED_OWNER_ID } from '@ahoo-wang/wow-view-store';

export class ViewStoreDefaults implements RequestInterceptor {
  readonly name = 'ViewStoreDefaults';
  readonly order = RESOURCE_ATTRIBUTION_REQUEST_INTERCEPTOR_ORDER + 1;

  intercept(exchange: FetchExchange): void {
    const path = exchange.ensureRequestUrlParams().path;
    path.tenantId ??= '(0)';
    path.ownerId ??= SHARED_OWNER_ID;
    exchange.ensureRequestHeaders()[CoSecHeaders.APP_ID] ??= 'my-console';
  }
}
```

The compensation console is this case (`compensation/dashboard/src/views/viewStore.ts`).

## The CoSec gateway rules

The view store does not authenticate: it trusts the tenant and owner of every path and the `CoSec-App-Id` header. Every deployment runs behind the CoSec gateway; hand whoever configures it the server README's [CoSec gateway rules](https://github.com/Ahoo-Wang/Wow/blob/main/view-store/README.md#cosec-gateway-rules) table, the authority on paths and methods. What a host author must not get wrong:

- A personal path (`owner/{ownerId}`) needs the token's tenant and `sub == {ownerId}`; a shared path (`owner/(shared)`) reads with the tenant and writes only with the shared-write role.
- **Claim and share need the tenant, `sub == {ownerId}` and the shared-write role**, and both paths are excluded from the personal rule: otherwise anyone could publish by creating a personal view and sharing it.
- **`(shared)` is never a `sub`:** issue no token whose `sub` is `(shared)` or holds parentheses.
- Reject a request whose `CoSec-App-Id` is missing or names an application the token is not for. A host nobody signs in to is admitted by network or a service token, as the rest of that host is; the server is never reachable without the gateway.
