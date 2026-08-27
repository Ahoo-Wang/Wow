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

package me.ahoo.wow.benchmark.infrastructure

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration

class StorageBatchTuningOptionsTest {
    @Test
    fun `should parse batch size and microsecond delay`() {
        StorageBatchTuningOptions.parse("256x500us")
            .assert()
            .isEqualTo(
                StorageBatchTuningOptions(
                    maxSize = 256,
                    maxDelay = Duration.ofNanos(500_000),
                )
            )
    }

    @Test
    fun `should reject malformed tuning options`() {
        listOf(
            "",
            "128",
            "0x1000us",
            "128x0us",
            "-1x1000us",
            "128x-1us",
            "128x1ms",
            "2147483648x1000us",
            "128x9223372036854775807us",
        ).forEach { value ->
            assertThrows<IllegalArgumentException> {
                StorageBatchTuningOptions.parse(value)
            }
        }
    }
}
