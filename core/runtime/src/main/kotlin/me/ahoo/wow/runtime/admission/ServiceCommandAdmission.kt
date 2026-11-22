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
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.result.ErrorInfo
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.runtime.admission.AdmissionIdentity.unaddressed
import me.ahoo.wow.spi.tenant.TenantConflictException
import me.ahoo.wow.spi.tenant.TenantPolicy

/**
 * The admission of a command the service made itself, which no caller's request admitted: one a saga derived to an
 * aggregate of its own context ([me.ahoo.wow.runtime.binding.DerivedCommands]), one a timer sends to one
 * ([me.ahoo.wow.runtime.timer.ContextTimerFirer]), and one its context's command topic delivered
 * ([me.ahoo.wow.runtime.command.CommandIntake]). Each is sent for an acting tenant, the tenant of what made it (the
 * execution's that derived it, the scheduling aggregate's), or none known (one the topic delivered). Before the
 * command runs, in this order:
 *
 * 1. Its tenant is [tenants]' ([TenantPolicy.tenantOf], the one rule of every path): the tenant its ref names counts
 *    as named unless it is the acting one. One that names another tenant than its type's fixed one is refused as
 *    [FrameworkCodes.COMMAND_MALFORMED], as the gateway refuses a request that does.
 * 2. The node's [validators] (the gateway's) check its body, sent to its aggregate in that tenant: one they find
 *    violations in is refused as [FrameworkCodes.COMMAND_INVALID], listing them ([invalid]).
 * 3. One that names no aggregate whose id is its owner's, and has no owner, is refused as admission refuses its
 *    request ([AdmissionIdentity.unaddressed]).
 * 4. It is sent in that tenant, and a creation that names no aggregate takes the id its request created, or a new
 *    one, from [creations].
 *
 * A refusal ([refusalOf]) is answered by the caller before any aggregate decides the command, as its path answers
 * one: a saga's rejected command, a timer's rejected command, or the intake's rejection receipt; an admitted command
 * then takes its tenant and its id ([assign]).
 */
internal class ServiceCommandAdmission(
    /** The node's command validators, the gateway's. */
    private val validators: List<CommandValidator>,
    private val creations: CreationIds,
    /** The tenant of each aggregate type: the node's ([me.ahoo.wow.runtime.node.AdmissionPlugins.tenants]). */
    private val tenants: TenantPolicy,
) {
    /**
     * Why [command], of [model], sent for the [acting] tenant (null when none is known), is refused (1 to 3), or null
     * when it is admitted, to [assign] its tenant and its id next. It does not suspend: its caller answers a refusal
     * before its first suspension, and keeps no more across it than before.
     */
    fun refusalOf(model: AggregateModel<*>, command: Command<*>, acting: String?): ErrorInfo? {
        val ref = try {
            refOf(command, acting)
        } catch (conflict: TenantConflictException) {
            return ErrorInfo(FrameworkCodes.COMMAND_MALFORMED, conflict.message!!)
        }
        invalid(model, command, ref)?.let { return it }
        // One that names none of an aggregate whose id is its owner's, and has no owner, is the sender's mistake.
        unaddressed(model, command)?.let { return ErrorInfo(it.code, it.message) }
        return null
    }

    /**
     * [command], of [model], sent for the [acting] tenant and admitted ([refusalOf]), in its tenant and with its aggregate
     * id assigned when it names none (4): a creation that names no aggregate takes the id its request created, or a new
     * one.
     */
    suspend fun <C : Any> assign(model: AggregateModel<*>, command: Command<C>, acting: String?): Command<C> {
        val ref = refOf(command, acting)
        return creations.assign(model, if (ref === command.ref) command else command.copyWith(ref = ref))
    }

    /**
     * The aggregate [command], sent for the [acting] tenant, goes to: its ref in the tenant [tenants] gives it; null
     * when it is refused for naming another tenant than its type's fixed one.
     */
    fun targetOf(command: Command<*>, acting: String?): AggregateRef? = try {
        refOf(command, acting)
    } catch (_: TenantConflictException) {
        null
    }

    /** [command]'s ref, in the tenant [tenants] gives it for the [acting] tenant: itself when its tenant is that one. */
    private fun refOf(command: Command<*>, acting: String?): AggregateRef {
        val ref = command.ref
        val tenantId = tenants.tenantOf(ref.type, ref.tenantId, acting)
        return if (tenantId == ref.tenantId) ref else ref.type.ref(tenantId, ref.id)
    }

    /**
     * The error of [command] when the node's [validators] find violations in its body, sent to [ref],
     * [FrameworkCodes.COMMAND_INVALID] listing them, as the gateway refuses an admitted command; null when it is valid.
     */
    private fun invalid(model: AggregateModel<*>, command: Command<*>, ref: AggregateRef): ErrorInfo? {
        val type = model.commandType(command.body.javaClass)
        val violations = validators.violationsOf {
            CommandCandidate(type, command.body, ref, command.scope, command.operator, command.caller)
        }
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
