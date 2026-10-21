---
title: Read Models
description: Why the snapshot is the default read model, the three query models, who sees what, and how reads are kept from slowing down writes.
---

# Read Models

Every read in Wow is derived from the log. This page explains which read models exist without code, when you write your own, who is allowed to see what, and how Wow keeps reads from hurting writes.

## The snapshot is the default read model

A snapshot is the latest committed state of one aggregate, with its metadata: version, tenant, owner, space, deletion mark, tags, the time of the first and last event, and the operator of the first and last commit. One copy serves three purposes:

| Purpose | How |
| --- | --- |
| Faster writes | On a cache miss, the kernel rebuilds the state from the snapshot plus the log entries after it. |
| Queries | The query protocol reads snapshots directly. The fields come from the state's schema in the Manifest; you write no projection. |
| Views and datasets | The view engine's record and analysis views are built on snapshots by default. |

The built-in `snapshot` subscription writes snapshots, guarded by version. When one batch holds several versions of the same aggregate, only the latest is written, so the write load grows with the number of aggregates, not the number of commits.

Write a projection of your own only when the snapshot cannot serve a read: a view that combines several aggregates, a shape the state does not have, a store the read needs to be in.

An aggregate that is only written and never queried, with a large state, can turn its snapshots off.

## Snapshots are a cache

A snapshot is derived, not a fact. The write side never needs to rebuild one: a snapshot that does not fit is simply not used, and the state is replayed from the log. Only reads need snapshots to be right, and Wow rebuilds them when they are not:

- **The state's structure changed in a way old snapshots cannot express**, for example an evolve function whose meaning changed, or a new field that must be computed from history: raise the aggregate's state revision. The new deployment rebuilds the stored snapshots from the log, in place and in the background. Adding an optional field needs no new revision.
- **A few snapshots are wrong**: a system command rebuilds the snapshots of given aggregates.
- **Snapshots move to another store**: the new store is filled from the start of the log and becomes the primary once it has caught up.

While a rebuild runs, queries keep answering, and their responses say that the data is being rebuilt.

## Three query models

| Model | One record is | Use it for |
| --- | --- | --- |
| `snapshot` | the latest state of one aggregate | lists, search, pages, counts and aggregations over current state |
| `events` | one commit of one aggregate: its version and its events | audit trails, timelines, analysis per event |
| `dataset` | a row of a wide table defined over published data, served from ClickHouse | analytics that join aggregates and contexts |

All three share one query engine: the same filter language, the same admission (schema checks, policies, budgets), the same observation and the same description of what a model can do. Each model brings its schema and its backend. What a backend can do, such as which operators or aggregations it supports, is part of the description a client reads before it builds a query, because it depends on the store, not on the model.

Besides queries, a client can read one aggregate's state directly: the latest state, the state at a version, the state at a point in time, or the trace of how the state changed commit by commit. Historical states are replayed from the log, within limits.

## Reading your own writes

A command waits for its snapshot by default ([Waiting for outcomes](./waits)), so a query that follows sees the write. A read of one aggregate's state can also ask for a minimum version, the one a command result returned: the service catches a lagging snapshot up with the log, waits briefly if it must, and answers 409 `wow.query.min-version-not-reached` with the current version if the aggregate has still not reached it. Before an aggregate's first snapshot is written, a read of its state is answered from the log.

## Who sees what

The same facts decide what a caller may read and what a command may change, and the field names that hold them are defined once, in the Manifest:

- **Tenant.** A query always reads within the caller's tenant.
- **Owner and space.** A caller whose scope has an owner reads only the aggregates it owns; a tenant-level caller reads all aggregates of its tenant. A space narrows both. An aggregate declared as belonging to the tenant, such as a product, is not filtered by owner.
- **Deleted aggregates** are left out unless the query asks for them.
- **Tags.** With attribute-based access control turned on, the caller's attributes are matched against each aggregate's tags. It is off by default.
- **Sensitive fields** are masked in what the caller gets.

What a caller may not see does not exist for it: reading it answers 404, never 403, and the reason goes only to the audit log.

## Reads must not slow down writes

Queries and commands often share a database and its connection pool. A slow query that holds a connection can starve the commands that need one. So each store has a query bulkhead:

- **A concurrency limit per store.** By default it is derived from the connections the queries share with the writes.
- **A short queue, then a refusal.** A query beyond the limit waits up to about 200 milliseconds for a free slot. If none frees up, or the queue is full, it is refused with 429 `wow.query.busy` and a `Retry-After`. A page that sends a burst of queries is absorbed; sustained overload is refused fast.
- **A limit that follows the writes.** The limit adjusts itself every second from the health of the write path: when appends slow down or wait for connections, it halves; when writes are healthy and queries are waiting, it grows by one.
- **Streams that resume.** A streamed list releases its slot between chunks and ends after a holding time. Each record it sends carries a position, so a client that is cut off, by the server, the network or a rolling upgrade, resumes where it stopped.
- **Budgets.** Every query has a time budget. A query that exceeds it fails with 504 `wow.query.timeout`, which is not worth retrying as it is. Counts and page totals are bounded unless an exact count is allowed.

Datasets on ClickHouse go through the same kind of bulkhead, so analytics cannot hold up the sink that writes to the same cluster.
