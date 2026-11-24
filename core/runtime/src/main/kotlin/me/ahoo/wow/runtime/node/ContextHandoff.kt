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

package me.ahoo.wow.runtime.node

import me.ahoo.wow.kernel.StateCache
import me.ahoo.wow.runtime.cluster.probingAfterAcquiring
import me.ahoo.wow.runtime.log.HandoffLog
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.observation.ObservedEvent
import me.ahoo.wow.spi.observation.ObservedEvent.Fatal.Component

/**
 * [log] handing this node's commits to its tails in memory, as [ServiceNodeOptions.handoff] says. What its tails
 * read from the store instead holds the commits other nodes made in this node's partitions without their
 * leases: each drops the cached state of its aggregate when that is behind it ([StateCache.dropBefore]), so the
 * next command decides on the log, not on a stale state that would conflict.
 */
internal fun NodeWiring.handoffOf(context: String, log: EventLog, states: StateCache): EventLog =
    HandoffLog(
        log,
        context,
        options.handoff.probingAfterAcquiring(clusterOrNull()),
        observer,
        contextOf(Component.NODE, "tail-probe:$context"),
    ) { entry ->
        if (states.dropBefore(entry.stream.ref, entry.stream.version)) {
            observer.observed(ObservedEvent.StateCache(context, ObservedEvent.StateCache.Outcome.STALE))
        }
    }

/** The cluster's options on a partitioned node; null when every command runs where it arrives. */
private fun NodeWiring.clusterOrNull() = options.cluster.takeIf { options.routing == Routing.PARTITIONED }
