---
name: "wow-client"
description: "Write TypeScript code that calls Wow V10 services: clients generated from the service's Manifest with wow-generator, @ahoo-wang/wow-client commands with wait plans, state reads, snapshot/events/dataset queries and the filter and aggregation DSL, exact numbers, problem+json errors, CoSec, @ahoo-wang/wow-react hooks. Exclude Kotlin/Java services, view-engine definitions and hosts, 9.x client migration (wow-migrate), and developing these packages inside the Wow repository."
---

# wow-client

The job: an application written in TypeScript has to send a Wow V10 service commands and read its data, and the code has to keep working as the service's contract changes. The deliverable is application code on the clients `wow-generator` writes from the service's Manifest (or on `@ahoo-wang/wow-client` directly for a route no generated client covers), with the regeneration in CI, and its tests.

## Scope

- Downstream TypeScript code that imports `@ahoo-wang/wow-client`, `@ahoo-wang/wow-react` or `@ahoo-wang/wow-generator`, or is asked to call a Wow service.
- Not: Kotlin or Java code (`wow-develop`); view definitions and hosts (`wow-view-definition`, `wow-view-host`); answering a data question (`wow-data-query`); an application still on `@ahoo-wang/fetcher-wow`, `@ahoo-wang/fetcher-generator` or the 9.x `@ahoo-wang/wow-client` (`wow-migrate`); the packages themselves inside the Wow repository.

## Workflow

1. **Pin the versions.** The Wow packages release with Wow, from the same tag; keep them on one minor (`save-prefix=~` or exact). `wow-client` must be on the generator's minor. Node `>=22.12.0`, TypeScript 6 or later. Peers: `@ahoo-wang/fetcher` and `@ahoo-wang/fetcher-eventstream`.
2. **Generate from the Manifest** (`references/generate.md`): `wow-generator generate --input <manifest.json or GET /wow/manifest> --output src/generated`; commit the output with its `.wow-generator.json`, never edit it, and regenerate in CI with `--strict`.
3. **Commands** (`references/commands.md`): the generated `<Aggregate>CommandClient`, a wait plan for how far the call goes (`wait.snapshot()` to read your own write), the request id for a safe retry, and `WowProblemError` for every refusal.
4. **Reads** (`references/queries.md`): `<Aggregate>StateClient` for one aggregate (`minVersion` after a command), `<Aggregate>SnapshotQueryClient` and `<Aggregate>EventQueryClient` with the DSL from `@ahoo-wang/wow-client/dsl`, `DatasetClient` for datasets; `describe()` for what the backend admits.
5. **React** (`references/react.md`), **authentication** (`references/auth.md`) when the work needs them.
6. **Verify**: the type check is the first test (the generated types carry the contract); then the application's tests against a stubbed `fetch` or a running service. Report each command with its result.

## Rules that bite

- **No checkout is no reason to stop.** When the project, the sources or a shell are not available (only a diff, a snippet or a description), still deliver: write the test, the fix or the code against what was shown, mark each name to confirm in the project, and give the commands to run and what they should show. Never answer with only a plan or a question.
- **Exact numbers.** A Manifest field of a Kotlin `Long`, `BigInteger` or `BigDecimal` (format `int64`, `bigint`, `decimal`) is a `string` in the generated types, its digits as the server wrote them; the wire keeps JSON numbers. Generated clients read and write them exactly through `<Aggregate>Aggregate.textNumbers` (states, events, command bodies). Pass such a command field as a string (`unitPrice: '10.00'`) and compute with a decimal library, never `Number(...)`. A hand-written client passes `bodyNumbers` itself, or loses digits past 2^53.
- **Time zones.** A relative-time filter (`today`, `recentDays`, `beforeToday`, …) without `zoneId` reads its calendar days in UTC on the server; pass `zoneId` (`'Asia/Shanghai'`) for a local day.
- **Projected types.** A query written with a literal `projection: { include: [...] }` answers records narrowed to those paths; one built by a factory, or with an exclusion only, answers whole records.
- **Errors reject.** Every refused request and every command that did not succeed rejects with `WowProblemError` (`code`, `status`, `violations`, `retry`); switch on `FrameworkErrorCodes` and the domain's codes in the generated `boundedContext.ts`. Retry only as `error.retry` says, with the same request id when it says so; a `wow.wait.timeout` may have committed.
- **No tenant or owner in paths or bodies.** A command whose body names its aggregate (the Manifest's `idProperty`) is sent without an id: its generated method takes no id, and a computed one is left out of the body type (`Omit<Body, '<idProperty>'>`). The service takes the tenant and owner from the caller; `RequestOptions.scope` (`Wow-Scope`; a command's `targetTenant` and `targetScope`) and `space` (`Wow-Space`) only for a caller allowed to act elsewhere.
- **Snapshot fields are `state.<path>`** in filters and sorts; system fields (`aggregateId`, `version`, `tags`, …) are at the root.

## References

- `references/generate.md`: the generator from a Manifest, what each file holds, regeneration in CI, the OpenAPI path for non-Wow APIs.
- `references/commands.md`: command clients, wait plans and streams, request ids and retries, lifecycle commands, errors.
- `references/queries.md`: state reads, query clients, the DSL, streams and resumption, descriptors, exact numbers in reads, datasets.
- `references/react.md`: the `@ahoo-wang/wow-react` hooks.
- `references/auth.md`: CoSec and the Fetcher, scope and space, server-side rendering.

Confirm every signature in the installed typings (`node_modules/@ahoo-wang/wow-client/dist/*.d.ts`) at the pinned version before relying on an overload or default. Guides: https://wow.ahoo.me/typescript/ ; reference: https://wow.ahoo.me/reference/typescript/ .

## Related Skills

- $wow-develop: the Kotlin/Java service behind the client.
- $wow-view-host and $wow-view-definition: views over the same query routes.
- $wow-data-query: answering a question from the data instead of writing code.
- $wow-migrate: an application moving from 9.x clients.
