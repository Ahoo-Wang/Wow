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

package me.ahoo.wow.infra.lifecycle

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

class GracefullyStoppableTest {

    @Test
    fun `stop delegates to stopGracefully`() {
        val stoppable = RecordingGracefullyStoppable()

        stoppable.stop()

        stoppable.stopCount.get().assert().isOne()
    }

    @Test
    fun `stop with timeout delegates to stopGracefully`() {
        val stoppable = RecordingGracefullyStoppable()

        stoppable.stop(Duration.ofSeconds(1))

        stoppable.stopCount.get().assert().isOne()
    }

    @Test
    fun `close delegates to the default stop operation`() {
        val stoppable = RecordingGracefullyStoppable()

        stoppable.close()

        stoppable.stopCount.get().assert().isOne()
    }
}

private class RecordingGracefullyStoppable : GracefullyStoppable {
    val stopCount = AtomicInteger()

    override fun stopGracefully(): Mono<Void> =
        Mono.fromRunnable {
            stopCount.incrementAndGet()
        }
}
