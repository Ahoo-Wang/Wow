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

package me.ahoo.wow.mongo

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration

class MongoSnapshotStoreBatchOptionsTest {
    @Test
    fun `defaults should keep batching disabled`() {
        val options = MongoSnapshotStoreBatchOptions()

        options.enabled.assert().isFalse()
        options.maxSize.assert().isEqualTo(MongoSnapshotStoreBatchOptions.DEFAULT_MAX_SIZE)
        options.maxDelay.assert().isEqualTo(MongoSnapshotStoreBatchOptions.DEFAULT_MAX_DELAY)
        options.maxPendingSaves.assert()
            .isEqualTo(MongoSnapshotStoreBatchOptions.DEFAULT_MAX_PENDING_SAVES)
        options.laneCount.assert().isEqualTo(MongoSnapshotStoreBatchOptions.DEFAULT_LANE_COUNT)
    }

    @Test
    fun `max size should allow native batching only`() {
        assertThrows<IllegalArgumentException> {
            MongoSnapshotStoreBatchOptions(maxSize = 1)
        }
    }

    @Test
    fun `max delay should be positive`() {
        assertThrows<IllegalArgumentException> {
            MongoSnapshotStoreBatchOptions(maxDelay = Duration.ZERO)
        }
        assertThrows<IllegalArgumentException> {
            MongoSnapshotStoreBatchOptions(maxDelay = Duration.ofMillis(-1))
        }
    }

    @Test
    fun `pending capacity should contain one full batch`() {
        assertThrows<IllegalArgumentException> {
            MongoSnapshotStoreBatchOptions(
                maxSize = 4,
                maxPendingSaves = 3,
            )
        }
    }

    @Test
    fun `lane count should be positive`() {
        assertThrows<IllegalArgumentException> {
            MongoSnapshotStoreBatchOptions(laneCount = 0)
        }
    }
}
