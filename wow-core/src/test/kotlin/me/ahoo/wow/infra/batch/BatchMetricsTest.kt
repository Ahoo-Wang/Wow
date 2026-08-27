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

import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.config.MeterFilter
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import me.ahoo.test.asserts.assert
import me.ahoo.wow.metrics.WowMetrics
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

class BatchMetricsTest {
    @Test
    fun `disabled metrics should keep every operation as a no-op`() {
        val registry = SimpleMeterRegistry()
        val metrics = BatchMetrics("disabled", WowMetrics.NONE)
        val enqueuedAt = metrics.markEnqueued()

        metrics.admissionRejected(BatchAdmissionRejectionReason.LIVE_ITEMS_EXHAUSTED)
        metrics.requestDequeued(lane = 0, enqueuedAt = enqueuedAt)
        assertThrows<IllegalStateException> {
            metrics.batchWriteStarted(
                lane = 0,
                bufferedItems = 2,
                writtenItems = 2,
                windowType = BatchWindowType.FULL,
            )
        }
        metrics.markCloseStarted()
        metrics.coordinatorFailed()
        metrics.closeCompleted(failed = false)

        registry.meters.assert().isEmpty()
    }

    @Test
    fun `batch write completion should be idempotent`() {
        val registry = SimpleMeterRegistry()
        val metrics = BatchMetrics("write-idempotent", WowMetrics(registry))
        val batchWrite = metrics.batchWriteStarted(
            lane = 0,
            bufferedItems = 2,
            writtenItems = 2,
            windowType = BatchWindowType.FULL,
        )

        batchWrite.complete(BatchWriteOutcome.SUCCESS, failedItems = 0)
        batchWrite.complete(BatchWriteOutcome.FAILED, failedItems = 2)

        registry.get("wow.batch.write")
            .tag("coordinator", "write-idempotent")
            .tag("outcome", "success")
            .timer()
            .count()
            .assert()
            .isEqualTo(1)
        registry.find("wow.batch.write")
            .tag("coordinator", "write-idempotent")
            .tag("outcome", "failed")
            .timer()
            .assert()
            .isNull()
    }

    @Test
    fun `close completion should require a start and remain idempotent`() {
        val registry = SimpleMeterRegistry()
        val metrics = BatchMetrics("close-idempotent", WowMetrics(registry))

        metrics.closeCompleted(failed = false)
        registry.meters.assert().isEmpty()

        metrics.markCloseStarted()
        metrics.closeCompleted(failed = false)
        metrics.closeCompleted(failed = true)

        registry.get("wow.batch.close")
            .tag("coordinator", "close-idempotent")
            .tag("outcome", "success")
            .timer()
            .count()
            .assert()
            .isEqualTo(1)
        registry.find("wow.batch.close")
            .tag("coordinator", "close-idempotent")
            .tag("outcome", "failed")
            .timer()
            .assert()
            .isNull()
    }

    @Test
    fun `non-fatal registry failures should be isolated`() {
        val metrics = BatchMetrics(
            "non-fatal",
            WowMetrics(throwingRegistry(AssertionError("failed"))),
        )

        assertDoesNotThrow {
            metrics.admissionRejected(BatchAdmissionRejectionReason.LIVE_ITEMS_EXHAUSTED)
        }
    }

    @Test
    fun `fatal registry failures should propagate`() {
        val failure = LinkageError("failed")
        val metrics = BatchMetrics("fatal", WowMetrics(throwingRegistry(failure)))

        assertThrows<LinkageError> {
            metrics.admissionRejected(BatchAdmissionRejectionReason.LIVE_ITEMS_EXHAUSTED)
        }.assert().isSameAs(failure)
    }

    private fun throwingRegistry(failure: Throwable): SimpleMeterRegistry =
        SimpleMeterRegistry().apply {
            config().meterFilter(
                object : MeterFilter {
                    override fun map(id: Meter.Id): Meter.Id = throw failure
                }
            )
        }
}
