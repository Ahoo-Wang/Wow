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

package me.ahoo.wow.spring.boot

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.client.CommandClient
import me.ahoo.wow.client.EndpointResolver
import me.ahoo.wow.client.HttpCommandCaller
import me.ahoo.wow.client.PeerClients
import me.ahoo.wow.client.RetryPolicy
import me.ahoo.wow.client.RoutedCommandClient
import me.ahoo.wow.client.UserCredential
import me.ahoo.wow.client.WowClient
import me.ahoo.wow.runtime.admission.CommandGateway
import me.ahoo.wow.runtime.client.InProcessCommandClient
import me.ahoo.wow.runtime.contract.ContractDirectory
import me.ahoo.wow.spi.call.CommandCaller
import me.ahoo.wow.spi.id.GlobalIdGenerator
import me.ahoo.wow.spi.peer.PeerDiscovery
import me.ahoo.wow.spring.boot.NodeModels.Companion.serviceOf
import me.ahoo.wow.spring.boot.WowAutoConfiguration.Companion.PEER_HTTP_CLIENT
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.web.server.WebFilter
import java.net.http.HttpClient
import kotlin.time.toKotlinDuration

/**
 * The node's peers: where their services are, the one HTTP client the node calls them with, the sagas' calls
 * ([CommandCaller]), the typed clients ([PeerClients], [WowClient]) and the service's [CommandClient], in process and
 * to the peers. Imported by [WowAutoConfiguration], whose order and conditions it shares.
 */
@Configuration(proxyBeanMethods = false)
internal class NodePeers {
    /**
     * Where the peers' services are: each context's public routes at
     * `wow.peers.<context>.public-url`; its internal port at `wow.peers.<context>.url`, else at the nodes
     * `wow.peer.discovery` finds of the service its contract on the class path names (`dns` by default, DNS SRV). An
     * application's own bean, a resolver that discovers them, replaces it for the sagas' calls and the clients alike.
     */
    @Bean
    @ConditionalOnMissingBean
    @InternalWowApi
    fun wowEndpointResolver(
        properties: WowProperties,
        contracts: ContractDirectory,
        discoveries: ObjectProvider<PeerDiscovery>,
        environment: Environment,
        context: ApplicationContext,
    ): EndpointResolver = PeerEndpoints.of(
        properties,
        PeerDiscoveries.load(context.classLoader, discoveries.orderedStream().toList()),
        { type -> PortProviders.optionsOf(environment, "${PeerDiscoveries.PREFIX}.$type") },
        { peer -> serviceOf(contracts, peer) },
    )

    /** The one HTTP client the node calls its peers with, the sagas' calls and the typed clients alike. */
    @Bean(PEER_HTTP_CLIENT, defaultCandidate = false)
    @ConditionalOnMissingBean(name = [PEER_HTTP_CLIENT])
    @InternalWowApi
    fun wowPeerHttpClient(properties: WowProperties): HttpClient =
        HttpCommandCaller.client(properties.peer.connectTimeout.toKotlinDuration())

    /**
     * The client of the other contexts' command gateways the sagas call: the service mode of
     * `wow-client` at the internal ports [endpoints] resolves, as this node's service, each call bounded by
     * `wow.peer.timeout` or its peer's own and sent by the route its peer's contract in [contracts] lists.
     */
    @Bean
    @ConditionalOnMissingBean
    @InternalWowApi
    fun wowCommandCaller(
        properties: WowProperties,
        identity: NodeIdentity,
        endpoints: EndpointResolver,
        @Qualifier(PEER_HTTP_CLIENT) client: HttpClient,
        contracts: ContractDirectory,
    ): CommandCaller = HttpCommandCaller(
        endpoints,
        identity.service,
        client,
        properties.peer.timeout.toKotlinDuration(),
        properties.peerTimeouts(),
        contracts.fragments.map { it.manifest },
    )

    /**
     * The peer contexts reached over HTTP, `wow.peers`, and their clients in either mode, each
     * built when first used: the service mode naming this node's service, the user mode forwarding the request's
     * `wow.peer.forwarded-headers`. A context of `wow.peers` the node serves itself is reached by its sagas' calls
     * alone: its commands and queries stay in the node.
     */
    @Bean
    @InternalWowApi
    fun wowPeerClients(
        properties: WowProperties,
        endpoints: EndpointResolver,
        identity: NodeIdentity,
        contracts: ContractDirectory,
        globalIds: GlobalIdGenerator,
        @Qualifier(PEER_HTTP_CLIENT) httpClient: HttpClient,
    ): PeerClients {
        val peer = properties.peer
        val described = properties.peers
            .filterValues { it.contract == PeerTargetProperties.Contract.MANIFEST }.keys

        /** A client of the peers, its mode named by [mode]: as the node's service, or forwarding the end user's. */
        fun client(mode: WowClient.Builder.() -> Unit) = lazy {
            val builder = WowClient.builder()
                .endpoints(endpoints)
                .httpClient(httpClient)
                .timeout(peer.timeout.toKotlinDuration())
                .retry(
                    RetryPolicy.of(
                        peer.retry.maxAttempts,
                        peer.retry.maxRetryAfter.toKotlinDuration(),
                        peer.retry.initialBackoff.toKotlinDuration(),
                    ),
                )
                .contracts(contracts.fragments)
                .ids(globalIds)
                .manifestPeers(described)
            properties.peerTimeouts().forEach { (context, timeout) -> builder.timeout(context, timeout) }
            builder.apply(mode).build()
        }
        return PeerClients(
            properties.peers.filterKeys { it !in contracts.localContexts }
                .mapValues { (_, target) -> target.mode ?: peer.mode },
            contracts.localContexts,
            contracts.peerContexts,
            client { service(identity.service) },
            client { user(UserCredential.FORWARDED) },
            peer.mode,
            // The node's service as its Manifest names it, which a subscription reads a peer as.
            checkNotNull(contracts.local.service) { "The node's Manifest names no service." }.name,
            described,
        )
    }

    /**
     * The typed clients of the peers over HTTP in `wow.peer.mode`, for the application that calls a
     * peer explicitly; the [CommandClient] and [me.ahoo.wow.api.read.QueryApi] beans route to them on their own.
     * An application replaces it by declaring a [WowClient] bean, not by calling this method.
     */
    @Bean
    @ConditionalOnMissingBean
    @InternalWowApi
    fun wowClient(peers: PeerClients): WowClient = peers.client

    /**
     * Puts the `wow.peer.forwarded-headers` of each public request in its handling's coroutine context
     * ([me.ahoo.wow.client.ForwardedHeaders]), which a user-mode client forwards to a peer.
     */
    @Bean
    @InternalWowApi
    fun wowForwardedHeadersFilter(properties: WowProperties): WebFilter =
        ForwardedHeadersFilter(properties.peer.forwardedHeaders.map(String::trim).filter(String::isNotEmpty))

    /**
     * The service's [CommandClient]: command objects sent through the node's gateway, in
     * process to the aggregates it serves, to its peers' command topics for the contexts their api jars declare, and
     * over HTTP to the peer contexts `wow.peers` lists ([PeerClients]). It sends as an anonymous caller;
     * [CommandClient.of] gives it another. An application replaces it by declaring a [CommandClient] bean, not by
     * calling this method. It is built on its first use ([DeferredCommandClient]), so a transformer, validator,
     * authorizer or subscription the node is assembled with may take it.
     */
    @Bean
    @ConditionalOnMissingBean
    @InternalWowApi
    fun wowCommandClient(gateway: ObjectProvider<CommandGateway>, peers: ObjectProvider<PeerClients>): CommandClient =
        DeferredCommandClient { commandClientOf(gateway.getObject(), peers.getObject()) }

    private companion object {
        private fun commandClientOf(gateway: CommandGateway, peers: PeerClients): CommandClient {
            val local = InProcessCommandClient(gateway)
            return if (peers.contexts.isEmpty()) local else RoutedCommandClient(local, peers, gateway::aggregatesOf)
        }
    }
}
