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

package me.ahoo.wow.spi.log

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AggregateTailTest {
    private val ref = AggregateType("ctx", "agg").ref("tenant", "a")
    private val event = EventRecord.of("ctx.agg.Acted", 1, "{}")
    private val streams = (1..5).map { version ->
        EventStreamRecord.of(ref, version, "command-$version", "request-$version", "ctx.agg.Act", 0L, listOf(event), Scope.NONE)
    }

    /** A log whose [EventLog.readTail] is the default, over [streams]. */
    private fun log(): EventLog = mockk<EventLog>().also { log ->
        every { log.read(ref, any(), any()) } answers {
            streams.filter { it.version in secondArg<Int>()..thirdArg<Int>() }.asFlow()
        }
        coEvery { log.readTail(any(), any(), any()) } coAnswers { callOriginal() }
    }

    @Test
    fun `by default the tail and the earlier request keys are two reads, the keys newest first`(): Unit = runBlocking {
        val tail = log().readTail(ref, 4, 2)
        tail.streams.assert().isEqualTo(streams.drop(3))
        tail.earlier.assert().containsExactly(CommittedRequest.of(streams[2]), CommittedRequest.of(streams[1]))
        log().readTail(ref, 3, 10).earlier.map { it.version }.assert().containsExactly(2, 1)
        log().readTail(ref, 3, 0).earlier.assert().isEmpty()
        log().readTail(ref, 1, 3).earlier.assert().isEmpty()
        assertThrows<IllegalArgumentException> { log().readTail(ref, 0, 1) }
        assertThrows<IllegalArgumentException> { log().readTail(ref, 1, -1) }
    }

    @Test
    fun `a request key and a tail compare by value`() {
        val key = CommittedRequest.of(2, "request", "ctx.agg.Act")
        key.assert().isEqualTo(CommittedRequest.of(2, "request", "ctx.agg.Act"))
        key.assert().isNotEqualTo(CommittedRequest.of(3, "request", "ctx.agg.Act"))
        key.hashCode().assert().isEqualTo(CommittedRequest.of(2, "request", "ctx.agg.Act").hashCode())
        key.toString().assert().isEqualTo("CommittedRequest(version=2, requestId=request, commandType=ctx.agg.Act)")
        val tail = AggregateTail.of(streams.take(1), listOf(key))
        tail.assert().isEqualTo(AggregateTail.of(streams.take(1), listOf(key)))
        tail.assert().isNotEqualTo(AggregateTail.of(streams.take(1), emptyList()))
        tail.assert().isNotEqualTo(AggregateTail.of(emptyList(), listOf(key)))
        tail.hashCode().assert().isEqualTo(AggregateTail.of(streams.take(1), listOf(key)).hashCode())
        tail.toString().assert().startsWith("AggregateTail(streams=")
    }
}
