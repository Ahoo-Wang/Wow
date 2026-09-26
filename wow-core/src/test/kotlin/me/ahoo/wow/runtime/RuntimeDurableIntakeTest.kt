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

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import reactor.core.Disposable
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

class RuntimeDurableIntakeTest {

    @Test
    fun `graceful shutdown completes under sustained external traffic`() {
        val component = SustainedTrafficComponent()
        val runtime = WowRuntime(
            components = listOf(component),
            shutdownTimeout = Duration.ofSeconds(10),
            shutdownQuietPeriod = Duration.ofMillis(200),
        )
        runtime.start().block()
        component.awaitTraffic()

        StepVerifier.create(runtime.stopGracefully())
            .expectComplete()
            .verify(Duration.ofSeconds(3))

        component.admissionOpenWhenSuspended.assert().isTrue()
    }

    /**
     * Admits a short activity every 20 ms, far more often than the quiet period,
     * until its durable intake is suspended.
     */
    private class SustainedTrafficComponent : RuntimeComponent {
        private lateinit var runtimeContext: RuntimeContext
        private val suspended = AtomicBoolean()
        private val trafficSeen = java.util.concurrent.CountDownLatch(3)
        private var traffic: Disposable? = null

        @Volatile
        var admissionOpenWhenSuspended = false

        override fun prepare(runtimeContext: RuntimeContext): Mono<Void> =
            Mono.fromRunnable { this.runtimeContext = runtimeContext }

        override fun start() {
            traffic = Flux.interval(Duration.ofMillis(20))
                .takeWhile { !suspended.get() }
                .subscribe {
                    val activity = runtimeContext.tryAcquire() ?: return@subscribe
                    trafficSeen.countDown()
                    Mono.delay(Duration.ofMillis(5)).subscribe { activity.close() }
                }
        }

        fun awaitTraffic() {
            trafficSeen.await()
        }

        override fun suspendDurableIntake() {
            admissionOpenWhenSuspended = runtimeContext.tryAcquire()?.also { it.close() } != null
            suspended.set(true)
        }

        override fun stopGracefully(): Mono<Void> = Mono.empty()

        override fun forceStop() {
            traffic?.dispose()
        }
    }
}
