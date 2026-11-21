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

import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.result.ErrorInfo
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.runtime.admission.AdmissionIdentity.unaddressed

/**
 * The admission of a command the service made itself, which no caller's request admitted: one a saga derived to an
 * aggregate of its own context ([me.ahoo.wow.runtime.binding.DerivedCommands]), and one its context's command topic
 * delivered ([me.ahoo.wow.runtime.command.CommandIntake]). Before the command runs, in this order:
 *
 * 1. The node's [validators] (the gateway's) check its body: one they find violations in is refused as
 *    [FrameworkCodes.COMMAND_INVALID], listing them ([invalid]).
 * 2. One that names no aggregate whose id is its owner's, and has no owner, is refused as admission refuses its
 *    request ([AdmissionIdentity.unaddressed]).
 * 3. A creation that names no aggregate takes the id its request created, or a new one, from [creations].
 *
 * A refusal ([refusalOf]) is answered by the caller before any aggregate decides the command, as its path answers
 * one: a saga's rejected command, or the intake's rejection receipt; an admitted command then takes its id
 * ([assign]).
 */
internal class ServiceCommandAdmission(
    /** The node's command validators, the gateway's. */
    private val validators: List<CommandValidator>,
    private val creations: CreationIds,
) {
    /**
     * Why [command], of [model], is refused (1 and 2), or null when it is admitted, to [assign] its id next. It does not
     * suspend: its caller answers a refusal before its first suspension, and keeps no more across it than before.
     */
    fun refusalOf(model: AggregateModel<*>, command: Command<*>): ErrorInfo? {
        invalid(model, command)?.let { return it }
        // One that names none of an aggregate whose id is its owner's, and has no owner, is the sender's mistake.
        unaddressed(model, command)?.let { return ErrorInfo(it.code, it.message) }
        return null
    }

    /**
     * [command], of [model], admitted, with its aggregate id assigned when it names none (3): a creation that names
     * no aggregate takes the id its request created, or a new one.
     */
    suspend fun <C : Any> assign(model: AggregateModel<*>, command: Command<C>): Command<C> =
        creations.assign(model, command)

    /**
     * The error of [command] when the node's [validators] find violations in its body,
     * [FrameworkCodes.COMMAND_INVALID] listing them, as the gateway refuses an admitted command; null when it is valid.
     */
    private fun invalid(model: AggregateModel<*>, command: Command<*>): ErrorInfo? {
        val type = model.commandType(command.body.javaClass)
        val violations = validators.violationsOf { candidateOf(type, command) }
        return if (violations.isEmpty()) {
            null
        } else {
            ErrorInfo(
                FrameworkCodes.COMMAND_INVALID,
                "${type.name} is invalid.",
                violations
            )
        }
    }
}
