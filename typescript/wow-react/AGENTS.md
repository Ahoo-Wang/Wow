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
  types.ts                               — the public types every hook shares: QueryStatus, QueryExecutor, ListStreamExecutor, QueryHookOptions, QueryHookReturn
  internal/fetcherReact.ts               — the only file that imports fetcher-react; not exported
  use{Single,List,Paged,Count}Query.ts   — hooks over fetcher-react's useQuery; `execute` is typically a query client method
  useListStreamQuery.ts                  — reads the stream itself and keeps `items` / `done`; readStreamRows.ts is internal
  fetcher/useFetcher*Query.ts            — the same queries POSTed to a URL through a Fetcher
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
- Import fetcher-react only in `src/internal/fetcherReact.ts`, and only through `@ahoo-wang/fetcher-react/core` and `@ahoo-wang/fetcher-react/fetcher`. Its root entry exports query hooks with the same names and types that reference `@ahoo-wang/fetcher-wow`. No public type extends or names a fetcher-react type; `verify-package.mjs` fails a declaration that imports it.
- Query types come from `@ahoo-wang/wow-client`; do not redefine them here.
- Tests do not mock the hooks' dependencies: use a real `Fetcher` and query client over `test/support/fakeServer.ts`.
- A stream hook never hands a `ReadableStream` to components; it owns, reads and cancels the stream.
- The hook names are the public API moved from fetcher-react. Every exported name is in `test/surface/root.txt`; change it only on purpose.
