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
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.lifecycle.DeleteAggregate
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.kernel.aggregate
import org.junit.jupiter.api.Test

/** What a decide function puts for the caller is checked without the kernel's outcome type (finding R5.A-3). */
class ExpectResultTest {
    data class Place(val sku: String)
    data class Placed(val sku: String)
    data class Note(val text: String)

    class Order(val id: String)

    private val model = aggregate<Order>("shop", "order") {
        initial { Order(it.id) }
        decide<Place>(Creation.REQUIRED) { _, body ->
            putResult("number", "N-${body.sku}")
            Decision.events(Placed(body.sku))
        }
        decide<Note> { _, _ -> Decision.NoOp }
        evolve<Placed> { }
    }

    @Test
    fun `a committed command's results should be checked by key`() = aggregateSpec(model) {
        whenCommand(Place("mug")) {
            expectResult("number", "N-mug")
            results.assert().isEqualTo(mapOf("number" to "N-mug"))
            assertThrownBy<AssertionError> { expectResult("number", "N-cap") }
                .hasMessageContaining("Expected the result number = N-cap but got N-mug")
        }
    }

    @Test
    fun `a command that committed nothing should have no results to check`() = aggregateSpec(model) {
        given(Placed("mug"))
        whenCommand(Note("hi")) {
            expectUnchanged()
            assertThrownBy<AssertionError> { expectResult("number", "N-mug") }
                .hasMessageContaining("Expected committed events")
        }
    }

    @Test
    fun `the version should be the committed stream's, or the one the aggregate stayed at`() = aggregateSpec(model) {
        whenCommand(Place("mug")) {
            expectVersion(1)
            assertThrownBy<AssertionError> { expectVersion(2) }
                .hasMessageContaining("Expected the aggregate at version 2 but it is at 1")
        }
        whenCommand(Note("hi")) {
            expectUnchanged()
            expectVersion(1)
        }
        whenCommand(Place("cap")) {
            expectRejected(FrameworkCodes.AGGREGATE_EXISTS)
            expectVersion(1)
        }
    }

    @Test
    fun `a deletion should be expected only of a command that committed one`() = aggregateSpec(model) {
        given(Placed("mug"))
        whenCommand(Note("hi")) {
            assertThrownBy<AssertionError> { expectDeleted() }
                .hasMessageContaining("Expected the command to delete the aggregate")
        }
        whenCommand(DeleteAggregate) {
            expectDeleted()
            expectVersion(2)
        }
    }
}
