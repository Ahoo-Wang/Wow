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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.ahoo.wow.api.aggregate.AggregateIdSource
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.caller.TargetScope
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.CallerAdmission
import me.ahoo.wow.kernel.CommandType
import me.ahoo.wow.kernel.RejectionLog
import me.ahoo.wow.runtime.ZeroAwaitingCounter
import me.ahoo.wow.runtime.admission.AdmissionIdentity.BodyId
import me.ahoo.wow.runtime.admission.AdmissionIdentity.OwnId
import me.ahoo.wow.runtime.admission.AdmissionIdentity.actingCaller
import me.ahoo.wow.runtime.admission.AdmissionIdentity.bodyId
import me.ahoo.wow.runtime.admission.AdmissionIdentity.callProblem
import me.ahoo.wow.runtime.admission.AdmissionIdentity.headersOf
import me.ahoo.wow.runtime.admission.AdmissionIdentity.identityConflict
import me.ahoo.wow.runtime.admission.AdmissionIdentity.ownId
import me.ahoo.wow.runtime.admission.AdmissionIdentity.tenantConflict
import me.ahoo.wow.runtime.cluster.ClusterCodec
import me.ahoo.wow.runtime.wait.WaitCoordinator
import me.ahoo.wow.runtime.wait.isAcceptedOnly
import me.ahoo.wow.spi.StorageUnavailableException
import me.ahoo.wow.spi.id.IdGenerators
import me.ahoo.wow.spi.observation.ObservedEvent
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.observation.observe
import me.ahoo.wow.spi.tenant.TenantConflictException
import me.ahoo.wow.spi.tenant.TenantPolicy
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
 *    admitted as [PeerAdmission.admitPeer] says and sent to that context's command topic; any other is
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
 *      finds the aggregate. A command whose body names its aggregate
 *      ([AggregateId][me.ahoo.wow.api.annotation.AggregateId]) has its body decoded here, as step 4 says, and its
 *      aggregate id is the body's: a request that names it too names the same one, and a blank one is
 *      [FrameworkCodes.COMMAND_MALFORMED]; a creation whose body names none is given one as above;
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
 * 3. Authorize the action with [authorizer], before the body is decoded unless it names the aggregate; without one,
 *    every action is allowed here ([authorizes]), and the public port refuses the built-in aggregates' system commands
 *    itself.
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
 *    ([CommandTransformer.TRANSFORMED_FROM]), and so does every result ([CommandResult.transformedFrom]). A
 *    transformer that throws a [RejectedException] refuses the command with its rejection, as the kernel answers a
 *    decide function's: its code qualified with the input's aggregate type, with its message and extensions.
 * 6. Validate the [CommandCandidate] with [validators]: their violations, together, are [FrameworkCodes.COMMAND_INVALID].
 * 7. Build the [Command], its wait plan the request's, or [WaitPlan.DEFAULT] within the coordinator's
 *    [default timeout][me.ahoo.wow.runtime.wait.WaitOptions.defaultTimeout] ([defaultWaitTimeout]) when it names none;
 *    its timeout capped by the coordinator's [maximum][me.ahoo.wow.runtime.wait.WaitOptions.maxTimeout].
 *
 * The coordinator then checks the plan, routes the command and waits for it; deduplication by request id and the
 * check that an existing aggregate belongs to the command's scope are the kernel's.
 *
 * The transformers are checked when the gateway is built ([Transformations]).
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
    /** The node's authorizer, the application's or CoSec's; null when it has none. */
    authorizer: CommandAuthorizer? = null,
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
    /**
     * Whether the node has an authorizer ([CommandGateway]'s `authorizer`): without one, every action is allowed at
     * step 3, and the public port refuses the system commands of Wow's built-in aggregates, the jobs' and the snapshot
     * rebuild's, never allowing them by default. Their senders in the process and on the internal port are not
     * refused.
     */
    @InternalWowApi
    val authorizes: Boolean = authorizer != null

    private val authorizer: CommandAuthorizer = authorizer ?: CommandAuthorizer.ALLOW_ALL

    private val creations = CreationIds(ids, committed)

    /** How a body is read as its command type. */
    private val bodies = CommandBodies(mapper)

    private val coordinators: Map<String, WaitCoordinator> = coordinators.toMap()
    private val detached = CoroutineScope(SupervisorJob() + detachedContext)

    /** The admission of a peer context's command, sent to its command topic. */
    private val peerAdmission =
        PeerAdmission(peers, tenants, validators, this.authorizer, mapper, bodies, this.coordinators)

    /** Step 5: the transformers, and the command each makes of the one a request names. */
    private val transformations = Transformations(registry, transformers, tenants, creations, this.authorizer)

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
        // Counted until a detached command is launched, or a peer's command is sent, so close() waits for it.
        val (admission, accepted) = counted {
            val admission = observed.observe { admitting(request).also { observed.outcome(it.observedName) } }
            admission to traced(trace) { acceptedOnly(admission) }
        }
        when (admission) {
            is Admission.Refused -> emit(observedRefusal(admission.result))
            is Admission.Relayed -> if (accepted != null) emit(accepted) else relay(admission, streaming, trace, emit)
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
     * [admission]'s result when its plan is ACCEPTED only, run while the admission is counted: a command of this node,
     * accepted, its detached execution launched; a peer's, sent to its command topic. Null for any other plan, which
     * its wait keeps until it ends.
     */
    private suspend fun acceptedOnly(admission: Admission): CommandResult? = when (admission) {
        is Admission.Refused -> null
        is Admission.Admitted -> admission.takeIf { it.call == null && it.command.wait.isAcceptedOnly }?.let {
            coordinators.getValue(it.command.ref.type.context).accept(it.model, it.command, detached)
        }
        is Admission.Relayed -> admission.takeIf { it.command.wait.isAcceptedOnly }?.let { (command, function, relay) ->
            coordinators.values.first().acceptRelayed(command, function, relay)
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
     * Sends [admission], a peer's command whose plan waits past ACCEPTED, through its command topic and hands [emit]
     * its results as [dispatch] does; any coordinator follows its wait, which no execution here is part of. One whose
     * plan is ACCEPTED only is sent while it is counted ([acceptedOnly]).
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
            streaming -> {
                val relayed = coordinator.relay(command, function, relay)
                (if (trace === EmptyCoroutineContext) relayed else relayed.flowOn(trace)).collect { emit(it) }
            }

            else -> emit(traced(trace) { coordinator.executeRelayed(command, function, relay) })
        }
    }

    /**
     * [refused], told to the observer by its code and logged at DEBUG ([RejectionLog]): refusing is admission's ordinary
     * work.
     */
    private fun observedRefusal(refused: CommandResult): CommandResult = refused.also { result ->
        result.error?.let {
            observer.observed(ObservedEvent.Refused(result.ref.type, it.code))
            val type = result.ref.type
            RejectionLog.refused(result.ref, "$type.${result.function.name}", it.code, result.requestId)
        }
    }

    /** Runs [block] in the admission's [trace], switching context only when there is one. */
    private suspend inline fun <T> traced(trace: CoroutineContext, crossinline block: suspend () -> T): T =
        if (trace === EmptyCoroutineContext) block() else withContext(trace) { block() }

    /**
     * The timeout of a command of [context] whose request names none: its coordinator's
     * [default][me.ahoo.wow.runtime.wait.WaitOptions.defaultTimeout], at most its maximum; [WaitPlan.DEFAULT_TIMEOUT]
     * for a context this gateway does not admit. An edge whose request names a plan but no timeout gives the plan
     * this timeout.
     */
    @InternalWowApi
    fun defaultWaitTimeout(context: String): Duration =
        coordinators[context]?.options?.defaultTimeout ?: WaitPlan.DEFAULT_TIMEOUT

    /**
     * Whether the node is on standby ([me.ahoo.wow.runtime.node.ServiceNode.start]): every request is refused with
     * [FrameworkCodes.NODE_STANDBY], before anything of it is read, until the node is activated.
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
     * Refuses new requests from now on ([FrameworkCodes.NODE_CLOSING]), waits for the admissions under way and the
     * detached commands accepted before, together at most [timeout] (the node's drain timeout), and closes their scope.
     *
     * An admission ends by its own send's deadline once admitted (`min(plan, wait.maxTimeout)`), but the application's
     * plugins it runs first and a detached command's run (its execution or forward) are bounded by nothing of the
     * gateway's. At [timeout], the detached commands still running are cancelled with the scope, and an admission still
     * under way is refused ([FrameworkCodes.NODE_CLOSING]) once its plugins return: nothing of it is launched or sent.
     */
    @InternalWowApi
    suspend fun close(timeout: Duration) {
        closed = true
        val ended = withTimeoutOrNull(timeout) {
            // A request counted before it read `closed` launches its detached command, if any, before it is uncounted.
            admissions.awaitZero()
            while (true) {
                val running = detached.coroutineContext.job.children.toList()
                if (running.isEmpty()) {
                    break
                }
                running.joinAll()
            }
        }
        if (ended == null) {
            logger.warn {
                "The gateway closes after $timeout: ${admissions.value} admissions under way are refused when they " +
                    "get past admission, ${detached.coroutineContext.job.children.count()} detached commands running " +
                    "are cancelled."
            }
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
        val facts = Facts(
            request,
            request.call?.takeIf { request.caller.service }?.commandId ?: ids.global.next(),
            tenants,
            clock,
        )
        return try {
            val admission = admit(request, facts)
            // Admitted once the close stopped waiting for it (its plugins outlived the bound): refused as one after the
            // close, so that nothing is launched on the cancelled scope nor sent, and no caller is told ACCEPTED.
            if (!detached.isActive && admission !is Admission.Refused) closing(facts) else admission
        } catch (unavailable: StorageUnavailableException) {
            logger.warn(unavailable) { "Request ${facts.requestId} was not admitted: a store is unavailable." }
            facts.refuse(FrameworkCodes.STORAGE_UNAVAILABLE, unavailable.message!!, CommandResult.Status.FAILED)
        }
    }

    /** [facts] refused because the node is closing. */
    private fun closing(facts: Facts): Admission =
        facts.refuse(FrameworkCodes.NODE_CLOSING, "The node is closing.", CommandResult.Status.FAILED)

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private suspend fun admit(request: ServerCommandRequest, facts: Facts): Admission {
        if (closed) {
            return closing(facts)
        }
        if (standby) {
            return facts.refuse(
                FrameworkCodes.NODE_STANDBY,
                "The node is on standby until the cut-over: it serves reads and refuses commands.",
                CommandResult.Status.FAILED,
            )
        }
        // 1. The command type.
        val model = registry.find(facts.type) ?: return peerAdmission.admitPeer(request, facts)
        val unhandled = "${facts.type} has no command ${request.command}."
        val commandType = model.resolve(facts.type, request.command)
            ?: return facts.refuse(FrameworkCodes.COMMAND_UNHANDLED, unhandled)
        val transformer = transformations.transformers[commandType.name]
        val decider = model.decider(commandType.type)
        if (decider == null && transformer == null) {
            return facts.refuse(FrameworkCodes.COMMAND_UNHANDLED, unhandled)
        }
        facts.function = functionOf(facts.type, commandType)
        // 2. The identity.
        val creation = decider?.creation ?: Creation.NONE
        val owned = model.idSource == AggregateIdSource.OWNER
        val fromBody = decider != null && model.idFromBody(commandType.type)
        identityConflict(request, creation, transformed = transformer != null || owned || fromBody)?.let {
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
        // Before any store is read for the request, the creation lookup by its request id included.
        AdmissionLimits.oversized(facts.requestId, request.aggregateId, tenantId, scope)
            ?.let { return facts.refuse(FrameworkCodes.COMMAND_MALFORMED, it) }
        // A command whose body names its aggregate is read now, so that its authorization names that aggregate.
        val early = if (fromBody) {
            bodies.bodyProblem(request.body)?.let { return facts.refuse(FrameworkCodes.COMMAND_MALFORMED, it) }
            bodies.decode(request.body, commandType.type)
                ?: return facts.refuse(FrameworkCodes.COMMAND_MALFORMED, "The body is not a ${commandType.name}.")
        } else {
            null
        }
        val inputId = if (owned) {
            when (val id = ownId(request.aggregateId, scope, request.caller)) {
                is OwnId.Refused -> {
                    facts.ref = facts.type.ref(tenantId, request.aggregateId ?: UNADDRESSED)
                    return facts.refuse(id.code, id.message)
                }

                is OwnId.Of -> id.id
            }
        } else if (early != null) {
            val named = bodyId(commandType.name, request.aggregateId, creation) { model.aggregateIdOf(early.value) }
            when (named) {
                is BodyId.Refused -> {
                    facts.ref = facts.type.ref(tenantId, request.aggregateId ?: UNADDRESSED)
                    return facts.refuse(FrameworkCodes.COMMAND_MALFORMED, named.message)
                }

                is BodyId.Of -> named.id ?: creations.idOf(facts.type, tenantId, facts.requestId, scope, model.ownership)
            }
        } else {
            request.aggregateId ?: if (transformer == null) {
                creations.idOf(facts.type, tenantId, facts.requestId, scope, model.ownership)
            } else {
                UNADDRESSED
            }
        }
        facts.ref = facts.type.ref(tenantId, inputId)
        // 3. Action-level authorization.
        authorizer.authorize(request, CommandAction(commandType, facts.ref, scope, creation))
            ?.let { return facts.refuse(it) }
        // 4. The body, unless it was read for its aggregate.
        val decoded = early ?: run {
            bodies.bodyProblem(request.body)?.let { return facts.refuse(FrameworkCodes.COMMAND_MALFORMED, it) }
            bodies.decode(request.body, commandType.type)
                ?: return facts.refuse(FrameworkCodes.COMMAND_MALFORMED, "The body is not a ${commandType.name}.")
        }
        AdmissionLimits.oversizedBody(decoded.value)?.let { return facts.refuse(FrameworkCodes.COMMAND_MALFORMED, it) }
        // 5. The transformation.
        val headers = headersOf(request)
        var command = Resolved(model, commandType, decoded.value, headers)
        if (transformer != null) {
            val transformed = transformations.transform(
                transformer,
                request,
                facts,
                commandType,
                decoded,
                headers,
                tenantId,
                target,
                scope,
            )
            command = when (transformed) {
                is Admission.Refused -> return transformed
                is Resolved -> transformed
            }
        }
        // 6. Validation.
        val operator = if (call != null) call.operator else origin.operator
        // A call's command is derived: the access policies trust it, and it carries no caller.
        val caller = if (call == null) actingCaller(request.caller, facts.ref, scope) else null
        val violations = validators.violationsOf { candidateOf(command, facts.ref, scope, operator, caller) }
        if (violations.isNotEmpty()) {
            val invalid = "${command.type.name} is invalid."
            return facts.refuse(FrameworkCodes.COMMAND_INVALID, invalid, violations = violations)
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
            operator = operator,
            wait = plan.within(minOf(plan.timeout, coordinator.options.maxTimeout)),
            caller = caller,
        )
        return Admission.Admitted(command.model, admitted, facts.transformedFrom, call)
    }

    @InternalWowApi
    companion object {
        /** The aggregate id a refusal's ref names when admission had decided none. */
        @InternalWowApi
        const val UNADDRESSED: String = AggregateRef.UNASSIGNED

        private val logger = KotlinLogging.logger {}

        /** The command [command] of [type] names: decided by the model or mounted under it. */
        private fun AggregateModel<*>.resolve(type: AggregateType, command: String): CommandType? =
            findCommandType(command)
                ?: findCommandType("$type.$command")
                ?: findMountedCommandType(command)
                ?: findMountedCommandType("$type.$command")
    }
}
