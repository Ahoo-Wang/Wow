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

package me.ahoo.wow.runtime.health

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.kernel.memory.InMemoryTimerStore
import me.ahoo.wow.runtime.node.BackendFactory
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.spi.timer.Timer
import me.ahoo.wow.spi.timer.TimerStore
import org.junit.jupiter.api.Test

/**
 * A loop of a running node that ends (RT-5): the node's liveness is `DOWN` for good, naming the loop, while the node
 * holds its partitions. Before, an error that escaped the timer loop's round ended it silently, every timer of the node
 * stopping with the liveness `UP`.
 */
class EndedLoopLivenessTest : NodeHealthFixtures() {
    /** Once broken, a read of the timers due fails with an error no round catches. */
    @Volatile
    private var broken = false

    private val inner = InMemoryTimerStore()
    private val timers = object : TimerStore by inner {
        override suspend fun due(partitions: Set<Int>, until: Long, perPartition: Int): List<Timer> {
            if (broken) {
                throw NotImplementedError("The timers due are not read yet.")
            }
            return inner.due(partitions, until, perPartition)
        }
    }

    @Test
    fun `a timer loop that meets an error should fail the liveness for good, naming the loop`(): Unit = runBlocking {
        val node = node(backends = BackendFactory { backends.backends(it).copyWith(timers = timers) })
        val health = health(node)
        node.start()
        start(health)
        health.liveness().status.assert().isEqualTo(HealthStatus.UP)

        broken = true

        eventually { health.liveness().status == HealthStatus.DOWN }
        val liveness = health.liveness()
        (liveness.details[ENDED] as Map<*, *>).let {
            it["component"].assert().isEqualTo("timer")
            it["name"].assert().isEqualTo("timers")
            it["loop"].assert().isEqualTo("timers")
            it["error"].assert().isEqualTo(NotImplementedError::class.java.name)
        }
        liveness.details["endedLoops"].assert().isEqualTo(1)
        // The memory is sound: the node holds its partitions, not fatal, and runs on.
        node.fatals.isFatal.assert().isFalse()
        node.state.assert().isEqualTo(ServiceNode.State.RUNNING)
        // For good: the error is gone, the loop is not.
        broken = false
        health.liveness().status.assert().isEqualTo(HealthStatus.DOWN)
    }
}
