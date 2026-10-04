---
name: "wow-client"
description: "Write TypeScript code that calls Wow services: @ahoo-wang/wow-client commands and wait stages, snapshot/event queries, the filter and aggregation DSL, error handling, @ahoo-wang/wow-react query hooks, and typed clients generated from OpenAPI with the wow-generator CLI. Includes moving from @ahoo-wang/fetcher-wow or fetcher-generator. Exclude Kotlin/Java services, view-engine definitions and hosts, and developing these packages inside the Wow repository."
---

# wow-client

Calling a Wow service from TypeScript, with generated or hand-written clients. Full guides: https://wow.ahoo.me/guide/typescript/ ; API reference: https://wow.ahoo.me/reference/typescript/ .

## Workflow

1. Start from aggregate metadata: service, bounded context, aggregate name, tenant, and owner attribution. When the service publishes OpenAPI (`/v3/api-docs`), generate the clients (`wow-generator generate`) and treat that document as the source of truth; load `references/generator.md`.
2. Use `CommandClient` (or a generated `…CommandClient`) for writes and query clients for reads; keep the flows separate. Use `QueryClientFactory` when several query clients share metadata and attribution.
3. Build queries with the `filter.*` and `aggregation.*` builders, not ad hoc JSON. Aggregation groups and metrics take `(target, alias, options?)`, e.g. `aggregation.count('paid', { filter })`.
4. Build command headers with `commandHeaders({ … })` and `waitStrategy({ stage, timeoutMs?, tail? })`. `CommandClient` is not generic: pass the body type per call, `send<C>(request)`.
5. Handle failures as exceptions (below). Pass cancellation as the last argument of query methods: `abort` takes an `AbortController` or `AbortSignal` (`AbortSignal.timeout(ms)`, a data library's `signal`).
6. Load `references/api.md` for entry points, client methods, headers, errors, the DSL, key types and React hooks. Confirm exact signatures in the installed typings, or in `typescript/wow-client/src`, `typescript/wow-react/src` and `typescript/wow-generator/src` at the matching release, before relying on an overload, default or output shape.

## Packages

- `@ahoo-wang/wow-client` with peers `@ahoo-wang/fetcher`, `@ahoo-wang/fetcher-decorator` and `@ahoo-wang/fetcher-eventstream`; the fetcher packages keep their names. From Wow 9.2.1 the Wow packages accept them as `^5.1.5 || ^6.0.0`; 9.2.0 accepts `^5.1.5` only, so an application moving to fetcher 6 takes Wow 9.2.1 or later.
- React: `@ahoo-wang/wow-react` (React 19.0 or later). It runs its own request state machine and does not need `@ahoo-wang/fetcher-react`.
- Generation: `@ahoo-wang/wow-generator` and `typescript` as dev dependencies; generated code imports `@ahoo-wang/wow-client`, `@ahoo-wang/fetcher`, `@ahoo-wang/fetcher-decorator` and `@ahoo-wang/fetcher-eventstream` at run time, so the application depends on them directly.
- Entries: `@ahoo-wang/wow-client` exports everything except the deprecated `Condition` API; `/dsl` exports only the query DSL with no HTTP code; `/legacy` exports the `Condition` API for Wow 8.10 servers until v10.
- Supported servers: Wow 8.11 and later with `filter.*`; Wow 8.10 only through `/legacy`. Node `>=22.12.0`.

## Moving from fetcher packages

- `@ahoo-wang/fetcher-wow` → `@ahoo-wang/wow-client` in dependencies and imports; `Condition` builders from `@ahoo-wang/wow-client/legacy`; Wow query hooks from `@ahoo-wang/wow-react` instead of the `@ahoo-wang/fetcher-react` root. Then fix what the type check reports: `ErrorCodes.isSucceeded` removed, non-generic `CommandClient`, typed command headers, the `abort` parameter, aggregation builder arguments, `createLoadOwnerStateAggregateClient`.
- `@ahoo-wang/fetcher-generator` → `@ahoo-wang/wow-generator`: rename the script command (`fetcher-generator` stays an alias until v10) and `fetcher-generator.config.json` → `wow-generator.config.json`, then regenerate so imports switch to `@ahoo-wang/wow-client`.
- fetcher-wow and fetcher-generator are deprecated on npm. Guide: https://wow.ahoo.me/guide/typescript/migration.html.

## Gotchas

- Errors are exceptions: every refused request and every command whose processing failed rejects; it never resolves with a failure. Read it with `await toWowError(error)` (a `WowError` with `errorCode`, `errorMsg`, `bindingErrors`, `status`; `undefined` when Wow never answered) and switch on `ErrorCodes`. A stream that fails midway throws a `WowError` from `for await`; in `sendAndWaitStream` a failed stage arrives as a result whose `errorCode` is not `ErrorCodes.SUCCEEDED`. Resend a command only when Wow never answered (`undefined`), and with the same `requestId`; never after `RequestTimeout` (it may still complete) or `DuplicateRequestId`: read the state instead.
- Generated clients merge constructor options over their defaults: `new CartCommandClient({ fetcher })` keeps the bounded-context prefix (`example/owner/...`), which a gateway routes by. Calling the service directly, pass `basePath: ''` to command clients and `contextAlias: ''` to query factories.
- Authentication belongs to the Fetcher: register the service's Fetcher (for example `new NamedFetcher('default', { baseURL })`) and apply `@ahoo-wang/fetcher-cosec`'s `CoSecConfigurer`, which adds the Bearer token and fills `{tenantId}`/`{ownerId}`. Without CoSec, pass `urlParams: { path: { ownerId } }`. On a server, create a Fetcher per request that carries a user's credentials, never on the process-wide default (https://wow.ahoo.me/guide/typescript/ssr-and-node.html).
- Treat generated code as an output boundary: change the OpenAPI document or `wow-generator.config.json` and regenerate, never hand-edit. Commit the output with its `.wow-generator.json` manifest; in CI run with `--strict` (https://wow.ahoo.me/guide/typescript/regenerate-in-ci.html).
- An aggregate is generated only with a `{context}.{aggregate}` tag plus both `.snapshot_state.single` and `.snapshot.count` operations; otherwise it is skipped with a warning, not turned into a plain API client. Its clients land in `<output>/<contextAlias>/<aggregateName>/`: the output directory is never the context, so `-o src/generated/ecommerce` with tag `ecommerce.cart` writes `src/generated/ecommerce/ecommerce/cart/`.
- Keep command requests explicit about aggregate identity and the expected command result.

## References

- `references/api.md`: entry points, constructors, `CommandClient`, stages and headers, errors, query clients and loaders, `QueryClientFactory`, cursors, the filter and aggregation DSL, the legacy `Condition` API, key types, generated clients, React hooks, and a complete flow. Load it before writing client code.
- `references/generator.md`: CLI flags and exit codes, `CodeGenerator`, configuration, output layout, aggregate discovery, generated command, query and API clients. Load it for generation, regeneration, or reading generated code.

## Related Skills

- $wow-develop: the Kotlin/Java service behind the client.
- $wow-view-host: wiring the view engine and its declared actions; $wow-view-definition: what a view declares.
- Fetcher core, decorator, event-stream, CoSec and generic React hooks are documented by the fetcher project skills.
