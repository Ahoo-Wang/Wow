---
title: 'wow-view-store reference'
description: 'WowViewStore, the @ahoo-wang/wow-view-store package: the view engine''s ViewStore on the Wow view store server.'
---

# wow-view-store reference

::: info Released with Wow 9.2.0
`@ahoo-wang/wow-view-store` is released with Wow 9.2.0, together with [wow-view-engine](../wow-view-engine/), from the same tag and with the same version; it is not on npm before that. A patch release never breaks its public surface (the exports of the entry and the error codes); a minor release may, and its release notes list every break with the steps to follow ([version ranges](../../../guide/typescript/compatibility.md#version-ranges)). The [package README](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-store/README.md) and the [design](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/docs/design/view-store-backend.md) are the source of truth.
:::

`WowViewStore` implements the view engine's [`ViewStore` port](../wow-view-engine/#persistence) on the [view store](https://github.com/Ahoo-Wang/Wow/blob/main/view-store/README.md): saved views and preferences as two Wow aggregates, served by the standalone `wow-view-store-server` or by a Wow service that embeds `wow-view-store-starter`.

## Entry

| Export | Role |
|---|---|
| `WowViewStore` | The store: `new WowViewStore({ fetcher, permissions? })`, handed to `new ViewEngine({ store })` |
| `WowViewStoreOptions` | `fetcher`: the fetcher whose interceptors carry the tenant, the owner and the application; `permissions`: which buttons are enabled |
| `SHARED_OWNER_ID` | `(shared)`, the owner segment of shared views and shared preferences |
| `WowViewStoreErrorCodes` | The view store's own error codes, beside Wow's |

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
| Errors | By Wow's error code onto `CONFLICT`, `NOT_FOUND`, `FORBIDDEN`, `INVALID` and `UNAVAILABLE`; the HTTP status only when no known code came back; `UNSUPPORTED` from a server with no view store. The error keeps the server's code as `detail.code`, and an `UNAVAILABLE` the server answered says `reachable` |
| List order | System views, then shared, then personal, each audience oldest first; at most 1,000 of each audience |
| Shared boards | A view a shared dashboard shows stays shared: the claim is `INVALID`, its `boards` the boards' titles as stored, which the view engine says in its own words |

## Hosts

The example server and the compensation service embed `wow-view-store-starter`; the compensation console keeps its views there. Nobody signs in to the console, so it adds a request interceptor of its own that fills the tenant `(0)`, the owner `(shared)` and its application (`compensation-dashboard`) where a request names none: every view and preference it keeps is shared, and its permissions turn personal views off. A host that embeds the starter gives the view store's Kafka topics a prefix of their own with `wow.view-store.kafka.topic-prefix`, which leaves the host's own topics as they are.

## CoSec gateway rules

The server does not authenticate: it takes the tenant, the owner and the application as the request names them, so every deployment runs behind the CoSec gateway, whose path rules tie each to the token:

| Path | Allow when |
|---|---|
| `/view-store/tenant/{tenantId}/owner/{ownerId}/**`, except `…/view/{id}/claim` and `…/view/{id}/share` | `{tenantId}` is the token's tenant and `{ownerId}` is its `sub` |
| `/view-store/tenant/{tenantId}/owner/(shared)/**`, reads | `{tenantId}` is the token's tenant |
| `/view-store/tenant/{tenantId}/owner/(shared)/**`, writes | the token's tenant, and the role that may write shared views |
| `PUT /view-store/tenant/{tenantId}/owner/{ownerId}/view/{id}/claim`, `…/share` | the token's tenant, `{ownerId}` is its `sub`, **and** the role that may write shared views |

A claim takes a shared view out of everyone's list and a share publishes a personal one into it, so the personal rule must not admit either on its own: leave `…/view/{id}/claim` and `…/view/{id}/share` out of that rule, and only the claim-and-share rule decides (otherwise anyone could publish a shared view by creating a personal one and sharing it). `CoSec-App-Id` is authenticated by CoSec and keeps applications apart; a host gives `permissions` by the same role the gateway checks: `createShared` and `changeAudience` need the shared-write role. The full rules, with the reasons, are in the [view store README](https://github.com/Ahoo-Wang/Wow/blob/main/view-store/README.md#cosec-gateway-rules).

## Source

[typescript/wow-view-store](https://github.com/Ahoo-Wang/Wow/tree/main/typescript/wow-view-store) · [README](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-store/README.md) · [view store server](https://github.com/Ahoo-Wang/Wow/blob/main/view-store/README.md)
