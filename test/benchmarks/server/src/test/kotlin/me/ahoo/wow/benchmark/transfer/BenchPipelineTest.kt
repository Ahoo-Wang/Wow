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
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.spi.observation.ObservedEvent
import org.junit.jupiter.api.Test

/** The per-second line of the saga pipeline, and the counts `bench-signals` adds for it. */
@OptIn(InternalWowApi::class)
class BenchPipelineTest {
    @Test
    fun `a snapshot should give each section's fields in order, every value a number`() {
        val line = BenchPipeline {
            linkedMapOf(
                "appendDepth" to linkedMapOf("queued" to 3, "handedGroups" to 2, "handedStreams" to 40),
                "appendCalls" to linkedMapOf("calls" to 10L, "streams" to 2_048L, "full" to 4L),
                "handoff" to emptyMap(),
                "window" to sortedMapOf("onPrepared" to 1_024, "onAmountEntered" to 7),
            )
        }.snapshot()

        line!!.substringAfter(',').assert().isEqualTo(
            "\"appendDepth\":{\"queued\":3,\"handedGroups\":2,\"handedStreams\":40}," +
                "\"appendCalls\":{\"calls\":10,\"streams\":2048,\"full\":4}," +
                "\"window\":{\"onAmountEntered\":7,\"onPrepared\":1024}}",
        )
        line.assert().startsWith("{\"t\":")
    }

    @Test
    fun `a node with nothing to say should log nothing`() {
        BenchPipeline { null }.snapshot().assert().isNull()
        BenchPipeline { mapOf("window" to emptyMap()) }.snapshot().assert().isNull()
    }

    @Test
    fun `bench-signals should count the push-backs, the throttles and the rebuilds with the gate's wait`() {
        val signals = BenchSignals()
        val type = AggregateType("transfer", "account")
        signals.observed(ObservedEvent.Backpressured("g", "onPrepared", 0, "wow.command.busy"))
        signals.observed(ObservedEvent.Backpressured("g", "onPrepared", 1, "wow.query.busy"))
        signals.observed(ObservedEvent.PartitionThrottled("g", "onPrepared", 0, true))
        signals.observed(ObservedEvent.PartitionThrottled("g", "onPrepared", 0, false))
        signals.observed(ObservedEvent.StateRebuilt(type, true, ObservedEvent.StateRebuilt.Outcome.RAN, 120))
        signals.observed(ObservedEvent.StateRebuilt(type, true, ObservedEvent.StateRebuilt.Outcome.REFUSED, 200))
        signals.observed(ObservedEvent.StateRebuilt(type, false, ObservedEvent.StateRebuilt.Outcome.RAN, 0))

        signals.snapshot().substringAfter(',').assert().isEqualTo(
            "\"backpressureCode\":{\"wow.command.busy\":1,\"wow.query.busy\":1}," +
                "\"backpressured\":{\"onPrepared\":2}," +
                "\"partitionThrottled\":{\"onPrepared\":1}," +
                "\"partitionUnthrottled\":{\"onPrepared\":1}," +
                "\"stateRebuildWait\":{\"millis\":320}," +
                "\"stateRebuilt\":{\"gated-ran\":1,\"gated-refused\":1,\"ungated-ran\":1}}",
        )
        signals.close()
    }
}
