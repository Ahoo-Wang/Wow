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

package me.ahoo.wow.test

import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.subscribe.StateEvent
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.CommandType
import kotlin.reflect.KClass

/**
 * An aggregate a processor reads, as [ProcessorSpec.givenAggregate] gave it: its [model] replays its history, one
 * commit per event, to the [state] and [version] it is at. [ProcessorSpec.whenEvent] hands over the next commit of it.
 */
class GivenAggregate<S : Any> internal constructor(
    /** The model that evolves the aggregate's state. */
    val model: AggregateModel<S>,
    /** The aggregate. */
    val ref: AggregateRef,
    private val history: List<Any>,
    /** The scope its commits leave it in. */
    val scope: Scope,
) {
    init {
        require(ref.type == model.type) { "$ref is not an aggregate of ${model.type}, the model's." }
    }

    /** The version its history left it at: one commit per event. */
    val version: Int get() = history.size

    /** The state its history left it in, replayed anew on each read, as states are mutable. */
    val state: S get() = stateAfter(emptyList())

    private fun stateAfter(events: List<Any>): S =
        (history + events).fold(model.initialState(ref)) { state, event -> model.evolve(state, event) }

    /**
     * The commit of [events] after the history, at the next version, leaving the aggregate in the state they evolve
     * it to: the commit of the command of [by] when given, which names its command type as the model does.
     */
    internal fun commit(
        events: List<Any>,
        by: KClass<*>?,
        operator: Operator?,
        headers: Map<String, String>,
    ): StateEvent<S> {
        require(events.isNotEmpty()) { "A commit has at least one event." }
        return stateEvent(
            ref,
            stateAfter(events),
            *events.toTypedArray(),
            version = version + 1,
            commandType = by?.let { commandTypeOf(it.java) } ?: "${ref.type}.Command",
            scope = scope,
            headers = headers,
            operator = operator,
        )
    }

    /** The full name of the command [type] of the aggregate: the model's name of it, else the naming rule's. */
    private fun commandTypeOf(type: Class<*>): String =
        (model.commandTypes + model.mountedCommandTypes).firstOrNull { it.type == type }?.name
            ?: CommandType.of(model.type, type).name
}
