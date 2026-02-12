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

package me.ahoo.wow.eventsourcing

import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.modeling.NamedAggregate
import reactor.core.publisher.Flux

/**
 * Interface for scanning aggregate IDs within a named aggregate.
 * Provides functionality to retrieve aggregate IDs in a paginated manner.
 */
interface AggregateIdScanner {
    companion object {
        /**
         * Constant representing the first ID for scanning, used as the starting point.
         */
        const val FIRST_ID = "(0)"

        /**
         * Constant representing the last possible ID, used for unbounded scanning.
         */
        const val LAST_ID = "~"
    }

    /**
     * Scans for aggregate IDs within the specified named aggregate, starting after the given ID.
     * Returns a limited number of aggregate IDs in lexicographical order.
     *
     * @param namedAggregate the named aggregate to scan within
     * @param afterId the ID to start scanning after (default: FIRST_ID)
     * @param limit the maximum number of aggregate IDs to return (default: 10)
     * @return a Flux of aggregate IDs
     */
    fun scanAggregateId(
        namedAggregate: NamedAggregate,
        afterId: String = FIRST_ID,
        limit: Int = 10
    ): Flux<AggregateId>
}
