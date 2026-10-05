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

package me.ahoo.wow.command.wait

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import reactor.test.StepVerifier
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * B23: a wait handle emits its signals after releasing its state lock, so a subscriber reacting to a signal never runs
 * under it. Each test has the subscriber wait, while it handles a signal, for another thread to use the same handle:
 * if the signal were emitted under the lock, that thread would block on it and the wait would time out.
 */
class WaitHandleLockTest {
    private fun fromAnotherThread(action: () -> Unit): Boolean =
        runCatching { CompletableFuture.runAsync(action).get(1, TimeUnit.SECONDS) }.isSuccess

    @Test
    fun `a stream subscriber can signal the handle from another thread while it handles a signal`() {
        val handle = DefaultWaitStreamHandle(plan = CommandWait.snapshot("wait-id"), onTerminate = {})
        var otherThreadSignalled = false

        StepVerifier.create(handle.stream())
            .then { handle.next(testSignal(CommandStage.SENT, signalTime = 1)) }
            .assertNext {
                it.stage.assert().isEqualTo(CommandStage.SENT)
                otherThreadSignalled = fromAnotherThread {
                    handle.next(testSignal(CommandStage.PROCESSED, signalTime = 2))
                }
            }
            .assertNext { it.stage.assert().isEqualTo(CommandStage.PROCESSED) }
            .then { handle.cancel() }
            .verifyComplete()

        otherThreadSignalled.assert().isTrue()
    }

    @Test
    fun `a stream subscriber can cancel the handle from another thread while it handles the final signal`() {
        val handle = DefaultWaitStreamHandle(plan = CommandWait.processed("wait-id"), onTerminate = {})
        var otherThreadCancelled = false

        StepVerifier.create(handle.stream())
            .then { handle.next(testSignal(CommandStage.PROCESSED)) }
            .assertNext { otherThreadCancelled = fromAnotherThread { handle.cancel() } }
            .expectComplete()
            .verify(Duration.ofSeconds(5))

        otherThreadCancelled.assert().isTrue()
    }

    @Test
    fun `a last-result subscriber can use the handle from another thread while it handles the result`() {
        val handle = DefaultWaitLastHandle(plan = CommandWait.processed("wait-id"), onTerminate = {})
        var otherThreadSignalled = false

        StepVerifier.create(handle.await())
            .then { handle.next(testSignal(CommandStage.PROCESSED)) }
            .assertNext {
                otherThreadSignalled = fromAnotherThread {
                    handle.next(testSignal(CommandStage.PROCESSED)).assert().isFalse()
                }
            }
            .expectComplete()
            .verify(Duration.ofSeconds(5))

        otherThreadSignalled.assert().isTrue()
    }

    @Test
    fun `a stream delivers a signal the subscriber causes re-entrantly after the one it is handling`() {
        val handle = DefaultWaitStreamHandle(plan = CommandWait.snapshot("wait-id"), onTerminate = {})
        val delivered = mutableListOf<CommandStage>()

        StepVerifier.create(handle.stream())
            .then { handle.next(testSignal(CommandStage.SENT, signalTime = 1)) }
            .assertNext {
                delivered += it.stage
                handle.next(testSignal(CommandStage.PROCESSED, signalTime = 2)).assert().isTrue()
                delivered.assert().containsExactly(CommandStage.SENT)
            }
            .assertNext { delivered += it.stage }
            .then { handle.cancel() }
            .verifyComplete()

        delivered.assert().containsExactly(CommandStage.SENT, CommandStage.PROCESSED)
    }
}
