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
import me.ahoo.wow.kernel.aggregate
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * An import's creation in a specification, at the time it declares: the import program gives when it imports in the
 * command's body, and the event keeps it.
 */
class OccurredAtSpecTest {
    private data class ImportOrder(val source: String, val placedAt: Long, val importedAt: Long)
    private data class OrderImported(val source: String, val placedAt: Long, val importedAt: Long)

    private class OrderState {
        var importedAt = 0L
    }

    private val model = aggregate<OrderState>("shop", "order") {
        initial { OrderState() }
        decide<ImportOrder>(
            Creation.REQUIRED
        ) { _, body -> Decision.events(OrderImported(body.source, now, body.importedAt)) }
        occurredAt<ImportOrder> { it.placedAt }
        evolve<OrderImported> { importedAt = it.importedAt }
    }

    @Test
    fun `an import's creation is decided at its declared time, and keeps when it was imported`() =
        aggregateSpec(model, id = "legacy-1", start = START) {
            whenCommand(ImportOrder("legacy-1", PLACED, IMPORTED)) {
                expectEvents(OrderImported("legacy-1", PLACED, IMPORTED))
                expectState { importedAt.assert().isEqualTo(IMPORTED) }
            }
        }

    private companion object {
        val START: Instant = Instant.parse("2026-11-12T08:00:00Z")
        const val PLACED = 1_500_000_000_000L
        val IMPORTED: Long = START.toEpochMilli() - 60_000
    }
}
