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

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.runtime.wait.SubscriptionDirectory
import me.ahoo.wow.spi.log.EventStreamRecord

/**
 * The subscriptions of a context whose log is in several groups, as its waits see them: every group's
 * runtime runs the same subscriptions, so [default]'s answers what they are; whether one has handled a stream is
 * told by the runtime of the stream's group, [groups] for the types they name, [default] for the others, whose
 * checkpoints are in that group's sequences.
 */
internal class LogGroupDirectory(
    private val default: SubscriptionRuntime,
    private val groups: Map<AggregateType, SubscriptionRuntime>,
) : SubscriptionDirectory {
    override fun kindOf(context: String, subscription: String): FunctionKind? = default.kindOf(context, subscription)

    override fun covers(subscription: String, type: AggregateType): Boolean = default.covers(subscription, type)

    override suspend fun hasHandled(subscription: String, stream: EventStreamRecord): Boolean =
        (groups[stream.ref.type] ?: default).hasHandled(subscription, stream)
}
