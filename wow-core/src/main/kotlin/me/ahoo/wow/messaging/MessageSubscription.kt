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
package me.ahoo.wow.messaging

import me.ahoo.wow.api.modeling.NamedAggregate

/**
 * Describes a message bus subscription.
 *
 * @property namedAggregates Aggregates whose messages should be received.
 * @property receiverGroup Logical receiver group used by distributed buses for consumer coordination.
 */
data class MessageSubscription(
    val namedAggregates: Set<NamedAggregate>,
    val receiverGroup: String = DEFAULT_RECEIVER_GROUP,
) {
    init {
        require(receiverGroup.isNotBlank()) {
            "receiverGroup must not be blank."
        }
    }

    constructor(
        namedAggregate: NamedAggregate,
        receiverGroup: String = DEFAULT_RECEIVER_GROUP,
    ) : this(setOf(namedAggregate), receiverGroup)

    companion object {
        const val DEFAULT_RECEIVER_GROUP = "default"
    }
}
