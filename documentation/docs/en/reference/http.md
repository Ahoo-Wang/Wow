---
title: HTTP API
description: Every route of a service's public port, its request headers, the Wow-Wait grammar, responses, server-sent events and problems.
outline: deep
---

# HTTP API

A service's public port serves these routes, materialized from its Manifest. [Serve and Call the HTTP API](../guide/http) explains how to use them; [Wire Protocols](./protocols) describes the bodies.

## Routes

**Commands**

| Method and path | Sends |
| --- | --- |
| `POST /<context>/<aggregate>/{id}/<command>` | The command, in kebab case, to the aggregate `{id}` |
| `POST /<context>/<aggregate>/<command>` | A creating command to a new aggregate whose id the server gives it; a command whose body names its aggregate, to that one; a mounted or `@Unaddressed` command, to the aggregate its transformer finds; any command of an aggregate whose id is its owner's (`idSource = OWNER`), to the caller's own |
| `DELETE /<context>/<aggregate>/{id}` | `DeleteAggregate` |
| `DELETE /<context>/<aggregate>` | `DeleteAggregate` of the caller's own aggregate, for an aggregate whose id is its owner's |
| `POST /<context>/<aggregate>/{id}/wow.lifecycle.restore-aggregate` | `RestoreAggregate` |
| `POST /<context>/<aggregate>/{id}/wow.lifecycle.transfer-owner` | `TransferOwner`, body `{"ownerId": "…"}` |
| `POST /<context>/<aggregate>/{id}/wow.lifecycle.transfer-space` | `TransferSpace`, body `{"spaceId": "…"}` |
| `POST /<context>/<aggregate>/wow.snapshot.rebuild` | A rebuild of the snapshots of `{"ids": [...]}` |
| `POST /<context>/wow.schedule/{job}/<command>` | A job command: `enable`, `reschedule`, `disable` |

An internal command (`@InternalCommand`, `"internal": true` in the Manifest) has its routes on the internal port alone, where the services' calls reach it: on this port a request to one is the `404` problem of any unknown path, and `GET /wow/openapi` leaves the command out ([Keep a command internal](../guide/aggregates#keep-a-command-internal)). Only the lifecycle commands an aggregate lists in `@AggregateRoot(lifecycle)` get routes. The body of a command is its JSON; an empty one is `{}`. A command whose body names its aggregate (`@AggregateId`) has the route without `{id}` alone, and the Manifest lists its `idProperty`, the property of the body that holds the id: a blank one is `wow.command.malformed` (400), and so is a request that names another aggregate beside the body. The snapshot rebuild and the job commands are system commands of Wow's built-in aggregates: a service without a command authorizer refuses them on this port (403 `wow.auth.forbidden`), never allowing them by default ([Authorize commands](../operate/security#authorize-commands)).

**Reads**

| Method and path | Answers |
| --- | --- |
| `GET /<context>/<aggregate>/{id}/state` | The latest state; `?version=n`, `?at=<instant>` or `?minVersion=n` |
| `GET /<context>/<aggregate>/{id}/state/trace` | The commits, `?from=`, `?to=`, `?limit=` (100, at most 1,000) |
| `GET /<context>/<aggregate>/state`, `…/state/trace` | The same, of the caller's own aggregate, for an aggregate whose id is its owner's |
| `POST /<context>/<aggregate>/snapshot/query` | A snapshot query |
| `POST /<context>/<aggregate>/events/query` | An events query |
| `GET` on either query path | The model's capability descriptor, with its version as `ETag` |
| `POST /wow/dataset/<context>/<dataset>/query`, its `GET` | A dataset query, its descriptor |
| `GET /wow/dataset` | The datasets the caller sees |

**The service**

| Method and path | Answers |
| --- | --- |
| `GET /wow/manifest` | The service's merged Manifest (`wow.http.manifest.enabled`), its internal commands among them, marked `internal` |
| `GET /wow/openapi` | The OpenAPI 3.1 document of these routes (`wow.http.openapi.enabled`): the internal commands are not among them, and each aggregate's records are typed by its Manifest, the `state` by its state schema and the `access` by the access attributes it declares |

A context's own service routes, the handlers its code declares, are served as its Manifest lists them. `wow.http.ops.enabled: true` (off by default) also serves the operator's routes: the parked entries under `/wow/ops/parked` and the read of the node's subscriptions, `GET /wow/ops/subscriptions` ([Ops Console](../operate/ops-console)).

Any other request under a context's path is a `404` problem.

## Request headers

| Header | On | Means |
| --- | --- | --- |
| `Wow-Request-Id` | Commands | The caller's id of the request: a retry with the same id is answered as done (`duplicate`), never decided twice; the same id with another command is `wow.command.request-id-reused` |
| `Wow-Wait` | Commands | How far to wait before answering (below); `snapshot` by default, `committed` for an aggregate without snapshots, where an explicit `snapshot` is `wow.wait.invalid` |
| `Wow-Wait-Timeout` | Commands | How long to wait: `500ms`, `10s`, `1m` or ISO-8601; `wow.wait.default-timeout` (10 s) by default, at most `wow.wait.max-timeout` (1 min) |
| `If-Match` | Commands | The version the aggregate must be at; `412 wow.aggregate.version-mismatch` otherwise ([Entity tags](./protocols#entity-tags)) |
| `If-None-Match` | State reads, descriptors | The last `ETag`; `304` when it still holds ([Entity tags](./protocols#entity-tags)) |
| `Accept: text/event-stream` | Commands, `list` queries | Server-sent events (below) |
| `Last-Event-ID` | Streamed `list` queries | The position to resume after |
| `Wow-Scope` | Every route | For a caller allowed to act across scopes: the tenant, owner and space to act in, an RFC 8941 dictionary, `tenant="t1", owner="u1", space="s1"`. On a query route, `tenant=*` or `tenant=("t1" "t2")` reads across tenants when `wow.query.allow-cross-tenant` is on |
| `Wow-Space` | Every route | The space, among the caller's own, to act in |

A command, or a request to a context's own service route, that carries `Wow-Call`, the header of a service's synchronous call, is refused on the public port with `403 wow.auth.forbidden`: calls are taken on the internal port alone. The headers `wow.http.request-facts` lists are copied into the command and written into its event stream.

## Wow-Wait

```text
plan := hop (">" hop)*
hop  := "accepted" ["(" context ")"]
      | "committed" ["(" context ")"]
      | "snapshot" ["(" context ")"]
      | "handled(" [context "/"] subscription ")"
```

| Hop | Reached when |
| --- | --- |
| `accepted` | The command passed admission; durable only for a command routed through Kafka |
| `committed` | Its events are appended, or it was settled without appending |
| `snapshot` | The snapshot holds the commit |
| `handled(sub)` | The subscription `sub`, `processor.function`, handled the commit |

A hop without a context is in the command's context. After a saga's `handled` hop, the following hops apply to the commands the saga derived, in their contexts. A plan has at most `wow.wait.max-hops` (16) hops. A hop of a context the node has no Manifest of is checked where the chain arrives, unless `wow.wait.unknown-contexts: reject` refuses it with `wow.wait.invalid`.

## Responses

| Status | When |
| --- | --- |
| `200` | The command's result at the end of its wait; a state; a query's answer |
| `202` | A command whose wait ends at `accepted` |
| `304` | A state or a descriptor whose `ETag` the request named |
| `4xx`, `5xx` | An RFC 9457 problem (below), with `Retry-After` when a retry may succeed later |

**Server-sent events.** A command with `Accept: text/event-stream` answers `200` once admitted, then one event per result as the wait goes on (a hop that several derived commands reach gives one each), its `id` the result's index, its name the hop's stage, its data the result, until the wait ends; an admission refusal is a problem instead. A streamed `list` answers one `row` event per record, its `id` the cursor of the row's position (none when the list's sort cannot be resumed), then an `end` event `{"reason", "resumable"}`: `complete` (`"resumable":false`) once every record was sent, `hold-expired` after `wow.query.stream.max-hold`, `node-stopping` when the node stops, or `permit-reclaimed` when the store had no query permit for the next chunk in time. In a resumable stream every reason but `complete` resumes with `Last-Event-ID`. A failure after the first row ends the stream with an `error` event holding the problem.

## Problems

Every error is `application/problem+json`:

| Member | Holds |
| --- | --- |
| `type` | `urn:wow:error:<code>`; `about:blank` for a problem that is only HTTP's, such as a missing route |
| `code` | The error code, a framework code (`wow.*`) or a domain code; absent on an `about:blank` problem |
| `status` | The HTTP status |
| `title` | A short summary of the code for people, the same for every occurrence: a framework code's title ([Error Codes](./error-codes)), a domain code's `@ErrorCode(description)` |
| `detail` | What happened to this request, for people: the rejection's or failure's message |
| `violations` | The field-level violations: `path`, `code`, `message`. A command constraint's `code` is the constraint's name in lower-kebab: `NotBlank` is `not-blank`. |
| `retry` | What a retry may do, on every problem: `none`, `same-request` (send again with the same `Wow-Request-Id`) or `new-request`; with `Retry-After` when it is not `none` |
| `result` | A command's whole result, when the command produced one |
| `version` | The aggregate's version the problem answered: a rejection's, or the current one of a state read below its `minVersion` |
| Any other member | A rejection's structured extensions, by name; one named like a member above, or `instance`, is left out |

A domain code's status is its `@ErrorCode(status)`, 422 by default, overridable per code with `wow.http.errors.[<code>].status`. [Error Codes](./error-codes) lists the framework's codes. The application's own routes answer their failures as problems too (`wow.http.global-errors.enabled`, on by default).
