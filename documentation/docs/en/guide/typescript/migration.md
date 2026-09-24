---
title: Migrate from Fetcher Packages
description: Move from fetcher-wow, fetcher-generator, and the Wow hooks of fetcher-react to wow-client, wow-generator, and wow-react, and regenerate generated clients.
---

# Migrate from Fetcher Packages

This page answers: **what must change in an application that uses `@ahoo-wang/fetcher-wow`, `@ahoo-wang/fetcher-generator`, or the Wow hooks of `@ahoo-wang/fetcher-react`?**

The Wow TypeScript packages moved from the [Fetcher repository](https://github.com/Ahoo-Wang/fetcher) into the Wow repository so that the Kotlin contract, the TypeScript client, and the generator change in one pull request and release together. The exported APIs did not change. What changed is package names, the CLI name, peer ranges, and the version line.

## What changed

| Before | After | Notes |
|---|---|---|
| `@ahoo-wang/fetcher-wow` | `@ahoo-wang/wow-client` | Same exports, including the `/query/locale/en_US` and `/query/locale/zh_CN` subpaths |
| `@ahoo-wang/fetcher-generator` | `@ahoo-wang/wow-generator` | CLI renamed to `wow-generator`; `fetcher-generator` stays as an alias until v10 |
| Wow hooks of `@ahoo-wang/fetcher-react` | `@ahoo-wang/wow-react` | `useSingleQuery`, `useListQuery`, `usePagedQuery`, `useCountQuery`, `useListStreamQuery` and their `useFetcher*` variants |
| Fetcher 5.x version line | Wow version line | `wow-client` 9.x.y is released with Wow 9.x.y |
| `@ahoo-wang/fetcher-view-engine` (never published) | `@ahoo-wang/wow-view-engine` | Not released yet; see [View Engine](./view-engine.md) |

The core Fetcher packages keep their names and their documentation at [fetcher.ahoo.me](https://fetcher.ahoo.me/): `fetcher`, `fetcher-decorator`, `fetcher-eventstream`, `fetcher-openapi`, `fetcher-react`, and `fetcher-cosec`. `@ahoo-wang/fetcher-viewer` and the `dataMonitor` hooks of `fetcher-react` did not move; they stay on Fetcher 5.x.

## Steps

```mermaid
flowchart LR
    Deps["1. Swap dependencies"] --> Imports["2. Rewrite imports"]
    Imports --> Scripts["3. Rename the CLI in scripts"]
    Scripts --> Regen["4. Regenerate clients"]
    Regen --> Verify["5. Type-check and test"]
```

### 1. Swap dependencies

Upgrade the Fetcher peers first: `@ahoo-wang/fetcher-react` must be 5.1.3 or later, because `wow-react` imports only its `/core` and `/fetcher` subpaths. Then replace the moved packages:

```sh
pnpm remove @ahoo-wang/fetcher-wow @ahoo-wang/fetcher-generator
pnpm add @ahoo-wang/wow-client
pnpm add -D @ahoo-wang/wow-generator
# only when the application uses the Wow query hooks
pnpm add @ahoo-wang/wow-react
```

| Package | Peer | Range |
|---|---|---|
| `wow-client` | `fetcher`, `fetcher-decorator`, `fetcher-eventstream` | `^5.1 \|\| ^6` |
| `wow-generator` | `fetcher`, `fetcher-decorator`, `fetcher-eventstream`, `fetcher-openapi` | `^5.1 \|\| ^6` |
| `wow-generator`, `wow-react` | `wow-client` | `~x.y.z`, the same minor version |
| `wow-react` | `fetcher-react` | `^5.1.3 \|\| ^6` |
| `wow-react` | `fetcher`, `fetcher-eventstream`, `react` | As declared by the package |

With `fetcher-react` 5.1.3 or later, its peer dependency on `fetcher-wow` is optional, so removing `fetcher-wow` leaves a single copy of the Wow types in the dependency graph. Keep `fetcher-wow` installed only if another dependency still requires it, and do not import Wow types from both packages in one application: the two sets of types are not interchangeable.

### 2. Rewrite imports

Replace the module specifiers. Symbol names are unchanged.

```ts
// Before
import { CommandClient, filter, pagedQuery } from '@ahoo-wang/fetcher-wow';
import { zh_CN } from '@ahoo-wang/fetcher-wow/query/locale/zh_CN';
import { usePagedQuery, useFetcher } from '@ahoo-wang/fetcher-react';

// After
import { CommandClient, filter, pagedQuery } from '@ahoo-wang/wow-client';
import { zh_CN } from '@ahoo-wang/wow-client/query/locale/zh_CN';
import { usePagedQuery } from '@ahoo-wang/wow-react';
import { useFetcher } from '@ahoo-wang/fetcher-react';
```

Only the five Wow query hooks and their `useFetcher*` variants move to `wow-react`. Every other hook, such as `useFetcher`, `useQuery`, and `useFetcherQuery`, stays in `@ahoo-wang/fetcher-react`. A search for `fetcher-wow` and for the ten hook names finds every line to change.

### 3. Rename the CLI in scripts

```json
{
  "scripts": {
    "generate": "wow-generator generate -i ./openapi.json -o ./src/generated -t ./tsconfig.json"
  }
}
```

The `fetcher-generator` command still works as an alias of `wow-generator` until v10, so an unchanged script keeps running after step 1. The existing options are unchanged.

Rename the optional configuration file from `fetcher-generator.config.json` to `wow-generator.config.json`. Until v10 the old name is still read when the new one is absent, with a deprecation warning. The ownership manifest is now `.wow-generator.json`: the first regeneration reads an existing `.fetcher-generator.json`, so stale files are still cleaned up, and replaces it; commit the new manifest.

A configuration that cannot be read or parsed, or has an option of the wrong shape, now fails the run with exit code 3 instead of being ignored, and an http(s) input that answers with a non-2xx status fails with exit code 2. A script that checks the exit code sees the failure; see [CLI exit codes](../../reference/typescript/wow-generator/cli.md#failures-and-exit-codes).

### 4. Regenerate clients

Generated code imports its Wow types from `@ahoo-wang/wow-client` instead of `@ahoo-wang/fetcher-wow`. Code generated by `fetcher-generator` therefore does not compile once `fetcher-wow` is removed. Run the generator again against the same OpenAPI document:

```sh
pnpm exec wow-generator generate -i ./openapi.json -o ./src/generated -t ./tsconfig.json
```

Review the diff. The import specifier is the expected change; treat any other difference as a generator change and review it like a contract change. Regenerate instead of rewriting the imports inside generated files by hand: generation owns those files, see [generated output and regeneration](../../reference/typescript/wow-generator/generated-output.md).

### 5. Type-check and test

```sh
pnpm exec tsc --noEmit
pnpm test
```

A remaining `@ahoo-wang/fetcher-wow` import fails type checking once the package is removed. Run the application's integration tests against a real Wow server as well: type checking does not prove that routes and command stages behave as before.

## Versions from now on

- The Wow TypeScript packages follow Wow releases. Choose the version that matches the Wow server, and upgrade `wow-client`, `wow-generator`, and `wow-react` together; they declare each other with `~x.y.z`.
- Breaking changes ship only in an `x.Y.0` release, and the release notes list each one with its migration.
- Throughout Wow 9.x, the client and the generator still work against Wow 8.x servers, the `fetcher-generator` alias and the `fetcher-generator.config.json` fallback work, and the deprecated `Condition` API is available. All of them are removed in v10; switch to `FilterExpression` and the `filter.*` builders before then, see [filters](../../reference/typescript/wow-client/filters.md).
- New features land only in the Wow packages. Fetcher keeps a 5.x branch for fixes, and `fetcher-wow` and `fetcher-generator` are planned to be deprecated on npm when Fetcher 6.0 is released.

## Checklist

| Check | Done when |
|---|---|
| Dependencies | `fetcher-wow` and `fetcher-generator` are gone from `package.json`, and `fetcher-react` is 5.1.3 or later where it is used |
| Imports | No source file imports `@ahoo-wang/fetcher-wow`, and the Wow query hooks come from `@ahoo-wang/wow-react` |
| Generated code | Regenerated with `wow-generator`, and the generated files import `@ahoo-wang/wow-client` |
| Versions | `wow-client`, `wow-generator`, and `wow-react` share one minor version that matches the Wow server |
| Verification | Type checking and the integration tests against a real Wow server pass |
