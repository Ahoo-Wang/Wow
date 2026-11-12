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
import me.ahoo.wow.api.annotation.AggregateId
import me.ahoo.wow.api.annotation.CreateAggregate
import me.ahoo.wow.api.annotation.InternalCommand
import me.ahoo.wow.api.annotation.OccurredAt
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.spi.log.EventStreamRecord
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * An import's creation in a specification, at the time it declares: the import program gives when it imports in the
 * command's body, and the event keeps it. `documentation/docs/{en,zh}/guide/importing.md` shows the command and the
 * specification.
 */
class OccurredAtSpecTest {
    // The annotations below are inert here: the DSL reads none of them. They are the command as the guide shows it,
    // which the compiler reads in a service's domain; this file's model is built by hand below, by the DSL's
    // decide<…>(Creation.REQUIRED), aggregateId<…> and occurredAt<…>, as the compiler generates them.
    // region command
    @CreateAggregate
    @InternalCommand
    data class ImportOrder(
        @AggregateId val legacyId: String,
        @OccurredAt val placedAt: Long,
        val importedAt: Long,
    )

    data class OrderImported(val importedAt: Long)
    // endregion

    private class OrderState {
        var importedAt = 0L
    }

    private val model = aggregate<OrderState>("shop", "order") {
        initial { OrderState() }
        decide<ImportOrder>(Creation.REQUIRED) { _, body -> Decision.events(OrderImported(body.importedAt)) }
        aggregateId<ImportOrder> { it.legacyId }
        occurredAt<ImportOrder> { it.placedAt }
        evolve<OrderImported> { importedAt = it.importedAt }
    }

    @Test
    fun `an import's creation is decided at its declared time, and keeps when it was imported`() {
        // region spec
        aggregateSpec(model, id = "legacy-1", start = START) {
            whenCommand(ImportOrder("legacy-1", PLACED, IMPORTED)) {
                expectEventEnvelope<OrderImported> {
                    body.assert().isEqualTo(OrderImported(IMPORTED))
                    // The commit is at the time the order was placed; the header holds when it was written.
                    commitTime.assert().isEqualTo(PLACED)
                    headers[EventStreamRecord.WRITTEN_AT].assert().isEqualTo("${START.toEpochMilli()}")
                }
                expectState { importedAt.assert().isEqualTo(IMPORTED) }
            }
        }
        // endregion
    }

    private companion object {
        val START: Instant = Instant.parse("2026-11-12T08:00:00Z")
        const val PLACED = 1_500_000_000_000L
        val IMPORTED: Long = START.toEpochMilli() - 60_000
    }
}
