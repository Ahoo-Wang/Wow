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

package me.ahoo.wow.test.server

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.ErrorInfo
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.result.FunctionInfo
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.wire.WowHeaders
import me.ahoo.wow.http.internal.InternalPort
import me.ahoo.wow.http.internal.InternalPortSettings
import me.ahoo.wow.http.problem.ErrorStatuses
import me.ahoo.wow.manifest.ClassPathContracts
import me.ahoo.wow.manifest.CommandManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.runtime.admission.CallContext
import me.ahoo.wow.runtime.admission.CallHeaders
import me.ahoo.wow.spi.id.GlobalIdGenerator
import me.ahoo.wow.spi.json.WowJson
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.awaitBodyOrNull
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import org.springframework.web.reactive.function.server.coRouter
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import java.net.URI
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * Starts a fake of the peer service that serves [context], on a port of the loopback interface the system chooses:
 * the call routes of its internal port, which a saga's synchronous call and a service's `wow-client` send its commands
 * to. Its commands and their routes are the ones its api module's contract on the class path declares, as the caller
 * finds them. Give it to the server under test ([wowServer]'s `peers`), which then calls it at its [FakePeer.url].
 *
 * ```kotlin
 * fakePeer("eshop-ordering").use { ordering ->
 *     wowServer<BasketServer>("dev", peers = listOf(ordering)).use { basket ->
 *         // check out the basket: its saga calls the ordering
 *         ordering.called<SubmitOrder>().operator!!.id.assert().isEqualTo("alice")
 *     }
 * }
 * ```
 */
fun fakePeer(context: String): FakePeer = FakePeer(context, Thread.currentThread().contextClassLoader)

/**
 * A fake of the peer service of [context] ([fakePeer]): it records each call it takes ([calls]), decoded as the peer's
 * node would decode it, and answers it as [answer] says, a success by default. It refuses a request that names no
 * service (`Wow-Service`) or no root command (`Wow-Call`) with 403 `wow.auth.forbidden`, as a peer's internal port
 * does, and records none. Closing it stops its port.
 */
class FakePeer internal constructor(val context: String, classLoader: ClassLoader) : AutoCloseable {
    private val contract: Manifest = contractOf(context, classLoader)
    private val commands: List<PeerCommand> = commandsOf(contract, context, classLoader)

    /** The statuses of the peer's errors, from its contract's tables, as its own routes answer them. */
    private val statuses = ErrorStatuses(contract)
    private val lock = ReentrantLock()
    private val received = lock.newCondition()
    private val recorded = mutableListOf<PeerCall>()
    private val taken = mutableSetOf<PeerCall>()

    @Volatile
    private var rule: (PeerCall) -> PeerAnswer = { PeerAnswer.succeeded() }

    private val port = InternalPort.of(InternalPortSettings(0, LOOPBACK), routes()).apply { start() }

    /** The base URL of its internal port, which the server under test's `wow.peers.<context>.url` names. */
    val url: URI = URI.create("http://$LOOPBACK:${port.boundPort}")

    /** Every call it took, in order. */
    val calls: List<PeerCall> get() = lock.withLock { recorded.toList() }

    /**
     * Answers each call from now on as [rule] says: [PeerAnswer.succeeded], [PeerAnswer.rejected] or
     * [PeerAnswer.timedOut].
     */
    fun answer(rule: (PeerCall) -> PeerAnswer) {
        this.rule = rule
    }

    /**
     * The first call of a command of [type] that no call of this function returned yet, waited for at most [timeout];
     * fails when none comes. A test takes each call of a type in turn so.
     */
    fun called(type: Class<*>, timeout: Duration = EVENTUALLY_TIMEOUT): PeerCall {
        val deadline = TimeSource.Monotonic.markNow() + timeout
        lock.withLock {
            while (true) {
                recorded.firstOrNull { type.isInstance(it.command) && it !in taken }?.let { call ->
                    taken += call
                    return call
                }
                val left = -deadline.elapsedNow()
                if (!left.isPositive()) {
                    throw AssertionError("No call of ${type.simpleName} to $context in $timeout; took ${calls()}.")
                }
                received.await(left.inWholeNanoseconds, java.util.concurrent.TimeUnit.NANOSECONDS)
            }
        }
    }

    /** The first call of a command of [C] not returned yet, waited for at most [timeout]; see [called]. */
    inline fun <reified C : Any> called(
        timeout: Duration = EVENTUALLY_TIMEOUT
    ): PeerCall = called(C::class.java, timeout)

    override fun close() {
        port.stop()
    }

    override fun toString(): String = "FakePeer($context, $url)"

    private fun calls(): List<String> = recorded.map { it.commandType }

    private fun routes() = coRouter {
        commands.forEach { command ->
            command.manifest.routes.filter { it.method.equals("POST", ignoreCase = true) }.forEach { route ->
                POST(route.path) { request -> handle(request, command) }
            }
        }
    }

    private suspend fun handle(request: ServerRequest, command: PeerCommand): ServerResponse {
        val headers = request.headers()
        val service = headers.firstHeader(WowHeaders.SERVICE)?.takeIf { it.isNotBlank() }
        val callHeader = headers.firstHeader(WowHeaders.CALL)?.takeIf { it.isNotBlank() }
        if (service == null || callHeader == null) {
            val detail = "A call names its service (${WowHeaders.SERVICE}) and its root command (${WowHeaders.CALL})."
            return statuses.problemOf(FrameworkCodes.AUTH_FORBIDDEN, detail).respond()
        }
        val body = request.awaitBodyOrNull<ByteArray>()?.takeIf { it.isNotEmpty() }
        val tree: JsonNode = body?.let { WowJson.mapper.readTree(it) } ?: WowJson.mapper.createObjectNode()
        val aggregateId = request.pathVariables()[ID] ?: command.manifest.idProperty?.let { property ->
            (tree as? ObjectNode)?.get(property)?.takeIf { it.isString }?.asString()
        }
        val call = PeerCall(
            command = command.type?.let { WowJson.mapper.treeToValue(tree, it) } ?: tree,
            commandType = command.typeName,
            aggregateId = aggregateId,
            service = service,
            requestId = headers.firstHeader(WowHeaders.REQUEST_ID),
            call = CallHeaders.parse(callHeader),
        )
        lock.withLock {
            recorded += call
            received.signalAll()
        }
        return rule(call).respond(call, command, statuses)
    }

    /** A command of the peer's contract: its [manifest], its aggregate's [type] and its class, when it is on the class path. */
    internal class PeerCommand(val aggregate: AggregateType, val manifest: CommandManifest, val type: Class<*>?) {
        val typeName: String = "${aggregate.context}.${aggregate.name}.${manifest.name}"
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val ID = "id"
        const val DEFS_PREFIX = "#/\$defs/"

        /** The contract on [classLoader] that declares [context]; refused when none does. */
        fun contractOf(context: String, classLoader: ClassLoader): Manifest {
            // A peer's api jar is built by the peer's Wow, which may be a minor ahead: what it holds unknown is logged.
            val contracts = ClassPathContracts.load(classLoader, { contractsLogger.warn(it) }) {
                WowJson.mapper.readValue(it, Map::class.java)
            }
            return checkNotNull(contracts.map { it.manifest }.firstOrNull { it.contexts.any { it.name == context } }) {
                "No contract on the class path declares the context $context: the test depends on its api module."
            }
        }

        /** The commands [contract] declares for [context], each with its class on [classLoader] when it is there. */
        fun commandsOf(contract: Manifest, context: String, classLoader: ClassLoader): List<PeerCommand> =
            contract.contexts.filter { it.name == context }.flatMap { it.aggregates }.flatMap { aggregate ->
                aggregate.commands.map { command ->
                    PeerCommand(AggregateType(context, aggregate.name), command, classOf(command.schema, classLoader))
                }
            }

        fun classOf(schema: String, classLoader: ClassLoader): Class<*>? = try {
            Class.forName(schema.removePrefix(DEFS_PREFIX), false, classLoader)
        } catch (_: ClassNotFoundException) {
            null
        }
    }
}

/**
 * A call a [FakePeer] took: the [command] it sends, decoded to its class when the class path has it (else its JSON),
 * by its [commandType] (`context.aggregate.Command`), the [aggregateId] it names (its route's, or its body's id
 * property; null for a creation that names none), the [service] that sent it (`Wow-Service`), its [requestId], and the
 * root command it acts for ([call], `Wow-Call`): its tenant, scope and operator.
 */
class PeerCall internal constructor(
    val command: Any,
    val commandType: String,
    val aggregateId: String?,
    val service: String,
    val requestId: String?,
    val call: CallContext,
) {
    /** The tenant the call acts in. */
    val tenantId: String get() = call.tenantId

    /** The scope the call acts in: the root command's owner and space. */
    val scope: Scope get() = call.scope

    /** Who the root command acts for; null for an anonymous one. */
    val operator: Operator? get() = call.operator

    override fun toString(): String = "PeerCall($commandType, $aggregateId, $service, $call)"
}

/**
 * How a [FakePeer] answers a call ([FakePeer.answer]): it [succeeded] (the default), was [rejected], or [timedOut]. A
 * problem is the one the peer's own routes answer: its status from the peer's contract (a code its error tables
 * declare takes their status, an undeclared domain rejection 422), its title, its result and what a retry may do.
 */
class PeerAnswer private constructor(
    private val id: String?,
    private val version: Int,
    private val code: String?,
    private val message: String?,
    private val status: Int?,
) {
    internal suspend fun respond(
        call: PeerCall,
        command: FakePeer.PeerCommand,
        statuses: ErrorStatuses
    ): ServerResponse {
        val type = command.aggregate
        if (code == FrameworkCodes.WAIT_TIMEOUT) {
            return statuses.problemOf(code, message.orEmpty(), type.context).respond()
        }
        val ref = type.ref(call.tenantId, id ?: call.aggregateId ?: GlobalIdGenerator.UUID_V7.next())
        val function = FunctionInfo.of(FunctionKind.COMMAND, type.context, type.name, command.manifest.name)
        val requestId = call.requestId ?: call.call.commandId
        val correlationId = call.call.causation.correlationId
        val signalTime = System.currentTimeMillis()
        val hop = Hop.committed(type.context)
        if (code == null) {
            val result = CommandResult.succeeded(
                call.call.commandId,
                requestId,
                correlationId,
                ref,
                hop,
                function,
                version,
                signalTime,
            )
            return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON)
                .bodyValueAndAwait(WowJson.mapper.writeValueAsBytes(result))
        }
        val error = ErrorInfo.of(code, message.orEmpty())
        val result = CommandResult.rejected(
            call.call.commandId, requestId, correlationId, ref, hop, function, null, error, signalTime,
        )
        val problem = statuses.problemOf(result)
        return (status?.let { problem.copyWith(status = it) } ?: problem).respond()
    }

    companion object {
        /**
         * The command committed at [version], to the aggregate [id]: the call's own by default, else a new one, as a
         * creation that names none gets.
         */
        @JvmStatic
        @JvmOverloads
        fun succeeded(id: String? = null, version: Int = 1): PeerAnswer = PeerAnswer(id, version, null, null, null)

        /**
         * The peer's aggregate rejected the command with the error [code] and [message], answered with the status the
         * peer's contract gives the code, or [status] when given.
         */
        @JvmStatic
        @JvmOverloads
        fun rejected(code: String, message: String = code, status: Int? = null): PeerAnswer =
            PeerAnswer(null, 0, code, message, status)

        /**
         * The peer's node waited for the commit past its timeout: 504 `wow.wait.timeout`, whether the command
         * committed unknown, which the caller may send again with the same request id.
         */
        @JvmStatic
        fun timedOut(): PeerAnswer = PeerAnswer(
            null,
            0,
            FrameworkCodes.WAIT_TIMEOUT,
            "The call's commit was not seen within the wait timeout.",
            null,
        )
    }
}

/** The notices of the contracts on a test's class path, a peer's newer than this reader among them. */
private val contractsLogger = LoggerFactory.getLogger(FakePeer::class.java)
