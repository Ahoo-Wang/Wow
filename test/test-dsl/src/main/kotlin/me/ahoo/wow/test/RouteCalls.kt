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

package me.ahoo.wow.test

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.client.CommandClient
import me.ahoo.wow.api.client.CommandRejectedException
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.read.Queries
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.Violation
import me.ahoo.wow.api.route.RouteCall
import me.ahoo.wow.kernel.CommandCalls
import me.ahoo.wow.spi.call.requestIdOf
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A call of a service route as a test gives it to the handler: [caller] acting in its own tenant and scope, under
 * [requestId], its reads on [queries] and its commands through [commands].
 *
 * Left at a default: the anonymous caller ([Caller.ANONYMOUS]), the request id `request-1`, and neither queries nor
 * commands. A handler that reads or sends then fails the test, naming what to give `routeCall`; an
 * [answeringCommandClient] answers the commands, and `memoryQueries` of `wow-test-query` serves the reads.
 */
fun routeCall(
    caller: Caller = Caller.ANONYMOUS,
    queries: Queries? = null,
    commands: CommandClient? = null,
    requestId: String = "request-1",
): RouteCall = TestRouteCall(caller, queries, commands, requestId)

private class TestRouteCall(
    override val caller: Caller,
    private val givenQueries: Queries?,
    private val givenCommands: CommandClient?,
    override val requestId: String,
) : RouteCall {
    override val tenantId: String get() = caller.tenantId
    override val scope: Scope get() = caller.scope
    override val queries: Queries
        get() = checkNotNull(givenQueries) { "The route call has no queries: give routeCall(queries = ...)." }
    override val commands: CommandClient
        get() = checkNotNull(givenCommands) {
            "The route call has no commands: give routeCall(commands = answeringCommandClient { ... })."
        }
}

/**
 * A command client a test answers, as a specification answers a saga's calls ([ProcessorSpec.answerCalls]): every
 * request it takes ([send], [wait], [stream], as [of] a caller or not) goes to [answer], and it keeps each in [calls].
 * As a real client does ([CommandClient]): [send] and [wait] throw an answer whose result is REJECTED as a
 * [CommandRejectedException], and one that FAILED as a [me.ahoo.wow.api.problem.WowProblemException]; [stream] emits
 * every answer, whatever its status. [answer] may also throw one, as a target that is down does.
 */
fun answeringCommandClient(answer: suspend (CommandCall) -> CommandResult): AnsweringCommandClient =
    AnsweringCommandClient(answer, null, CopyOnWriteArrayList())

/** The [CommandClient] of [answeringCommandClient]: [calls] holds every request it took, in order. */
class AnsweringCommandClient internal constructor(
    private val answer: suspend (CommandCall) -> CommandResult,
    private val caller: Caller?,
    private val taken: MutableList<CommandCall>,
) : CommandClient {
    /** Every call it took, in order, of this client and of every client [of] gave. */
    val calls: List<CommandCall> get() = taken.toList()

    /** This client sending as [caller]: what it takes goes to the same [answer] and the same [calls]. */
    override fun of(caller: Caller): AnsweringCommandClient = AnsweringCommandClient(answer, caller, taken)

    override suspend fun send(request: CommandRequest<*>): CommandResult = answered(request)

    override suspend fun wait(request: CommandRequest<*>): CommandResult = answered(request)

    /** Emits the answer, whatever its status, as a real client's stream emits every result. */
    override fun stream(request: CommandRequest<*>): Flow<CommandResult> = flow { emit(take(request)) }

    private suspend fun answered(request: CommandRequest<*>): CommandResult {
        val result = take(request)
        return when (result.status) {
            CommandResult.Status.REJECTED -> throw CommandRejectedException(result)
            CommandResult.Status.FAILED -> throw CommandCalls.failure(
                "${result.ref} failed the command.",
                result = result
            )
            else -> result
        }
    }

    /**
     * [request]'s answer, the call kept in [calls]. Sent while a decide function decides, a request that gives no id
     * takes the one a node's client gives it ([requestIdOf]): its command's next call, the same at every attempt, a
     * call that changes at a later attempt failing the command as on a node.
     */
    private suspend fun take(request: CommandRequest<*>): CommandResult {
        val call = CommandCall(caller, request, currentCoroutineContext().requestIdOf(request))
        taken += call
        return answer(call)
    }
}

/**
 * A request an [AnsweringCommandClient] took: the [caller] it was sent as (null for a client no [CommandClient.of]
 * gave), and the [request]. Its results are the ones a target answers it with, on [ref]: the request's aggregate when
 * it names its type and id, else the ref the test gives.
 */
class CommandCall internal constructor(
    val caller: Caller?,
    val request: CommandRequest<*>,
    /**
     * The request id the call was sent under: the request's own, else, sent while a decide function decides, the one
     * a node's client derives from the command being decided and the call's index; null for neither.
     */
    val requestId: String? = request.requestId,
) {
    /** The request's aggregate, when it names its type and id, in its target tenant or the caller's. */
    val ref: AggregateRef?
        get() {
            val type = request.type ?: return null
            val id = request.aggregateId ?: return null
            return type.ref(request.targetScope?.tenantId ?: caller?.tenantId ?: Caller.DEFAULT_TENANT, id)
        }

    /**
     * The result of the call when its target committed it at [version], on [ref], with the values its decision answered
     * ([results]), which the caller reads from [CommandResult.results].
     */
    fun committed(
        version: Int = 1,
        ref: AggregateRef = required(),
        results: Map<String, Any> = emptyMap(),
    ): CommandResult = committedResult(commandOf(ref), version, results = results)

    /**
     * The result of the call when its target refused it with [code] and [message], on [ref], naming the fields the
     * command broke ([violations]) and the rejection's structured [extensions]: the caller reads them from the
     * [CommandRejectedException] it gets ([CommandRejectedException.violations], [CommandRejectedException.error]).
     */
    fun rejected(
        code: String,
        message: String = code,
        ref: AggregateRef = required(),
        violations: List<Violation> = emptyList(),
        extensions: Map<String, Any?> = emptyMap(),
    ): CommandResult = rejectedResult(commandOf(ref), code, message, violations, extensions)

    /** The result of the call when its target failed it for a technical reason [code], on [ref]. */
    fun failed(code: String, message: String = code, ref: AggregateRef = required()): CommandResult =
        failedResult(commandOf(ref), code, message)

    private fun required(): AggregateRef = checkNotNull(ref) {
        "The request names no aggregate (type and id): give the result its ref."
    }

    private fun commandOf(ref: AggregateRef): Command<*> {
        val id = requestId ?: "request-of-${ref.id}"
        return Command(id, id, ref, request.command)
    }

    override fun toString(): String = "CommandCall(caller=$caller, request=$request)"
}
