---
title: Upgrade a Deployment
description: Upgrade a service with a rolling update, what may run side by side, how formats, state revisions, events and the Manifest keep an upgrade safe, and what a major version changes.
outline: deep
---

# Upgrade a Deployment

A service is upgraded by a rolling update: new nodes start beside the old ones, the partitions move to them, and the old ones stop. For a while, two versions run at once, against the same database and the same topics. This page explains what makes that safe, for an upgrade of Wow and for an upgrade of your own code.

## Two versions side by side

Within one major version, adjacent minor versions of Wow run side by side in one service and across services. Their internal protocols, the cluster channel, the wait signals, the wire forms, and their stored formats stay compatible:

- **Stored formats** are versioned per structure, not per row: each log, snapshot and timer table records its format in `wow_format`. Within a major version the format does not change, and readers ignore what they do not know. A column or a field added within a format is added in place where a store does that today: ClickHouse's `_state` ([When the state schema changes](./clickhouse#when-the-state-schema-changes)) and the Elasticsearch mappings ([A field mapped later](./elasticsearch#field-mapped-later)).
- **Wire forms** (the Kafka envelope, commands, receipts, wait signals) carry their format too. A new optional field is read by one minor version before the next one writes it.
- **A new value of an enumeration**, such as a result's status, a hop's stage or a Manifest's `creation`, follows the same order: readers first, then writers. A reader older than its writer reads a value it does not know leniently: Wow's clients and nodes read an unknown status, stage or function kind as `UNKNOWN`, and a Manifest reader leaves out what an unknown value belongs to (a command of an unknown `creation`, an aggregate of an unknown `ownership`, a subscription of an unknown kind or start), reads an error's unknown `retry` as none, and logs it. A server still refuses an unknown value in a request.
- **A Manifest that changes what a part of it means** lists a marker in `requires`. A reader that does not understand one refuses the Manifest instead of misreading it.
- **An incompatible change** waits for the next major version, whose migration handles it.

So upgrade one minor version at a time, and let each rollout finish before the next.

## Your code: events

An event's class may change between two versions of your service, and both versions read the log during the rollout:

- A new optional field with a default needs nothing.
- Any other change raises the event's revision, with an upgrade function from the old one ([Evolve Events](../guide/event-evolution)). Deploy the reading side first: once every node can read revision `n + 1`, deploy the code that writes it. Otherwise an old node may read a record it has no upgrade for.
- A consumer in another service reads the new revision as a `PublishedEvent` until its api is upgraded, and is not called for it in a handler of the event's class ([Publish and Consume Streams](../guide/published-streams#events-the-subscriber-has-no-class-for)).

## Your code: state

A change of the state's structure raises `@AggregateRoot(stateRevision = n)`. During the rollout, the new nodes claim the snapshot table at the new revision and rebuild it in place, while the old nodes keep writing at the old one; the store keeps an old node from overwriting a rebuilt snapshot. A rollback lowers the claim again only once no node of the higher revision is left ([Rebuild and Replay](./replay#snapshots-raise-the-state-revision)).

The warehouse's flat view follows the same rule: a writer replaces it only with a newer definition, so an old node that restarts does not undo a new one's view ([ClickHouse](./clickhouse#schema-management)).

## Your code: the contract

The commands, events, state and errors a service exposes are its contract with its clients and peers. The Gradle plugin compares each build's Manifest with the committed baseline and fails a change that breaks a client: a removed command or event, a new required field, a field removed or retyped. Record an intended change with `updateWowManifestBaseline` and review it with the code ([Test a Service](../guide/testing-services#check-the-contract)). Within the baseline's compatibility line, the service's major version, the update refuses a breaking change: it needs the service's next major version, which records the break and the new line, or a new command beside the old one.

## Subscriptions during a rollout

A partition moves to a new node with its checkpoints, and the new node's subscriptions go on from there. A handler added in the new version starts by its kind ([Subscriptions](../concepts/subscriptions#replay-and-backfill)): a projection at the beginning; a reactor or a saga of its own context's log at the end; a reactor's or a saga's handler of another context's stream where its service joins it, at the beginning when no other subscription of the service has read it, else at the end. `@OnEvent(start = ...)` or `wow.subscriptions.[<context>.<processor.function>].start` overrides it.

A subscription's name is its identity, so check the renames before the rollout:

- **A renamed processor or handler is a new subscription.** It starts by the same rule, as if new: a saga or a reactor at the end, skipping whatever its old name had not handled yet, a projection at the beginning, which rebuilds its read model. Its old name's checkpoint and parked entries stay behind, unread.
- **To keep its position, pin the old name** with `@Name` on the renamed class or function.
- **The new nodes warn** at their first start of the checkpoints under names no subscription has any more; `GET /wow/ops/subscriptions` lists them ([The Ops Service and Console](./ops-console#rename-and-rewind)).
- **A new name is the only rewind**: no checkpoint is ever set back. Deploying a handler under a new name with an earlier `start` is how a stream is read again.

## Settings that must not change

`wow.log.partitions` is fixed once the log holds entries, and the nodes of one service share their store layout ([Configure a Service](./configuration#what-must-match-across-nodes)).

## A major version

A new major version may change formats and protocols. Its nodes do not run beside the previous major's: it comes with a migration, run before or during a cut-over, as V10 does from 9.x ([Migrating from 9.x](../migrate/)).

## From a 10.0.0 pre-release

A 10.0.0 pre-release (`alpha`, `beta`, `rc`) makes no compatibility promise for what it stores or for its API. Its stores are not carried over: start this version on empty stores. A MongoDB log a pre-release wrote has no tenant-scoped access keys, so an equality on the owner or the space finds none of its streams until a stamp writes their attributes again. A service coming from 9.x follows [Migrating from 9.x](../migrate/).

A pre-release that listed its snapshot stores changes its configuration before this version starts. The keys `wow.snapshot.stores`, `wow.snapshot.primary`, and an aggregate's `snapshot.stores` and `snapshot.primary` are gone, and each refuses the start, naming itself. Name each type's one store with `wow.snapshot.store` or the aggregate's `snapshot.store`. A deployment that kept a search copy, `snapshot.stores: [main, search]` with `query.snapshot.store: search`, drops `snapshot.stores` and keeps `query.snapshot.store`: the copy's subscription keeps its name, `wow.snapshot:search`, so its index and its checkpoints are kept, and nothing is read again.
