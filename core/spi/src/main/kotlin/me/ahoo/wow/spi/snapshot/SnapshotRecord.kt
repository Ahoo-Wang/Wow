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

package me.ahoo.wow.spi.snapshot

import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.schedule.ScheduleRule

/**
 * An aggregate's latest committed state as the snapshot store keeps it: a cache derived from the log, never a fact.
 *
 * - [version] is the version of the last event stream folded into [state];
 * - [schemaFingerprint] identifies the state's schema: a reader whose fingerprint differs discards the snapshot and
 *   rebuilds the state from the log;
 * - [stateRevision] is the revision of the state's structure the snapshot was written at (decision D172,
 *   `@AggregateRoot(stateRevision)`): a reader of another revision does not read it back, and a store never lets a
 *   snapshot of a lower revision replace one of a higher revision;
 * - [state] is the serialized state, a JSON document;
 * - [scope] is the aggregate's owner and space, the scope of the last event stream folded (decision D56), and
 *   [tags] its access tags; the query side filters on the same fields;
 * - [deleted] marks an aggregate that is deleted; its snapshot stays, so it can be queried and restored;
 * - [firstEventTime] and [lastEventTime] are the commit times (epoch milliseconds) of its first and last streams;
 * - [operator] and [firstOperator] are the operators (decision D163) of its last stream and of its first, the one
 *   that created it; each null when that stream's caller was anonymous. Both are queryable system fields.
 * - [schedules] are the schedules the aggregate keeps (decision D198), each rule by its key: those its streams set
 *   (`wow.ScheduleSet`) and did not cancel (`wow.ScheduleCancelled`), fired or not; a deleted aggregate keeps none.
 */
data class SnapshotRecord(
    val ref: AggregateRef,
    val version: Int,
    val schemaFingerprint: String,
    val state: String,
    val scope: Scope = Scope.NONE,
    val deleted: Boolean = false,
    val firstEventTime: Long,
    val lastEventTime: Long,
    val tags: Map<String, List<String>> = emptyMap(),
    val operator: Operator? = null,
    val firstOperator: Operator? = null,
    val schedules: Map<String, ScheduleRule> = emptyMap(),
    val stateRevision: Int = 1,
) {
    init {
        require(version >= 1) { "A snapshot's version starts at 1: it folds at least one event stream." }
        require(stateRevision >= 1) { "A snapshot's state revision starts at 1." }

        require(schemaFingerprint.isNotBlank()) { "A snapshot needs its state's schema fingerprint." }
        require(state.isNotBlank()) { "A snapshot needs a state." }
        require(firstEventTime <= lastEventTime) { "The first event is not committed after the last one." }
        require(tags.keys.none { it.isBlank() }) { "A tag needs a name." }
    }
}
