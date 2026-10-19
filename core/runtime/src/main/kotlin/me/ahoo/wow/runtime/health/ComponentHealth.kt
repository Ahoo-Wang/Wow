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

package me.ahoo.wow.runtime.health

import me.ahoo.wow.api.annotation.InternalWowApi

/** How healthy a component of a node is (review R3.5-3), from the best to the worst. */
@InternalWowApi
enum class HealthStatus {
    /** Works as it should. */
    UP,

    /** Works, but something needs an operator's eye: a store that failed lately, a growing lag, a hand-over. */
    DEGRADED,

    /** Does not work: the node should not serve, or (for the liveness) should be restarted. */
    DOWN,
    ;

    @InternalWowApi
    companion object {
        /** The worst of [statuses]; [UP] when there are none. */
        fun worst(statuses: Iterable<HealthStatus>): HealthStatus = statuses.maxOrNull() ?: UP
    }
}

/**
 * The health of one component of a node, [name]d: its [status], what it is made of ([components], each with its own
 * health), and [details] an operator reads, plain values only (numbers, strings, booleans, lists and maps of them),
 * never a message that may name a host or hold a secret.
 */
@InternalWowApi
class ComponentHealth(
    val name: String,
    val status: HealthStatus,
    val details: Map<String, Any?> = emptyMap(),
    val components: List<ComponentHealth> = emptyList(),
) {
    /** The component [name]d among [components]; null when there is none. */
    fun component(name: String): ComponentHealth? = components.firstOrNull { it.name == name }

    override fun toString(): String = "ComponentHealth($name, $status, $details, $components)"

    @InternalWowApi
    companion object {
        /** The component [name]d made of [components], as healthy as the worst of them. */
        fun of(name: String, components: List<ComponentHealth>, details: Map<String, Any?> = emptyMap()) =
            ComponentHealth(name, HealthStatus.worst(components.map { it.status }), details, components)
    }
}
