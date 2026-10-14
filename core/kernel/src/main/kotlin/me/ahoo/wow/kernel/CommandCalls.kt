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

import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.subscribe.CallFailedException

/**
 * Sends the synchronous calls of a saga's execution to other contexts (decision D193): the runtime's HTTP client on a
 * node, an answer of the test DSL in a domain module's test. [call] returns the target's result as it is, SUCCEEDED,
 * REJECTED or FAILED, and throws a [CallFailedException] when it reaches none; [ExecutionSagaContext.call] turns the
 * results that did not succeed into the handler's typed failures.
 */
fun interface CommandCalls {
    suspend fun call(command: Command<*>): CommandResult

    companion object {
        /** No call client: every call fails, naming the configuration it lacks. */
        @JvmField
        val NONE: CommandCalls = CommandCalls { command ->
            throw CallFailedException(
                "No client calls ${command.ref.type.context} synchronously for ${command.id}: give the host its " +
                    "address, wow.calls.contexts.${command.ref.type.context}.url (decision D193).",
            )
        }
    }
}
