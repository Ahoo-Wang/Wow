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

package me.ahoo.wow.runtime.snapshot

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.subscribe.SystemSubscriptions
import me.ahoo.wow.runtime.wait.SubscriptionDirectory
import me.ahoo.wow.spi.log.EventStreamRecord

/**
 * The subscription directory of [context]'s runtime, with its `snapshot` and `queryable` hops answered by the snapshot
 * targets, for each type of [types], the context's snapshotted ones: `snapshot` by the subscription of the type's own
 * store ([SnapshotTargets.primarySubscriptionOf]), `queryable` by the one of the store its queries read, its copy's
 * where it has one ([SnapshotTargets.querySubscriptionOf]).
 */
internal class SnapshotHops(
    private val runtime: SubscriptionDirectory,
    private val context: String,
    private val targets: SnapshotTargets,
    private val types: Set<AggregateType>,
) : SubscriptionDirectory by runtime {
    override fun kindOf(context: String, subscription: String): FunctionKind? =
        if (snapshotHop(subscription) && context == this.context) {
            FunctionKind.SYSTEM.takeIf { types.isNotEmpty() }
        } else {
            runtime.kindOf(context, subscription)
        }

    override fun covers(subscription: String, type: AggregateType): Boolean =
        if (snapshotHop(subscription) && type.context == context) {
            type in types
        } else {
            runtime.covers(subscription, type)
        }

    override suspend fun hasHandled(subscription: String, stream: EventStreamRecord): Boolean {
        val type = stream.ref.type
        if (subscription == SystemSubscriptions.SNAPSHOT && type in types) {
            return runtime.hasHandled(targets.primarySubscriptionOf(type), stream)
        }
        if (subscription == SystemSubscriptions.QUERYABLE && type in types) {
            return runtime.hasHandled(targets.querySubscriptionOf(type), stream)
        }
        return runtime.hasHandled(subscription, stream)
    }

    private fun snapshotHop(subscription: String): Boolean =
        subscription == SystemSubscriptions.SNAPSHOT || subscription == SystemSubscriptions.QUERYABLE
}
