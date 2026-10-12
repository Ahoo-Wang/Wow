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

package me.ahoo.wow.http.command

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.withIndex
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.Stage
import me.ahoo.wow.http.HttpJson
import me.ahoo.wow.http.WowHeaders
import me.ahoo.wow.http.WowHttpOptions
import me.ahoo.wow.http.caller.Authenticator
import me.ahoo.wow.http.problem.ErrorStatuses
import me.ahoo.wow.runtime.admission.CommandBody
import me.ahoo.wow.runtime.admission.CommandGateway
import me.ahoo.wow.runtime.admission.CommandRequest
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.awaitBodyOrNull
import org.springframework.web.reactive.function.server.bodyAndAwait
import org.springframework.web.reactive.function.server.bodyValueAndAwait

/** The command a route sends: [command] of [aggregate] in [context], to the aggregate of the path's id when [byId]. */
internal data class CommandTarget(val context: String, val aggregate: String, val command: String, val byId: Boolean)

/**
 * Sends the command of a route through [gateway] (decisions D92, D98–D102):
 *
 * - the caller from [authenticator]; the wait (a plan that names no timeout waits the gateway's
 *   [default][CommandGateway.defaultWaitTimeout]), the target scope, the expected version and the request id from the Wow
 *   headers; the request facts [WowHttpOptions.requestFacts] lists as the command's headers; an empty body is `{}`;
 * - without `Accept: text/event-stream`, the result the wait ends with: 200, or 202 for a plan that ends at ACCEPTED,
 *   with the [CommandResult]; a problem with the result when it did not succeed;
 * - with it, the first result decides: an admission refusal (a result at ACCEPTED that did not succeed) is a problem,
 *   else 200 and one event per result, its id the result's index, its name the hop's stage, its data the result,
 *   until the wait ends; a rejection or a failure after admission is the stream's last event.
 *
 * The SSE results are collected in [scope], apart from the request, and handed over as the response is written; a
 * client that goes away cancels the collection.
 */
internal class CommandHandler(
    private val gateway: CommandGateway,
    private val authenticator: Authenticator,
    private val headers: RequestHeaders,
    private val statuses: ErrorStatuses,
    private val options: WowHttpOptions,
    private val scope: CoroutineScope,
) {
    suspend fun handle(request: ServerRequest, target: CommandTarget): ServerResponse {
        val caller = authenticator.authenticate(request)
        val eventStream = Negotiation.wantsEventStream(request)
        Negotiation.checkAcceptable(request, alsoEventStream = true)
        Negotiation.checkJsonBody(request)
        val expectedVersion = headers.expectedVersion(request)
        val command = CommandRequest(
            context = target.context,
            aggregate = target.aggregate,
            command = target.command,
            body = CommandBody.Bytes(request.awaitBodyOrNull<ByteArray>()?.takeIf { it.isNotEmpty() } ?: EMPTY_BODY),
            caller = caller,
            aggregateId = if (target.byId) request.pathVariable(ID) else null,
            target = headers.target(request),
            requestId = headers.requestId(request),
            expectedVersion = expectedVersion,
            headers = headers.facts(request, options.requestFacts),
            wait = headers.wait(request, gateway.defaultWaitTimeout(target.context)),
        )
        val ifMatch = request.headers().firstHeader(WowHeaders.IF_MATCH) != null
        return if (eventStream) stream(command, ifMatch) else execute(command, ifMatch)
    }

    private suspend fun execute(command: CommandRequest, ifMatch: Boolean): ServerResponse {
        val result = gateway.execute(command)
        if (result.status != CommandResult.Status.SUCCEEDED) {
            return statuses.problemOf(result, ifMatch).respond()
        }
        val status = if (result.hop.stage == Stage.ACCEPTED) HttpStatus.ACCEPTED else HttpStatus.OK
        return ServerResponse.status(status).contentType(MediaType.APPLICATION_JSON)
            .bodyValueAndAwait(HttpJson.write(result))
    }

    @OptIn(ExperimentalCoroutinesApi::class) // produce: the results of one collection, handed to the response.
    private suspend fun stream(command: CommandRequest, ifMatch: Boolean): ServerResponse {
        val results = scope.produce { gateway.send(command).collect { send(it) } }
        val first = results.receive()
        if (first.isAdmissionRefusal) {
            results.cancel()
            return statuses.problemOf(first, ifMatch).respond()
        }
        val events = flow {
            emit(first)
            emitAll(results)
        }.withIndex().map { (index, result) ->
            ServerSentEvent.builder(HttpJson.writeString(result))
                .id(index.toString())
                .event(result.hop.stage.name.lowercase())
                .build()
        }
        return ServerResponse.ok().contentType(MediaType.TEXT_EVENT_STREAM).bodyAndAwait(events)
    }

    companion object {
        private val CommandResult.isAdmissionRefusal: Boolean
            get() = hop.stage == Stage.ACCEPTED && status != CommandResult.Status.SUCCEEDED

        /** The path variable of the aggregate id. */
        const val ID: String = "id"

        private val EMPTY_BODY = "{}".toByteArray()
    }
}
