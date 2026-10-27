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

package me.ahoo.wow.kernel

import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.decide.Rejection
import me.ahoo.wow.api.subscribe.ExecutionId
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.LogPosition
import org.junit.jupiter.api.Test

/** The kernel's values are plain classes compared by value (findings R5.A-3, A-11, A-13). */
class KernelValuesTest {
    private val stream = mockk<EventStreamRecord>()

    @Test
    fun `every command outcome should be a value`() {
        val outcomes: List<() -> CommandOutcome> = listOf(
            { CommandOutcome.Committed(stream, LogPosition(0, 1), "state") },
            { CommandOutcome.AlreadyCommitted(stream) },
            { CommandOutcome.Unchanged(1) },
            { CommandOutcome.Rejected(Rejection.of("shop.out", "Out.")) },
            { CommandOutcome.Contended(3) },
            { CommandOutcome.Unknown(LOST) },
        )
        outcomes.forEach { build ->
            val outcome = build()
            outcome.assert().isEqualTo(outcome).isEqualTo(build())
            outcome.hashCode().assert().isEqualTo(build().hashCode())
            outcome.equals(Any()).assert().isFalse()
            outcome.toString().assert().startsWith(outcome.javaClass.simpleName + "(")
        }
        CommandOutcome.Unchanged(1).assert().isNotEqualTo(CommandOutcome.Unchanged(2))
    }

    @Test
    fun `an event type should be equal by what it names and reads, not by its upgrade functions`() {
        val placed = EventType("Placed", 2, String::class.java, mapOf(1 to EventUpgrade { it }))
        val same = EventType("Placed", 2, String::class.java, mapOf(1 to EventUpgrade { it.deepCopy() }))
        placed.assert().isEqualTo(same)
        placed.hashCode().assert().isEqualTo(same.hashCode())
        placed.assert().isNotEqualTo(EventType("Placed", 1, String::class.java))
        placed.equals("Placed").assert().isFalse()
        placed.toString().assert()
            .isEqualTo("EventType(name=Placed, revision=2, type=java.lang.String, upgrades=[1], aliases=[])")
    }

    @Test
    fun `a projection's and a reactor's context should name their execution`() {
        val execution = ExecutionId("shop.board.onPlaced", AggregateType("shop", "order").ref("t", "o-1"), 1)
        ExecutionContext(execution).toString().assert().contains(execution.toString())
        ExecutionContext(execution).toString().assert().contains(execution.toString())
    }

    private companion object {
        val LOST = IllegalStateException("lost")
    }
}
