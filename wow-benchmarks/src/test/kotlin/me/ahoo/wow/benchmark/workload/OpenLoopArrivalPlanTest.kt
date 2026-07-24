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

package me.ahoo.wow.benchmark.workload

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.TimeUnit

class OpenLoopArrivalPlanTest {
    @Test
    fun `should derive exact scheduled arrivals without materializing them`() {
        val plan = OpenLoopArrivalPlan(
            ratePerSecond = 3,
            producerCount = 2,
            durationNanos = TimeUnit.MILLISECONDS.toNanos(1500),
        )

        plan.arrivalCount.assert().isEqualTo(5)
        (0L until plan.arrivalCount)
            .map(plan::scheduledOffsetNanos)
            .assert()
            .containsExactly(
                0L,
                333_333_333L,
                666_666_666L,
                1_000_000_000L,
                1_333_333_333L,
            )
        plan.scheduledOffsetNanos(plan.arrivalCount)
            .assert()
            .isGreaterThanOrEqualTo(plan.durationNanos)
    }

    @Test
    fun `should partition global arrival sequence across producers`() {
        val plan = OpenLoopArrivalPlan(
            ratePerSecond = 10,
            producerCount = 3,
            durationNanos = TimeUnit.SECONDS.toNanos(1),
        )

        val partitioned = (0 until plan.producerCount)
            .flatMap { producerIndex ->
                buildList {
                    var sequence = plan.firstSequence(producerIndex)
                    while (plan.contains(sequence)) {
                        add(sequence)
                        sequence = plan.nextSequence(sequence)
                    }
                }
            }.sorted()

        partitioned.assert().isEqualTo((0L until 10L).toList())
    }

    @Test
    fun `should reject invalid or overflowing plans`() {
        assertThrows<IllegalArgumentException> {
            OpenLoopArrivalPlan(ratePerSecond = 0, producerCount = 1, durationNanos = 1)
        }
        assertThrows<IllegalArgumentException> {
            OpenLoopArrivalPlan(ratePerSecond = 1, producerCount = 0, durationNanos = 1)
        }
        assertThrows<IllegalArgumentException> {
            OpenLoopArrivalPlan(ratePerSecond = 1, producerCount = 1, durationNanos = 0)
        }
        assertThrows<IllegalArgumentException> {
            OpenLoopArrivalPlan(
                ratePerSecond = Long.MAX_VALUE,
                producerCount = 1,
                durationNanos = Long.MAX_VALUE,
            )
        }
    }
}
