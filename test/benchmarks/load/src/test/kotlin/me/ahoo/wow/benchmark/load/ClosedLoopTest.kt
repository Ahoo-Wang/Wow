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

class ClosedLoopTest {
    @Test
    fun `a failing step below an earlier one ends once it has measured long enough`() {
        // 10 s at 500/s with 5% failed, below an earlier step's 1,000/s.
        ClosedLoop.stopsEarly(seconds = 10, ok = 5_000, failed = 263, best = 1_000.0, failAfterSeconds = 10)
            .assert().isTrue()
        // Not before it has measured that long.
        ClosedLoop.stopsEarly(seconds = 9, ok = 4_500, failed = 237, best = 1_000.0, failAfterSeconds = 10)
            .assert().isFalse()
    }

    @Test
    fun `a step that may yet be the saturation point, or fails too little, runs its whole length`() {
        // Failing, but at more than any earlier step did.
        ClosedLoop.stopsEarly(seconds = 10, ok = 12_000, failed = 600, best = 1_000.0, failAfterSeconds = 10)
            .assert().isFalse()
        // The first step has no earlier one to stay below.
        ClosedLoop.stopsEarly(seconds = 10, ok = 5_000, failed = 263, best = 0.0, failAfterSeconds = 10)
            .assert().isFalse()
        // 1% failed is within the limit.
        ClosedLoop.stopsEarly(seconds = 10, ok = 4_950, failed = 50, best = 1_000.0, failAfterSeconds = 10)
            .assert().isFalse()
    }

    @Test
    fun `without a fail-after no step ends early`() {
        ClosedLoop.stopsEarly(seconds = 29, ok = 100, failed = 10_000, best = 1_000.0, failAfterSeconds = null)
            .assert().isFalse()
    }

    @Test
    fun `a step that measured less than its length ended early`() {
        val step = ClosedLoop.Step(
            concurrency = 4_096,
            ok = 5_000,
            errors = mapOf("http-429" to 300L),
            seconds = 10,
            latency = Histogram(),
        )
        step.endedEarly(stepSeconds = 30).assert().isTrue()
        step.throughput.assert().isEqualTo(500.0)
        step.copy(seconds = 30).endedEarly(stepSeconds = 30).assert().isFalse()
    }
}
