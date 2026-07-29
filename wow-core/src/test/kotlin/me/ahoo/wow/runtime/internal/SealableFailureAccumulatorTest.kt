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

package me.ahoo.wow.runtime.internal

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SealableFailureAccumulatorTest {

    @Test
    fun `seal freezes the primary and suppressed failures`() {
        val failures = SealableFailureAccumulator()
        val primary = IllegalStateException("primary")
        val beforeSeal = IllegalArgumentException("before-seal")
        val afterSeal = UnsupportedOperationException("after-seal")

        failures.record(primary).assert().isSameAs(primary)
        failures.record(beforeSeal).assert().isSameAs(primary)
        failures.seal().assert().isSameAs(primary)

        failures.record(afterSeal).assert().isSameAs(primary)

        failures.failure.assert().isSameAs(primary)
        primary.suppressedExceptions.assert().containsExactly(beforeSeal)
    }

    @Test
    fun `concurrent reports cannot mutate failure after seal returns`() {
        val executor = Executors.newFixedThreadPool(8)
        try {
            repeat(1_000) {
                val failures = SealableFailureAccumulator()
                val primary = IllegalStateException("primary")
                val concurrent = IllegalArgumentException("concurrent")
                val late = UnsupportedOperationException("late")
                failures.record(primary)

                val reports = (1..8).map {
                    CompletableFuture.runAsync(
                        { failures.record(concurrent) },
                        executor,
                    )
                }
                val seal = CompletableFuture.supplyAsync(failures::seal, executor)
                CompletableFuture.allOf(*reports.toTypedArray(), seal)
                    .get(1, TimeUnit.SECONDS)
                val sealedSuppressed = primary.suppressedExceptions.toList()

                failures.record(late)
                primary.suppressedExceptions.toList().assert().isEqualTo(sealedSuppressed)
                sealedSuppressed.count { it === concurrent }.assert().isLessThanOrEqualTo(1)
            }
        } finally {
            executor.shutdownNow()
        }
    }
}
