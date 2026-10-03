---
title: 'wow-view-store reference'
description: 'WowViewStore, the @ahoo-wang/wow-view-store package: the view engine''s ViewStore on the Wow view store server.'
---

# wow-view-store reference

::: info On npm since Wow 9.2.0
`@ahoo-wang/wow-view-store` is on npm since Wow 9.2.0, released together with [wow-view-engine](../wow-view-engine/) from the same tag and with the same version as Wow. A patch release never breaks its public surface (the exports of the entry and the error codes); a minor release may, and its release notes list every break with the steps to follow ([version ranges](../../../guide/typescript/compatibility.md#version-ranges)). The [package README](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-store/README.md) and the [design](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/docs/design/view-store-backend.md) are the source of truth.
:::

`WowViewStore` implements the view engine's [`ViewStore` port](../wow-view-engine/#persistence) on the [view store](https://github.com/Ahoo-Wang/Wow/blob/main/view-store/README.md): saved views and preferences as two Wow aggregates, served by the standalone `wow-view-store-server` or by a Wow service that embeds `wow-view-store-starter`.

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

## Hosts

The example server and the compensation service embed `wow-view-store-starter`; the compensation console keeps its views there. Nobody signs in to the console, so it adds a request interceptor of its own that fills the tenant `(platform)`, the owner `(shared)` and its application (`compensation-dashboard`) where a request names none: every view and preference it keeps is shared, and its permissions turn personal views off. A host that embeds the starter gives the view store's Kafka topics a prefix of their own with `wow.view-store.kafka.topic-prefix`, which leaves the host's own topics as they are.

## CoSec gateway rules

The server does not authenticate: it takes the tenant, the owner and the application as the request names them, so every deployment runs behind the CoSec gateway, whose path rules tie each to the token:

| Path | Allow when |
|---|---|
| `/view-store/tenant/{tenantId}/owner/{ownerId}/**`, except `…/view/{id}/claim` and `…/view/{id}/share` | `{tenantId}` is the token's tenant and `{ownerId}` is its `sub` |
| `/view-store/tenant/{tenantId}/owner/(shared)/**`, reads | `{tenantId}` is the token's tenant |
| `/view-store/tenant/{tenantId}/owner/(shared)/**`, writes | the token's tenant, and the role that may write shared views |
| `PUT /view-store/tenant/{tenantId}/owner/{ownerId}/view/{id}/claim`, `…/share` | the token's tenant, `{ownerId}` is its `sub`, **and** the role that may write shared views |
| `/view-store/tenant/(platform)/owner/(system)/**` | the caller administers the system views (global, so any tenant). Without this rule anyone who reaches the view store writes every tenant's system views |

A claim takes a shared view out of everyone's list and a share publishes a personal one into it, so the personal rule must not admit either on its own: leave `…/view/{id}/claim` and `…/view/{id}/share` out of that rule, and only the claim-and-share rule decides (otherwise anyone could publish a shared view by creating a personal one and sharing it). `CoSec-App-Id` is authenticated by CoSec and keeps applications apart; a host gives `permissions` by the same role the gateway checks: `createShared` and `changeAudience` need the shared-write role. The full rules, with the reasons, are in the [view store README](https://github.com/Ahoo-Wang/Wow/blob/main/view-store/README.md#cosec-gateway-rules).

## Source

[typescript/wow-view-store](https://github.com/Ahoo-Wang/Wow/tree/main/typescript/wow-view-store) · [README](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-store/README.md) · [view store server](https://github.com/Ahoo-Wang/Wow/blob/main/view-store/README.md)
