/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.wow.runtime.snapshot

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.snapshot.SnapshotRebuildRequested
import me.ahoo.wow.api.snapshot.SnapshotRebuilds
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.runtime.subscription.SubscribedEntry
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.subscription.SubscriptionFilter
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.spi.snapshot.SnapshotStore
import me.ahoo.wow.spi.snapshot.SnapshotTable
import me.ahoo.wow.spi.subscription.CheckpointStore
import me.ahoo.wow.spi.subscription.LeaseStore
import java.util.concurrent.ConcurrentHashMap

/** Where the subscriptions of one log group of a context keep their checkpoints: [group]'s, over [log]. */
@InternalWowApi
class LogProgress(val group: String, val log: EventLog, val checkpoints: CheckpointStore)

/**
 * Keeps the snapshot targets of one bounded context in step with the log and the code (decision D172), on every node,
 * every [SnapshotOptions.interval]:
 *
 * - **State revision claims**: each node is a member of its state revision of each snapshotted type (in the lease
 *   group `<group>#snapshot/<aggregate>@<revision>`), and claims each target's table ([SnapshotStore.claim]) at its
 *   revision: a higher revision at once (a new deployment, the start of a rolling upgrade), a lower one only once no
 *   member of the claimed revision is left (a rollback). Its snapshot subscriptions write at its revision whatever the
 *   claim; the store keeps a node of a lower revision from overwriting the higher one and marks those snapshots stale.
 * - **In-place rebuild**: a node whose revision a table is claimed at, and which holds the table's rebuild lease (the
 *   lease group `<group>#snapshot-rebuild/<store>/<aggregate>`, so one node rebuilds), rewrites its pending snapshots ([SnapshotStore.pending])
 *   from the log, page by page, at their latest version, and marks the table rebuilt once none is left. A node of
 *   another revision marks the table rebuilding on each run, so the stale marks its refused saves leave are rewritten.
 * - **Backfill**: a target that has not caught up with the log since it was first claimed is filled from the beginning
 *   of the log, by its store's subscription when that one has no checkpoint yet or is `snapshot` (the default store's,
 *   which wrote every type from the beginning before targets existed), else by a subscription of its own ([prepare],
 *   `<store subscription>#<aggregate>`) that stops writing once the target caught up. A target caught up
 *   once its subscription's checkpoint in every partition reached the log's head of the previous run; the table is
 *   then marked backfilled.
 * - **Rebuild requests**: the built-in subscription [REBUILD_SUBSCRIPTION] rewrites, from the log, the snapshots each
 *   `wow.SnapshotRebuildRequested` of the rebuild aggregate names ([SnapshotRebuildModel]), in each target: in place on
 *   a node of the claimed revision, else marked stale (or written at its own revision when missing) for the rebuild.
 *
 * It answers [rebuilding] for a type while the table of its primary target is rebuilding or not backfilled.
 */
@Suppress("LongParameterList", "TooManyFunctions")
@InternalWowApi
class SnapshotMaintenance(
    val context: String,
    private val group: String,
    private val node: String,
    models: Collection<AggregateModel<*>>,
    val targets: SnapshotTargets,
    private val kernel: Kernel,
    private val codec: EventCodec,
    private val log: EventLog,
    private val leases: LeaseStore,
    private val progress: List<LogProgress>,
    private val options: SnapshotOptions = SnapshotOptions(),
    private val address: () -> String = { node },
) : SnapshotStatus {
    private val models: Map<AggregateType, AggregateModel<*>> = models.associateBy { it.type }

    /** The snapshotted aggregate types of the context. */
    val types: Set<AggregateType> get() = models.keys
    private val placed: Map<String, Set<AggregateType>> = targets.storesOf(this.models.keys)
    private val tables = ConcurrentHashMap<Pair<String, AggregateType>, SnapshotTable>()
    private val tracked = ConcurrentHashMap<Pair<String, AggregateType>, String>()
    private val caughtUp: MutableSet<Pair<String, AggregateType>> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var previousHeads: Map<Pair<String, Int>, Long>? = null

    @Volatile
    private var loop: Job? = null

    /** The built-in subscription that carries out the rebuild requests of the context (decision D172). */
    val rebuildSubscription: Subscription = Subscription.builtIn(
        REBUILD_SUBSCRIPTION,
        SubscriptionFilter(context, setOf(SnapshotRebuilds.AGGREGATE)),
        FunctionKind.SNAPSHOT,
        ::rebuildRequested,
    )

    /** The table records last seen, by store and aggregate type. */
    fun tables(): Map<Pair<String, AggregateType>, SnapshotTable> = tables.toMap()

    override fun rebuilding(type: AggregateType): Boolean {
        if (type !in models) {
            return false
        }
        return tables[targets.placementOf(type).primary to type]?.catchingUp ?: true
    }

    /**
     * Claims every target's table at this node's revisions, and gives the subscriptions that backfill the targets
     * whose store subscription is already past the beginning of the log: register them before the node starts.
     */
    suspend fun prepare(): List<SnapshotSubscription> {
        heartbeat()
        val backfills = ArrayList<SnapshotSubscription>()
        placed.forEach { (store, types) ->
            types.forEach { type ->
                val table = claim(store, type)
                if (!table.backfilled) {
                    val subscription = targets.subscriptionOf(store)
                    // The default store's subscription, `snapshot`, has written every type from the beginning since
                    // before targets: another store's, past the beginning, has not written this one from it.
                    if (store != targets.default && progress.any { hasCheckpoint(it, subscription) }) {
                        val backfill = backfillOf(store, type)
                        tracked[store to type] = backfill.name
                        backfills += backfill
                    } else {
                        tracked[store to type] = subscription
                    }
                }
            }
        }
        return backfills
    }

    /** Runs [maintain] every interval in [scope], the first one an interval after [prepare], until [stop]. */
    fun start(scope: CoroutineScope) {
        loop = scope.launch {
            while (isActive) {
                delay(options.interval)
                try {
                    maintain()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (
                    @Suppress("TooGenericExceptionCaught") // A run that fails is logged; the next one tries again.
                    failure: Exception,
                ) {
                    logger.warn(failure) { "The snapshot maintenance of $context failed; it runs again." }
                }
            }
        }
    }

    /**
     * Stops the runs and leaves this node's revision memberships, so a rollback need not wait for them to expire. Call
     * it once the node's subscriptions stopped: a table claimed at another revision than this node's is marked
     * rebuilding once more, for the stale marks its last refused saves left.
     */
    suspend fun stop() {
        loop?.cancel()
        loop = null
        tables.forEach { (key, table) ->
            val (store, type) = key
            if (table.revision != revisionOf(type)) {
                storeOf(store).mark(type, table.revision, rebuilding = true)
            }
        }
        models.values.forEach { leases.leave(memberGroup(it.type, it.stateRevision), node) }
    }

    /** One run: claims, rebuilds, backfill checks; see the class. */
    suspend fun maintain() {
        heartbeat()
        // The heads serve the backfill checks alone: read while a target has not caught up, or before its table is known
        // (B.D diagnostics: once every target caught up, a run reads no partition counter).
        val catchingUp = placed.any { (store, types) -> types.any { tables[store to it]?.backfilled != true } }
        val heads = if (catchingUp) heads() else null
        placed.forEach { (store, types) ->
            types.forEach { type ->
                val table = claim(store, type)
                val revision = revisionOf(type)
                if (table.revision == revision) {
                    if (table.rebuilding) {
                        rebuild(store, type, revision)
                    }
                } else if (!table.rebuilding) {
                    // This node's refused saves may mark snapshots stale: the node of the claimed revision rewrites them.
                    storeOf(store).mark(type, table.revision, rebuilding = true)?.let { tables[store to type] = it }
                }
                if (!table.backfilled) {
                    checkBackfill(store, type, table)
                }
            }
        }
        previousHeads = heads
    }

    /**
     * Rebuilds the snapshot of [ref] from the log in each target of its type (a rebuild request, decision D172): in
     * place where the table is claimed at this node's revision; elsewhere marked stale, or written at this node's
     * revision when there is none, for the node of the claimed revision to rewrite.
     */
    suspend fun rebuild(ref: AggregateRef) {
        val model = models[ref.type] ?: return
        targets.placementOf(ref.type).targets.forEach { name ->
            val store = storeOf(name)
            val table = store.table(ref.type)
            if (table == null || table.revision == model.stateRevision) {
                recordOf(model, ref)?.let { store.rewrite(it) }
            } else {
                if (!store.markStale(ref)) {
                    recordOf(model, ref)?.let { store.save(it) }
                }
                store.mark(ref.type, table.revision, rebuilding = true)?.let { tables[name to ref.type] = it }
            }
        }
    }

    private suspend fun rebuildRequested(entry: SubscribedEntry) {
        val stream = entry.stream
        val type = AggregateType(context, stream.ref.id)
        stream.events.filter { it.name == SnapshotRebuilds.REBUILD_REQUESTED }
            .flatMap { json.readValue(it.payload, SnapshotRebuildRequested::class.java).ids }
            .distinct()
            .forEach { rebuild(type.ref(stream.ref.tenantId, it)) }
    }

    /** Keeps this node a member of its revision of each type. */
    private suspend fun heartbeat() {
        models.values.forEach {
            leases.heartbeat(
                memberGroup(it.type, it.stateRevision),
                node,
                address(),
                options.memberTtl
            )
        }
    }

    /** Claims [type]'s table in [store] at this node's revision, as the class says; the record after. */
    private suspend fun claim(store: String, type: AggregateType): SnapshotTable {
        val revision = revisionOf(type)
        val snapshots = storeOf(store)
        val table = snapshots.table(type)
        val claimed = when {
            table == null || revision > table.revision -> snapshots.claim(type, revision)
            revision < table.revision && leases.members(memberGroup(type, table.revision)).isEmpty() -> {
                logger.info { "No node of state revision ${table.revision} of $type is left: rolling back to $revision." }
                snapshots.claim(type, revision, lower = true)
            }

            else -> table
        }
        tables[store to type] = claimed
        return claimed
    }

    /** Rewrites a run's pages of the pending snapshots of [type] in [store], under the table's rebuild lease. */
    private suspend fun rebuild(store: String, type: AggregateType, revision: Int) {
        val lease = rebuildGroup(store, type)
        leases.acquire(lease, 0, node, options.leaseTtl) ?: return
        val snapshots = storeOf(store)
        val model = models.getValue(type)
        var after: AggregateRef? = null
        repeat(options.pages) {
            val pending = snapshots.pending(type, revision, after, options.batch)
            if (pending.isEmpty()) {
                if (after == null) {
                    snapshots.mark(type, revision, rebuilding = false)?.let { tables[store to type] = it }
                    leases.release(lease, 0, node)
                    logger.info { "The snapshots of $type in $store are rebuilt at state revision $revision." }
                }
                return
            }
            pending.forEach { ref ->
                val record = recordOf(model, ref)
                if (record == null) {
                    logger.warn { "The log holds no stream of $ref: its snapshot in $store is left as it is." }
                } else {
                    snapshots.rewrite(record)
                }
            }
            after = pending.last()
            leases.renew(lease, 0, node, options.leaseTtl)
        }
    }

    /**
     * Marks [type]'s target [store] backfilled once the subscription that fills it reached, in every partition of
     * every log group, the head the log had at the previous run.
     */
    private suspend fun checkBackfill(store: String, type: AggregateType, table: SnapshotTable) {
        val heads = previousHeads ?: return
        val subscription = tracked[store to type] ?: targets.subscriptionOf(store)
        val reached = progress.all { group ->
            (0 until group.log.partitions).all { partition ->
                val head = heads[group.group to partition] ?: return@all true
                (group.checkpoints.load(group.group, subscription, partition) ?: 0L) >= head
            }
        }
        if (!reached) {
            return
        }
        storeOf(store).mark(type, table.revision, backfilled = true)?.let {
            tables[store to type] = it
            caughtUp += store to type
            logger.info { "The snapshots of $type in $store caught up with the log." }
        }
    }

    /** The head of each partition of each log group, the empty ones left out. */
    private suspend fun heads(): Map<Pair<String, Int>, Long> = buildMap {
        progress.forEach { group ->
            for (partition in 0 until group.log.partitions) {
                group.log.head(partition)?.let { put(group.group to partition, it) }
            }
        }
    }

    private suspend fun hasCheckpoint(group: LogProgress, subscription: String): Boolean =
        (0 until group.log.partitions).any { group.checkpoints.load(group.group, subscription, it) != null }

    /** The subscription that backfills [type]'s target [store] alone, until it caught up. */
    private fun backfillOf(store: String, type: AggregateType): SnapshotSubscription = SnapshotSubscription(
        context,
        listOf(models.getValue(type)),
        storeOf(store),
        kernel,
        log,
        codec,
        name = "${targets.subscriptionOf(store)}#${type.name}",
        answers = { false },
        active = { (store to it) !in caughtUp },
        writeBatch = options.writeBatch,
    )

    /** The snapshot of [ref] rebuilt from its whole log, at [model]'s state revision; null when the log holds none. */
    private suspend fun recordOf(model: AggregateModel<*>, ref: AggregateRef): SnapshotRecord? {
        val streams = log.read(ref).toList()
        val state = kernel.replayed(model, ref, streams) ?: return null
        val first = streams.first()
        val last = streams.last()
        return codec.snapshotOf(
            model,
            ref,
            codec.stateText(state.state),
            last.version,
            state.scope,
            first.commitTime,
            last.commitTime,
            state.deleted,
            state.tags,
            last.operator,
            first.operator,
            state.schedules,
        )
    }

    private fun storeOf(name: String): SnapshotStore = targets.stores.getValue(name)

    private fun revisionOf(type: AggregateType): Int = models.getValue(type).stateRevision

    private fun memberGroup(type: AggregateType, revision: Int): String = "$group#snapshot/${type.name}@$revision"

    private fun rebuildGroup(store: String, type: AggregateType): String = "$group#snapshot-rebuild/$store/${type.name}"

    @InternalWowApi
    companion object {
        /** The built-in subscription of the rebuild requests (decision D172). */
        const val REBUILD_SUBSCRIPTION: String = "wow.snapshot.rebuild"

        private val json = WowJson.mapper
        private val logger = KotlinLogging.logger {}
    }
}
