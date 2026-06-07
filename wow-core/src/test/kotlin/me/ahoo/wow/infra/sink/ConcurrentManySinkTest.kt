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

package me.ahoo.wow.infra.sink

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import reactor.core.publisher.Sinks
import reactor.test.StepVerifier
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ConcurrentManySinkTest {

    @Test
    fun `should decorate sink only once`() {
        val sink = Sinks.many().unicast().onBackpressureBuffer<Int>().concurrent()

        sink.concurrent().assert().isSameAs(sink)
    }

    @Test
    fun `should emit many values from concurrent callers without non serialized failures`() {
        val total = 32
        val sink = Sinks.many().unicast().onBackpressureBuffer<Int>().concurrent()
        val executor = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        val done = CountDownLatch(total)
        val results = java.util.concurrent.ConcurrentLinkedQueue<Sinks.EmitResult>()

        StepVerifier.create(sink.asFlux().take(total.toLong()).collectList())
            .then {
                try {
                    repeat(total) { value ->
                        executor.execute {
                            start.await()
                            results.add(sink.tryEmitNext(value))
                            done.countDown()
                        }
                    }
                    start.countDown()
                    done.await(5, TimeUnit.SECONDS).assert().isTrue()
                    results.size.assert().isEqualTo(total)
                    results.all { it == Sinks.EmitResult.OK }.assert().isTrue()
                } finally {
                    executor.shutdownNow()
                    executor.awaitTermination(5, TimeUnit.SECONDS)
                }
            }
            .assertNext { emitted ->
                emitted.size.assert().isEqualTo(total)
                emitted.toSet().size.assert().isEqualTo(total)
            }
            .expectComplete()
            .verify(Duration.ofSeconds(5))
    }
}
