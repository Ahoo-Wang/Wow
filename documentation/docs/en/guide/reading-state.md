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
| `GET …/state?version=n` | The state at version `n` |
| `GET …/state?at=<instant>` | The state after the last commit at or before an ISO-8601 instant (write `+` as `%2B`) |
| `GET …/state?minVersion=n` | The latest state at version `n` or later: read your own write |
| `GET …/state/trace?from=&to=&limit=` | The commits from version `from` (1) to `to` (the last), at most `limit` of them, each with its events and the state after it |

A state answers its aggregate's `ref` and `version`, its `scope` (owner and space), its `deleted` mark, its `tags`, and the `state` itself. A trace answers `{"ref", "commits"}`: each commit's version, commit time, command and request ids, command type, events, and the aggregate after it. The next page of a trace starts after the last commit's version.

The latest state is the snapshot, or, before the aggregate's first snapshot, its log replayed. A deleted aggregate's state reads like any other, with `deleted` set.

## Who sees a state

A state is read as its caller sees it:

- The tenant is the caller's, or the one `Wow-Scope` names for a caller allowed to act across scopes.
- A state outside the caller's owner or space, or one the snapshot queries' policies hide from the caller, answers `404 wow.aggregate.not-found`, exactly as a missing state does. Only the audit learns why. The caller's owner is not checked for an aggregate that belongs to its tenant.
- Every state, and every event of a trace, is masked as the snapshot queries mask it.

## Cache with ETags

A state answers `ETag: "<version>-<digest>"`, the digest a hash of the body as the caller reads it. A request with `If-None-Match` naming that tag answers `304 Not Modified`. The tag changes whenever what the caller reads changes, even at the same version: after a snapshot rebuild, an upgrade or a change of masks.

## Read your own write

A client that sent a command and wants to see its effect reads with the version the command's result gave it: `GET …/state?minVersion=7`. The node answers the latest state once it reaches that version, catching a lagging snapshot up with the log. A version not committed within `wow.query.state.min-version-wait` answers `409 wow.query.min-version-not-reached`, with the aggregate's current `version` and `Retry-After`.

A command that waits for its snapshot, the default wait, already returns after the snapshot holds its commit, so a read right after it sees it ([Waiting for Outcomes](../concepts/waits)).

## Replaying is bounded

A read of an older version, an instant or a trace replays the log. Such reads run under the query bulkhead of the aggregate's log store (`429 wow.query.busy` when it is full), and replay a bounded number of commits, `wow.query.state.max-replay`: a state further away answers `400 wow.query.invalid` with the violation `REPLAY_TOO_LONG`. A trace returns 100 commits per page by default, and at most 1,000. The caller is checked on the latest state before anything is replayed.

## In Kotlin

The host provides a `QueryApi` bean. Choose the caller with `of(caller)`, then read a state by its id:

<!-- sample: example/eshop/e2e/src/integrationTest/kotlin/me/ahoo/wow/example/eshop/e2e/EshopClientTest.kt#read -->

```kotlin
val queries = basket.bean<QueryApi>().of(alice).snapshots(EshopOrderingContract.Order.TYPE)
val state = queries.state("client-alice-1")!!
state.scope.ownerId.assert().isEqualTo("alice")
(state.state as ObjectNode)["items"].size().assert().isEqualTo(1)
// On the memory backend the ordering serves no query model: a list is its 404, never a command.
val unserved = runCatching { queries.list(ListQuery.of(MatchAllFilter)).toList() }.exceptionOrNull()
(unserved as WowProblemException).status.assert().isEqualTo(NOT_FOUND)
// Bob, another buyer, sees none of it.
val bob = Caller.of(scope = Scope("bob"), subject = "bob")
basket.bean<QueryApi>().of(bob).snapshots(EshopOrderingContract.Order.TYPE).state("client-alice-1")
    .assert().isNull()
```

- `snapshots(type)` reads the aggregate type of another service, its state as JSON; `snapshots<ProductState>()` reads a type of this service, its state decoded as the class.
- `state(id)` answers `null` for a state the caller may not see, alike to a missing one. `state(id, minVersion)` reads your own write, and throws `MinVersionNotReachedException` when the version is not reached in time.
- `trusted()` reads as trusted code: no tenant, owner or space restricts it. Use it for system jobs, never for a request that has a caller.
- A peer listed under `wow.clients.contexts` is read over HTTP with the same calls ([Call Other Services](./calling-services#configure-a-peer-over-http)).
- In a saga, a projection or a reactor, `context.queries` reads the whole tenant of the commit being handled.

The same `QueryApi` runs the snapshot queries: [Query Snapshots](./queries).
