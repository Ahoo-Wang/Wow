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

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.caller.TargetScope
import me.ahoo.wow.api.client.CommandClient
import me.ahoo.wow.api.client.CommandRejectedException
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.client.targetTenantOf
import me.ahoo.wow.api.client.typeOf
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.decide.RequestFacts
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.api.wire.CallWire
import me.ahoo.wow.api.wire.ScopeHeader
import me.ahoo.wow.api.wire.WaitHeader
import me.ahoo.wow.api.wire.WowHeaders
import me.ahoo.wow.manifest.CommandIndex
import me.ahoo.wow.manifest.CommandRoutes
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.spi.call.requestIdOf
import me.ahoo.wow.spi.id.GlobalIdGenerator
import tools.jackson.core.JacksonException
import kotlin.time.Duration.Companion.seconds

/**
 * The [CommandClient] of the peers' HTTP command routes: each command goes, as JSON, to
 * the route its contract gives it (`POST /{context}/{aggregate}/{id}/{command}`, or without the id for a request
 * that names none, a creation or an unaddressed command), named by [names] from its class and the request's type (the
 * one whose contract lists the class when the request leaves it out), on the port [transport]'s mode calls, as
 * [caller]:
 *
 * - in service mode, on the internal port with `Wow-Call`: the caller's tenant (or the target's of
 *   [CommandRequest.targetTenant]), scope (or [CommandRequest.targetScope]), operator and application as the root
 *   context, a new command id its own root; the peer answers once the command is committed, so [send], [wait] and
 *   [stream] answer that one result, whatever the request's wait plan;
 * - in user mode, on the public routes with the forwarded credential: the wait plan as `Wow-Wait` and its timeout as
 *   `Wow-Wait-Timeout` ([send] waits for ACCEPTED), the target scope as `Wow-Scope`, and [stream] reads the results
 *   as server-sent events, one per hop.
 *
 * Every command carries its request id (`Wow-Request-Id`, the request's or a new one, kept by every retry) and its
 * expected version (`If-Match`). A problem the peer answers is a [CommandRejectedException] when the command was
 * rejected, a [me.ahoo.wow.api.client.WowProblemException] otherwise, with the hint of its code; one a retry may get
 * past is retried first, as the transport's [RetryPolicy] says (never a 504). A connection that cannot be made is its
 * [java.io.IOException] once the retries are spent; a context the endpoints give no URL an [IllegalStateException].
 *
 * A peer described by its Manifest alone ([WowClient.Builder.manifestPeers]) takes [NamedCommand]s, named by their
 * type and checked against its Manifest before any request; a command object to it is a configuration error.
 */
class HttpCommandClient internal constructor(
    private val transport: HttpTransport,
    private val names: CommandIndex,
    private val ids: GlobalIdGenerator,
    private val caller: Caller = Caller.ANONYMOUS,
    /** The peers described by their Manifest alone, which take [NamedCommand]s only. */
    private val described: ManifestPeers? = null,
) : CommandClient {
    override fun of(caller: Caller): CommandClient = HttpCommandClient(transport, names, ids, caller, described)

    override suspend fun send(request: CommandRequest): CommandResult {
        val peer = peerRequestOf(request, WaitPlan.ACCEPTED.within(request.timeout ?: ACCEPT_TIMEOUT))
        return settled(peer, transport.exchange(peer.http, PeerProblem::retriesCommand))
    }

    override suspend fun wait(request: CommandRequest): CommandResult {
        val peer = peerRequestOf(request, request.plan)
        return settled(peer, transport.exchange(peer.http, PeerProblem::retriesCommand))
    }

    override fun stream(request: CommandRequest): Flow<CommandResult> = flow {
        val peer = peerRequestOf(request, request.plan)
        if (transport.mode is ClientMode.Service) {
            emit(resultOf(peer, transport.exchange(peer.http, PeerProblem::retriesCommand)))
            return@flow
        }
        when (val answer = transport.stream(peer.http, PeerProblem::retriesCommand)) {
            is Answer.Ok -> answer.value.serverSentEvents().collect { event ->
                emit(ClientJson.mapper.readValue(event.data, CommandResult::class.java))
            }
            is Answer.Problem -> emit(answer.problem.result ?: throw answer.problem.exception(peer.what))
        }
    }

    /** The result [answer] holds, of whatever status, or the exception of a problem that holds none. */
    private fun resultOf(peer: Sent, answer: Answer<ByteArray>): CommandResult = when (answer) {
        is Answer.Ok -> decode(answer.value)
        is Answer.Problem -> answer.problem.result ?: throw answer.problem.exception(peer.what)
    }

    /** The result [answer] holds when it succeeded; else the exception that says why it did not. */
    private fun settled(peer: Sent, answer: Answer<ByteArray>): CommandResult = when (answer) {
        is Answer.Ok -> decode(answer.value).also { result ->
            if (result.status == CommandResult.Status.REJECTED) throw CommandRejectedException(result)
        }
        is Answer.Problem -> throw answer.problem.exception(peer.what)
    }

    /** The HTTP request of a command, with what a problem names it by. */
    private class Sent(val http: PeerRequest, val what: String)

    private suspend fun peerRequestOf(request: CommandRequest, plan: WaitPlan?): Sent {
        val command = request.command
        val type = aggregateTypeOf(request)
        val targetTenant = targetTenantOf(request, caller.tenantId)
        val targeted = targetTenant != null || request.targetScope != null
        val root = if (transport.mode is ClientMode.Service) {
            rootOf(targetTenant ?: caller.tenantId, request.targetScope ?: caller.scope, targeted)
        } else {
            null
        }
        val name = nameOf(command, type, root)
        val segment = CommandRoutes.segment(name.removePrefix("$type."))
        val id = request.id
        val path = if (id != null) {
            "/${type.context}/${type.name}/${HttpTransport.encode(id)}/$segment"
        } else {
            "/${type.context}/${type.name}/$segment"
        }
        val headers = headersOf(request, plan, targetTenant, targeted)
        val http = PeerRequest(
            context = type.context,
            path = path,
            caller = caller,
            call = root,
            headers = headers,
            body = if (command is NamedCommand) ClientJson.write(command.body) else ClientJson.write(command),
            timeout = plan?.timeout?.let { it + RESPONSE_GRACE }?.takeIf { it > transport.timeoutOf(type.context) },
        )
        val what = if (command is NamedCommand) command.name else command.javaClass.simpleName
        return Sent(http, "The command $what to $type${id?.let { "/$it" }.orEmpty()}")
    }

    /**
     * The headers of [request] waiting with [plan]: its request id (derived from the command being decided when sent
     * from a decide function, else a new one) and expected version; in user mode its wait and the scope it targets.
     */
    private suspend fun headersOf(
        request: CommandRequest,
        plan: WaitPlan?,
        targetTenant: String?,
        targeted: Boolean,
    ): Map<String, String> = buildMap {
        put(WowHeaders.REQUEST_ID, currentCoroutineContext().requestIdOf(request.requestId) ?: ids.next())
        request.expectedVersion?.let { put(WowHeaders.IF_MATCH, "\"$it\"") }
        if (transport.mode is ClientMode.User) {
            plan?.let { put(WowHeaders.WAIT, WaitHeader.format(it)) }
            // A plan's own timeout, or the request's; the peer's default timeout otherwise.
            plan?.timeout?.takeIf { request.timeout != null || it != WaitPlan.DEFAULT_TIMEOUT }
                ?.let { put(WowHeaders.WAIT_TIMEOUT, WaitHeader.formatTimeout(it)) }
            if (targeted) {
                put(
                    WowHeaders.SCOPE,
                    ScopeHeader.format(TargetScope(targetTenant, request.targetScope ?: caller.scope))
                )
            }
        }
    }

    /**
     * The aggregate type [request] goes to: its own; a [NamedCommand]'s, which its full type name gives; else the one
     * whose contract lists its command's class.
     */
    private fun aggregateTypeOf(request: CommandRequest): AggregateType {
        val command = request.command
        if (request.type == null && command is NamedCommand) {
            return aggregateTypeOf(command.target)
        }
        return typeOf(request) { names.aggregatesOf(Manifest.schemaRef(it.name)).map(::aggregateTypeOf) }
    }

    /** The aggregate type `context.aggregate` names. */
    private fun aggregateTypeOf(name: String) = AggregateType(name.substringBefore('.'), name.substringAfter('.'))

    /** The Manifest of [context], a peer described by its Manifest alone, read as this client's caller. */
    internal suspend fun manifestOf(context: String): Manifest {
        val peers = requireNotNull(described) {
            "$context is not described by its Manifest: list it in wow.peers with contract: manifest."
        }
        val call = if (transport.mode is ClientMode.Service) rootOf(caller.tenantId, caller.scope, false) else null
        return peers.manifestOf(context, caller, call)
    }

    /**
     * The full type name of [command] to [type]: a [NamedCommand]'s, once the Manifest of its peer declares it; a
     * command object's, as its contract names it. A peer described by its Manifest takes named commands alone, and a
     * named command goes only to such a peer.
     */
    private suspend fun nameOf(command: Any, type: AggregateType, call: CallWire?): String {
        val manifestPeer = described?.describes(type.context) == true
        if (command is NamedCommand) {
            require(manifestPeer) {
                "${type.context} is not described by its Manifest: a named command goes only to a context of " +
                    "wow.peers with contract: manifest."
            }
            return "$type.${described.commandOf(command, type, caller, call).name}"
        }
        check(!manifestPeer) {
            "${type.context} is described by its Manifest alone (contract: manifest): send it a NamedCommand, not " +
                "the command object ${command.javaClass.name}."
        }
        return requireNotNull(names.commandTypeOf(type.toString(), command.javaClass)) {
            "No contract names the command ${command.javaClass.name} of $type: depend on the api module of " +
                "${type.context}, whose jar carries its contract, or send a NamedCommand of the full type name to a " +
                "peer described by its Manifest."
        }
    }

    /**
     * The root context a service-mode command asserts: a command of its own, in [tenantId] (the caller's, or the
     * target's) and [scope] (the caller's, or the target's), sent by the caller's operator from its application.
     */
    private fun rootOf(tenantId: String, scope: Scope, actingElsewhere: Boolean): CallWire {
        val commandId = ids.next()
        return CallWire(
            commandId = commandId,
            tenantId = tenantId,
            ownerId = scope.ownerId,
            spaceId = scope.spaceId,
            causation = Causation(commandId, commandId),
            operator = caller.toOperator(actingElsewhere = actingElsewhere),
            facts = caller.app?.let { mapOf(RequestFacts.APP_ID to it) }.orEmpty(),
        )
    }

    private companion object {
        /** How long [send] waits for the peer to accept a command whose request names no timeout. */
        val ACCEPT_TIMEOUT = 10.seconds

        /** What a response may take beyond its wait's timeout, which the peer answers at (504). */
        val RESPONSE_GRACE = 5.seconds

        fun decode(body: ByteArray): CommandResult = try {
            ClientJson.mapper.readValue(body, CommandResult::class.java)
        } catch (invalid: JacksonException) {
            throw IllegalStateException(
                "The peer answered a command with no result: ${invalid.originalMessage}",
                invalid
            )
        }
    }
}
