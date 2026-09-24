# AGENTS.md — @ahoo-wang/wow-react

React hooks for Wow queries. Workspace rules live in `typescript/AGENTS.md`.

## Commands

```bash
pnpm --filter @ahoo-wang/wow-react... build
pnpm --filter @ahoo-wang/wow-react test
pnpm --filter @ahoo-wang/wow-react exec vitest run --maxWorkers=3 test/useFetcherCountQuery.test.ts
```

## Structure

```
src/
  use{Single,List,Paged,Count,ListStream}Query.ts   — hooks over fetcher-react's useQuery
  fetcher/useFetcher*Query.ts                        — the same queries sent through a Fetcher
test/                                                — Vitest (jsdom); filterQueryTypes.test.ts is type-checked by test:type
```

## Boundaries

- Import fetcher-react only through `@ahoo-wang/fetcher-react/core` and `@ahoo-wang/fetcher-react/fetcher`. Its root entry exports query hooks with the same names and types that reference `@ahoo-wang/fetcher-wow`.
- Query types come from `@ahoo-wang/wow-client`; do not redefine them here.
- Keep the hook names and options stable: they are the public API moved from fetcher-react.
