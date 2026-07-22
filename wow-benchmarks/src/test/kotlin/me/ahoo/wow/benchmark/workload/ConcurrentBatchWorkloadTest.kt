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
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import reactor.test.publisher.TestPublisher

class ConcurrentBatchWorkloadTest {
    @Test
    fun `should execute the configured number of operations`() {
        val workload = ConcurrentBatchWorkload(size = 8, concurrency = 2)
        var executions = 0

        StepVerifier.create(workload.execute { Mono.fromRunnable<Void> { executions++ } })
            .expectNext(8)
            .verifyComplete()

        executions.assert().isEqualTo(8)
    }

    @Test
    fun `should bound in flight operations by concurrency`() {
        val workload = ConcurrentBatchWorkload(size = 5, concurrency = 2)
        val publishers = List(workload.size) { TestPublisher.create<Void>() }
        var subscriptions = 0

        StepVerifier.create(workload.execute { publishers[subscriptions++].mono() })
            .then {
                subscriptions.assert().isEqualTo(2)
                publishers[0].complete()
                subscriptions.assert().isEqualTo(3)
                publishers[1].complete()
                subscriptions.assert().isEqualTo(4)
                publishers[2].complete()
                subscriptions.assert().isEqualTo(5)
                publishers[3].complete()
                publishers[4].complete()
            }.expectNext(5)
            .verifyComplete()
    }

    @Test
    fun `should reject non positive options`() {
        assertThrows<IllegalArgumentException> {
            ConcurrentBatchWorkload(size = 0, concurrency = 1)
        }
        assertThrows<IllegalArgumentException> {
            ConcurrentBatchWorkload(size = 1, concurrency = 0)
        }
    }
}
