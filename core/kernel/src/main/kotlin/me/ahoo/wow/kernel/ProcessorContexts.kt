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
import me.ahoo.wow.api.subscribe.SagaContext
import me.ahoo.wow.api.subscribe.StateEvent
import me.ahoo.wow.api.subscribe.SubscriptionContext
import me.ahoo.wow.spi.id.IdGenerators

/**
 * The context of a projection's or a reactor's handler for one execution; its [queries] are [contextQueries]' of the
 * commit's tenant.
 */
class ExecutionContext @InternalWowApi constructor(
    override val executionId: ExecutionId,
    private val contextQueries: ContextQueries,
) : SubscriptionContext {
    /**
     * The context of the execution [executionId], without queries: a domain module's test calls a projection or a
     * reactor with it.
     */
    constructor(executionId: ExecutionId) : this(executionId, ContextQueries.NONE)

    override val queries: SubscriptionQueries get() = contextQueries.of(executionId.ref.tenantId)

    override fun toString(): String = "ExecutionContext(executionId=$executionId)"
}

/**
 * The context of a saga's handler for one execution of the commit [event]. Each
 * execution, a retry included, gets a new one, and each [command] is keyed by what it asks ([derivedId]): its target,
 * its command type and its occurrence among the commands of this execution with both. Handling the commit again
 * derives the same request for each command it derives again, in whatever order, which the kernel does not decide
 * twice (it deduplicates by request id). A domain module's tests build one to call a saga directly.
 *
 * Its schedules are commands too: [schedule] and [cancel] derive the system commands
 * `wow.schedule.Set` and `wow.schedule.Cancel` to their target, numbered with the other commands, the command they
 * schedule named by [commandTypes] and written by [codec].
 *
 * Its [ids] are the node's generators; a domain module's test gives fixed ones.
 *
 * Its synchronous [call]s go through [calls]: the node's client of the other contexts' command
 * gateways, an answer in a domain module's test. Its [queries] are [contextQueries]' of the commit's tenant.
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

    private val derived = DerivedRequests()

    override val queries: SubscriptionQueries get() = contextQueries.of(executionId.ref.tenantId)

    init {
        require(event.ref == executionId.ref && event.version == executionId.version) {
            "Execution $executionId is not of the commit ${event.id}."
        }
    }

    /**
     * A command to [ref] asking [body]: its id and request id are [derivedId] of this execution, its target, its
     * command type and its occurrence,
     * and its causation links it to the commit (`ref@version`) and to the root command of the chain; its operator is
     * the commit's, the root command's, and so is its scope: the owner and space of
     * the commit, so an aggregate it creates belongs to them; an existing one it changes is not checked against
     * them, and they are recorded for the audit. It inherits the commit's request facts that
     * propagate ([RequestFacts.PROPAGATED]), such as the calling application, and no other header.
     */
    override fun <C : Any> command(ref: AggregateRef, body: C): Command<C> {
        val id = derivedId(executionId, ref, body.javaClass, derived.next(ref, body.javaClass))
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
     * tenant of the commit.
     */
    override fun <C : Any> command(id: String, body: C): Command<C> =
        command(commandTypes.omittedTargetOf(body.javaClass, executionId.subscription).ref(tenantId, id), body)

    /** A creation without an id of the type that decides [body]'s class. */
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
     * Calls [command] through [calls]: its result when it succeeded; a [CallRejectedException] when
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
         * The id, and request id, of the [occurrence]-th command (from 0) to [ref] of [commandType] derived by
         * [execution]: a name-based UUID of the execution, the target (its id, or `unassigned` for a creation that
         * leaves it to admission), the command type and the occurrence, stable across every execution of the same
         * commit by the same subscription, whatever order the handler derives its commands in.
         */
        @JvmStatic
        @JvmOverloads
        fun derivedId(execution: ExecutionId, ref: AggregateRef, commandType: Class<*>, occurrence: Int = 0): String {
            require(occurrence >= 0) { "An occurrence is never negative." }
            return DerivedRequests.idOf("$execution#${DerivedRequests.keyOf(ref, commandType)}#$occurrence")
        }
    }
}

/**
 * The context a handler of [kind] gets for the execution of [event] by the subscription [subscription]: its
 * [ExecutionId] is (subscription, aggregate, version); a saga's takes its ids from [ids], names the commands it
 * schedules by [commandTypes] and makes its synchronous calls through [calls]; every handler reads
 * through [queries].
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
        FunctionKind.PROJECT, FunctionKind.REACT -> ExecutionContext(execution, queries)
        FunctionKind.SAGA -> ExecutionSagaContext(execution, event, ids, commandTypes, EventCodec(), calls, queries)
        else -> throw IllegalArgumentException("A $kind handler is not a processor's.")
    }
}
