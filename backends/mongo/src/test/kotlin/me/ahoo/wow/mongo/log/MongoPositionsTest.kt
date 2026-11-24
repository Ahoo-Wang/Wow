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

package me.ahoo.wow.mongo.log

import me.ahoo.test.asserts.assert
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.MAX_SEQUENCE
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.lastPositionId
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.positionId
import me.ahoo.wow.spi.log.Partitioner
import org.junit.jupiter.api.Test

/** An entry's position as its `_id` (decision D276): 12 bits of partition, 51 of sequence. */
class MongoPositionsTest {
    @Test
    fun `the last of 4,096 partitions ends at the highest long, without overflowing`() {
        lastPositionId(Partitioner.SLOTS - 1).assert().isEqualTo(Long.MAX_VALUE)
        positionId(Partitioner.SLOTS - 1, 0).assert().isPositive()
    }

    @Test
    fun `each partition's positions end just below the next partition's first`() {
        listOf(0, 1, 63, Partitioner.SLOTS - 2).forEach { partition ->
            (lastPositionId(partition) + 1).assert().isEqualTo(positionId(partition + 1, 0))
            positionId(partition, MAX_SEQUENCE).assert().isEqualTo(lastPositionId(partition))
        }
    }
}
