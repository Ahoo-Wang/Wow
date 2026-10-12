# `@ahoo-wang/wow-view-store`

[简体中文](./README.zh-CN.md)

The view engine's `ViewStore` on a Wow server: the saved views and preferences
of [`@ahoo-wang/wow-view-engine`](../wow-view-engine/README.md), kept by the
[view store](../../view-store/README.md) — two Wow aggregates, served by the
standalone `wow-view-store-server` or by any Wow service that embeds
`wow-view-store-starter`.

Released with Wow, from the same tag and with the same version, together
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
one all need it. Left out, everything is allowed — but the engine's
`editSystem` (publishing, editing and unpublishing stored system views) is
off unless the host's answer says `true`: give it by the role the gateway
admits to `tenant/(platform)/owner/(system)` (`view-store/README.md`, 「System views」).

### Dataset wording

`WowDatasetWordings` keeps a tenant's wording over its datasets (the view
engine's dataset overrides: title, record noun, field labels, order and
narrowing) under `owner/(shared)`, one per dataset and application, keyed by
the dataset's definition id. Hand it to the datasets as their store of
wording: `DatasetResources` reads it before each refresh, registers each
dataset in its stored words, and gives the view manager its wording tab
where the host grants `editDatasetWording`:

```ts
import type { Fetcher } from '@ahoo-wang/fetcher';
import {
  DatasetDirectoryClient,
  DatasetQueryClient,
} from '@ahoo-wang/wow-client';
import { DatasetResources, type ViewEngine } from '@ahoo-wang/wow-view-engine';
import { WowDatasetWordings } from '@ahoo-wang/wow-view-store';

declare const engine: ViewEngine;
declare const fetcher: Fetcher;

const directory = new DatasetDirectoryClient();
const datasets = new DatasetResources(engine, {
  list: () => directory.list(),
  source: entry =>
    new DatasetQueryClient({ context: entry.contextName, dataset: entry.name }),
  wordings: new WowDatasetWordings({ fetcher }),
});
await datasets.refresh();
```

`set(dataset, wording, revision, { requestId })` writes one whole at the
expected revision (`'0'` for one never written) and `clear` removes it; the
gateway decides who may, by the role it admits to
`…/owner/(shared)/datasets/{dataset}/wording`.

## Documentation

| To                                                                                              | Read                                                                                                             |
| ----------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------- |
| Choose where views live, wire `WowViewStore`, give permissions, serve a host nobody signs in to | [Where views live](https://wow.ahoo.me/guide/typescript/view-engine-storage#wowviewstore-views-on-a-wow-service) |
| The exports, the contract, every request the port sends and how server errors map to the port   | [wow-view-store reference](https://wow.ahoo.me/reference/typescript/wow-view-store/)                             |
| Run the view store server, its configuration and the gateway rules                              | [View store](https://wow.ahoo.me/guide/extensions/view-store)                                                    |

## Testing

- `pnpm --filter @ahoo-wang/wow-view-store test` — unit tests against a fake
  server, the public surface and the API report.
- The port's conformance suite runs over `WowViewStore` against a view store
  server in `typescript/integration-test` (`test/view-store/`), with the tenant
  and application isolation.

Licensed under the Apache License, Version 2.0.
