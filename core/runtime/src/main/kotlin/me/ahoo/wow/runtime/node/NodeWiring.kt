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

import me.ahoo.wow.kernel.ContextQueries
import me.ahoo.wow.runtime.InFlightLimit
import me.ahoo.wow.runtime.admission.ModelRegistry
import me.ahoo.wow.runtime.cluster.ClusterChannel
import me.ahoo.wow.runtime.cluster.ServiceMembership
import me.ahoo.wow.runtime.command.CommandSender
import me.ahoo.wow.runtime.contract.ContractDirectory
import me.ahoo.wow.runtime.contract.PeerEvents
import me.ahoo.wow.runtime.subscription.FailureClassification
import me.ahoo.wow.runtime.subscription.ParkedNotifier
import me.ahoo.wow.runtime.timer.TimerLoop
import me.ahoo.wow.runtime.wait.SignalSender
import me.ahoo.wow.runtime.wait.WaitRegistry
import me.ahoo.wow.spi.call.CommandCaller
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.subscription.LeaseStore
import me.ahoo.wow.spi.wait.Origin
import java.time.Clock

/**
 * What every part of a [ServiceNode] shares: its identity and [address], its options and plug-ins, the node-wide
 * registries ([registry], [waits]), the senders ([signals], [sender], [caller]), how its subscriptions notify and
 * classify, the one in-flight bound and timer loop, and the state-cache sizing. Built once, before the contexts.
 */
@Suppress("LongParameterList")
internal class NodeWiring(
    val service: String,
    val node: String,
    /** This node's internal address, once it is known (given, or bound before the node starts). */
    val address: () -> String,
    val options: ServiceNodeOptions,
    val admission: AdmissionPlugins,
    val observer: Observer,
    val channel: ClusterChannel,
    val contracts: ContractDirectory,
    val topics: SourceTopics?,
    val registry: ModelRegistry,
    val waits: WaitRegistry,
    val signals: SignalSender,
    val origin: Origin,
    val sender: CommandSender?,
    val caller: CommandCaller?,
    val queries: ContextQueries,
    val peerEvents: PeerEvents,
    val notifiers: ParkedNotifier,
    val classification: FailureClassification,
    val inFlight: InFlightLimit,
    val timerLoop: TimerLoop,
    /** The node's clock: every wall time the node reads, its timers', leases' and waits' alike. */
    val clock: Clock,
    val nodeSnapshots: NodeSnapshots,
    val stateCacheCapacity: Int?,
    val stateCacheBytes: Long?,
    /** The node's membership in its service on a lease store, shared by every lease group on it. */
    val membershipOf: (LeaseStore) -> ServiceMembership,
    /** The `@OnCommitted` handlers of a context's sagas: hops of its waits. */
    val committedHandlers: (String) -> Set<String>,
) {
    /** The contexts of the types the topic router takes: each takes the commands of its topic. */
    private val topicRoutedContexts: Set<String> = options.topicRouted.mapTo(LinkedHashSet()) { it.context }

    /** Whether [context] takes the commands of its topic: the node takes them from topics, or routes some through Kafka. */
    fun takesCommands(context: String): Boolean = topics?.intake == true || context in topicRoutedContexts
}
