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

package me.ahoo.wow.runtime.subscription

import me.ahoo.wow.api.annotation.InternalWowApi

/**
 * A subscription's partition paused on a systemic failure (decision D243): since [since] (epoch milliseconds), for
 * [reason], [subscription] of the subscription [group] handles nothing in [partition] but one probe at a time, until a
 * handling succeeds.
 */
@InternalWowApi
data class PausedPartition(
    val group: String,
    val subscription: String,
    val partition: Int,
    val since: Long,
    val reason: String,
)

/**
 * The partitions a node's subscriptions have paused on a systemic failure (decision D243), as the health model (review
 * R3-H) reads them: a paused partition is not a failed node, but one its operator wants to see, and that stays paused
 * as long as what it depends on is down.
 */
@InternalWowApi
fun interface SubscriptionPauses {
    /** The partitions paused now; empty when none is. */
    fun paused(): List<PausedPartition>

    @InternalWowApi
    companion object {
        /** Pauses nothing. */
        @JvmField
        val NONE: SubscriptionPauses = SubscriptionPauses { emptyList() }

        /** The pauses of every one of [pauses], read each time. */
        fun of(pauses: () -> Collection<SubscriptionPauses>): SubscriptionPauses =
            SubscriptionPauses { pauses().flatMap { it.paused() } }
    }
}
