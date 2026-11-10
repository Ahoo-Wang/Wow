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
import me.ahoo.wow.runtime.health.HealthOptions
import java.time.Duration
import kotlin.time.toJavaDuration
import kotlin.time.toKotlinDuration

/** The runtime's own health defaults, which these keys and the node's default to. */
internal val DEFAULT_HEALTH = HealthOptions()

/**
 * The node's health (me.ahoo.wow.runtime.health.NodeHealth): with Spring Boot Actuator on the class
 * path, the contributor `wow` (every component), and `wowReadiness` and `wowLiveness`, which the host adds to
 * Actuator's `readiness` and `liveness` groups (`/actuator/health/readiness`, `/actuator/health/liveness`) unless
 * the application configures their members itself. The thresholds are conservative: a probe that fails too soon
 * takes nodes out of service, or restarts them, for what a retry would have absorbed.
 */
@InternalWowApi
class HealthProperties {
    /** Whether the host contributes the node's health to Actuator and its probe groups. */
    var enabled: Boolean = true

    var stores: HealthStoresProperties = HealthStoresProperties()
    var ownership: HealthOwnershipProperties = HealthOwnershipProperties()
    var topics: HealthTopicsProperties = HealthTopicsProperties()
    var executor: HealthExecutorProperties = HealthExecutorProperties()
    var subscriptions: HealthSubscriptionsProperties = HealthSubscriptionsProperties()
    var liveness: HealthLivenessProperties = HealthLivenessProperties()

    /** These thresholds, the node sampling every [sampleInterval] (`wow.node.sample-interval`). */
    @InternalWowApi
    fun options(sampleInterval: Duration): HealthOptions = HealthOptions(
        sampleInterval = sampleInterval.toKotlinDuration(),
        storeDownAfter = stores.downAfter.toKotlinDuration(),
        storeWindow = stores.window.toKotlinDuration(),
        handoverGrace = ownership.handoverGrace.toKotlinDuration(),
        topicsRequired = topics.required,
        executorSaturation = executor.saturation,
        lagDegradedAt = subscriptions.lagDegradedAt,
        parkedDegradedAt = subscriptions.parkedDegradedAt,
        stallTimeout = liveness.stallTimeout.toKotlinDuration(),
    )
}

/** The node's event logs, as its own calls and a probe of their heads see them. */
@InternalWowApi
class HealthStoresProperties {
    /**
     * How long a log must keep failing, with no success since, before it is down and the node not ready: a failed
     * call or probe, or a probe that timed out (after this long, at least a second); a probe still under way is no
     * failure. `0s` is down at the first failure.
     */
    var downAfter: Duration = DEFAULT_HEALTH.storeDownAfter.toJavaDuration()

    /** A log with a failure this recent is degraded. */
    var window: Duration = DEFAULT_HEALTH.storeWindow.toJavaDuration()
}

/** The node's share of each lease group's partitions. */
@InternalWowApi
class HealthOwnershipProperties {
    /**
     * How long the owned partitions may differ from the node's share (a hand-over) before the group is degraded,
     * and down (the node not ready) when it owns none of a share that is not empty.
     */
    var handoverGrace: Duration = DEFAULT_HEALTH.handoverGrace.toJavaDuration()
}

/** The topics of other contexts the node reads. */
@InternalWowApi
class HealthTopicsProperties {
    /** Whether a topic the node is still joining makes it not ready; degraded only when false. */
    var required: Boolean = DEFAULT_HEALTH.topicsRequired
}

/** The node's command executors. */
@InternalWowApi
class HealthExecutorProperties {
    /** The share of `wow.executor.max-in-flight` in flight at which the executor is degraded. */
    var saturation: Double = DEFAULT_HEALTH.executorSaturation
}

/** The node's subscriptions, never a reason to take it out of service. */
@InternalWowApi
class HealthSubscriptionsProperties {
    /** The entries a subscription may be behind the log's head before it is degraded. */
    var lagDegradedAt: Long = DEFAULT_HEALTH.lagDegradedAt

    /** The parked entries of a subscription, counted up to this, at which it is degraded (as when they grow). */
    var parkedDegradedAt: Int = DEFAULT_HEALTH.parkedDegradedAt
}

/** Whether the node is stuck, so a restart helps; a storage outage never fails it. */
@InternalWowApi
class HealthLivenessProperties {
    /**
     * How long the node's dispatcher or its backends' driver threads may not tick, one reconcile not end, or
     * commands in flight not finish while every log answers, before the node is not live; longer than
     * `wow.cluster.reconcile-timeout`, so a stuck node leaves the members before it restarts.
     */
    var stallTimeout: Duration = DEFAULT_HEALTH.stallTimeout.toJavaDuration()
}
