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
import me.ahoo.wow.runtime.subscription.SubscribedEntry
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.subscription.SubscriptionFilter
import me.ahoo.wow.spi.log.EventLog
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
 * - **The state**: the view taken when the stream committed on this node ([views]); without one (a replay, an
 *   ownership change, a command run on another node) the stored snapshot evolved with the log's tail up to the
 *   stream ([Kernel.stateAt]), else the log replayed.
 * - **The record**: built by [EventCodec.snapshotOf]; its scope is the stream's (decision D56), its last event time
 *   the stream's commit time, its first event time that of the aggregate's first stream.
 * - **Idempotent**: [SnapshotStore.save] only ever replaces a lower version, so a replay or an old owner running
 *   alongside the new one (decision D45) never moves a snapshot back. When the store already holds the stream's
 *   version or a later one, as this subscription last wrote or loaded it, nothing is rebuilt or saved, from a view
 *   or without one, and the aggregate's views up to that version are dropped: a view recorded after its entry was
 *   written without it never costs a second write.
 *
 * [codec] must be the kernel's, so the views and the fingerprints are what the kernel reads back. [capacity] bounds
 * both the views and the versions and first event times kept per aggregate.
 */
class SnapshotSubscription(
    context: String,
    models: Collection<AggregateModel<*>>,
    private val store: SnapshotStore,
    private val kernel: Kernel,
    private val log: EventLog,
    private val codec: EventCodec = EventCodec(),
    capacity: Int = StateViews.DEFAULT_CAPACITY,
) {
    private val models: Map<AggregateType, AggregateModel<*>> = models.associateBy { it.type }
    private val written = LruMap<AggregateRef, Written>(capacity)

    init {
        require(models.isNotEmpty()) { "A snapshot subscription needs the aggregate types it snapshots." }
        require(models.all { it.type.context == context }) { "Every snapshotted aggregate type is of $context." }
    }

    /** The state views the executor records for the snapshotted aggregate types. */
    val views: StateViews = StateViews(codec, this.models.keys, capacity)

    /** The subscription to register: named [Hop.SNAPSHOT_SUBSCRIPTION], of the snapshotted aggregate types only. */
    val subscription: Subscription = Subscription.coalescing(
        Hop.SNAPSHOT_SUBSCRIPTION,
        SubscriptionFilter(context, this.models.keys.mapTo(LinkedHashSet()) { it.name }),
        FunctionKind.SNAPSHOT,
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
            views.dropThrough(ref, held.version)
            return
        }
        val view = views.take(ref, version)
        val known = if (version == 1) stream.commitTime else held?.firstEventTime
        if (view != null && known != null) {
            save(codec.snapshotOf(model, ref, view, version, stream.scope, known, stream.commitTime))
            return
        }
        val stored = store.load(ref)
        if (stored != null && stored.version >= version) {
            remember(ref, stored.firstEventTime, stored.version)
            return
        }
        val first = known ?: stored?.firstEventTime ?: firstEventTime(ref)
        val state = view ?: codec.stateText(
            checkNotNull(kernel.stateAt(model, ref, version, stored)?.state) { "The log does not hold $ref@$version." },
        )
        save(codec.snapshotOf(model, ref, state, version, stream.scope, first, stream.commitTime))
    }

    /** Saves [snapshot]: written or not, the store then holds its version or a later one (a version-guarded save). */
    private suspend fun save(snapshot: SnapshotRecord) {
        store.save(snapshot)
        remember(snapshot.ref, snapshot.firstEventTime, snapshot.version)
    }

    /** The store holds [ref] at [version] or later: its views up to [version] are of no use any more. */
    private fun remember(ref: AggregateRef, firstEventTime: Long, version: Int) {
        written[ref] = Written(firstEventTime, version)
        views.dropThrough(ref, version)
    }

    /** What this subscription knows the store holds of an aggregate: a lower bound of the stored version. */
    private class Written(val firstEventTime: Long, val version: Int)

    /** The commit time of [ref]'s first stream. */
    private suspend fun firstEventTime(ref: AggregateRef): Long =
        checkNotNull(log.read(ref, 1, 1).firstOrNull()) { "The log does not hold $ref@1." }.commitTime
}
