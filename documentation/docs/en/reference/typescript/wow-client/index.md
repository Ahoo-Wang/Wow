---
title: 'wow-client reference'
description: 'The @ahoo-wang/wow-client package: its four entries, what each exports, and where the generated symbol index and the API report are.'
---

# wow-client reference

`@ahoo-wang/wow-client` is the TypeScript client of a Wow service's routes, on [Fetcher](https://fetcher.ahoo.me/): commands with their waits, state reads, snapshot, event and dataset queries, the Manifest, and the problems the service answers with. The clients `wow-generator` writes build on it. How to use it is the [TypeScript guide](../../../typescript/); this reference lists what it exports.

## Entries

| Entry | Holds | Symbols |
| --- | --- | --- |
| `@ahoo-wang/wow-client` | `CommandClient` and the command options, `WaitPlan`, `CommandRejectedError`, `StateClient`, `SnapshotQueryClient`, `EventQueryClient`, `QueryModelClient`, `DatasetClient`, `fetchManifest`, `WowProblemError` with `FrameworkErrorCodes`, the `wait` hops, the wire types and the exact-number JSON helpers; not the query DSL, which is `/dsl`'s | [Root entry](./symbols) |
| `@ahoo-wang/wow-client/dsl` | The query DSL alone: `filter`, `aggregation`, sort, projection, pagination, cursor queries, the query factories, the capability descriptor's types and `QueryErrorCodes`, with no Fetcher and none of the stream patches `fetcher-eventstream` installs | [`/dsl`](./symbols-dsl) |
| `@ahoo-wang/wow-client/routes` | `ServiceRoutes`: calls a service's own routes, those whose handler is the service's code, by the names its Manifest gives them; the generated `<Context>Routes` build on it | [`/routes`](./symbols-routes) |
| `@ahoo-wang/wow-client/testing` | Helpers for tests and development runs, never for production: `commandResultOf`, `snapshotOf`, `problemOf` and `problemResponse` build what a service answers, for a fake; `developmentCaller` sends every request as a caller by the headers only a development run reads | [`/testing`](./symbols-testing) |

Use `/dsl` where a bundle only builds queries and sends them through a client of its own.

## The generated half

The four symbol index pages are generated from the built declarations by the package's `scripts/api-report.mjs`: every exported name, its kind and the first sentence of its TSDoc. The same run holds each entry's full signatures in its API report, `test/api/root.api.md`, `dsl.api.md`, `routes.api.md` and `testing.api.md`, and fails when the build differs from a committed report or page. A change to the surface therefore shows in review, and a `-` line in a report labels the pull request `breaking-change`.

## See also

- [Send Commands](../../../typescript/commands), [Read and Query](../../../typescript/queries) and [Generate a Client](../../../typescript/generate): the package in use.
- [Compatibility and Versions](../../../typescript/compatibility): peers, runtimes and version ranges.
- [Query Language](../../query-language) and [HTTP API](../../http): the server side of what it sends.
