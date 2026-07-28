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
import reactor.kotlin.test.test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class BatchRequestTest {

    @Test
    fun `force detach helps admission release after cancel transition`() {
        val admission = BlockingRelease()
        val queue = ImmediateRelease()
        val request = request(admission::release, queue::release)
        val executor = Executors.newSingleThreadExecutor()

        try {
            val cancellation = executor.submit(request::cancel)
            admission.firstInvocationEntered.await(1, TimeUnit.SECONDS).assert().isTrue()

            request.forceDetach(IllegalStateException("forced")).assert().isNull()

            admission.owned.get().assert().isFalse()
            queue.owned.get().assert().isFalse()
            admission.actualReleases.get().assert().isOne()
            queue.actualReleases.get().assert().isOne()
            admission.releaseFirstInvocation.countDown()
            cancellation.get(1, TimeUnit.SECONDS)
        } finally {
            admission.releaseFirstInvocation.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `force detach helps queue release after claim transition`() {
        val admission = ImmediateRelease()
        val queue = BlockingRelease()
        val request = request(admission::release, queue::release)
        val executor = Executors.newSingleThreadExecutor()

        try {
            val claim = executor.submit<Boolean>(request::claim)
            queue.firstInvocationEntered.await(1, TimeUnit.SECONDS).assert().isTrue()

            val forcedSignal = request.forceDetach(IllegalStateException("forced"))

            forcedSignal.assert().isNotNull()
            admission.owned.get().assert().isFalse()
            queue.owned.get().assert().isFalse()
            admission.actualReleases.get().assert().isOne()
            queue.actualReleases.get().assert().isOne()
            queue.releaseFirstInvocation.countDown()
            claim.get(1, TimeUnit.SECONDS).assert().isTrue()
        } finally {
            queue.releaseFirstInvocation.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `force detach helps ownership release after settled signal transition`() {
        val admission = BlockingRelease()
        val queue = ImmediateRelease()
        val request = request(admission::release, queue::release)
        request.claim().assert().isTrue()
        request.settle(BatchItemResult.Success)
        val executor = Executors.newSingleThreadExecutor()

        try {
            val signal = executor.submit(request::signalSettled)
            admission.firstInvocationEntered.await(1, TimeUnit.SECONDS).assert().isTrue()

            request.forceDetach(IllegalStateException("forced")).assert().isNull()

            admission.owned.get().assert().isFalse()
            queue.owned.get().assert().isFalse()
            admission.actualReleases.get().assert().isOne()
            queue.actualReleases.get().assert().isOne()
            admission.releaseFirstInvocation.countDown()
            signal.get(1, TimeUnit.SECONDS)
        } finally {
            admission.releaseFirstInvocation.countDown()
            executor.shutdownNow()
        }
    }

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
    fun `force detach should release capacity before notifying a subscriber`() {
        val fixture = fixture()
        val failure = IllegalStateException("forced")

        fixture.request.claim().assert().isTrue()
        val signal = fixture.request.forceDetach(failure)
        signal.assert().isNotNull()

        fixture.admissionReleases.get().assert().isEqualTo(1)
        fixture.queueReleases.get().assert().isEqualTo(1)
        fixture.request.claim().assert().isFalse()
        fixture.request.result.asMono()
            .test()
            .then { signal!!.invoke() }
            .expectErrorMatches { it === failure }
            .verify()
        fixture.admissionReleases.get().assert().isEqualTo(1)
    }

    @Test
    fun `force detach should preserve an already settled outcome`() {
        val fixture = fixture()

        fixture.request.claim().assert().isTrue()
        fixture.request.settle(BatchItemResult.Success)
        val signal = fixture.request.forceDetach(IllegalStateException("late"))

        fixture.request.result.asMono()
            .test()
            .then { signal!!.invoke() }
            .verifyComplete()
        fixture.admissionReleases.get().assert().isEqualTo(1)
        fixture.queueReleases.get().assert().isEqualTo(1)
    }

    @Test
    fun `close timeout detach should leave settled result on its accepted dispatch path`() {
        val fixture = fixture()

        fixture.request.claim().assert().isTrue()
        fixture.request.settle(BatchItemResult.Success)

        fixture.request.forceDetachIfUnsettled(IllegalStateException("late"))
            .assert()
            .isNull()
        fixture.admissionReleases.get().assert().isZero()
        fixture.queueReleases.get().assert().isEqualTo(1)

        fixture.request.signalSettled()

        fixture.request.result.asMono()
            .test()
            .verifyComplete()
        fixture.admissionReleases.get().assert().isEqualTo(1)
        fixture.queueReleases.get().assert().isEqualTo(1)
    }

    @Test
    fun `force detach should discard a cancelled queued request`() {
        val fixture = fixture()

        fixture.request.cancel()
        fixture.request.forceDetach(IllegalStateException("ignored"))
            .assert()
            .isNull()

        fixture.admissionReleases.get().assert().isEqualTo(1)
        fixture.queueReleases.get().assert().isEqualTo(1)
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
        val admissionOwned = AtomicBoolean(true)
        val queueOwned = AtomicBoolean(true)
        return RequestFixture(
            request = BatchRequest(
                value = 1,
                onReleaseAdmission = {
                    if (admissionOwned.compareAndSet(true, false)) {
                        admissionReleases.incrementAndGet()
                    }
                },
                onReleaseQueueSlot = {
                    if (queueOwned.compareAndSet(true, false)) {
                        queueReleases.incrementAndGet()
                    }
                },
            ),
            admissionReleases = admissionReleases,
            queueReleases = queueReleases,
        )
    }

    private fun request(
        releaseAdmission: () -> Unit,
        releaseQueue: () -> Unit,
    ): BatchRequest<Int> =
        BatchRequest(
            value = 1,
            onReleaseAdmission = { releaseAdmission() },
            onReleaseQueueSlot = { releaseQueue() },
        )

    private data class RequestFixture(
        val request: BatchRequest<Int>,
        val admissionReleases: AtomicInteger,
        val queueReleases: AtomicInteger,
    )

    private open class ImmediateRelease {
        val owned = AtomicBoolean(true)
        val actualReleases = AtomicInteger()

        open fun release() {
            if (owned.compareAndSet(true, false)) {
                actualReleases.incrementAndGet()
            }
        }
    }

    private class BlockingRelease : ImmediateRelease() {
        val firstInvocationEntered = CountDownLatch(1)
        val releaseFirstInvocation = CountDownLatch(1)
        private val invocations = AtomicInteger()

        override fun release() {
            if (invocations.incrementAndGet() == 1) {
                firstInvocationEntered.countDown()
                releaseFirstInvocation.await()
            }
            super.release()
        }
    }
}
