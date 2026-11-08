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

A 10.0.0 pre-release (`alpha`, `beta`, `rc`) makes no compatibility promise for what it stores: the storage format of a later build may differ in place. Upgrading from an alpha needs a fresh database; what it wrote is not migrated or read.

A log a released beta wrote still loads. Its streams and snapshots carry no access attributes until the access walk of each aggregate type has stamped them, and until then some reads leave them out:

- **What is hidden.** Under an access policy, a snapshot query leaves out the type's unstamped snapshots, and an events query its unstamped streams. Without one, an events query by a caller acting as an owner or in a space leaves out the unstamped streams. A caller of the whole tenant still reads them, and a state read, a version, a time or a trace is not affected.
- **Why.** A read decides by the access attributes a record carries: the policy's, or, on the events, the aggregate's owner and space of now, which each stream records so that its history follows the aggregate after a transfer ([Who sees what](../guide/event-queries#who-sees-what)). A record that carries none grants nothing, rather than showing to every caller.
- **How to watch it finish.** The first node of the new version starts the walks on its own. `wow.access.walk.pending` reads 1 for a type until its walk completes, and 0 once it has. `wow.access.walked` counts the aggregates walked so far, and the walking node logs `The access walk of <type> is completed` when it is done ([Rebuild and Replay](./replay#access-attributes)).

A dataset whose rows are an aggregate's with an access policy reads each row by the attributes of the aggregate's newest commit in the ClickHouse sink's `_state` ([Datasets](../guide/datasets#rows-of-an-aggregate-with-an-access-policy)). The walks stamp the log, not the warehouse, so for a while some rows are hidden from every caller the policy restricts:

- **Rows of an aggregate last written by a pre-release.** Its newest row in `_state` carries no attributes (`access` empty), so its rows are hidden until its next commit.
- **After a version declares a new access attribute.** Each aggregate's newest row lacks it, so a comparison of it sees no value, until the aggregate's next commit.

To show them before then, fill the warehouse again from the log: reset the sink's subscription of each such aggregate, `wow.clickhouse-sink.<aggregate>`, which writes every commit again with the attributes the log holds now, the newest of each aggregate kept in `_state` ([Rebuild and Replay](./replay#access-attributes), [Rebuild the warehouse](./clickhouse#rebuild-the-warehouse)).

`wow_catalog` gains an `access` column: whether the aggregate's access policy decides its rows ([wow_catalog](./clickhouse#wow-catalog)). A sink with `wow.clickhouse.ddl: auto` adds it at its first check; with `validate` it refuses its writes until the column is there: `ALTER TABLE <database>.wow_catalog ADD COLUMN IF NOT EXISTS access LowCardinality(String) AFTER columns_hash`. The rows an earlier sink wrote say nothing of a policy, so the analytics catalog reads them as a policy's: the catalog's datasets over those tables need revision, and their queries answer `409 wow.query.dataset-unavailable`, until the owning service runs this version, whose sink writes its rows again at its first check after it starts. Nothing needs replaying. A table whose aggregate has a policy stays refused: serve its dataset from the owning service ([Datasets](../data/datasets#define-a-dataset)).

The TypeScript packages follow: `@ahoo-wang/wow-client` has no `ApplyTags`, `TagsApplied` or `Tags`, a record's `tags` is its `access`, and a client `wow-generator` writes types each aggregate's access attributes. Regenerate the clients; [Commands](../typescript/commands#lifecycle-commands-scope-and-space) lists the steps.

A command on an aggregate outside the caller's scope is answered `404 wow.aggregate.not-found` from 10.0.0, as a read of it is; a pre-release answered `403 wow.aggregate.forbidden`, which only a creating command that names such an aggregate is still answered ([What the kernel checks](../guide/ownership#what-the-kernel-checks)). A client that told the two apart by the status shows its "not found" answer instead.

The OpenAPI document (`GET /wow/openapi`) types each aggregate's `access` by the attributes it declares, a single one's value or null and a multiple one's array, an integer as `int64`; an event stream's `access`, with the aggregate's `owner` and `space`, is null while the stream is not stamped. A pre-release's document had a `tags` member instead. Regenerate a client generated from it.

The configuration surface was settled once before 10.0.0, without aliases: a renamed or removed key refuses the node's start; the refusal names the key and points at the migration guide's configuration page, which maps each earlier key to its new one or says why it is gone.

### wow-cocache on CoCache 8

`wow-cocache` moved from CoCache 6 to CoCache 8, which neither reads nor writes what CoCache 6 stored. A service that shares states through it changes its code, its configuration and its deployment once.

**Code.** A Spring application changes nothing: the caches are created by the `CoCacheFactory` bean of CoCache's starter. Code that builds the caches itself passes CoCache's `CoCacheFactory`:

| Before | Now |
| --- | --- |
| `StateViewCaches(StateViewCaches.CacheFactory.of(runtime)) { queryApi }` | `StateViewCaches(CoCacheFactory(runtime)) { queryApi }` |
| An own `StateViewCaches.CacheFactory`, to change the caches' policy | `CoCacheFactory(runtime, layers)`, with a `CacheLayers` of your own |
| `StateViewCaches.specOf(...)`: a `CacheSpec` | A `VersionedCacheSpec` |
| `me.ahoo.cocache:cocache-coroutines` | Gone: the suspending views are in `cocache-core` |

**Configuration.** A key CoCache 8 does not define fails the start under `cocache.caches.[<name>]`, `cocache.defaults` and `cocache.overrides`, and the message says where the key goes: a CoCache 6 `ttl-forever` or `load-async-timeout`, a CoCache 6 `cocache.defaults.ttl` (now `cocache.defaults.cache.ttl`) or `cocache.defaults.key-prefix` (now `cocache.key-prefix`), or a cache name with dots written without its brackets (`cocache.caches.wow.eshop-ordering.order.ttl`). Anywhere else under `cocache`, such as `cocache.redis.*`, Spring Boot ignores it without a warning. Check every `cocache.*` key:

| CoCache 6 | CoCache 8 |
| --- | --- |
| `cocache.defaults.<field>` | `cocache.defaults.cache.<field>`, which fills only what a declaration does not write; `cocache.overrides.cache.<field>` forces a stricter value |
| A duration without its unit (`ttl: 600`, `cocache.redis.command-timeout: 2000`) | With its unit (`ttl: 10m`, `command-timeout: 2s`): a bare number fails the start |
| `ttl-forever: true` | `ttl: forever` |
| `load-async-timeout` | `load-timeout` |
| `load-max-concurrent: 0` | `load-max-concurrent: unlimited` |
| `cocache.defaults.key-prefix` | `cocache.key-prefix` |
| `cocache.async-executor`, `cocache.blocking-executor` | `cocache.executor` |
| `fill-in-transaction`, `invalidate-fills-on-rollback` | Under `cocache.defaults.cache.*` or `cocache.caches.[<name>].*` |
| `refresh-window` on a state view's cache: refused at start | Off by Wow's declaration, so `cocache.defaults.cache.refresh-window` does not reach it; `cocache.caches.[wow.<context>.<aggregate>].refresh-window` turns it on for one |

An entry under `cocache.caches` that names no cache, or a key its type does not have, fails the start too; `cocache.caches-validation: warn` logs these instead. [CoCache's upgrade guide](https://cocache.ahoo.me/guide/upgrade) lists the rest, the Redis ACL among them.

**Deleting a state.** CoCache's endpoint deletes a state view's key with `evict`, which keeps its version; `?purge=true` drops the version and needs `management.endpoint.cocache.allow-purge` ([Drop a state from the cache](../guide/extensions/cocache#drop-a-state-from-the-cache)).

**The cut-over.** The owner and every service that reads its states switch in one release window: a CoCache 6 node and a CoCache 8 node cannot share a cache, and nothing of the CoCache 6 cache is carried over. The state views are derived, so nothing is lost:

1. Stop every node, of the owner and of each reader, that runs CoCache 6.
2. Delete the state views' keys, on each master of a Redis cluster: `redis-cli --scan --pattern 'cocache:wow.*' | xargs redis-cli unlink` (with a key prefix of your own, that prefix). An owner's write onto a key that still holds a CoCache 6 value fails until the key expires or a reader's read replaces it, and its sink subscription retries and parks meanwhile.
3. Start the owner and the readers on the new version. The caches start empty: the owner's commits write the states again, and a reader's miss reads the owner.

A rollback is the same steps the other way.

### wow-cosec on CoSec 8.0.0-alpha.3

`wow-cosec` follows CoSec's pre-releases and moved from 8.0.0-alpha.2 to 8.0.0-alpha.3. A service in gateway mode (`wow.cosec.mode: gateway`) holds no secret, documents or Redis of CoSec's, and changes neither code nor keys. The CoSec gateway in front of it changes, and so does a service that decides for itself with documents in Redis.

**The data in CoSec's Redis.** CoSec 8.0.0-alpha.3 builds on CoCache 8, which does not read what alpha.2 wrote through CoCache 6. A document or a revocation still in the old form fails closed: the request is answered 503, for a revoked token too. With every CoSec instance stopped, either move the data with `cosec-migrate cocache8 --redis <uri> --key-prefix <prefix> --out <dir>` (`--dry-run` first), or empty that Redis and publish the documents again with `cosec-migrate publish --to-redis`.

**One key prefix.** A node that still sets one of these does not start, and the message names the key families that move. Copy the revocations and API keys kept under another prefix to the new one first, since they are no longer read there:

| CoSec 8.0.0-alpha.2 | CoSec 8.0.0-alpha.3 |
| --- | --- |
| `cosec.authorization.snapshot.key-prefix` | `cosec.key-prefix` |
| `cosec.authorization.cache.key-prefix` | `cosec.key-prefix` |
| `cosec.api-key.key-prefix` | `cosec.key-prefix` |

**The gateway** ([Configure the CoSec Gateway](./security#configure-the-cosec-gateway)):

- A route to your own service by `http://` or `https://` needs the metadata `cosec-identity: true`; without it the service reads no caller. A route to `lb://` forwards the identity as before.
- Credentials no longer go downstream by default: the `Authorization` header, the API key header, the `authorization` query parameter and the `authorization` cookie. A service in gateway mode reads none of them; one that reads them itself sets `cosec.gateway.identity.forward-authorization: true` and sits behind a route that receives the identity.
- A gateway that exposes an Actuator endpoint other than `health` on its application port does not start.

**Behaviour.**

- `cosec.authorization.snapshot.revalidate-interval` must be between 1 second and 5 minutes, or the node does not start.
- An id with an unpaired UTF-16 surrogate is refused: 401 in a token, 400 on the control API.
- A header that a document reads, sent more than once with different values, is answered 400.
- A path pattern over CoSec's cost bound does not compile (422 on the control plane).
- A `SnapshotDocumentStore` of your own must produce a 32-byte SHA-256 digest.

[CoSec's upgrade guide](https://cosec.ahoo.me/operations/upgrading#from-8-0-0-alpha-2) has the rest, the control plane among it.
