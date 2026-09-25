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
  hooks/use{Single,List,Paged,Count}Query.ts — the request hooks; `execute` is typically a query client method
  hooks/useListStreamQuery.ts            — reads the stream itself and keeps `items` / `done`
  hooks/useFetcher*Query.ts              — the matching hook above, with an endpoint executor as `execute`
  internal/endpoint.ts                   — the Wow query endpoint protocol: postQuery, postQueryStream (QUERY_STREAM_ENDPOINT)
  internal/fetcherReact.ts               — the only file that imports fetcher-react; not exported
  internal/readStreamRows.ts             — reads an SSE stream into rows for useListStreamQuery
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
- Import fetcher-react only in `src/internal/fetcherReact.ts`, and only through `@ahoo-wang/fetcher-react/core`. Its root entry exports query hooks with the same names and types that reference `@ahoo-wang/fetcher-wow`. No public type extends or names a fetcher-react type; `verify-package.mjs` fails a declaration that imports it.
- Query types come from `@ahoo-wang/wow-client`; do not redefine them here.
- How a query reaches a Wow endpoint (POST, JSON body, the stream's `Accept` and extractor) lives only in `src/internal/endpoint.ts`; the stream path takes wow-client's `QUERY_STREAM_ENDPOINT` rather than repeating it.
- Tests do not mock the hooks' dependencies: use a real `Fetcher` and query client over `test/support/fakeServer.ts`.
- A stream hook never hands a `ReadableStream` to components; it owns, reads and cancels the stream.
- The hook names are the public API moved from fetcher-react. Every exported name is in `test/surface/root.txt`; change it only on purpose.
