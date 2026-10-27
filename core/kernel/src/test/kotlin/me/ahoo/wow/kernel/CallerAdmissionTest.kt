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
        elsewhere.hashCode().assert().isEqualTo(CallerAdmission.of(alice, TargetScope.of("acme", Scope("bob"))).hashCode())
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
}
