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

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.result.FunctionInfo
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.Stage
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.kernel.CallerAdmission
import me.ahoo.wow.runtime.admission.AdmissionIdentity.BodyId
import me.ahoo.wow.runtime.admission.AdmissionIdentity.actingCaller
import me.ahoo.wow.runtime.admission.AdmissionIdentity.bodyId
import me.ahoo.wow.runtime.admission.AdmissionIdentity.identityConflict
import me.ahoo.wow.runtime.admission.AdmissionIdentity.tenantConflict
import me.ahoo.wow.runtime.admission.CommandGateway.Companion.UNADDRESSED
import me.ahoo.wow.runtime.wait.WaitCoordinator
import me.ahoo.wow.spi.json.BodyAggregateIds
import me.ahoo.wow.spi.tenant.TenantConflictException
import me.ahoo.wow.spi.tenant.TenantPolicy
import tools.jackson.databind.json.JsonMapper

/**
 * The admission of a command of a peer context's aggregate, which this node does not serve but sends to that context's
 * command topic ([admitPeer]); [CommandGateway] admits it in place of its own aggregates' commands.
 */
internal class PeerAdmission(
    private val peers: PeerCommands?,
    private val tenants: TenantPolicy,
    private val validators: List<CommandValidator>,
    private val authorizer: CommandAuthorizer,
    private val mapper: JsonMapper,
    private val bodies: CommandBodies,
    private val coordinators: Map<String, WaitCoordinator>,
) {
    /**
     * Admits [request] for an aggregate this node does not serve: one of a peer context
     * whose contract the class path carries ([peers]) is checked as a command of this node is, up to its wait plan, and
     * sent to that context's command topic; any other is [FrameworkCodes.COMMAND_UNHANDLED]. Its command type is resolved
     * from the contract; its identity is decided as for this node's own aggregates, an aggregate id left out only for
     * a command that creates its aggregate, which the peer's intake then gives one, and read from the body by the
     * property the contract names for a command whose body names its aggregate; the caller is
     * authorized and the body decoded and validated here. The peer runs no transformer of this node, and a synchronous
     * call goes to the context that serves the aggregate, not through here. Its plan
     * ([peerPlanProblem]) waits for ACCEPTED, or for the peer's COMMITTED, the default.
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    internal suspend fun admitPeer(request: ServerCommandRequest, facts: Facts): Admission {
        val type = facts.type
        val aggregate = peers?.aggregateOf(type)
            ?: return facts.refuse(FrameworkCodes.COMMAND_UNHANDLED, "No aggregate $type is served here.")
        val peer = peers.resolve(type, aggregate, request.command)
            ?: return facts.refuse(FrameworkCodes.COMMAND_UNHANDLED, "$type has no command ${request.command}.")
        facts.function = FunctionInfo(FunctionKind.COMMAND, type.context, type.name, peer.name.substringAfterLast('.'))
        val commandType = peer.commandType ?: return facts.refuse(
            FrameworkCodes.COMMAND_UNHANDLED,
            "The class of ${peer.name} is not on the class path: depend on the api module of ${type.context}, whose " +
                "jar carries its contract and its commands.",
        )
        val relay = peers.relayOf(peer.name) ?: return facts.refuse(
            FrameworkCodes.COMMAND_UNHANDLED,
            "$type is of context ${type.context}, which another service serves: its commands go through its command " +
                "topic, and this node has no command transport.",
        )
        // 2. The identity.
        val idProperty = peer.idProperty
        identityConflict(request, peer.creation, transformed = idProperty != null)?.let {
            return facts.refuse(FrameworkCodes.COMMAND_MALFORMED, it)
        }
        if (request.call != null) {
            return facts.refuse(
                FrameworkCodes.COMMAND_MALFORMED,
                "A synchronous call goes to the service of ${type.context}, which serves $type.",
            )
        }
        val target = request.target
        if (target != null && !request.caller.crossScope) {
            return facts.refuse(FrameworkCodes.AUTH_FORBIDDEN, "The caller may not act in another scope.")
        }
        val tenantId = try {
            tenants.tenantOf(type, request.caller, target)
        } catch (conflict: TenantConflictException) {
            return facts.refuse(FrameworkCodes.COMMAND_MALFORMED, conflict.message!!)
        }
        tenantConflict(request, tenantId)?.let { return facts.refuse(FrameworkCodes.COMMAND_MALFORMED, it) }
        val origin = CallerAdmission.of(request.caller, target)
        val scope = origin.scope
        facts.ref = type.ref(tenantId, request.aggregateId ?: UNADDRESSED)
        // A command whose body names its aggregate is read now, by the property its contract names.
        val early = if (idProperty != null) {
            bodies.bodyProblem(request.body)?.let { return facts.refuse(FrameworkCodes.COMMAND_MALFORMED, it) }
            val decoded = bodies.decode(request.body, commandType.type)
                ?: return facts.refuse(FrameworkCodes.COMMAND_MALFORMED, "The body is not a ${peer.name}.")
            val named = bodyId(peer.name, request.aggregateId, peer.creation) {
                BodyAggregateIds.of(mapper.valueToTree(decoded.value), idProperty)
            }
            when (named) {
                is BodyId.Refused -> return facts.refuse(FrameworkCodes.COMMAND_MALFORMED, named.message)
                // A creation that names none is given its id by the peer's intake.
                is BodyId.Of -> named.id?.let { facts.ref = type.ref(tenantId, it) }
            }
            decoded
        } else {
            null
        }
        AdmissionLimits.oversized(facts.requestId, facts.ref.id, tenantId, scope)
            ?.let { return facts.refuse(FrameworkCodes.COMMAND_MALFORMED, it) }
        // 3. Action-level authorization.
        authorizer.authorize(request, CommandAction(commandType, facts.ref, scope, peer.creation))
            ?.let { return facts.refuse(it) }
        // 4. The body, unless it was read for its aggregate.
        val decoded = early ?: run {
            bodies.bodyProblem(request.body)?.let { return facts.refuse(FrameworkCodes.COMMAND_MALFORMED, it) }
            bodies.decode(request.body, commandType.type)
                ?: return facts.refuse(FrameworkCodes.COMMAND_MALFORMED, "The body is not a ${peer.name}.")
        }
        AdmissionLimits.oversizedBody(decoded.value)?.let { return facts.refuse(FrameworkCodes.COMMAND_MALFORMED, it) }
        // 6. Validation.
        val violations = validators.violationsOf(commandType, decoded.value)
        if (violations.isNotEmpty()) {
            return facts.refuse(FrameworkCodes.COMMAND_INVALID, "${peer.name} is invalid.", violations = violations)
        }
        // 7. The command.
        val options = coordinators.values.first().options
        val wait = request.wait
        peerPlanProblem(type, wait)?.let { return facts.refuse(FrameworkCodes.WAIT_INVALID, it) }
        val plan = if (wait == null || wait.isDefault) {
            WaitPlan.COMMITTED.within(wait?.timeout ?: options.defaultTimeout)
        } else {
            wait
        }
        val admitted = Command(
            id = facts.commandId,
            requestId = facts.requestId,
            ref = facts.ref,
            body = decoded.value,
            expectedVersion = request.expectedVersion,
            scope = scope,
            headers = request.headers,
            operator = origin.operator,
            wait = plan.within(minOf(plan.timeout, options.maxTimeout)),
            caller = actingCaller(request.caller, facts.ref, scope),
        )
        return Admission.Relayed(admitted, facts.function, relay)
    }

    /**
     * Why [wait] is not a plan a command of the peer aggregate [type] may have, or null when it is one: the command
     * topic's path delivers ACCEPTED, once the broker acknowledged the command, and the peer's COMMITTED, which its
     * intake signals back; a later hop is not followed across services.
     */
    private fun peerPlanProblem(type: AggregateType, wait: WaitPlan?): String? {
        if (wait == null || wait.isDefault) {
            return null
        }
        val hop = wait.hops.firstOrNull { hop ->
            hop.stage == Stage.HANDLED || hop.context != null && hop.context != type.context
        } ?: return null
        return "A command of $type, a context another service serves, goes through its command topic, which answers " +
            "at ACCEPTED or at its COMMITTED; hop $hop is not followed. Wait for COMMITTED, or read " +
            "the peer's state afterwards."
    }
}
