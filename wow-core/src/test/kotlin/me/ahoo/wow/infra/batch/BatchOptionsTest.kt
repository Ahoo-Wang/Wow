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

package me.ahoo.wow.infra.batch

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration

class BatchOptionsTest {
    @Test
    fun `max size should not overflow Reactor fair prefetch`() {
        val maximum = Int.MAX_VALUE / 4
        BatchOptions(maxSize = maximum, maxPendingItems = maximum).maxSize.assert().isEqualTo(maximum)
        assertThrows<IllegalArgumentException> {
            BatchOptions(maxSize = maximum + 1, maxPendingItems = maximum + 1)
        }
    }

    @Test
    fun `defaults should be shared by every storage`() {
        val options = BatchOptions()
        options.maxSize.assert().isEqualTo(128)
        options.maxDelay.assert().isEqualTo(Duration.ofMillis(1))
        options.maxPendingItems.assert().isEqualTo(4096)
        options.laneCount.assert().isEqualTo(1)
    }

    @Test
    fun `limits should reject unusable batching configurations`() {
        assertThrows<IllegalArgumentException> { BatchOptions(maxSize = 1) }
        assertThrows<IllegalArgumentException> { BatchOptions(maxDelay = Duration.ZERO) }
        assertThrows<IllegalArgumentException> { BatchOptions(maxDelay = Duration.ofNanos(-1)) }
        assertThrows<IllegalArgumentException> { BatchOptions(maxPendingItems = 127) }
        assertThrows<IllegalArgumentException> { BatchOptions(laneCount = 0) }
        assertThrows<IllegalArgumentException> { BatchOptions(laneCount = 4097) }
        BatchOptions(laneCount = 4096).laneCount.assert().isEqualTo(4096)
    }
}
