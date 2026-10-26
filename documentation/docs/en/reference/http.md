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
| `POST /<context>/<aggregate>/<command>` | A creating command to a new aggregate whose id the server gives it |
| `DELETE /<context>/<aggregate>/{id}` | `DeleteAggregate` |
| `POST /<context>/<aggregate>/{id}/wow.lifecycle.restore-aggregate` | `RestoreAggregate` |
| `POST /<context>/<aggregate>/{id}/wow.lifecycle.transfer-owner` | `TransferOwner`, body `{"ownerId": "…"}` |
| `POST /<context>/<aggregate>/{id}/wow.lifecycle.transfer-space` | `TransferSpace`, body `{"spaceId": "…"}` |
| `POST /<context>/<aggregate>/{id}/wow.lifecycle.apply-tags` | `ApplyTags`, body `{"tags": {"name": ["value"]}}`, replacing the applied tags |
| `POST /<context>/snapshot/<aggregate>/wow.snapshot.rebuild` | A rebuild of the snapshots of `{"ids": [...]}` |
| `POST /<context>/schedule/{job}/wow.schedule.<command>` | A job command: `enable`, `reschedule`, `disable` |

Only the lifecycle commands an aggregate lists in `@AggregateRoot(lifecycle)` get routes. The body of a command is its JSON; an empty one is `{}`.

**Reads**

| Method and path | Answers |
| --- | --- |
| `GET /<context>/<aggregate>/{id}/state` | The latest state; `?version=n`, `?at=<instant>` or `?minVersion=n` |
| `GET /<context>/<aggregate>/{id}/state/trace` | The commits, `?from=`, `?to=`, `?limit=` (100, at most 1,000) |
| `POST /<context>/<aggregate>/snapshot/query` | A snapshot query |
| `POST /<context>/<aggregate>/events/query` | An events query |
| `GET` on either query path | The model's capability descriptor, with its version as `ETag` |
| `POST /wow/dataset/<context>/<dataset>/query`, its `GET` | A dataset query, its descriptor |
| `GET /wow/dataset` | The datasets the caller sees |

**The service**

| Method and path | Answers |
| --- | --- |
| `GET /wow/manifest` | The service's merged Manifest (`wow.http.manifest.enabled`) |
| `GET /wow/openapi` | The OpenAPI 3.1 document of these routes (`wow.http.openapi.enabled`) |
| `GET /wow/id/global` | A new global id, as `text/plain` |

Any other request under a context's path is a `404` problem.

## Request headers

| Header | On | Means |
| --- | --- | --- |
| `Wow-Request-Id` | Commands | The caller's id of the request: a retry with the same id is answered as done (`duplicate`), never decided twice; the same id with another command is `wow.command.request-id-reused` |
| `Wow-Wait` | Commands | How far to wait before answering (below); `snapshot` by default |
| `Wow-Wait-Timeout` | Commands | How long to wait: `500ms`, `10s`, `1m` or ISO-8601; `wow.wait.default-timeout` (10 s) by default, at most `wow.wait.max-timeout` (1 min) |
| `If-Match: "n"` | Commands | The version the aggregate must be at; `412 wow.aggregate.version-mismatch` otherwise |
| `If-None-Match` | State reads | The state's last `ETag`; `304` when it still holds |
| `Accept: text/event-stream` | Commands, `list` queries | Server-sent events (below) |
| `Last-Event-ID` | Streamed `list` queries | The position to resume after |
| `Wow-Scope` | Every route | For a caller allowed to act across scopes: the tenant, owner and space to act in, an RFC 8941 dictionary, `tenant="t1", owner="u1", space="s1"`. On a query route, `tenant=*` or `tenant=("t1" "t2")` reads across tenants when `wow.query.cross-tenant.enabled` is on |
| `Wow-Space` | Every route | The space, among the caller's own, to act in |

A request that carries `Wow-Call`, the header of a service's synchronous call, is refused on the public port with `403 wow.auth.forbidden`: calls are taken on the internal port alone. The headers `wow.http.request-facts` lists are copied into the command and written into its event stream.

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
| `304` | A state whose `ETag` the request named |
| `4xx`, `5xx` | An RFC 9457 problem (below), with `Retry-After` when a retry may succeed later |

**Server-sent events.** A command with `Accept: text/event-stream` answers `200` once admitted, then one event per hop it reaches, named after the hop's stage, its data the result, until the wait ends; an admission refusal is a problem instead. A streamed `list` answers one `row` event per record, its `id` the row's position, then an `end` event: `{"reason":"complete"}`, or `{"reason":"hold-expired","resumable":true}` after `wow.query.stream.max-hold`.

## Problems

Every error is `application/problem+json`:

| Member | Holds |
| --- | --- |
| `type` | `urn:wow:error:<code>`; `about:blank` for a problem that is only HTTP's, such as a missing route |
| `code` | The error code, a framework code (`wow.*`) or a domain code; absent on an `about:blank` problem |
| `status` | The HTTP status |
| `title` | A short summary of the code for people, the same for every occurrence: a framework code's title ([Error Codes](./error-codes)), a domain code's `@ErrorCode(description)` |
| `detail` | What happened to this request, for people: the rejection's or failure's message |
| `violations` | The field-level violations: `path`, `code`, `message` |
| `result` | A command's whole result, when the command produced one |
| Any other member | A rejection's structured extensions, by name |

A domain code's status is its `@ErrorCode(status)`, 422 by default, overridable per code with `wow.http.errors.[<code>].status`. [Error Codes](./error-codes) lists the framework's codes. The application's own routes answer their failures as problems too (`wow.http.global-errors`, on by default).
