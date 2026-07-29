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
import org.junit.jupiter.api.assertThrows
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Compile- and behavior-level coverage for the public runtime extension SPI
 * from a downstream Gradle module.
 */
class RuntimeContextSpiTest {

    @Test
    fun `custom lifecycle participates in global activity and intake close`() {
        val component = ContextAwareLifecycle()
        val runtime = WowRuntime(
            components = listOf(component),
            shutdownTimeout = Duration.ofSeconds(10),
            shutdownQuietPeriod = Duration.ZERO,
        )
        runtime.start().block()
        val activity = component.runtimeContext.tryAcquire()
        activity.assert().isNotNull()

        try {
            val termination = runtime.stopGracefully().toFuture()

            termination.isDone.assert().isFalse()
            activity!!.close()
            termination.get(5, TimeUnit.SECONDS)
            component.closeActionCount.get().assert().isOne()
            component.forceStopCount.get().assert().isZero()
        } finally {
            activity?.close()
            runtime.forceStop()
        }
    }

    @Test
    fun `custom lifecycle can report a fatal runtime failure`() {
        val component = ContextAwareLifecycle()
        val runtimeFailure = IllegalStateException("custom-runtime-failure")
        val runtime = WowRuntime(
            components = listOf(component),
            shutdownTimeout = Duration.ofSeconds(1),
            shutdownQuietPeriod = Duration.ZERO,
        )
        runtime.start().block()

        component.runtimeContext.reportFailure(runtimeFailure)

        val error = assertThrows<ExecutionException> {
            runtime.terminationSignal.toFuture().get(1, TimeUnit.SECONDS)
        }
        error.cause.assert().isSameAs(runtimeFailure)
        component.forceStopCount.get().assert().isOne()
    }

    private class ContextAwareLifecycle : RuntimeComponent {
        lateinit var runtimeContext: RuntimeContext
        val closeActionCount = AtomicInteger()
        val forceStopCount = AtomicInteger()
        override fun prepare(runtimeContext: RuntimeContext) {
            this.runtimeContext = runtimeContext
        }

        override fun start() = Unit

        override fun quiesce() {
            closeActionCount.incrementAndGet()
        }

        override fun stopGracefully(): Mono<Void> = Mono.empty()

        override fun forceStop() {
            forceStopCount.incrementAndGet()
        }
    }
}
