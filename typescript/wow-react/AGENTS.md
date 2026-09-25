# AGENTS.md — @ahoo-wang/wow-react

React hooks for Wow queries. Workspace rules live in `typescript/AGENTS.md`.

## Commands

```bash
pnpm --filter @ahoo-wang/wow-react... build     # vite build, then scripts/verify-package.mjs
pnpm --filter @ahoo-wang/wow-react test         # vitest with coverage, then test:type
pnpm --filter @ahoo-wang/wow-react exec vitest run --maxWorkers=3 test/listStreamQuery.test.tsx
pnpm --filter @ahoo-wang/wow-react exec vitest run test/publicSurface.test.ts -u   # accept a surface change
```

## Structure

```
src/
  index.ts                               — re-exports hooks/ and types.ts only
  types.ts                               — the public types every hook shares: QueryStatus, QueryExecutor, ListStreamExecutor, QueryHookOptions, QueryHookReturn
  hooks/use{Single,List,Paged,Count}Query.ts — the request hooks on useQueryRunner; `execute` is typically a query client method
  hooks/useListStreamQuery.ts            — reads the stream itself and keeps `items` / `done`
  hooks/useFetcher*Query.ts              — the same runner with an endpoint executor as `execute`, and the endpoint as part of the request's identity
  internal/endpoint.ts                   — the Wow query endpoint protocol: postQuery, postQueryStream (QUERY_STREAM_ENDPOINT)
  internal/useQueryRunner.ts             — the one request state machine: latest wins, aborts, StrictMode, first frame
  internal/queryTransitions.ts           — its state transitions as pure functions (section 3.3 of the design doc)
  internal/useListStream.ts              — the runner plus readStreamRows, for both list-stream hooks
  internal/readStreamRows.ts             — reads an SSE stream into rows, publishing at most once per 16 ms
test/
  support/fakeServer.ts                  — fake global fetch behind a real Fetcher; SSE streams the test writes to
  queryHooks / fetcherQueryHooks / listStreamQuery .test.tsx — real behaviour: races, aborts, StrictMode, errors
  filterQueryTypes / listStreamTypes .test.ts — type tests; test:type type-checks every test file
  hookContract.types.test.ts             — the members of every options and return type, name by name
  publicSurface.test.ts, surface/root.txt — the public surface, name by name
scripts/verify-package.mjs               — holds the built entry to surface/root.txt and to react/compiler-runtime
```

The same-source contract runs the hooks against the example server:
`typescript/integration-test/test/wow/react/`.

## Boundaries

- React 19.3 or later only: the build runs the React Compiler and imports `react/compiler-runtime`. Do not add the `react-compiler-runtime` polyfill.
- Do not depend on `@ahoo-wang/fetcher-react`: the request state machine is this package's own (`src/internal/useQueryRunner.ts`), so its semantics are Wow's to freeze. `verify-package.mjs` fails a declaration that imports fetcher-react.
- The behaviour of every hook is the table in section 3.3 of `docs/design/refactor-2026-09.md`, pinned by `requestStateTable`, `streamStateTable`, `queryIdentity`, `callbacks`, `ssr` and `hydration`. Change a cell only on purpose, and say which in the pull request.
- Query types come from `@ahoo-wang/wow-client`; do not redefine them here.
- How a query reaches a Wow endpoint (POST, JSON body, the stream's `Accept` and extractor) lives only in `src/internal/endpoint.ts`; the stream path takes wow-client's `QUERY_STREAM_ENDPOINT` rather than repeating it.
- Tests do not mock the hooks' dependencies: use a real `Fetcher` and query client over `test/support/fakeServer.ts`.
- A stream hook never hands a `ReadableStream` to components; it owns, reads and cancels the stream.
- The hook names are the public API moved from fetcher-react. Every exported name is in `test/surface/root.txt`; change it only on purpose.
