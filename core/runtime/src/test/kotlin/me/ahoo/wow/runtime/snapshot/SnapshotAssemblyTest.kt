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

package me.ahoo.wow.runtime.snapshot

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.kernel.CommandOutcome
import me.ahoo.wow.runtime.state.SharedStates
import me.ahoo.wow.spi.log.LogPosition
import org.junit.jupiter.api.Test

/** The snapshot subscriptions a context's assembly builds, as the node gives them its shared states' bounds. */
class SnapshotAssemblyTest : SnapshotSubscriptionFixtures() {
    /**
     * The store calls a subscription remembering what it wrote of at most [capacity] aggregates makes when it takes
     * `a`'s first entry again, after `b`'s: none while `a` is still remembered, one once `b` took its place.
     */
    private suspend fun callsForAgain(capacity: Int): Int {
        val written = RecordingStore()
        val assembly = SnapshotAssembly(
            "shop",
            SnapshotTargets("default", mapOf("default" to written)),
            listOf(counter),
            kernel,
            log,
            codec,
        )
        val subscription = assembly.subscriptions(SharedStates(codec, assembly.readers), 1, capacity).single()
        suspend fun handle(id: String) {
            val stream = log.read(ref(id)).toList().single()
            subscription.subscription.handle(
                subscription.subscription.decoder.decode(stream, LogPosition(0, 1), null)
            )
        }
        handle("a")
        handle("b")
        val before = written.saves.size + written.loads.get()
        handle("a")
        return written.saves.size + written.loads.get() - before
    }

    @Test
    fun `a subscription remembers what it wrote of as many aggregates as its capacity`(): Unit = runBlocking {
        listOf("a", "b").forEach { executor.execute(counter, command(it, Add())) as CommandOutcome.Committed }
        callsForAgain(capacity = 2).assert().isZero()
        callsForAgain(capacity = 1).assert().isEqualTo(1)
    }
}
