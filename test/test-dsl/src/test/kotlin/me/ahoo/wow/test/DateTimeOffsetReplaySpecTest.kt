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

package me.ahoo.wow.test

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.aggregate
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * A state that holds an `OffsetDateTime` or a `ZonedDateTime` sourced from its event is the state its log rebuilds:
 * Wow's JSON reads such a value back at the offset it was written with, so the state a command left, at the event's
 * offset, and the one the replay check rebuilds from the log's JSON are one.
 */
class DateTimeOffsetReplaySpecTest {
    data class Book(val at: OffsetDateTime, val zoned: ZonedDateTime)

    data class Booked(val at: OffsetDateTime, val zoned: ZonedDateTime)

    class Slot {
        var at: OffsetDateTime? = null
        var zoned: ZonedDateTime? = null
    }

    private val model: AggregateModel<Slot> = aggregate("desk", "slot") {
        initial { Slot() }
        decide<Book>(Creation.REQUIRED) { _, body -> Decision.events(Booked(body.at, body.zoned)) }
        evolve<Booked> { event ->
            at = event.at
            zoned = event.zoned
        }
    }

    @Test
    fun `a state holding an event's offset should be the state its log rebuilds`() = aggregateSpec(model) {
        val at = OffsetDateTime.parse("2026-01-02T03:04:05+08:00")
        val zoned = ZonedDateTime.parse("2026-01-02T03:04:05+08:00[Asia/Shanghai]")
        whenCommand(Book(at, zoned)) {
            expectState {
                this.at!!.offset.assert().isEqualTo(ZoneOffset.ofHours(8))
                this.zoned!!.offset.assert().isEqualTo(ZoneOffset.ofHours(8))
            }
        }
    }
}
