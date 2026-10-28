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

package me.ahoo.wow.api.client

import kotlinx.coroutines.flow.Flow
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.result.CommandResult
import kotlin.time.Duration

/**
 * Sends command objects to aggregates, wherever they are served: the same calls reach an
 * aggregate of this service in process, without encoding the command, and one of a peer context, known from the
 * contract its api jar carries, through that context's command topic, or over HTTP with the HTTP
 * implementation.
 *
 * Each call takes one [CommandRequest]: the command object, the aggregate it goes to (its [id][CommandRequest.id] and,
 * unless the command's class says, its [type][CommandRequest.type]), and how it is sent. The tenant and scope are the
 * caller's, or the target's a cross-scope caller names ([CommandRequest.targetTenant], [CommandRequest.targetScope]);
 * a target tenant that is the caller's own is the caller's, so a request built from a ref of the caller's tenant
 * (`CommandRequest.to(event.ref, command)` in a saga) is an ordinary one. Shorthands send a command by its id alone
 * (`client.wait(command, id)`).
 *
 * A client sends as one caller ([of]); the host's client sends as an anonymous one until [of] gives it another.
 * Commands have no trusted mode by design: a write always carries an accountable caller and goes through the
 * authorizer, so a system or service sender passes its own service caller.
 *
 * - [send] answers once the command is accepted: durable for a peer's command, whose topic acknowledged it, within the
 *   request's [timeout][CommandRequest.timeout]. A request whose wait plan waits for more fails before it is sent:
 *   [wait] answers with that plan's result.
 * - [wait] answers with the result the request's wait plan ([CommandRequest.wait]) ends with, the server's default
 *   plan when it names none.
 * - [stream] emits each result of that plan's hops as each is reached, the last one the result [wait] would answer
 *   with; collecting the flow sends the command.
 *
 * [send] and [wait] answer only a result that succeeded, and map what a command is answered by the result's status,
 * not by where it was refused (`@ahoo-wang/wow-client`'s `CommandClient` maps it the same way):
 *
 * - a result that succeeded: returned;
 * - a rejected result: a [CommandRejectedException];
 * - a problem whose result is rejected, an admission refusal (an unknown command, an invalid one, a forbidden
 *   caller): a [CommandRejectedException];
 * - a result of a status this client does not know, a newer peer's: a [WowProblemException] `wow.command.failed`,
 *   not retryable;
 * - any other problem, refused before admission (a body that does not parse), a technical failure (a busy or closing
 *   node, a store or a broker that is down, a wait that timed out) or a failed result: a [WowProblemException], with
 *   its result when the peer sent one, and whether to retry ([RetryHint]).
 *
 * [stream] emits every result, whatever its status. A request whose aggregate type the client cannot derive from its
 * command's class fails before anything is sent.
 */
interface CommandClient {
    /** This client sending as [caller]. */
    fun of(caller: Caller): CommandClient

    /** Sends [request], and answers once its command is accepted, whatever its wait plan. */
    suspend fun send(request: CommandRequest<*>): CommandResult

    /** Sends [request], and answers with the result its wait plan ends with. */
    suspend fun wait(request: CommandRequest<*>): CommandResult

    /** The results of [request]'s wait, sent when the flow is collected, in order. */
    fun stream(request: CommandRequest<*>): Flow<CommandResult>
}

/**
 * The command was rejected ([CommandResult.Status.REJECTED]): by its aggregate, a business outcome with the domain's
 * [code], or at admission (an unknown command, a malformed or invalid one, a forbidden caller). Sending it again with the
 * same request id is rejected again.
 */
class CommandRejectedException(val result: CommandResult) :
    RuntimeException("${result.ref} rejected the command: ${result.error?.code} ${result.error?.message}") {
    init {
        require(result.status == CommandResult.Status.REJECTED) { "A rejection has a REJECTED result." }
    }

    /** The rejection's error code. */
    val code: String get() = result.error!!.code
}

/**
 * The command did not reach a result that succeeded for a technical reason: its [code] (`wow.command.busy`,
 * `wow.storage.unavailable`, `wow.wait.timeout`, ...), the HTTP [status] it is answered with (problem+json), the
 * [result] when the server answered with one, and what a retry may do ([retryHint]).
 */
class WowProblemException @JvmOverloads constructor(
    val code: String,
    val status: Int,
    val retryHint: RetryHint,
    message: String,
    val result: CommandResult? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * What a client may do about a [WowProblemException]: whether a retry may succeed ([retryable]), whether it keeps the
 * request id ([sameRequestId]), so the server answers a command that did commit as a duplicate, and how long to wait
 * first ([retryAfter], the server's `Retry-After`).
 */
class RetryHint @JvmOverloads constructor(
    val retryable: Boolean,
    val sameRequestId: Boolean,
    val retryAfter: Duration? = null,
) {
    override fun equals(other: Any?): Boolean =
        this === other || other is RetryHint && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "RetryHint(retryable=$retryable, sameRequestId=$sameRequestId, retryAfter=$retryAfter)"

    private fun valueFields(): List<Any?> = listOf(retryable, sameRequestId, retryAfter)

    companion object {
        /** Not retried. */
        @JvmField
        val NONE: RetryHint = RetryHint(retryable = false, sameRequestId = false)
    }
}
