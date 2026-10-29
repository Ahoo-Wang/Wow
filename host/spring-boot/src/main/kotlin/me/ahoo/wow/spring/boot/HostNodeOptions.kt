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
import me.ahoo.wow.runtime.node.Routing
import me.ahoo.wow.runtime.node.ServiceNodeOptions
import me.ahoo.wow.spring.boot.PortProviders.Companion.configured

/**
 * The node's options from these properties: [routing] and the [topicRouted] types as [CommandRoutings] resolved them,
 * the failure ledger published when the node has a [transport]. A range a setting breaks fails naming its group of
 * keys (`wow.cluster`, `wow.wait`, ...), not only the runtime's reason.
 */
internal fun WowProperties.serviceNodeOptions(
    routing: Routing,
    topicRouted: Set<AggregateType>,
    transport: Boolean,
): ServiceNodeOptions = ServiceNodeOptions(
    cluster = configured("wow.cluster") { cluster.options(wait.defaultTimeout) },
    routing = routing,
    wait = configured("wow.wait") { wait.options() },
    subscription = configured("wow.subscription") { subscription.options(subscriptions) },
    handoff = configured("wow.log") { log.handoffOptions() },
    executor = configured("wow.executor") { executor.options() },
    stateCache = configured("wow.state-cache") { stateCache.options() },
    sharedStates = configured("wow.shared-states") { sharedStates.options() },
    timers = configured("wow.timers") { timers.options() },
    schedules = schedules.mapValues { (name, schedule) -> configured("wow.schedules.$name") { schedule.options() } },
    topicRouted = topicRouted,
    ledger = ops.publish.options(transport = transport),
    snapshots = configured("wow.snapshot") { snapshot.options() },
)
