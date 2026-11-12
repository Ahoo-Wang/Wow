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
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** A stream record's own checks. */
class EventStreamRecordTest {
    private val ref = AggregateType("ctx", "agg").ref("tenant", "a")

    private fun stream(access: Map<String, Any?>?) = EventStreamRecord.of(
        ref,
        1,
        "command-1",
        "request-1",
        "ctx.agg.Act",
        0L,
        listOf(EventRecord.of("ctx.agg.Acted", 1, "{}")),
        Scope.NONE,
        access = access,
    )

    @Test
    fun `a stamped stream's attributes hold the built-in owner and space, null when it has none`() {
        stream(null).access.assert().isNull()
        stream(mapOf("owner" to null, "space" to null)).access.assert().containsKeys("owner", "space")
        stream(mapOf("owner" to "alice", "space" to null, "region" to "east")).accessVersion.assert().isEqualTo(1)
        assertThrows<IllegalArgumentException> { stream(emptyMap()) }.message.assert().contains("owner and space")
        assertThrows<IllegalArgumentException> { stream(mapOf("owner" to "alice")) }
        assertThrows<IllegalArgumentException> { stream(mapOf("space" to "s1")) }
    }
}
