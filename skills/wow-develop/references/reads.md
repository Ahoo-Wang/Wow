# Reads, routes and the contract

The job: serve and read the read side of a V10 service, and keep its contract (the Manifest) honest. The snapshot is the default read model: every aggregate's state, queryable without writing a projection. Verify every member and route below against the target's version.

## Which read

| Need | Use |
| --- | --- |
| One aggregate's state, now, at a version or a time | The state read (`GET …/{id}/state`, `QueryApi` `state(id)`) |
| Read your own write right after a command | `state(id, minVersion = <the command result's version>)`, or `?minVersion=` over HTTP; 409 `wow.query.min-version-not-reached` (with the current version) when it does not commit within `wow.query.state.min-version-wait` |
| Many aggregates by their state: filter, sort, page, count, aggregate | Snapshot queries |
| An aggregate's history | The `events` model (one record per commit) |
| Across aggregates or contexts, analytics | A dataset (a wide table the service, or an analytics catalog, publishes) |
| A shape the snapshot cannot serve (a denormalized view, a search engine) | A projection writing its own read model (`processes.md`) |

## Reading in Kotlin: `QueryApi`

`QueryApi` (`me.ahoo.wow.api.read`, a bean of the host) runs the same admission, visibility, bulkhead and budget as the public port:

- `queryApi.of(caller)` reads within that caller's scope; `queryApi.trusted()` reads every tenant (trusted code only); `trusted().unbounded()` drops the budget, under `wow.query.unbounded-timeout`.
- A subscription handler reads through its context's `queries`: the whole tenant of the commit it handles, as trusted code pinned to that tenant.
- `snapshots<S>()` (or `snapshots(type)`), `events(type)`, `datasets<R>(name)`; each has `single`, `list` (a `Flow`), `stream`, `paged`, `cursor`, `count`, `aggregate`. A typed snapshot row reads the whole record; a query with a projection goes through `dynamic<T>()`.
- Failures: `wow.query.invalid` (refused), `wow.query.timeout`, `wow.query.busy` (back off), `MinVersionNotReachedException`.

Queries are built with the DSL of `wow-query-api` (`me.ahoo.wow.api.query.dsl`: `singleQuery`, `listQuery`, `pagedQuery`, `cursorQuery`, `countQuery`, `aggregation`, `filter`), which builds the same objects the JSON reads as. With `wow-query-api` on the domain's compile class path, the compiler writes `<State>Properties` (`QueryField` constants of every queryable path) beside each state: use them instead of string paths, so a renamed field is a compile error.

## Declaring the read side on the state

Annotations of `wow-query-api` (`me.ahoo.wow.api.query.annotation`) on state fields, written into the Manifest:

- `@QueryIndex`: an index for filters and sorts that must stay index scans; the `ddl` of their store (`wow.ddl` by default) manages them at startup.
- `@Sensitive`: masked in every result; its level decides whether a query may still compare the raw value.
- `@QueryDecimal(scale)`, `@QueryDuration(unit)`, `@QueryTemporal`, `@QueryReference(aggregate)`: how consumers format, total and link a field; never inferred.
- `@QueryAlias`: the field's earlier paths, so saved queries keep working after a rename.
- `@Tags` on the state (`model.md`) feeds ABAC: `wow.query.abac.*` matches the caller's `abac.<key>` attributes against the aggregates' tags.

## The routes

- Routes, all from the Manifest:
  - commands: `POST /<ctx>/<agg>/{id}/<kebab-command>`, plus `POST /<ctx>/<agg>/<kebab-command>` for creating commands;
  - lifecycle: `DeleteAggregate` at the `DELETE` of `/<ctx>/<agg>/{id}`, the others at `POST /<ctx>/<agg>/{id}/wow.lifecycle.<kebab>`;
  - state: `GET /<ctx>/<agg>/{id}/state`;
  - state history: `GET /<ctx>/<agg>/{id}/state?version=` or `?at=`, and `GET …/{id}/state/trace`;
  - read your writes: `GET /<ctx>/<agg>/{id}/state?minVersion=<the command result's version>` (or `QueryApi` `state(id, minVersion)`): 409 `wow.query.min-version-not-reached` with the current `version` when it is not committed within the wait; paged totals are counted with their page (`wow.query.count-cache.paged-totals=false`), so they agree with the rows;
  - queries: `POST` and `GET` (the descriptor) of `/<ctx>/<agg>/{model}/query`, the model `snapshot` or `events` (one record per committed event stream, decision D235); the body's `kind` is `single`, `list`, `paged`, `cursor`, `count` or `aggregate`;
  - snapshot rebuild: `POST /<ctx>/snapshot/<agg>/wow.snapshot.rebuild` (decision D172);
  - datasets under `/wow/dataset`, the parked entries under `/wow/ops/parked` (`wow.ops.enabled`), the Manifest at `GET /wow/manifest`, the OpenAPI document at `GET /wow/openapi`.

The body of a query names its operation in `kind`; relative-time operators without `zoneId` read days in UTC, never the server's zone. Capabilities are not in the Manifest: they depend on the backend, so the `GET` descriptor is their authority, and the same query can be admitted on MongoDB and refused on PostgreSQL. Over HTTP: lists and pages are bounded (`wow.query.budgets.http.*`), totals are `BOUNDED` (an exact total is refused unless `allow-exact-count`), and a store's query bulkhead answers 429 `wow.query.busy` with `Retry-After`. Cross-tenant queries are off unless `wow.query.cross-tenant.enabled`, for a `crossScope` caller only, and audited.

## HTTP headers and problems

- Headers: `Wow-Wait` (`accepted`, `committed`, `snapshot`, `handled([ctx/]sub)`, chained with `>`; default `snapshot`), `Wow-Wait-Timeout`, `Wow-Request-Id`, `If-Match`, `Accept: text/event-stream`, `Wow-Scope`, `Wow-Space`.
- Errors are RFC 9457 problems (`application/problem+json`, `type` `urn:wow:error:<code>`), with `code`, `status`, `detail`, and `violations` for a refused query or command body. A domain rejection's status is the `@ErrorCode`'s, overridable per code with `wow.http.errors.[<code>].status`. The application's own routes answer problems too (`wow.http.global-errors.enabled`).

## The Manifest is the contract

- Each domain module writes `META-INF/wow/manifest/<module>.json`; the plugin completes its schemas (`$defs`, from Jackson's real output) and merges the service's Manifest (`build/wow/manifest.json`), served at `GET /wow/manifest`. Without completion, snapshot queries cannot filter `state.*`.
- Number fidelity: a Kotlin `Long`, `BigInteger` or `BigDecimal` is written with the schema `format` `int64`, `bigint` or `decimal`, so generated TypeScript clients keep it exact as a string; a custom serializer that writes such a value differently needs `schemaOverrides`.
- `checkManifestCompatibility` (part of `check`) compares the Manifest with `manifest/baseline.json` and fails on a break: a removed command or event, a new required field, an event schema changed without a new revision. Adding, removing or swapping those number formats is not a break. Update the baseline (`updateManifestBaseline`) only for a reviewed change, and in the same pull request.
- OpenAPI: `GET /wow/openapi` (`wow.http.openapi.enabled`) is derived from the Manifest; `wow.http.openapi.springdoc.*` bridges it into springdoc. TypeScript clients are generated from the Manifest, not from OpenAPI (`$wow-client`).
- The peer contract: the api jar of a context carries `META-INF/wow/contract.json`, which other contexts' builds read (`cross-context.md`).

## Clients

- Java code outside a domain calls commands and queries through `wow-client`'s facade, `JavaCommandClient.of(commandClient)` and `JavaQueryApi.of(queryApi)` (`me.ahoo.wow.client.java`): blocking or `…Async` (`CompletableFuture`), `List` and `java.util.stream.Stream` results, `java.time.Duration` timeouts; never call the blocking forms on a Netty or WebFlux thread.
- Over HTTP (decisions D257, D259): a context listed in `wow.peers.<context>` (`public-url` for the public routes, `url` for the internal port, `mode: service|user`, default `wow.peer.mode`) is reached by the `CommandClient` and `QueryApi` beans over HTTP; its api jar must be on the class path. A console, gateway or tool that calls a peer without its jar says `contract: manifest` there and sends `NamedCommand("ctx.agg.Command", body)` (`me.ahoo.wow.client`, a `JsonNode` body): the peer's `GET /wow/manifest` must declare it, or the send fails before any request; a command object to such a peer is a configuration error. `WowClient.manifestOf(context, caller)` reads that Manifest (kept a minute), what the caller may be offered.
