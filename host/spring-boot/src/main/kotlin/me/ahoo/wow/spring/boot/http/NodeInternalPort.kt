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

package me.ahoo.wow.spring.boot.http

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.http.WowHttpOptions
import me.ahoo.wow.http.callRoutes
import me.ahoo.wow.http.caller.CallPermits
import me.ahoo.wow.http.caller.DeclaredServices
import me.ahoo.wow.http.internal.HttpClusterChannel
import me.ahoo.wow.http.internal.HttpSignalSender
import me.ahoo.wow.http.internal.HttpSignalSenderOptions
import me.ahoo.wow.http.internal.InternalHttp
import me.ahoo.wow.http.internal.InternalPort
import me.ahoo.wow.http.internal.InternalPortSettings
import me.ahoo.wow.http.internal.internalRoutes
import me.ahoo.wow.http.peerReadRoutes
import me.ahoo.wow.http.query.HttpQueries
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.query.mask.PayloadMasks
import me.ahoo.wow.runtime.cluster.ClusterChannel
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.state.StateReads
import me.ahoo.wow.runtime.wait.SignalSender
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spring.boot.InternalServerLifecycle
import me.ahoo.wow.spring.boot.NodeIdentity
import me.ahoo.wow.spring.boot.NodeStandby
import me.ahoo.wow.spring.boot.RoutesScope
import me.ahoo.wow.spring.boot.WowAutoConfiguration
import me.ahoo.wow.spring.boot.WowAutoConfiguration.Companion.INTERNAL_HTTP_CLIENT
import me.ahoo.wow.spring.boot.WowProperties
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.util.unit.DataSize
import java.net.http.HttpClient
import kotlin.time.toKotlinDuration

/**
 * The node's internal port: the cluster channel and the wait signals over one JDK client ([HttpClusterChannel],
 * [HttpSignalSender]), and the [InternalPort] that serves the other nodes' forwards and signals, the peers' reads and
 * the services' calls, on a port of its own at `wow.internal.port`, never on the public server. Imported by
 * [WowAutoConfiguration], whose order and conditions it shares.
 */
@Configuration(proxyBeanMethods = false)
internal class NodeInternalPort {
    @Bean(defaultCandidate = false)
    @ConditionalOnMissingBean(name = [INTERNAL_HTTP_CLIENT])
    @InternalWowApi
    fun wowInternalHttpClient(properties: WowProperties): HttpClient =
        InternalHttp.client(properties.internal.connectTimeout.toKotlinDuration())

    @Bean
    @ConditionalOnMissingBean
    @InternalWowApi
    fun wowClusterChannel(
        @Qualifier(INTERNAL_HTTP_CLIENT) client: HttpClient,
        identity: NodeIdentity,
    ): ClusterChannel = HttpClusterChannel(client, service = identity.service)

    @Bean
    @ConditionalOnMissingBean
    @InternalWowApi
    fun wowSignalSender(
        @Qualifier(INTERNAL_HTTP_CLIENT) client: HttpClient,
        properties: WowProperties,
        identity: NodeIdentity,
        observers: ObjectProvider<Observer>,
    ): SignalSender = HttpSignalSender(
        client,
        service = identity.service,
        observer = Observer.of(observers.orderedStream().toList()),
        options = HttpSignalSenderOptions(queueCapacity = properties.internal.signalQueueCapacity),
    )

    @Bean
    @ConditionalOnMissingBean
    @InternalWowApi
    fun wowInternalServer(
        properties: WowProperties,
        node: ServiceNode,
        identity: NodeIdentity,
        manifest: Manifest,
        options: WowHttpOptions,
        environment: Environment,
        reads: StateReads,
        queries: ObjectProvider<HttpQueries>,
        masks: PayloadMasks,
        routes: RoutesScope,
    ): InternalPort {
        // The service each request names, its identity in the default tenant.
        val services = DeclaredServices.resolver(properties.tenant.default)
        val permits = callPermits(properties)
        val settings = InternalPortSettings(
            port = properties.internal.port,
            bindHost = identity.bindHost,
            maxMessageSize = InternalHttp.messageLimit(publicCodecLimit(environment)),
        )
        return InternalPort.of(
            settings,
            internalRoutes(
                node.forwardHandler,
                node.waits,
                scope = routes.scope,
                service = identity.service,
                standby = NodeStandby(node),
            )
                // The services' peer reads, before the call routes: their catch-all routes of an
                // unknown command would take a query's POST.
                .and(
                    peerReadRoutes(
                        manifest,
                        node.gateway,
                        services,
                        queries.getIfAvailable { HttpQueries.NONE },
                        reads,
                        options,
                        permits,
                        masks,
                        routes.scope,
                    ),
                )
                .and(callRoutes(manifest, node.gateway, services, options, permits, routes.scope)),
        )
    }

    /** Binds the internal port, then gives a node without a configured address the one it is bound at. */
    @Bean
    @InternalWowApi
    fun wowInternalServerLifecycle(
        server: InternalPort,
        node: ServiceNode,
        identity: NodeIdentity,
    ): InternalServerLifecycle = InternalServerLifecycle(server) { port ->
        if (!node.hasAddress) {
            node.bind(identity.addressAt(port))
        }
    }

    internal companion object {
        /**
         * The in-memory limit of the public port's codecs, in bytes (negative: none): Spring Boot's
         * `spring.http.codecs.max-in-memory-size`, or the older `spring.codec.max-in-memory-size`, else WebFlux's
         * default. The internal port takes a forward of a body that large.
         */
        internal fun publicCodecLimit(environment: Environment): Int {
            val binder = Binder.get(environment)
            val size = CODEC_LIMITS.firstNotNullOfOrNull { binder.bind(it, DataSize::class.java).orElse(null) }
                ?: return InternalHttp.MAX_UPGRADE_BODY
            return size.toBytes().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }

        private val CODEC_LIMITS = listOf("spring.http.codecs.max-in-memory-size", "spring.codec.max-in-memory-size")

        /** The commands each service may call on the internal port (`wow.callers.*.commands`). */
        internal fun callPermits(properties: WowProperties): CallPermits =
            CallPermits(
                properties.callers.mapValues { it.value.commands.toList() },
                properties.callers.mapValues { it.value.reads.toList() },
            )
    }
}
