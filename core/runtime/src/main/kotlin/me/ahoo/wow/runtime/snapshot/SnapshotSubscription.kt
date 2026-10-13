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
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.runtime.state.StateViewOptions
import me.ahoo.wow.runtime.state.StateViewReader
import me.ahoo.wow.runtime.state.StateViews
import me.ahoo.wow.runtime.subscription.EntryDecoder
import me.ahoo.wow.runtime.subscription.SubscribedEntry
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.subscription.SubscriptionFilter
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.spi.snapshot.SnapshotStore

/**
 * The built-in `snapshot` subscription of one bounded [context] (design 03 §3, roadmap 4.6): keeps the latest
 * snapshot of every aggregate of the [models] given, the aggregate types whose snapshots are enabled. Register
 * [subscription] in the context's [me.ahoo.wow.runtime.subscription.SubscriptionRuntime] and give [views] to the
 * [me.ahoo.wow.runtime.Executor]: it then runs on each partition's owner, checkpoints, parks and signals like any
 * subscription, and completes the default wait, `SNAPSHOT` (`HANDLED(snapshot)`, decisions D61, D69). An aggregate
 * type not among [models] has no snapshot, and its default wait is `COMMITTED`.
 *
 * - **Merged writes**: of an aggregate's entries waiting together, only the latest is written; the waits on the
 *   earlier ones complete with it ([Subscription.coalesces]).
 * - **The state**: read as every state consumer of the context reads it ([EntryDecoder.stateEvents], decision D133),
 *   as the reader [Hop.SNAPSHOT_SUBSCRIPTION] of the context's shared [views]: the view taken when the stream
 *   committed on this node; without one (a replay, an ownership change, a command run on another node) the stored
 *   snapshot or a nearer view evolved with the log's tail up to the stream ([Kernel.stateAt]), else the log replayed.
 * - **The record**: built by [EventCodec.snapshotOf]; its scope is the stream's (decision D56), its deleted mark and
 *   tags the kernel's after the stream, its last event time the stream's commit time, its first event time that of
 *   the aggregate's first stream. The kernel reloads a deleted or tagged aggregate from it as such.
 * - **Idempotent**: [SnapshotStore.save] only ever replaces a lower version, so a replay or an old owner running
 *   alongside the new one (decision D45) never moves a snapshot back. When the store already holds the stream's
 *   version or a later one, as this subscription last wrote or loaded it, nothing is rebuilt or saved, from a view
 *   or without one, and the subscription passes the aggregate's views up to that version: a view recorded after its
 *   entry was written without it never costs a second write.
 *
 * [codec] must be the kernel's, so the views and the fingerprints are what the kernel reads back. [views] are the
 * context's, where this subscription is the reader [Hop.SNAPSHOT_SUBSCRIPTION] of the snapshotted types; by default
 * its own, bounded by [capacity]. [capacity] also bounds the versions and first event times kept per aggregate.
 */
@Suppress("LongParameterList")
class SnapshotSubscription(
    context: String,
    models: Collection<AggregateModel<*>>,
    private val store: SnapshotStore,
    kernel: Kernel,
    private val log: EventLog,
    private val codec: EventCodec = EventCodec(),
    views: StateViews? = null,
    capacity: Int = StateViewOptions.DEFAULT_CAPACITY,
) {
    private val models: Map<AggregateType, AggregateModel<*>> = models.associateBy { it.type }
    private val written = LruMap<AggregateRef, Written>(capacity)

    init {
        require(models.isNotEmpty()) { "A snapshot subscription needs the aggregate types it snapshots." }
        require(models.all { it.type.context == context }) { "Every snapshotted aggregate type is of $context." }
    }

    /** The state views the executor records for the snapshotted aggregate types, this subscription one of their readers. */
    val views: StateViews = views ?: StateViews(
        codec,
        mapOf(Hop.SNAPSHOT_SUBSCRIPTION to this.models.keys),
        StateViewOptions(capacity),
    )

    private val reader = StateViewReader(Hop.SNAPSHOT_SUBSCRIPTION, models, kernel, codec, this.views, store)

    /** The subscription to register: named [Hop.SNAPSHOT_SUBSCRIPTION], of the snapshotted aggregate types only. */
    val subscription: Subscription = Subscription.coalescing(
        Hop.SNAPSHOT_SUBSCRIPTION,
        SubscriptionFilter(context, this.models.keys.mapTo(LinkedHashSet()) { it.name }),
        FunctionKind.SNAPSHOT,
        EntryDecoder.stateEvents(reader, decodeEvents = false),
        ::write,
    )

    private suspend fun write(entry: SubscribedEntry) {
        val stream = entry.stream
        val ref = stream.ref
        val version = stream.version
        val model = checkNotNull(models[ref.type]) { "${ref.type} has no snapshot." }
        val held = written[ref]
        if (held != null && held.version >= version) {
            // A view recorded after this subscription wrote its version, without one, is never written again.
            reader.pass(ref, held.version)
            return
        }
        val view = views.get(ref, version)
        val known = if (version == 1) stream.commitTime else held?.firstEventTime
        if (view != null && known != null) {
            save(snapshotOf(model, stream, view.state, known, view.deleted, view.tags))
            return
        }
        val stored = store.load(ref)
        if (stored != null && stored.version >= version) {
            remember(ref, stored.firstEventTime, stored.version)
            return
        }
        val first = known ?: stored?.firstEventTime ?: firstEventTime(ref)
        val state = entry.state(stored)
        save(snapshotOf(model, stream, state.state, first, state.deleted, state.tags))
    }

    /** The snapshot of [stream]: [state] with the kernel's [deleted] mark and [tags] after it, and its scope. */
    @Suppress("LongParameterList")
    private fun snapshotOf(
        model: AggregateModel<*>,
        stream: EventStreamRecord,
        state: String,
        firstEventTime: Long,
        deleted: Boolean,
        tags: Map<String, List<String>>,
    ): SnapshotRecord = codec.snapshotOf(
        model,
        stream.ref,
        state,
        stream.version,
        stream.scope,
        firstEventTime,
        stream.commitTime,
        deleted,
        tags,
    )

    /** Saves [snapshot]: written or not, the store then holds its version or a later one (a version-guarded save). */
    private suspend fun save(snapshot: SnapshotRecord) {
        store.save(snapshot)
        remember(snapshot.ref, snapshot.firstEventTime, snapshot.version)
    }

    /** The store holds [ref] at [version] or later: this subscription is done with its views up to [version]. */
    private fun remember(ref: AggregateRef, firstEventTime: Long, version: Int) {
        written[ref] = Written(firstEventTime, version)
        reader.pass(ref, version)
    }

    /** What this subscription knows the store holds of an aggregate: a lower bound of the stored version. */
    private class Written(val firstEventTime: Long, val version: Int)

    /** The commit time of [ref]'s first stream. */
    private suspend fun firstEventTime(ref: AggregateRef): Long =
        checkNotNull(log.read(ref, 1, 1).firstOrNull()) { "The log does not hold $ref@1." }.commitTime
}
