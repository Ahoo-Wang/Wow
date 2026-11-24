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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.withIndex
import kotlinx.coroutines.reactor.ReactorContext
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.result.Stage
import me.ahoo.wow.api.wire.WireNames
import me.ahoo.wow.api.wire.WowHeaders
import me.ahoo.wow.http.HttpJson
import me.ahoo.wow.http.WowHttpOptions
import me.ahoo.wow.http.caller.Authenticator
import me.ahoo.wow.http.caller.CallPermits
import me.ahoo.wow.http.handingOver
import me.ahoo.wow.http.problem.ErrorStatuses
import me.ahoo.wow.http.problem.Problem
import me.ahoo.wow.http.problem.ProblemException
import me.ahoo.wow.http.receiveBefore
import me.ahoo.wow.http.withKeepAlive
import me.ahoo.wow.runtime.admission.CommandBody
import me.ahoo.wow.runtime.admission.CommandGateway
import me.ahoo.wow.runtime.admission.ServerCommandRequest
import me.ahoo.wow.runtime.wait.StreamedWait
import me.ahoo.wow.spi.SystemCommandGroups
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.awaitBodyOrNull
import org.springframework.web.reactive.function.server.bodyAndAwait
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The command a route sends: [command] of [aggregate] in [context], to the aggregate of the path's id when [byId], or
 * to the aggregate [id] when the route names it (a built-in aggregate's instance, such as the snapshot rebuild's).
 */
internal data class CommandTarget(
    val context: String,
    val aggregate: String,
    val command: String,
    val byId: Boolean,
    val id: String? = null,
    /** Whether the command always creates its aggregate ([me.ahoo.wow.api.aggregate.Creation.REQUIRED]). */
    val creates: Boolean = false,
) {
    /**
     * Whether the command is an operational one ([SystemCommandGroups.OPERATIONAL]), a job's or the snapshot rebuild
     * on this port, which the public port refuses on a node without an authorizer.
     */
    val operational: Boolean get() = command in SystemCommandGroups.OPERATIONAL
}

/**
 * Sends the command of a route through [gateway] (–):
 *
 * - the caller from [authenticator]; the wait (a plan that names no timeout waits the gateway's
 *   [default][CommandGateway.defaultWaitTimeout]), the target scope, the expected version and the request id from the Wow
 *   headers; the request facts [WowHttpOptions.requestFacts] lists as the command's headers; an empty body is `{}`;
 * - the W3C trace context headers (`traceparent`, `tracestate`, `baggage`) as the request's trace,
 *   which the command's admission continues;
 * - an operational command ([CommandTarget.operational]), a job's or the snapshot rebuild: on the public port, refused (403
 *   `wow.auth.forbidden`) unless one of the node's authorizers decides them ([CommandGateway.decidesOperational]),
 *   never allowed by default, and authorized by the node's authorizers otherwise, as any command;
 * - `Wow-Call`, the root command of a service's synchronous call: refused (403) on the public port;
 *   on the internal port (`internal`) required, from a service identity only, for a command [permits] lets it call
 *   (403 [CallPermits.COMMAND_FORBIDDEN] otherwise), and answered once its command is committed, never
 *   as an event stream;
 * - without `Accept: text/event-stream`, the result the wait ends with: 200, or 202 for a plan that ends at ACCEPTED,
 *   with the [CommandResult]; a problem with the result when it did not succeed;
 * - with it, the head waits for the command to be accepted ([StreamedWait]): a refusal before that (an admission
 *   refusal, a full node, an invalid plan, a command topic that did not take it: a result at ACCEPTED that did not
 *   succeed) is a problem with its status and `Retry-After`; once it is accepted, 200, whatever hop the plan names
 *   first, then one event per result, its id the result's index, its name the hop's stage, its data the result, as
 *   each hop is reached, until the wait ends. A rejection, a timeout or a failure after the head is the stream's last
 *   event, and so is a chain that ended before its plan did, its result marked `chainEnded` with the hops not reached
 *   as `pending`, which is also the result answered without the event stream. After that last result, one more event,
 *   `end` with `{"reason":"complete"}`, says the stream is whole, as a streamed list's `end` does: a reader that does
 *   not get it before the stream ends, however cleanly, has an unknown outcome, and never infers the end from the plan
 *   (#6808). It follows only results that ended as the wait did: a stream whose results fail or are cancelled (the
 *   node stopping, its routes' scope cancelled) has no `end`. A comment (`: keep-alive`) is written at once, which
 *   sends the head, then every [KEEP_ALIVE], so a gateway's idle timeout does not close the stream (#6817); a reader
 *   of server-sent events skips comments.
 *
 * The SSE results are collected in [scope], apart from the request, and handed over as the response is written; a
 * client that goes away, or a response that is never written, cancels the collection. The collection keeps the
 * request's Reactor context, which the request's filters wrote (CoSec's security context among them), so the
 * command is admitted with the same context as one answered without the event stream.
 */
internal class CommandHandler(
    private val gateway: CommandGateway,
    private val authenticator: Authenticator,
    private val headers: RequestHeaders,
    private val statuses: ErrorStatuses,
    private val options: WowHttpOptions,
    private val scope: CoroutineScope,
    /** Whether this is the internal port's handler, of the services' synchronous calls alone. */
    private val internal: Boolean = false,
    /** The commands each service may call on the internal port; every one without. */
    private val permits: CallPermits? = null,
) {
    suspend fun handle(request: ServerRequest, target: CommandTarget): ServerResponse {
        val caller = authenticator.authenticate(request)
        if (target.operational && !internal && !gateway.decidesOperational) {
            throw ProblemException(statuses.problemOf(FrameworkCodes.AUTH_FORBIDDEN, refusedWithoutAuthorizer(target)))
        }
        val call = headers.call(request, caller, internal)
        if (internal) {
            permits?.refusal(caller, target.context, target.aggregate, target.command)?.let { refusal ->
                throw ProblemException(
                    Problem(
                        HttpStatus.FORBIDDEN.value(),
                        "The service may not call this command.",
                        refusal,
                        CallPermits.COMMAND_FORBIDDEN
                    ),
                )
            }
        }
        val eventStream = call == null && Negotiation.wantsEventStream(request)
        Negotiation.checkAcceptable(request, alsoEventStream = true)
        Negotiation.checkJsonBody(request)
        val expectedVersion = headers.expectedVersion(request, target.context, target.creates)
        val command = ServerCommandRequest(
            context = target.context,
            aggregate = target.aggregate,
            command = target.command,
            body = CommandBody.Bytes(request.awaitBodyOrNull<ByteArray>()?.takeIf { it.isNotEmpty() } ?: EMPTY_BODY),
            caller = caller,
            aggregateId = target.id ?: if (target.byId) request.pathVariable(ID) else null,
            target = headers.target(request),
            requestId = headers.requestId(request, options.requestIdHeaders),
            expectedVersion = expectedVersion,
            headers = headers.facts(request, options.requestFacts),
            wait = headers.wait(request, gateway.defaultWaitTimeout(target.context)),
            trace = traceOf(request),
            call = call,
        )
        val ifMatch = request.headers().firstHeader(WowHeaders.IF_MATCH) != null
        return if (eventStream) stream(command, ifMatch) else execute(command, ifMatch)
    }

    private suspend fun execute(command: ServerCommandRequest, ifMatch: Boolean): ServerResponse {
        val result = gateway.execute(command)
        if (result.status != CommandResult.Status.SUCCEEDED) {
            return statuses.problemOf(result, ifMatch).respond()
        }
        val status = if (result.hop.stage == Stage.ACCEPTED) HttpStatus.ACCEPTED else HttpStatus.OK
        return ServerResponse.status(status).contentType(MediaType.APPLICATION_JSON)
            .bodyValueAndAwait(HttpJson.write(result))
    }

    @OptIn(ExperimentalCoroutinesApi::class) // produce: the results of one collection, handed to the response.
    private suspend fun stream(command: ServerCommandRequest, ifMatch: Boolean): ServerResponse {
        val request = currentCoroutineContext()[ReactorContext] ?: EmptyCoroutineContext
        val accepted = CompletableDeferred<Unit>()
        val streamed = StreamedWait { accepted.complete(Unit) }
        val results = scope.produce(request + streamed) { gateway.send(command).collect { send(it) } }
        // The head goes out once the command is accepted: a result before it is a refusal, a problem with its status,
        // or the one result of a plan answered on acceptance alone.
        val first = results.receiveBefore(accepted)
        if (first == null) {
            check(accepted.isCompleted) { "The command's results ended without one." }
        } else if (first.isAdmissionRefusal) {
            results.cancel()
            return statuses.problemOf(first, ifMatch).respond()
        }
        val events = flow {
            first?.let { emit(it) }
            emitAll(results)
        }.withIndex().map { (index, result) ->
            ServerSentEvent.builder(HttpJson.writeString(result))
                .id(index.toString())
                .event(WireNames.of(result.hop.stage))
                .build()
        }.onCompletion { failure ->
            // After the last result alone: results that failed or were cancelled leave the outcome unknown.
            if (failure == null) emit(COMPLETE)
        }.withKeepAlive(KEEP_ALIVE, KEEP_ALIVE_COMMENT)
        return results.handingOver(events) {
            ServerResponse.ok().contentType(MediaType.TEXT_EVENT_STREAM).bodyAndAwait(it)
        }
    }

    companion object {
        /** Why the public port refuses [target], a built-in aggregate's system command, on a node without an authorizer. */
        internal fun refusedWithoutAuthorizer(target: CommandTarget): String =
            "${target.command} is refused on the public port: no CommandAuthorizer of the service decides Wow's " +
                "operational commands (CommandAuthorizer.operational, or CoSec's with wow.cosec.authorizer.enabled)."

        private val CommandResult.isAdmissionRefusal: Boolean
            get() = hop.stage == Stage.ACCEPTED && status != CommandResult.Status.SUCCEEDED

        /** How often a command's event stream writes a comment, events or not: below common idle timeouts. */
        val KEEP_ALIVE: Duration = 15.seconds

        /** The event a command's event stream ends with, after its last result: the stream is whole. */
        const val END_EVENT: String = "end"

        private val COMPLETE: ServerSentEvent<String> =
            ServerSentEvent.builder("{\"reason\":\"complete\"}").event(END_EVENT).build()

        /** The comment it writes, which a reader of server-sent events skips. */
        private val KEEP_ALIVE_COMMENT: ServerSentEvent<String> =
            ServerSentEvent.builder<String>().comment("keep-alive").build()

        /** The path variable of the aggregate id. */
        const val ID: String = "id"

        /** The W3C trace context header that names the caller's span; without it a request carries no trace. */
        const val TRACEPARENT: String = "traceparent"

        /** The W3C trace context headers that go with [TRACEPARENT]. */
        private val TRACE_STATE = listOf("tracestate", "baggage")

        /** The W3C trace context [request] carries; empty without a [TRACEPARENT]. */
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
