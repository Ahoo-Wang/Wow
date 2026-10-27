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

package me.ahoo.wow.api.caller

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.decide.RequestFacts
import org.junit.jupiter.api.Test

class CallerTest {
    @Test
    fun `an anonymous caller is in the default tenant with no scope and records no operator`() {
        Caller.ANONYMOUS.tenantId.assert().isEqualTo(Caller.DEFAULT_TENANT)
        Caller.ANONYMOUS.scope.assert().isEqualTo(Scope.NONE)
        Caller.ANONYMOUS.subject.assert().isNull()
        Caller.ANONYMOUS.toOperator().assert().isNull()
        Caller.ANONYMOUS.toOperator(actingElsewhere = true).assert().isNull()
    }

    @Test
    fun `the operator is the subject in the caller's own tenant, and nothing else of it`() {
        val caller = Caller(
            tenantId = "t1",
            scope = Scope("alice", "home"),
            subject = "alice",
            crossScope = true,
            attributes = mapOf("roles" to listOf("admin")),
        )
        caller.toOperator().assert().isEqualTo(Operator("alice", "t1"))
        // Allowed to act elsewhere is not acting elsewhere: only a named target marks the operator.
        caller.toOperator(actingElsewhere = true).assert().isEqualTo(Operator("alice", "t1", crossScope = true))
    }

    @Test
    fun `a system operator names its component`() {
        Operator.system("timers").assert().isEqualTo(Operator("system:timers", Caller.DEFAULT_TENANT))
        Operator.system("ledger", "t1").tenantId.assert().isEqualTo("t1")
        assertThrownBy<IllegalArgumentException> { Operator.system(" ") }
    }

    @Test
    fun `callers and operators check their facts`() {
        assertThrownBy<IllegalArgumentException> { Caller(" ") }
        assertThrownBy<IllegalArgumentException> { Caller(subject = " ") }
        assertThrownBy<IllegalArgumentException> { Operator(" ", "t1") }
        assertThrownBy<IllegalArgumentException> { Operator("alice", "") }
    }

    @Test
    fun `a service identity names its subject, and no other caller is one`() {
        Caller.ANONYMOUS.service.assert().isFalse()
        Caller(subject = "service:basket", service = true).service.assert().isTrue()
        assertThrownBy<IllegalArgumentException> { Caller(service = true) }
    }

    @Test
    fun `a caller's command is in its scope, by its operator, from its application`() {
        val alice = Caller.of(tenantId = "acme", scope = Scope("alice"), subject = "alice", attributes = mapOf(Caller.APP to listOf(" console ")))
        val own = alice.originOf()
        own.scope.assert().isEqualTo(Scope("alice"))
        own.operator.assert().isEqualTo(Operator("alice", "acme"))
        own.facts.assert().isEqualTo(mapOf(RequestFacts.APP_ID to "console"))
        val elsewhere = alice.originOf(TargetScope.of("acme", Scope("bob")))
        elsewhere.scope.assert().isEqualTo(Scope("bob"))
        elsewhere.operator.assert().isEqualTo(Operator("alice", "acme", true))
        elsewhere.assert().isEqualTo(CommandOrigin(Scope("bob"), Operator("alice", "acme", true), own.facts))
        elsewhere.hashCode().assert().isEqualTo(CommandOrigin(Scope("bob"), Operator("alice", "acme", true), own.facts).hashCode())
        elsewhere.toString().assert().contains("bob")
        elsewhere.assert().isNotEqualTo(own)
        Caller.ANONYMOUS.originOf().assert().isEqualTo(CommandOrigin(Scope.NONE, null, emptyMap()))
    }
}
