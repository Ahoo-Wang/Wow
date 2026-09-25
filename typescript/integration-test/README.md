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
- The `@ahoo-wang/wow-view-engine` runtime (`test/view-engine/`): a
  `ViewEngine` whose source is the Wow `SnapshotQueryClient` itself, over
  sales orders the suite seeds through commands. See
  [View engine against the server](#view-engine-against-the-server).

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
pnpm --filter wow-integration-test typecheck
pnpm --filter wow-integration-test test
```

`generate` reads `http://localhost:8080/v3/api-docs` and replaces
`src/generated`. Inspect generated changes before committing them.

`test` waits for the server before the first test (`test/globalSetup.ts`):
until `/actuator/health` answers `UP`, for up to five minutes, then one
command to each aggregate the suite drives, waited to its snapshot. A freshly
started server pays for its first commands there, so no test needs a longer
timeout for a cold server.

`src/generated` holds the generator's output byte for byte, with its manifest
`.wow-generator.json`. ESLint checks it like the rest of the package;
Prettier skips it. Never edit or reformat it by hand.

## View engine against the server

`test/view-engine/` drives the engine's public runtime (`new ViewEngine`,
`create`, `open`, `edit`, `apply`, `page`, `exportRows`, and a dashboard's
`setFilterValue`, `setFilters`, `clearFilters` and `crossFilter`) against the
example server. Each file writes thirteen sales orders into a tenant of its
own (`salesOrders.ts`: create, then pay in full or in part), reads their
creation times back off the server, and asserts every answer against numbers
it adds up from those orders, never against a snapshot of the engine's own
output.

| File                 | What it checks                                                                                                                                                                                                                                                                                                     |
| -------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `recordView.test.ts` | Text, enum, number-range and date-range conditions; a sort over two fields; three pages; the totals row; the CSV export, paged at five rows; a search, which the MongoDB backend refuses (no full-text capability) and the view reports                                                                            |
| `analysis.test.ts`   | Groups by a field, by an expanded array and by day, week and month; `COUNT`, `SUM`, `AVG`, `DISTINCT_COUNT`, `PERCENTILE` and a derived metric; the having; 「前 N 组」 and its probe row; a split folded into 「其他」; a pie's 「其他」; dense seconds; this month so far against the same stretch of last month |
| `dashboard.test.ts`  | A board's filters wired into an analysis, a metric card and a saved record view; a cross-filter from one panel; the board's fixed scope; a trend card anchored to the day the board's date filter holds (D39), and an expected failure (`it.fails`) for the comparison it cannot make yet                          |

To run it alone, start a server as under [Prerequisites](#prerequisites). On
a machine whose port 8080 is taken:

```bash
docker run -d --name wow-it-mongo -p 27117:27017 \
  -e GLIBC_TUNABLES=glibc.pthread.rseq=1 \
  -e MONGO_INITDB_ROOT_USERNAME=root -e MONGO_INITDB_ROOT_PASSWORD=root mongo:8.3.11
./gradlew :example-server:installDist
cd example/example-server/build/install/example-server
mkdir -p logs data
SERVER_PORT=18080 \
SPRING_AUTOCONFIGURE_EXCLUDE=org.springframework.boot.elasticsearch.autoconfigure.ElasticsearchClientAutoConfiguration,org.springframework.boot.elasticsearch.autoconfigure.ElasticsearchRestClientAutoConfiguration \
SPRING_MONGODB_URI='mongodb://root:root@localhost:27117/wow_example_db?authSource=admin' \
WOW_EVENTSOURCING_STORE_STORAGE=mongo WOW_EVENTSOURCING_SNAPSHOT_STORAGE=mongo \
bin/example-server
```

then, from the repository root:

```bash
pnpm --filter wow-integration-test... build
cd typescript/integration-test
WOW_EXAMPLE_SERVER_URL=http://localhost:18080/ pnpm exec vitest run --maxWorkers=2 test/view-engine
```

MongoDB 8.x exits on Linux kernels 6.19 to 7.0.13 (SERVER-121912), which
Docker Desktop may run: the image sets `GLIBC_TUNABLES=glibc.pthread.rseq=0`,
so its allocator takes the kernel feature the bug is in. Setting it to
`glibc.pthread.rseq=1`, as above and as CI's services do, lets MongoDB start on
any kernel; the image is the one CI runs. The suite takes about five
seconds on a warm server, two of them a pause that puts the dense
histogram's orders seconds apart.

## CI

`.github/workflows/typescript-contract.yml` runs these steps against an example
server built from the same commit, whenever the Kotlin sources, the example, the
Gradle build, these packages or the sources of `wow-view-engine` change. It fails when regenerating changes
`src/generated`, and uploads the server log when a step fails. For changes to
`wow-client`, `wow-generator` or this package it also generates code from the
`wow-example-server` images 8.10.8, 8.11.5, 9.1.3 and 9.1.5 and type-checks it,
and against every image but 8.10.8 runs `test/released/`
(`vitest.released.config.ts`): a command, snapshot reads, and a list and list
stream without a `limit`, each held to what the compatibility page documents
for that version (`WOW_SERVER_VERSION`). The same-source run includes
`test/released/` too, as the current version.

`typecheck` checks `src` and `test` together through `tsconfig.test.json` and
needs no server. The Quality job of `typescript.yml` runs it, through the root
`pnpm typecheck`, on every pull request that touches TypeScript, so a type
change in `wow-client` or `wow-react` that breaks these tests fails there
without waiting for the contract job.

## Failure diagnosis

1. Build first; unresolved workspace imports usually mean stale or missing
   package output.
2. Check `http://localhost:8080/actuator/health` before generating or testing.
3. Regenerate after a server-contract change.

[中文](./README.zh-CN.md)
