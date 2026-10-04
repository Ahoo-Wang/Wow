# Storage

Saved views and preferences live behind the `ViewStore` port: personal and shared views, **stored system views** (since 9.2.0), and each definition's preferences (view order, default view, auto-run, last tab). Definitions and the system views they declare in code (`system:` ids) are code and never stored. In depth: [Where Views Live](https://wow.ahoo.me/guide/typescript/view-engine-storage.html) (stores, permissions, system views, errors, the conformance suite) and [View Store](https://wow.ahoo.me/guide/extensions/view-store.html) (the server: starter or standalone, configured system views, the gateway rules).

## Choose by who must see a saved view

| Store | For | Note |
| --- | --- | --- |
| `MemoryViewStore` | Tests, demos, a query-only page | Forgets on reload |
| `new MemoryViewStore({ snapshot: localStorageSnapshot(key) })` | Development, a single-user tool | One browser's views; another tab's write is merged or a `CONFLICT` |
| `WowViewStore` (`@ahoo-wang/wow-view-store`) | Everyone else: personal, shared and system views kept by a Wow service | The service embeds `wow-view-store-starter`, or the standalone `wow-view-store-server` runs behind the CoSec gateway |

Do not write a `ViewStore` of your own for a Wow backend: `WowViewStore` already handles replays, views that moved audience, system-view versions and error codes. Implement the port only for another backend, against its two rules (a stale `revision` throws `CONFLICT` carrying the current instance; a repeated `requestId` answers the first outcome) and the conformance suite (copied from `typescript/wow-view-engine/test/conformance/`, not published).

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

`WowViewStore` takes a fetcher and, optionally, the permissions that enable buttons. It takes **no tenant, owner, user or application**: the fetcher's interceptors carry who is asking, as for every other Wow request of the host. Every route is `/view-store/tenant/{tenantId}/owner/{ownerId}/…`, and **the path is the audience**:

| View | Path |
| --- | --- |
| Personal | The caller's own `owner/{ownerId}`, filled from the token |
| Shared | `owner/(shared)` (`SHARED_OWNER_ID`) |
| Stored system view | `tenant/(platform)/owner/(system)` (`SYSTEM_TENANT_ID`, `SYSTEM_OWNER_ID`), whatever the caller's tenant: system views are global |

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
const fetcher = new Fetcher({ baseURL: 'https://api.example.com', timeout: 60_000 });
new CoSecConfigurer({ appId: 'my-app' }).applyTo(fetcher);

/** The same role the gateway checks for writing `owner/(shared)`. */
const SHARED_WRITER = 'view-store:shared-writer';
/** The same role the gateway checks for `tenant/(platform)/owner/(system)`. */
const SYSTEM_VIEW_ADMIN = 'view-store:system-admin';

function permissions(): ViewPermissions {
  const sharedWriter = hasRole(SHARED_WRITER);
  return {
    createPersonal: true,
    createShared: sharedWriter,
    reorder: true,
    setDefault: true,
    // Publish, edit and unpublish stored system views. Left out: refused.
    editSystem: hasRole(SYSTEM_VIEW_ADMIN),
    instance: id => {
      // A system view declared in code (`system:`) is always read-only.
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

- **`permissions` only enables buttons; the gateway decides.** Give each member by the same role the gateway checks, or a button is enabled whose write the gateway refuses (or the other way round). `createShared` and `changeAudience` need the shared-write role, `editSystem` the system-view administrator's.
- **Silence:** `createPersonal`, `createShared`, `reorder`, `setDefault` and `instance(id)`'s `save`, `rename`, `delete` are required. Only `changeAudience` (absent: allowed), **`editSystem` (absent: `false`)** and `permissions` itself (absent: everything but `editSystem`) may be left out. A system view reaches every tenant and every reader, so only a host that says so lets anyone change one.
- The engine already handles what the store reports: `CONFLICT` (reload, overwrite or save as), `UNAVAILABLE` (retry with the same request id), a refused claim while a shared board shows the view, `FORBIDDEN` on a read-only view. Do not wrap them.

## System views: three sources, one of them editable

| Source | Where | Who changes it |
| --- | --- | --- |
| Code | The definition's `views`, ids `system:…` (`wow-view-definition`) | Nobody: shipped with the front end, never sent to the server |
| Configured | The server's `wow.view-store.system-views`, or a `SystemViewProvider` bean | Nobody at run time: a change needs a restart |
| Stored | View aggregates under `tenant/(platform)/owner/(system)` | Whoever the host grants `editSystem` and the gateway admits, without a restart |

- `WowViewStore` marks a stored system view `stored: true` on its summary and instance; the engine sends no such flag. A configured or code system view has no `stored` and stays read-only whatever `editSystem` says: any write to it is `FORBIDDEN` before anything is sent.
- **Publishing is a copy.** "Publish as system view" on a personal or shared view creates a stored system view from its title and saved config (`create({ scope: 'system', … })`); the source view stays. Save, rename and delete edit and unpublish it; changing its audience is always refused.
- A **system board** references system views only: publishing or saving one whose panel shows or opens a personal or shared view is refused (`dashboard.system.non-system-panels`), since shared views belong to one tenant.
- Readers need no gateway rule of their own: clients list system views through the `system-views` route under their own tenant's `owner/(shared)` path.

A test or a demo seeds a stored system view in `MemoryViewStore`; without `stored: true` it would be a configured, read-only one:

<!-- typecheck: file=memoryStore.ts -->
<!-- typecheck-context
import type { RecordViewConfig } from '@ahoo-wang/wow-view-engine';
declare const openOrders: RecordViewConfig;
declare function isAdmin(): boolean;
-->

```ts
import { MemoryViewStore } from '@ahoo-wang/wow-view-engine';

export const memoryStore = new MemoryViewStore({
  instances: [
    {
      id: 'orders-open',
      definitionId: 'orders',
      title: 'To ship',
      scope: 'system',
      stored: true,
      revision: '1',
      config: openOrders,
    },
  ],
  permissions: () => ({
    createPersonal: true,
    createShared: true,
    reorder: true,
    setDefault: true,
    editSystem: isAdmin(),
    instance: () => ({ save: true, rename: true, delete: true }),
  }),
});
```

## A host nobody signs in to

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

The view store does not authenticate: identity is the path (tenant and owner) plus the `CoSec-App-Id` header, and the server has no setting for who may write. Every deployment runs behind the CoSec gateway; hand whoever configures it the [gateway rules](https://wow.ahoo.me/guide/extensions/view-store.html#gateway-rules), the authority on paths and methods. What a host author must not get wrong:

- A personal path (`owner/{ownerId}`) needs the token's tenant and `sub == {ownerId}`; a shared path (`owner/(shared)`) reads with the tenant and writes only with the shared-write role.
- **Claim and share need the tenant, `sub == {ownerId}` and the shared-write role**, and both paths are excluded from the personal rule: otherwise anyone could publish by creating a personal view and sharing it.
- **The system views' path (tenant `(platform)`, owner `(system)`, and all below it) is admitted to system-view administrators only** (any tenant: the views are global), and the personal rule must not admit it. Without that rule, anyone who reaches the view store writes the system views of every tenant. The server refuses any other spelling of the path (percent-encoded, another case), so a rule on the literal path sees every system-view write.
- **`(shared)` and `(system)` are never a `sub`:** issue no token whose `sub` is either or holds parentheses.
- Reject a request whose `CoSec-App-Id` is missing or names an application the token is not for. A host nobody signs in to is admitted by network or a service token, as the rest of that host is; the server is never reachable without the gateway.
