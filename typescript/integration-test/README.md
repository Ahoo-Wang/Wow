# Integration tests

This private package checks `@ahoo-wang/wow-view-store` and the view engine
on it against the V10 view store service (`platform/view-store`). It is kept
apart from the deterministic unit tests of each package.

## What runs

`test/view-store-v10/` holds the store to the service: the view engine's port
conformance suite
(`../wow-view-engine/test/conformance/viewStoreConformance.ts`, imported by its
workspace path, so `tsconfig.json` sets `rootDir` to `..`), tenant and
application isolation, the stored and configured system views, the service's
writer roles, the tenant's dataset wording, the errors, `viewSourceOf` over a
V10 query route, and the engine end to end (saves, conflicts, an unknown
outcome retried, share and claim, preferences, a record view and an analysis
through the V10 query routes).

## Run

The global setup (`test/view-store-v10/globalSetup.ts`) starts the service in
a JVM on the memory backend, every port bound to 0, and stops it at the end. On
the memory backend the service's own configuration serves its snapshot queries
from memory (`wow.query.memory.enabled`). Build it first, with JDK 21:

```bash
./gradlew :view-store-server:installDist :view-store-server:testClasses :query-tck:jar
pnpm --filter @ahoo-wang/wow-view-store... build
pnpm --dir typescript/integration-test test:v10
```

`JAVA_HOME` picks the JVM (`java` on the path otherwise). With
`WOW_V10_VIEW_STORE_URL` set, the suites use a service already running
instead; it must serve snapshot queries and the configured system view of
`conformance-system` that `test/view-store-v10/globalSetup.ts` configures.

## CI

The `v10-contract` job of `.github/workflows/typescript-contract.yml` runs
`test:v10` against the service built from the same commit.

`typecheck` checks `test` and needs no server. The Quality job of
`typescript.yml` runs it, through the root `pnpm typecheck`, on every pull
request that touches TypeScript, so a type change in `wow-client`,
`wow-view-store` or `wow-view-engine` that breaks these tests fails there
without waiting for the contract job.

[中文](./README.zh-CN.md)
