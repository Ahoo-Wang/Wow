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

import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.RequestFacts
import me.ahoo.wow.api.id.Ids
import me.ahoo.wow.api.read.SubscriptionQueries
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.schedule.CancelSchedule
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.api.schedule.SetSchedule
import me.ahoo.wow.api.subscribe.CallFailedException
import me.ahoo.wow.api.subscribe.CallRejectedException
import me.ahoo.wow.api.subscribe.ExecutionId
import me.ahoo.wow.api.subscribe.ProjectContext
import me.ahoo.wow.api.subscribe.ReactContext
import me.ahoo.wow.api.subscribe.SagaContext
import me.ahoo.wow.api.subscribe.StateEvent
import me.ahoo.wow.api.subscribe.SubscriptionContext
import me.ahoo.wow.spi.id.IdGenerators
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * The context of a projection's handler for one execution (design 01 §1); its [queries] are [contextQueries]' of the
 * commit's tenant (decision D259).
 */
class ExecutionProjectContext @InternalWowApi constructor(
    override val executionId: ExecutionId,
    private val contextQueries: ContextQueries,
) : ProjectContext {
    /** The context of the execution [executionId], without queries: a domain module's test calls a projection with it. */
    constructor(executionId: ExecutionId) : this(executionId, ContextQueries.NONE)

    override val queries: SubscriptionQueries get() = contextQueries.of(executionId.ref.tenantId)

    override fun toString(): String = "ExecutionProjectContext(executionId=$executionId)"
}

/**
 * The context of a reactor's handler for one execution (design 01 §1); its [queries] are [contextQueries]' of the
 * commit's tenant (decision D259).
 */
class ExecutionReactContext @InternalWowApi constructor(
    override val executionId: ExecutionId,
    private val contextQueries: ContextQueries,
) : ReactContext {
    /** The context of the execution [executionId], without queries: a domain module's test calls a reactor with it. */
    constructor(executionId: ExecutionId) : this(executionId, ContextQueries.NONE)

    override val queries: SubscriptionQueries get() = contextQueries.of(executionId.ref.tenantId)

    override fun toString(): String = "ExecutionReactContext(executionId=$executionId)"
}

/**
 * The context of a saga's handler for one execution of the commit [event] (design 01 §1, design 04 §1). Each
 * execution, a retry included, gets a new one, so the n-th [command] of every execution of the same commit by the
 * same subscription is the same request: handling the commit again derives the same commands, which the kernel does
 * not decide twice (it deduplicates by request id). A domain module's tests build one to call a saga directly.
 *
 * Its schedules (decision D198) are commands too: [schedule] and [cancel] derive the system commands
 * `wow.schedule.Set` and `wow.schedule.Cancel` to their target, numbered with the other commands, the command they
 * schedule named by [commandTypes] and written by [codec].
 *
 * Its [ids] are the node's generators (decision D171); a domain module's test gives fixed ones.
 *
 * Its synchronous [call]s (decision D193) go through [calls]: the node's client of the other contexts' command
 * gateways, an answer in a domain module's test. Its [queries] are [contextQueries]' of the commit's tenant (decision
 * D259).
 */
class ExecutionSagaContext @InternalWowApi constructor(
    override val executionId: ExecutionId,
    private val event: StateEvent<*>,
    override val ids: Ids,
    private val commandTypes: CommandTypes,
    private val codec: EventCodec = EventCodec(),
    private val calls: CommandCalls = CommandCalls.NONE,
    private val contextQueries: ContextQueries = ContextQueries.NONE,
) : SagaContext {
    /** The context of the execution [executionId] of the commit [event], deriving its ids from [ids]. */
    constructor(executionId: ExecutionId, event: StateEvent<*>, ids: Ids = IdGenerators.DEFAULT) :
        this(executionId, event, ids, CommandTypes.SIMPLE_NAMES)

    private val derived = AtomicInteger()

    override val queries: SubscriptionQueries get() = contextQueries.of(executionId.ref.tenantId)

    init {
        require(event.ref == executionId.ref && event.version == executionId.version) {
            "Execution $executionId is not of the commit ${event.id}."
        }
    }

    /**
     * A command to [ref] asking [body]: its id and request id are [derivedId] of this execution and the call's index,
     * and its causation links it to the commit (`ref@version`) and to the root command of the chain; its operator is
     * the commit's, the root command's (decision D163), and so is its scope (decision D189): the owner and space of
     * the commit, so an aggregate it creates belongs to them; an existing one it changes is not checked against
     * them (decision D199), and they are recorded for the audit. It inherits the commit's request facts that
     * propagate ([RequestFacts.PROPAGATED], decision D232), such as the calling application, and no other header.
     */
    override fun <C : Any> command(ref: AggregateRef, body: C): Command<C> {
        val id = derivedId(executionId, derived.getAndIncrement())
        return Command(
            id = id,
            requestId = id,
            ref = ref,
            body = body,
            scope = event.scope,
            headers = RequestFacts.propagatedOf(event.headers),
            causation = Causation(event.correlationId, event.id),
            operator = event.operator,
        )
    }

    /**
     * A command to the aggregate [id] of the type that decides [body]'s class ([CommandTypes.aggregatesOf]), in the
     * tenant of the commit (decision D234 ⑤).
     */
    override fun <C : Any> command(id: String, body: C): Command<C> =
        command(commandTypes.omittedTargetOf(body.javaClass, executionId.subscription).ref(tenantId, id), body)

    /** A creation without an id (decision D191) of the type that decides [body]'s class (decision D234 ⑤). */
    override fun <C : Any> command(body: C): Command<C> =
        command(commandTypes.omittedTargetOf(body.javaClass, executionId.subscription), body)

    private val tenantId: String get() = executionId.ref.tenantId

    override fun schedule(key: String, rule: ScheduleRule, target: AggregateRef, command: Any): Command<SetSchedule> {
        val type = requireNotNull(commandTypes.nameOf(target.type, command.javaClass)) {
            "${executionId.subscription} schedules ${command.javaClass.name} for $target, a command no Manifest of the " +
                "service or its peers names."
        }
        return command(target, SetSchedule(key, rule, type, codec.commandText(command)))
    }

    override fun cancel(key: String, target: AggregateRef): Command<CancelSchedule> =
        command(target, CancelSchedule(key))

    /**
     * Calls [command] through [calls] (decision D193): its result when it succeeded; a [CallRejectedException] when
     * the target refused it, a [CallFailedException] when it failed there or reached no result.
     */
    override suspend fun call(command: Command<*>): CommandResult {
        val result = calls.call(command)
        return when (result.status) {
            CommandResult.Status.SUCCEEDED -> result
            CommandResult.Status.REJECTED -> throw CallRejectedException(command, result)
            CommandResult.Status.FAILED -> throw CallFailedException(
                "The call of ${command.id} on ${command.ref} failed: ${result.error?.code} ${result.error?.message}",
                result = result,
            )
        }
    }

    companion object {
        /**
         * The id, and request id, of the [index]-th command (from 0) derived by [execution]: a name-based UUID of
         * `execution#index`, stable across every execution of the same commit by the same subscription.
         */
        @JvmStatic
        fun derivedId(execution: ExecutionId, index: Int): String {
            require(index >= 0) { "A call index is never negative." }
            return UUID.nameUUIDFromBytes("$execution#$index".toByteArray(Charsets.UTF_8)).toString()
        }
    }
}

/**
 * The context a handler of [kind] gets for the execution of [event] by the subscription [subscription]: its
 * [ExecutionId] is (subscription, aggregate, version); a saga's takes its ids from [ids], names the commands it
 * schedules by [commandTypes] and makes its synchronous calls through [calls] (decision D193); every handler reads
 * through [queries] (decision D259).
 */
@InternalWowApi
fun contextOf(
    kind: FunctionKind,
    subscription: String,
    event: StateEvent<*>,
    ids: Ids = IdGenerators.DEFAULT,
    commandTypes: CommandTypes = CommandTypes.SIMPLE_NAMES,
    calls: CommandCalls = CommandCalls.NONE,
    queries: ContextQueries = ContextQueries.NONE,
): SubscriptionContext {
    val execution = ExecutionId(subscription, event.ref, event.version)
    return when (kind) {
        FunctionKind.PROJECT -> ExecutionProjectContext(execution, queries)
        FunctionKind.REACT -> ExecutionReactContext(execution, queries)
        FunctionKind.SAGA -> ExecutionSagaContext(execution, event, ids, commandTypes, EventCodec(), calls, queries)
        else -> throw IllegalArgumentException("A $kind handler is not a processor's.")
    }
}
