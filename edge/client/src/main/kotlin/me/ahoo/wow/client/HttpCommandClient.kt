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

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.client.CommandClient
import me.ahoo.wow.api.client.CommandRejectedException
import me.ahoo.wow.api.client.SendOptions
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.decide.RequestFacts
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.client.wire.ScopeHeader
import me.ahoo.wow.client.wire.WaitHeader
import me.ahoo.wow.client.wire.WowHeaders
import me.ahoo.wow.kernel.CommandTypes
import me.ahoo.wow.manifest.CommandRoutes
import me.ahoo.wow.runtime.admission.CallContext
import me.ahoo.wow.runtime.admission.TargetScope
import me.ahoo.wow.spi.id.GlobalIdGenerator
import tools.jackson.core.JacksonException
import kotlin.time.Duration.Companion.seconds

/**
 * The [CommandClient] of the peers' HTTP command routes (decisions D257, D258, D259): each command goes, as JSON, to
 * the route its contract gives it (`POST /{context}/{aggregate}/{id}/{command}`, or without the id for a creation that
 * leaves it to the peer, decision D191), named by [names] from its class and its ref's type, on the port [transport]'s
 * mode calls, as [caller]:
 *
 * - in service mode, on the internal port with `Wow-Call`: the caller's tenant (the ref's, or the target's of
 *   [SendOptions.targetTenant]), scope (or [SendOptions.targetScope]), operator and application as the root context,
 *   a new command id its own root; the peer answers once the command is committed, so [send], [wait] and [stream]
 *   answer that one result, whatever the options' wait plan;
 * - in user mode, on the public routes with the forwarded credential: the wait plan as `Wow-Wait` and its timeout as
 *   `Wow-Wait-Timeout` ([send] waits for ACCEPTED), the target scope as `Wow-Scope`, and [stream] reads the results
 *   as server-sent events, one per hop.
 *
 * Every command carries its request id (`Wow-Request-Id`, the options' or a new one, kept by every retry) and its
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
    private val names: CommandTypes,
    private val ids: GlobalIdGenerator,
    private val caller: Caller = Caller.ANONYMOUS,
    /** The peers described by their Manifest alone, which take [NamedCommand]s only. */
    private val described: ManifestPeers? = null,
) : CommandClient {
    override fun of(caller: Caller): CommandClient = HttpCommandClient(transport, names, ids, caller, described)

    override suspend fun send(command: Any, ref: AggregateRef, options: SendOptions): CommandResult =
        settled(
            command,
            ref,
            exchange(command, ref, options, WaitPlan.ACCEPTED.within(options.timeout ?: ACCEPT_TIMEOUT))
        )

    override suspend fun wait(command: Any, ref: AggregateRef, options: SendOptions): CommandResult =
        settled(command, ref, exchange(command, ref, options, options.plan))

    override fun stream(command: Any, ref: AggregateRef, options: SendOptions): Flow<CommandResult> = flow {
        val request = request(command, ref, options, options.plan)
        if (transport.mode is ClientMode.Service) {
            emit(resultOf(command, ref, transport.exchange(request, PeerProblem::retriesCommand)))
            return@flow
        }
        when (val answer = transport.stream(request, PeerProblem::retriesCommand)) {
            is Answer.Ok -> answer.value.serverSentEvents().collect { event ->
                emit(ClientJson.mapper.readValue(event.data, CommandResult::class.java))
            }
            is Answer.Problem -> emit(answer.problem.result ?: throw answer.problem.exception(what(command, ref)))
        }
    }

    private suspend fun exchange(command: Any, ref: AggregateRef, options: SendOptions, plan: WaitPlan?) =
        transport.exchange(request(command, ref, options, plan), PeerProblem::retriesCommand)

    /** The result [answer] holds, of whatever status, or the exception of a problem that holds none. */
    private fun resultOf(command: Any, ref: AggregateRef, answer: Answer<ByteArray>): CommandResult = when (answer) {
        is Answer.Ok -> decode(answer.value)
        is Answer.Problem -> answer.problem.result ?: throw answer.problem.exception(what(command, ref))
    }

    /** The result [answer] holds when it succeeded; else the exception that says why it did not. */
    private fun settled(command: Any, ref: AggregateRef, answer: Answer<ByteArray>): CommandResult = when (answer) {
        is Answer.Ok -> decode(answer.value).also { result ->
            if (result.status == CommandResult.Status.REJECTED) throw CommandRejectedException(result)
        }
        is Answer.Problem -> throw answer.problem.exception(what(command, ref))
    }

    private suspend fun request(command: Any, ref: AggregateRef, options: SendOptions, plan: WaitPlan?): PeerRequest {
        val type = ref.type
        val name = nameOf(command, ref, options)
        val segment = CommandRoutes.segment(name.removePrefix("$type."))
        val path = if (ref.isAssigned) {
            "/${type.context}/${type.name}/${HttpTransport.encode(ref.id)}/$segment"
        } else {
            "/${type.context}/${type.name}/$segment"
        }
        val headers = buildMap {
            put(WowHeaders.REQUEST_ID, options.requestId ?: ids.next())
            options.expectedVersion?.let { put(WowHeaders.IF_MATCH, "\"$it\"") }
            if (transport.mode is ClientMode.User) {
                plan?.let { put(WowHeaders.WAIT, WaitHeader.format(it)) }
                // A plan's own timeout, or the options'; the peer's default timeout otherwise.
                plan?.timeout?.takeIf { options.timeout != null || it != WaitPlan.DEFAULT_TIMEOUT }
                    ?.let { put(WowHeaders.WAIT_TIMEOUT, WaitHeader.formatTimeout(it)) }
                if (options.targeted) {
                    put(
                        WowHeaders.SCOPE,
                        ScopeHeader.format(TargetScope(options.targetTenant, options.targetScope ?: caller.scope))
                    )
                }
            }
        }
        return PeerRequest(
            context = type.context,
            path = path,
            caller = caller,
            call = if (transport.mode is ClientMode.Service) rootOf(ref, options) else null,
            headers = headers,
            body = if (command is NamedCommand) ClientJson.write(command.body) else ClientJson.write(command),
            timeout = plan?.timeout?.let { it + RESPONSE_GRACE }?.takeIf { it > transport.timeout },
        )
    }

    /**
     * The full type name of [command] to [ref]: a [NamedCommand]'s, once the Manifest of its peer declares it; a
     * command object's, as its contract names it. A peer described by its Manifest takes named commands alone, and a
     * named command goes only to such a peer.
     */
    private suspend fun nameOf(command: Any, ref: AggregateRef, options: SendOptions): String {
        val type = ref.type
        val manifestPeer = described?.describes(type.context) == true
        if (command is NamedCommand) {
            require(manifestPeer) {
                "${type.context} is not described by its Manifest: a named command goes only to a context of " +
                    "wow.clients.contexts with contract: manifest (decision D257)."
            }
            val call = if (transport.mode is ClientMode.Service) rootOf(ref, options) else null
            return "$type.${described.commandOf(command, ref, caller, call).name}"
        }
        check(!manifestPeer) {
            "${type.context} is described by its Manifest alone (contract: manifest): send it a NamedCommand, not " +
                "the command object ${command.javaClass.name} (decision D257)."
        }
        return requireNotNull(names.nameOf(type, command.javaClass)) {
            "No contract names the command ${command.javaClass.name} of $type: depend on the api module of " +
                "${type.context}, whose jar carries its contract (decision D234)."
        }
    }

    /**
     * The root context a service-mode command asserts (decisions D193, D236 ③): a command of its own, in the ref's
     * tenant (or the target's) and the caller's scope (or the target's), sent by the caller's operator from its
     * application.
     */
    private fun rootOf(ref: AggregateRef, options: SendOptions): CallContext {
        val commandId = ids.next()
        return CallContext.of(
            commandId = commandId,
            tenantId = options.targetTenant ?: ref.tenantId,
            scope = options.targetScope ?: caller.scope,
            causation = Causation(commandId, commandId),
            operator = caller.toOperator(actingElsewhere = options.targeted),
            facts = caller.app?.let { mapOf(RequestFacts.APP_ID to it) }.orEmpty(),
        )
    }

    private fun what(command: Any, ref: AggregateRef): String =
        "The command ${if (command is NamedCommand) command.name else command.javaClass.simpleName} to $ref"

    private companion object {
        /** How long [send] waits for the peer to accept a command whose options name no timeout. */
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
