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
import me.ahoo.wow.http.receiveFirst
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
 * - the W3C trace context headers (`traceparent`, `tracestate`, `baggage`) as the request's trace (decision D174),
 *   which the command's admission continues;
 * - `Wow-Call`, the root command of a service's synchronous call (decision D193): refused (403) on the public port;
 *   on the internal port (`internal`) required, from a service identity only, and answered once its command is
 *   committed, never as an event stream;
 * - without `Accept: text/event-stream`, the result the wait ends with: 200, or 202 for a plan that ends at ACCEPTED,
 *   with the [CommandResult]; a problem with the result when it did not succeed;
 * - with it, the first result decides: an admission refusal (a result at ACCEPTED that did not succeed) is a problem,
 *   else 200 and one event per result, its id the result's index, its name the hop's stage, its data the result,
 *   until the wait ends; a rejection or a failure after admission is the stream's last event, and so is a chain that
 *   ended before its plan did, its result marked `chainEnded` with the hops not reached as `pending` (decision D212),
 *   which is also the result answered without the event stream.
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
    /** Whether this is the internal port's handler, of the services' synchronous calls alone (decision D193). */
    private val internal: Boolean = false,
) {
    suspend fun handle(request: ServerRequest, target: CommandTarget): ServerResponse {
        val caller = authenticator.authenticate(request)
        val call = headers.call(request, caller, internal)
        val eventStream = call == null && Negotiation.wantsEventStream(request)
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
            trace = traceOf(request),
            call = call,
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
        // A wait's results end with a final one: a stream never ends without a first result.
        val first = checkNotNull(results.receiveFirst()) { "The command's results ended without one." }
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

        /** The W3C trace context header that names the caller's span; without it a request carries no trace. */
        const val TRACEPARENT: String = "traceparent"

        /** The W3C trace context headers that go with [TRACEPARENT]. */
        private val TRACE_STATE = listOf("tracestate", "baggage")

        /** The W3C trace context [request] carries (decision D174); empty without a [TRACEPARENT]. */
        internal fun traceOf(request: ServerRequest): Map<String, String> {
            val headers = request.headers()
            val parent = headers.firstHeader(TRACEPARENT) ?: return emptyMap()
            return buildMap {
                put(TRACEPARENT, parent)
                TRACE_STATE.forEach { name -> headers.firstHeader(name)?.let { put(name, it) } }
            }
        }

        private val EMPTY_BODY = "{}".toByteArray()
    }
}
