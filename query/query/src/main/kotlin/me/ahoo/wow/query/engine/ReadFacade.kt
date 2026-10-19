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

package me.ahoo.wow.query.engine

import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.query.QueryCaller
import me.ahoo.wow.query.QueryGateway
import me.ahoo.wow.query.mask.PayloadMasks
import me.ahoo.wow.query.state.CommittedState
import me.ahoo.wow.query.state.StateVisibility
import tools.jackson.databind.JsonNode

/**
 * The committed states a node reads (decision D231 ②): the latest committed state of an aggregate, as the write side
 * keeps it (`:runtime`'s state reads, injected here so that `:query` does not depend on the runtime, D237).
 */
@InternalWowApi
fun interface CommittedStates {
    /** [ref]'s latest committed state, or null when it has none. */
    suspend fun committed(ref: AggregateRef): CommittedState?

    @InternalWowApi
    companion object {
        /** No state: every read finds none. */
        @JvmField
        val NONE: CommittedStates = CommittedStates { null }
    }
}

/**
 * The one read path of every entry that reads an aggregate's state (decision D237: HTTP, synchronous calls, ops):
 * read the state, check that the caller may see it, mask it. [read] loads a state (its latest, at a version or a time,
 * as the entry asks, from the runtime's state reads), hands it to [admitted], which checks it against the snapshot
 * query policies of its type ([StateVisibility]: the caller scope, ABAC and the application's policies, through the
 * type's snapshot query gateway or, when its snapshots are not queried, its policy gateway; review R3.3-3) and masks it
 * as the snapshot queries mask a state (09 §17 G-2); [event] masks an event body the same way.
 *
 * A query policy that refuses the caller throws its query error, which the entry answers as its own problem.
 */
@InternalWowApi
class ReadFacade(
    private val visibility: (AggregateType) -> QueryGateway?,
    private val masks: PayloadMasks = PayloadMasks.NONE,
) {
    /** What reading a state found: [Missing], [Hidden] from the caller, or [Visible] with its masked tree. */
    @InternalWowApi
    sealed interface Read<out V> {
        /** No such state. */
        @InternalWowApi
        data object Missing : Read<Nothing>

        /** A state the caller may not see, answered as a missing one. */
        @InternalWowApi
        data object Hidden : Read<Nothing>

        /** The [view] read, its state [state] as the caller sees it. */
        @InternalWowApi
        class Visible<V>(val view: V, val state: JsonNode) : Read<V>
    }

    /**
     * Reads a state with [load], and admits it for [caller]: [committed] gives what its policies read of the [view][V]
     * [load] found, after the entry's own [check] of it (its scope, say), which throws to refuse it.
     */
    suspend fun <V : Any> read(
        caller: QueryCaller,
        load: suspend () -> V?,
        committed: (V) -> CommittedState,
        check: (V) -> Unit = {},
    ): Read<V> {
        val view = load() ?: return Read.Missing
        check(view)
        val state = admitted(caller, committed(view)) ?: return Read.Hidden
        return Read.Visible(view, state)
    }

    /** The tree of [state] as [caller] may see it, masked, or null when its policies hide it from the caller. */
    suspend fun admitted(caller: QueryCaller, state: CommittedState): JsonNode? =
        StateVisibility.admitted(visibility(state.ref.type), caller, state, masks)

    /** The body of an event [name] of [type] as every caller sees it: masked as the Manifest declares. */
    fun event(type: AggregateType, name: String, body: JsonNode): JsonNode = masks.event(type, name, body)

    @InternalWowApi
    companion object {
        /** No policy and no mask: every state is seen as it is. */
        @JvmField
        val NONE: ReadFacade = ReadFacade({ null })
    }
}
