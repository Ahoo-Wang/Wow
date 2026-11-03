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

package me.ahoo.wow.spi.access

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.state.StateEventPayload
import me.ahoo.wow.spi.state.StateEventRecord
import org.junit.jupiter.api.Test

/** The version a record's access attributes are of: its own when given them and no version, none without them. */
class AccessVersionTest {
    private val ref = AggregateType("office", "document").ref("tenant", "d-1")
    private val access = AccessValues.ofStream(mapOf("region" to "east"), Scope("alice"))
    private val events = listOf(EventRecord("e", 1, "{}"))

    private fun stream(access: Map<String, Any?>?) =
        EventStreamRecord.of(ref, 3, "c-3", "r-3", "office.document.Edit", 3L, events, Scope("alice"), access = access)

    private fun state(access: Map<String, Any?>?) = StateEventRecord.of(
        ref,
        3,
        "c-3",
        "r-3",
        "office.document.Edit",
        3L,
        listOf(StateEventPayload("e", 1, WowJson.mapper.readTree("{}"))),
        access = access,
    )

    @Test
    fun `a record given attributes is of its own version unless told otherwise, one without them of none`() {
        stream(access).accessVersion.assert().isEqualTo(3)
        stream(access).copyWith(accessVersion = 7).accessVersion.assert().isEqualTo(7)
        stream(null).accessVersion.assert().isNull()
        state(access).accessVersion.assert().isEqualTo(3)
        state(null).accessVersion.assert().isNull()
        state(access).copyWith(accessVersion = 9).toString().assert().contains("accessVersion=9")
    }
}
