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
import me.ahoo.wow.http.WowHttpOptions
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.http.ops.subscriptionRoutes
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.runtime.admission.ServiceRouteAuthorizer
import me.ahoo.wow.runtime.node.NodeSampler
import me.ahoo.wow.runtime.node.ServiceNode
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerResponse
import kotlin.time.toKotlinDuration

/**
 * The node's one sampler ([NodeSampler]), every `wow.node.sample-interval`, its parked entries counted up to
 * `wow.node.max-parked`: the node's health, its gauges and the operator's read of its subscriptions all read its
 * samples, so the ledgers and the logs' heads are read once per interval, whoever reports them.
 */
@AutoConfiguration(
    after = [WowAutoConfiguration::class],
    before = [HealthAutoConfiguration::class, ObservabilityAutoConfiguration::class]
)
class NodeSamplerAutoConfiguration {
    @Bean(initMethod = "start", destroyMethod = "close")
    @ConditionalOnBean(ServiceNode::class)
    @ConditionalOnMissingBean
    @InternalWowApi
    fun wowNodeSampler(node: ServiceNode, properties: WowProperties): NodeSampler =
        NodeSampler(node, properties.node.sampleInterval.toKotlinDuration(), properties.node.maxParked)

    /**
     * The operator's read of the node's subscriptions, when `wow.http.ops.enabled`, beside the parked routes:
     * authenticated by the application's [CallerResolver], read by a caller that reaches every tenant, and authorized
     * by its [ServiceRouteAuthorizer]s as the route `ops.subscriptions` of the context `wow`.
     */
    @Bean
    @ConditionalOnBean(ServiceNode::class)
    @ConditionalOnProperty(prefix = "${WowProperties.PREFIX}.http.ops", name = ["enabled"], havingValue = "true")
    @InternalWowApi
    fun wowSubscriptionsRouterFunction(
        manifest: Manifest,
        sampler: NodeSampler,
        callers: CallerResolver,
        options: WowHttpOptions,
        routeAuthorizers: ObjectProvider<ServiceRouteAuthorizer>,
    ): RouterFunction<ServerResponse> = subscriptionRoutes(
        sampler,
        manifest,
        ServiceRouteAuthorizer.allOf(routeAuthorizers.orderedStream().toList()),
        callers,
        options,
    )
}
