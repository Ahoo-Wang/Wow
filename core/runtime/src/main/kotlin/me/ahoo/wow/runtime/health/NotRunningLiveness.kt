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

import me.ahoo.wow.runtime.cluster.PartitionOwnership
import me.ahoo.wow.runtime.node.ServiceNode
import kotlin.time.Duration

/**
 * The liveness of a node not running, [details] those of [NodeHealth.liveness]. A new or stopping node is live: the
 * startup probe covers a start, and a draining node is not restarted. A start (RT-12) counts its reconciles alone, each
 * by how long it has finished no step ([PartitionOwnership.stalledFor]): `DOWN` as
 * [reconcile][NodeHealth.STALLED_RECONCILE] once one of [ownerships] has finished none for [stallTimeout], naming the
 * longest one's `group` and `stalledMillis`; `UP` while every one keeps finishing steps, however long the start takes.
 */
internal fun notRunningLiveness(
    state: ServiceNode.State,
    ownerships: List<PartitionOwnership>,
    stallTimeout: Duration,
    details: MutableMap<String, Any?>,
): ComponentHealth {
    val stalled = ownerships.takeIf { state == ServiceNode.State.STARTING }.orEmpty().mapNotNull { ownership ->
        ownership.stalledFor?.takeIf { it >= stallTimeout }?.let { ownership.group.name to it }
    }.maxByOrNull { it.second } ?: return ComponentHealth(NodeHealth.LIVENESS, HealthStatus.UP, details)
    details["group"] = stalled.first
    details["stalledMillis"] = stalled.second.inWholeMilliseconds
    details["stalled"] = NodeHealth.STALLED_RECONCILE
    details["stalls"] = listOf(NodeHealth.STALLED_RECONCILE)
    return ComponentHealth(NodeHealth.LIVENESS, HealthStatus.DOWN, details)
}
