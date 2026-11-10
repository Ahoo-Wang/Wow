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

package me.ahoo.wow.spring.cloud

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.reactive.awaitFirstOrNull
import me.ahoo.wow.api.annotation.ExperimentalWowApi
import me.ahoo.wow.client.EndpointResolver
import me.ahoo.wow.client.Port
import org.springframework.cloud.client.ServiceInstance
import org.springframework.cloud.client.loadbalancer.reactive.ReactiveLoadBalancer
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * The endpoints of a [me.ahoo.wow.client.WowClient] in an application that is no Wow service, each context's service
 * named `lb://<service>` and its instance chosen by Spring Cloud LoadBalancer on every attempt, so a
 * retry may reach another one: a context's public routes at the chosen instance's URI, its internal port at the
 * internal address its [WowInstanceMetadata] names, which a node of a service with wow-spring-cloud registers.
 *
 * ```kotlin
 * WowClient.builder()
 *     .endpoints(LoadBalancedEndpoints.of(loadBalancers, mapOf("bank" to URI.create("lb://bank-service"))))
 *     .service("teller-app")
 *     .build()
 * ```
 *
 * [loadBalancers] is the application's `ReactiveLoadBalancer.Factory<ServiceInstance>`, the bean
 * spring-cloud-starter-loadbalancer gives. A context that is not listed has no endpoint: the call fails as the code's
 * mistake. A listed service with no instance to choose, or whose chosen instance names no internal port, is an outage
 * for the moment: the call fails as `wow.peer.unavailable` and is retried.
 */
@ExperimentalWowApi
class LoadBalancedEndpoints private constructor(
    private val loadBalancers: ReactiveLoadBalancer.Factory<ServiceInstance>,
    private val services: Map<String, String>,
) : EndpointResolver {
    private val warned: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override suspend fun resolve(context: String, port: Port): URI? {
        val service = services[context] ?: return null
        val chosen = loadBalancers.getInstance(service)?.choose()?.awaitFirstOrNull()
        val instance = chosen?.takeIf { it.hasServer() }?.server ?: return null
        return when (port) {
            Port.PUBLIC -> instance.uri
            Port.INTERNAL -> WowInstanceMetadata.internalUrlOf(instance).also {
                if (it == null && warned.add(service)) {
                    logger.warn {
                        "Instance ${instance.instanceId} of $service names no internal port (metadata " +
                            "${WowInstanceMetadata.INTERNAL_PORT}), so context $context cannot be called in service " +
                            "mode through it: register the nodes of $service with wow-spring-cloud on their class path."
                    }
                }
            }
        }
    }

    /** Whether [context] is listed, without choosing an instance: a listed service need not be up at startup. */
    override suspend fun reaches(context: String, port: Port): Boolean = context in services

    companion object {
        private val logger = KotlinLogging.logger {}

        /** The scheme of a service's URI. */
        const val SCHEME: String = "lb"

        /**
         * The endpoints of each context of [services] at the service its `lb://<service>` URI names, through
         * [loadBalancers]; a URI of another scheme, or with a path, is refused.
         */
        @JvmStatic
        fun of(
            loadBalancers: ReactiveLoadBalancer.Factory<ServiceInstance>,
            services: Map<String, URI>
        ): LoadBalancedEndpoints =
            LoadBalancedEndpoints(
                loadBalancers,
                services.mapValues { (context, uri) ->
                    require(uri.scheme == SCHEME && !uri.authority.isNullOrBlank() && uri.path.isNullOrEmpty()) {
                        "The service of context $context is lb://<service>: $uri"
                    }
                    uri.authority
                },
            )
    }
}
