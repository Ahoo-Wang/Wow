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
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.runtime.state.ReplayGate
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.snapshot.SnapshotFill
import me.ahoo.wow.spi.snapshot.SnapshotTable
import me.ahoo.wow.spi.subscription.SubscriptionId
import java.util.concurrent.ConcurrentHashMap

/**
 * Who fills each target table of a [SnapshotMaintenance] from the log's beginning, and the backfills of this node
 * (the class's **Backfill**, **The first claim's reading** and **Nothing to backfill**): the subscription the table's
 * record names ([SnapshotTable.fill]), checked for being caught up the same on every node, and the backfill
 * subscriptions that write while their table is not marked backfilled, whichever node marks it.
 */
internal class SnapshotBackfills(
    private val context: String,
    private val models: Map<AggregateType, AggregateModel<*>>,
    private val targets: SnapshotTargets,
    private val kernel: Kernel,
    private val codec: EventCodec,
    private val log: EventLog,
    private val progress: List<LogProgress>,
    private val options: SnapshotOptions,
    /** How a backfill's rebuilds replay the log, as the subscriptions' do. */
    private val gate: ReplayGate,
) {
    /** The subscription that fills each table not caught up when this node met it. */
    private val tracked = ConcurrentHashMap<Pair<String, AggregateType>, String>()

    /** The tables this node read as marked backfilled: their backfill here writes no more. */
    private val caughtUp: MutableSet<Pair<String, AggregateType>> = ConcurrentHashMap.newKeySet()

    /** The tables a check found caught up but for aggregates their backfill parked, told once. */
    private val heldBack: MutableSet<Pair<String, AggregateType>> = ConcurrentHashMap.newKeySet()

    /**
     * Who fills [store]'s table, as its first claim records it, read before that claim: the store's subscription when
     * that one has no checkpoint in any partition, so it starts at the beginning; else a backfill. A subscription past
     * the beginning has not written the store from it, whatever its name: a store first claimed for the type, or one
     * that replaced the type's store, is filled from the log.
     */
    suspend fun firstFill(store: String): SnapshotFill =
        if (progress.any { hasCheckpoint(it, targets.subscriptionOf(store)) }) {
            SnapshotFill.BACKFILL
        } else {
            SnapshotFill.SUBSCRIPTION
        }

    /**
     * [type]'s [table] in [store], met not backfilled as the node prepares: its backfill subscription to register when
     * the record says a backfill fills it; else none, the table marked backfilled at once ([mark]) when the log holds
     * nothing ([empty]), the store's subscription then starting at a beginning that holds nothing.
     */
    suspend fun prepared(
        store: String,
        type: AggregateType,
        table: SnapshotTable,
        empty: suspend () -> Boolean,
        mark: suspend () -> SnapshotTable?,
    ): SnapshotSubscription? {
        val subscription = targets.subscriptionOf(store)
        if (table.fill == SnapshotFill.BACKFILL) {
            val backfill = backfillOf(store, type)
            tracked[store to type] = backfill.name
            logger.info {
                "Backfilling the snapshots of $type in $store from the log's beginning with ${backfill.name}: " +
                    "$subscription was past it when the table was first claimed."
            }
            return backfill
        }
        tracked[store to type] = subscription
        // Read before this node's runtime starts (the node prepares first): whatever commits afterwards, the
        // subscription the record names writes it from the beginning.
        if (empty()) {
            mark()
            caughtUp += store to type
            logger.info {
                "The snapshots of $type in $store need no backfill: $subscription writes them from the log's " +
                    "beginning, which holds nothing yet."
            }
        }
        return null
    }

    /**
     * Marks [type]'s [table] in [store] backfilled ([mark]) once the subscription its record names reached, in every
     * partition of every log group, the [heads] the log had at the previous run; and, for a backfill, once it holds no
     * aggregate parked in any group's ledger. A parked aggregate's entries count as done for the checkpoint, its
     * snapshot never written, and the mark ends the backfill ([backfillOf]'s `active`): its retry would then resolve the
     * entry and write nothing. So the table stays rebuilding until each is retried, and written, or skipped.
     */
    suspend fun check(
        store: String,
        type: AggregateType,
        table: SnapshotTable,
        heads: Map<Pair<String, Int>, Long>,
        mark: suspend () -> SnapshotTable?,
    ) {
        val subscription = fillerOf(store, type, table)
        val reached = progress.all { group ->
            (0 until group.log.partitions).all { partition ->
                val head = heads[group.group to partition] ?: return@all true
                (group.checkpoints.load(group.group, subscription, partition) ?: 0L) >= head
            }
        }
        if (!reached || table.fill == SnapshotFill.BACKFILL && parkedBy(store, type, subscription)) {
            return
        }
        if (mark() != null) {
            caughtUp += store to type
            logger.info { "The snapshots of $type in $store caught up with the log." }
        }
    }

    /**
     * Whether [subscription], the backfill of [type]'s table in [store], has an aggregate parked in a log group's ledger:
     * told once a table, as the first check that finds it caught up but for them.
     */
    private suspend fun parkedBy(store: String, type: AggregateType, subscription: String): Boolean {
        val id = SubscriptionId(context, subscription)
        val parked = progress.distinctBy { it.ledger }.sumOf { it.ledger.count(id, 1) }
        if (parked > 0 && heldBack.add(store to type)) {
            logger.warn {
                "The snapshots of $type in $store caught up with the log but for the aggregates $subscription parked: " +
                    "the table stays rebuilding until each is retried or skipped."
            }
        }
        return parked > 0
    }

    /** [type]'s table in [store] is read marked backfilled, by this node or another: its backfill here, if any, stops. */
    fun marked(store: String, type: AggregateType) {
        if (!caughtUp.add(store to type)) {
            return
        }
        val backfill = tracked[store to type]?.takeIf { it != targets.subscriptionOf(store) } ?: return
        logger.info { "The backfill $backfill of $type in $store stops: its table is marked backfilled." }
    }

    /** The subscription that fills [type]'s [table] in [store]: the one its record names. */
    private fun fillerOf(store: String, type: AggregateType, table: SnapshotTable): String = when (table.fill) {
        SnapshotFill.SUBSCRIPTION -> targets.subscriptionOf(store)
        SnapshotFill.BACKFILL -> backfillNameOf(store, type)
    }

    /** The name of the subscription that backfills [type]'s target [store]. */
    private fun backfillNameOf(store: String, type: AggregateType): String =
        "${targets.subscriptionOf(store)}#${type.name}"

    private suspend fun hasCheckpoint(group: LogProgress, subscription: String): Boolean =
        (0 until group.log.partitions).any { group.checkpoints.load(group.group, subscription, it) != null }

    /** The subscription that backfills [type]'s target [store] alone, until it caught up. */
    private fun backfillOf(store: String, type: AggregateType): SnapshotSubscription = SnapshotSubscription(
        context,
        listOf(models.getValue(type)),
        targets.stores.getValue(store),
        kernel,
        log,
        codec,
        name = backfillNameOf(store, type),
        answers = { emptySet() },
        active = { (store to it) !in caughtUp },
        writeBatch = options.writeBatch,
        writesInFlight = options.writesInFlight,
        gate = gate,
    )

    private companion object {
        /** The maintenance's own: these lines moved from it, and an operator's filter names it. */
        val logger = KotlinLogging.logger(SnapshotMaintenance::class.java.name)
    }
}
