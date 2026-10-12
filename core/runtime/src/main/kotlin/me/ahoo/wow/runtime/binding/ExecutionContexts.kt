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

import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.subscribe.ExecutionId
import me.ahoo.wow.api.subscribe.ProjectContext
import me.ahoo.wow.api.subscribe.ReactContext
import me.ahoo.wow.api.subscribe.SagaContext
import me.ahoo.wow.api.subscribe.StateEvent
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** The context of a projection's handler for one execution (design 01 §1). */
data class RuntimeProjectContext(override val executionId: ExecutionId) : ProjectContext

/** The context of a reactor's handler for one execution (design 01 §1). */
data class RuntimeReactContext(override val executionId: ExecutionId) : ReactContext

/**
 * The context of a saga's handler for one execution of the commit [event] (design 01 §1, design 04 §1). Each
 * execution, a retry included, gets a new one, so the n-th [command] of every execution of the same commit by the
 * same subscription is the same request: handling the commit again derives the same commands, which the kernel does
 * not decide twice (it deduplicates by request id).
 */
class RuntimeSagaContext(override val executionId: ExecutionId, private val event: StateEvent<*>) : SagaContext {
    private val calls = AtomicInteger()

    init {
        require(event.ref == executionId.ref && event.version == executionId.version) {
            "Execution $executionId is not of the commit ${event.id}."
        }
    }

    /**
     * A command to [ref] asking [body]: its id and request id are [derivedId] of this execution and the call's index,
     * and its causation links it to the commit (`ref@version`) and to the root command of the chain. Its scope is
     * none, as for any command a system derives: the kernel checks only the parts a command's scope has (D94).
     */
    override fun <C : Any> command(ref: AggregateRef, body: C): Command<C> {
        val id = derivedId(executionId, calls.getAndIncrement())
        return Command(
            id = id,
            requestId = id,
            ref = ref,
            body = body,
            causation = Causation(event.correlationId, event.id),
        )
    }

    companion object {
        /**
         * The id, and request id, of the [index]-th command (from 0) derived by [execution]: a name-based UUID of
         * `execution#index`, stable across every execution of the same commit by the same subscription.
         */
        @JvmStatic
        fun derivedId(execution: ExecutionId, index: Int): String {
            require(index >= 0) { "A call index is never negative." }
            return UUID.nameUUIDFromBytes("$execution#$index".toByteArray(Charsets.UTF_8)).toString()
        }
    }
}
