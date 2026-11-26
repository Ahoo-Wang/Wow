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
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.decide.RequestFacts
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.kernel.AggregateIds
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
            operator = Operator.of("alice", DEFAULT_TENANT),
        ) {
            expectEventEnvelope<Reserved> {
                body.assert().isEqualTo(Reserved("mug", 2))
                ref.id.assert().isEqualTo("aggregate-1")
                ref.tenantId.assert().isEqualTo(DEFAULT_TENANT)
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

    @Test
    fun `maps and collections of other classes should compare by their entries`() {
        StructuralDiff.of(mapOf("a" to listOf(1)), linkedMapOf("a" to mutableListOf(1))).assert().isEmpty()
        StructuralDiff.of(setOf("a"), listOf("b")).single().assert().isEqualTo("[0]: expected \"a\", was \"b\"")
    }

    @Test
    fun `given commands should set the aggregate up and fail on the command that does not commit`() {
        aggregateSpec(plenty) {
            givenCommands(Open("alice"), Reserve("mug", 1))
            whenCommand(Reserve("cap", 2)) { expectVersion(3) }
        }
        assertThrownBy<AssertionError> {
            aggregateSpec(model { 0 }) { givenCommands(Open("alice"), Reserve("mug", 1)) }
        }.hasMessage(
            "givenCommands(Reserve) (command-2): Expected it to commit but it was rejected " +
                "shop.basket.out-of-stock: mug is short.",
        )
        assertThrownBy<AssertionError> {
            aggregateSpec(
                model { throw UnsupportedOperationException("down") }
            ) { givenCommands(Open("alice"), Reserve("mug", 1)) }
        }.hasMessageStartingWith(
            "givenCommands(Reserve) (command-2): It threw java.lang.UnsupportedOperationException: down"
        )
        assertThrownBy<IllegalArgumentException> { aggregateSpec(plenty) { givenCommands() } }
    }

    @Test
    fun `an outcome should read as its decoded events, version or rejection`() {
        assertThrownBy<AssertionError> {
            aggregateSpec(plenty) { whenCommand(Open("alice")) { expectUnchanged() } }
        }.hasMessageEndingWith("Expected no change but it was committed [Opened(owner=alice)] at v1.")
        assertThrownBy<AssertionError> {
            aggregateSpec(model { 0 }) {
                given(Opened("alice"))
                whenCommand(Reserve("mug", 1)) { expectEventCount(1) }
            }
        }.hasMessageEndingWith("Expected committed events but it was rejected shop.basket.out-of-stock: mug is short.")
        assertThrownBy<AssertionError> {
            aggregateSpec(model { 0 }) {
                given(Opened("alice"))
                whenCommand(Reserve("mug", 1)) { }
            }
        }.hasMessageEndingWith(
            "The command was rejected shop.basket.out-of-stock: mug is short, which the scenario does not check: " +
                "expectRejected checks a rejection.",
        )
    }

    @Test
    fun `a caller's commands should carry its scope, operator and application`() {
        val alice = Caller.of(
            tenantId = "acme",
            scope = Scope("alice"),
            subject = "alice",
            attributes = mapOf(Caller.APP to listOf("console"))
        )
        val bob = Caller.of(tenantId = "acme", scope = Scope("bob"), subject = "bob")
        aggregateSpec(plenty, caller = alice) {
            ref.tenantId.assert().isEqualTo("acme")
            givenCommands(Open("alice"))
            whenCommand(Reserve("mug", 1)) {
                expectScope(Scope("alice"))
                expectEventEnvelope<Reserved> {
                    operator.assert().isEqualTo(Operator("alice", "acme"))
                    headers.assert().containsEntry(RequestFacts.APP_ID, "console")
                }
            }
            // Another user's commands are outside the aggregate's scope: it is not found for them.
            asCaller(bob) {
                whenCommand(Reserve("cap", 1)) { expectRejected(FrameworkCodes.AGGREGATE_NOT_FOUND) }
                fork("as bob") {
                    whenCommand(Reserve("cap", 1)) { expectRejected(FrameworkCodes.AGGREGATE_NOT_FOUND) }
                }
            }
            // A scope it names is one it crosses into, its operator acting elsewhere.
            val admin = Caller.of(tenantId = "acme", subject = "admin", crossScope = true)
            whenCommand(Reserve("hat", 1), scope = Scope("alice"), caller = admin) {
                expectEventEnvelope<Reserved> { operator.assert().isEqualTo(Operator("admin", "acme", true)) }
            }
            whenCommand(Reserve("pen", 1), operator = Operator.system("importer")) {
                expectEventEnvelope<Reserved> { operator.assert().isEqualTo(Operator.system("importer")) }
            }
        }
    }

    @Test
    fun `a caller the gateway refuses for its tenant or scope should fail the specification with that answer`() {
        val alice = Caller.of(tenantId = "acme", scope = Scope("alice"), subject = "alice")
        val carol = Caller.of(tenantId = "globex", scope = Scope("carol"), subject = "carol")
        val advice = " A specification sends only what admission takes: give a caller of the specification's " +
            "tenant, or one that acts across scopes (crossScope) for another tenant or scope."
        // A caller of another tenant, in asCaller as on the command.
        val otherTenant = "The gateway refuses Reserve from this caller, ${FrameworkCodes.COMMAND_MALFORMED}: The " +
            "command's ref names tenant acme, but it is sent in tenant globex: a ref's tenant is the caller's, a " +
            "cross-scope target's or the one fixed for its type." + advice
        assertThrownBy<IllegalArgumentException> {
            aggregateSpec(plenty, caller = alice) { asCaller(carol) { whenCommand(Reserve("cap", 1)) { } } }
        }.hasMessage(otherTenant)
        assertThrownBy<IllegalArgumentException> {
            aggregateSpec(plenty, caller = alice) { givenCommands(Reserve("cap", 1), caller = carol) }
        }.hasMessage(otherTenant)
        // Another scope, without crossScope.
        assertThrownBy<IllegalArgumentException> {
            aggregateSpec(plenty, caller = alice) { whenCommand(Reserve("cap", 1), scope = Scope("bob")) { } }
        }.hasMessage(
            "The gateway refuses Reserve from this caller, ${FrameworkCodes.AUTH_FORBIDDEN}: The caller may not act " +
                "in another scope." + advice,
        )
        // A cross-scope caller of another tenant acts in the specification's, in the scope it names.
        val admin = Caller.of(tenantId = "ops", subject = "admin", crossScope = true)
        aggregateSpec(plenty, caller = alice) {
            givenCommands(Open("alice"))
            whenCommand(Reserve("hat", 1), scope = Scope("alice"), caller = admin) {
                expectScope(Scope("alice"))
                expectEventEnvelope<Reserved> { operator.assert().isEqualTo(Operator("admin", "ops", true)) }
            }
        }
    }

    /** #7064: as the gateway, a specification refuses a request id the framework gives its own commands. */
    @Test
    fun `a command whose request id the framework's prefix starts should be refused, as the gateway refuses it`() {
        assertThrownBy<IllegalArgumentException> {
            aggregateSpec(plenty) { whenCommand(Open("alice"), requestId = "wow:shop.cart/t/c-1#remind@1#1") { } }
        }.hasMessage(
            "The gateway refuses Open as ${FrameworkCodes.COMMAND_MALFORMED}: a request id never starts with wow:, " +
                "which the framework gives its own commands. A specification sends only what admission takes.",
        )
    }

    @Test
    fun `a command without a caller should be refused the scope it names, as the gateway refuses the anonymous`() {
        // As on a node: a request without a caller is Caller.ANONYMOUS (ServerCommandRequest), and admission refuses a
        // scope it names, as it refuses any caller that does not act across scopes. Moved from review/r1-testkit
        // (DslReviewR1Test).
        assertThrownBy<IllegalArgumentException> {
            aggregateSpec(plenty) { whenCommand(Reserve("cap", 1), scope = Scope("bob")) { } }
        }.hasMessage(
            "The gateway refuses Reserve from an anonymous caller, as a request without one is, " +
                "${FrameworkCodes.AUTH_FORBIDDEN}: The caller may not act in another scope. A specification sends only " +
                "what admission takes: give a caller of the specification's tenant, or one that acts across scopes " +
                "(crossScope) for another tenant or scope.",
        )
        assertThrownBy<IllegalArgumentException> {
            aggregateSpec(plenty) { givenCommands(Open("bob"), scope = Scope("bob")) }
        }.hasMessageContaining(FrameworkCodes.AUTH_FORBIDDEN)
    }

    @Test
    fun `a request id past the admission limit should be refused as malformed, as the gateway refuses it`() {
        // As on a node: admission refuses an id longer than AggregateIds.MAX_LENGTH before any store is read, the same
        // check (AggregateIds.oversized). Moved from review/r1-testkit (DslReviewR1Test).
        val tooLong = "r".repeat(AggregateIds.MAX_LENGTH + 1)
        assertThrownBy<IllegalArgumentException> {
            aggregateSpec(plenty) { whenCommand(Reserve("cap", 1), requestId = tooLong) { } }
        }.hasMessage(
            "The gateway refuses Reserve as ${FrameworkCodes.COMMAND_MALFORMED}: The request id is at most " +
                "${AggregateIds.MAX_LENGTH} characters; this one has ${tooLong.length}. A specification sends only " +
                "what admission takes.",
        )
        // The longest takes, and an owner id past the limit is refused as the request id is.
        aggregateSpec(plenty, caller = Caller.owner("alice")) {
            givenCommands(Open("alice"))
            whenCommand(Reserve("cap", 1), requestId = "r".repeat(AggregateIds.MAX_LENGTH)) {
                expectEventTypes(Reserved::class)
            }
        }
        val longOwner = Caller.owner("o".repeat(AggregateIds.MAX_LENGTH + 1))
        assertThrownBy<IllegalArgumentException> {
            aggregateSpec(plenty, caller = longOwner) { whenCommand(Open("o"), requestId = "r-1") { } }
        }.hasMessageContaining("The owner id is at most ${AggregateIds.MAX_LENGTH} characters")
    }

    private val inConsole = Caller.of(subject = "alice", attributes = mapOf(Caller.APP to listOf("console")))

    @Test
    fun `a caller's application should be recorded beside the request facts a specification adds`() =
        aggregateSpec(plenty, caller = inConsole) {
            givenCommands(Open("alice"))
            whenCommand(Reserve("mug", 1), headers = mapOf("X-Trace" to "t-1")) {
                expectEventEnvelope<Reserved> {
                    headers.assert().isEqualTo(mapOf("X-Trace" to "t-1", RequestFacts.APP_ID to "console"))
                }
            }
        }

    @Test
    fun `headers naming the caller's application should fail the specification, with or without a caller`() {
        val named = mapOf(RequestFacts.APP_ID to "forged")
        val advice = "Give it with caller(…), a Caller whose 'app' attribute names the application."
        assertThrownBy<IllegalArgumentException> {
            aggregateSpec(plenty, caller = inConsole) { whenCommand(Reserve("mug", 1), headers = named) { } }
        }.hasMessageStartingWith("A specification's headers do not name app_id").hasMessageEndingWith(advice)
        assertThrownBy<IllegalArgumentException> {
            aggregateSpec(plenty) { whenCommand(Reserve("mug", 1), headers = named) { } }
        }.hasMessageEndingWith(advice)
        assertThrownBy<IllegalArgumentException> {
            aggregateSpec(plenty) { givenCommands(Reserve("mug", 1), headers = named) }
        }.hasMessageEndingWith(advice)
    }
}
