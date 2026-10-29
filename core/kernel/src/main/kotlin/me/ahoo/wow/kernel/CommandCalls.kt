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

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.problem.Retry
import me.ahoo.wow.api.problem.WowProblemException
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.FrameworkCodes

/**
 * Sends the synchronous calls of a saga's execution to other contexts: the runtime's HTTP client on a
 * node, an answer of the test DSL in a domain module's test. [call] returns the target's result as it is, SUCCEEDED,
 * REJECTED or FAILED, and throws a [WowProblemException] ([failure]) when it reaches none;
 * [ExecutionSagaContext.call] turns the results that did not succeed into the handler's typed failures.
 */
@InternalWowApi
fun interface CommandCalls {
    suspend fun call(command: Command<*>): CommandResult

    @InternalWowApi
    companion object {
        /** The status of a call that failed without the target's: a bad gateway. */
        @Suppress("MayBeConst") // An interface's companion with a @JvmField property holds no const.
        @JvmField
        val FAILED_STATUS: Int = 502

        /**
         * A call that failed or reached no result, as the handler sees it: [result]'s error code, else
         * [FrameworkCodes.COMMAND_FAILED], with [status], retried with the same request id, so the target decides it
         * once.
         */
        @JvmStatic
        @JvmOverloads
        fun failure(
            message: String,
            cause: Throwable? = null,
            result: CommandResult? = null,
            status: Int = FAILED_STATUS,
        ): WowProblemException = WowProblemException(
            result?.error?.code ?: FrameworkCodes.COMMAND_FAILED,
            status,
            Retry.SAME_REQUEST,
            null,
            message,
            result,
            cause,
        )

        /** No call client: every call fails, naming the configuration it lacks. */
        @JvmField
        val NONE: CommandCalls = CommandCalls { command ->
            throw failure(
                "No client calls ${command.ref.type.context} synchronously for ${command.id}: give the host its " +
                    "internal address, wow.peers.${command.ref.type.context}.url.",
            )
        }
    }
}
