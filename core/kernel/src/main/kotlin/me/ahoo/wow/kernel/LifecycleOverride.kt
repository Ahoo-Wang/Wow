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

package me.ahoo.wow.kernel

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.lifecycle.AggregateDeleted
import me.ahoo.wow.api.lifecycle.AggregateRestored
import me.ahoo.wow.api.lifecycle.ApplyTags
import me.ahoo.wow.api.lifecycle.DeleteAggregate
import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.api.lifecycle.OwnerTransferred
import me.ahoo.wow.api.lifecycle.RestoreAggregate
import me.ahoo.wow.api.lifecycle.SpaceTransferred
import me.ahoo.wow.api.lifecycle.TagsApplied
import me.ahoo.wow.api.lifecycle.TransferOwner
import me.ahoo.wow.api.lifecycle.TransferSpace

/**
 * A model's decide function of a lifecycle command, which overrides the kernel's to add domain rules:
 * it decides the command's own lifecycle event beside the aggregate's own events, `NoOp` or a rejection. A decision
 * with another lifecycle event (a `DeleteAggregate` that transfers the owner) fails the command, since the event would
 * change what the command does not.
 */
internal object LifecycleOverride {
    /** Each lifecycle command's own event. */
    private val OWN: Map<Class<*>, Class<*>> = mapOf(
        DeleteAggregate::class.java to AggregateDeleted::class.java,
        RestoreAggregate::class.java to AggregateRestored::class.java,
        TransferOwner::class.java to OwnerTransferred::class.java,
        TransferSpace::class.java to SpaceTransferred::class.java,
        ApplyTags::class.java to TagsApplied::class.java,
    )

    private val EVENTS: Set<Class<*>> = Lifecycle.EVENTS.values.toSet()

    /** [decider] of [command] in the model of [type], its decisions checked. */
    fun <S : Any> of(type: AggregateType, command: Class<*>, decider: DecideFunction<S>): DecideFunction<S> =
        when (decider) {
            is DecideFunction.Sync -> DecideFunction.Sync(decider.creation, decider.onDeleted) { state, body ->
                checked(type, command, decider.decide(this, state, body))
            }

            is DecideFunction.Suspending -> DecideFunction.Suspending(
                decider.creation,
                decider.blocking,
                decider.onDeleted,
            ) { state, body -> checked(type, command, decider.decide(this, state, body)) }
        }

    /** [decision], unless it decides a lifecycle event other than [command]'s own. */
    fun checked(type: AggregateType, command: Class<*>, decision: Decision): Decision {
        if (decision !is Decision.Events) return decision
        val own = OWN.getValue(command)
        val foreign = decision.events.map { it.javaClass }.filter { it in EVENTS && it != own }
        check(foreign.isEmpty()) {
            "$type's decide function of ${command.simpleName} decided ${foreign.joinToString { it.simpleName }}: a " +
                "lifecycle command's decide function decides its own ${own.simpleName}, the aggregate's own events, " +
                "NoOp or a rejection."
        }
        return decision
    }
}
