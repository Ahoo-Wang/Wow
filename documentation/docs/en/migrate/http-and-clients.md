---
title: HTTP and TypeScript Clients
description: What changes for the callers of a 9.x service, routes, headers, errors and the TypeScript packages, and how a front end moves to V10 with its service.
---

# HTTP and TypeScript Clients

V10 changes the routes, the headers, the errors and the client packages, and keeps no compatibility layer: every caller of a service moves with it. The order is fixed. The service's source migration produces its V10 Manifest first; the TypeScript clients are generated again from that Manifest; then the application is fixed by what the type check reports. The `wow-migrate` Skill does this part too, from its reference `http-and-clients.md`.

## Routes

| 9.x | V10 |
| --- | --- |
| `POST /<context>/[tenant/{tenantId}/][owner/{ownerId}/]<aggregate>/{id}/<action>`, with `PUT`, `PATCH` and custom actions | `POST /<context>/<aggregate>/{id}/<command>`, the command in kebab case; a creating command also without `{id}`. The tenant and owner come from the caller, never the path |
| `DELETE …/{id}`, `PUT …/{id}/recover`, `PUT …/{id}/tags` | `DELETE /<context>/<aggregate>/{id}`, `POST …/{id}/wow.lifecycle.restore-aggregate`, `POST …/{id}/wow.lifecycle.apply-tags` |
| `…/snapshot/{single,list,paged,cursor,count,aggregation}`, their `/state` forms, `…/event/…` | `POST /<context>/<aggregate>/snapshot/query` and `…/events/query`, the operation in the body's `kind` |
| `GET …/snapshot/schema` | `GET` on the same query route: the capability descriptor, with its `ETag` |
| `GET …/{id}/state/{version}`, `…/state/tracing` | `GET /<context>/<aggregate>/{id}/state` with `?version=`, `?at=` or `?minVersion=`, and `…/state/trace` |
| `PUT …/{id}/snapshot` (regenerate) | `POST /<context>/snapshot/<aggregate>/wow.snapshot.rebuild` with `{"ids": [...]}` |
| State-event resend, per-version re-dispatch | None until 10.x; a failed version is a parked entry, resumed under `/wow/ops/parked` |
| `/wow/command/send`, `/wow/command/wait` | The per-command routes |
| `/dataset/…` | `GET /wow/dataset`, `POST /wow/dataset/<context>/<dataset>/query` |

Read the 9.x paths from the service's generated 9.x OpenAPI, not from its annotations. Every gateway rule, CoSec policy and API document that names a 9.x path changes with it. [HTTP API](../reference/http) lists the V10 routes.

## Headers

| 9.x | V10 |
| --- | --- |
| `Command-Wait-Stage`, `-Context`, `-Processor`, `-Function`, `Command-Wait-Tail-*` | `Wow-Wait`: a plan of hops, `accepted`, `committed`, `snapshot`, `handled([context/]Processor.function)`, joined with `>`; `snapshot` by default |
| `Command-Wait-Timeout` | `Wow-Wait-Timeout`: `500ms`, `10s` or ISO-8601 |
| `Command-Request-Id` | `Wow-Request-Id` |
| `Command-Aggregate-Version` | `If-Match: "<version>"`; reads answer `ETag` |
| `Command-Tenant-Id`, `Command-Owner-Id`, `Command-Aggregate-Id` | The caller and the route; a caller allowed to act elsewhere sends `Wow-Scope`; `Wow-Space` picks one of the caller's spaces |
| `Command-Local-First`, `Command-Type`, `Command-Header-*` | Gone; the headers a service keeps as request facts are listed in `wow.http.request-facts` |
| `CoSec-Request-Id`, `CoSec-Space-Id` read as aliases | Not read: a gateway rewrites them to `Wow-Request-Id` and `Wow-Space` |

9.x wait stages map by meaning: `SENT` is `accepted`, `PROCESSED` is `committed`, `SNAPSHOT` is `snapshot`, and `PROJECTED`, `EVENT_HANDLED` and `SAGA_HANDLED` are a `handled` hop that names the subscription.

## Errors

A 9.x error body, `errorCode`, `errorMsg` and `bindingErrors`, is an RFC 9457 problem in V10, `application/problem+json`: `type` `urn:wow:error:<code>`, `title`, `status`, `detail`, `code`, `violations`, and the command's whole `result` once it reached its aggregate. Every code changed: the framework's are `wow.*` (`wow.aggregate.not-found`, `wow.aggregate.version-mismatch`, `wow.command.busy`), and a domain rejection is `<context>.<aggregate>.<code>`, 422 unless the service declares another status. Search the application for every 9.x code it matches, and map each ([Error Codes](../reference/error-codes)).

## TypeScript packages

| 9.x | V10 |
| --- | --- |
| `@ahoo-wang/fetcher-wow`, or a 9.x `@ahoo-wang/wow-client` | `@ahoo-wang/wow-client` of the V10 version, with the peers `@ahoo-wang/fetcher` and `@ahoo-wang/fetcher-eventstream` |
| `@ahoo-wang/fetcher-generator`, or a 9.x `wow-generator` reading `/v3/api-docs` | `@ahoo-wang/wow-generator` of the V10 version, reading the service's Manifest: `GET /wow/manifest` or its committed `manifest/baseline.json`. The OpenAPI-to-Wow path, the `fetcher-generator` command and `fetcher-generator.config.json` are gone |
| `@ahoo-wang/fetcher-react`'s Wow hooks, or a 9.x `wow-react` | `@ahoo-wang/wow-react` of the V10 version |
| `@ahoo-wang/fetcher-viewer` (frozen at 5.x), and `fetcher-react`'s data monitor it used | `@ahoo-wang/wow-view-engine`, rebuilt with a definition and a host |
| The 9.x view store's routes | `@ahoo-wang/wow-view-store`'s `WowViewStore` on the V10 view store service |

Keep every Wow package on one V10 minor ([Compatibility and Versions](../typescript/compatibility)). The fetcher repository's agent Skills for these packages, `fetcher-wow-cqrs` and `fetcher-openapi-generator`, became Wow's `wow-client` Skill. The `@ahoo-wang/fetcher` core packages stay in their own repository and are peers of every Wow package; no fetcher package depends on a Wow one.

## Client code

| 9.x | V10 |
| --- | --- |
| `CommandClient.send(…)` with `commandHeaders` and `waitStrategy({ stage: CommandStage.SNAPSHOT })` | The generated `<Aggregate>CommandClient.<command>(body, { id, wait: [wait.snapshot()] })` |
| `sendAndWaitStream` | The generated `…Stream` method |
| `QueryClientFactory`, `<aggregate>QueryClientFactory` | The generated `<Aggregate>SnapshotQueryClient`, `<Aggregate>EventQueryClient` and `<Aggregate>StateClient` |
| `pagedState`, `listState`, `singleState` | `paged`, `list`, `single`, answering whole records: read `record.state`, or project with a literal `include` |
| `getStateById(id)` | `<Aggregate>StateClient.get(id)`, with `minVersion` after a command |
| `EventStreamQueryClient`, records of `body[]` with `bodyType` | `EventQueryClient`: commits with `events[]` of `{ name, revision, body }`, `name` being `<context>.<aggregate>.<Event>` |
| `@ahoo-wang/wow-client/legacy` (`Condition`) | Gone: `filter.*` from `@ahoo-wang/wow-client/dsl` |
| `urlParams.path.{ tenantId, ownerId }`, `contextAlias` | Nothing, the caller decides them; `basePath` for a gateway prefix |
| `toWowError`, `ErrorCodes`, `errorCode`, `bindingErrors` | `error instanceof WowProblemError`: `code`, `status`, `violations`, `retry`; `FrameworkErrorCodes` and the generated `<Context>Contract.<Aggregate>.Errors` |
| A failed command resolving with its error code | Every refusal and every command that did not succeed rejects; `resolveRejected: true` resolves instead |

[TypeScript](../typescript/) describes the V10 clients.

## What changes without a type error

- **Error codes** are strings the compiler cannot check: grep for each 9.x code.
- **Exact numbers.** A `Long`, `BigInteger` or `BigDecimal` field is a `string` in V10's types, read exactly. Code that did arithmetic on a `number` now concatenates strings unless it is fixed.
- **Time zones.** A relative-time filter without `zoneId` reads its days in UTC, where 9.x used the server's zone: pass `zoneId` where a local day was meant.
- **Event names.** 9.x `bodyType` class names become `name` values; saved filters and screens keyed on them change.
- **Snapshot fields.** 9.x `eventId` and `snapshotTime` are gone; `firstEventTime`, `lastEventTime`, `operator`, `firstOperator` and `tags` remain.

## Steps

1. The service's V10 Manifest exists and passes its compatibility baseline.
2. Replace the packages, generate the clients from the Manifest into the same directory, commit `.wow-generator.json`, and delete the 9.x generator configuration and scripts ([Generate a Client](../typescript/generate)).
3. Run `tsc` and fix each error by the tables above, never with a cast or `any`; then the linter and the application's tests.
4. Search for what the type check cannot see: 9.x error codes, `bodyType`, 9.x paths in fetches, gateway rules and CoSec policies, relative times without `zoneId`, arithmetic on exact numbers.
5. Run the application against the V10 service, in development or staging: a command with its wait, a state read, a query, and a refusal shown to the user.

Kotlin and Java callers of a 9.x service's routes, `wow-apiclient` and `CommandGateway`, move to the `CommandClient` and `QueryApi` clients ([Calling Other Services](../guide/calling-services)).
