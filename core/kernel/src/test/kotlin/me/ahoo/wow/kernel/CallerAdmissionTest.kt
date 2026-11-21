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
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.caller.TargetScope
import me.ahoo.wow.api.decide.RequestFacts
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
}
