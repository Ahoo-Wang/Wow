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
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.takeWhile
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
import me.ahoo.wow.api.decide.Misuse
import me.ahoo.wow.api.decide.RequestFacts
import me.ahoo.wow.api.decide.checkUse
import me.ahoo.wow.api.decide.requireUse
import me.ahoo.wow.api.problem.RetryAdvice
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
import me.ahoo.wow.spi.SystemCommandGroups
import me.ahoo.wow.spi.call.requestIdOf
import me.ahoo.wow.spi.id.GlobalIdGenerator
import tools.jackson.core.JacksonException
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

/**
 * The [CommandClient] of the peers' HTTP command routes: each command goes, as JSON, to
 * the route its contract gives it (`POST /{context}/{aggregate}/{id}/{command}`, or without the id for a request
 * that names none: a creation, an unaddressed command, or any command of an aggregate whose id is its owner's, which
 * goes to the caller's own; and for a command whose body names its aggregate, whose body names the request's, as
 * [CommandBodies] writes it), named by [names] from its class and the request's type (the
 * one whose contract lists the class when the request leaves it out), on the port [transport]'s mode calls, as
 * [caller]:
 *
 * - in service mode, on the internal port with `Wow-Call`: the caller's tenant (or the tenant of
 *   [CommandRequest.targetScope]), scope (or its scope, when it names one), operator and application as the root
 *   context, a new command id its own root; the peer answers once the command is committed, so [send], [wait] and
 *   [stream] answer that one result, whatever the request's wait plan;
 * - in user mode, on the public routes with the forwarded credential: the wait plan as `Wow-Wait` ([send] waits for
 *   ACCEPTED), the target scope as `Wow-Scope`, and [stream] reads the results as server-sent events, one per hop.
 *
 * In either mode the plan's timeout goes as `Wow-Wait-Timeout`, and the request waits for its answer that long and
 * [RESPONSE_GRACE] more, whatever the transport's timeout (`wow.peer.timeout`), which bounds what has no wait: the
 * peer answers 504 at the wait's end, so a peer that has not answered by then never will, and a request sent on a
 * connection to a node that is gone without closing it ends then instead of at the transport's timeout (#6517).
 *
 * Every command carries its request id (`Wow-Request-Id`, the request's or a new one, kept by every retry) and its
 * expected version (`If-Match`). A problem the peer answers is a [CommandRejectedException] when the command was
 * rejected, a [me.ahoo.wow.api.problem.WowProblemException] otherwise, with the retry of its code; one a retry with the
 * same request id may get past is retried first, as the transport's [RetryPolicy] says (never a 504). A connection that
 * cannot be made is `wow.peer.unavailable` once the retries are spent, one lost before the answer `wow.command.unknown`;
 * so is a user-mode [stream] whose connection is lost after its head, that passes its bound, whose event holds no
 * result, or that ends, however cleanly, without the `end` event its peer writes after the last result once the stream
 * is whole (#6808): the client reads results until `end` and emits results alone, never inferring the end from the
 * plan. A context the endpoints give no URL is an [IllegalStateException].
 *
 * A peer described by its Manifest alone ([WowClient.Builder.manifestPeers]) takes [NamedCommand]s, named by their
 * type and checked against its Manifest before any request; a command object to it is a configuration error.
 */
internal class HttpCommandClient(
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
            is Answer.Ok -> {
                // Whether the stream said it is whole: its `end` event, after its last result.
                var whole = false
                answer.value.serverSentEvents()
                    .takeWhile { event -> (event.event != END_EVENT).also { whole = !it } }
                    .map { event -> decode(peer.what, event.data.toByteArray()) }
                    // Upstream of the collector: only the connection's failures, its bound's included.
                    .catch { lost -> throw if (lost is IOException) unknown(peer, lost).exception(peer.what) else lost }
                    .collect { emit(it) }
                if (!whole) {
                    throw unknown(peer, IOException("The stream ended without its end event.")).exception(peer.what)
                }
            }
            is Answer.Problem -> emit(answer.problem.result ?: throw answer.problem.exception(peer.what))
        }
    }

    /**
     * The problem of a command whose stream of results failed after its head ([lost]), or ended without its `end`
     * event: it was taken, and whether it committed is unknown, as a command whose answer was lost is
     * (`wow.command.unknown`, a retry with its request id is safe). A caller sees one kind of failure, whatever met it.
     */
    private fun unknown(peer: Sent, lost: IOException): PeerProblem = PeerProblem.of(
        FrameworkCodes.COMMAND_UNKNOWN,
        "The stream of results of the command sent to ${peer.http.context} was lost (${lost.javaClass.name}): " +
            "whether it committed is unknown.",
        lost,
    )

    /** The result [answer] holds, of whatever status, or the exception of a problem that holds none. */
    private fun resultOf(peer: Sent, answer: Answer<ByteArray>): CommandResult = when (answer) {
        is Answer.Ok -> decode(peer.what, answer.value)
        is Answer.Problem -> answer.problem.result ?: throw answer.problem.exception(peer.what)
    }

    /**
     * The result [answer] holds when it succeeded; else the exception that says why it did not. A status this client
     * does not know, a newer peer's, is not known to have succeeded: a problem that says the peer is newer, not
     * retried, since sending again does not teach this client the status.
     */
    private fun settled(peer: Sent, answer: Answer<ByteArray>): CommandResult = when (answer) {
        is Answer.Ok -> decode(peer.what, answer.value).also { result ->
            when (result.status) {
                CommandResult.Status.REJECTED -> throw CommandRejectedException(result)
                CommandResult.Status.UNKNOWN -> throw WowProblemException(
                    FrameworkCodes.COMMAND_FAILED,
                    NEWER_PEER_STATUS,
                    RetryAdvice.NONE,
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
        val targeted = targetTenant != null || request.targetScope?.scope != null
        val root = if (transport.mode is ClientMode.Service) {
            rootOf(
                targetTenant ?: caller.tenantId,
                request.targetScope?.scopeFor(caller.scope) ?: caller.scope,
                targeted
            )
        } else {
            null
        }
        val id = request.aggregateId
        val declared = declaredOf(command, type, root)
        // A command whose body names its aggregate goes to its route without an id, the body naming the request's.
        val byId = id != null && declared.idProperty == null
        val route = routeOf(declared.name, byId, declared.routes(byId))
        val headers = headersOf(request, plan, targetTenant, targeted)
        val body = if (command is NamedCommand) command.body else command
        val http = PeerRequest(
            context = type.context,
            path = CommandRoutes.pathOf(route, id?.takeIf { byId }?.let(HttpTransport::encode)),
            method = route.method,
            caller = caller,
            call = root,
            headers = headers,
            body = CommandBodies.of(declared.name, body, declared.idProperty, id),
            // A command with a wait is answered within it: the peer answers 504 at its end, so the request waits no
            // longer, whatever the transport's timeout. wow.peer.timeout bounds what has no wait.
            timeout = plan?.timeout?.let { it + RESPONSE_GRACE },
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
        put(WowHeaders.REQUEST_ID, currentCoroutineContext().requestIdOf(request) ?: ids.next())
        request.expectedVersion?.let { put(WowHeaders.IF_MATCH, "\"$it\"") }
        // The wait the request is bounded by, stated so the peer answers within it, on either port.
        plan?.timeout?.let { put(WowHeaders.WAIT_TIMEOUT, WaitHeader.formatTimeout(it)) }
        if (transport.mode is ClientMode.User) {
            // The default plan sends no wait: the service picks it, the snapshot or the commit where there is none.
            plan?.takeUnless { it.isDefault }?.let { put(WowHeaders.WAIT, WaitHeader.format(it)) }
            if (targeted) {
                put(
                    WowHeaders.SCOPE,
                    ScopeHeader.format(TargetScope(targetTenant, request.targetScope?.scope), caller.scope)
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
        val peers = described ?: throw Misuse.Argument(
            "$context is not described by its Manifest: list it in wow.peers with contract: manifest.",
        )
        val call = if (transport.mode is ClientMode.Service) rootOf(caller.tenantId, caller.scope, false) else null
        return peers.manifestOf(context, caller, call)
    }

    /**
     * The command [name], the property its body names its aggregate in ([idProperty], null for none), and its route
     * [routes] gives, to an aggregate by its id or without one, as its contract lists them.
     */
    private class Declared(val name: String, val idProperty: String?, val routes: (byId: Boolean) -> RouteManifest?)

    /**
     * [command] to [type] as its contract declares it: a [NamedCommand], once the Manifest of its peer declares it; a
     * command object, as the contract on the class path names it. A peer described by its Manifest takes named commands
     * alone, and a named command goes only to such a peer.
     */
    private suspend fun declaredOf(command: Any, type: AggregateType, call: CallWire?): Declared {
        val manifestPeer = described?.describes(type.context) == true
        if (command is NamedCommand) {
            requireUse(manifestPeer) {
                "${type.context} is not described by its Manifest: a named command goes only to a context of " +
                    "wow.peers with contract: manifest."
            }
            val declared = described.commandOf(command, type, caller, call)
            val name = "$type.${declared.name}"
            requireSendable(name, declared.internal)
            return Declared(name, declared.idProperty) { CommandRoutes.routeOf(declared.routes, it) }
        }
        checkUse(!manifestPeer) {
            "${type.context} is described by its Manifest alone (contract: manifest): send it a NamedCommand, not " +
                "the command object ${command.javaClass.name}."
        }
        val name = names.commandTypeOf(type.toString(), command.javaClass)
            // A system command by the system name a contract lists it under: its lifecycle, its rebuild, a job's.
            ?: SystemCommandGroups.LISTED_BY_A_CONTRACT.nameOf(command.javaClass)
                ?.let { names.systemCommandTypeOf(type.toString(), it) }
            ?: throw Misuse.Argument(
                "No contract names the command ${command.javaClass.name} of $type: depend on the api module of " +
                    "${type.context}, whose jar carries its contract, or send a NamedCommand of the full type " +
                    "name to a peer described by its Manifest.",
            )
        requireSendable(name, names.isInternal(name))
        return Declared(name, names.idPropertyOf(name)) { names.routeOf(name, it) }
    }

    /**
     * Refuses an [internal] command [name] in user mode, before it is sent: the peer takes it on its internal port
     * alone, from a service, and its public routes do not serve it.
     */
    private fun requireSendable(name: String, internal: Boolean) {
        requireUse(!internal || transport.mode !is ClientMode.User) {
            "$name is an internal command, which its service takes on its internal port alone: a user-mode client " +
                "does not send it; a service sends it in service mode."
        }
    }

    /** [route], the route of the command [name], or why its contract lists none. */
    private fun routeOf(name: String, byId: Boolean, route: RouteManifest?): RouteManifest =
        route ?: throw Misuse.Argument(
            if (byId) {
                "The contract of $name lists no route to an aggregate by its id: send it without an id."
            } else {
                "The contract of $name lists no route without an aggregate id: send it to an aggregate by its id."
            },
        )

    /**
     * The root context a service-mode command asserts: a command of its own, in [tenantId] (the caller's, or the
     * target's) and [scope] (the caller's, or the target's), sent by the caller's operator from its application.
     */
    private fun rootOf(tenantId: String, scope: Scope, actedElsewhere: Boolean): CallWire {
        val commandId = ids.next()
        return CallWire(
            commandId = commandId,
            tenantId = tenantId,
            ownerId = scope.ownerId,
            spaceId = scope.spaceId,
            causation = Causation(commandId, commandId),
            operator = caller.toOperator(actedElsewhere = actedElsewhere),
            facts = caller.app?.let { mapOf(RequestFacts.APP_ID to it) }.orEmpty(),
        )
    }

    private companion object {
        /** The event a command's stream ends with once whole, after its last result; never a result. */
        const val END_EVENT = "end"

        /** How long [send] waits for the peer to accept a command whose request names no timeout. */
        val ACCEPT_TIMEOUT = 10.seconds

        /** What a response may take beyond its wait's timeout, which the peer answers at (504). */
        val RESPONSE_GRACE = 5.seconds

        /** The status of the problem of a result whose status this client does not know: a bad gateway, as a call's. */
        private const val NEWER_PEER_STATUS = 502

        /**
         * The result [body] holds, the answer to [what]. An answer that holds none is the wire's or the peer's fault, as
         * an answer that never came is: `wow.command.unknown`, whether the command committed unknown, which the same
         * request id may be sent again for.
         */
        fun decode(what: String, body: ByteArray): CommandResult = try {
            CommandResults.read(body)
        } catch (invalid: JacksonException) {
            throw PeerProblem.of(
                FrameworkCodes.COMMAND_UNKNOWN,
                "The answer holds no result (${invalid.originalMessage}): whether the command committed is unknown.",
                invalid,
            ).exception(what)
        }
    }
}
