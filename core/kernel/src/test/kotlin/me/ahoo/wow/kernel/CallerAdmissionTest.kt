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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.caller.TargetScope
import me.ahoo.wow.api.decide.RequestFacts
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.spi.tenant.TenantPolicy
import org.junit.jupiter.api.Test

class CallerAdmissionTest {
    private val alice = Caller.of(
        tenantId = "acme",
        scope = Scope("alice"),
        subject = "alice",
        attributes = mapOf(Caller.APP to listOf(" console ")),
    )

    @Test
    fun `a caller's command is in its scope, by its operator, from its application`() {
        val own = CallerAdmission.of(alice)
        own.scope.assert().isEqualTo(Scope("alice"))
        own.operator.assert().isEqualTo(Operator.of("alice", "acme"))
        own.facts.assert().isEqualTo(mapOf(RequestFacts.APP_ID to "console"))
        CallerAdmission.factsOf(alice).assert().isEqualTo(own.facts)
    }

    @Test
    fun `a caller acting in a target is in the target's scope, its operator acting elsewhere`() {
        val elsewhere = CallerAdmission.of(alice, TargetScope.of("acme", Scope("bob")))
        elsewhere.scope.assert().isEqualTo(Scope("bob"))
        elsewhere.operator.assert().isEqualTo(Operator.of("alice", "acme", true))
        elsewhere.assert().isEqualTo(CallerAdmission.of(alice, TargetScope.of("acme", Scope("bob"))))
        elsewhere.hashCode().assert().isEqualTo(
            CallerAdmission.of(alice, TargetScope.of("acme", Scope("bob"))).hashCode()
        )
        elsewhere.toString().assert().contains("bob")
        elsewhere.assert().isNotEqualTo(CallerAdmission.of(alice)).isNotEqualTo("alice")
    }

    @Test
    fun `an anonymous caller records no operator and no fact`() {
        val anonymous = CallerAdmission.of(Caller.ANONYMOUS)
        anonymous.scope.assert().isEqualTo(Scope.NONE)
        anonymous.operator.assert().isNull()
        anonymous.facts.assert().isEmpty()
    }

    @Test
    fun `a caller with a blank application records none`() {
        val blank = Caller.of(subject = "alice", attributes = mapOf(Caller.APP to listOf(" ")))
        CallerAdmission.factsOf(blank).assert().isEmpty()
    }

    @Test
    fun `an application longer than a fact may be is cut to the most a command records`() {
        val long = "a".repeat(CallerAdmission.MAX_FACT_LENGTH + 1)
        val caller = Caller.of(subject = "alice", attributes = mapOf(Caller.APP to listOf(long)))
        CallerAdmission.factsOf(caller).assert()
            .isEqualTo(mapOf(RequestFacts.APP_ID to long.take(CallerAdmission.MAX_FACT_LENGTH)))
    }

    @Test
    fun `the facts admission derives from the caller are its application's alone`() {
        CallerAdmission.FACTS.assert().containsExactly(RequestFacts.APP_ID)
    }

    @Test
    fun `a request's facts should take the caller's application in place of any it names itself`() {
        val inApp = CallerAdmission.of(alice)
        val anonymous = CallerAdmission.of(Caller.ANONYMOUS)
        val forged = mapOf(RequestFacts.APP_ID to "forged", "X-Trace" to "t-1")
        inApp.headersOf(emptyMap()).assert().isEqualTo(mapOf(RequestFacts.APP_ID to "console"))
        inApp.headersOf(mapOf("X-Trace" to "t-1")).assert()
            .isEqualTo(mapOf("X-Trace" to "t-1", RequestFacts.APP_ID to "console"))
        inApp.headersOf(forged).assert().isEqualTo(mapOf("X-Trace" to "t-1", RequestFacts.APP_ID to "console"))
        anonymous.headersOf(forged).assert().isEqualTo(mapOf("X-Trace" to "t-1"))
    }

    @Test
    fun `a request's facts should be kept as they are when the caller has nothing to add or drop`() {
        val facts = mapOf("X-Trace" to "t-1")
        CallerAdmission.of(Caller.ANONYMOUS).headersOf(facts).assert().isSameAs(facts)
    }

    private val cart = AggregateType("shop", "cart")

    private fun refused(answer: CallerAnswer): Pair<String, String> =
        (answer as CallerAdmission.Refused).let { it.code to it.message }

    @Test
    fun `a caller is admitted in its own tenant and scope, or a cross-scope caller in its target's`() {
        val own = CallerAdmission.admit(cart, alice, null, "acme", TenantPolicy.CALLERS) as CallerAdmission
        own.assert().isEqualTo(CallerAdmission.of(alice))
        own.tenantId.assert().isEqualTo("acme")
        own.equals(own).assert().isTrue()
        CallerAdmission.admit(cart, alice, null, null, TenantPolicy.CALLERS).assert().isEqualTo(own)
        val admin = Caller.of(tenantId = "ops", subject = "admin", crossScope = true)
        val target = TargetScope.of("acme", Scope("bob"))
        val elsewhere = CallerAdmission.admit(cart, admin, target, "acme", TenantPolicy.CALLERS) as CallerAdmission
        listOf(elsewhere.tenantId, elsewhere.scope).assert().isEqualTo(listOf("acme", Scope("bob")))
        elsewhere.assert().isNotEqualTo(CallerAdmission.of(admin, target, tenantId = "ops"))
        // A type of a fixed tenant is in it, whoever sends the command.
        val fixed = TenantPolicy.fixed(mapOf(cart to "shared"))
        (CallerAdmission.admit(cart, alice, null, null, fixed) as CallerAdmission).tenantId.assert().isEqualTo("shared")
    }

    @Test
    fun `a caller naming a target without crossing scopes is forbidden, unless it is a service's call`() {
        val target = TargetScope.of("acme", Scope("bob"))
        refused(CallerAdmission.admit(cart, alice, target, null, TenantPolicy.CALLERS)).assert()
            .isEqualTo(FrameworkCodes.AUTH_FORBIDDEN to "The caller may not act in another scope.")
        (CallerAdmission.admit(cart, alice, target, null, TenantPolicy.CALLERS, called = true) as CallerAdmission)
            .scope.assert().isEqualTo(Scope("bob"))
    }

    @Test
    fun `a ref or a target naming another tenant than the one decided is malformed`() {
        refused(CallerAdmission.admit(cart, alice, null, "globex", TenantPolicy.CALLERS)).assert().isEqualTo(
            FrameworkCodes.COMMAND_MALFORMED to
                "The command's ref names tenant globex, but it is sent in tenant acme: a ref's tenant is the " +
                "caller's, a cross-scope target's or the one fixed for its type.",
        )
        val admin = Caller.of(tenantId = "ops", subject = "admin", crossScope = true)
        refused(
            CallerAdmission.admit(
                cart,
                admin,
                TargetScope.of("acme", Scope.NONE),
                null,
                TenantPolicy.fixed(mapOf(cart to "shared")),
            ),
        ).assert().isEqualTo(
            FrameworkCodes.COMMAND_MALFORMED to "The tenant of shop.cart is fixed to shared; the request names acme.",
        )
    }
}
