---
title: Upgrade a Deployment
description: Upgrade a service with a rolling update, what may run side by side, how formats, state revisions, events and the Manifest keep an upgrade safe, and what a major version changes.
outline: deep
---

# Upgrade a Deployment

A service is upgraded by a rolling update: new nodes start beside the old ones, the partitions move to them, and the old ones stop. For a while, two versions run at once, against the same database and the same topics. This page explains what makes that safe, for an upgrade of Wow and for an upgrade of your own code.

## Two versions side by side

Within one major version, adjacent minor versions of Wow run side by side in one service and across services. Their internal protocols, the cluster channel, the wait signals, the wire forms, and their stored formats stay compatible:

- **Stored formats** are versioned per structure, not per row: each log, snapshot and timer table records its format in `wow_format`. Within a major version the format does not change; a column added within it is added to existing tables when they are next checked, and readers ignore what they do not know.
- **Wire forms** (the Kafka envelope, commands, receipts, wait signals) carry their format too. A new optional field is read by one minor version before the next one writes it.
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

The commands, events, state and errors a service exposes are its contract with its clients and peers. The Gradle plugin compares each build's Manifest with the committed baseline and fails a change that breaks a client within the baseline's major version: a removed command or event, a new required field, a field removed or retyped. Record an intended change with `updateManifestBaseline` and review it with the code ([Test a Service](../guide/testing-services#check-the-contract)). A breaking change needs a new major version of the service's contract, or a new command beside the old one.

## Subscriptions during a rollout

A partition moves to a new node with its checkpoints, and the new node's subscriptions go on from there. A handler added in the new version starts by its kind: a projection at the beginning of the log, a reactor or a saga at the end. A renamed handler is a new subscription: it starts by its kind, as if new.

## Settings that must not change

`wow.log.partitions` is fixed once the log holds entries, and the nodes of one service share their store layout ([Configure a Service](./configuration#what-must-match-across-nodes)).

## A major version

A new major version may change formats and protocols. Its nodes do not run beside the previous major's: it comes with a migration, run before or during a cut-over, as V10 does from 9.x ([Migrating from 9.x](../migrate/)).

## From a 10.0.0 pre-release

A 10.0.0 pre-release (`alpha`, `beta`, `rc`) makes no compatibility promise for what it stores: the storage format of a later build may differ in place. Upgrading from a pre-release needs a fresh database; what an earlier pre-release wrote is not migrated or read.

### Configuration keys renamed before 10.0.0

The configuration surface was settled once before 10.0.0, without aliases: a key of an earlier pre-release binds to nothing. Each row names what took its place.

| Pre-release key | 10.0.0 |
| --- | --- |
| `wow.query.index.ddl` | Removed: the query indexes follow the `ddl` of their store (`wow.stores.<store>.ddl`, else `wow.ddl`), and `validate` refuses to start while one is missing |
| `wow.kafka.topics.ddl` | `wow.kafka.ddl`, which follows `wow.ddl` (`auto`) when not set; it was `validate` by default |
| `wow.clickhouse.sink.ddl` | `wow.clickhouse.ddl`, which follows `wow.ddl` when not set |
| `wow.clickhouse.sink.aggregates.<context>.<aggregate>.history.*` | `wow.aggregates.[<context>.<aggregate>].clickhouse.history.*` |
| `wow.kafka.command-intake` | `wow.kafka.command-intake.enabled` |
| `wow.internal.dev-profiles`, `wow.kafka.topics.dev-profiles` | `wow.dev-profiles` |
| `wow.query.http.*` | `wow.query.budgets.http.*` |
| `wow.query.in-process.*` | `wow.query.budgets.in-process.*` |
| `wow.query.in-process-timeout` | `wow.query.unbounded-timeout` |
| `wow.query.events.mongo-read-preference` | `wow.mongo.query-read-preference`, for the snapshot queries too |
| `wow.stores.<store>.query.read-preference` | `wow.stores.<store>.options.query-read-preference` |
| `wow.query.count-cache.events-enabled` | `wow.query.count-cache.events.enabled` |
| `wow.snapshot.store`, `wow.snapshot.targets` | `wow.snapshot.stores`; the first one's subscription is `snapshot` |
| `wow.aggregates.[<context>.<aggregate>].snapshot.store` | `wow.aggregates.[<context>.<aggregate>].snapshot.stores` |
| `wow.subscription.parked.min-backoff`, `wow.subscriptions.[<subscription>].parked.min-backoff` | `…parked.initial-backoff` |
| `wow.http.client-facts` | `wow.http.client-facts.enabled` |
| `wow.http.global-errors` | `wow.http.global-errors.enabled` |
| `wow.standby` | `wow.node.standby` |
