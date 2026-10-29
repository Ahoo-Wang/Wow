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

import kotlinx.coroutines.flow.firstOrNull
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.kernel.FirstCommit
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.runtime.state.PublishedStateOptions
import me.ahoo.wow.runtime.state.PublishedStateReader
import me.ahoo.wow.runtime.state.PublishedStates
import me.ahoo.wow.runtime.subscription.EntryDecoder
import me.ahoo.wow.runtime.subscription.SubscribedEntry
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.subscription.SubscriptionFilter
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.spi.snapshot.SnapshotStore

/**
 * The built-in `snapshot` subscription of one bounded [context]: keeps the latest
 * snapshot of every aggregate of the [models] given, the aggregate types whose snapshots are enabled. Register
 * [subscription] in the context's [me.ahoo.wow.runtime.subscription.SubscriptionRuntime] and give [views] to the
 * [me.ahoo.wow.runtime.Executor]: it then runs on each partition's owner, checkpoints, parks and signals like any
 * subscription, and completes the default wait, `SNAPSHOT` (`HANDLED(snapshot)`). An aggregate
 * type not among [models] has no snapshot, and its default wait is `COMMITTED`.
 *
 * - **Merged writes**: of an aggregate's entries waiting together, only the latest is written; the waits on the
 *   earlier ones complete with it ([Subscription.coalesces]).
 * - **Batched writes**: the snapshots built while a write runs are written together, with one
 *   [SnapshotStore.saveAll] of at most [writeBatch] ([SnapshotWrites]), one commit on a transactional store. The
 *   partitions share [WRITE_LANES] writers: one per partition left most writes with a single
 *   snapshot, one for all of them could not keep up with a node's commits; each entry's
 *   handling, and so its wait signal and checkpoint, ends only once the write that covers it is durable, and a
 *   snapshot the store fails parks only its own aggregate.
 * - **The state**: read as every state consumer of the context reads it ([EntryDecoder.stateEvents]),
 *   as the reader [Hop.SNAPSHOT_SUBSCRIPTION] of the context's shared [views]: the view taken when the stream
 *   committed on this node; without one (a replay, an ownership change, a command run on another node) the stored
 *   snapshot or a nearer view evolved with the log's tail up to the stream ([Kernel.stateAt]), else the log replayed.
 * - **The record**: built by [EventCodec.snapshotOf]; its scope is the stream's, its deleted mark and
 *   tags the kernel's after the stream, its last event time and operator the stream's, its first event time and
 *   first operator those of the aggregate's first stream. The kernel reloads a deleted or tagged aggregate from it as such.
 * - **Idempotent**: [SnapshotStore.save] only ever replaces a lower version, so a replay or an old owner running
 *   alongside the new one never moves a snapshot back. When the store already holds the stream's
 *   version or a later one, as this subscription last wrote or loaded it, nothing is rebuilt or saved, from a view
 *   or without one, and the subscription passes the aggregate's views up to that version: a view recorded after its
 *   entry was written without it never costs a second write.
 *
 * - **Snapshot targets**: the subscription writes one [store], the target it is [name]d after
 *   ([SnapshotTargets.subscriptionOf]); a context with several targets runs one per store, each with its own
 *   checkpoints. A wait's `snapshot` hop of an aggregate is answered by the subscription of its type's primary target
 *   ([answers]); a subscription that backfills a target stops writing a type once it is no longer [active] for it.
 * - **State revisions**: the snapshot carries the model's state revision; the store never lets it
 *   replace one of a higher revision, and marks that one stale instead, for the rebuild.
 *
 * [codec] must be the kernel's, so the views and the fingerprints are what the kernel reads back. [views] are the
 * context's, where this subscription is the reader [name] of the snapshotted types; by default its own, bounded by
 * [capacity]. [capacity] also bounds the versions, first event times and first operators kept per aggregate.
 */
@Suppress("LongParameterList")
@InternalWowApi
class SnapshotSubscription(
    context: String,
    models: Collection<AggregateModel<*>>,
    private val store: SnapshotStore,
    kernel: Kernel,
    private val log: EventLog,
    private val codec: EventCodec = EventCodec(),
    views: PublishedStates? = null,
    capacity: Int = PublishedStateOptions.DEFAULT_CAPACITY,
    /** The subscription's name, and its reader's: `snapshot` unless it writes another target than the default. */
    val name: String = Hop.SNAPSHOT_SUBSCRIPTION,
    /** Whether the subscription answers the `snapshot` hop of an aggregate of a type: it writes the primary target. */
    answers: (AggregateType) -> Boolean = { true },
    /** Whether the subscription still writes the snapshots of a type: a backfill stops once its target caught up. */
    private val active: (AggregateType) -> Boolean = { true },
    /** How many snapshots one write of a partition holds at most ([SnapshotOptions.writeBatch]). */
    private val writeBatch: Int = SnapshotOptions.DEFAULT_WRITE_BATCH,
) {
    private val models: Map<AggregateType, AggregateModel<*>> = models.associateBy { it.type }
    private val written = LruMap<AggregateRef, Written>(capacity)

    /** The batched writes, partition `p`'s in lane `p % WRITE_LANES`: an aggregate's always in the same lane. */
    private val writes = List(WRITE_LANES) { SnapshotWrites(store, writeBatch) }

    init {
        require(models.isNotEmpty()) { "A snapshot subscription needs the aggregate types it snapshots." }
        require(models.all { it.type.context == context }) { "Every snapshotted aggregate type is of $context." }
    }

    /** The published states the executor records for the snapshotted aggregate types, this subscription one of their readers. */
    val views: PublishedStates = views ?: PublishedStates(
        codec,
        mapOf(name to this.models.keys),
        PublishedStateOptions(capacity),
    )

    private val reader = PublishedStateReader(name, models, kernel, codec, this.views, store)

    /** The subscription to register: [name]d, of the snapshotted aggregate types only. */
    val subscription: Subscription = Subscription.coalescing(
        name,
        SubscriptionFilter(context, this.models.keys.mapTo(LinkedHashSet()) { it.name }),
        FunctionKind.SNAPSHOT,
        EntryDecoder.stateEvents(reader, decodeEvents = false),
        answers = { type -> Hop.SNAPSHOT_SUBSCRIPTION.takeIf { answers(type) } },
        handler = ::write,
    )

    private suspend fun write(entry: SubscribedEntry) {
        val stream = entry.stream
        val ref = stream.ref
        val version = stream.version
        val model = checkNotNull(models[ref.type]) { "${ref.type} has no snapshot." }
        if (!active(ref.type)) {
            reader.pass(ref, version)
            return
        }
        val held = written[ref]
        if (held != null && held.version >= version) {
            // A view recorded after this subscription wrote its version, without one, is never written again.
            reader.pass(ref, held.version)
            return
        }
        val view = views.get(stream)
        // The view knows the first stream when the kernel loaded the aggregate: no store read.
        val known = if (version == 1) FirstCommit.of(stream) else held?.first ?: view?.first
        if (view != null && known != null) {
            save(entry, snapshotOf(model, stream, view.state, known, view.deleted, view.tags, view.schedules))
            return
        }
        val stored = store.load(ref)
        if (stored != null && stored.version >= version) {
            remember(ref, FirstCommit.of(stored), stored.version)
            return
        }
        val first = known ?: stored?.let(FirstCommit::of) ?: firstOf(ref)
        val state = entry.state(stored)
        save(entry, snapshotOf(model, stream, state.state, first, state.deleted, state.tags, state.schedules))
    }

    /**
     * The snapshot of [stream]: [state] with the kernel's [deleted] mark, [tags] and [schedules] after it, its scope and operator,
     * and what [first] says of the aggregate's first stream.
     */
    @Suppress("LongParameterList")
    private fun snapshotOf(
        model: AggregateModel<*>,
        stream: EventStreamRecord,
        state: String,
        first: FirstCommit,
        deleted: Boolean,
        tags: Map<String, List<String>>,
        schedules: Map<String, ScheduleRule>,
    ): SnapshotRecord = codec.snapshotOf(
        model,
        stream.ref,
        state,
        stream.version,
        stream.scope,
        first.eventTime,
        stream.commitTime,
        deleted,
        tags,
        stream.operator,
        first.operator,
        schedules,
    )

    /**
     * Saves [snapshot], [entry]'s, with the saves waiting in its lane, of any of the lane's partitions: written or not,
     * the store then holds its version or a later one (a version-guarded save).
     */
    private suspend fun save(entry: SubscribedEntry, snapshot: SnapshotRecord) {
        val partition = entry.position?.partition ?: 0
        writes[Math.floorMod(partition, WRITE_LANES)].save(snapshot)
        remember(snapshot.ref, FirstCommit.of(snapshot), snapshot.version)
    }

    /** The store holds [ref] at [version] or later: this subscription is done with its views up to [version]. */
    private fun remember(ref: AggregateRef, first: FirstCommit, version: Int) {
        written[ref] = Written(first, version)
        reader.pass(ref, version)
    }

    /** What this subscription knows the store holds of an aggregate: a lower bound of the stored version. */
    private class Written(val first: FirstCommit, val version: Int)

    /** What the snapshot keeps of [ref]'s first stream, read from the log. */
    private suspend fun firstOf(ref: AggregateRef): FirstCommit {
        val stream = checkNotNull(log.read(ref, 1, 1).firstOrNull()) { "The log does not hold $ref@1." }
        return FirstCommit.of(stream)
    }

    private companion object {
        /**
         * The snapshot writers a subscription runs at once: each batches the saves of a quarter
         * of the partitions, so a write commits several partitions' snapshots and four writes keep up with a node.
         */
        const val WRITE_LANES = 4
    }
}
