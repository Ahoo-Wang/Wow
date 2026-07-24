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

package me.ahoo.wow.benchmark.scenario

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class BenchmarkSchedulerConfigTest {

    @Test
    fun `should resolve explicit and default scheduler pool sizes`() {
        resolveSchedulerPoolSize("cpu", defaultPoolSize = 14).assert().isEqualTo(14)
        resolveSchedulerPoolSize("4", defaultPoolSize = 14).assert().isEqualTo(4)
    }

    @Test
    fun `should resolve explicit and default stripe counts`() {
        resolveStripeCount("default", defaultStripeCount = 896).assert().isEqualTo(896)
        resolveStripeCount("64", defaultStripeCount = 896).assert().isEqualTo(64)
    }

    @Test
    fun `should reject non-positive or malformed scheduler values`() {
        listOf("", "0", "-1", "four").forEach { value ->
            assertThrows<IllegalArgumentException> {
                resolveSchedulerPoolSize(value, defaultPoolSize = 14)
            }
            assertThrows<IllegalArgumentException> {
                resolveStripeCount(value, defaultStripeCount = 896)
            }
        }
    }
}
