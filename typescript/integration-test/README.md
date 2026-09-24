# Integration tests

This private package checks the Wow TypeScript client and the code
`wow-generator` produces against a running Wow example server. It is kept apart
from the deterministic unit tests of each package.

## What runs

- Commands, snapshot queries, load-state queries and event streams through
  `@ahoo-wang/wow-client` (`test/wow/cart/`).
- The generated clients under `src/generated/`, checked with `tsc` and exercised
  by `generatedCartCommandClient.test.ts`.
- `test/wow/wowOpenApi.test.ts`, which holds every enum `wow-client` sends to the
  server's OpenAPI document.
- The `@ahoo-wang/wow-react` hooks (`test/wow/react/`, under jsdom): the
  endpoint hooks with snapshot URLs, the list-stream hook's event stream and
  its `WowError`, and the `execute` hooks with a generated query client.

## Prerequisites

A Wow example server on port 8080, backed by MongoDB. The tests read another
address from `WOW_EXAMPLE_SERVER_URL` (for example
`WOW_EXAMPLE_SERVER_URL=http://localhost:18080/`). Either build the server from
this repository:

```bash
./gradlew :example-server:installDist
```

or run the published image `ghcr.io/ahoo-wang/wow-example-server:<version>`.
Point it at MongoDB with `SPRING_MONGODB_URI`, choose its port with
`SERVER_PORT`, and set
`WOW_EVENTSOURCING_STORE_STORAGE=mongo` and `WOW_EVENTSOURCING_SNAPSHOT_STORAGE=mongo`.

## Generate and test

From the repository root:

```bash
pnpm install --frozen-lockfile
pnpm --filter wow-integration-test... build
pnpm --filter wow-integration-test generate
pnpm --filter wow-integration-test exec tsc --noEmit
pnpm --filter wow-integration-test test
```

`generate` reads `http://localhost:8080/v3/api-docs` and replaces
`src/generated`. Inspect generated changes before committing them.

`src/generated` holds the generator's output byte for byte, with its manifest
`.wow-generator.json`. ESLint checks it like the rest of the package;
Prettier skips it. Never edit or reformat it by hand.

## CI

`.github/workflows/typescript-contract.yml` runs these steps against an example
server built from the same commit, whenever the Kotlin sources, the example, the
Gradle build or these packages change. It fails when regenerating changes
`src/generated`, and uploads the server log when a step fails. For changes to
`wow-client`, `wow-generator` or this package it also generates code from the
`wow-example-server` images 8.10.8 and 8.11.5 and type-checks it.

## Failure diagnosis

1. Build first; unresolved workspace imports usually mean stale or missing
   package output.
2. Check `http://localhost:8080/actuator/health` before generating or testing.
3. Regenerate after a server-contract change.

[中文](./README.zh-CN.md)
