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

package me.ahoo.wow.runtime.binding

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.subscribe.StateEvent
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.runtime.snapshot.StateViews
import me.ahoo.wow.runtime.subscription.SubscribedEntry
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.spi.snapshot.SnapshotStore

/**
 * Builds the [StateEvent] a handler receives for a committed entry (design 04 §1, design 08 §5): the stream's
 * identity, command, commit time, scope (the aggregate's after the commit, decision D56), causation and headers, its
 * events as the entry's decoder read them (decoded and upgraded, [me.ahoo.wow.runtime.subscription.EntryDecoder.models]),
 * and the aggregate's state after the commit:
 *
 * - from the state view this node took as it committed the stream ([views], [StateViews.peek]), decoded afresh for
 *   each handler, so no handler sees another's changes nor the live instance;
 * - without one (a replay, an ownership change, a command run on another node, an evicted view): the aggregate's
 *   snapshot in [snapshots] evolved with the log's tail up to the stream ([Kernel.stateAt]), else the log replayed.
 *   A snapshot already past the stream, as a subscription behind the snapshot one finds it, is passed over.
 *
 * [codec] must be the kernel's, so a view decodes as it was encoded. It runs inside the subscription's retries: a
 * state that cannot be read is retried, then parks the aggregate like a failing handler.
 */
class StateEventReader(
    models: Collection<AggregateModel<*>>,
    private val kernel: Kernel,
    private val codec: EventCodec,
    private val views: StateViews? = null,
    private val snapshots: SnapshotStore? = null,
) {
    private val models: Map<AggregateType, AggregateModel<*>> = models.associateBy { it.type }

    /** The state event of [entry]: its stream with the state after it. */
    suspend fun read(entry: SubscribedEntry): StateEvent<Any> {
        val stream = entry.stream
        val model = checkNotNull(models[stream.ref.type]) { "No model of ${stream.ref.type} to read its state with." }
        return StateEvent(
            ref = stream.ref,
            version = stream.version,
            commandId = stream.commandId,
            requestId = stream.requestId,
            commandType = stream.commandType,
            commitTime = stream.commitTime,
            events = entry.events,
            state = stateAt(model, stream.ref, stream.version),
            scope = stream.scope,
            causation = stream.causation,
            headers = stream.headers,
        )
    }

    private suspend fun stateAt(model: AggregateModel<*>, ref: AggregateRef, version: Int): Any {
        views?.peek(ref, version)?.let { return codec.decodeState(model, it.state) }
        val state = kernel.stateAt(model, ref, version, loadSnapshot(ref))
        return checkNotNull(state) { "The log does not hold $ref@$version." }.state
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
