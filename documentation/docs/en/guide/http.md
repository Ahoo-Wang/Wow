---
title: Serve and Call the HTTP API
description: The routes a service serves from its Manifest, who the caller is, sending a command and choosing how long to wait, retries, streamed results, and errors.
outline: deep
---

# Serve and Call the HTTP API

A service serves an HTTP API derived from its Manifest: a route per command, the state of every aggregate, the snapshot and event queries, the service's datasets, and the Manifest and its OpenAPI document. There is no controller to write. This page covers what a client does with it: authenticate, send a command, wait for the outcome, retry safely, and read errors. [HTTP API](../reference/http) lists every route, header and field.

## The routes

| Route | Serves |
| --- | --- |
| `POST /<context>/<aggregate>/{id}/<command>` | A command to an aggregate |
| `POST /<context>/<aggregate>/<command>` | A creating command whose aggregate id the server gives |
| `DELETE /<context>/<aggregate>/{id}`, `POST …/{id}/wow.lifecycle.<command>` | The lifecycle commands |
| `GET /<context>/<aggregate>/{id}/state`, `…/state/trace` | An aggregate's state and history ([Read State](./reading-state)) |
| `POST /<context>/<aggregate>/snapshot/query`, `…/events/query` | The query models ([Query Snapshots](./queries), [Query Event Streams](./event-queries)) |
| `POST /wow/dataset/<context>/<dataset>/query` | A dataset of the service |
| `GET /wow/manifest`, `GET /wow/openapi` | The Manifest and the OpenAPI 3.1 document of these routes |

The routes are on the public port. The internal port serves the cluster and other services, never a client.

## Who the caller is

Every route authenticates its request through the application's `CallerResolver` bean, which turns the request's credentials into a caller: its tenant, its owner and space, its subject and attributes. In development, the eShop's ordering service reads two headers, standing in for an identity provider, and refuses them anywhere else:

<!-- sample: example/eshop/ordering/ordering-server/src/main/kotlin/me/ahoo/wow/example/eshop/ordering/server/OrderingCallers.kt#resolver -->

```kotlin
@Bean
@ConditionalOnProperty(COSEC_ENABLED, havingValue = "false", matchIfMissing = true)
fun orderingCallerResolver(development: DevelopmentMode): CallerResolver {
    development.require(COSEC_ENABLED, false, "takes its callers from headers any client sets")
    return CallerResolver { request ->
        val headers = request.headers()
        val user = headers.firstHeader(USER)?.trim()?.takeIf { it.isNotEmpty() } ?: return@CallerResolver Caller.ANONYMOUS
        val employee = headers.firstHeader(ROLE)?.trim() == EMPLOYEE
        Caller.of(
            tenantId = Caller.DEFAULT_TENANT,
            scope = if (employee) Scope.NONE else Scope(ownerId = user),
            subject = user,
            attributes = if (employee) mapOf(ROLES to listOf(EMPLOYEE)) else emptyMap(),
        )
    }
}
```

- A buyer is an **owner-level** caller: its scope has an owner, so it reaches only its own aggregates.
- An employee is a **tenant-level** caller: no owner, so it reaches every aggregate of its tenant, and action-level authorization decides what it may do.
- A resolver that returns `null` answers `401 wow.auth.unauthenticated`. Without a resolver, a node in development (a profile of `wow.internal.dev-profiles`, or an internal port on the loopback interface alone) serves every request as the anonymous caller of the default tenant; any other node refuses to start. A service that is anonymous on purpose declares a resolver bean returning `CallerResolver.ANONYMOUS`.

With `wow-cosec`, CoSec's resolver does this from the authenticated principal and its roles ([Secure a Deployment](../operate/security#authenticate-callers)). The eShop's services run that way behind the CoSec gateway: their `cosec` profile turns CoSec on, the header resolver above steps aside, and an employee is a principal with the role `employee` (`wow.cosec.tenant-level.roles`). The tenant, owner and space always come from the caller, never from the path or the body; a caller allowed to act across scopes names another in `Wow-Scope`.

## Send a command

```bash
curl -X POST localhost:8083/eshop-ordering/order/order-1/pay-order \
  -H 'Content-Type: application/json' -H 'X-User: alice' \
  -H 'Wow-Request-Id: 01JB2T6Q0Z' \
  -d '{"paymentId":"p-1","amount":29.00}'
```

The body is the command's JSON. A success answers `200` with the command's result: its `status` (`SUCCEEDED`), the aggregate's `ref` and `version` after the commit, and the `results` the decision added. `If-Match: "n"` sends the command only if the aggregate is at version `n`, else `412`.

## Choose how long to wait

By default a command answers once its snapshot holds the commit, so a read right after sees it. `Wow-Wait` asks for another point, as a chain of hops:

| `Wow-Wait` | Answers when |
| --- | --- |
| `accepted` | The command passed admission (`202`) |
| `committed` | Its events are appended |
| `snapshot` | Its snapshot holds the commit (the default) |
| `committed > handled(OrderProcessSaga.onOrderAwaitingValidation) > committed(eshop-inventory)` | The saga derived its commands and the inventory committed them |

A wait follows sagas across services: after a saga's `handled` hop, the next hops apply to the commands it derived. `Wow-Wait-Timeout` bounds the wait (`10s` by default, at most `wow.wait.max-timeout`). [Waiting for Outcomes](../concepts/waits) explains the model.

## Retry safely

Give every command a `Wow-Request-Id`. A retry with the same id is answered as already done, with `duplicate: true` and the original version, and is never decided twice. So:

- `504 wow.wait.timeout`: the command **may have committed**. Retry with the same request id.
- `503 wow.command.unknown`, `wow.storage.unavailable`, `wow.node.closing`: retry with the same request id.
- `429 wow.command.busy`: retry after `Retry-After`.
- A rejection (`4xx` with a domain code): do not retry; the same request is rejected again.

A creating command sent without an id gets one; a retry with the same request id gets the same one.

## Stream the outcome

With `Accept: text/event-stream`, a command answers one server-sent event per hop it reaches, named after the hop's stage, its data the result at that hop, until the wait ends. A client shows progress without polling: accepted, committed, then each service the chain crosses.

A snapshot `list` streams its rows the same way, resumable with `Last-Event-ID` ([Query Snapshots](./queries#over-http)).

## Read an error

Every error is an RFC 9457 problem, `application/problem+json`: its `type` is `urn:wow:error:<code>`, `code` and `status` repeat it, `violations` list what a request broke field by field, and a command's problem carries its whole result under `result`. A client branches on `code`. The status of a domain code is its `@ErrorCode(status)` ([Rejections and Error Codes](./rejections)); [Error Codes](../reference/error-codes) lists the framework's.

## Generated clients

Clients rarely write these requests by hand: the TypeScript client is generated from the Manifest, and Kotlin services call each other through the typed command client ([Call Other Services](./calling-services)).
