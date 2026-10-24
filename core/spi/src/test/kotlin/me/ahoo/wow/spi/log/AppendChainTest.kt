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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException

/** One aggregate's consecutive streams in one `appendAll` (decision D273). */
class AppendChainTest {
    private val type = AggregateType("ctx", "agg")
    private val a = type.ref("tenant", "a")
    private val b = type.ref("tenant", "b")

    private fun stream(ref: AggregateRef, version: Int): Pair<EventStreamRecord, Transport?> = EventStreamRecord.of(
        ref,
        version,
        "command-$version",
        "request-$version",
        "ctx.agg.Act",
        0L,
        listOf(EventRecord.of("ctx.agg.Acted", 1, "{}")),
        Scope.NONE,
    ) to null

    @Test
    fun `an aggregate's streams come in consecutive versions, among other aggregates'`() {
        requireChains(listOf(stream(a, 3), stream(b, 1), stream(a, 4), stream(a, 5), stream(b, 2)))
    }

    @Test
    fun `a gap or a repeated version is refused`() {
        assertThrows<IllegalArgumentException> { requireChains(listOf(stream(a, 3), stream(a, 5))) }
            .message.assert().contains("5 after 3")
        assertThrows<IllegalArgumentException> { requireChains(listOf(stream(a, 3), stream(b, 1), stream(a, 3))) }
    }

    @Test
    fun `a stream after one that did not commit is a conflict at the version it left`() {
        val failed = stream(a, 4).first
        notAppendedAfter(failed, AppendOutcome.Conflict.of(7)).currentVersion.assert().isEqualTo(7)
        notAppendedAfter(failed, AppendOutcome.AlreadyCommitted.of(failed)).currentVersion.assert().isEqualTo(3)
        notAppendedAfter(failed, AppendOutcome.Unknown.of(IOException("lost"))).currentVersion.assert().isEqualTo(3)
    }
}
