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

package me.ahoo.wow.reactor

import me.ahoo.test.asserts.assert
import me.ahoo.wow.infra.lifecycle.Lifecycle
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class LifecycleTest {

    @Test
    fun `start can transition a lifecycle implementation to started`() {
        val lifecycle = RecordingLifecycle()

        lifecycle.start()

        lifecycle.started.get().assert().isTrue()
    }

    @Test
    fun `stop delegates to stopGracefully and blocks until completion`() {
        val lifecycle = RecordingLifecycle()

        lifecycle.stop(Duration.ofSeconds(1))

        lifecycle.stopCount.get().assert().isOne()
    }

    @Test
    fun `stopGracefully exposes deterministic completion`() {
        val lifecycle = RecordingLifecycle()

        StepVerifier.create(lifecycle.stopGracefully())
            .verifyComplete()

        lifecycle.stopCount.get().assert().isOne()
    }
}

private class RecordingLifecycle : Lifecycle {
    val started = AtomicBoolean(false)
    val stopCount = AtomicInteger()

    override fun start() {
        started.set(true)
    }

    override fun stopGracefully(): Mono<Void> =
        Mono.fromRunnable {
            stopCount.incrementAndGet()
        }
}
