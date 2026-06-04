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

package me.ahoo.wow.tck.container

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration

class ElasticsearchAuthenticationWaiterTest {

    @Test
    fun retryWhenAuthenticationIsNotReady() {
        val statuses = ArrayDeque(listOf(401, 200))
        var sleepTimes = 0

        ElasticsearchAuthenticationWaiter.waitUntilAuthenticated(
            timeout = Duration.ofSeconds(10),
            retryInterval = Duration.ofMillis(1),
            authenticate = { statuses.removeFirst() },
            sleep = {
                sleepTimes++
            },
        )

        sleepTimes.assert().isOne()
    }

    @Test
    fun retryWhenProbeThrowsException() {
        var attempts = 0

        ElasticsearchAuthenticationWaiter.waitUntilAuthenticated(
            timeout = Duration.ofSeconds(10),
            retryInterval = Duration.ofMillis(1),
            authenticate = {
                attempts++
                if (attempts == 1) {
                    error("Elasticsearch is not ready.")
                }
                200
            },
            sleep = {},
        )

        attempts.assert().isEqualTo(2)
    }

    @Test
    fun throwWhenAuthenticationTimeout() {
        var nanoTime = 0L

        val error = assertThrows<IllegalStateException> {
            ElasticsearchAuthenticationWaiter.waitUntilAuthenticated(
                timeout = Duration.ofMillis(2),
                retryInterval = Duration.ofMillis(1),
                authenticate = { 401 },
                sleep = {
                    nanoTime += it.toNanos()
                },
                nanoTime = { nanoTime },
            )
        }

        error.message.assert().isEqualTo(
            "Elasticsearch container did not accept authenticated requests before timeout.",
        )
    }

    @Test
    fun shouldNotSleepBeyondTimeout() {
        var nanoTime = 0L
        val sleepTimes = mutableListOf<Duration>()

        assertThrows<IllegalStateException> {
            ElasticsearchAuthenticationWaiter.waitUntilAuthenticated(
                timeout = Duration.ofMillis(2),
                retryInterval = Duration.ofMillis(10),
                authenticate = { 401 },
                sleep = {
                    sleepTimes.add(it)
                    nanoTime += it.toNanos()
                },
                nanoTime = { nanoTime },
            )
        }

        sleepTimes.assert().containsExactly(Duration.ofMillis(2))
    }

    @Test
    fun shouldRejectInvalidTimeout() {
        val error = assertThrows<IllegalArgumentException> {
            ElasticsearchAuthenticationWaiter.waitUntilAuthenticated(
                timeout = Duration.ZERO,
                authenticate = { 200 },
            )
        }

        error.message.assert().isEqualTo("timeout must be positive.")
    }

    @Test
    fun shouldRejectInvalidRetryInterval() {
        val error = assertThrows<IllegalArgumentException> {
            ElasticsearchAuthenticationWaiter.waitUntilAuthenticated(
                retryInterval = Duration.ZERO,
                authenticate = { 200 },
            )
        }

        error.message.assert().isEqualTo("retryInterval must be positive.")
    }

    @Test
    fun abortWhenAuthenticationIsInterrupted() {
        val error = assertThrows<IllegalStateException> {
            ElasticsearchAuthenticationWaiter.waitUntilAuthenticated(
                timeout = Duration.ofSeconds(10),
                retryInterval = Duration.ofMillis(1),
                authenticate = { throw InterruptedException("cancelled") },
                sleep = {},
            )
        }
        val wasInterrupted = Thread.interrupted()

        error.message.assert().isEqualTo("Interrupted while waiting for Elasticsearch authenticated requests.")
        error.cause.assert().isInstanceOf(InterruptedException::class.java)
        wasInterrupted.assert().isTrue()
    }

    @Test
    fun abortWhenRetrySleepIsInterrupted() {
        val error = assertThrows<IllegalStateException> {
            ElasticsearchAuthenticationWaiter.waitUntilAuthenticated(
                timeout = Duration.ofSeconds(10),
                retryInterval = Duration.ofMillis(1),
                authenticate = { 401 },
                sleep = { throw InterruptedException("cancelled") },
            )
        }
        val wasInterrupted = Thread.interrupted()

        error.message.assert().isEqualTo("Interrupted while waiting for Elasticsearch authenticated requests.")
        error.cause.assert().isInstanceOf(InterruptedException::class.java)
        wasInterrupted.assert().isTrue()
    }
}
