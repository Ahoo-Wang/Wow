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

package me.ahoo.wow.messaging

import me.ahoo.test.asserts.assert
import me.ahoo.wow.runtime.WowRuntime
import me.ahoo.wow.runtime.internal.DefaultRuntimeContext
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.kotlin.test.test
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

class LocalFirstDistributedCopiesTest {

    @Test
    fun `a graceful stop waits for the copies in flight`() {
        val copies = LocalFirstDistributedCopies("copies")
        val inFlight = Sinks.empty<Void>()
        copies.send(inFlight.asMono()) { "message[1]" }
        copies.pending.assert().isEqualTo(1)

        val stopped = copies.stopGracefully().toFuture()
        Thread.sleep(50)
        stopped.isDone.assert().isFalse()

        inFlight.tryEmitEmpty().orThrow()
        stopped.get()
        copies.pending.assert().isZero()
    }

    @Test
    fun `a graceful stop with nothing in flight completes at once`() {
        val copies = LocalFirstDistributedCopies()

        copies.prepare(DefaultRuntimeContext()).test().verifyComplete()
        copies.start()
        copies.stopGracefully().test().verifyComplete()
        copies.toString().assert().isEqualTo("LocalFirstDistributedCopies")
    }

    @Test
    fun `a failed copy is logged and released, never thrown`() {
        val copies = LocalFirstDistributedCopies()

        copies.send(Mono.error(IllegalStateException("broker down"))) { "message[1]" }

        copies.pending.assert().isZero()
    }

    @Test
    fun `a force stop cancels the copies in flight`() {
        val copies = LocalFirstDistributedCopies()
        val cancelled = AtomicBoolean()
        copies.send(Mono.never<Void>().doOnCancel { cancelled.set(true) }) { "message[1]" }

        copies.forceStop()

        cancelled.get().assert().isTrue()
        copies.pending.assert().isZero()
    }

    @Test
    fun `the runtime waits for the copies in flight within its shutdown deadline`() {
        val copies = LocalFirstDistributedCopies()
        val inFlight = Sinks.empty<Void>()
        copies.send(inFlight.asMono()) { "message[1]" }
        val runtime = WowRuntime(
            components = listOf(copies),
            shutdownTimeout = Duration.ofSeconds(5),
            shutdownQuietPeriod = Duration.ZERO,
        )
        runtime.start().block(Duration.ofSeconds(5))

        val stopped = runtime.stopGracefully().toFuture()
        Thread.sleep(50)
        stopped.isDone.assert().isFalse()
        inFlight.tryEmitEmpty().orThrow()

        stopped.get()
        copies.pending.assert().isZero()
    }

    @Test
    fun `the runtime cancels copies still in flight at its shutdown deadline`() {
        val copies = LocalFirstDistributedCopies()
        val cancelled = AtomicBoolean()
        copies.send(Mono.never<Void>().doOnCancel { cancelled.set(true) }) { "message[1]" }
        val runtime = WowRuntime(
            components = listOf(copies),
            shutdownTimeout = Duration.ofMillis(200),
            shutdownQuietPeriod = Duration.ZERO,
        )
        runtime.start().block(Duration.ofSeconds(5))

        runCatching { runtime.stopGracefully().block(Duration.ofSeconds(5)) }

        cancelled.get().assert().isTrue()
    }
}
