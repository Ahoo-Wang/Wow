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
import reactor.kotlin.test.test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class BatchRequestTest {
    @Test
    fun `successful request transitions should be idempotent`() {
        val fixture = fixture()

        fixture.request.claim().assert().isTrue()
        fixture.request.claim().assert().isFalse()
        fixture.queueReleases.get().assert().isEqualTo(1)

        fixture.request.cancel()
        fixture.request.settle(BatchItemResult.Success)
        fixture.request.settle(BatchItemResult.Failure(IllegalStateException("late")))
        fixture.request.signalSettled()
        fixture.request.signalSettled()

        fixture.request.result.asMono()
            .test()
            .verifyComplete()
        fixture.admissionReleases.get().assert().isEqualTo(1)
        fixture.request.claim().assert().isFalse()
        fixture.request.settleFailureIfUnsettled(IllegalStateException("late"))
            .assert()
            .isFalse()
    }

    @Test
    fun `cancelled request should release each capacity once`() {
        val fixture = fixture()

        fixture.request.cancel()
        fixture.request.cancel()
        fixture.admissionReleases.get().assert().isEqualTo(1)
        fixture.queueReleases.get().assert().isZero()

        fixture.request.settle(BatchItemResult.Success)
        fixture.queueReleases.get().assert().isEqualTo(1)
        fixture.request.claim().assert().isFalse()
        fixture.request.signalSettled()
        fixture.admissionReleases.get().assert().isEqualTo(1)
    }

    @Test
    fun `cancelled request should ignore terminal failure`() {
        val fixture = fixture()

        fixture.request.cancel()
        fixture.request.settleFailureIfUnsettled(IllegalStateException("ignored"))
            .assert()
            .isFalse()

        fixture.admissionReleases.get().assert().isEqualTo(1)
        fixture.queueReleases.get().assert().isEqualTo(1)
    }

    @Test
    fun `queued request may be failed before it reaches a lane`() {
        val fixture = fixture()
        val failure = IllegalStateException("failed")

        fixture.request.settle(BatchItemResult.Success)
        fixture.request.signalSettled()
        fixture.admissionReleases.get().assert().isZero()

        fixture.request.settleFailureIfUnsettled(failure)
            .assert()
            .isTrue()
        fixture.request.settleFailureIfUnsettled(IllegalStateException("late"))
            .assert()
            .isFalse()
        fixture.request.signalSettled()

        fixture.request.result.asMono()
            .test()
            .expectErrorMatches { it === failure }
            .verify()
        fixture.admissionReleases.get().assert().isEqualTo(1)
        fixture.queueReleases.get().assert().isEqualTo(1)
    }

    @Test
    fun `discarded request should release reservation once`() {
        val fixture = fixture()

        fixture.request.discardAdmission()
        fixture.request.discardAdmission()

        fixture.admissionReleases.get().assert().isEqualTo(1)
        fixture.queueReleases.get().assert().isEqualTo(1)
        fixture.request.claim().assert().isFalse()
        fixture.request.settle(BatchItemResult.Success)
        fixture.request.settleFailure(IllegalStateException("late"))
    }

    @Test
    fun `claim should release queue slot before claimed callback failure`() {
        val admissionReleases = AtomicInteger()
        val queueReleases = AtomicInteger()
        val failure = LinkageError("failed")
        val request = object : BatchRequest<Int>(
            value = 1,
            onReleaseAdmission = {
                admissionReleases.incrementAndGet()
            },
            onReleaseQueueSlot = queueReleases::incrementAndGet,
        ) {
            override fun onClaimed(lane: Int) {
                throw failure
            }
        }

        assertThrows<LinkageError> {
            request.claim(lane = 1)
        }.assert().isSameAs(failure)
        queueReleases.get().assert().isEqualTo(1)

        request.settleFailureIfUnsettled(failure).assert().isTrue()
        request.signalSettled()
        admissionReleases.get().assert().isEqualTo(1)
    }

    @Test
    fun `claim racing terminal failure should release each capacity once`() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(1_000) {
                val fixture = fixture()
                val start = CountDownLatch(1)
                val failure = IllegalStateException("failed")
                val claim = executor.submit<Boolean> {
                    start.await()
                    fixture.request.claim()
                }
                val fail = executor.submit<Boolean> {
                    start.await()
                    fixture.request.settleFailureIfUnsettled(failure)
                }

                start.countDown()
                claim.get(1, TimeUnit.SECONDS)
                fail.get(1, TimeUnit.SECONDS).assert().isTrue()
                fixture.request.signalSettled()

                fixture.admissionReleases.get().assert().isEqualTo(1)
                fixture.queueReleases.get().assert().isEqualTo(1)
            }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun fixture(): RequestFixture {
        val admissionReleases = AtomicInteger()
        val queueReleases = AtomicInteger()
        return RequestFixture(
            request = BatchRequest(
                value = 1,
                onReleaseAdmission = {
                    admissionReleases.incrementAndGet()
                },
                onReleaseQueueSlot = queueReleases::incrementAndGet,
            ),
            admissionReleases = admissionReleases,
            queueReleases = queueReleases,
        )
    }

    private data class RequestFixture(
        val request: BatchRequest<Int>,
        val admissionReleases: AtomicInteger,
        val queueReleases: AtomicInteger,
    )
}
