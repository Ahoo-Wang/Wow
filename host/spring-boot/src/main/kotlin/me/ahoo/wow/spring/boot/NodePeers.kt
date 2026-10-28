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
import me.ahoo.wow.client.EndpointResolver
import me.ahoo.wow.client.HttpCommandCaller
import me.ahoo.wow.runtime.contract.ContractDirectory
import me.ahoo.wow.spi.peer.PeerDiscovery
import me.ahoo.wow.spring.boot.WowAutoConfiguration.Companion.PEER_HTTP_CLIENT
import me.ahoo.wow.spring.boot.WowAutoConfiguration.Companion.serviceOf
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.net.http.HttpClient
import kotlin.time.toKotlinDuration

/** The node's peers: where their services are, and the one HTTP client the node calls them with. */
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
}
