# `@ahoo-wang/fetcher-wow`

Typed Fetcher clients and contracts for Wow commands, snapshots, domain events,
filters, pagination, and aggregation. Use it only against Wow HTTP endpoints.

## Install

```bash
pnpm add @ahoo-wang/fetcher @ahoo-wang/fetcher-decorator \
  @ahoo-wang/fetcher-eventstream @ahoo-wang/fetcher-wow
```

Peer dependencies: `fetcher`, `fetcher-decorator`, and `fetcher-eventstream`.

## Example

```ts
import { Fetcher } from '@ahoo-wang/fetcher';
import { SnapshotQueryClient, filter, listQuery } from '@ahoo-wang/fetcher-wow';

interface CartState {
  status: 'ACTIVE' | 'CHECKED_OUT';
}

const fetcher = new Fetcher({ baseURL: 'https://api.example.com' });
const snapshots = new SnapshotQueryClient<CartState>({
  fetcher,
  basePath: 'cart',
});

const carts = await snapshots.listState(
  listQuery({
    filter: filter.and([
      filter.ownerId('u-42'),
      filter.eq('state.status', 'ACTIVE'),
    ]),
    limit: 50,
  }),
);
```

## Core capabilities

- Command results and streaming wait stages.
- Snapshot, domain-event, load-state, and owner-state clients.
- Array-first `FilterExpression` builders with early validation.
- Single, list, paged, cursor, count, and stream query contracts.
- Projection, sorting, nested aggregation, modeling, ABAC, and metadata types.

## Documentation

- [Wow CQRS recipe](https://fetcher.ahoo.me/recipes/wow-cqrs)
- [Wow reference](https://fetcher.ahoo.me/reference/wow)
- [Interactive query stories](https://fetcher.ahoo.me/storybook/)

[中文](./README.zh-CN.md) · [License](../../LICENSE)
