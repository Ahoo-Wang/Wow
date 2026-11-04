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

package me.ahoo.wow.migrate

import kotlinx.coroutines.flow.transformWhile
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.spi.log.EventLog

/**
 * Where this log ends, by partition ([EventLog.head]), the empty ones left out: what a walk records before its first
 * instance, so a later run reads only what the log holds after it ([changedSince]).
 */
internal suspend fun EventLog.heads(): Map<Int, Long> =
    (0 until partitions).mapNotNull { partition -> head(partition)?.let { partition to it } }.toMap()

/**
 * The instances of [type] this log holds entries of after [from] up to [to], two [heads] read in that order: each
 * partition whose head moved read from the sequence after its old head (from its first entry when it had none) until
 * the entry at its new head, as the tail does not end by itself.
 *
 * The log only grows, so an instance it holds no entry of after [from] has the history it had then. On MongoDB an
 * append in flight while [from] was read may still land below it ([EventLog.head]), which this read does not see.
 */
internal suspend fun EventLog.changedSince(
    type: AggregateType,
    from: Map<Int, Long>,
    to: Map<Int, Long>,
): Set<AggregateRef> {
    val refs = LinkedHashSet<AggregateRef>()
    for ((partition, head) in to) {
        val after = from[partition]
        if (after != null && after >= head) {
            continue
        }
        tail(partition, after)
            .transformWhile { entry ->
                emit(entry)
                entry.position.sequence < head
            }
            .collect { entry -> entry.stream.ref.takeIf { it.type == type }?.let(refs::add) }
    }
    return refs
}
