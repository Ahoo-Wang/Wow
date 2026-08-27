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

package me.ahoo.wow.elasticsearch.eventsourcing

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration

class ElasticsearchBatchOptionsTest {
    @Test
    fun `event store lane count should default to one and remain positive`() {
        ElasticsearchEventStoreBatchOptions().laneCount.assert()
            .isEqualTo(ElasticsearchEventStoreBatchOptions.DEFAULT_LANE_COUNT)

        assertThrows<IllegalArgumentException> {
            ElasticsearchEventStoreBatchOptions(laneCount = 0)
        }.message.assert().isEqualTo("laneCount must be greater than zero.")
    }

    @Test
    fun `event store options should reject invalid batch limits`() {
        assertThrows<IllegalArgumentException> {
            ElasticsearchEventStoreBatchOptions(maxSize = 1)
        }.message.assert().isEqualTo("maxSize must be greater than 1.")
        assertThrows<IllegalArgumentException> {
            ElasticsearchEventStoreBatchOptions(maxDelay = Duration.ZERO)
        }.message.assert().isEqualTo("maxDelay must be positive.")
        assertThrows<IllegalArgumentException> {
            ElasticsearchEventStoreBatchOptions(maxDelay = Duration.ofMillis(-1))
        }.message.assert().isEqualTo("maxDelay must be positive.")
        assertThrows<IllegalArgumentException> {
            ElasticsearchEventStoreBatchOptions(
                maxSize = 4,
                maxPendingAppends = 3,
            )
        }.message.assert()
            .isEqualTo("maxPendingAppends must be greater than or equal to maxSize.")
    }

    @Test
    fun `snapshot store lane count should default to one and remain positive`() {
        ElasticsearchSnapshotStoreBatchOptions().laneCount.assert()
            .isEqualTo(ElasticsearchSnapshotStoreBatchOptions.DEFAULT_LANE_COUNT)

        assertThrows<IllegalArgumentException> {
            ElasticsearchSnapshotStoreBatchOptions(laneCount = 0)
        }.message.assert().isEqualTo("laneCount must be greater than zero.")
    }

    @Test
    fun `snapshot store options should reject invalid batch limits`() {
        assertThrows<IllegalArgumentException> {
            ElasticsearchSnapshotStoreBatchOptions(maxSize = 1)
        }.message.assert().isEqualTo("maxSize must be greater than 1.")
        assertThrows<IllegalArgumentException> {
            ElasticsearchSnapshotStoreBatchOptions(maxDelay = Duration.ZERO)
        }.message.assert().isEqualTo("maxDelay must be positive.")
        assertThrows<IllegalArgumentException> {
            ElasticsearchSnapshotStoreBatchOptions(maxDelay = Duration.ofMillis(-1))
        }.message.assert().isEqualTo("maxDelay must be positive.")
        assertThrows<IllegalArgumentException> {
            ElasticsearchSnapshotStoreBatchOptions(
                maxSize = 4,
                maxPendingSaves = 3,
            )
        }.message.assert()
            .isEqualTo("maxPendingSaves must be greater than or equal to maxSize.")
    }
}
