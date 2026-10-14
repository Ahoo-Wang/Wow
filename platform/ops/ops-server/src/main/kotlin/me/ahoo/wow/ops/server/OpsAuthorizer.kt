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

package me.ahoo.wow.ops.server

import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.ledger.Ledger
import me.ahoo.wow.kernel.CommandType
import me.ahoo.wow.ops.api.OpsContext
import me.ahoo.wow.ops.api.parked.ApplyRetrySpec
import me.ahoo.wow.ops.api.parked.MarkRecoverable
import me.ahoo.wow.ops.api.parked.Resume
import me.ahoo.wow.ops.api.parked.Skip
import me.ahoo.wow.runtime.admission.Authorization
import me.ahoo.wow.runtime.admission.CommandAction
import me.ahoo.wow.runtime.admission.CommandAuthorizer

/**
 * Who sends what to the ops context through its command gateway (decisions D141, D92):
 *
 * - An operator's action on an entry, `ops.parked.Resume`, `Skip`, `MarkRecoverable` and `ApplyRetrySpec`, is
 *   authorized by [operators] as the ledger's system command of the same name ([Ledger.RESUME], ...) on the entry, so
 *   one policy guards the action here, on the operator's routes of the owning service (`/wow/ops/parked`) and when
 *   the owning service runs the command the saga sends it, as the operator who asked (decision D163).
 * - The framework's own system commands (`wow.*`: this node's ledger, its snapshot rebuild) are [operators]' too.
 * - The records, `ops.parked.RecordParked` and `RecordResolved`, are refused: the owning services' ledgers publish them
 *   through `wow.ops.commands`, whose intake no authorizer guards, and no caller of the gateway may forge one.
 */
class OpsAuthorizer(private val operators: CommandAuthorizer = OperatorRole()) : CommandAuthorizer {
    override suspend fun authorize(caller: Caller, action: CommandAction): Authorization {
        val name = action.type.name
        if (name.startsWith(SYSTEM_PREFIX)) {
            return operators.authorize(caller, action)
        }
        val ledger = ACTIONS[name] ?: return Authorization.Denied(
            "$name is published by the owning service's ledger through ${OpsContext.NAME}'s command topic alone.",
        )
        return operators.authorize(caller, action.copy(type = CommandType(ledger, Ledger.COMMANDS.getValue(ledger))))
    }

    companion object {
        /** The namespace of the framework's system commands. */
        const val SYSTEM_PREFIX: String = "wow."

        /** The ledger's system command each of an operator's actions is authorized as. */
        @JvmField
        val ACTIONS: Map<String, String> = mapOf(
            commandOf(Resume::class.java) to Ledger.RESUME,
            commandOf(Skip::class.java) to Ledger.SKIP,
            commandOf(MarkRecoverable::class.java) to Ledger.MARK_RECOVERABLE,
            commandOf(ApplyRetrySpec::class.java) to Ledger.APPLY_RETRY_SPEC,
        )

        /** The name of the command [type] of the `parked` aggregate (decision D55). */
        private fun commandOf(type: Class<*>): String = "${OpsContext.NAME}.${OpsContext.PARKED}.${type.simpleName}"
    }
}

/**
 * The stand-in for CoSec's command authorizer: a caller with the role [OpsCallers.OPERATOR] may send the ledger's
 * system commands, and no other caller may.
 */
class OperatorRole : CommandAuthorizer {
    override suspend fun authorize(caller: Caller, action: CommandAction): Authorization =
        if (OpsCallers.OPERATOR in caller.attributes[OpsCallers.ROLES_ATTRIBUTE].orEmpty()) {
            Authorization.Allowed
        } else {
            Authorization.Denied(
                "${caller.subject ?: "An anonymous caller"} may not ${action.type.name}: it needs ${OpsCallers.OPERATOR}."
            )
        }
}
