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

package me.ahoo.wow.api.decide

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.caller.Operator
import org.junit.jupiter.api.Test

class CommandTest {
    private val ref = AggregateType("sales", "order").ref("tenant", "o-1")

    @Test
    fun `a command has no scope and no causation unless admission gives them`() {
        val command = Command("c-1", "r-1", ref, "body")
        command.scope.assert().isEqualTo(Scope.NONE)
        command.causation.assert().isNull()
        command.headers.assert().isEmpty()
    }

    @Test
    fun `a scope's present parts are not blank`() {
        Scope("owner", null).ownerId.assert().isEqualTo("owner")
        Scope(null, "space").spaceId.assert().isEqualTo("space")
        assertThrownBy<IllegalArgumentException> { Scope(ownerId = " ") }
        assertThrownBy<IllegalArgumentException> { Scope(spaceId = "") }
    }

    @Test
    fun `the recorded caller acts in the aggregate's tenant and the command's scope, as its operator`() {
        val scope = Scope(ownerId = "alice", spaceId = "s-1")
        val command = Command("c-3", "r-3", ref, "body", scope = scope, operator = Operator.of("alice", "tenant"))
        command.recordedCaller.assert().isEqualTo(Caller.of("tenant", scope, subject = "alice"))
        command.recordedCaller.attributes.assert().isEmpty()
    }

    @Test
    fun `an operator acting in another tenant is recorded in the aggregate's, not its own`() {
        val scope = Scope(ownerId = "bob")
        val admin = Operator.of("admin", "headquarters", actedElsewhere = true)
        val command = Command("c-4", "r-4", ref, "body", scope = scope, operator = admin)
        command.recordedCaller.apply {
            tenantId.assert().isEqualTo("tenant")
            this.scope.assert().isEqualTo(scope)
            subject.assert().isEqualTo("admin")
            crossScope.assert().isFalse()
        }
    }

    @Test
    fun `the recorded caller is never a service identity, and anonymous without an operator`() {
        val billing = Command("c-5", "r-5", ref, "body", operator = Operator.of("service:billing", "tenant"))
        billing.recordedCaller.subject.assert().isEqualTo("service:billing")
        billing.recordedCaller.service.assert().isFalse()
        Command("c-6", "r-6", ref, "body", operator = Operator.system("timer", "tenant")).recordedCaller.apply {
            subject.assert().isEqualTo("system:timer")
            service.assert().isFalse()
        }
        Command("c-7", "r-7", ref, "body").recordedCaller.apply {
            subject.assert().isNull()
            tenantId.assert().isEqualTo("tenant")
            scope.assert().isEqualTo(Scope.NONE)
        }
    }

    @Test
    fun `a causation names its root and its direct upstream`() {
        val causation = Causation("root", "sales.order/tenant/o-1@3")
        Command("c-2", "r-2", ref, "body", causation = causation).causation.assert().isEqualTo(causation)
        assertThrownBy<IllegalArgumentException> { Causation(" ", "upstream") }
        assertThrownBy<IllegalArgumentException> { Causation("root", "") }
    }
}
