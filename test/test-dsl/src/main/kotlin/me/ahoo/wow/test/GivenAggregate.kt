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
 * commit per event, to the [state] and [version] it is at. [ProcessorSpec.whenEvent] hands over its next commit, and
 * the aggregate is then at it: the next one comes after it, and [ProcessorSpec.whenRedelivered] and
 * [ProcessorSpec.whenDeliveredLate] hand it again. A fork of the specification advances a copy of its own, which
 * [ProcessorSpec.whenEvent] still finds by the aggregate's [ref].
 */
class GivenAggregate<S : Any> private constructor(
    /** The model that evolves the aggregate's state. */
    val model: AggregateModel<S>,
    /** The aggregate. */
    val ref: AggregateRef,
    /** Its events so far: its history, one commit per event, then those of the commits handed over. */
    private val events: MutableList<Any>,
    /** The commits [ProcessorSpec.whenEvent] handed over, by version. */
    private val handed: MutableMap<Int, StateEvent<S>>,
    initialVersion: Int,
    /** The scope its commits leave it in. */
    val scope: Scope,
) {
    internal constructor(model: AggregateModel<S>, ref: AggregateRef, history: List<Any>, scope: Scope) :
        this(model, ref, ArrayList(history), LinkedHashMap(), history.size, scope)

    init {
        require(ref.type == model.type) { "$ref is not an aggregate of ${model.type}, the model's." }
    }

    /**
     * The version it is at: one commit per event of its history, then one per commit handed over, by the
     * specification that gave it (a fork advances a copy of its own).
     */
    var version: Int = initialVersion
        private set

    /** The state it is in, replayed anew on each read, as states are mutable. */
    val state: S get() = stateAfter(emptyList())

    private fun stateAfter(more: List<Any>): S =
        (events + more).fold(model.initialState(ref)) { state, event -> model.evolve(state, event) }

    /**
     * The commit of [events] after those so far, at the next version, leaving the aggregate in the state they evolve
     * it to, and at that version: the commit of the command of [by] when given, which names its command type as the
     * model does.
     */
    internal fun commit(
        events: List<Any>,
        by: KClass<*>?,
        headers: Map<String, String>,
        operator: Operator?,
    ): StateEvent<S> {
        require(events.isNotEmpty()) { "A commit has at least one event." }
        val commit = stateEvent(
            ref,
            stateAfter(events),
            *events.toTypedArray(),
            version = version + 1,
            commandType = by?.let { commandTypeOf(it.java) } ?: "${ref.type}.Command",
            scope = scope,
            headers = headers,
            operator = operator,
        )
        this.events.addAll(events)
        version = commit.version
        handed[version] = commit
        return commit
    }

    /** The commit at [version] it handed over, the latest when null: a commit to hand again. */
    internal fun handed(version: Int?): StateEvent<S> {
        val at = version ?: this.version
        return handed[at] ?: throw IllegalArgumentException(
            if (handed.isEmpty()) {
                "No commit of $ref was handed over yet: whenEvent hands it over first."
            } else {
                "No commit of $ref was handed over at version $at: those handed over are at ${handed.keys}."
            },
        )
    }

    /** A copy for a fork of the specification: its commits so far, advanced apart from this one. */
    internal fun copy(): GivenAggregate<S> =
        GivenAggregate(model, ref, ArrayList(events), LinkedHashMap(handed), version, scope)

    /** The full name of the command [type] of the aggregate: the model's name of it, else the naming rule's. */
    private fun commandTypeOf(type: Class<*>): String =
        (model.commandTypes + model.mountedCommandTypes).firstOrNull { it.type == type }?.name
            ?: CommandType.of(model.type, type).name
}
