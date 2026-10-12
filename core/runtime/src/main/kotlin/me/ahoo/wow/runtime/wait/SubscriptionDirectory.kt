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

package me.ahoo.wow.runtime.wait

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.spi.log.EventStreamRecord

/**
 * What a wait needs to know about the subscriptions its chain names, until the Manifest gives it (design 04 §6.1:
 * admission checks the chain against the Manifest).
 *
 * The subscription runtime is one implementation, for the subscriptions of its own service; a Manifest-backed one
 * will answer for other contexts too.
 */
interface SubscriptionDirectory {
    /** The kind of [subscription] in [context], or null when it is not known. */
    fun kindOf(context: String, subscription: String): FunctionKind?

    /**
     * Whether [subscription] of [type]'s context handles the streams of [type]. By default, whether the subscription
     * is known in that context at all; a directory that knows the subscriptions' filters answers per aggregate type.
     */
    fun covers(subscription: String, type: AggregateType): Boolean = kindOf(type.context, subscription) != null

    /**
     * Whether [subscription] has handled [stream] for good: its checkpoint is past the stream's position and its
     * aggregate is not parked (decision D63). False when that cannot be told.
     */
    suspend fun hasHandled(subscription: String, stream: EventStreamRecord): Boolean

    companion object {
        /** Knows no subscription. */
        val NONE: SubscriptionDirectory = object : SubscriptionDirectory {
            override fun kindOf(context: String, subscription: String): FunctionKind? = null

            override suspend fun hasHandled(subscription: String, stream: EventStreamRecord): Boolean = false
        }
    }
}
