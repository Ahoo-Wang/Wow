---
title: V10 HTTP API
description: The command routes, the Wow-Wait header, state reads, snapshot and event queries, datasets and errors of a Wow V10 service.
outline: deep
---

# V10 HTTP API

The host builds the public routes from the service's Manifest alone, on the application's WebFlux server (`server.port`). No controller is written by hand. `GET /wow/manifest` answers the Manifest the routes come from (off with `wow.http.manifest.enabled: false`).

## Callers

Every route authenticates its request through the application's `CallerResolver` bean (`me.ahoo.wow.http.caller`). It returns the request's `Caller` (`me.ahoo.wow.runtime.admission`): its tenant, its scope (owner and space), its subject, whether it may act across scopes (`crossScope`) and further attributes. It returns `null` for a request without acceptable credentials, which is answered 401 `wow.auth.unauthenticated`.

```kotlin
@Bean
fun callerResolver(): CallerResolver = CallerResolver { request ->
    request.headers().firstHeader("X-User")?.let { user ->
        Caller(scope = Scope(ownerId = user), subject = user)
    }
}
```

Without a `CallerResolver` bean, every request is the anonymous caller: the default tenant (`wow.tenant.default`, `(0)`), no owner, no space. The host warns at startup and on the first request.

The tenant, owner and space of a command come from the caller, never from the path or the body. A caller with `crossScope` may name another with `Wow-Scope`.

### Two caller levels

Every aggregate belongs to its tenant, and to its owner and space when it has them. A caller is at one of two levels, as its resolver decides:

| Level | Caller's scope | Reads and writes |
| --- | --- | --- |
| Owner-level, such as a buyer | Has an owner | Only the aggregates of its owner (and space) |
| Tenant-level, such as an employee | No owner | Every aggregate of its tenant, an order of any buyer, say |

A part absent from the caller's scope is never checked, by the kernel on a command or by a query or a state read. So a tenant-level caller may ship any buyer's order, and its commit leaves the order's owner as it was. What a caller may do is the action-level authorization's (a `CommandAuthorizer`, CoSec's): the levels only decide which aggregates a caller reaches.

```kotlin
@Bean
fun callerResolver(): CallerResolver = CallerResolver { request ->
    val user = request.headers().firstHeader("X-User") ?: return@CallerResolver null
    val employee = request.headers().firstHeader("X-Role") == "employee"
    Caller(scope = if (employee) Scope.NONE else Scope(ownerId = user), subject = user)
}
```

With CoSec (`wow-cosec`), `CoSecCallerResolver` makes the principal its own owner, unless it has one of the roles of `wow.cosec.tenant-level.roles` or one of the policies of `wow.cosec.tenant-level.policies`: such a principal is a tenant-level caller. Its subject is its id either way, so the operator of what it commits is recorded.

An aggregate that every caller of its tenant reads, such as a product of a catalog, declares `@AggregateRoot(ownership = Ownership.TENANT)` ([Owners, spaces and tenants](./programming-model.md#owners-spaces-and-tenants)): no owner is recorded on it, and no read or write of it is restricted by the caller's owner. Its writes are then left to action-level authorization alone.

## Commands

| Method and path | Sends |
| --- | --- |
| `POST /<context>/<aggregate>/{id}/<command>` | The command to the aggregate `{id}` |
| `POST /<context>/<aggregate>/<command>` | A creating command to a new aggregate whose id the server gives it |

`<command>` is the command's name in kebab case: `AddCartItem` is `add-cart-item`. The body is the command's JSON; an empty body is `{}`.

A creating command sent without an id gets one at admission: when its `Wow-Request-Id` already created an aggregate (within the caller's scope), the id of that aggregate, without drawing a new one; otherwise a new one from the aggregate type's generator (`wow.aggregates.[<context>.<aggregate>].id-generator`). A retry with the same request id is therefore answered with the same id, as a duplicate. A committed id never changes. The ids are stable, not gap-free: two concurrent requests with one id may draw a number that is then not used. With a Bean Validation provider on the class path (`spring-boot-starter-validation`), the command's Jakarta constraints are checked before it is decided.

### Lifecycle routes

| Method and path | Sends |
| --- | --- |
| `DELETE /<context>/<aggregate>/{id}` | `DeleteAggregate` |
| `POST /<context>/<aggregate>/{id}/wow.lifecycle.restore-aggregate` | `RestoreAggregate` |
| `POST /<context>/<aggregate>/{id}/wow.lifecycle.transfer-owner` | `TransferOwner`, body `{"ownerId": "…"}` |
| `POST /<context>/<aggregate>/{id}/wow.lifecycle.transfer-space` | `TransferSpace`, body `{"spaceId": "…"}` |
| `POST /<context>/<aggregate>/{id}/wow.lifecycle.apply-tags` | `ApplyTags`, body `{"tags": {"name": ["value"]}}`, replacing the tags |

Only the lifecycle commands the aggregate lists (`@AggregateRoot(lifecycle)`) get routes.

### Request headers

| Header | Means |
| --- | --- |
| `Wow-Wait` | How far to wait before answering (below) |
| `Wow-Wait-Timeout` | How long to wait: `500ms`, `10s`, `1m` or ISO-8601 (`PT10S`); `wow.wait.default-timeout` (10 s) by default, at most `wow.wait.max-timeout` (60 s) |
| `Wow-Request-Id` | The caller's id of the request: a retry with the same id is answered as already done (`duplicate`), not decided again. The same id with another command is `wow.command.request-id-reused` |
| `If-Match: "n"` | The version the aggregate must be at; `wow.aggregate.version-mismatch` (412) otherwise |
| `Accept: text/event-stream` | One server-sent event per hop reached, until the wait ends |
| `Wow-Scope` | For a caller with `crossScope`: the tenant, owner and space to act in, an RFC 8941 dictionary: `tenant="t1", owner="u1", space="s1"` |
| `Wow-Space` | The space, among the caller's own, to act in; 403 `wow.auth.forbidden` when the resolver does not grant it |

The request headers that `wow.http.request-facts` names are copied into the command's `headers`, which are written into the event stream: list only request facts there (the calling application, say), never personal data or secrets.

### `Wow-Wait`

A wait plan is a chain of hops, separated by `>`:

```text
plan := hop (">" hop)*
hop  := "accepted" ["(" context ")"]
      | "committed" ["(" context ")"]
      | "snapshot" ["(" context ")"]
      | "handled(" [context "/"] subscription ")"
```

| Hop | Reached when |
| --- | --- |
| `accepted` | The command passed admission. Durable only for a command routed through Kafka |
| `committed` | The command's events are appended (or it was settled without appending) |
| `snapshot` | The snapshot holds the commit, so a read right after sees it |
| `handled(sub)` | The subscription `sub` (`processor.function`) handled the commit |

A hop without a context is in the command's context. After a saga's `handled` hop, the next hops apply to the commands the saga derived, possibly in another context:

```text
Wow-Wait: committed > handled(example/OrderSaga.onOrderCreated) > committed(inventory)
```

answers once the inventory has committed the reservations the order saga sent, or at once with the inventory's refusal. Without `Wow-Wait`, a command waits for `snapshot`, so the caller reads its own write.

- **Hops.** A plan has at most `wow.wait.max-hops` (16) hops; a node allows at most 32. Each saga a chain follows adds two hops: its own `handled` and the next `committed`. A checkout followed through four sagas to its payment takes 15.
- **Contexts the node does not know.** The node a wait starts on checks the hops of the contexts it has a Manifest of, its own and its peers'. A hop of any other context is let through when its name is a legal context name, and is checked where the chain arrives: a hop that names a subscription or a context the chain never reaches is never signalled, so the wait times out with the last hop it reached. With `wow.wait.unknown-contexts: reject`, such a hop makes the plan invalid (`wow.wait.invalid`) instead.

### Responses

- **200** with the `CommandResult` the wait ended with; **202** for a plan that ends at `accepted`. A 202 means the command will not be lost only when its aggregate type is routed through Kafka ([Kafka Router](./deployment.md#kafka-router)); otherwise it runs detached on the node that took it.
- **An RFC 9457 problem** (`application/problem+json`) when the command was rejected or failed: `type` is `urn:wow:error:<code>`, `code` and `status` repeat it, and the body carries the result. The status of a domain code is its `@ErrorCode(status)`, 422 by default, overridable per code with `wow.http.errors.[<code>].status`.
- **With `Accept: text/event-stream`**, the first result decides: an admission refusal is a problem; otherwise 200, then one event per result (its name the hop's stage, its data the result) until the wait ends. A rejection or failure after admission is the last event.

A `CommandResult` holds:

| Field | Holds |
| --- | --- |
| `commandId`, `requestId`, `correlationId` | The command, the caller's request, the root of the chain |
| `ref` | The aggregate: `context`, `name`, `tenantId`, `id` |
| `hop`, `function` | The hop reached (`stage`, `context`, `subscription`) and who produced the result |
| `status` | `SUCCEEDED`, `REJECTED` or `FAILED` |
| `version` | The aggregate's version after the commit |
| `duplicate` | The request was already committed and was not decided again |
| `error` | `code`, `message` and field `violations`, when it did not succeed |
| `results` | What the decide function put with `DecideScope.putResult`; empty on a duplicate |
| `derived` | On a saga's hop, the ids of the commands it derived |
| `signalTime` | When the result was produced, in epoch milliseconds |

## State reads

| Method and path | Answers |
| --- | --- |
| `GET /<context>/<aggregate>/{id}/state` | The latest state, with `ETag: "<version>"`; 304 for a matching `If-None-Match` |
| `GET …/state?version=n` | The state at version `n` |
| `GET …/state?at=<ISO-8601 instant>` | The state after the last commit at or before that instant (write `+` as `%2B`) |
| `GET …/state?minVersion=n` | The latest state at version `n` or later: read your writes, `n` the `version` of a command's result. A lagging snapshot is caught up with the log; a version not committed within `wow.query.state.min-version-wait` (2s) is 409 `wow.query.min-version-not-reached`, whose `version` is the current one, with `Retry-After` |
| `GET …/state/trace?from=&to=&limit=` | The commits from `from` (1) to `to` (the last), at most `limit`, each with its events and the state after it |

A state outside the caller's scope is 403 `wow.aggregate.forbidden`; a missing one 404 `wow.aggregate.not-found`. The caller's owner is not checked for an aggregate of its tenant (`Ownership.TENANT`). A deleted aggregate's state is read as any other, with `deleted` set.

## Queries

Each aggregate has two query models:

| Model | Path | Reads |
| --- | --- | --- |
| `snapshot` | `/<context>/<aggregate>/snapshot/query` | The aggregates' current snapshots |
| `events` | `/<context>/<aggregate>/events/query` | One aggregate's event streams, by version |

`POST` runs a query; `GET` answers the model's capability descriptor (its fields, operators and limits), with its version as the `ETag`. The body names the operation in `kind`, beside the query; [Query Language](./query.md) describes every filter, kind and answer, the Kotlin DSL and the query annotations:

| `kind` | Answers |
| --- | --- |
| `list` | Up to `limit` records (`wow.query.http.default-list-size`, 100, when none is given); `Accept: text/event-stream` streams them |
| `paged` | A page: `{"pagination": {"index": 1, "size": 20}}` |
| `cursor` | A page after a cursor, with the next cursor; needs a `sort` |
| `single` | One record, or 404 |
| `count` | `{count, exact}`; `exact` is false when there are more than `limit` (at most `wow.query.http.count-limit`, 10000) |
| `aggregate` | Grouped aggregations |

```bash
curl -X POST localhost:8080/example/order/snapshot/query \
  -H 'Content-Type: application/json' \
  -d '{"kind":"cursor","filter":{"op":"MATCH_ALL"},"sort":[{"field":"lastEventTime","direction":"DESC"}],"size":20}'
curl -X POST localhost:8080/example/order/events/query \
  -H 'Content-Type: application/json' -d '{"kind":"list","filter":{"op":"ID","value":"<order id>"}}'
```

- The caller sees only its own aggregates: its tenant, and its owner and space when it has them. A tenant-level caller, without an owner, sees every aggregate of its tenant. Query admission reads each aggregate's ownership from the Manifest: the caller's owner does not restrict the queries of an aggregate of its tenant (`Ownership.TENANT`). A query does not see deleted aggregates unless it names their deletion state.
- A snapshot query filters and sorts the system fields (`tenantId`, `aggregateId`, `version`, `ownerId`, `spaceId`, `deleted`, `tags`, `firstEventTime`, `lastEventTime`, `operator`, `firstOperator`) and `state.*`, as the state schema in the completed fragment describes it. `@QueryIndex` (`me.ahoo.wow.api.query.annotation`, in `wow-query-api`) on a state field declares an index the backend creates (`wow.query.index.ddl`); see [Query annotations](./query.md#query-annotations).
- The `events` model's filter names one aggregate's id and may bound `version` or `commitTime`, nothing else.
- A query the model refuses is 400 `wow.query.invalid`, its violations naming each rule; one that does not read as a query is 400 `wow.query.malformed`; a timeout is 504 `wow.query.timeout`, not worth running again as it is; a `single` query that matches nothing is 404 `wow.query.not-found`.
- The snapshots are queried on PostgreSQL and MongoDB. On the memory backend no query backend serves them, and their routes answer 404.
- Every HTTP query runs under the budget `wow.query.http.*` (see [Configuration Keys](./configuration.md#query)).

## Datasets

A dataset is a declared, read-only table for analysis, queried like a snapshot model:

| Method and path | Answers |
| --- | --- |
| `POST /wow/dataset/<context>/<dataset>/query` | A query of the dataset |
| `GET /wow/dataset/<context>/<dataset>/query` | Its capability descriptor, with an ETag |
| `GET /wow/dataset` | The datasets the caller sees |

A dataset is a JSON file named `<context>.<dataset>.json`, on the class path under `META-INF/wow/dataset/`, or in the working directory under `<wow.query.dataset.config-directory>/wow/dataset/` (`config/wow/dataset/` by default), which takes precedence. The shop's `example.orders` dataset reads the ClickHouse view of the order stream:

```json
{
  "title": "Orders",
  "identity": "id",
  "eventTime": "commit_time",
  "scope": { "tenant": "tenant_id", "owner": "owner_id", "space": "space_id" },
  "storage": { "table": "example_order_state_flat" },
  "fields": {
    "id": { "types": ["STRING"], "nullable": false },
    "aggregate_id": { "types": ["STRING"], "nullable": false, "description": "The order's id." },
    "version": { "types": ["INTEGER"], "nullable": false },
    "commit_time": { "types": ["STRING"], "semantic": { "type": "TEMPORAL_DATE" }, "nullable": false }
  }
}
```

Datasets are served only when a dataset backend is wired: today ClickHouse's, with `wow-clickhouse` on the class path and `wow.clickhouse.endpoint` set. The ClickHouse Sink writes each published stream to `<context>_<aggregate>_state_events`, with the views `<context>_<aggregate>_state`, `_events` and `_state_flat` (the state's top-level scalar fields as typed columns), which datasets read.

## Error codes

The framework's codes, as the Manifest lists them (`FrameworkErrors`):

| Code | Status | Means |
| --- | --- | --- |
| `wow.auth.unauthenticated` | 401 | The request carries no valid credentials |
| `wow.auth.forbidden` | 403 | The caller may not send this command, or use this `Wow-Space` or `Wow-Scope` |
| `wow.aggregate.forbidden` | 403 | The aggregate is outside the caller's scope |
| `wow.command.malformed` | 400 | The body, a header or a parameter cannot be read |
| `wow.command.invalid` | 400 | The command failed validation; the problem lists the violations |
| `wow.wait.invalid` | 400 | The wait is not a chain the runtime can follow; the command was not run |
| `wow.command.unhandled` | 400 | The aggregate does not handle this command |
| `wow.aggregate.not-found` | 404 | The aggregate does not exist |
| `wow.aggregate.exists` | 409 | The aggregate already exists |
| `wow.aggregate.version-mismatch` | 409 | The aggregate is not at the expected version; 412 when the request carried `If-Match` |
| `wow.command.request-id-reused` | 409 | The request id was used by another command |
| `wow.command.contended` | 409 | Concurrent writers kept winning the version; retrying with the same request id is safe |
| `wow.aggregate.deleted` | 410 | The aggregate is deleted; only `RestoreAggregate` applies |
| `wow.command.busy` | 429 | The node has too many commands in flight |
| `wow.command.unknown` | 503 | Whether the command committed is unknown; retrying with the same request id is safe |
| `wow.node.closing` | 503 | The node is shutting down; retry on another node |
| `wow.node.standby` | 503 | The node is on standby until a migration's cut-over: it serves reads only |
| `wow.subscription.parked` | 500 | A subscription on the wait chain parked the aggregate |
| `wow.wait.timeout` | 504 | The wait ran out of time; the command may have committed, and the problem carries the last stage reached |
| `wow.query.malformed` | 400 | The query body cannot be read as a query |
| `wow.query.invalid` | 400 | The query failed admission; the violations name each rule |
| `wow.query.failed` | 500 | The query backend failed |
| `wow.query.not-found` | 404 | A `single` query matched no record |
| `wow.query.dataset-not-found` | 404 | No dataset of that name is served to the caller |
| `wow.query.timeout` | 504 | The query ran longer than its budget allows; narrow it rather than retry |

`GET /wow/manifest` lists them under `errors`, and each context's own codes under the context.
