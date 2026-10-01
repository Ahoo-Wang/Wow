---
title: 'wow-view-store reference'
description: 'WowViewStore, the unreleased @ahoo-wang/wow-view-store package: the view engine''s ViewStore on the Wow view store server.'
---

# wow-view-store reference

::: warning Not released
`@ahoo-wang/wow-view-store` has not been published to npm and carries no compatibility promise; it is released with [wow-view-engine](../wow-view-engine/). The [package README](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-store/README.md) and the [design](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/docs/design/view-store-backend.md) are the source of truth until then.
:::

`WowViewStore` implements the view engine's [`ViewStore` port](../wow-view-engine/#persistence) on the [view store](https://github.com/Ahoo-Wang/Wow/blob/main/view-store/README.md): saved views and preferences as two Wow aggregates, served by the standalone `wow-view-store-server` or by a Wow service that embeds `wow-view-store-starter`.

## Entry

| Export | Role |
|---|---|
| `WowViewStore` | The store: `new WowViewStore({ fetcher, permissions? })`, handed to `new ViewEngine({ store })` |
| `WowViewStoreOptions` | `fetcher`: the fetcher whose interceptors carry the tenant, the owner and the application; `permissions`: which buttons are enabled |
| `SHARED_OWNER_ID` | `(shared)`, the owner segment of shared views and shared preferences |
| `ViewStoreErrorCodes` | The view store's own error codes, beside Wow's |

```ts
export interface WowViewStoreOptions {
  fetcher: Fetcher;
  permissions?: (definitionId: string) => ViewPermissions;
}
```

## Contract

| Rule | Behavior |
|---|---|
| Who is asking | The store takes no tenant, user or application. The fetcher's interceptors fill the path's `{tenantId}` and, on a personal path, `{ownerId}` (fetcher-cosec's `ResourceAttributionRequestInterceptor`, from the token) and send `CoSec-App-Id`; a host nobody signs in to fills defaults with an interceptor of its own |
| Audience | The owner segment of the path: a personal view on the caller's path, a shared one on `owner/(shared)`. 设为共享 is `share` on the view's path, 设为个人 is `claim` on the caller's own path |
| Writes | `Command-Request-Id` is the port's `requestId`, `Command-Aggregate-Version` its `revision`; each waits for the snapshot and answers the view read back |
| Retries | A write refused as a stale version or a repeated request id is looked up by its request id first, and a retry answers what its first attempt wrote. The server does not deduplicate a create (it generates the id); a store asks the replay route before posting a retry of its own create again, so only a retry from another store makes a second view |
| Errors | By Wow's error code onto `CONFLICT`, `NOT_FOUND`, `FORBIDDEN`, `INVALID` and `UNAVAILABLE`; the HTTP status only when no known code came back |
| Shared boards | A view a shared dashboard shows stays shared: the claim is `INVALID`, its `boards` the boards' titles as stored, which the view engine says in its own words |

## Source

[typescript/wow-view-store](https://github.com/Ahoo-Wang/Wow/tree/main/typescript/wow-view-store) · [README](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-store/README.md) · [view store server](https://github.com/Ahoo-Wang/Wow/blob/main/view-store/README.md)
