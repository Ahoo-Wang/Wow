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

package me.ahoo.wow.http.command

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.http.WowHttpOptions
import me.ahoo.wow.runtime.admission.TargetScope
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** The grammars of `Wow-Wait`, `Wow-Wait-Timeout` (decision D99) and `Wow-Scope` (decision D95). */
class HeaderGrammarTest {
    @Test
    fun `every hop form parses`() {
        WaitHeader.parse("accepted").hops.assert().containsExactly(Hop.accepted())
        WaitHeader.parse("committed").hops.assert().containsExactly(Hop.committed())
        WaitHeader.parse("committed(order)").hops.assert().containsExactly(Hop.committed("order"))
        WaitHeader.parse("snapshot").hops.assert().containsExactly(Hop.snapshot())
        WaitHeader.parse("snapshot(order)").hops.assert().containsExactly(Hop.snapshot("order"))
        WaitHeader.parse("handled(audit)").hops.assert().containsExactly(Hop.handled("audit"))
        WaitHeader.parse("handled(payment/order-saga.onPaid)").hops
            .assert().containsExactly(Hop.handled("order-saga.onPaid", "payment"))
        WaitHeader.parse("handled(snapshot)").hops.assert().containsExactly(Hop.snapshot())
    }

    @Test
    fun `a chain parses in order, white space and case aside`() {
        WaitHeader.parse(" Handled( order / payment-saga ) >committed(inventory) ").hops.assert().containsExactly(
            Hop.handled("payment-saga", "order"),
            Hop.committed("inventory"),
        )
        WaitHeader.parse("committed").timeout.assert().isEqualTo(WaitPlan.DEFAULT_TIMEOUT)
    }

    @Test
    fun `format is the inverse of parse`() {
        val plans = listOf(
            WaitPlan.of(Hop.accepted()),
            WaitPlan.of(Hop.committed("order"), Hop.snapshot()),
            WaitPlan.of(Hop.handled("payment-saga", "order"), Hop.handled("audit"), Hop.snapshot("inventory")),
        )
        plans.forEach { plan -> WaitHeader.parse(WaitHeader.format(plan)).assert().isEqualTo(plan) }
        WaitHeader.format(plans[2]).assert()
            .isEqualTo("handled(order/payment-saga) > handled(audit) > snapshot(inventory)")
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "soon", "committed(", "handled", "handled()", "handled(a/b/c)", "committed >", "x(y)"])
    fun `what is not a plan does not parse`(value: String) {
        assertThrownBy<IllegalArgumentException> { WaitHeader.parse(value) }
    }

    @Test
    fun `timeouts are milliseconds, seconds, minutes or ISO-8601`() {
        WaitHeader.parseTimeout("500ms").assert().isEqualTo(500.milliseconds)
        WaitHeader.parseTimeout("10s").assert().isEqualTo(10.seconds)
        WaitHeader.parseTimeout("2m").assert().isEqualTo(2.minutes)
        WaitHeader.parseTimeout("PT1.5S").assert().isEqualTo(1500.milliseconds)
        WaitHeader.formatTimeout(10.seconds).assert().isEqualTo("10s")
        WaitHeader.formatTimeout(1500.milliseconds).assert().isEqualTo("1500ms")
        listOf("0s", "PT0S", "-PT1S", "10", "ten seconds", "10h").forEach { value ->
            assertThrownBy<IllegalArgumentException> { WaitHeader.parseTimeout(value) }
        }
    }

    @Test
    fun `Wow-Scope members are optional strings or tokens`() {
        ScopeHeader.parse("""tenant="t1", owner="u1", space="s1"""")
            .assert().isEqualTo(TargetScope("t1", Scope("u1", "s1")))
        ScopeHeader.parse("owner=u1").assert().isEqualTo(TargetScope(null, Scope("u1")))
        ScopeHeader.parse("""space="a \"b\" \\ c"""").scope.spaceId.assert().isEqualTo("""a "b" \ c""")
        ScopeHeader.parse("""owner="u1",owner="u2"""").scope.ownerId.assert().isEqualTo("u2")
        val target = TargetScope("t1", Scope("u\"1", "s1"))
        ScopeHeader.parse(ScopeHeader.format(target)).assert().isEqualTo(target)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "", "owner", "owner=", "region=eu", "owner=u1;x=1", "owner=\"u1", "owner=u1,", "Owner=u1",
            "owner=\"\"", "owner=1", "owner=u1 space=s1",
        ],
    )
    fun `what is not such a dictionary does not parse`(value: String) {
        assertThrownBy<IllegalArgumentException> { ScopeHeader.parse(value) }
    }

    @Test
    fun `request facts cannot take a reserved name`() {
        assertThrownBy<IllegalArgumentException> { WowHttpOptions(requestFacts = setOf("Wow-Tenant")) }
        assertThrownBy<IllegalArgumentException> { WowHttpOptions(errorStatuses = mapOf("x" to 200)) }
    }
}
