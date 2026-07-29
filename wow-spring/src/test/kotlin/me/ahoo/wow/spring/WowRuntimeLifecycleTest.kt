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

package me.ahoo.wow.spring

import me.ahoo.test.asserts.assert
import me.ahoo.wow.runtime.RuntimeComponent
import me.ahoo.wow.runtime.RuntimeContext
import me.ahoo.wow.runtime.WowRuntime
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class WowRuntimeLifecycleTest {

    @Test
    fun `starts and asynchronously stops one complete runtime`() {
        val starts = AtomicInteger()
        val stops = AtomicInteger()
        val callback = CountDownLatch(1)
        val runtime = WowRuntime(
            components = listOf(
                object : RuntimeComponent {
                    override fun prepare(runtimeContext: RuntimeContext) = Unit

                    override fun start() {
                        starts.incrementAndGet()
                    }

                    override fun stopGracefully(): Mono<Void> =
                        Mono.fromRunnable {
                            stops.incrementAndGet()
                        }

                    override fun forceStop() = Unit
                }
            ),
            shutdownTimeout = Duration.ofSeconds(1),
            shutdownQuietPeriod = Duration.ZERO,
        )
        val lifecycle = WowRuntimeLifecycle(runtime)

        lifecycle.start()
        lifecycle.stop(callback::countDown)

        callback.await(1, TimeUnit.SECONDS).assert().isTrue()
        starts.get().assert().isEqualTo(1)
        stops.get().assert().isEqualTo(1)
        lifecycle.isRunning.assert().isFalse()
        lifecycle.isPauseable.assert().isFalse()
        lifecycle.phase.assert().isEqualTo(WOW_RUNTIME_PHASE)
    }

    @Test
    fun `unexpected runtime failure is delegated once`() {
        lateinit var preparedContext: RuntimeContext
        val callback = CountDownLatch(1)
        val callbackFailure = AtomicReference<Throwable>()
        val component = object : RuntimeComponent {
            override fun prepare(runtimeContext: RuntimeContext) {
                preparedContext = runtimeContext
            }

            override fun start() = Unit

            override fun stopGracefully(): Mono<Void> = Mono.empty()

            override fun forceStop() = Unit
        }
        val runtime = WowRuntime(
            components = listOf(component),
            shutdownTimeout = Duration.ofSeconds(1),
            shutdownQuietPeriod = Duration.ZERO,
        )
        val lifecycle = WowRuntimeLifecycle(
            wowRuntime = runtime,
            unexpectedTerminationExecutor = Executor(Runnable::run),
        ) { failure ->
            callbackFailure.set(failure)
            callback.countDown()
        }
        val failure = IllegalStateException("runtime failed")

        lifecycle.start()
        preparedContext.reportFailure(failure)

        callback.await(1, TimeUnit.SECONDS).assert().isTrue()
        callbackFailure.get().assert().isSameAs(failure)
        lifecycle.isRunning.assert().isFalse()
    }
}
