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

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withContext
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.caller.TargetScope
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.RequestFacts
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.ErrorInfo
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.result.FunctionInfo
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.Stage
import me.ahoo.wow.api.result.TransformedFrom
import me.ahoo.wow.api.result.Violation
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.CallerAdmission
import me.ahoo.wow.kernel.CommandType
import me.ahoo.wow.kernel.DecideFunction
import me.ahoo.wow.runtime.ZeroAwaitingCounter
import me.ahoo.wow.runtime.cluster.ClusterCodec
import me.ahoo.wow.runtime.wait.CommandRelay
import me.ahoo.wow.runtime.wait.WaitCoordinator
import me.ahoo.wow.runtime.wait.admissionRejected
import me.ahoo.wow.runtime.wait.isAcceptedOnly
import me.ahoo.wow.spi.StorageUnavailableException
import me.ahoo.wow.spi.id.IdGenerators
import me.ahoo.wow.spi.observation.ObservedEvent
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.observation.observe
import me.ahoo.wow.spi.tenant.TenantConflictException
import me.ahoo.wow.spi.tenant.TenantPolicy
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration

/**
 * Admits commands: turns a [ServerCommandRequest] into an immutable [Command] and hands
 * it to the [WaitCoordinator] of its context, which checks its wait plan, routes it and waits for it.
 *
 * Admission runs in this order, and the first step that refuses answers the request:
 *
 * 1. Resolve the command type from [registry], by its model name or the part of it after the aggregate type
 *    (`AddItem` for `shop.cart.AddItem`), or by the class of a command object ([CommandBody.Value]),
 *    among the commands the aggregate decides and those mounted under it: a command this node does not serve is
 *    [FrameworkCodes.COMMAND_UNHANDLED]. An aggregate it does not serve is a peer context's when [peers] knows its contract:
 *    admitted as [admitPeer] says and sent to that context's command topic; any other is
 *    [FrameworkCodes.COMMAND_UNHANDLED].
 * 2. Decide the identity, each fact by one rule (–); a fact given twice, or missing, is
 *    [FrameworkCodes.COMMAND_MALFORMED]:
 *    - the tenant and the scope are the caller's; a [ServerCommandRequest.target] replaces them only for a caller with
 *      [Caller.crossScope][me.ahoo.wow.api.caller.Caller.crossScope], and is [FrameworkCodes.AUTH_FORBIDDEN] for any
 *      other, never ignored;
 *    - an aggregate type whose tenant [tenants] fixes is in that tenant whoever the caller is; a target
 *      that names another tenant for it is [FrameworkCodes.COMMAND_MALFORMED];
 *    - the aggregate id is the request's; a command that may create the aggregate may name none:
 *      it takes the id of the aggregate its request id already created in [committed], within the caller's scope,
 *      else a new one from [ids]' generator of the aggregate type, so a retry keeps it. A
 *      request of a type one of [transformers] takes, a mounted command among them, may name none: its transformer
 *      finds the aggregate;
 *    - the command id is always a new global one from [ids]; the request id is the request's, or the command id;
 *    - the operator is the caller's ([me.ahoo.wow.api.caller.Caller.toOperator]): its subject in its
 *      own tenant, marked as acting elsewhere when a target was named; none for an anonymous caller;
 *    - a synchronous call's [ServerCommandRequest.call] is [FrameworkCodes.AUTH_FORBIDDEN] unless the caller
 *      is a [service][me.ahoo.wow.api.caller.Caller.service] identity, and [FrameworkCodes.COMMAND_MALFORMED] beside a target;
 *      from a service, the command's id, tenant, scope, operator, causation and propagating request facts
 *      ([CallContext.facts]) are the call's: a derived command, executed with the
 *      call's wait context as one from another context's command topic, and answered at COMMITTED, apart from
 *      the caller and within the default wait timeout ([WaitCoordinator.call]);
 *    - a request header in the framework's namespace (`Wow-…`, `wow.…`) is refused: identity, ids and the wait have
 *      fields of their own.
 * 3. Authorize the action with [authorizer], before the body is decoded.
 *    An id longer than 256 characters (a request, aggregate, tenant or space id) is [FrameworkCodes.COMMAND_MALFORMED]
 *    ([AdmissionLimits]).
 * 4. Decode the body as the command type with [mapper]; one that does not decode, or that writes `\u0000` in a string
 *    or a key, which not every store keeps ([AdmissionLimits]), is [FrameworkCodes.COMMAND_MALFORMED]. A command object is
 *    not decoded: it must be of the command type, and is checked as the JSON [mapper] writes it as. A request whose
 *    ref names another tenant than the one decided ([ServerCommandRequest.tenantId]) is [FrameworkCodes.COMMAND_MALFORMED] too.
 * 5. Transform the command with the one of [transformers] that takes its type, if any ([CommandTransformer]). The
 *    command it becomes must be decided by an aggregate this node serves ([FrameworkCodes.COMMAND_UNHANDLED] otherwise); its
 *    tenant is decided again for that aggregate type, and its scope, request id, expected version and wait plan are
 *    the request's. When the type or the aggregate changed, [authorizer] authorizes the result again. A result that
 *    names no aggregate gets an id as a creation without one does when it creates its aggregate, and
 *    is [FrameworkCodes.AGGREGATE_NOT_FOUND] otherwise. The command's headers then name the input
 *    ([CommandTransformer.TRANSFORMED_FROM]), and so does every result ([CommandResult.transformedFrom]).
 * 6. Validate the body with every one of [validators]: their violations, together, are [FrameworkCodes.COMMAND_INVALID].
 * 7. Build the [Command], its wait plan the request's, or [WaitPlan.DEFAULT] within the coordinator's
 *    [default timeout][me.ahoo.wow.runtime.wait.WaitOptions.defaultTimeout] ([defaultWaitTimeout]) when it names none;
 *    its timeout capped by the coordinator's [maximum][me.ahoo.wow.runtime.wait.WaitOptions.maxTimeout].
 *
 * The coordinator then checks the plan, routes the command and waits for it; deduplication by request id and the
 * check that an existing aggregate belongs to the command's scope are the kernel's.
 *
 * At most one of [transformers] takes a command type, each takes one this node decides or mounts (or one of a context
 * it does not serve), and every mounted command needs one: the gateway is not built otherwise.
 *
 * A refusal is a result at ACCEPTED, REJECTED with its code, and the only result the flow emits. Until the command
 * is built, it names the facts decided so far; its ref names the aggregate id [UNADDRESSED] when none was decided.
 *
 * A command whose plan has only an ACCEPTED hop is answered once admitted, and runs detached from the request in a
 * supervised scope this gateway owns; it is not durable: [close] waits for the ones running, and a
 * node that stops before running one loses it.
 */
class CommandGateway @InternalWowApi constructor(
    private val registry: ModelRegistry,
    coordinators: Map<String, WaitCoordinator>,
    private val authorizer: CommandAuthorizer = CommandAuthorizer.ALLOW_ALL,
    transformers: List<CommandTransformer> = emptyList(),
    private val validators: List<CommandValidator> = emptyList(),
    /** The node's id generators: command ids from the global one, new aggregate ids per type. */
    @InternalWowApi
    val ids: IdGenerators = IdGenerators.DEFAULT,
    /** The tenant of each aggregate type: the caller's, unless fixed for the type. */
    @InternalWowApi
    val tenants: TenantPolicy = TenantPolicy.CALLERS,
    private val mapper: JsonMapper = ClusterCodec.mapper,
    private val clock: Clock = Clock.systemUTC(),
    detachedContext: CoroutineContext = Dispatchers.Default,
    /** The streams committed for a request, which a creation without an id looks up first. */
    committed: CommittedRequests = CommittedRequests.NONE,
    /** Sees each admission; the admitted command executes in the admission's trace. */
    private val observer: Observer = Observer.NONE,
    /**
     * The commands of peer contexts this gateway sends to their command topics; none by
     * default, and an aggregate this node does not serve is then [FrameworkCodes.COMMAND_UNHANDLED].
     */
    private val peers: PeerCommands? = null,
) {
    private val creations = CreationIds(ids, committed)

    private val coordinators: Map<String, WaitCoordinator> = coordinators.toMap()
    private val detached = CoroutineScope(SupervisorJob() + detachedContext)

    /** The transformers, by the full type name of the command they take. */
    private val transformers: Map<String, CommandTransformer> = transformersOf(registry, transformers)

    /** The models that decide each command class, with the command's type. */
    private val deciders: Map<Class<*>, List<Pair<AggregateModel<*>, CommandType>>> = registry.models.flatMap { model ->
        model.commandTypes.map { it.type to (model to it) }
    }.groupBy({ it.first }, { it.second })

    @Volatile
    private var closed = false

    /** The requests between their check of [closed] and the launch of their detached command, if any. */
    private val admissions = ZeroAwaitingCounter()

    init {
        val missing = registry.contexts - this.coordinators.keys
        require(missing.isEmpty()) { "Contexts without a wait coordinator: $missing" }
    }

    /**
     * The aggregate types that take [command], a command class, for a request that leaves its type out: the ones this
     * node decides or mounts it on, and the ones a peer context's contract lists it for.
     */
    @InternalWowApi
    fun aggregatesOf(command: Class<*>): List<AggregateType> {
        val local = registry.models.filter { model ->
            model.findCommandType(command) != null || model.mountedCommandTypes.any { it.type == command }
        }.map { it.type }
        return (local + peers?.aggregatesOf(command).orEmpty()).distinct()
    }

    /**
     * Admits [request] and emits its results: an admission refusal alone, or what its wait emits for each hop as it is
     * reached (see [WaitCoordinator.stream]); for a plan with only an ACCEPTED hop, that result alone. The command is
     * admitted when the flow is collected.
     */
    fun send(request: ServerCommandRequest): Flow<CommandResult> = flow {
        dispatch(request, streaming = true) { emit(it) }
    }

    /**
     * Admits [request] and hands [emit] what [send] emits; unless [streaming], only the result its wait ends with,
     * without the flow of every hop.
     */
    private suspend fun dispatch(
        received: ServerCommandRequest,
        streaming: Boolean,
        emit: suspend (CommandResult) -> Unit,
    ) {
        val request = named(received)
        val observed = observer.admitting(request.context, request.aggregate, request.command, request.trace)
        // The command executes in the admission's trace.
        val trace = observed.context
        // Counted until a detached command is launched, so close() waits for it.
        val (admission, accepted) = counted {
            val admission = observed.observe { admitting(request).also { observed.outcome(it.observedName) } }
            admission to (admission as? Admission.Admitted)
                ?.takeIf { it.call == null && it.command.wait.isAcceptedOnly }
                ?.let { admitted ->
                    val coordinator = coordinators.getValue(admitted.command.ref.type.context)
                    traced(trace) { coordinator.accept(admitted.model, admitted.command, detached) }
                }
        }
        when (admission) {
            is Admission.Refused -> emit(observedRefusal(admission.result))
            is Admission.Relayed -> relay(admission, streaming, trace, emit)
            is Admission.Admitted -> {
                val coordinator = coordinators.getValue(admission.command.ref.type.context)
                val from = admission.transformedFrom
                val tagged: suspend (CommandResult) -> Unit = {
                    emit(if (from == null) it else it.copyWith(transformedFrom = from))
                }
                val call = admission.call
                if (call != null) {
                    // Apart from the caller and bounded by its plan's timeout.
                    tagged(traced(trace) { coordinator.call(admission.model, admission.command, call.wait) })
                } else if (accepted != null) {
                    tagged(accepted)
                } else if (streaming) {
                    val streamed = coordinator.stream(admission.model, admission.command)
                    val results = if (trace === EmptyCoroutineContext) streamed else streamed.flowOn(trace)
                    results.collect { tagged(it) }
                } else {
                    tagged(traced(trace) { coordinator.execute(admission.model, admission.command) })
                }
            }
        }
    }

    /**
     * [request] naming its command type, when it was built from a command object that names it by its class
     * ([ServerCommandRequest.of]): the type of that class the aggregate decides or mounts, on this node, or
     * the one its peer context's contract lists. A class neither has is left as it is, and admission refuses it.
     */
    private fun named(request: ServerCommandRequest): ServerCommandRequest {
        val value = (request.body as? CommandBody.Value)?.command ?: return request
        val type = value.javaClass
        if (request.command != type.name) {
            return request
        }
        val aggregate = AggregateType(request.context, request.aggregate)
        val name = registry.find(aggregate)?.let { model ->
            (model.findCommandType(type) ?: model.mountedCommandTypes.firstOrNull { it.type == type })?.name
        } ?: peers?.let { peers -> peers.aggregateOf(aggregate)?.let { peers.resolve(aggregate, it, type.name)?.name } }
        return if (name == null) request else request.copyWith(command = name)
    }

    /**
     * Sends [admission], a peer's command, through its command topic and hands [emit] its
     * results as [dispatch] does; any coordinator follows its wait, which no execution here is part of.
     */
    private suspend fun relay(
        admission: Admission.Relayed,
        streaming: Boolean,
        trace: CoroutineContext,
        emit: suspend (CommandResult) -> Unit,
    ) {
        val coordinator = coordinators.values.first()
        val (command, function, relay) = admission
        when {
            command.wait.isAcceptedOnly -> emit(traced(trace) { coordinator.acceptRelayed(command, function, relay) })
            streaming -> {
                val relayed = coordinator.relay(command, function, relay)
                (if (trace === EmptyCoroutineContext) relayed else relayed.flowOn(trace)).collect { emit(it) }
            }

            else -> emit(traced(trace) { coordinator.executeRelayed(command, function, relay) })
        }
    }

    /** [refused], told to the observer by its code. */
    private fun observedRefusal(refused: CommandResult): CommandResult = refused.also { result ->
        result.error?.let { observer.observed(ObservedEvent.Refused(result.ref.type, it.code)) }
    }

    /** Runs [block] in the admission's [trace], switching context only when there is one. */
    private suspend inline fun <T> traced(trace: CoroutineContext, crossinline block: suspend () -> T): T =
        if (trace === EmptyCoroutineContext) block() else withContext(trace) { block() }

    /**
     * The timeout of a command of [context] whose request names none: its coordinator's
     * [default][me.ahoo.wow.runtime.wait.WaitOptions.defaultTimeout], capped by its maximum; [WaitPlan.DEFAULT_TIMEOUT]
     * for a context this gateway does not admit. An edge whose request names a plan but no timeout gives the plan
     * this timeout.
     */
    @InternalWowApi
    fun defaultWaitTimeout(context: String): Duration =
        coordinators[context]?.options?.let { minOf(it.defaultTimeout, it.maxTimeout) } ?: WaitPlan.DEFAULT_TIMEOUT

    /**
     * Whether the node is on standby ([me.ahoo.wow.runtime.node.ServiceNode.start]): every request is refused with
     * [FrameworkCodes.NODE_STANDBY], before anything of it is read, until the node is resumed.
     */
    @InternalWowApi
    @Volatile
    var standby: Boolean = false

    /** Admits [request] and returns the result its wait ends with: the last one [send] emits. */
    suspend fun execute(request: ServerCommandRequest): CommandResult {
        var last: CommandResult? = null
        dispatch(request, streaming = false) { last = it }
        return checkNotNull(last) { "Request ${request.requestId} ended without a result." }
    }

    /**
     * Refuses new requests from now on ([FrameworkCodes.NODE_CLOSING]), waits for the detached commands accepted
     * before, those whose admission was still under way included, and closes their scope.
     */
    @InternalWowApi
    suspend fun close() {
        closed = true
        // A request counted before it read `closed` launches its detached command, if any, before it is uncounted.
        admissions.awaitZero()
        while (true) {
            val running = detached.coroutineContext.job.children.toList()
            if (running.isEmpty()) {
                break
            }
            running.joinAll()
        }
        detached.cancel()
    }

    /** Runs [block], an admission and the launch of its detached command, counted in [admissions]. */
    private inline fun <T> counted(block: () -> T): T {
        admissions.increment()
        try {
            return block()
        } finally {
            admissions.decrement()
        }
    }

    /**
     * [admit]s [request]; a store that admission reads (the stream its request id committed) and that
     * is unavailable refuses it, FAILED with [FrameworkCodes.STORAGE_UNAVAILABLE]: nothing ran, so a retry with the
     * same request id is safe.
     */
    private suspend fun admitting(request: ServerCommandRequest): Admission {
        // A service's call keeps the id its saga derived; any other command gets a new one.
        val facts = Facts(request, request.call?.takeIf { request.caller.service }?.commandId ?: ids.global.next())
        return try {
            admit(request, facts)
        } catch (unavailable: StorageUnavailableException) {
            logger.warn(unavailable) { "Request ${facts.requestId} was not admitted: a store is unavailable." }
            facts.refuse(FrameworkCodes.STORAGE_UNAVAILABLE, unavailable.message!!, CommandResult.Status.FAILED)
        }
    }

    @Suppress("ReturnCount", "CyclomaticComplexMethod", "LongMethod") // One early return per refusing step.
    private suspend fun admit(request: ServerCommandRequest, facts: Facts): Admission {
        if (closed) {
            return facts.refuse(FrameworkCodes.NODE_CLOSING, "The node is closing.", CommandResult.Status.FAILED)
        }
        if (standby) {
            return facts.refuse(
                FrameworkCodes.NODE_STANDBY,
                "The node is on standby until the cut-over: it serves reads and refuses commands.",
                CommandResult.Status.FAILED,
            )
        }
        // 1. The command type.
        val model = registry.find(facts.type) ?: return admitPeer(request, facts)
        val unhandled = "${facts.type} has no command ${request.command}."
        val commandType = model.resolve(facts.type, request.command)
            ?: return facts.refuse(FrameworkCodes.COMMAND_UNHANDLED, unhandled)
        val transformer = transformers[commandType.name]
        val decider = model.decider(commandType.type)
        if (decider == null && transformer == null) {
            return facts.refuse(FrameworkCodes.COMMAND_UNHANDLED, unhandled)
        }
        facts.function = functionOf(facts.type, commandType)
        // 2. The identity.
        val creation = decider?.creation ?: Creation.NONE
        identityConflict(request, creation, transformed = transformer != null)?.let {
            return facts.refuse(FrameworkCodes.COMMAND_MALFORMED, it)
        }
        val call = request.call
        callProblem(request)?.let { (code, message) -> return facts.refuse(code, message) }
        val target = call?.let { TargetScope(it.tenantId, it.scope) } ?: request.target
        if (call == null && target != null && !request.caller.crossScope) {
            return facts.refuse(FrameworkCodes.AUTH_FORBIDDEN, "The caller may not act in another scope.")
        }
        val tenantId = try {
            tenants.tenantOf(facts.type, request.caller, target)
        } catch (conflict: TenantConflictException) {
            return facts.refuse(FrameworkCodes.COMMAND_MALFORMED, conflict.message!!)
        }
        tenantConflict(request, tenantId)?.let { return facts.refuse(FrameworkCodes.COMMAND_MALFORMED, it) }
        val origin = CallerAdmission.of(request.caller, target)
        val scope = origin.scope
        val inputId = request.aggregateId ?: if (transformer == null) {
            creations.idOf(facts.type, tenantId, facts.requestId, scope, model.ownership)
        } else {
            UNADDRESSED
        }
        facts.ref = facts.type.ref(tenantId, inputId)
        AdmissionLimits.oversized(facts.requestId, inputId, tenantId, scope)
            ?.let { return facts.refuse(FrameworkCodes.COMMAND_MALFORMED, it) }
        // 3. Action-level authorization.
        authorize(request, CommandAction(commandType, facts.ref, scope, creation))?.let { return facts.refuse(it) }
        // 4. The body.
        bodyProblem(request.body)?.let { return facts.refuse(FrameworkCodes.COMMAND_MALFORMED, it) }
        val decoded = decode(request.body, commandType.type)
            ?: return facts.refuse(FrameworkCodes.COMMAND_MALFORMED, "The body is not a ${commandType.name}.")
        // 5. The transformation.
        val headers = headersOf(request)
        var command = Resolved(model, commandType, decoded.value, headers)
        if (transformer != null) {
            val input = CommandInput(
                commandType,
                decoded.value,
                request.aggregateId,
                headers,
                facts.requestId,
                request.caller,
                tenantId,
            )
            val result = transformer.transform(input)
            facts.transformedFrom = TransformedFrom(commandType.name, request.aggregateId)
            val (targetModel, targetDecider) = decidedBy(result.type)
                ?: return facts.refuse(
                    FrameworkCodes.COMMAND_UNHANDLED,
                    "${commandType.name} became ${result.type.name}, which no aggregate served here decides.",
                )
            val targetType = targetModel.type
            facts.function = functionOf(targetType, result.type)
            val targetTenant = try {
                tenants.tenantOf(targetType, request.caller, target)
            } catch (conflict: TenantConflictException) {
                return facts.refuse(FrameworkCodes.COMMAND_MALFORMED, conflict.message!!)
            }
            val aggregateId = result.aggregateId ?: if (targetDecider.creation != Creation.NONE) {
                creations.idOf(targetType, targetTenant, facts.requestId, scope, targetModel.ownership)
            } else {
                facts.ref = targetType.ref(targetTenant, UNADDRESSED)
                return facts.refuse(
                    FrameworkCodes.AGGREGATE_NOT_FOUND,
                    "No $targetType is found for ${commandType.name} to send ${result.type.name} to.",
                )
            }
            val ref = targetType.ref(targetTenant, aggregateId)
            AdmissionLimits.oversized(facts.requestId, aggregateId, targetTenant, scope)?.let {
                facts.ref = ref
                return facts.refuse(FrameworkCodes.COMMAND_MALFORMED, it)
            }
            if (result.type != commandType || ref != facts.ref) {
                facts.ref = ref
                val action = CommandAction(result.type, ref, scope, targetDecider.creation)
                authorize(request, action)?.let { return facts.refuse(it) }
            }
            command = Resolved(
                targetModel,
                result.type,
                result.body,
                result.headers + provenance(commandType, request.aggregateId),
            )
        }
        // 6. Validation.
        val violations = if (validators.isEmpty()) {
            emptyList()
        } else {
            validators.flatMap { it.validate(command.type, command.body) }
        }
        if (violations.isNotEmpty()) {
            return facts.refuse(
                FrameworkCodes.COMMAND_INVALID,
                "${command.type.name} is invalid.",
                violations = violations
            )
        }
        // 7. The command.
        val coordinator = coordinators.getValue(facts.ref.type.context)
        val plan = if (call != null) {
            WaitPlan.COMMITTED.within(coordinator.options.defaultTimeout)
        } else {
            request.wait ?: WaitPlan.DEFAULT.within(coordinator.options.defaultTimeout)
        }
        val admitted = Command(
            id = facts.commandId,
            requestId = facts.requestId,
            ref = facts.ref,
            body = command.body,
            expectedVersion = request.expectedVersion,
            scope = scope,
            headers = command.headers,
            causation = call?.causation,
            operator = if (call != null) call.operator else origin.operator,
            wait = plan.within(minOf(plan.timeout, coordinator.options.maxTimeout)),
        )
        return Admission.Admitted(command.model, admitted, facts.transformedFrom, call)
    }

    /**
     * Admits [request] for an aggregate this node does not serve: one of a peer context
     * whose contract the class path carries ([peers]) is checked as a command of this node is, up to its wait plan, and
     * sent to that context's command topic; any other is [FrameworkCodes.COMMAND_UNHANDLED]. Its command type is resolved
     * from the contract; its identity is decided as for this node's own aggregates, an aggregate id left out only for
     * a command that creates its aggregate, which the peer's intake then gives one; the caller is
     * authorized and the body decoded and validated here. The peer runs no transformer of this node, and a synchronous
     * call goes to the context that serves the aggregate, not through here. Its plan
     * ([peerPlanProblem]) waits for ACCEPTED, or for the peer's COMMITTED, the default.
     */
    @Suppress("ReturnCount", "CyclomaticComplexMethod", "LongMethod") // One early return per refusing step.
    private suspend fun admitPeer(request: ServerCommandRequest, facts: Facts): Admission {
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
        identityConflict(request, peer.creation, transformed = false)?.let {
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
        AdmissionLimits.oversized(facts.requestId, facts.ref.id, tenantId, scope)
            ?.let { return facts.refuse(FrameworkCodes.COMMAND_MALFORMED, it) }
        // 3. Action-level authorization.
        authorize(request, CommandAction(commandType, facts.ref, scope, peer.creation))?.let { return facts.refuse(it) }
        // 4. The body.
        bodyProblem(request.body)?.let { return facts.refuse(FrameworkCodes.COMMAND_MALFORMED, it) }
        val decoded = decode(request.body, commandType.type)
            ?: return facts.refuse(FrameworkCodes.COMMAND_MALFORMED, "The body is not a ${peer.name}.")
        // 6. Validation.
        val violations = validators.flatMap { it.validate(commandType, decoded.value) }
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

    /** Why the tenant [request]'s ref names is not [tenantId], the one admission decided; null when it is, or none. */
    private fun tenantConflict(request: ServerCommandRequest, tenantId: String): String? =
        request.tenantId?.takeIf { it != tenantId }?.let {
            "The command's ref names tenant $it, but it is sent in tenant $tenantId: a ref's tenant is the caller's, " +
                "a cross-scope target's or the one fixed for its type."
        }

    /**
     * The request facts of [request]'s command: a service's synchronous call carries those of its root request that
     * propagate ([CallContext.facts]) in place of the ones the edge recorded for the calling service.
     */
    private fun headersOf(request: ServerCommandRequest): Map<String, String> {
        val call = request.call ?: return request.headers
        return request.headers.filterKeys { it !in RequestFacts.PROPAGATED } + call.facts
    }

    /**
     * Why [request]'s synchronous call is refused, with its code, or null when it has none or may
     * make it: only a service identity asserts the root command a call acts for, and it names the call's scope in
     * the call alone.
     */
    private fun callProblem(request: ServerCommandRequest): Pair<String, String>? = when {
        request.call == null -> null
        !request.caller.service ->
            FrameworkCodes.AUTH_FORBIDDEN to
                "Only a service identity calls on behalf of a root command."

        request.target != null -> FrameworkCodes.COMMAND_MALFORMED to "A call names its scope in the call, not in Wow-Scope."
        else -> null
    }

    /** The authorizer's denial of [action], or null when it allows it. */
    private suspend fun authorize(request: ServerCommandRequest, action: CommandAction): Authorization.Denied? =
        authorizer.authorize(request.caller, action) as? Authorization.Denied

    /** The model that decides commands of [type], with its decide function; null when none does. */
    private fun decidedBy(type: CommandType): Pair<AggregateModel<*>, DecideFunction<*>>? {
        val (model, _) = deciders[type.type].orEmpty().singleOrNull { (_, commandType) -> commandType.name == type.name }
            ?: return null
        return model.decider(type.type)?.let { model to it }
    }

    /** Why [request]'s identity facts cannot be decided, or null when they can. */
    private fun identityConflict(request: ServerCommandRequest, creation: Creation, transformed: Boolean): String? {
        val reserved = request.headers.keys.firstOrNull(::isReservedHeader)
        return when {
            reserved != null -> "Header $reserved is reserved: identity, ids and the wait have fields of their own."
            request.requestId?.isBlank() == true -> "A request id, when given, is not blank."
            request.aggregateId?.isBlank() == true -> "An aggregate id, when given, is not blank."
            request.aggregateId == AggregateRef.UNASSIGNED ->
                "An aggregate id is never ${AggregateRef.UNASSIGNED}: leave it out for admission to choose one."
            request.aggregateId == null && creation == Creation.NONE && !transformed ->
                "${request.command} needs an aggregate id."

            (request.expectedVersion ?: 0) < 0 -> "An expected version is never negative."
            else -> null
        }
    }

    /**
     * Why [body] cannot be taken, or null when it can: it writes `\u0000` in a string or a key ([AdmissionLimits]); a
     * command object ([CommandBody.Value]) is checked as the JSON it is written as, which it must be.
     */
    private fun bodyProblem(body: CommandBody): String? {
        val nul = when (body) {
            is CommandBody.Bytes -> AdmissionLimits.writesNul(body.bytes)
            is CommandBody.Tree -> AdmissionLimits.holdsNul(body.node)
            is CommandBody.Value -> try {
                AdmissionLimits.holdsNul(mapper.valueToTree(body.command))
            } catch (failure: JacksonException) {
                return "The command ${body.command.javaClass.name} is not written as JSON: ${failure.originalMessage}"
            }
        }
        return if (nul) "The body writes \\u0000 in a string, which no store keeps." else null
    }

    /**
     * [body] read as [type], or null when it does not decode to one; a command object ([CommandBody.Value]) is taken as
     * it is when it is one.
     */
    private fun decode(body: CommandBody, type: Class<*>): Decoded? = try {
        when (body) {
            is CommandBody.Bytes -> mapper.readValue(body.bytes, type)
            is CommandBody.Tree -> mapper.treeToValue(body.node, type)
            is CommandBody.Value -> body.command.takeIf { type.isInstance(it) }
        }?.let(::Decoded)
    } catch (_: JacksonException) {
        null
    }

    private class Decoded(val value: Any)

    /** The command admission builds: a [body] of [type], decided by [model], with [headers]. */
    private class Resolved(
        val model: AggregateModel<*>,
        val type: CommandType,
        val body: Any,
        val headers: Map<String, String>,
    )

    /** The facts decided so far about a request, to refuse it with. */
    private inner class Facts(private val request: ServerCommandRequest, val commandId: String) {
        val type = AggregateType(request.context, request.aggregate)
        val requestId: String = request.requestId?.takeIf { it.isNotBlank() } ?: commandId
        var ref: AggregateRef = type.ref(
            tenants.fixedTenant(type) ?: request.caller.tenantId,
            request.aggregateId?.takeIf { it.isNotBlank() } ?: UNADDRESSED,
        )
        private var resolved: FunctionInfo? = null

        /** The command's function: as the request names it until admission resolves it, built only when needed. */
        var function: FunctionInfo
            get() = resolved ?: FunctionInfo(
                FunctionKind.COMMAND,
                type.context,
                type.name,
                request.command.substringAfterLast('.').ifBlank { request.command },
            ).also { resolved = it }
            set(value) {
                resolved = value
            }
        var transformedFrom: TransformedFrom? = null

        fun refuse(denied: Authorization.Denied): Admission.Refused = refuse(denied.code, denied.message)

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
                now = clock.millis(),
            )
            return Admission.Refused(rejected.copyWith(status = status, transformedFrom = transformedFrom))
        }
    }

    private sealed interface Admission {
        class Admitted(
            val model: AggregateModel<*>,
            val command: Command<*>,
            val transformedFrom: TransformedFrom?,
            /** The synchronous call the command came with, from a service identity. */
            val call: CallContext? = null,
        ) : Admission

        class Refused(val result: CommandResult) : Admission

        /** A peer context's command, sent to its command topic through [relay]. */
        data class Relayed(
            val command: Command<*>,
            val function: FunctionInfo,
            val relay: CommandRelay,
        ) : Admission

        /** How the admission ended, as its observation names it. */
        val observedName: String get() = if (this is Refused) "refused" else "admitted"
    }

    @InternalWowApi
    companion object {
        /** The aggregate id a refusal's ref names when admission had decided none. */
        @InternalWowApi
        const val UNADDRESSED: String = AggregateRef.UNASSIGNED

        private val logger = KotlinLogging.logger {}

        private val CommandType.shortName: String get() = name.substringAfterLast('.')

        private fun functionOf(type: AggregateType, command: CommandType): FunctionInfo =
            FunctionInfo(FunctionKind.COMMAND, type.context, type.name, command.shortName)

        /** The command [command] of [type] names: decided by the model or mounted under it. */
        private fun AggregateModel<*>.resolve(type: AggregateType, command: String): CommandType? =
            findCommandType(command)
                ?: findCommandType("$type.$command")
                ?: findMountedCommandType(command)
                ?: findMountedCommandType("$type.$command")

        /** The headers that name the input of a transformed command. */
        private fun provenance(input: CommandType, aggregateId: String?): Map<String, String> = buildMap {
            put(CommandTransformer.TRANSFORMED_FROM, input.name)
            aggregateId?.let { put(CommandTransformer.TRANSFORMED_FROM_ID, it) }
        }

        /**
         * [transformers] by the full type name of the command each takes; fails when two take one type, when one takes
         * a command no model of [registry] decides or mounts under that name and class (unless the name is of a context
         * [registry] does not serve), or when a mounted command has none.
         */
        private fun transformersOf(
            registry: ModelRegistry,
            transformers: List<CommandTransformer>,
        ): Map<String, CommandTransformer> {
            val ambiguous = transformers.groupBy { it.input.name }.filterValues { it.size > 1 }
            require(ambiguous.isEmpty()) {
                "More than one transformer takes ${ambiguous.keys.sorted().joinToString()}: at most one takes a command " +
                    "type: " +
                    ambiguous.values.flatten().joinToString { it.javaClass.name }
            }
            val known = registry.models.flatMap { it.commandTypes + it.mountedCommandTypes }.associateBy { it.name }
            transformers.forEach { transformer ->
                val input = transformer.input
                val type = known[input.name]
                require(type != null || input.name.substringBefore('.') !in registry.contexts) {
                    "${transformer.javaClass.name} takes ${input.name}, which no aggregate served here decides or mounts."
                }
                require(type == null || type.type == input.type) {
                    "${transformer.javaClass.name} takes ${input.name} as ${input.type.name}, but it is a ${type!!.type.name}."
                }
            }
            val byName = transformers.associateBy { it.input.name }
            val unclaimed = registry.models.flatMap { it.mountedCommandTypes }.filterNot { it.name in byName }
            require(unclaimed.isEmpty()) {
                "No transformer takes the mounted commands ${unclaimed.joinToString { it.name }}: a mounted command " +
                    "needs exactly one."
            }
            return byName
        }
    }
}
