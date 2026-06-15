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

package me.ahoo.wow.webflux.route.policy

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.test.test
import java.time.Duration

class BatchExecutionPolicyTest {

    @Test
    fun `should use default execution options`() {
        val policy = BatchExecutionPolicy()

        policy.concurrency.assert().isOne()
        policy.prefetch.assert().isOne()
    }

    @Test
    fun `should use configured execution options`() {
        val policy = BatchExecutionPolicy(concurrency = 4, prefetch = 8)

        policy.concurrency.assert().isEqualTo(4)
        policy.prefetch.assert().isEqualTo(8)
    }

    @Test
    fun `should reject non positive execution options`() {
        assertThrows<IllegalArgumentException> {
            BatchExecutionPolicy(concurrency = 0)
        }
        assertThrows<IllegalArgumentException> {
            BatchExecutionPolicy(prefetch = 0)
        }
    }

    @Test
    fun `should preserve source order while mapping concurrently`() {
        val policy = BatchExecutionPolicy(concurrency = 2, prefetch = 1)

        policy.apply(Flux.just(1, 2, 3)) { value ->
            Mono.just(value)
                .delayElement(Duration.ofMillis((4 - value) * 10L))
        }.test()
            .expectNext(1, 2, 3)
            .verifyComplete()
    }
}
