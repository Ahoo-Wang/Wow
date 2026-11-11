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
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.http.internal.HttpSignalSenderOptions
import me.ahoo.wow.http.internal.InternalHttp
import me.ahoo.wow.runtime.node.NodeSampler
import me.ahoo.wow.spring.boot.WowProperties.Companion.DEFAULT_INTERNAL_PORT
import me.ahoo.wow.spring.boot.observability.DEFAULT_HEALTH
import java.time.Duration
import kotlin.time.toJavaDuration

@InternalWowApi
class NodeProperties {
    /** This node's id among the nodes of the service; a new one each start when not set. */
    var id: String? = null

    /**
     * The `host:port` of this node's internal port that the other nodes reach, an IPv6 host in brackets (`[::1]:9191`;
     * a bare one fails the start), as the node writes it when not set; the port binds at `wow.internal.host`.
     */
    var address: String? = null

    /**
     * Whether the node starts on standby: through the dual-run window of a migration's cut-over it serves reads of
     * what `migrate-data` imports, refuses every command (the error `wow.node.standby`, 503), and handles no
     * subscription, retries no parked entry and runs no job, until its operator activates it on the internal port
     * (`POST /wow/node/activate` from a node of its service, naming who activates it). A subscription that starts
     * at the end of its stream starts where the stream ends at the activation.
     */
    var standby: Boolean = false

    /**
     * How often the node samples what is read rather than heard: for its health, the logs' heads, the
     * subscriptions' lag and parked entries, and a probe of the stores it has not called since; for its gauges
     * (wow-observability), the parked entries, the subscriptions' positions and a rebuild's progress.
     */
    var sampleInterval: Duration = DEFAULT_HEALTH.sampleInterval.toJavaDuration()

    /**
     * How many parked entries of one subscription a sample counts at most: a count past it reads as the cap, so a
     * flood of parked entries costs one bounded read per sample. The health's `parked-degraded-at` is at most it.
     */
    var maxParked: Int = NodeSampler.DEFAULT_MAX_PARKED
}

@InternalWowApi
class InternalProperties {
    /**
     * The port of the internal server, which carries the cluster channel and the wait signals; 0 for one the
     * system chooses, which the node advertises once the server is bound.
     */
    var port: Int = DEFAULT_INTERNAL_PORT

    /**
     * The address the internal server binds, `0.0.0.0` for every interface; when not set,
     * the host of the member address the node advertises (`wow.node.address`, else this host's address).
     */
    var host: String? = null

    /** How long a peer has to accept a connection of the cluster channel or the signal sender. */
    var connectTimeout: Duration = InternalHttp.DEFAULT_CONNECT_TIMEOUT.toJavaDuration()

    /**
     * The wait signals queued per origin node before they are dropped, so a slow or unreachable node cannot
     * hold the sender's memory.
     */
    var signalQueueCapacity: Int = HttpSignalSenderOptions.DEFAULT_QUEUE_CAPACITY
}

/**
 * The bounded contexts the node serves among the domain modules on the class path ([ContextSelection]), all of
 * them by default. A name that is no context on the class path is refused at startup.
 */
@InternalWowApi
class ContextsProperties {
    /** The contexts the node serves; every context on the class path when empty. */
    var include: MutableSet<String> = LinkedHashSet()

    /** The contexts the node does not serve, taken out of those [include] gives. */
    var exclude: MutableSet<String> = LinkedHashSet()

    @InternalWowApi
    fun selection(): ContextSelection = ContextSelection(include.toSet(), exclude.toSet())
}

/** The tenants of the callers. */
@InternalWowApi
class TenantProperties {
    /** The tenant of a caller that has none, such as the anonymous one. */
    var default: String = Caller.DEFAULT_TENANT
}
