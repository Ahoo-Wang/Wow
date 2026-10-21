---
title: The Log Is the Bus
description: Why each bounded context keeps one partitioned event log, what a position in it means, and why Wow has no event bus.
---

# The Log Is the Bus

Every fact a Wow service knows is an entry in its event log. Snapshots, projections, published streams and the warehouse are all read from it. This page explains what the log is, how it is ordered, and why there is nothing else to write to.

## Why one write

A system that appends an event and then publishes it to a bus writes twice. If it crashes between the two writes, the event is committed but never published, and every observer misses it for good. Outboxes and retries narrow that gap; they do not close it.

Wow closes it by having one write. Committing a command is one atomic append to the log. Everything that reacts to the change reads the log, from a checkpoint it keeps itself. If an observer crashes, it reads on from its checkpoint when it restarts. Nothing is lost between a commit and its observers, because there is nothing between them.

## One log per bounded context

Each bounded context has its own log, private to the services of that context. Other contexts see what it publishes, never the log itself ([Between contexts](./cross-context)).

An entry of the log is one event stream: the events one command produced, with the facts around them.

| Part | What it holds |
| --- | --- |
| Identity | the aggregate, as context, aggregate type, tenant and id; the version this commit produced |
| Events | each event's name, revision and body, in order |
| Request | the command id, the request id and the command type that produced the events |
| Who | the operator the commit is recorded under, the owner and space of the aggregate after the commit, and the request facts admission allowed through |
| Causation | the root command of the chain and the direct cause, for commands a saga sent |
| Time | the commit time |

Beside the facts, an entry can carry transport data: what an open wait needs to be completed, reservations to confirm, trace context. Transport data only helps delivery. Domain code and queries never see it, and migration and replay drop it.

## Versions

Every aggregate has a version. Its first commit is version 1, and each commit adds exactly one. Two unique keys guard the log:

- **(aggregate, version)**: two writers that decided on the same version cannot both commit. The second gets a conflict and decides again on the fresh state.
- **(tenant, request id)**: the same request cannot commit twice. A retry with the same request id gets the first commit's result.

These two keys, not a single writer, are what make writing correct. See [Writing: admission to append](./write-path).

## Partitions

A log is split into a fixed number of partitions, 64 by default. An aggregate always lands in the same partition: the aggregate's identity, tenant included, is hashed to one of 4,096 slots, and the slot to a partition. Because the hash is part of the storage format, it is the same on every JVM and every version.

The number of partitions must be a power of two that divides 4,096, and it cannot change once the log holds entries. It is the throughput knob: commits within one partition are numbered one after another, so one partition commits about as fast as one storage round trip allows, and partitions commit in parallel.

Partitions are also the unit of ownership. Each node of a service owns a share of them through leases kept in storage. The owner of a partition executes the commands for its aggregates and runs the subscriptions over it.

## Positions

Within a partition, each entry has a position: a sequence number, assigned as the entry commits. **The order of positions is the order in which commits become visible.**

That rule is what makes reading safe. A commit time or an auto-increment id does not satisfy it: a transaction that took its id first can commit last, and a reader paging by id would skip it for good. Because positions follow visibility, a reader that reads a partition from a position, and reads on from the last position it saw, misses nothing and sees the same sequence every time.

The only ordering guarantee is per aggregate: one aggregate's events arrive in version order. Entries of different aggregates, even in one partition, even when one caused the other, have no order a reader may rely on.

## Backends

The log contract has no optional parts: a backend implements all of it, or it is not a log backend. A test kit holds every backend to the same contract.

| Backend | Use |
| --- | --- |
| PostgreSQL | The first choice. One statement checks the version, numbers the entry and inserts it; a conflict rolls it all back and leaves no gap. |
| MongoDB | A first-class backend. A transaction numbers the entry; the partition's owner commits in batches, so the database sees no contention. It needs a replica set. |
| Memory | Tests and examples. |

Each aggregate type has its own log table or collection, so access rights, change data capture and rebuilds work per aggregate. Partitions, positions and leases are still per context. Different aggregates of one context can keep their logs in different stores.

## Formats

The log is kept forever, so its format is a contract. The format version is recorded per table, and every wire structure carries its own. Adding an optional field with a default is not a format change: old readers ignore it and new readers fill in the default. Within a major version, a new field is first read by one minor version and only written by the next, so two adjacent minor versions can run side by side during a rolling upgrade. A change old readers cannot handle waits for the next major version and its migration tool.

Your own events change in a different way, through their names, revisions and upgrade functions. [Deciding and evolving](./programming-model#events-change-over-time) explains it.
