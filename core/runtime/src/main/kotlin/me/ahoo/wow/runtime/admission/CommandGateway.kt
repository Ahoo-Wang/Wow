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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.ErrorInfo
import me.ahoo.wow.api.result.FunctionInfo
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.Violation
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.CommandType
import me.ahoo.wow.kernel.Creation
import me.ahoo.wow.runtime.FrameworkCodes
import me.ahoo.wow.runtime.cluster.ClusterCodec
import me.ahoo.wow.runtime.wait.WaitCoordinator
import me.ahoo.wow.runtime.wait.admissionRejected
import me.ahoo.wow.runtime.wait.isAcceptedOnly
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration

/**
 * Admits commands (design 02 §1; decision D92): turns a [CommandRequest] into an immutable [Command] and hands it to
 * the [WaitCoordinator] of its context, which checks its wait plan, routes it and waits for it.
 *
 * Admission runs in this order, and the first step that refuses answers the request:
 *
 * 1. Resolve the command type from [registry], by its model name or the part of it after the aggregate type
 *    (`AddItem` for `shop.cart.AddItem`): an aggregate or a command this node does not serve is
 *    [FrameworkCodes.UNHANDLED].
 * 2. Decide the identity, each fact by one rule (decisions D95–D97); a fact given twice, or missing, is
 *    [FrameworkCodes.MALFORMED]:
 *    - the tenant and the scope are the caller's; a [CommandRequest.target] replaces them only for a caller with
 *      [Caller.crossScope], and is [FrameworkCodes.AUTH_FORBIDDEN] for any other, never ignored;
 *    - an aggregate type whose tenant [tenants] fixes (decision D116) is in that tenant whoever the caller is; a target
 *      that names another tenant for it is [FrameworkCodes.MALFORMED];
 *    - the aggregate id is the request's, or a new one from [ids] for a command that may create the aggregate;
 *    - the command id is always a new one from [ids]; the request id is the request's, or the command id;
 *    - a request header in the framework's namespace (`Wow-…`, `wow.…`) is refused: identity, ids and the wait have
 *      fields of their own.
 * 3. Authorize the action with [authorizer], before the body is decoded.
 * 4. Decode the body as the command type with [mapper]; one that does not decode is [FrameworkCodes.MALFORMED].
 * 5. Rewrite the command with each of [rewriters] in turn. A rewriter may change the body (keeping its type) and the
 *    headers only; one that changes anything else is a defect of the server, so the flow fails.
 * 6. Validate the body with every one of [validators]: their violations, together, are [FrameworkCodes.INVALID].
 * 7. Build the [Command], its wait plan the request's, or [WaitPlan.DEFAULT] within the coordinator's
 *    [default timeout][me.ahoo.wow.runtime.wait.WaitOptions.defaultTimeout] ([defaultWaitTimeout]) when it names none;
 *    its timeout capped by the coordinator's [maximum][me.ahoo.wow.runtime.wait.WaitOptions.maxTimeout].
 *
 * The coordinator then checks the plan, routes the command and waits for it; deduplication by request id and the
 * check that an existing aggregate belongs to the command's scope (decision D94) are the kernel's.
 *
 * A refusal is a result at ACCEPTED, REJECTED with its code, and the only result the flow emits. Until the command
 * is built, it names the facts decided so far; its ref names the aggregate id [UNADDRESSED] when none was decided.
 *
 * A command whose plan has only an ACCEPTED hop is answered once admitted, and runs detached from the request in a
 * supervised scope this gateway owns (decision D100); it is not durable: [close] waits for the ones running, and a
 * node that stops before running one loses it.
 */
class CommandGateway(
    private val registry: ModelRegistry,
    coordinators: Map<String, WaitCoordinator>,
    private val authorizer: CommandAuthorizer = CommandAuthorizer.ALLOW_ALL,
    private val rewriters: List<CommandRewriter> = emptyList(),
    private val validators: List<CommandValidator> = emptyList(),
    private val ids: IdGenerator = IdGenerator.UUID_V7,
    /** The tenant of each aggregate type: the caller's, unless fixed for the type (decision D116). */
    val tenants: TenantPolicy = TenantPolicy.CALLERS,
    private val mapper: JsonMapper = ClusterCodec.mapper,
    private val clock: () -> Long = System::currentTimeMillis,
    detachedContext: CoroutineContext = Dispatchers.Default,
) {
    private val coordinators: Map<String, WaitCoordinator> = coordinators.toMap()
    private val detached = CoroutineScope(SupervisorJob() + detachedContext)

    @Volatile
    private var closed = false

    init {
        val missing = registry.contexts - this.coordinators.keys
        require(missing.isEmpty()) { "Contexts without a wait coordinator: $missing" }
    }

    /**
     * Admits [request] and emits its results: an admission refusal alone, or what its wait emits for each hop as it is
     * reached (see [WaitCoordinator.stream]); for a plan with only an ACCEPTED hop, that result alone. The command is
     * admitted when the flow is collected.
     */
    fun send(request: CommandRequest): Flow<CommandResult> = flow {
        when (val admission = admit(request)) {
            is Admission.Refused -> emit(admission.result)
            is Admission.Admitted -> {
                val coordinator = coordinators.getValue(admission.command.ref.type.context)
                if (admission.command.wait.isAcceptedOnly) {
                    emit(coordinator.accept(admission.model, admission.command, detached))
                } else {
                    emitAll(coordinator.stream(admission.model, admission.command))
                }
            }
        }
    }

    /**
     * The timeout of a command of [context] whose request names none: its coordinator's
     * [default][me.ahoo.wow.runtime.wait.WaitOptions.defaultTimeout], capped by its maximum; [WaitPlan.DEFAULT_TIMEOUT]
     * for a context this gateway does not admit. An edge whose request names a plan but no timeout gives the plan
     * this timeout.
     */
    fun defaultWaitTimeout(context: String): Duration =
        coordinators[context]?.options?.let { minOf(it.defaultTimeout, it.maxTimeout) } ?: WaitPlan.DEFAULT_TIMEOUT

    /** Admits [request] and returns the result its wait ends with: the last one [send] emits. */
    suspend fun execute(request: CommandRequest): CommandResult = send(request).last()

    /**
     * Refuses new requests from now on ([FrameworkCodes.NODE_CLOSING]), waits for the detached commands that are
     * running, and closes their scope.
     */
    suspend fun close() {
        closed = true
        detached.coroutineContext.job.children.toList().joinAll()
        detached.cancel()
    }

    @Suppress("ReturnCount", "CyclomaticComplexMethod", "LongMethod") // One early return per refusing step.
    private suspend fun admit(request: CommandRequest): Admission {
        val facts = Facts(request, ids.next())
        if (closed) {
            return facts.refuse(FrameworkCodes.NODE_CLOSING, "The node is closing.", CommandResult.Status.FAILED)
        }
        // 1. The command type.
        val model = registry.find(facts.type)
            ?: return facts.refuse(FrameworkCodes.UNHANDLED, "No aggregate ${facts.type} is served here.")
        val unhandled = "${facts.type} has no command ${request.command}."
        val commandType = model.findCommandType(request.command)
            ?: model.findCommandType("${facts.type}.${request.command}")
            ?: return facts.refuse(FrameworkCodes.UNHANDLED, unhandled)
        val decider = model.decider(commandType.type) ?: return facts.refuse(FrameworkCodes.UNHANDLED, unhandled)
        facts.function = FunctionInfo(FunctionKind.COMMAND, facts.type.context, facts.type.name, commandType.shortName)
        // 2. The identity.
        identityConflict(request, decider.creation)?.let { return facts.refuse(FrameworkCodes.MALFORMED, it) }
        val target = request.target
        if (target != null && !request.caller.crossScope) {
            return facts.refuse(FrameworkCodes.AUTH_FORBIDDEN, "The caller may not act in another scope.")
        }
        val tenantId = try {
            tenants.tenantOf(facts.type, request.caller, target)
        } catch (conflict: TenantConflictException) {
            return facts.refuse(FrameworkCodes.MALFORMED, conflict.message!!)
        }
        val scope = target?.scope ?: request.caller.scope
        facts.ref = facts.type.ref(tenantId, request.aggregateId ?: ids.next())
        // 3. Action-level authorization.
        val action = CommandAction(commandType, facts.ref, scope, decider.creation)
        val authorization = authorizer.authorize(request.caller, action)
        if (authorization is Authorization.Denied) {
            return facts.refuse(authorization.code, authorization.message)
        }
        // 4. The body.
        val body = decode(request.body, commandType)
            ?: return facts.refuse(FrameworkCodes.MALFORMED, "The body is not a ${commandType.name}.")
        // 5. Rewriting.
        val coordinator = coordinators.getValue(facts.type.context)
        val plan = request.wait ?: WaitPlan.DEFAULT.within(coordinator.options.defaultTimeout)
        var command: Command<*> = Command(
            id = facts.commandId,
            requestId = facts.requestId,
            ref = facts.ref,
            body = body.value,
            expectedVersion = request.expectedVersion,
            scope = scope,
            headers = request.headers,
            wait = plan.within(minOf(plan.timeout, coordinator.options.maxTimeout)),
        )
        rewriters.forEach { rewriter -> command = rewriter.rewrite(command).also { checkRewrite(command, it) } }
        // 6. Validation.
        val violations = validators.flatMap { it.validate(commandType, command.body) }
        if (violations.isNotEmpty()) {
            return facts.refuse(FrameworkCodes.INVALID, "${commandType.name} is invalid.", violations = violations)
        }
        return Admission.Admitted(model, command)
    }

    /** Why [request]'s identity facts cannot be decided, or null when they can. */
    private fun identityConflict(request: CommandRequest, creation: Creation): String? {
        val reserved = request.headers.keys.firstOrNull(::isReserved)
        return when {
            reserved != null -> "Header $reserved is reserved: identity, ids and the wait have fields of their own."
            request.requestId?.isBlank() == true -> "A request id, when given, is not blank."
            request.aggregateId?.isBlank() == true -> "An aggregate id, when given, is not blank."
            request.aggregateId == null && creation == Creation.NONE -> "${request.command} needs an aggregate id."
            (request.expectedVersion ?: 0) < 0 -> "An expected version is never negative."
            else -> null
        }
    }

    /** [body] read as [type], or null when it does not decode to one. */
    private fun decode(body: CommandBody, type: CommandType): Decoded? = try {
        when (body) {
            is CommandBody.Bytes -> mapper.readValue(body.bytes, type.type)
            is CommandBody.Tree -> mapper.treeToValue(body.node, type.type)
        }?.let(::Decoded)
    } catch (_: JacksonException) {
        null
    }

    private class Decoded(val value: Any)

    /** The facts decided so far about a request, to refuse it with. */
    private inner class Facts(request: CommandRequest, val commandId: String) {
        val type = AggregateType(request.context, request.aggregate)
        val requestId: String = request.requestId?.takeIf { it.isNotBlank() } ?: commandId
        var ref: AggregateRef = type.ref(
            tenants.fixedTenant(type) ?: request.caller.tenantId,
            request.aggregateId?.takeIf { it.isNotBlank() } ?: UNADDRESSED,
        )
        var function = FunctionInfo(
            FunctionKind.COMMAND,
            type.context,
            type.name,
            request.command.substringAfterLast('.').ifBlank { request.command },
        )

        fun refuse(
            code: String,
            message: String,
            status: CommandResult.Status = CommandResult.Status.REJECTED,
            violations: List<Violation> = emptyList(),
        ): Admission.Refused {
            val rejected = admissionRejected(
                commandId = commandId,
                requestId = requestId,
                correlationId = commandId,
                ref = ref,
                function = function,
                error = ErrorInfo(code, message, violations),
                now = clock(),
            )
            return Admission.Refused(rejected.copy(status = status))
        }
    }

    private sealed interface Admission {
        class Admitted(val model: AggregateModel<*>, val command: Command<*>) : Admission

        class Refused(val result: CommandResult) : Admission
    }

    companion object {
        /** The aggregate id a refusal's ref names when admission had decided none. */
        const val UNADDRESSED: String = "(none)"

        private val RESERVED_HEADER_PREFIXES = listOf("wow-", "wow.")

        private fun isReserved(header: String): Boolean =
            RESERVED_HEADER_PREFIXES.any { header.startsWith(it, ignoreCase = true) }

        private val CommandType.shortName: String get() = name.substringAfterLast('.')

        /** Fails when [rewritten] changed more of [command] than its body (keeping its type) and its headers. */
        private fun checkRewrite(command: Command<*>, rewritten: Command<*>) {
            check(
                rewritten.id == command.id &&
                    rewritten.requestId == command.requestId &&
                    rewritten.ref == command.ref &&
                    rewritten.scope == command.scope &&
                    rewritten.expectedVersion == command.expectedVersion &&
                    rewritten.causation == command.causation &&
                    rewritten.wait == command.wait,
            ) { "A rewriter changed the identity, the ids or the wait of ${command.id}; it may change only its body and headers." }
            check(rewritten.body.javaClass == command.body.javaClass) {
                "A rewriter changed the body of ${command.id} from ${command.body.javaClass.name} to ${rewritten.body.javaClass.name}."
            }
            val reserved = rewritten.headers.keys.firstOrNull(::isReserved)
            check(reserved == null) { "A rewriter added the reserved header $reserved to ${command.id}." }
        }
    }
}
