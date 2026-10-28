---
title: 'wow-react reference'
description: 'The @ahoo-wang/wow-react package: its hooks, their options and results, and where the generated symbol index and the API report are.'
---

# wow-react reference

`@ahoo-wang/wow-react` keeps a Wow query in a React component: the query, its result, its loading state and its error, as React state, on the query types of `@ahoo-wang/wow-client`. How to use it is [React Hooks](../../../typescript/react); this reference lists what it exports.

## Entry

One entry, `@ahoo-wang/wow-react`. Its [symbol index](./symbols) lists every name it exports.

| Hook | Runs | Through |
| --- | --- | --- |
| `useSingleQuery`, `useFetcherSingleQuery` | A single query | A client's method, or a Fetcher and a URL |
| `useListQuery`, `useFetcherListQuery` | A list query | Same |
| `usePagedQuery`, `useFetcherPagedQuery` | A paged query | Same |
| `useCountQuery`, `useFetcherCountQuery` | A count | Same |
| `useStreamQuery`, `useFetcherStreamQuery` | A list, as server-sent events | Same |

Each hook takes a `Use…Options` and returns a `Use…Return`, both exported. The `use*Query` hooks run your `execute`, a generated client's method; the `useFetcher*Query` hooks post the query to a query route through a Fetcher. Every hook has a second overload typed by a query of your own, a subtype of its query type.

The shared types are exported too: `QueryStatus` (`'idle' | 'loading' | 'success' | 'error'`), `QueryExecutor`, and `QueryHookOptions` and `QueryHookReturn`, which every hook's options and result extend.

## The generated half

The symbol index page is generated from the built declarations by the package's `scripts/api-report.mjs`: every exported name, its kind and the first sentence of its TSDoc. The same run holds the entry's full signatures in its API report, `test/api/root.api.md`, and fails when the build differs from the committed report or page.

## See also

- [React Hooks](../../../typescript/react): the hooks in use.
- [wow-client reference](../wow-client/): the query types the hooks take.
- [Compatibility and Versions](../../../typescript/compatibility): React 19 and the other peers.
