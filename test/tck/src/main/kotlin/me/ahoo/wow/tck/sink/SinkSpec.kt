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

package me.ahoo.wow.tck.sink

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.sink.Sink
import me.ahoo.wow.spi.state.StateEventPayload
import me.ahoo.wow.spi.state.StateEventRecord
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The [Sink] contract (design 04 §4, decision D132; review R3.3-2) every sink passes: what a write returned is where
 * the sink writes, a record written again is no more than a duplicate of the same id, and an aggregate's records keep
 * their version order. A sink's test extends it, supplies a sink of [aggregateType] from [createSink], and reads back
 * what its destination holds with [written].
 */
abstract class SinkSpec {
    /** The aggregate type of the records the spec writes. */
    protected open val aggregateType: AggregateType = AggregateType("tck", "sinked")

    /** A sink of [aggregateType]'s records. Sinks may share their destination: each test writes aggregates of its own. */
    protected abstract fun createSink(): Sink

    /**
     * The versions of [ref]'s records its destination holds now, in the order it keeps them for one aggregate (by
     * version, as a table read orders them, or as a topic's partition holds them); a duplicate may appear twice.
     */
    protected abstract suspend fun written(ref: AggregateRef): List<Int>

    /** The record of [ref] at [version]: a sink with a schema of its own (a warehouse's tables) gives one that fits. */
    protected open fun record(ref: AggregateRef, version: Int): StateEventRecord = StateEventRecord.of(
        ref,
        version,
        commandId = "command-${ref.id}-$version",
        requestId = "request-${ref.id}-$version",
        commandType = "tck.sinked.record",
        commitTime = COMMIT_TIME + version,
        events = listOf(
            StateEventPayload.of("recorded", 1, WowJson.mapper.createObjectNode().put("version", version)),
        ),
    )

    /** How long a destination read through a consumer (a topic's) may take to show what a write returned. */
    protected open val visibleWithin: Duration = 10.seconds

    private val sink: Sink by lazy { createSink() }

    private fun ref(): AggregateRef = AggregateRef(aggregateType, TENANT, "sinked-" + UUID.randomUUID())

    /** [ref]'s versions once they hold every one of [expected], or as they are after [visibleWithin]. */
    private suspend fun visible(ref: AggregateRef, expected: Collection<Int>): List<Int> =
        withTimeoutOrNull(visibleWithin) {
            var seen = written(ref)
            while (!seen.containsAll(expected)) {
                delay(POLL)
                seen = written(ref)
            }
            seen
        } ?: written(ref)

    @Test
    fun `what a write returned is at the destination`(): Unit = runBlocking {
        val first = ref()
        val second = ref()
        sink.write(listOf(record(first, 1), record(second, 1), record(first, 2)))
        visible(first, listOf(1, 2)).distinct().assert().containsExactly(1, 2)
        visible(second, listOf(1)).distinct().assert().containsExactly(1)
    }

    @Test
    fun `an empty batch writes nothing`(): Unit = runBlocking {
        sink.write(emptyList())
        val ref = ref()
        sink.write(listOf(record(ref, 1)))
        visible(ref, listOf(1)).distinct().assert().containsExactly(1)
    }

    @Test
    fun `a batch written again leaves no more than duplicates of its records`(): Unit = runBlocking {
        val ref = ref()
        val batch = listOf(record(ref, 1), record(ref, 2))
        sink.write(batch)
        // A retry after a lost acknowledgement, and an old owner of the partition writing late.
        sink.write(batch)
        sink.write(listOf(record(ref, 2)))
        visible(ref, listOf(1, 2)).distinct().assert().containsExactly(1, 2)
    }

    @Test
    fun `a retry of a partly written batch writes the rest`(): Unit = runBlocking {
        val ref = ref()
        sink.write(listOf(record(ref, 1)))
        sink.write(listOf(record(ref, 1), record(ref, 2), record(ref, 3)))
        visible(ref, listOf(1, 2, 3)).distinct().assert().containsExactly(1, 2, 3)
    }

    @Test
    fun `an aggregate's records keep their version order`(): Unit = runBlocking {
        val ref = ref()
        val other = ref()
        (1..3).forEach { version -> sink.write(listOf(record(ref, version), record(other, version))) }
        sink.write(listOf(record(ref, 4), record(ref, 5)))
        visible(ref, (1..5).toList()).distinct().assert().containsExactly(1, 2, 3, 4, 5)
        visible(other, (1..3).toList()).distinct().assert().containsExactly(1, 2, 3)
    }

    private companion object {
        const val TENANT = "tenant-tck"
        const val COMMIT_TIME = 1_700_000_000_000L
        val POLL = 50.milliseconds
    }
}
