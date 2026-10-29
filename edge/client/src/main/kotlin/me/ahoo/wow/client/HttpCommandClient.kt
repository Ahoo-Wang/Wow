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
import me.ahoo.wow.api.client.sendable
import me.ahoo.wow.api.client.targetTenantOf
import me.ahoo.wow.api.client.typeOf
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.decide.RequestFacts
import me.ahoo.wow.api.problem.Retry
import me.ahoo.wow.api.problem.WowProblemException
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.api.wire.CallWire
import me.ahoo.wow.api.wire.ScopeHeader
import me.ahoo.wow.api.wire.WaitHeader
import me.ahoo.wow.api.wire.WowHeaders
import me.ahoo.wow.manifest.CommandIndex
import me.ahoo.wow.manifest.CommandRoutes
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.RouteManifest
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
 * rejected, a [me.ahoo.wow.api.problem.WowProblemException] otherwise, with the retry of its code; one a retry with the
 * same request id may get past is retried first, as the transport's [RetryPolicy] says (never a 504). A connection that
 * cannot be made is `wow.peer.unavailable` once the retries are spent, one lost before the answer `wow.command.unknown`;
 * a context the endpoints give no URL is an [IllegalStateException].
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

    override suspend fun send(request: CommandRequest<*>): CommandResult {
        val peer = peerRequestOf(sendable(request), WaitPlan.ACCEPTED.within(request.timeout ?: ACCEPT_TIMEOUT))
        return settled(peer, transport.exchange(peer.http, Exchange.COMMAND))
    }

    override suspend fun wait(request: CommandRequest<*>): CommandResult {
        val peer = peerRequestOf(request, request.plan)
        return settled(peer, transport.exchange(peer.http, Exchange.COMMAND))
    }

    override fun stream(request: CommandRequest<*>): Flow<CommandResult> = flow {
        val peer = peerRequestOf(request, request.plan)
        if (transport.mode is ClientMode.Service) {
            emit(resultOf(peer, transport.exchange(peer.http, Exchange.COMMAND)))
            return@flow
        }
        when (val answer = transport.stream(peer.http, Exchange.COMMAND)) {
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

    /**
     * The result [answer] holds when it succeeded; else the exception that says why it did not. A status this client
     * does not know, a newer peer's, is not known to have succeeded: a problem that says the peer is newer, not
     * retried, since sending again does not teach this client the status.
     */
    private fun settled(peer: Sent, answer: Answer<ByteArray>): CommandResult = when (answer) {
        is Answer.Ok -> decode(answer.value).also { result ->
            when (result.status) {
                CommandResult.Status.REJECTED -> throw CommandRejectedException(result)
                CommandResult.Status.UNKNOWN -> throw WowProblemException(
                    FrameworkCodes.COMMAND_FAILED,
                    NEWER_PEER_STATUS,
                    Retry.NONE,
                    null,
                    "${peer.what} was answered with a status this client does not know: the peer is newer than " +
                        "this client, so upgrade it.",
                    result,
                )
                else -> Unit
            }
        }
        is Answer.Problem -> throw answer.problem.exception(peer.what)
    }

    /** The HTTP request of a command, with what a problem names it by. */
    private class Sent(val http: PeerRequest, val what: String)

    private suspend fun peerRequestOf(request: CommandRequest<*>, plan: WaitPlan?): Sent {
        val command = request.command
        val type = aggregateTypeOf(request)
        val targetTenant = targetTenantOf(request, caller.tenantId)
        val targeted = targetTenant != null || request.targetScope != null
        val root = if (transport.mode is ClientMode.Service) {
            rootOf(targetTenant ?: caller.tenantId, request.targetScope ?: caller.scope, targeted)
        } else {
            null
        }
        val id = request.id
        val route = routeOf(command, type, id != null, root)
        val headers = headersOf(request, plan, targetTenant, targeted)
        val http = PeerRequest(
            context = type.context,
            path = CommandRoutes.pathOf(route, id?.let(HttpTransport::encode)),
            method = route.method,
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
        request: CommandRequest<*>,
        plan: WaitPlan?,
        targetTenant: String?,
        targeted: Boolean,
    ): Map<String, String> = buildMap {
        put(WowHeaders.REQUEST_ID, currentCoroutineContext().requestIdOf(request.requestId) ?: ids.next())
        request.expectedVersion?.let { put(WowHeaders.IF_MATCH, "\"$it\"") }
        if (transport.mode is ClientMode.User) {
            // The default plan sends no wait: the service picks it, the snapshot or the commit where there is none.
            plan?.takeUnless { it.isDefault }?.let { put(WowHeaders.WAIT, WaitHeader.format(it)) }
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
    private fun aggregateTypeOf(request: CommandRequest<*>): AggregateType {
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
     * The route of [command] to [type], to an aggregate by its id when [byId], as its contract lists it: a
     * [NamedCommand]'s, once the Manifest of its peer declares it; a command object's, as the contract on the class path
     * names it. A peer described by its Manifest takes named commands alone, and a named command goes only to such a
     * peer.
     */
    private suspend fun routeOf(command: Any, type: AggregateType, byId: Boolean, call: CallWire?): RouteManifest {
        val manifestPeer = described?.describes(type.context) == true
        if (command is NamedCommand) {
            require(manifestPeer) {
                "${type.context} is not described by its Manifest: a named command goes only to a context of " +
                    "wow.peers with contract: manifest."
            }
            val declared = described.commandOf(command, type, caller, call)
            return routeOf("$type.${declared.name}", byId, CommandRoutes.routeOf(declared.routes, byId))
        }
        check(!manifestPeer) {
            "${type.context} is described by its Manifest alone (contract: manifest): send it a NamedCommand, not " +
                "the command object ${command.javaClass.name}."
        }
        val name = requireNotNull(names.commandTypeOf(type.toString(), command.javaClass)) {
            "No contract names the command ${command.javaClass.name} of $type: depend on the api module of " +
                "${type.context}, whose jar carries its contract, or send a NamedCommand of the full type name to a " +
                "peer described by its Manifest."
        }
        return routeOf(name, byId, names.routeOf(name, byId))
    }

    /** [route], the route of the command [name], or why its contract lists none. */
    private fun routeOf(name: String, byId: Boolean, route: RouteManifest?): RouteManifest = requireNotNull(route) {
        if (byId) {
            "The contract of $name lists no route to an aggregate by its id: send it without an id."
        } else {
            "The contract of $name lists no route without an aggregate id: send it to an aggregate by its id."
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

        /** The status of the problem of a result whose status this client does not know: a bad gateway, as a call's. */
        private const val NEWER_PEER_STATUS = 502

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
