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

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.runtime.subscription.SubscriptionPauses
import me.ahoo.wow.runtime.subscription.ThrottledPartition

/**
 * The partitions the node's subscriptions have paused on a systemic failure (decision D243), the built-in ones, the
 * intakes' and those of other contexts' topics included: what the health model reads (review R3-H).
 */
@InternalWowApi
val ServiceNode.pauses: SubscriptionPauses
    get() = SubscriptionPauses.of {
        contexts.values.flatMap { context -> context.logGroups.map { it.subscriptions } + listOfNotNull(context.intake) } +
            topicSubscriptions.values
    }

/**
 * The partitions the node's subscriptions throttle on backpressure now (decision D263), the built-in ones, the intakes'
 * and those of other contexts' topics included.
 */
@InternalWowApi
val ServiceNode.throttled: List<ThrottledPartition>
    get() = contexts.values.flatMap { context ->
        (context.logGroups.map { it.subscriptions } + listOfNotNull(context.intake)).flatMap { it.throttled() }
    } + topicSubscriptions.values.flatMap { it.throttled() }
