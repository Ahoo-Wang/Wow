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
import kotlin.time.Duration

/**
 * What a subscription runtime tells the health model beyond its positions (review R3.5-3): the partitions it has
 * paused (decision D243).
 *
 * **The extension point of roadmap P.16**: a [me.ahoo.wow.runtime.subscription.SubscriptionRuntime] that pauses a
 * partition on a systemic failure (`StorageUnavailableException`, connection errors, many aggregates failing at once)
 * implements this interface, and [NodeHealth] reads it from every runtime of the node, the topic subscriptions'
 * included, by `runtime is SubscriptionHealth`; no other wiring is needed. A runtime that does not implement it pauses
 * nothing.
 *
 * [NodeHealth] reports a paused partition as `DEGRADED` in the `subscriptions` component, with its subscription,
 * partition, how long it has been paused and why; the readiness does not read it (the stores' component already takes
 * a node whose store is down out of service), nor the liveness (a pause waits for the store, it is not stuck).
 */
@InternalWowApi
fun interface SubscriptionHealth {
    /** The partitions paused now, each once per subscription; empty when none is. Called often: no I/O. */
    fun paused(): List<PausedPartition>
}

/**
 * [partition] of [subscription]'s source, whose handling is paused (decision D243) since [pausedFor] ago, for
 * [reason]: a short code or exception class, never a message that may name hosts.
 */
@InternalWowApi
class PausedPartition(
    val subscription: String,
    val partition: Int,
    val pausedFor: Duration,
    val reason: String,
) {
    override fun equals(other: Any?): Boolean = this === other ||
        other is PausedPartition &&
        subscription == other.subscription &&
        partition == other.partition &&
        pausedFor == other.pausedFor &&
        reason == other.reason

    override fun hashCode(): Int = listOf(subscription, partition, pausedFor, reason).hashCode()

    override fun toString(): String = "PausedPartition($subscription, $partition, $pausedFor, $reason)"
}
