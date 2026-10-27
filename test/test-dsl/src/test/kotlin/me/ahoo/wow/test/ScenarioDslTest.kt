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
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.aggregate
import org.junit.jupiter.api.Test

/** Forks with other services, event counts and envelopes, and failures that say where and what (R5.A-21..A-23). */
class ScenarioDslTest {
    data class Open(val owner: String)
    data class Reserve(val sku: String, val quantity: Int)
    data class Opened(val owner: String)
    data class Reserved(val sku: String, val quantity: Int)
    data class Line(val sku: String, val quantity: Int)

    class Basket(val id: String) {
        var owner = ""
        var lines = listOf<Line>()
    }

    /** The domain service the decide function asks, which a scenario swaps. */
    fun interface Inventory {
        fun available(sku: String): Int
    }

    private fun model(inventory: Inventory): AggregateModel<Basket> = aggregate("shop", "basket") {
        initial { Basket(it.id) }
        decide<Open>(Creation.REQUIRED) { _, body -> Decision.events(Opened(body.owner)) }
        decide<Reserve> { _, body ->
            if (inventory.available(body.sku) < body.quantity) {
                Decision.reject("out-of-stock", "${body.sku} is short.")
            } else {
                Decision.events(Reserved(body.sku, body.quantity))
            }
        }
        evolve<Opened> { owner = it.owner }
        evolve<Reserved> { lines = lines + Line(it.sku, it.quantity) }
    }

    private val plenty = model { 100 }

    @Test
    fun `a fork should run with the services of another model, on a copy of the aggregate`() = aggregateSpec(plenty) {
        whenCommand(Open("alice")) {
            expectEventCount(1)
            fork("stock-out", model = model { 0 }) {
                whenCommand(Reserve("mug", 1)) { expectRejected("out-of-stock") }
            }
        }
        fork("also out", model = model { 0 }) {
            whenCommand(Reserve("cap", 2)) { expectRejected("out-of-stock") }
        }
        // The specification goes on with its own services.
        whenCommand(Reserve("mug", 1)) { expectEvents(Reserved("mug", 1)) }
    }

    @Test
    fun `an unnamed fork should run with the services of another model`() = aggregateSpec(plenty) {
        whenCommand(Open("alice")) {
            fork(model = model { 0 }) {
                whenCommand(Reserve("mug", 1)) { expectRejected("out-of-stock") }
            }
        }
        fork(model = model { 0 }) {
            whenCommand(Reserve("cap", 1)) { expectRejected("out-of-stock") }
        }
        whenCommand(Reserve("mug", 1)) {
            expectEventEnvelope(Reserved::class).body.assert().isEqualTo(Reserved("mug", 1))
        }
    }

    @Test
    fun `a fork's model should decide the aggregate of the specification`() {
        val other = aggregate<Basket>("shop", "other") { initial { Basket(it.id) } }
        assertThrownBy<IllegalArgumentException> { aggregateSpec(plenty) { fork("x", model = other) { } } }
            .hasMessageContaining("shop.other")
    }

    @Test
    fun `an event should be checked with its envelope`() = aggregateSpec(plenty) {
        given(Opened("alice"))
        whenCommand(
            Reserve("mug", 2),
            requestId = "r-mug",
            headers = mapOf("app" to "console"),
            operator = Operator.of("alice", "tenant"),
        ) {
            expectEventEnvelope<Reserved> {
                body.assert().isEqualTo(Reserved("mug", 2))
                aggregateId.assert().isEqualTo("aggregate-1")
                tenantId.assert().isEqualTo("tenant")
                version.assert().isEqualTo(2)
                requestId.assert().isEqualTo("r-mug")
                commandId.assert().startsWith("command-")
                headers.assert().containsEntry("app", "console")
                operator?.id.assert().isEqualTo("alice")
                commitTime.assert().isEqualTo(DEFAULT_START.toEpochMilli())
                toString().assert().contains("Reserved", "r-mug")
            }
        }
    }

    @Test
    fun `a failing check should name its step and command, and list the differences by path`() {
        assertThrownBy<AssertionError> {
            aggregateSpec(plenty) {
                given(Opened("alice"))
                whenCommand(Reserve("mug", 2)) { expectEvents(Reserved("mug", 3)) }
            }
        }.hasMessageStartingWith("whenCommand(Reserve) (command-")
            .hasMessageContaining("[0].quantity: expected 3, was 2")
        assertThrownBy<AssertionError> {
            aggregateSpec(plenty) {
                given(Opened("alice"))
                whenCommand(Reserve("mug", 2)) { expectEvents(Reserved("mug", 2), Opened("bob")) }
            }
        }.hasMessageContaining("expected 2 items, was 1").hasMessageContaining("[1]: missing Opened")
        assertThrownBy<AssertionError> {
            aggregateSpec(plenty) {
                given(Opened("alice"))
                whenCommand(Reserve("mug", 2)) { expectEventCount(2) }
            }
        }.hasMessageContaining("Expected 2 events but got 1")
    }

    @Test
    fun `a state should be compared field by field`() {
        aggregateSpec(plenty) {
            given(Opened("alice"))
            whenCommand(Reserve("mug", 2)) {
                expectState(
                    Basket("aggregate-1").apply {
                        owner = "alice"
                        lines = listOf(Line("mug", 2))
                    }
                )
            }
        }
        assertThrownBy<AssertionError> {
            aggregateSpec(plenty) {
                given(Opened("alice"))
                whenCommand(Reserve("mug", 2)) {
                    expectState(
                        Basket("aggregate-1").apply {
                            owner = "bob"
                            lines = listOf(Line("mug", 2))
                        }
                    )
                }
            }
        }.hasMessageContaining("The state differs").hasMessageContaining("owner: expected \"bob\", was \"alice\"")
    }

    @Test
    fun `values of different classes should differ as such`() {
        StructuralDiff.of(Opened("a"), Reserved("a", 1)).single().assert()
            .isEqualTo("the value: expected a Opened, was a Reserved")
        StructuralDiff.of(listOf(Opened("a")), listOf(Opened("a"), Opened("b"))).assert()
            .contains("[1]: unexpected Opened {\"owner\":\"b\"}")
        StructuralDiff.of(mapOf("a" to listOf(1, 2)), mapOf("a" to listOf(1))).single().assert()
            .isEqualTo("a: expected 2 items, was 1")
        StructuralDiff.of(null, null).assert().isEmpty()
    }
}
