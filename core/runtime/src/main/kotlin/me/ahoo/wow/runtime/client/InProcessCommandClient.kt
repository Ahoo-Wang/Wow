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

package me.ahoo.wow.runtime.client

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.caller.TargetScope
import me.ahoo.wow.api.client.CommandClient
import me.ahoo.wow.api.client.CommandRejectedException
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.client.RetryHint
import me.ahoo.wow.api.client.WowProblemException
import me.ahoo.wow.api.client.targetTenantOf
import me.ahoo.wow.api.client.typeOf
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.manifest.FrameworkErrors
import me.ahoo.wow.runtime.admission.CommandBody
import me.ahoo.wow.runtime.admission.CommandGateway
import me.ahoo.wow.runtime.admission.ServerCommandRequest
import me.ahoo.wow.runtime.wait.ResultCodes
import me.ahoo.wow.spi.call.requestIdOf
import kotlin.time.Duration.Companion.seconds

/**
 * The [CommandClient] of a service's own node: each command goes to [gateway] as the object
 * it is ([ServerCommandRequest.of]), sent as [caller]. An aggregate the node serves runs it in process, never encoded; one
 * of a peer context known from its contract goes to that context's command topic, answering at ACCEPTED
 * or at its COMMITTED. The gateway admits it as any request: the caller is authorized, the command validated, its
 * identity decided.
 *
 * A request without a type goes to the one aggregate type that takes its command's class: one the node decides or
 * mounts it on, or one a peer context's contract lists it for ([CommandGateway.aggregatesOf]). Its tenant is the
 * caller's, unless it targets another ([targetTenantOf]).
 *
 * A failure's HTTP status is the one the edge would answer it with ([FrameworkErrors]), and its retry hint the one the
 * HTTP clients give (`retryHintOf` of `@ahoo-wang/wow-client`).
 */
class InProcessCommandClient @InternalWowApi constructor(
    private val gateway: CommandGateway,
    private val caller: Caller = Caller.ANONYMOUS,
) : CommandClient {
    override fun of(caller: Caller): CommandClient = InProcessCommandClient(gateway, caller)

    override suspend fun send(request: CommandRequest): CommandResult =
        settled(gateway.execute(serverRequest(request, WaitPlan.ACCEPTED.within(acceptTimeout(request)))))

    override suspend fun wait(request: CommandRequest): CommandResult =
        settled(gateway.execute(serverRequest(request, request.plan)))

    override fun stream(request: CommandRequest): Flow<CommandResult> = flow {
        emitAll(gateway.send(serverRequest(request, request.plan)))
    }

    /**
     * [request] as the gateway receives it, from [caller], with [plan]: its request id the request's, else derived
     * from the command being decided when sent from a decide function, else the command's id.
     */
    private suspend fun serverRequest(request: CommandRequest, plan: WaitPlan?): ServerCommandRequest {
        val type = typeOf(request, gateway::aggregatesOf)
        val command = request.command
        val targetTenant = targetTenantOf(request, caller.tenantId)
        return ServerCommandRequest.builder(type.context, type.name, command.javaClass.name, CommandBody.Value(command))
            .aggregateId(request.id)
            .caller(caller)
            .requestId(currentCoroutineContext().requestIdOf(request.requestId))
            .expectedVersion(request.expectedVersion)
            .target(
                if (targetTenant != null || request.targetScope != null) {
                    TargetScope(targetTenant, request.targetScope ?: Scope.NONE)
                } else {
                    null
                },
            )
            .wait(plan)
            .build()
    }

    /** How long [send] waits for the broker to acknowledge a peer's command: the request's timeout, else the default. */
    private fun acceptTimeout(request: CommandRequest) = request.timeout ?: WaitPlan.DEFAULT_TIMEOUT

    @InternalWowApi
    companion object {
        /** The `Retry-After` of a problem a retry may get past, as the edge answers it (`ErrorStatuses`). */
        private val RETRY_AFTER = 1.seconds

        /**
         * The codes a retry may get past, each with whether it keeps the request id: a command whose outcome is unknown,
         * a closing or busy node, a store that is down, a schedule not due yet, a contended aggregate, a wait that timed
         * out, whose command may have committed.
         */
        private val RETRYABLE: Set<String> = setOf(
            ResultCodes.UNKNOWN,
            ResultCodes.NODE_CLOSING,
            ResultCodes.NODE_STANDBY,
            ResultCodes.STORAGE_UNAVAILABLE,
            ResultCodes.SCHEDULE_NOT_DUE,
            ResultCodes.BUSY,
            ResultCodes.CONTENDED,
            ResultCodes.WAIT_TIMEOUT,
        )

        /** [result] when it succeeded; else the exception that says why it did not. */
        @InternalWowApi
        fun settled(result: CommandResult): CommandResult = when (result.status) {
            CommandResult.Status.SUCCEEDED -> result
            CommandResult.Status.REJECTED -> throw CommandRejectedException(result)
            else -> throw problemOf(result)
        }

        /** The problem of [result], a command that failed. */
        @InternalWowApi
        fun problemOf(result: CommandResult): WowProblemException {
            val error = checkNotNull(result.error) { "A result that did not succeed has an error." }
            return problemOf(result, "${result.ref} did not take the command: ${error.code} ${error.message}")
        }

        /** The problem of [result], a command that failed, said as [message], from [cause] when it is known. */
        internal fun problemOf(result: CommandResult, message: String, cause: Throwable? = null): WowProblemException {
            val code = checkNotNull(result.error) { "A result that did not succeed has an error." }.code
            val status = FrameworkErrors.BY_CODE[code]?.status ?: INTERNAL_ERROR
            val retryAfter = RETRY_AFTER.takeIf { status == UNAVAILABLE || status == TOO_MANY_REQUESTS }
            val hint = if (code in RETRYABLE) RetryHint(true, true, retryAfter) else RetryHint.NONE
            return WowProblemException(code, status, hint, message, result, cause)
        }

        private const val INTERNAL_ERROR = 500
        private const val UNAVAILABLE = 503
        private const val TOO_MANY_REQUESTS = 429
    }
}
