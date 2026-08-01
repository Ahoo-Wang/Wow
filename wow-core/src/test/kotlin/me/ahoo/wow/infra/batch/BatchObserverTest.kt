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

class BatchObserverTest {
    @Test
    fun `same-named coordinators should expose distinct instance ids`() {
        val observations = mutableListOf<BatchObservation>()
        val observer = BatchObserver(observations::add)

        BatchObservationEmitter("same-name", observer, System::nanoTime)
            .requestCancelled()
        BatchObservationEmitter("same-name", observer, System::nanoTime)
            .requestCancelled()

        observations.map(BatchObservation::coordinatorInstanceId)
            .distinct()
            .assert()
            .hasSize(2)
    }

    @Test
    fun `unobserved admission should keep the compact request type`() {
        val admission = BatchAdmission<Int>(capacity = 1, observations = null)
        check(admission.tryAcquire() == null)

        val request = admission.track(1)
        try {
            request.javaClass.assert().isEqualTo(BatchRequest::class.java)
        } finally {
            request.discardAdmission()
        }
    }

    @Test
    fun `composite should isolate observer failures and preserve snapshot order`() {
        val calls = mutableListOf<String>()
        val observers = mutableListOf(
            BatchObserver {
                calls += "first"
                throw IllegalStateException("observer failed")
            },
            BatchObserver { calls += "second" },
        )
        val composite = CompositeBatchObserver(observers)
        observers += BatchObserver { calls += "late" }

        composite.onObservation(
            BatchObservation.AdmissionRejected(
                coordinatorName = "test",
                coordinatorInstanceId = 1,
                reason = BatchAdmissionRejectionReason.LIVE_ITEMS_EXHAUSTED,
                capacity = BatchCapacitySnapshot(
                    sequence = 1,
                    liveItems = 2,
                    queuedItems = 2,
                    liveHighWater = 2,
                    queuedHighWater = 2,
                ),
            ),
        )

        calls.assert().containsExactly("first", "second")
    }
}
