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

package me.ahoo.wow.client

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.decide.RequestFacts
import me.ahoo.wow.api.problem.RetryAdvice
import me.ahoo.wow.api.problem.WowProblemException
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.api.wire.CallWire
import me.ahoo.wow.api.wire.WaitHeader
import me.ahoo.wow.api.wire.WowHeaders
import me.ahoo.wow.manifest.ClassPathContracts
import me.ahoo.wow.manifest.CommandIndex
import me.ahoo.wow.manifest.CommandRoutes
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.spi.call.CommandCaller
import me.ahoo.wow.spi.call.OutgoingCall
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.wait.toWire
import java.net.URI
import java.net.http.HttpClient
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * The synchronous calls of a node's sagas to the HTTP command gateways of other contexts: the service
 * mode of the command client ([ClientMode.Service]), at the base URL [endpoints] gives each context's
 * **internal port** (`wow.peers.<context>.url`), the only
 * one that takes a call; its public port refuses `Wow-Call`. Each call goes to the command's route as the contracts
 * list it ([CommandIndex.routeOf]), those of the peers' api jars on the class path by default, as the
 * [HttpCommandClient] sends one (a command whose body names its aggregate to its route without an id), and carries:
 *
 * - the command's body as JSON, and its expected version as `If-Match`;
 * - its deterministic request id as `Wow-Request-Id`, so every retry and replay is the same request, which the target
 *   decides once and answers with the same aggregate;
 * - `Wow-Call` ([me.ahoo.wow.api.wire.CallHeader]): its command id, the root command's tenant, scope, operator
 *   and causation, the root request's facts that propagate, and the
 *   wait context a wait that follows the saga goes on with;
 * - the calling service's name as `Wow-Service`, which makes the target take `Wow-Call`;
 * - the trace the call runs in.
 *
 * The call waits as long as the wait it goes on with has left, or the calling node's default wait for one that
 * goes on with none (`wow.wait.default-timeout`, which the host gives it; [WaitPlan.DEFAULT_TIMEOUT] for a caller of no
 * node), at most the context's timeout: it states that as `Wow-Wait-Timeout`, and waits for the answer
 * that long and [ANSWER_GRACE] more, never the transport's whole timeout (`wow.peer.timeout`, which bounds what has no
 * wait). The target answers 504 at the wait's end, so a target that has not answered by then never will: a call sent
 * on a connection to a node that is gone without closing it (a pod deleted by force) ends then (#6517).
 *
 * The target answers once the command is committed: 200 with its result, or a problem+json whose `result` is its
 * REJECTED or FAILED result. A REJECTED result is returned; a FAILED one is its problem's [WowProblemException],
 * which keeps the problem's `Retry-After` for the subscription's backoff (a 504's second, not the backoff's tenth).
 * The outcome is the one it was: a FAILED result returned failed the handler too, as `CommandCalls.failure` of its
 * code and result, classified alike; only the `Retry-After` is new.
 * Anything else is a [WowProblemException] too: an error answered without a result (401 or 403
 * for a call the target refuses, such as one of a service its lists do not name) as its own code, a connection that
 * fails or no answer within the bound as the transport's (`wow.command.unknown`, `wow.peer.unavailable`), and a
 * success the client cannot read as `wow.command.failed`, retried with the same request id. The saga's handler
 * fails and its subscription retries it, then parks the aggregate. The call itself is not retried here: the
 * subscription's retry budget is the one that counts.
 */
@InternalWowApi
class HttpCommandCaller internal constructor(
    private val transport: HttpTransport,
    private val commands: CommandIndex,
    /** The time a wait's deadline is read against, in epoch milliseconds. */
    private val clock: () -> Long = System::currentTimeMillis,
    /** The wait of a call that goes on with none: the calling node's default. */
    private val defaultWait: Duration = WaitPlan.DEFAULT_TIMEOUT,
) : CommandCaller {
    /**
     * The caller of the contexts' internal ports at the fixed [endpoints], as the service named [service], on
     * [client], each call answered within [timeout] and routed as [contracts] list its command. It takes fixed URLs
     * where the other takes a resolver, and has one timeout for every context.
     */
    constructor(
        endpoints: Map<String, URI>,
        service: String? = null,
        client: HttpClient = client(),
        timeout: Duration = DEFAULT_TIMEOUT,
        contracts: List<Manifest> = classPathContracts(),
    ) : this(EndpointResolver.of(emptyMap(), endpoints), service, client, timeout, contracts = contracts)

    /**
     * The caller of the contexts' internal ports [endpoints] finds, as the service named [service], each call answered
     * within its context's timeout of [timeouts], else [timeout], and routed as [contracts] list its command. A call
     * that goes on with no wait waits [defaultWait], the calling node's `wow.wait.default-timeout`. It builds what the
     * internal constructor takes: the transport from the endpoints, and the command index from the contracts.
     */
    constructor(
        endpoints: EndpointResolver,
        service: String?,
        client: HttpClient,
        timeout: Duration,
        timeouts: Map<String, Duration> = emptyMap(),
        contracts: List<Manifest> = classPathContracts(),
        defaultWait: Duration = WaitPlan.DEFAULT_TIMEOUT,
        /** The nodes that took a request and did not answer it: the node's, which its clients share. */
        silent: SilentPeers = SilentPeers(),
    ) : this(
        HttpTransport(endpoints, ClientMode.Service(service), client, timeout, RetryPolicy.NONE, timeouts, silent),
        CommandIndex(contracts),
        defaultWait = defaultWait,
    )

    /**
     * Whether the endpoints give [context] an internal URL: the node does not start when a saga calls
     * a context without one, rather than failing the call later. A resolver that discovers its services says whether it
     * can find one without looking it up ([EndpointResolver.reaches]), so a peer need not be up then.
     */
    override fun reaches(context: String): Boolean =
        runBlocking { transport.endpoints.reaches(context, Port.INTERNAL) }

    @Suppress("TooGenericExceptionCaught") // Whatever fails the exchange is a call that reached no result.
    override suspend fun call(call: OutgoingCall): CommandResult {
        val command = call.command
        val answer = try {
            transport.exchange(requestOf(call), Exchange.CALL)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // Named by its class: the problem's message is a caller's answer, and the failure's may carry values.
            throw failed(
                "The call of ${command.id} to ${command.ref.type.context} failed: ${failure.javaClass.name}",
                cause = failure,
            )
        }
        return when (answer) {
            is Answer.Ok -> CommandResults.readOrNull(answer.value)
            // A problem without a result (a call the target refuses, a peer no node of which answered) is its own, and
            // so is a FAILED result's, which keeps the problem's Retry-After.
            is Answer.Problem ->
                answer.problem.result?.takeIf { it.status != CommandResult.Status.FAILED }
                    ?: throw answer.problem.exception("The call of ${command.id} to ${command.ref.type.context}")
        } ?: throw failed("The call of ${command.id} to ${command.ref.type.context} was answered without a result.")
    }

    /** The request of [call]: its command's route, body, request id and expected version, and its root context. */
    internal fun requestOf(call: OutgoingCall): PeerRequest {
        val command = call.command
        val ref = command.ref
        val idProperty = commands.idPropertyOf(call.type)
        val byId = ref.isAssigned && idProperty == null
        val bound = boundOf(call)
        val route = checkNotNull(commands.routeOf(call.type, byId)) {
            "No contract lists a route of ${call.type} ${if (byId) "to an aggregate by its id" else "without an id"}: " +
                "depend on the api module of ${ref.type.context}, whose jar carries its contract."
        }
        return PeerRequest(
            context = ref.type.context,
            path = CommandRoutes.pathOf(route, ref.id.takeIf { byId }?.let(HttpTransport::encode)),
            method = route.method,
            caller = Caller.ANONYMOUS,
            call = contextOf(call),
            headers = buildMap {
                put(WowHeaders.REQUEST_ID, command.requestId)
                command.expectedVersion?.let { put(WowHeaders.IF_MATCH, "\"$it\"") }
                put(WowHeaders.WAIT_TIMEOUT, WaitHeader.formatTimeout(bound))
            },
            body = CommandBodies.of(call.type, command.body, idProperty, ref.id.takeIf { ref.isAssigned }),
            timeout = bound + ANSWER_GRACE,
        )
    }

    /**
     * How long [call] waits for its command: what the wait it goes on with has left (a millisecond at least), else
     * [defaultWait]; at most its context's timeout.
     */
    private fun boundOf(call: OutgoingCall): Duration {
        val wait = call.wait?.let { (it.deadline - clock()).milliseconds.coerceAtLeast(1.milliseconds) }
        return minOf(wait ?: defaultWait, transport.timeoutOf(call.command.ref.type.context))
    }

    /** A call that reached no result it could read: [FrameworkCodes.COMMAND_FAILED], a bad gateway. */
    private fun failed(message: String, cause: Throwable? = null): WowProblemException =
        WowProblemException(
            FrameworkCodes.COMMAND_FAILED,
            BAD_GATEWAY,
            RetryAdvice.SAME_REQUEST,
            null,
            message,
            cause = cause
        )

    @InternalWowApi
    companion object {
        private const val BAD_GATEWAY = 502

        /** The contracts of the peers' api jars on the thread's context class loader. */
        @JvmStatic
        fun classPathContracts(): List<Manifest> =
            ClassPathContracts.load(Thread.currentThread().contextClassLoader, { callerLogger.warn { it } }) { bytes ->
                WowJson.mapper.readValue(bytes, Map::class.java)
            }.map { it.manifest }

        /** The default bound of one call: as long as a wait's default timeout. */
        val DEFAULT_TIMEOUT: Duration = 10.seconds

        /**
         * How long a call waits for its answer past its wait: the internal port answers a call whose wait ran out
         * at most this long after it (its forward's answer grace, `InternalHttp.ANSWER_GRACE`).
         */
        val ANSWER_GRACE: Duration = 2.seconds

        /** The default connect timeout of [client]. */
        val DEFAULT_CONNECT_TIMEOUT: Duration = 2.seconds

        /** A JDK [HttpClient] for the ports of other services: HTTP/1.1, which every server speaks. */
        @JvmStatic
        @JvmOverloads
        fun client(connectTimeout: Duration = DEFAULT_CONNECT_TIMEOUT): HttpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(connectTimeout.toJavaDuration())
            .build()

        /**
         * The root command [call] acts for, as `Wow-Call` carries it, with the request facts its command inherited
         * that propagate: the target records the root request's application, not this service's.
         */
        internal fun contextOf(call: OutgoingCall): CallWire {
            val command = call.command
            return CallWire(
                commandId = command.id,
                tenantId = command.ref.tenantId,
                ownerId = command.scope.ownerId,
                spaceId = command.scope.spaceId,
                causation = checkNotNull(command.causation),
                operator = command.operator,
                wait = call.wait?.toWire(),
                facts = RequestFacts.propagatedOf(command.headers),
                actingTenant = call.actingTenant,
            )
        }
    }
}

private val callerLogger = KotlinLogging.logger {}
