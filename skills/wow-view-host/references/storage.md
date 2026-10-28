# Storage

Saved views and preferences live behind the `ViewStore` port: personal and shared views, **stored system views**, and each definition's preferences (view order, default view, auto-run, last tab). Definitions and the system views they declare in code (`system:` ids) are code and never stored. In depth: [Saved views](https://wow.ahoo.me/typescript/view-engine/storage) (stores, permissions, system views, errors, the conformance suite).

## Choose by who must see a saved view

| Store | For | Note |
| --- | --- | --- |
| `MemoryViewStore` | Tests, demos, a query-only page | Forgets on reload |
| `new MemoryViewStore({ snapshot: localStorageSnapshot(key) })` | Development, a single-user tool, an operator's own console | One browser's views; another tab's write is merged or a `CONFLICT`. The ops console keeps its operators' views this way: the ops service hosts no view store |
| `WowViewStore` (`@ahoo-wang/wow-view-store`) | Everyone else: personal, shared and system views kept for a team | On the V10 view store service (`platform/view-store`, the image `wow-view-store-server`), a Wow V10 service of its own beside the business services |

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

## WowViewStore on the V10 view store service

The service keeps views and preferences as two Wow aggregates; `WowViewStore` reaches them through the clients generated from the service's Manifest. It takes a fetcher and, optionally, the permissions that enable buttons. It takes **no tenant, owner, user or application**, and no path names them: the service reads who is asking from the caller's principal, which the fetcher's interceptors carry as they do for every other Wow request of the host (a CoSec token, and `CoSec-App-Id`, the application every view, preference and wording is kept in). **The owner is the audience:**

| View | Owner | How the store reaches it |
| --- | --- | --- |
| Personal | The caller's own | As the caller |
| Shared | The reserved owner `(shared)` (`SHARED_OWNER_ID`) | The store sends `Wow-Scope` naming `(shared)` |
| Stored system view | `(system)` of the tenant `(platform)` (`SYSTEM_OWNER_ID`, `SYSTEM_TENANT_ID`), whatever the caller's tenant: system views are global | The store sends `Wow-Scope` naming both |

The host never sets `Wow-Scope` for the store; it says it itself, and the service refuses any other reserved scope (`wow.auth.forbidden`), so nobody acts as another user.

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

// The view store service, or the gateway in front of it. CoSec adds the
// token and `CoSec-App-Id`; the service reads the caller from them.
const fetcher = new Fetcher({ baseURL: 'https://api.example.com', timeout: 60_000 });
new CoSecConfigurer({ appId: 'my-app' }).applyTo(fetcher);

/** The role the service requires to write the owner `(shared)`. */
const SHARED_WRITER = 'view-store.shared-writer';
/** The role the service requires to write `(system)` of `(platform)`. */
const SYSTEM_WRITER = 'view-store.system-writer';

function permissions(): ViewPermissions {
  const sharedWriter = hasRole(SHARED_WRITER);
  return {
    createPersonal: true,
    createShared: sharedWriter,
    reorder: true,
    setDefault: true,
    // Publish, edit and unpublish stored system views. Left out: refused.
    editSystem: hasRole(SYSTEM_WRITER),
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

- **The service decides; `permissions` only enables buttons.** It authorizes every write by the caller's roles: the owner `(shared)`'s writes (a shared view's save, rename, delete and claim, sharing a personal view, the shared preferences) need `view-store.shared-writer`; the owner `(system)`'s need `view-store.system-writer`; a user writes only its own views and preferences; an owner never changes over HTTP except by sharing or claiming. The role names are the service's as shipped: a team's own role (an "ops lead") is granted these roles in the identity provider, not renamed in the host. Answer each `permissions` member by the same role the service checks, or a button is enabled whose write the service refuses (or the other way round).
- **Silence:** `createPersonal`, `createShared`, `reorder`, `setDefault` and `instance(id)`'s `save`, `rename`, `delete` are required. Only `changeAudience` (absent: allowed), **`editSystem` (absent: `false`)** and `permissions` itself (absent: everything but `editSystem`) may be left out. A system view reaches every tenant and every reader, so only a host that says so lets anyone change one.
- Every request needs the application (`CoSec-App-Id`): without it the service answers `view-store.app-required`. Views, preferences and wordings of one application are not another's.
- The engine already handles what the store reports: `CONFLICT` (reload, overwrite or save as), `UNAVAILABLE` (retry with the same request id), a refused claim while a shared board shows the view, `FORBIDDEN` on a read-only view. Do not wrap them.
- A page's total is the service's default `BOUNDED` count; the store lists at most the service's query budget of views per audience.

## System views: three sources, one of them editable

| Source | Where | Who changes it |
| --- | --- | --- |
| Code | The definition's `views`, ids `system:…` (`wow-view-definition`) | Nobody: shipped with the front end, never sent to the server |
| Configured | The service's `wow.view-store.system-views` (each with its `tenantId` and `appId`, blank for all, its `id`, `definitionId`, `title` and the engine's `config` as JSON) | Nobody at run time: a change needs a restart |
| Stored | View aggregates of the owner `(system)` in the tenant `(platform)` | Callers with `view-store.system-writer`, whom the host grants `editSystem`, without a restart |

- `WowViewStore` marks a stored system view `stored: true` on its summary and instance; the engine sends no such flag. A configured or code system view has no `stored` and stays read-only whatever `editSystem` says: any write to it is `FORBIDDEN` before anything is sent.
- **Publishing is a copy.** "Publish as system view" on a personal or shared view creates a stored system view from its title and saved config (`create({ scope: 'system', … })`); the source view stays. Save, rename and delete edit and unpublish it; changing its audience is always refused.
- A **system board** references system views only: publishing or saving one whose panel shows or opens a personal or shared view is refused (`dashboard.system.non-system-panels`), since shared views belong to one tenant.
- Readers need no role: the store lists the configured and stored system views of the caller's tenant and application through the service's `GET /view-store/system-views` route (lookups only).

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

## Dataset wordings

A tenant may reword the datasets it is served (title, record noun, field labels, order and narrowing) without a release: `WowDatasetWordings` from `@ahoo-wang/wow-view-store` keeps one wording per dataset and application on the same service, and `DatasetResources` reads it before each refresh (`wiring.md`). Writing a wording is the whole tenant's, so the service requires `view-store.shared-writer`; grant the view manager's wording tab (`editDatasetWording`) by that role.

## Running the service, and a host nobody signs in to

- In a deployment the service verifies each request's CoSec token (its `cosec` profile) and reads the tenant, the user and the roles from it. Only behind a CoSec gateway that authorizes each request's `CoSec-App-Id`, and only where nothing else reaches the service, does it trust the injected application (`wow.cosec.trust-injected-app`); otherwise the application is the one CoSec authorized.
- Without an identity provider the service starts in a development profile alone (`dev`, of `wow.dev-profiles`), for a local run: it then reads the caller from `Wow-User`, `Wow-Owner`, `Wow-Tenant`, `Wow-Roles` and the application from `Wow-App` instead; elsewhere it refuses to start.
- A caller with no user is anonymous: it reads only the reserved owners' views and writes nothing. So a host nobody signs in to either keeps its views in the browser (`localStorageSnapshot`, as the ops console does), or is put behind a gateway that sends a service identity holding the roles above; its permissions then leave `createPersonal` off.
