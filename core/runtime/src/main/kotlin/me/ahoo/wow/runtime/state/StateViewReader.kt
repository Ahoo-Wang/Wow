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

package me.ahoo.wow.runtime.state

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.CachedState
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.spi.snapshot.SnapshotStore
import tools.jackson.core.JacksonException

/**
 * Reads the state after a committed stream for one state consumer, the [reader] of the context's [views] (design 04
 * §1, decision D133), in this order:
 *
 * 1. the view taken at the stream's version ([StateViews.get]): what the executor recorded as it committed (or
 *    expected just before, when the log hands this node's commit over first), or what another reader rebuilt;
 * 2. otherwise the stream in hand evolved from the state right before it, reading no store (decision D261, B.G-5):
 *    the view at the previous version, such as the one this reader handled last, or the initial state for the
 *    aggregate's first stream;
 * 3. otherwise the state rebuilt from the nearest base before the version: the latest view before it
 *    ([StateViews.floor], such as the one this reader rebuilt for the aggregate's previous commit) or the aggregate's
 *    snapshot in [snapshots], whichever is later, evolved with the log's tail up to the version ([Kernel.stateAt]);
 * 4. without either (no view, and no snapshot or one already past the version), the log replayed up to the version.
 *
 * A rebuilt state is put back into the views, so the next commit of the aggregate, for this reader or another
 * behind it, evolves it with one stream rather than replaying the log again: a subscription behind the snapshot
 * replays its aggregate once, not on every commit. Each read [pass]es the version, so the views this reader no longer
 * needs can be evicted.
 *
 * [codec] must be the kernel's, so a view decodes as it was encoded. It runs inside the subscription's retries: a
 * state that cannot be read is retried, then parks the aggregate like a failing handler.
 */
@InternalWowApi
class StateViewReader(
    val reader: String,
    models: Collection<AggregateModel<*>>,
    private val kernel: Kernel,
    val codec: EventCodec,
    private val views: StateViews,
    private val snapshots: SnapshotStore? = null,
) {
    private val models: Map<AggregateType, AggregateModel<*>> = models.associateBy { it.type }

    /** The model of [type], whose events and state this reader decodes. */
    fun modelOf(type: AggregateType): AggregateModel<*> =
        checkNotNull(models[type]) { "No model of $type to read its state with." }

    /** The state after [stream]. */
    suspend fun read(stream: EventStreamRecord): SerializedState =
        views.get(stream)?.also { pass(stream.ref, stream.version) }
            ?: inHand(stream)
            ?: rebuild(stream, loadSnapshot(stream.ref))

    /** The state after [stream], with [snapshot], the aggregate's snapshot the caller already loaded, if any. */
    suspend fun read(stream: EventStreamRecord, snapshot: SnapshotRecord?): SerializedState =
        views.get(stream)?.also { pass(stream.ref, stream.version) }
            ?: inHand(stream)
            ?: rebuild(stream, snapshot)

    /** This reader is done with [ref] up to [version] ([StateViews.pass]). */
    fun pass(ref: AggregateRef, version: Int) {
        views.pass(reader, ref, version)
    }

    private suspend fun rebuild(stream: EventStreamRecord, snapshot: SnapshotRecord?): SerializedState {
        val ref = stream.ref
        val version = stream.version
        val model = modelOf(ref.type)
        val usable = snapshot?.takeIf { it.ref == ref && it.version <= version }
        val base = views.floor(ref, version)?.takeIf { usable == null || it.version >= usable.version }
        val state = base?.let { view -> baseOf(model, view)?.let { kernel.stateAt(model, ref, version, it) } }
            ?: kernel.stateAt(model, ref, version, usable)
        checkNotNull(state) { "The log does not hold $ref@$version." }
        return keep(ref, state)
    }

    /**
     * The state after [stream] evolved from the state right before it, without reading a store: the view at the
     * previous version, or the initial state when [stream] is the aggregate's first; null without that base.
     */
    private fun inHand(stream: EventStreamRecord): SerializedState? {
        val ref = stream.ref
        val model = modelOf(ref.type)
        val base = if (stream.version == 1) {
            CachedState(model.initialState(ref), 0)
        } else {
            views.floor(ref, stream.version)?.takeIf { it.version == stream.version - 1 }?.let { baseOf(model, it) }
        } ?: return null
        return kernel.evolved(model, base, stream)?.let { keep(ref, it) }
    }

    /** [state], rebuilt by this reader, as a view shared with the other readers, and passed. */
    private fun keep(ref: AggregateRef, state: CachedState): SerializedState {
        val view =
            SerializedState(
                state.version,
                codec.stateText(state.state),
                state.scope,
                state.deleted,
                state.tags,
                state.schedules
            )
        views.put(ref, view)
        pass(ref, view.version)
        return view
    }

    /**
     * [view] as a state the kernel may evolve: decoded afresh, so the view itself never changes; null when it does
     * not decode whole ([EventCodec.decodeState]), as a snapshot that does not fit: the reader then goes without it.
     */
    private fun baseOf(model: AggregateModel<*>, view: SerializedState): CachedState? = try {
        CachedState(
            codec.decodeState(model, view.state),
            view.version,
            view.scope,
            view.deleted,
            view.tags,
            view.schedules,
        )
    } catch (failure: IllegalArgumentException) {
        logger.debug(failure) { "The view of ${model.type}@${view.version} does not decode whole; not using it." }
        null
    } catch (failure: JacksonException) {
        logger.debug(failure) { "The view of ${model.type}@${view.version} does not decode; not using it." }
        null
    }

    /** The snapshot of [ref]; a store that fails costs a replay, not the entry: a snapshot is only a cache. */
    private suspend fun loadSnapshot(ref: AggregateRef): SnapshotRecord? {
        val store = snapshots ?: return null
        return try {
            store.load(ref)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (
            @Suppress("TooGenericExceptionCaught") // Whatever the store throws, the log still holds the state.
            failure: Exception,
        ) {
            logger.warn(failure) { "Loading the snapshot of $ref failed; replaying its log." }
            null
        }
    }

    private companion object {
        val logger = KotlinLogging.logger {}
    }
}
