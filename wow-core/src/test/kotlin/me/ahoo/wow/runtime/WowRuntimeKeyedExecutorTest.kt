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

package me.ahoo.wow.runtime

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import me.ahoo.test.asserts.assert
import me.ahoo.wow.execution.KeyedExecutor
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

/** The runtime owns its [KeyedExecutor]: every stop path disposes it, and a force stop does so before components. */
class WowRuntimeKeyedExecutorTest {
    private fun runtime(component: RuntimeComponent, keyedExecutor: KeyedExecutor): WowRuntime =
        WowRuntime(listOf(component), Duration.ofSeconds(5), Duration.ZERO, keyedExecutor)

    @Test
    fun `stopping a runtime that never started closes its executor`() {
        val keyedExecutor = KeyedExecutor(workers = 1, name = "runtime-new-stop")
        val runtime = runtime(ExecutorProbe(keyedExecutor), keyedExecutor)

        StepVerifier.create(runtime.stopGracefully()).verifyComplete()

        keyedExecutor.isDisposed.assert().isTrue()
    }

    @Test
    fun `a graceful stop closes the executor`() {
        val keyedExecutor = KeyedExecutor(workers = 1, name = "runtime-graceful-stop")
        val runtime = runtime(ExecutorProbe(keyedExecutor), keyedExecutor)
        runtime.start().block()
        keyedExecutor.isDisposed.assert().isFalse()

        StepVerifier.create(runtime.stopGracefully()).verifyComplete()

        keyedExecutor.isDisposed.assert().isTrue()
    }

    @Test
    fun `a force stop force-closes the executor before forcing components`() {
        val keyedExecutor = KeyedExecutor(workers = 1, name = "runtime-force-stop")
        val probe = ExecutorProbe(keyedExecutor)
        val runtime = runtime(probe, keyedExecutor)
        runtime.start().block()

        runtime.forceStop()

        probe.forcedWhenComponentForced.get().assert().isTrue()
        keyedExecutor.isDisposed.assert().isTrue()
    }

    @Test
    fun `a failed graceful stop force-closes the executor before forcing components`() {
        val keyedExecutor = KeyedExecutor(workers = 1, name = "runtime-failed-stop")
        val failure = IllegalStateException("stop")
        val probe = ExecutorProbe(keyedExecutor, stopFailure = failure)
        val runtime = runtime(probe, keyedExecutor)
        runtime.start().block()

        StepVerifier.create(runtime.stopGracefully())
            .expectErrorMatches { it === failure }
            .verify(Duration.ofSeconds(5))

        probe.forcedWhenComponentForced.get().assert().isTrue()
        keyedExecutor.isDisposed.assert().isTrue()
    }

    @Test
    fun `a runtime failure disposes the executor`() {
        val keyedExecutor = KeyedExecutor(workers = 1, name = "runtime-failure")
        val failure = IllegalStateException("runtime")
        val probe = ExecutorProbe(keyedExecutor)
        val runtime = runtime(probe, keyedExecutor)
        runtime.start().block()

        checkNotNull(probe.runtimeContext).reportFailure(failure)

        StepVerifier.create(runtime.terminationSignal)
            .expectErrorMatches { it === failure }
            .verify(Duration.ofSeconds(5))
        keyedExecutor.isDisposed.assert().isTrue()
    }

    @Test
    fun `starting with the removed wow parallelism property logs one warning`() {
        val keyedExecutor = KeyedExecutor(workers = 1, name = "runtime-parallelism")
        val runtime = runtime(ExecutorProbe(keyedExecutor), keyedExecutor)
        val logger = LoggerFactory.getLogger(WowRuntime::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        val previous = System.setProperty(REMOVED_PARALLELISM_PROPERTY, "8")
        try {
            runtime.start().block()
            runtime.isRunning.assert().isTrue()
            appender.list.filter { it.level == Level.WARN && it.formattedMessage.contains(REMOVED_PARALLELISM_PROPERTY) }
                .assert().hasSize(1)
        } finally {
            logger.detachAppender(appender)
            if (previous == null) {
                System.clearProperty(REMOVED_PARALLELISM_PROPERTY)
            } else {
                System.setProperty(REMOVED_PARALLELISM_PROPERTY, previous)
            }
            runtime.stopGracefully().block(Duration.ofSeconds(5))
        }
    }

    private class ExecutorProbe(
        private val keyedExecutor: KeyedExecutor,
        private val stopFailure: RuntimeException? = null,
    ) : RuntimeComponent {
        var runtimeContext: RuntimeContext? = null
        val forcedWhenComponentForced = AtomicReference<Boolean>()

        override fun prepare(runtimeContext: RuntimeContext): Mono<Void> =
            Mono.fromRunnable { this.runtimeContext = runtimeContext }

        override fun start() = Unit

        override fun stopGracefully(): Mono<Void> = stopFailure?.let { Mono.error(it) } ?: Mono.empty()

        override fun forceStop() {
            forcedWhenComponentForced.set(keyedExecutor.dispatchWorkers.forced)
        }
    }
}
