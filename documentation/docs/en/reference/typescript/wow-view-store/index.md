---
title: 'wow-view-store reference'
description: 'WowViewStore, the @ahoo-wang/wow-view-store package: the view engine''s ViewStore on the Wow view store server.'
---

# wow-view-store reference

::: info On npm since Wow 9.2.0
`@ahoo-wang/wow-view-store` is on npm since Wow 9.2.0, released together with [wow-view-engine](../wow-view-engine/) from the same tag and with the same version as Wow. A patch release never breaks its public surface (the exports of the entry and the error codes); a minor release may, and its release notes list every break with the steps to follow ([version ranges](../../../guide/typescript/compatibility.md#version-ranges)). The [package README](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-store/README.md) and the [design](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/docs/design/view-store-backend.md) are the source of truth.
:::

`WowViewStore` implements the view engine's [`ViewStore` port](../wow-view-engine/#persistence) on the [view store](../../../guide/extensions/view-store.md): saved views and preferences as two Wow aggregates, served by the standalone `wow-view-store-server` or by a Wow service that embeds `wow-view-store-starter`.

## Entry

| Export | Role |
|---|---|
| `WowViewStore` | The store: `new WowViewStore({ fetcher, permissions? })`, handed to `new ViewEngine({ store })` |
| `WowViewStoreOptions` | `fetcher`: the fetcher whose interceptors carry the tenant, the owner and the application; `permissions`: which buttons are enabled |
| `SHARED_OWNER_ID` | `(shared)`, the owner segment of shared views and shared preferences |
| `SYSTEM_OWNER_ID`, `SYSTEM_TENANT_ID` | `(system)` and `(platform)`: the one path stored system views are written on, whatever the caller's tenant |
| `WowViewStoreErrorCodes` | The view store's own error codes, beside Wow's |

```ts
export interface WowViewStoreOptions {
  fetcher: Fetcher;
  permissions?: (definitionId: string) => ViewPermissions;
}
```

### WowViewStore {#api-WowViewStore}

The view engine's [`ViewStore`](../wow-view-engine/store#api-ViewStore) over the Wow view store: every method of the port, the optional `changeAudience` included; `permissions` is there only when the host gave `options.permissions`. The port names a view by id alone, so the store remembers where it last saw each one (a list, a read, a write) and looks an unknown id up on the personal path, the shared path and the server's system views, in that order.

- `fetcher` goes to a base URL that serves `/view-store/…`: the CoSec gateway in front of the view store server, or the service that embeds the starter. Its interceptors carry who is asking; the store never does.
- `permissions` decides which buttons are enabled for one definition's views; everything is allowed when left out, as the port reads a store without `permissions`. The server does not authorize, the CoSec gateway does; a host answers this by the roles it holds there — `changeAudience` by the role that may write `owner/(shared)`, which claiming a view needs.

```ts
export declare class WowViewStore implements ViewStore {
  constructor(options: WowViewStoreOptions);
  changeAudience(id: string, audience: ViewAudience, revision: string, context: WriteContext): Promise<ViewInstance>;
  create(input: Omit<ViewInstance, 'id' | 'revision'>, context: WriteContext): Promise<ViewInstance>;
  delete(id: string, revision: string, context: WriteContext): Promise<void>;
  get(id: string, signal?: AbortSignal): Promise<ViewInstance>;
  getPreferences(definitionId: string, signal?: AbortSignal): Promise<ViewPreferences>;
  list(definitionId: string, signal?: AbortSignal): Promise<ViewInstanceSummary[]>;
  readonly permissions?: (definitionId: string) => ViewPermissions;
  rename(id: string, title: string, revision: string, context: WriteContext): Promise<ViewInstance>;
  save(id: string, config: ViewConfig, revision: string, context: WriteContext): Promise<ViewInstance>;
  setPreferences(definitionId: string, preferences: ViewPreferences, context: WriteContext): Promise<ViewPreferences>;
}
```

`WowViewStoreErrorCodes` are the server's `errorCode` strings, which a `ViewStoreError` carries as `detail.code`; they are named apart from the engine's `ViewStoreErrorCode` (the port's six codes):

```ts
export declare const WowViewStoreErrorCodes: Readonly<{
  readonly VIEW_INVALID: 'ViewInvalid';
  readonly VIEW_APP_REQUIRED: 'ViewAppRequired';
  readonly SYSTEM_VIEW_READ_ONLY: 'SystemViewReadOnly';
  readonly VIEW_SCOPE_REQUIRED: 'ViewScopeRequired';
  readonly VIEW_EVENT_STREAM_CLOSED: 'ViewEventStreamClosed';
}>;
```

## Contract

| Rule | Behavior |
|---|---|
| Who is asking | The store takes no tenant, user or application. The fetcher's interceptors fill the path's `{tenantId}` and, on a personal path, `{ownerId}` (fetcher-cosec's `ResourceAttributionRequestInterceptor`, from the token) and send `CoSec-App-Id`; a host nobody signs in to fills defaults with an interceptor of its own |
| Audience | The owner segment of the path: a personal view on the caller's path, a shared one on `owner/(shared)`. "Make shared" is `share` on the view's path, "Make personal" is `claim` on the caller's own path |
| Writes | `Command-Request-Id` is the port's `requestId`, `Command-Aggregate-Version` its `revision`; each waits for the snapshot and answers the view read back |
| Retries | A write refused as a stale version or a repeated request id is looked up by its request id first, and a retry answers what its first attempt wrote. The server does not deduplicate a create (it generates the id); a store asks the replay route before posting a retry of its own create again, so only a retry from another store makes a second view |
| Errors | By Wow's error code onto `CONFLICT`, `NOT_FOUND`, `FORBIDDEN`, `INVALID` and `UNAVAILABLE`; the HTTP status only when no known code came back; `UNSUPPORTED` from a server with no view store. The error keeps the server's code as `detail.code`, and an `UNAVAILABLE` the server answered says `reachable` |
| List order | System views, then shared, then personal, each audience oldest first; at most 1,000 of each audience |
| System views | Configured ones are read-only; stored ones (global, under `tenant/(platform)/owner/(system)`) carry `stored: true` and are created (published as a copy), saved, renamed and deleted there, never shared or claimed. Their `revision` is a content hash; the store sends the version it read beside it. The engine's `editSystem` is the host's to give, off when unsaid |
| Shared boards | A view a shared dashboard shows stays shared: the claim is `INVALID`, its `boards` the boards' titles as stored, which the view engine says in its own words |

## Hosts and the gateway

How a host wires `WowViewStore`, gives its `permissions` and serves a page nobody signs in to is in [Where Views Live](../../../guide/typescript/view-engine-storage.md#wowviewstore-views-on-a-wow-service). The server does not authenticate, so every deployment runs behind the CoSec gateway; its path rules, including the one that keeps the system views to platform administrators, are in the [View Store](../../../guide/extensions/view-store.md#security-model) page.

## Source

[typescript/wow-view-store](https://github.com/Ahoo-Wang/Wow/tree/main/typescript/wow-view-store) · [README](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-store/README.md) · [view store server](https://github.com/Ahoo-Wang/Wow/tree/main/view-store)
