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

package me.ahoo.wow.spi.log

import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi

/**
 * The backfill of the tags a log did not record with its streams (decision D259): streams appended before format 1
 * learned them have none ([EventStreamRecord.tags] null), which `migrate-data backfill-tags` derives by replaying each
 * aggregate and records here. Only the tags are written, and only where none are recorded: the rest of a stream never
 * changes. A log that stores the tags implements it beside [EventLog].
 */
@InternalWowApi
interface TagsBackfill {
    /**
     * The aggregates of [type] with at least one stream that records no tags, in an order of the log's own that is
     * total and stable (its key's, such as aggregate id, then tenant), strictly after [after] when given, at most
     * [limit]: a walk resumes from the last one it handled.
     */
    suspend fun untagged(type: AggregateType, after: AggregateRef? = null, limit: Int): List<AggregateRef>

    /**
     * Records on [ref]'s streams the tags [tagsByVersion] gives each version, on those that record none yet; returns
     * how many it recorded. A version the log does not hold, or whose stream records tags already, is left as it is.
     */
    suspend fun recordTags(ref: AggregateRef, tagsByVersion: Map<Int, Map<String, List<String>>>): Int
}
