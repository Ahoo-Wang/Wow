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

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.runtime.node.Routing
import me.ahoo.wow.spi.config.configCheck
import me.ahoo.wow.spi.config.configNotNull

/** Where the node runs commands: `wow.routing` and each aggregate type's `wow.aggregates.[<context>.<aggregate>].routing`. */
internal object CommandRoutings {
    /**
     * Where the node runs the commands it does not route through a topic: `wow.routing`, by default local when a
     * log is on a store no other node shares ([local]: the memory backend's), and partitioned on the others. Such a
     * store refuses partitioned routing. An aggregate type's `partitioned` or `local` names the node's routing, so
     * it must be that one.
     */
    fun routing(properties: WowProperties, local: Boolean): Routing {
        val configured = properties.routing
        configCheck(
            !local || configured != WowProperties.CommandRouting.PARTITIONED,
            "wow.routing",
            configured,
            "Back Wow with PostgreSQL or MongoDB, or route locally.",
        ) { "wow.routing=partitioned routes across nodes, which the memory backend cannot serve." }
        val routing = when (configured) {
            WowProperties.CommandRouting.PARTITIONED -> Routing.PARTITIONED
            WowProperties.CommandRouting.LOCAL -> Routing.LOCAL
            WowProperties.CommandRouting.AUTO, WowProperties.CommandRouting.TOPIC ->
                if (local) Routing.LOCAL else Routing.PARTITIONED
        }
        properties.aggregates.forEach { (key, aggregate) ->
            val named = when (aggregate.routing) {
                WowProperties.CommandRouting.PARTITIONED -> Routing.PARTITIONED
                WowProperties.CommandRouting.LOCAL -> Routing.LOCAL
                else -> return@forEach
            }
            configCheck(
                named == routing,
                "wow.aggregates.[$key].routing",
                aggregate.routing,
                "Leave it auto, set it to topic, or set wow.routing.",
            ) {
                "wow.aggregates.[$key].routing is ${valueOf(
                    aggregate.routing
                )}, but the node routes ${valueOf(routing)}."
            }
        }
        return routing
    }

    private fun valueOf(routing: Enum<*>): String = routing.name.lowercase()

    /**
     * The aggregate types of [models] routed through their context's command topic: each whose
     * `wow.aggregates.[<context>.<aggregate>].routing` is `topic`, or is `auto` under `wow.routing=topic`. A key
     * whose routing names no such type is refused.
     */
    fun topicRouted(properties: WowProperties, models: List<AggregateModel<*>>): Set<AggregateType> {
        val served = models.associateBy { "${it.type.context}.${it.type.name}" }
        properties.aggregates.filterValues { it.routing != WowProperties.CommandRouting.AUTO }.keys.forEach { key ->
            configNotNull(
                served[key],
                "wow.aggregates.[$key]",
                "Name one of the aggregate types this node serves: ${served.keys}."
            ) {
                "wow.aggregates.[$key].routing names no aggregate type this node serves."
            }
        }
        return served.filterKeys { key ->
            val own = properties.aggregates[key]?.routing ?: WowProperties.CommandRouting.AUTO
            val routing = if (own == WowProperties.CommandRouting.AUTO) properties.routing else own
            routing == WowProperties.CommandRouting.TOPIC
        }.values.mapTo(LinkedHashSet()) { it.type }
    }
}
