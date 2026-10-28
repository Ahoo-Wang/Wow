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
import me.ahoo.wow.runtime.node.NodeSampler
import me.ahoo.wow.runtime.node.ServiceNode
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import kotlin.time.toKotlinDuration

/**
 * The node's one sampler ([NodeSampler]), every `wow.node.sample-interval`, its parked entries counted up to
 * `wow.node.max-parked`: the node's health and its gauges both read its samples, so the ledgers and the logs' heads are
 * read once per interval, whoever reports them.
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
}
