# Reads, routes and the contract

This reference holds the read side, the HTTP surface and the Manifest as they stand before the V10 API freeze; it is rewritten once the freeze lands. Verify every route, header and member below against the target's version before relying on it.

## The Manifest and the routes

- `META-INF/wow/manifest/<module>.json`: aggregates (commands with `creation`, `produces`, `routes`; events with `revision`; `lifecycle`; `queries`; `schedules`), `subscriptions`, `published`, `errors`; schemas completed into `$defs` by `generateWowManifestFragment`. Without completion, snapshot queries cannot filter `state.*`.
- Routes, all from the Manifest:
  - commands: `POST /<ctx>/<agg>/{id}/<kebab-command>`, plus `POST /<ctx>/<agg>/<kebab-command>` for creating commands;
  - lifecycle: `DeleteAggregate` at the `DELETE` of `/<ctx>/<agg>/{id}`, the others at `POST /<ctx>/<agg>/{id}/wow.lifecycle.<kebab>`;
  - state: `GET /<ctx>/<agg>/{id}/state`;
  - state history: `GET /<ctx>/<agg>/{id}/state?version=` or `?at=`, and `GET …/{id}/state/trace`;
  - read your writes: `GET /<ctx>/<agg>/{id}/state?minVersion=<the command result's version>` (or `QueryApi` `state(id, minVersion)`): 409 `wow.query.min-version-not-reached` with the current `version` when it is not committed within the wait; paged totals are counted with their page (`wow.query.count-cache.paged-totals=false`), so they agree with the rows;
  - queries: `POST` and `GET` (the descriptor) of `/<ctx>/<agg>/{model}/query`, the model `snapshot` or `events` (one record per committed event stream, decision D235); the body's `kind` is `single`, `list`, `paged`, `cursor`, `count` or `aggregate`;
  - snapshot rebuild: `POST /<ctx>/snapshot/<agg>/wow.snapshot.rebuild` (decision D172);
  - datasets under `/wow/dataset`, the parked entries under `/wow/ops/parked` (`wow.ops.enabled`), the Manifest at `GET /wow/manifest`, the OpenAPI document at `GET /wow/openapi`.

## HTTP headers and problems

- Headers: `Wow-Wait` (`accepted`, `committed`, `snapshot`, `handled([ctx/]sub)`, chained with `>`; default `snapshot`), `Wow-Wait-Timeout`, `Wow-Request-Id`, `If-Match`, `Accept: text/event-stream`, `Wow-Scope`, `Wow-Space`. Errors are RFC 9457 problems, `type` `urn:wow:error:<code>`.
- A snapshot query on `state.*` fails when the fragment was not completed (no plugin, no fragment step).

## Clients

- Java code outside a domain calls commands and queries through `wow-client`'s facade, `JavaCommandClient.of(commandClient)` and `JavaQueryApi.of(queryApi)` (`me.ahoo.wow.client.java`): blocking or `…Async` (`CompletableFuture`), `List` and `java.util.stream.Stream` results, `java.time.Duration` timeouts; never call the blocking forms on a Netty or WebFlux thread.
- Over HTTP (decisions D257, D259): a context listed in `wow.clients.contexts.<context>` (`url` for the public routes, `internal-url` for the internal port, `mode: service|user`, default `wow.clients.mode`) is reached by the `CommandClient` and `QueryApi` beans over HTTP; its api jar must be on the class path. A console, gateway or tool that calls a peer without its jar says `contract: manifest` there and sends `NamedCommand("ctx.agg.Command", body)` (`me.ahoo.wow.client`, a `JsonNode` body): the peer's `GET /wow/manifest` must declare it, or the send fails before any request; a command object to such a peer is a configuration error. `WowClient.manifestOf(context, caller)` reads that Manifest (kept a minute), what the caller may be offered.
