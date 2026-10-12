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

package me.ahoo.wow.runtime.admission

import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.result.Violation
import me.ahoo.wow.kernel.CommandType
import me.ahoo.wow.kernel.Creation
import me.ahoo.wow.runtime.FrameworkCodes

/**
 * What a caller asks to do, as action-level authorization sees it before the body is decoded (decision D92): a
 * command of [type] on [ref], in [scope], and whether the command may create the aggregate ([creation]).
 */
data class CommandAction(val type: CommandType, val ref: AggregateRef, val scope: Scope, val creation: Creation)

/** An authorizer's answer: [Allowed], or [Denied] with a [Denied.code] and a [Denied.message]. */
sealed interface Authorization {
    data object Allowed : Authorization

    data class Denied(val message: String, val code: String = FrameworkCodes.AUTH_FORBIDDEN) : Authorization {
        init {
            require(code.isNotBlank()) { "A denial has a code." }
        }
    }
}

/**
 * Decides whether a caller may send a command, before its body is decoded (decision D92, step 3). The instance-level
 * check, whether the aggregate belongs to the caller's scope, is the kernel's (decision D94), not an authorizer's.
 */
fun interface CommandAuthorizer {
    suspend fun authorize(caller: Caller, action: CommandAction): Authorization

    companion object {
        /** Allows every action. */
        @JvmField
        val ALLOW_ALL: CommandAuthorizer = CommandAuthorizer { _, _ -> Authorization.Allowed }
    }
}

/**
 * Rewrites a decoded command before it is validated (decision D92, step 5), such as filling a default into its body or
 * adding a request fact to its headers. It may change only the body (keeping its type) and the headers; the gateway
 * checks after each rewriter that the ids, the ref, the scope, the expected version, the causation and the wait plan
 * are unchanged, and that no reserved header was added.
 */
fun interface CommandRewriter {
    suspend fun rewrite(command: Command<*>): Command<*>
}

/**
 * Validates a decoded and rewritten command body of [type][CommandType] (decision D106): the violations it finds, none
 * when it is valid. Any violation refuses the command with [FrameworkCodes.INVALID], listing them all.
 */
fun interface CommandValidator {
    fun validate(type: CommandType, body: Any): List<Violation>
}
