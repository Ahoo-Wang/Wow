---
name: "wow-client"
description: "Build TypeScript clients for Wow services with @ahoo-wang/wow-client: commands and wait stages, snapshot/event queries, aggregate-state loaders, QueryClientFactory, resource attribution, the filter/aggregation query DSL, and React query hooks from @ahoo-wang/wow-react. Use for runtime Wow client code or consuming generated CQRS clients in downstream TypeScript apps, including moving from @ahoo-wang/fetcher-wow. Exclude OpenAPI code generation, Kotlin/Java Wow services, and developing the packages inside the Wow repository."
---

# wow-client

## Workflow

1. Start from aggregate metadata: service, bounded context, aggregate name, tenant, and owner attribution.
2. Use `CommandClient` for command writes and query clients for read-side access; keep those flows separate.
3. Use `QueryClientFactory` when multiple query clients share the same metadata and attribution path spec.
4. Build query conditions with the `filter.*` and `aggregation.*` builders instead of ad hoc JSON objects. Aggregation groups and metrics take `(target, alias, options?)`, e.g. `aggregation.count('paid', { filter })`.
5. Build command headers with `commandHeaders({ … })` and `waitStrategy({ stage, timeoutMs?, tail? })`; `CommandClient` is not generic, so pass the body type per call: `send<C>(request)`.
6. Handle failures explicitly: a refused request rejects (read it with `await toWowError(error)` and switch on `ErrorCodes`), a failed command resolves with a non-`ErrorCodes.SUCCEEDED` `errorCode`, and a stream that fails midway throws a `WowError` from `for await`.
7. Pass cancellation as the last argument of query methods: `abort` accepts an `AbortController` or an `AbortSignal` (`AbortSignal.timeout(ms)`, a data library's `signal`).
8. Load `references/api.md` for installation and entry points, constructors, client methods, command stages and headers, errors, query DSL operators, key types, generated clients, React hooks, and complete flows.

## Packages

- Install `@ahoo-wang/wow-client` with its peer dependencies `@ahoo-wang/fetcher`, `@ahoo-wang/fetcher-decorator`, and `@ahoo-wang/fetcher-eventstream`. Those fetcher packages keep their names; only the Wow client moved.
- For React, add `@ahoo-wang/wow-react`. It imports `@ahoo-wang/fetcher-react` only through the `@ahoo-wang/fetcher-react/core` and `@ahoo-wang/fetcher-react/fetcher` subpaths and needs `@ahoo-wang/fetcher-react` 5.1.3 or later.
- Entries: `@ahoo-wang/wow-client` exports everything except the deprecated `Condition` API; `@ahoo-wang/wow-client/dsl` exports only the query DSL (`filter`, `aggregation`, sort, projection, pagination, cursor and query factories) without loading any HTTP code; `@ahoo-wang/wow-client/legacy` exports the `Condition` API for Wow 8.10 servers until v10.
- Code moving from `@ahoo-wang/fetcher-wow` swaps the dependency and import specifiers to `@ahoo-wang/wow-client`, imports `Condition` builders from `@ahoo-wang/wow-client/legacy`, imports Wow query hooks from `@ahoo-wang/wow-react` instead of the `@ahoo-wang/fetcher-react` root, and fixes the first release's API changes the type check reports (`ErrorCodes.isSucceeded` removed, non-generic `CommandClient`, typed command headers, `abort` parameter, aggregation builder arguments, `createLoadOwnerStateAggregateClient`).

## Key Practices

- Keep command requests explicit about aggregate identity and expected command result behavior.
- Use generated clients when OpenAPI metadata is the source of truth.
- Confirm exact signatures in the installed package typings, or in the Wow repository sources under `typescript/wow-client/src` and `typescript/wow-react/src` at the matching release, before relying on an overload or default.
- Route generation-time questions to `wow-generator`; keep this skill focused on runtime Wow clients, the query DSL, and React query hooks.

## References

- `references/api.md`: Detailed package API, examples, and edge-case guidance. Load it only when the task needs imports and entry points, constructors, command APIs and headers, error handling, query clients, aggregate load clients, factory setup, query DSL operators, key types, generated clients, React query hooks, and end-to-end examples.

## Related Skills

- $wow-generator: Use to generate Wow CQRS clients from OpenAPI specs.
- Fetcher core, decorator, event-stream, CoSec, and generic React hooks are documented by the fetcher project skills.
