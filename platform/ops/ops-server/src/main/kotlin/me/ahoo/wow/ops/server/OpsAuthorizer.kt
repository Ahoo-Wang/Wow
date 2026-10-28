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
import me.ahoo.wow.ops.api.OpsContext
import me.ahoo.wow.ops.api.parked.ApplyRetrySpec
import me.ahoo.wow.ops.api.parked.MarkRecoverable
import me.ahoo.wow.ops.api.parked.Resume
import me.ahoo.wow.ops.api.parked.Skip
import me.ahoo.wow.runtime.admission.Authorization
import me.ahoo.wow.runtime.admission.CommandAction
import me.ahoo.wow.runtime.admission.CommandAuthorizer

/**
 * The commands no caller of the ops context's gateway may send, whoever it is: the records,
 * `ops.parked.RecordParked`, `RecordResolved` and `RecordActionRefused`. The owning services' ledgers publish the
 * first two through `wow.ops.commands`, whose intake no authorizer guards, and the saga derives the last from an owning
 * service's refusal: none may be forged. Every other command it has no objection to, as one of the node's authorizers
 * (each must allow a command): an operator's action on an entry (`ops.parked.Resume`, `Skip`, `MarkRecoverable`,
 * `ApplyRetrySpec`) and the framework's own system commands (`wow.*`) are decided by who the operators are, CoSec's
 * policies of their own routes when CoSec authorizes the commands, else [OperatorRole].
 */
class OpsAuthorizer : CommandAuthorizer {
    override suspend fun authorize(caller: Caller, action: CommandAction): Authorization {
        val name = action.type.name
        return if (name.startsWith(SYSTEM_PREFIX) || name in ACTIONS) {
            Authorization.Allowed
        } else {
            Authorization.Denied.of(
                "$name is published by the owning service's ledger through ${OpsContext.NAME}'s command topic alone.",
            )
        }
    }

    companion object {
        /** The namespace of the framework's system commands. */
        const val SYSTEM_PREFIX: String = "wow."

        /** An operator's actions on an entry, by their command's name. */
        @JvmField
        val ACTIONS: Set<String> = setOf(
            commandOf(Resume::class.java),
            commandOf(Skip::class.java),
            commandOf(MarkRecoverable::class.java),
            commandOf(ApplyRetrySpec::class.java),
        )

        /** The name of the command [type] of the `parked` aggregate. */
        private fun commandOf(type: Class<*>): String = "${OpsContext.NAME}.${OpsContext.PARKED}.${type.simpleName}"
    }
}

/**
 * Who the operators are when CoSec does not authorize the commands: a caller with the role [OpsRoles.OPERATOR] may
 * act on an entry and send the framework's system commands, and no other caller may. With CoSec authorizing them,
 * its policies of the actions' own routes decide instead, and the ops service does not ask this one.
 */
class OperatorRole : CommandAuthorizer {
    override suspend fun authorize(caller: Caller, action: CommandAction): Authorization =
        if (OpsRoles.OPERATOR in caller.attributes[OpsRoles.ROLES_ATTRIBUTE].orEmpty()) {
            Authorization.Allowed
        } else {
            Authorization.Denied.of(
                "${caller.subject ?: "An anonymous caller"} may not ${action.type.name}: it needs ${OpsRoles.OPERATOR}."
            )
        }
}
