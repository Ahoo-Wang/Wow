---
title: Read State
description: Read an aggregate's state over HTTP or in Kotlin, at a version or an instant, trace its history, cache it with ETags, and read your own writes.
outline: deep
---

# Read State

Every aggregate's state can be read by its id, with nothing to configure: the latest state, the state at any earlier version or instant, and the history of commits that led to it. This page reads states over HTTP and in Kotlin. To find aggregates by their fields instead, query the snapshots: [Query Snapshots](./queries).

## Over HTTP

| Request | Answers |
| --- | --- |
| `GET /<context>/<aggregate>/{id}/state` | The latest state |
| `GET /<context>/<aggregate>/state` | The latest state of the caller's own aggregate, of one whose id is its owner's |
| `GET …/state?version=n` | The state at version `n` |
| `GET …/state?at=<instant>` | The state after the last commit at or before an ISO-8601 instant (write `+` as `%2B`) |
| `GET …/state?minVersion=n` | The latest state at version `n` or later: read your own write |
| `GET …/state/trace?from=&to=&limit=` | The commits from version `from` (1) to `to` (the last), at most `limit` of them, each with its events and the state after it |

Every read of an aggregate whose id is its owner's, `?version=`, `?at=`, `?minVersion=` and `/state/trace` included, is also served without the id: its id is the owner of the scope the caller reads in. A caller with no owner names the id; without one it is answered `400 wow.query.malformed`, and an unauthenticated caller `401 wow.auth.unauthenticated` ([One aggregate per owner](./ownership#one-aggregate-per-owner)).

A state answers its aggregate's `ref` and `version`, its `scope` (owner and space), its `deleted` mark, its `tags`, and the `state` itself. A trace answers `{"ref", "commits"}`: each commit's version, commit time, command and request ids, command type, events, and the aggregate after it. The next page of a trace starts after the last commit's version.

The latest state is the snapshot, or, before the aggregate's first snapshot, its log replayed. A deleted aggregate's state reads like any other, with `deleted` set.

## Who sees a state

A state is read as its caller sees it:

- The tenant is the caller's, or the one `Wow-Scope` names for a caller allowed to act across scopes.
- A state outside the caller's scope, or one the snapshot queries' policies hide from the caller (attribute-based access included, when it is on), answers `404 wow.aggregate.not-found`, exactly as a missing state does. Only the audit learns why. The scope is the caller's owner and space for an aggregate of `Ownership.OWNER`, and its space alone for one that belongs to its tenant ([Who reads it](./ownership#who-reads-it)). A policy that refuses the caller outright, such as a role check, answers `403 wow.auth.forbidden` instead.
- Every state, and every event of a trace, is masked as the snapshot queries mask it.

## Cache with ETags

A state answers `ETag: "<version>-<digest>"`, the digest a hash of the body as the caller reads it. A request with `If-None-Match` naming that tag answers `304 Not Modified`. The tag changes whenever what the caller reads changes, even at the same version: after a snapshot rebuild, an upgrade or a change of masks.

## Read your own write

A client that sent a command and wants to see its effect reads with the version the command's result gave it: `GET …/state?minVersion=7`. The node answers the latest state once it reaches that version, catching a lagging snapshot up with the log. A version not committed within `wow.query.state.min-version-wait` answers `409 wow.query.min-version-not-reached`, with the aggregate's current `version` and `Retry-After`.

A command that waits for its snapshot, the default wait, already returns after the snapshot holds its commit, so a read right after it sees it ([Waiting for Outcomes](../concepts/waits)).

## Replaying is bounded

Only the latest state is kept, in the snapshot. A read of an older version, an instant or a trace rebuilds the state it answers from the log, from version 1, since the snapshot is past it. Such reads run under the query bulkhead of the aggregate's log store (`429 wow.query.busy` when it is full), and replay at most `wow.query.state.max-replay` commits, 10,000 by default: a state further away answers `400 wow.query.invalid` with the violation `replay-too-long`. A trace returns 100 commits per page by default, and at most 1,000. The caller is checked on the latest state before anything is replayed.

- **Why the bound.** One read of an old state of a long-lived aggregate would otherwise replay its whole history on the node that serves it.
- **When to raise it.** A deployment that reads states far back in long histories, an audit view of an aggregate's ten-thousandth version say, raises `wow.query.state.max-replay`; each such read then costs up to that many commits.
- **What it does not bound.** The events: an aggregate's event history, however long, is read page by page through the events query ([Query Event Streams](./event-queries)). Only a state at an older version or an instant, and a trace, which carries the state after each commit, are bounded.

## In Kotlin

The host provides a `QueryApi` bean. Choose the caller with `of(caller)`, then read a state by its id:

<!-- sample: examples/eshop/e2e/src/integrationTest/kotlin/me/ahoo/wow/example/eshop/e2e/EshopClientTest.kt#read -->

```kotlin
// The ordering api's generated view of the order's state: the read finds the aggregate from it.
val orders = basket.bean<QueryApi>().of(alice).snapshots<OrderStateView>()
val state = orders.state("client-alice-1")!!
state.scope.ownerId.assert().isEqualTo("alice")
state.state.items.single().productId.assert().isEqualTo("mug")
state.state.totalAmount.assert().isEqualByComparingTo("17.00")
// The ordering on the memory backend serves its snapshot queries too: alice lists her one order.
orders.list(ListQuery.of(MatchAllFilter)).toList().size.assert().isEqualTo(1)
// A projection leaves out fields the view requires: it reads the rows as JSON.
val statuses = listQuery { projection { include(EshopOrderingContract.Order.STATE__STATUS) } }
val projected = orders.dynamic<ObjectNode>().list(statuses).toList().single()["state"]
projected.propertyNames().asSequence().toList().assert().containsExactly("status")
// Bob, another buyer, sees none of it.
val bob = Caller.of(scope = Scope("bob"), subject = "bob")
basket.bean<QueryApi>().of(bob).snapshots<OrderStateView>().state("client-alice-1").assert().isNull()
```

- `snapshots<OrderStateView>()` reads another context's aggregate, its state decoded as the view its api generates (`<State>View`, the state's fields; free-shape content an `ObjectNode`); `snapshots<ProductState>()` reads a type of this service, its state decoded as the class. `snapshots(type)` reads a type by its name, its state as JSON.
- A typed read reads whole states. A query that projects fields reads its rows through `dynamic<ObjectNode>()` (or a `Map`, or a class of the projected fields): a projection leaves out fields the view requires.
- `state(id)` answers `null` for a state the caller may not see, alike to a missing one. `state(id, minVersion)` reads your own write, and throws `MinVersionNotReachedException` when the version is not reached in time.
- `trusted()` reads as trusted code: no tenant, owner or space restricts it, and no policy that reads the caller, such as a role check, applies. Use it for system jobs, never for a request that has a caller.
- A peer listed under `wow.peers` is read over HTTP with the same calls ([Call Other Services](./calling-services#configure-a-peer-over-http)).
- In a saga, a projection or a reactor, `context.queries` reads the whole tenant of the commit being handled: no owner, space, attribute or role restricts it.

The same `QueryApi` runs the snapshot queries: [Query Snapshots](./queries).
