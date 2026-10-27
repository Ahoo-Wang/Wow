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

package me.ahoo.wow.runtime.subscription

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The retries of a failing log read or store write (decision D195, `wow.subscription.read-retry.*`): never given up,
 * the backoff growing until `max-attempts` are spent, then at its maximum.
 */
class ReadRetryTest {
    private val policy = RetryPolicy(maxAttempts = 3, initialBackoff = 100.milliseconds, maxBackoff = 5.seconds)

    @Test
    fun `the backoff should grow until the attempts are spent, then stay at its maximum`() {
        (1..5).map { readBackoff(policy, it) }.assert().containsExactly(
            100.milliseconds,
            200.milliseconds,
            5.seconds,
            5.seconds,
            5.seconds,
        )
        // One attempt: every failure is lasting at once.
        readBackoff(policy.copyWith(maxAttempts = 1), 1).assert().isEqualTo(5.seconds)
    }

    @OptIn(ExperimentalCoroutinesApi::class) // The virtual time of the test.
    @Test
    fun `a read that keeps failing past its attempts should still be retried until it succeeds`(): Unit = runTest {
        var calls = 0
        val result = retryForever(policy, { "read" }) {
            calls++
            check(calls > 5) { "Failure $calls." }
            "read"
        }
        result.assert().isEqualTo("read")
        calls.assert().isEqualTo(6)
        // 100ms and 200ms while attempts were left, then 5s for each of the three failures after them.
        currentTime.assert().isEqualTo(15_300L)
    }

    @Test
    fun `a cancelled read should not be retried`(): Unit = runTest {
        var calls = 0
        assertThrows<CancellationException> {
            retryForever(policy, { "read" }) {
                calls++
                throw CancellationException("stopped")
            }
        }
        calls.assert().isEqualTo(1)
    }
}
