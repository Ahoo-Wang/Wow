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

package me.ahoo.wow.benchmark.transfer

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.runtime.log.AppendQueueOptions
import me.ahoo.wow.runtime.node.ServiceNodeOptions
import org.junit.jupiter.api.Test
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment

@OptIn(InternalWowApi::class)
class BenchTuningTest {
    @Test
    fun `a run's tuning replaces the counts it gives and keeps the node's own for the rest`() {
        val plain = ServiceNodeOptions()
        val tuned = BenchTuning(committers = 4, snapshotWrites = 8).customize(plain)
        tuned.appendQueue.assert().isEqualTo(
            AppendQueueOptions(
                maxBatch = plain.appendQueue.maxBatch,
                mergers = 4,
                mergeMax = plain.appendQueue.mergeMax,
            ),
        )
        tuned.snapshots.writesInFlight.assert().isEqualTo(8)
        tuned.snapshots.writeBatch.assert().isEqualTo(plain.snapshots.writeBatch)
        tuned.copyWith(appendQueue = plain.appendQueue, snapshots = plain.snapshots).assert().isEqualTo(plain)
        BenchTuning().customize(plain).assert().isEqualTo(plain)
        BenchTuning.lineOf(tuned).assert()
            .isEqualTo("{\"committers\":4,\"callCap\":512,\"laneBatch\":64,\"snapshotWrites\":8}")
    }

    @Test
    fun `the tuning is declared only when a run gives one of its values, each read from bench tuning`() {
        fun environment(vararg values: Pair<String, Any>) = StandardEnvironment().apply {
            propertySources.addFirst(MapPropertySource("run", values.toMap()))
        }
        BenchTuning.given(environment()).assert().isFalse()
        val given = environment("bench.tuning.call-cap" to "128", "bench.tuning.lane-batch" to "32")
        BenchTuning.given(given).assert().isTrue()
        val tuning = BenchTuning.of(given)
        tuning.callCap.assert().isEqualTo(128)
        tuning.laneBatch.assert().isEqualTo(32)
        tuning.committers.assert().isNull()
        tuning.snapshotWrites.assert().isNull()
    }
}
