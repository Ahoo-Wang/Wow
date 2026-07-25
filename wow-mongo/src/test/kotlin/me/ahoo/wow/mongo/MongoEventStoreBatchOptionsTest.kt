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

class MongoEventStoreBatchOptionsTest {
    @Test
    fun `default options should disable batching`() {
        val options = MongoEventStoreBatchOptions()

        options.enabled.assert().isFalse()
        options.maxSize.assert().isEqualTo(MongoEventStoreBatchOptions.DEFAULT_MAX_SIZE)
        options.maxDelay.assert().isEqualTo(MongoEventStoreBatchOptions.DEFAULT_MAX_DELAY)
        options.maxPendingAppends.assert()
            .isEqualTo(MongoEventStoreBatchOptions.DEFAULT_MAX_PENDING_APPENDS)
    }

    @Test
    fun `max size should be greater than one`() {
        val error = assertThrows<IllegalArgumentException> {
            MongoEventStoreBatchOptions(maxSize = 1)
        }

        error.message.assert().isEqualTo("maxSize must be greater than 1.")
    }

    @Test
    fun `max delay should be positive`() {
        val error = assertThrows<IllegalArgumentException> {
            MongoEventStoreBatchOptions(maxDelay = Duration.ZERO)
        }

        error.message.assert().isEqualTo("maxDelay must be positive.")
    }

    @Test
    fun `max pending appends should cover a full batch`() {
        val error = assertThrows<IllegalArgumentException> {
            MongoEventStoreBatchOptions(
                maxSize = 128,
                maxPendingAppends = 127,
            )
        }

        error.message.assert().isEqualTo(
            "maxPendingAppends must be greater than or equal to maxSize."
        )
    }
}
