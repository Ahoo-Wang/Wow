# `@ahoo-wang/wow-view-store`

[简体中文](./README.zh-CN.md)

The view engine's `ViewStore` on the Wow view store service: the saved views
and preferences of [`@ahoo-wang/wow-view-engine`](../wow-view-engine/README.md),
kept by the view store service (`platform/view-store` in the Wow repository) —
two Wow aggregates, reached through the clients generated from the service's
Manifest — and the view engine's sources over a Wow service's query routes.

Released with Wow, from the same tag and with the same version, together
with the view engine.

> **Compatibility.** A patch release never breaks the public surface (the
> exports of the entry and the error codes); a minor release may, and its
> release notes list every break with the steps to follow. Keep the Wow
> packages on one minor, as [version ranges](https://wow.ahoo.me/guide/typescript/compatibility#version-ranges)
> explains.

## Use

Peer dependencies: `@ahoo-wang/fetcher`, `@ahoo-wang/wow-client` and
`@ahoo-wang/wow-view-engine`, and the peers those declare, which a host installs
with them: `@ahoo-wang/fetcher-eventstream` (wow-client's), and the view
engine's optional `react`, `react-dom`, `react-router` and `mingo` where its
own entries need them. Node `>=22.12.0` or a current browser; TypeScript 6 or
later.

`WowViewStore` takes a fetcher and, optionally, the permissions that drive the
buttons. It takes no tenant, user or application: the service takes them from
the principal, which the fetcher's interceptors carry as they do for every
other Wow request of the host. `viewSourceOf` puts a definition on a query
route, `datasetCatalogOf` the service's datasets, and `WowDatasetWordings`
keeps the tenant's wording over them.

<!-- typecheck-context
import type { ViewDefinition, ViewPermissions } from '@ahoo-wang/wow-view-engine';
declare const orders: ViewDefinition;
declare const permissionsFromRoles: (definitionId: string) => ViewPermissions;
-->

```ts
import { Fetcher } from '@ahoo-wang/fetcher';
import { DatasetClient, SnapshotQueryClient } from '@ahoo-wang/wow-client';
import { DatasetResources, ViewEngine } from '@ahoo-wang/wow-view-engine';
import {
  WowDatasetWordings,
  WowViewStore,
  datasetCatalogOf,
  viewSourceOf,
} from '@ahoo-wang/wow-view-store';

// The fetcher's interceptors send the authorization and `CoSec-App-Id`; no
// path names the tenant or the owner.
const fetcher = new Fetcher({ baseURL: 'https://api.example.com' });

const engine = new ViewEngine({
  store: new WowViewStore({ fetcher, permissions: permissionsFromRoles }),
  resources: [
    {
      definition: orders,
      // Any query route: a generated `…SnapshotQueryClient` fits too.
      source: viewSourceOf(
        new SnapshotQueryClient(
          { context: 'sales', name: 'order' },
          { fetcher },
        ),
      ),
    },
  ],
});

// The service's datasets (`GET /wow/dataset`), worded by the tenant.
const datasets = new DatasetResources(engine, {
  ...datasetCatalogOf(new DatasetClient({ fetcher })),
  wordings: new WowDatasetWordings({ fetcher }),
});
await datasets.refresh();
```

`permissions` decides which buttons are enabled, never what a write may do:
the service authorizes each write. Give `createShared` and `changeAudience` by
the role that writes the reserved owner `(shared)` (`view-store.shared-writer`
as shipped) — creating a shared view, claiming one and sharing a personal one
all need it. Left out, everything is allowed — but the engine's `editSystem`
(publishing, editing and unpublishing stored system views) is off unless the
host's answer says `true`: give it by the role that writes `(system)` of
`(platform)`.

`WowDatasetWordings` keeps a tenant's wording over its datasets (the view
engine's dataset overrides: title, record noun, field labels, order and
narrowing), one per dataset and application. `DatasetResources` reads it
before each refresh, registers each dataset in its stored words, and gives the
view manager its wording tab where the host grants `editDatasetWording`.
`set(dataset, wording, revision, { requestId })` writes one whole at the
expected revision (`'0'` for one never written) and `clear` removes it.

### How the store talks to the service

- **Who is asking** comes from the principal; a shared view is written as
  the reserved owner `(shared)` and a stored system view as `(system)` of
  `(platform)`, which the store says itself with `Wow-Scope`.
- **Writes** carry the port's request id as `Wow-Request-Id` and its revision
  as `If-Match`; the service deduplicates every write by its request id,
  creates included, so a retry from any tab is answered with the first
  outcome. Setting a view shared or personal waits for the service's saga
  that moves it to its new owner.
- **Errors** are read by the problem's `code`: a `ViewStoreError` carries it
  as `detail.code` (`WowViewStoreErrorCodes` lists the view store's own), and
  a route the service does not serve (`404` without a code) is `UNSUPPORTED`.
- **A page's total** is the service's default `BOUNDED` count: the service
  refuses an exact total over HTTP.

## Documentation

| To                                                                                              | Read                                                                                                  |
| ----------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------- |
| Choose where views live, wire `WowViewStore`, give permissions, serve a host nobody signs in to | [Saved views](https://wow.ahoo.me/typescript/view-engine/storage#wowviewstore-views-on-a-wow-service) |
| The exports, the contract, every request the port sends and how server errors map to the port   | [wow-view-store reference](https://wow.ahoo.me/reference/typescript/wow-view-store/)                  |

## Testing

- `pnpm --filter @ahoo-wang/wow-view-store test` — unit tests against the
  service in memory, the public surface and the API report.
- The port's conformance suite runs over `WowViewStore` against the view store
  service, which `pnpm --dir typescript/integration-test test` starts on
  ports bound to 0 (`test/view-store/`), with the tenant and application
  isolation.

Licensed under the Apache License, Version 2.0.
