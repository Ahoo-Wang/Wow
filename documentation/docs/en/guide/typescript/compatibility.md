---
title: Compatibility and Versions
description: Which Wow servers, Node.js, React, TypeScript and Fetcher versions the Wow TypeScript packages support, what CI verifies, and what v10 removes.
---

# Compatibility and Versions

This page answers: **which versions of the Wow TypeScript packages work with which Wow servers and runtimes, and how sure is that?**

## Release status

| Package | Status |
|---|---|
| `@ahoo-wang/wow-client` | Released with Wow **9.2.0**; not yet on npm until then |
| `@ahoo-wang/wow-generator` | Released with Wow **9.2.0**; not yet on npm until then |
| `@ahoo-wang/wow-react` | Released with Wow **9.2.0**; not yet on npm until then |
| `@ahoo-wang/wow-view-engine` | Not released; no date and no compatibility promise yet |

Until Wow 9.2.0 is out, `pnpm add @ahoo-wang/wow-client` fails with `E404`. A release candidate may appear under the `next` dist-tag first (`pnpm add @ahoo-wang/wow-client@next`); `latest` starts with 9.2.0. Before that, applications keep using `@ahoo-wang/fetcher-wow` and `@ahoo-wang/fetcher-generator` 5.x, and move with the [migration guide](./migration.md) once 9.2.0 is published.

## Versions

The TypeScript packages share one version with the Kotlin modules and are released from the same tag: `@ahoo-wang/wow-client` 9.2.0 is released together with Wow 9.2.0. Breaking changes ship only in an `x.Y.0` release and are listed under "Breaking" in the [release notes](https://github.com/Ahoo-Wang/Wow/releases).

- **Use one version for all Wow packages of an application.** `wow-generator` and `wow-react` declare `@ahoo-wang/wow-client` as a peer with `~x.y.z`, so the package manager warns when they differ in minor version.
- **Prefer the version of the service you call.** That combination is the one CI tests end to end.
- **Regenerate with the generator of the same version** as the `wow-client` the generated code compiles against.

## Wow servers

The root entry of `@ahoo-wang/wow-client` and every default speak the `FilterExpression` query model, which Wow 8.11 introduced. Wow 8.10 understands only the older `Condition` model, which the package keeps on its own subpath, `@ahoo-wang/wow-client/legacy`, until v10.

| Server | Queries | How the application builds them | What CI verifies |
|---|---|---|---|
| Wow 9.x | `FilterExpression` | Root entry: `filter.*`, `singleQuery` / `listQuery` / `pagedQuery` | Changes to the server, the client or the generator: the generator's output against a server built from the same commit must equal the committed clients byte for byte and compile, and the integration tests run against that server |
| Wow 8.11.x | `FilterExpression`; `raw()` is not available | Root entry, as for 9.x | Client and generator changes: a runtime smoke test against the published 8.11.5 example server, and the code generated from it type-checks |
| Wow 8.10.x | `Condition` only | `@ahoo-wang/wow-client/legacy` for the queries, the root entry for everything else | Client and generator changes: the code generated from the published 8.10.8 example server type-checks. Nothing runs against it |
| Before 8.10 | — | Not supported | — |

What the rows mean in practice:

- **8.10.** Build queries with `@ahoo-wang/wow-client/legacy`; the query clients accept both models. `getById` and `getStateById` send a `FilterExpression`, so against 8.10 call `single` or `singleState` with a `/legacy` query built from `aggregateId(id)`. Code the generator produces from an 8.10 document imports its `Condition` types from `/legacy` by itself. Only type checking covers this row: test the queries your application uses against your own 8.10 server. See [Wow 8.10 servers](./migration.md#wow-8-10-servers).
- **`raw()`**, the `Condition` operator that passes a raw query to the store, exists only on `/legacy`, and servers from 8.11.0 on answer it with 400. It has no `FilterExpression` replacement.
- **Before 8.11.1**, the OpenAPI document does not publish `x-wow-query-fields`; the generator then reads an aggregate's query fields from the `Condition` schema instead.
- **Newer client, older server.** A client method whose endpoint the server does not have yet — for example `EventStreamQueryClient.load` or `WowMetadataClient` — fails there, typically with 404. Everything the server has works.
- **Older client, newer server.** The server may send fields the client's types do not name; they arrive in the JSON and the types ignore them. Regenerate to see them.

The CI jobs are in [`typescript-contract.yml`](https://github.com/Ahoo-Wang/Wow/blob/main/.github/workflows/typescript-contract.yml); the compatibility code they protect is listed in [`docs/compat-debt.md`](https://github.com/Ahoo-Wang/Wow/blob/main/docs/compat-debt.md).

## Runtimes and peers

| Requirement | Version | Notes |
|---|---|---|
| Node.js | `>=22.12.0` | Declared in `engines` of every package; the generator CLI needs it too |
| Browsers | Current evergreen browsers | The packages use `fetch`, `ReadableStream` and `TextDecoderStream` |
| React (`wow-react`, `wow-view-engine` UI) | `^19.3.0` | React 18 is not supported: the build imports `react/compiler-runtime` |
| TypeScript | 6.0 is what CI checks | Generated clients need `experimentalDecorators: true`; `moduleResolution` `Bundler`, `NodeNext` or `Node16` |
| `@ahoo-wang/fetcher`, `fetcher-decorator`, `fetcher-eventstream`, `fetcher-openapi` | `^5.1.0 \|\| ^6.0.0` | Peers: the application installs them |
| `@ahoo-wang/fetcher-react` (`wow-react`) | `^5.1.3 \|\| ^6.0.0` | 5.1.3 is the first version whose Wow hooks moved out |
| `@ahoo-wang/wow-client` (for the other Wow packages) | `~x.y.z` | Same minor version |

pnpm 8 and later and npm 7 and later install missing peers by themselves; Yarn does not, so a Yarn project adds each peer explicitly. The install command of each [reference page](../../reference/typescript/wow-client/) lists them.

## What v10 removes

Wow 9.x keeps everything below working; v10 removes all of it in one breaking release:

| Kept through 9.x | Replacement |
|---|---|
| Wow 8.10 and 8.11 servers | Wow 9.x or later |
| `@ahoo-wang/wow-client/legacy` (the `Condition` API, `Operator`, the operator locales) and the `Condition` overloads of the query clients and hooks | `FilterExpression` and `filter.*` |
| The `fetcher-generator` command | `wow-generator` |
| Reading `fetcher-generator.config.json` and `.fetcher-generator.json` | `wow-generator.config.json` and `.wow-generator.json` |
| `LogicalField` | `QueryField` |

The complete list, with the code that implements each item, is [`docs/compat-debt.md`](https://github.com/Ahoo-Wang/Wow/blob/main/docs/compat-debt.md).
