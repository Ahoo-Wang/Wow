---
title: 'wow-view-store reference'
description: 'WowViewStore, the @ahoo-wang/wow-view-store package: the view engine''s ViewStore on the Wow view store service, the reserved scopes, the error codes and every request it sends.'
---

# wow-view-store reference

::: info On npm since Wow 9.2.0
`@ahoo-wang/wow-view-store` is on npm since Wow 9.2.0, released together with [wow-view-engine](../wow-view-engine/) from the same tag and with the same version as Wow. A patch release never breaks its public surface (the exports of the entry and the error codes); a minor release may, and its release notes list every break with the steps to follow ([version ranges](../../../typescript/compatibility.md#version-ranges)). The [package README](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-store/README.md) and the [design](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/docs/design/view-store-backend.md) are the source of truth.
:::

`WowViewStore` implements the view engine's [`ViewStore` port](../wow-view-engine/#persistence) on the [view store service](https://github.com/Ahoo-Wang/Wow/tree/main/platform/view-store): saved views and preferences as two Wow aggregates, reached through the clients generated from the service's Manifest. The same package gives the view engine a dataset catalog over a Wow service and a store for a tenant's wording of its datasets.

## Entry

| Export | Role |
|---|---|
| `WowViewStore` | The store: `new WowViewStore({ fetcher, permissions?, pageSize? })`, handed to `new ViewEngine({ store })` |
| `WowViewStoreOptions` | `fetcher`, whose interceptors carry the authorization and `CoSec-App-Id`; `permissions`, which buttons are enabled; `pageSize`, the views one `list` page holds |
| `SHARED_SCOPE`, `SYSTEM_SCOPE` | The `Wow-Scope` of the reserved owners: `{ ownerId: '(shared)' }` for shared views and preferences, `{ tenantId: '(platform)', ownerId: '(system)' }` for stored system views |
| `WowViewStoreErrorCodes`, `WowViewStoreErrorCode` | The view store's own problem codes, beside Wow's, and their union type |
| `datasetCatalogOf(client)` | The view engine's dataset catalog over a `DatasetClient`: the datasets a Wow service serves (`GET /wow/dataset`) and a source for each |
| `WowDatasetWordings`, `WowDatasetWordingsOptions` | The view engine's `DatasetWordingStore` on the view store: a tenant's wording of each dataset, one per dataset and application; it takes a `fetcher` |

```ts
export interface WowViewStoreOptions {
  fetcher: Fetcher;
  permissions?: (definitionId: string) => ViewPermissions;
  pageSize?: number;
}
```

### WowViewStore {#api-WowViewStore}

The view engine's [`ViewStore`](../wow-view-engine/store#api-ViewStore) over the Wow view store: every method of the port, the optional `changeAudience` included; `permissions` is there only when the host gave `options.permissions`. The port names a view by id alone, so the store remembers where it last saw each one (a list, a read, a write); a view it has not seen is found with one read by id, which the service answers with the caller's own view of that id, else a shared one, else a system view.

- `fetcher` goes to a base URL that serves `/view-store/…`: the gateway in front of the view store service. Its interceptors carry who is asking, the authorization and `CoSec-App-Id`; the service takes the tenant and the owner from the principal, and the store adds `Wow-Scope` itself where a view belongs to a reserved owner. No path names a tenant or an owner.
- `permissions` decides which buttons are enabled for one definition's views, never what a write may do: the service authorizes each write. A host answers it by the roles the caller holds: `createShared` and `changeAudience` by the role that writes the owner `(shared)` (`view-store.shared-writer` as shipped), since creating a shared view, sharing a personal one and claiming a shared one all need it; `editSystem` by the role that writes `(system)` of `(platform)` (`view-store.system-writer`). Left out, everything is allowed but `editSystem`, which stays off unless the answer says `true`.
- `pageSize` is the most views one `list` page returns, 100 by default, the service's default cursor page. The system views come whole on the first page, so that page may hold more.

```ts
export declare class WowViewStore implements ViewStore {
  constructor(options: WowViewStoreOptions);
  changeAudience(id: string, audience: ViewAudience, revision: string, context: WriteOptions): Promise<ViewInstance>;
  create(input: Omit<ViewInstance, 'id' | 'revision'>, context: WriteOptions): Promise<ViewInstance>;
  delete(id: string, revision: string, context: WriteOptions): Promise<void>;
  get(id: string, options?: ReadOptions): Promise<ViewInstance>;
  getPreferences(definitionId: string, options?: ReadOptions): Promise<ViewPreferences>;
  list(definitionId: string, options?: ViewListOptions): Promise<ViewListPage>;
  readonly permissions?: (definitionId: string) => ViewPermissions;
  rename(id: string, title: string, revision: string, context: WriteOptions): Promise<ViewInstance>;
  save(id: string, config: ViewConfig, revision: string, context: WriteOptions): Promise<ViewInstance>;
  setPreferences(definitionId: string, preferences: ViewPreferences, context: WriteOptions): Promise<ViewPreferences>;
}
```

### The reserved scopes {#api-SHARED_SCOPE}

A personal view belongs to the caller, so a request for it names no owner. A shared view belongs to the reserved owner `(shared)`, and a stored system view to `(system)` of the tenant `(platform)`, global whatever the caller's tenant. The store acts as those owners by sending `Wow-Scope`, `SHARED_SCOPE` or `SYSTEM_SCOPE`, which the service admits only for a caller holding the role that writes them. The owner ids themselves are the view store contract's and stay internal to the package; a host needs the two scopes only to address those owners through a Wow client of its own.

```ts
export declare const SHARED_SCOPE: TargetScope;
export declare const SYSTEM_SCOPE: TargetScope;
```

### Error codes {#api-WowViewStoreErrorCodes}

`WowViewStoreErrorCodes` are the codes of the view store's own problems, generated from its contract; a `ViewStoreError` carries the code the service answered as `detail.code`. They are named apart from the engine's `ViewStoreErrorCode`, the port's codes:

```ts
export declare const WowViewStoreErrorCodes: {
  readonly APP_REQUIRED: 'view-store.app-required';
  readonly INVALID: 'view-store.invalid';
  readonly NOT_FOUND: 'view-store.not-found';
  readonly OWNER_REQUIRED: 'view-store.owner-required';
  readonly REFERENCED_BY_SHARED_DASHBOARD: 'view-store.referenced-by-shared-dashboard';
  readonly SYSTEM_VIEW_READ_ONLY: 'view-store.system-view-read-only';
};
export type WowViewStoreErrorCode = (typeof WowViewStoreErrorCodes)[keyof typeof WowViewStoreErrorCodes];
```

| Code | Status | When |
|---|---|---|
| `view-store.app-required` | 400 | The request's scope has no application: behind the CoSec gateway, the `CoSec-App-Id` it forwarded |
| `view-store.invalid` | 400 | The write is not valid: its config, title, preferences or wording |
| `view-store.owner-required` | 400 | The write has no owner: a view is saved by a user, by `(shared)`, or by `(system)` under `(platform)` |
| `view-store.not-found` | 404 | The aggregate belongs to another application, which reads as none |
| `view-store.referenced-by-shared-dashboard` | 409 | A claim of a view a shared dashboard shows, which stays shared; the problem names the boards in `boards` |
| `view-store.system-view-read-only` | 403 | A share or a claim of a system view, which never moves audience |

## Contract

| Rule | Behavior |
|---|---|
| Who is asking | The store takes no tenant, user or application. The fetcher's interceptors send the authorization and `CoSec-App-Id`, and the service reads the tenant and the owner from the principal; a host nobody signs in to sends a caller through an interceptor of its own ([Saved Views](../../../typescript/view-engine/storage.md#wowviewstore-views-on-a-wow-service)) |
| Audience | The owner is the audience: a personal view is the caller's own, a shared one the owner `(shared)`'s. "Make shared" sends `share-view` as the caller, the view's owner; "Make personal" sends `claim-view` as `(shared)`, which makes the caller the owner. Either moves the view in place, its id kept, and waits for the service's `ViewAudienceSaga` to move it |
| Writes | `Wow-Request-Id` is the port's `requestId` and `If-Match` its `revision` (`"0"` for preferences never written); each waits for the snapshot and answers the view read back at the version it left |
| Retries | The service deduplicates every write by its request id, creates included, so a retry from any tab or store is answered with the first attempt's outcome. A stale revision is `CONFLICT`, carrying the view as it is now |
| Errors | By the problem's `code` onto `CONFLICT`, `NOT_FOUND`, `FORBIDDEN`, `INVALID` and `UNAVAILABLE`; by the HTTP status only when no known code came back; `UNSUPPORTED` when the service does not serve the route. The error keeps the code as `detail.code` |
| List order | System views, then shared, then personal, each audience oldest first; a page at a time, `pageSize` views a page, with one cursor across the audiences |
| System views | Configured ones are read-only; stored ones carry `stored: true` and are created (published as a copy), saved, renamed and deleted as `(system)` of `(platform)`, never shared or claimed. Their `revision` is a content hash; the store sends the version it read beside it. The engine's `editSystem` is the host's to give, off when unsaid |
| Shared boards | A view a shared dashboard shows stays shared: the claim is `INVALID`, its `boards` the boards' titles as stored, which the view engine says in its own words |

## Requests {#requests}

How each method of the port becomes requests to the view store service. Commands go through the clients generated from its Manifest, so their routes are the Manifest's.

| Port | Request |
|---|---|
| `list` | `GET /view-store/system-views?definitionId=` for the system views, whole, then the `view` snapshot model's cursor query for the shared views (`Wow-Scope: owner="(shared)"`) and the caller's own, each filtered by the definition and sorted by `firstEventTime` and then the id |
| `get` | `GET /view-store/views/{id}`: the caller's own view of that id, else a shared one, else a system view |
| `create` | `POST /view-store/view/create-view`, sent as the owner of the view's `scope`; the service generates the id |
| `save`, `rename` | `POST /view-store/view/{id}/save-view`, `rename-view`, sent as the view's owner |
| `delete` | `DELETE /view-store/view/{id}`, the lifecycle command, sent as the view's owner |
| `changeAudience('shared')` | `POST /view-store/view/{id}/share-view` with `{}`, sent as the caller |
| `changeAudience('personal')` | `POST /view-store/view/{id}/claim-view` with `{}`, sent as `(shared)` |
| `getPreferences` | A single query of the `view_preferences` snapshots by `state.definitionId`; none written reads as the port's empty preferences at revision `'0'` |
| `setPreferences` | `POST /view-store/view_preferences/set-view-preferences`; the service derives their id from the owner, the application and the definition |

The answer of a view write is read back with `GET /view-store/view/{id}/state?version=` at the version the write left.

**A list a page at a time.** One call of `list` reads the system views, then the shared views, then the caller's, and reads on into the next audience while its page has room, so a definition with few views answers in one page. Its `nextCursor` names the audience being read and that audience's own cursor; the engine passes it back as an opaque string.

**A view that moved.** When another tab shared a view this store remembers as personal (or the other way round), the write is refused from the old owner; the store looks the view up again and sends the write once more from where the view now is. A move to the audience a view already has changes nothing: the store answers the view as it is, once its revision matches.

**System views.** The service's system views are configured ones, read-only, and stored ones (`source: 'stored'`), which `WowViewStore` lists and reads with `stored: true` on the summary and the instance. `create({ scope: 'system', … })` publishes one (a copy: the source view stays), and `save`, `rename` and `delete` edit and unpublish it; `changeAudience` and any write to a configured or code system view are `FORBIDDEN` before anything is sent. A system view's `revision` is a hash of its content, as the engine needs for its dirty baseline, while a write expects the aggregate version: the store remembers the version each read gave beside the revision, sends that version, and reads the view again first when it holds a revision it has no version for. A revision other than the view's is `CONFLICT` with the view as it is, and nothing is sent.

## Error mapping {#errors}

Every rejection is the engine's `ViewStoreError`, read by the problem's code, and by the HTTP status only when an answer carries no code the store knows:

| Service | Port |
|---|---|
| `wow.aggregate.version-mismatch`, `wow.command.request-id-reused`; a rejection without a problem; without a known code, 409 and 412 | `CONFLICT` |
| `wow.aggregate.not-found`, `wow.aggregate.deleted`, `wow.query.not-found`, `view-store.not-found`; without a known code, 404 with a code, and 410 | `NOT_FOUND` |
| `wow.auth.unauthenticated`, `wow.auth.forbidden`, `wow.aggregate.forbidden`, `view-store.system-view-read-only`; without a known code, 401 and 403 | `FORBIDDEN` |
| `view-store.invalid`, `view-store.app-required`, `view-store.owner-required`, `view-store.referenced-by-shared-dashboard`, `wow.aggregate.exists`, `wow.command.malformed`, `wow.command.invalid`, `wow.command.unhandled`, `wow.wait.invalid`, `wow.query.malformed`, `wow.query.invalid`; without a known code, 400 and 422 | `INVALID` |
| Any other problem the service says may be retried (its `retry` is not `none`); without a known code, any other status; no answer at all | `UNAVAILABLE` |
| `404` without a code: a route the service does not serve | `UNSUPPORTED` |

Every `ViewStoreError` keeps what it was read from: the request's failure as `cause` and the problem's code as `detail.code`, so a host tells `view-store.app-required` from `view-store.invalid`, both `INVALID`.

A claim the service refuses because shared dashboards show the view carries those boards' **titles** in the error's `boards`, as stored, and the view engine says the refusal in its own words around them: a title written as a key stays a key, said where the refusal is shown. The `message` keeps the service's words, for logs.

## Hosts and the gateway

How a host wires `WowViewStore`, gives its `permissions` and serves a page nobody signs in to is in [Saved Views](../../../typescript/view-engine/storage.md#wowviewstore-views-on-a-wow-service). The gateway in front of the service authenticates every request, and the service authorizes each write by the caller's roles; the deployment, its roles and its path rules are in the [View Store](https://github.com/Ahoo-Wang/Wow/blob/main/deploy/view-store/README.md) page.

## Source

[typescript/wow-view-store](https://github.com/Ahoo-Wang/Wow/tree/main/typescript/wow-view-store) · [README](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-store/README.md) · [view store service](https://github.com/Ahoo-Wang/Wow/tree/main/platform/view-store)
