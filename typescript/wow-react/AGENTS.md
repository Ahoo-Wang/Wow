# AGENTS.md — @ahoo-wang/wow-react

React hooks for Wow queries. Workspace rules live in `typescript/AGENTS.md`.

## Commands

```bash
pnpm --filter @ahoo-wang/wow-react... build     # vite build, then scripts/verify-package.mjs
pnpm --filter @ahoo-wang/wow-react test         # vitest with coverage, then test:type
pnpm --filter @ahoo-wang/wow-react exec vitest run --maxWorkers=3 test/streamQuery.test.tsx
pnpm --filter @ahoo-wang/wow-react exec vitest run test/publicSurface.test.ts -u   # accept a surface change
```

## Structure

```
src/
  index.ts                               — re-exports hooks/ and types.ts only
  types.ts                               — the public types every hook shares: QueryStatus, QueryExecutor, StreamExecutor, QueryHookOptions, QueryHookReturn
  hooks/use{Single,List,Paged,Count,Aggregate}Query.ts — the request hooks on useQueryRunner; `execute` is a query client method
  hooks/useCursorQuery.ts                — the runner plus the pages read: owns the cursor, appends each page on fetchNext()
  hooks/useStreamQuery.ts                — the runner plus readStreamRows: keeps `items`, `position` and `done`
  internal/useQueryRunner.ts             — the one request state machine: latest wins, aborts, StrictMode, first frame, the query and requestOptions as the run's identity, the failure as `error` and `problem`
  internal/queryTransitions.ts           — its state transitions as pure functions (section 3 of the design doc)
  internal/readStreamRows.ts             — reads a stream of positioned rows, publishing at most once per 16 ms
test/
  support/fakeServer.ts                  — fake global fetch behind a real Fetcher; SSE streams the test writes to
  queryHooks / cursorQuery / streamQuery .test.tsx — real behaviour: races, aborts, StrictMode, errors
  filterQueryTypes / streamTypes .test.ts — type tests; test:type type-checks every test file
  hookContract.types.test.ts             — the members of every options and return type, name by name
  publicSurface.test.ts, surface/root.txt — the public surface, name by name
scripts/verify-package.mjs               — holds the built entry to surface/root.txt, to react/compiler-runtime and under its gzip ceiling
scripts/size-budget.json                 — the entry's gzip regression ceiling (typescript/AGENTS.md「Size ceilings」)
```

## Boundaries

- React 19.0 or later only: the build runs the React Compiler and imports `react/compiler-runtime`. Do not add the `react-compiler-runtime` polyfill.
- Do not depend on `@ahoo-wang/fetcher-react`: the request state machine is this package's own (`src/internal/useQueryRunner.ts`), so its semantics are Wow's to freeze. `verify-package.mjs` fails a declaration that imports fetcher-react.
- The behaviour of every hook is the table in section 3 of `docs/design/architecture.md`, pinned by `requestStateTable`, `streamStateTable`, `queryIdentity`, `callbacks`, `ssr` and `hydration`. Change a cell only on purpose, and say which in the pull request.
- Query types come from `@ahoo-wang/wow-client`; do not redefine them here.
- Every hook runs a client method handed over as `execute`; no hook builds a route, a request or a client. A query reaches a Wow service only through `@ahoo-wang/wow-client`.
- Tests do not mock the hooks' dependencies: use a real `Fetcher` and query client over `test/support/fakeServer.ts`.
- A stream hook never hands a `ReadableStream` to components; it owns, reads and cancels the stream.
- Every exported name is in `test/surface/root.txt`; change it only on purpose.
