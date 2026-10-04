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
 * @property runtimeOwned The subscriber is a [me.ahoo.wow.runtime.WowRuntime] dispatcher: it opens processing only once
 * the runtime is ready and confirms or rejects every local delivery it is handed ([confirmLocalDelivery],
 * [rejectLocalDelivery]). A local bus routes local-first deliveries only through such receivers; any other subscriber
 * just observes the messages. Built-in dispatchers set it; a custom consumer leaves it `false` unless it implements
 * the same protocol.
 */
data class MessageSubscription(
    val namedAggregates: Set<NamedAggregate>,
    val receiverGroup: String = DEFAULT_RECEIVER_GROUP,
    val runtimeOwned: Boolean = false,
) {
    init {
        require(receiverGroup.isNotBlank()) {
            "receiverGroup must not be blank."
        }
    }

    constructor(
        namedAggregate: NamedAggregate,
        receiverGroup: String = DEFAULT_RECEIVER_GROUP,
        runtimeOwned: Boolean = false,
    ) : this(setOf(namedAggregate), receiverGroup, runtimeOwned)

    /** compat(wow<9.3): the 9.2 constructor, kept for callers compiled against it. */
    @Deprecated(
        "Scheduled for removal in 10.0.0. Use the constructor with runtimeOwned.",
        level = DeprecationLevel.HIDDEN
    )
    constructor(namedAggregates: Set<NamedAggregate>, receiverGroup: String = DEFAULT_RECEIVER_GROUP) :
        this(namedAggregates, receiverGroup, false)

    /** compat(wow<9.3): the 9.2 constructor, kept for callers compiled against it. */
    @Deprecated(
        "Scheduled for removal in 10.0.0. Use the constructor with runtimeOwned.",
        level = DeprecationLevel.HIDDEN
    )
    constructor(namedAggregate: NamedAggregate, receiverGroup: String = DEFAULT_RECEIVER_GROUP) :
        this(setOf(namedAggregate), receiverGroup, false)

    /** compat(wow<9.3): the 9.2 `copy`, kept for callers compiled against it. */
    @Deprecated("Scheduled for removal in 10.0.0. Use copy with runtimeOwned.", level = DeprecationLevel.HIDDEN)
    fun copy(namedAggregates: Set<NamedAggregate> = this.namedAggregates, receiverGroup: String = this.receiverGroup) =
        copy(namedAggregates = namedAggregates, receiverGroup = receiverGroup, runtimeOwned = runtimeOwned)

    companion object {
        const val DEFAULT_RECEIVER_GROUP = "default"
    }
}
