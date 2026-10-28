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

package me.ahoo.wow.benchmark.load

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration

class InFlightCapTest {
    @Test
    fun `a low rate is bound by what it sends within one request's timeout`() {
        val cap = InFlightCap.of(rate = 50.0, timeout = Duration.ofSeconds(30), targets = 1)
        cap.cap.assert().isEqualTo(1_500)
        cap.boundBy.assert().isEqualTo(InFlightCap.BY_RATE)
        // A fraction of an operation rounds up: one sent at that rate may still be in flight.
        InFlightCap.of(rate = 0.5, timeout = Duration.ofMillis(1_500), targets = 1).cap.assert().isEqualTo(1)
        InFlightCap.of(rate = 10.0, timeout = Duration.ofMillis(150), targets = 1).cap.assert().isEqualTo(2)
    }

    @Test
    fun `a high rate is clamped to the per-target ceiling, which grows with the targets`() {
        val one = InFlightCap.of(rate = 2_000.0, timeout = Duration.ofSeconds(30), targets = 1)
        one.cap.assert().isEqualTo(InFlightCap.PER_TARGET)
        one.boundBy.assert().isEqualTo(InFlightCap.BY_CEILING)
        InFlightCap.of(rate = 2_000.0, timeout = Duration.ofSeconds(30), targets = 3).cap
            .assert().isEqualTo(3 * InFlightCap.PER_TARGET)
        // At the ceiling exactly, the rate is the bound.
        InFlightCap.of(rate = 100.0, timeout = Duration.ofSeconds(20), targets = 1, perTarget = 2_000).boundBy
            .assert().isEqualTo(InFlightCap.BY_RATE)
        InFlightCap.of(rate = 100.0, timeout = Duration.ofSeconds(20), targets = 1, perTarget = 1_999).boundBy
            .assert().isEqualTo(InFlightCap.BY_CEILING)
    }

    @Test
    fun `a cap is refused for no rate, no target or no room`() {
        assertThrows<IllegalArgumentException> { InFlightCap.of(0.0, Duration.ofSeconds(1), 1) }
        assertThrows<IllegalArgumentException> { InFlightCap.of(1.0, Duration.ofSeconds(1), 0) }
        assertThrows<IllegalArgumentException> { InFlightCap.of(1.0, Duration.ofSeconds(1), 1, perTarget = 0) }
        InFlightCap.of(
            1.0,
            Duration.ofSeconds(1),
            1
        ).toString().assert().isEqualTo("InFlightCap(cap=1, boundBy=rate x request timeout)")
    }
}
