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
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.WaitPlan
import kotlin.time.Duration

/**
 * Sends command objects to aggregates, wherever they are served (decisions D257, D258, D259): the same calls reach an
 * aggregate of this service in process, without encoding the command, and one of a peer context, known from the
 * contract its api jar carries (decision D234), through that context's command topic, or over HTTP with the HTTP
 * implementation.
 *
 * A command is the object of its class: its context, aggregate and command type come from its class and the [ref] it
 * is sent to; its identity only from [ref] (decision D68), whose tenant is the caller's (or the target's of
 * [SendOptions.targetTenant]) and whose id is [unassigned][AggregateRef.isAssigned] for a creation that leaves it to
 * the server (decision D191).
 *
 * A client sends as one caller ([of]); the host's client sends as an anonymous one until [of] gives it another.
 * Commands have no trusted mode by design: a write always carries an accountable caller and goes through the
 * authorizer, so a system or service sender passes its own service caller.
 *
 * - [send] answers once the command is accepted: durable for a peer's command, whose topic acknowledged it.
 * - [wait] answers with the result the command's wait plan ([SendOptions.wait]) ends with, the server's default plan
 *   when it names none.
 * - [stream] emits each result of that plan's hops as each is reached, the last one the result [wait] would answer
 *   with; collecting the flow sends the command.
 *
 * [send] and [wait] answer only a result that succeeded: a rejection, by the aggregate or at admission (an unknown
 * command, a malformed or invalid one, a forbidden caller), throws a [CommandRejectedException]; a technical failure
 * (a busy or closing node, a store or a broker that is down, a wait that timed out) throws a [WowProblemException]
 * that says whether to retry ([RetryHint]). [stream] emits every result, whatever its status.
 */
interface CommandClient {
    /** This client sending as [caller]. */
    fun of(caller: Caller): CommandClient

    /** Sends [command] to the aggregate [ref] names, and answers once it is accepted, whatever [options] waits for. */
    suspend fun send(command: Any, ref: AggregateRef, options: SendOptions = SendOptions.DEFAULT): CommandResult

    /** Sends [command] to the aggregate [ref] names, and answers with the result its wait plan ends with. */
    suspend fun wait(command: Any, ref: AggregateRef, options: SendOptions = SendOptions.DEFAULT): CommandResult

    /** The results of [command]'s wait, sent to the aggregate [ref] names when the flow is collected, in order. */
    fun stream(command: Any, ref: AggregateRef, options: SendOptions = SendOptions.DEFAULT): Flow<CommandResult>
}

/**
 * How a [CommandClient] sends a command: the [wait] plan (`Wow-Wait`; the server's default when null), bounded by
 * [timeout] when given (`Wow-Wait-Timeout`); the [requestId] that makes it idempotent (`Wow-Request-Id`; a new one
 * when null); the [expectedVersion] of the aggregate (`If-Match`); and, for a caller allowed to act in another scope
 * ([Caller.crossScope]), the [targetTenant] and [targetScope] it acts in (`Wow-Scope`).
 *
 * Fields may be added (decision D216): build it with [of]; the constructor is internal to Wow.
 */
class SendOptions @InternalWowApi constructor(
    val wait: WaitPlan? = null,
    val timeout: Duration? = null,
    val requestId: String? = null,
    val expectedVersion: Int? = null,
    val targetTenant: String? = null,
    val targetScope: Scope? = null,
) {
    init {
        require(timeout == null || timeout.isPositive()) { "A wait timeout is positive." }
        require(requestId == null || requestId.isNotBlank()) { "A request id, when given, is not blank." }
        require(expectedVersion == null || expectedVersion >= 0) { "An expected version is never negative." }
        require(targetTenant == null || targetTenant.isNotBlank()) { "A target tenant, when given, is not blank." }
    }

    /** The plan the command waits with: [wait] (or the default plan when only a [timeout] is given) within [timeout]. */
    val plan: WaitPlan?
        get() = when {
            timeout == null -> wait
            else -> (wait ?: WaitPlan.DEFAULT).within(timeout)
        }

    /** Whether the command acts in another scope than its caller's. */
    val targeted: Boolean get() = targetTenant != null || targetScope != null

    override fun equals(other: Any?): Boolean =
        this === other || other is SendOptions && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "SendOptions(wait=$wait, timeout=$timeout, requestId=$requestId, expectedVersion=$expectedVersion, " +
            "targetTenant=$targetTenant, targetScope=$targetScope)"

    private fun valueFields(): List<Any?> =
        listOf(wait, timeout, requestId, expectedVersion, targetTenant, targetScope)

    companion object {
        /** The server's default plan, a new request id, no expected version, the caller's own scope. */
        @JvmField
        val DEFAULT: SendOptions = SendOptions()

        /** Options with the given facts; each left out is as in [DEFAULT]. */
        @JvmStatic
        @JvmOverloads
        fun of(
            wait: WaitPlan? = null,
            timeout: Duration? = null,
            requestId: String? = null,
            expectedVersion: Int? = null,
            targetTenant: String? = null,
            targetScope: Scope? = null,
        ): SendOptions = SendOptions(wait, timeout, requestId, expectedVersion, targetTenant, targetScope)
    }
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
