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
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.ExperimentalWowApi
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.client.CommandClient
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.client.typeOf
import me.ahoo.wow.api.read.DatasetName
import me.ahoo.wow.api.read.EventQueries
import me.ahoo.wow.api.read.NodeQueryApi
import me.ahoo.wow.api.read.Queries
import me.ahoo.wow.api.read.RowQueries
import me.ahoo.wow.api.read.SubscriptionQueries
import me.ahoo.wow.api.read.TenantQueryApi
import me.ahoo.wow.api.read.TenantSnapshotQueries
import me.ahoo.wow.api.read.TrustedQueries
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.spi.config.configCheck
import tools.jackson.databind.node.ObjectNode

/**
 * How a peer context is reached over HTTP: [SERVICE], on its internal port as this service,
 * asserting the caller as the root of the call ([ClientMode.Service]); [USER], on its public routes, forwarding the end
 * user's credential ([ClientMode.User]).
 */
enum class PeerMode {
    SERVICE,
    USER,
}

/**
 * The peer contexts the service reaches over HTTP (routing moved here from the host):
 * each context of [modes] by the client of its mode, [service] or [user]. Every context of [modes] must be a peer the contracts on the class path declare
 * ([contracts]), not one of the service's own, unless it is one of [described], the peers described by
 * their Manifest alone (`contract: manifest`), which no contract on the class path may declare.
 */
@InternalWowApi
class PeerClients(
    private val modes: Map<String, PeerMode>,
    /** The contexts this service serves, which are never peers. */
    local: Set<String>,
    /** The contexts the peer contracts on the class path declare. */
    contracts: Set<String>,
    private val service: Lazy<WowClient>,
    private val user: Lazy<WowClient>,
    /** The mode of [client], the one the application uses directly. */
    private val defaultMode: PeerMode,
    /**
     * The service's own name, its Manifest's: the identity a subscription handler reads a peer as
     * ([RoutedQueryApi.tenant]).
     */
    val serviceName: String,
    /** The contexts of [modes] described by their Manifest alone. */
    val described: Set<String> = emptySet(),
) {
    init {
        val unknown = modes.keys.filterNot { it in described || it in contracts && it !in local }
        configCheck(
            unknown.isEmpty(),
            "wow.peers",
            unknown.joinToString(),
            "Depend on each one's api module, whose jar carries its contract, or say contract: manifest to call it by " +
                "its Manifest alone; a context of this service is never called over HTTP.",
        ) { "wow.peers lists $unknown, which no peer contract on the class path declares." }
        val stray = described - modes.keys
        configCheck(stray.isEmpty(), "wow.peers", null, "List each of them in wow.peers.") {
            "The peers described by their Manifest, $stray, are not in wow.peers."
        }
        val declared = described.filter { it in contracts || it in local }
        configCheck(
            declared.isEmpty(),
            "wow.peers",
            declared.joinToString(),
            "Call each by its command objects, without contract: manifest.",
        ) {
            "wow.peers says contract: manifest of $declared, whose contract is on the class path (or which " +
                "this service serves)."
        }
    }

    /** The peer contexts reached over HTTP. */
    val contexts: Set<String> get() = modes.keys

    /** The client in [defaultMode]. */
    val client: WowClient get() = clientOf(defaultMode)

    /** Whether [context] is reached over HTTP. */
    fun reaches(context: String): Boolean = context in modes

    /** The client of [context]'s mode; the default one for a context not reached over HTTP. */
    fun clientOf(context: String): WowClient = clientOf(modes[context] ?: defaultMode)

    private fun clientOf(mode: PeerMode): WowClient = when (mode) {
        PeerMode.SERVICE -> service.value
        PeerMode.USER -> user.value
    }
}

/**
 * The service's [CommandClient]: a command to an aggregate of a peer context [peers] reach
 * goes over HTTP, any other through [local], the node's gateway (in process, or a peer's command topic). A request
 * without a type goes to the one [aggregatesOf] (the node's models and the contracts on the class path) gives its
 * command's class, a [NamedCommand]'s to the one its type names.
 */
@InternalWowApi
class RoutedCommandClient(
    private val local: CommandClient,
    private val peers: PeerClients,
    /** The aggregate types that take a command class: the node's own and the peers' its contracts list. */
    private val aggregatesOf: (Class<*>) -> List<AggregateType>,
    private val caller: Caller = Caller.ANONYMOUS,
) : CommandClient {
    override fun of(caller: Caller): CommandClient = RoutedCommandClient(local, peers, aggregatesOf, caller)

    override suspend fun send(request: CommandRequest<*>): CommandResult = typed(request).let { clientOf(it).send(it) }

    override suspend fun wait(request: CommandRequest<*>): CommandResult = typed(request).let { clientOf(it).wait(it) }

    override fun stream(request: CommandRequest<*>): Flow<CommandResult> = typed(
        request
    ).let { clientOf(it).stream(it) }

    /** [request] with its type, derived when it leaves it out. */
    private fun typed(request: CommandRequest<*>): CommandRequest<*> {
        if (request.type != null) return request
        val command = request.command
        val type = if (command is NamedCommand) {
            AggregateType(command.target.substringBefore('.'), command.target.substringAfter('.'))
        } else {
            typeOf(request, aggregatesOf)
        }
        return request.toBuilder().type(type).build()
    }

    private fun clientOf(request: CommandRequest<*>): CommandClient {
        val context = checkNotNull(request.type).context
        require(request.command !is NamedCommand || context in peers.described) {
            "$context is not described by its Manifest: a named command goes only to a context of wow.peers " +
                "with contract: manifest."
        }
        return (if (peers.reaches(context)) peers.clientOf(context).commands else local).of(caller)
    }
}

/**
 * The service's [NodeQueryApi]: the models of a peer context [peers] reach over HTTP, any other
 * in process through [local]; a state class of none of the node's models ([localStates]) is a peer's, found by its
 * contract. Trusted queries are in process alone: a peer reads for a caller.
 */
@InternalWowApi
class RoutedQueryApi(
    private val local: NodeQueryApi,
    private val peers: PeerClients,
    private val localStates: Set<Class<*>>,
) : TenantQueryApi {
    override fun of(caller: Caller): Queries = Routed(local.of(caller), caller)

    override fun trusted(): TrustedQueries = local.trusted()

    /**
     * The queries of a subscription handler of a commit of [tenantId]: the local API's, which pins the tenant
     * ([TenantQueryApi.tenant]), a peer's as this service's identity in that tenant ([Caller.ofService]): the handler
     * reads for a commit of the service's own, not for whoever sent it.
     */
    override fun tenant(tenantId: String): SubscriptionQueries {
        val pinned = checkNotNull(local as? TenantQueryApi) { "The local queries pin no tenant." }.tenant(tenantId)
        val service = Caller.ofService(peers.serviceName, tenantId)
        return object : SubscriptionQueries, Queries by Routed(pinned, service) {
            override val tenantId: String = tenantId

            override fun trusted(): TrustedQueries = pinned.trusted()
        }
    }

    private inner class Routed(private val local: Queries, private val caller: Caller) : Queries {
        override fun <S : Any> snapshots(stateType: Class<S>): TenantSnapshotQueries<S> {
            if (stateType in localStates || peers.contexts.isEmpty()) return local.snapshots(stateType)
            val type = peers.client.queries.of(caller).snapshots(stateType).type
            return peerOf(type.context)?.snapshots(stateType) ?: local.snapshots(stateType)
        }

        override fun snapshots(type: AggregateType): TenantSnapshotQueries<ObjectNode> =
            peerOf(type.context)?.snapshots(type) ?: local.snapshots(type)

        override fun events(type: AggregateType): EventQueries = peerOf(type.context)?.events(type) ?: local.events(type)

        @ExperimentalWowApi
        override fun <R : Any> datasets(name: DatasetName, rowType: Class<R>): RowQueries<R> =
            peerOf(name.context)?.datasets(name, rowType) ?: local.datasets(name, rowType)

        private fun peerOf(context: String): Queries? =
            if (peers.reaches(context)) peers.clientOf(context).queries.of(caller) else null
    }
}
