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

package me.ahoo.wow.ops.server

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.ledger.Ledger
import me.ahoo.wow.api.ledger.ResumeParked
import me.ahoo.wow.kernel.CommandType
import me.ahoo.wow.kernel.Creation
import me.ahoo.wow.ops.api.parked.ApplyRetrySpec
import me.ahoo.wow.ops.api.parked.MarkRecoverable
import me.ahoo.wow.ops.api.parked.RecordParked
import me.ahoo.wow.ops.api.parked.Resume
import me.ahoo.wow.ops.api.parked.Skip
import me.ahoo.wow.runtime.admission.Authorization
import me.ahoo.wow.runtime.admission.CommandAction
import me.ahoo.wow.runtime.admission.CommandAuthorizer
import org.junit.jupiter.api.Test

class OpsAuthorizerTest {
    private val parked = AggregateType("ops", "parked").ref(Caller.DEFAULT_TENANT, "svc:Saga.on:shop.payment:(0):p-1")
    private val operator = Caller.of(
        subject = "alice",
        attributes = mapOf(OpsCallers.ROLES_ATTRIBUTE to listOf(OpsCallers.OPERATOR))
    )
    private val viewer = Caller.of(subject = "victor", attributes = mapOf(OpsCallers.ROLES_ATTRIBUTE to listOf("viewer")))

    private fun action(name: String, type: Class<*>): CommandAction =
        CommandAction(CommandType(name, type), parked, Scope.NONE, Creation.NONE)

    @Test
    fun `an operator's actions should be authorized as the ledger's commands of the same name`() {
        val seen = mutableListOf<CommandAction>()
        val authorizer = OpsAuthorizer { _, action ->
            seen += action
            Authorization.Allowed
        }
        listOf(
            "ops.parked.Resume" to Resume::class.java,
            "ops.parked.Skip" to Skip::class.java,
            "ops.parked.MarkRecoverable" to MarkRecoverable::class.java,
            "ops.parked.ApplyRetrySpec" to ApplyRetrySpec::class.java,
        ).forEach { (name, type) ->
            runBlocking { authorizer.authorize(operator, action(name, type)) }.assert().isEqualTo(Authorization.Allowed)
        }
        seen.map { it.type.name }.assert()
            .containsExactly(Ledger.RESUME, Ledger.SKIP, Ledger.MARK_RECOVERABLE, Ledger.APPLY_RETRY_SPEC)
        seen.map { it.type.type }.assert().containsExactlyElementsOf(
            listOf(Ledger.RESUME, Ledger.SKIP, Ledger.MARK_RECOVERABLE, Ledger.APPLY_RETRY_SPEC)
                .map { Ledger.COMMANDS.getValue(it) },
        )
        seen.map { it.ref }.toSet().assert().containsExactly(parked)
    }

    @Test
    fun `the framework's system commands should be the operators', and a record nobody's`() {
        val authorizer = OpsAuthorizer()
        val resume = action(Ledger.RESUME, ResumeParked::class.java)
        runBlocking { authorizer.authorize(operator, resume) }.assert().isEqualTo(Authorization.Allowed)
        runBlocking { authorizer.authorize(viewer, resume) }.assert().isInstanceOf(Authorization.Denied::class.java)
        val record = action("ops.parked.RecordParked", RecordParked::class.java)
        val denied = runBlocking { authorizer.authorize(operator, record) } as Authorization.Denied
        denied.message.assert().contains("ops.parked.RecordParked")
    }

    @Test
    fun `the operator role should decide, and name who lacks it`() {
        val role: CommandAuthorizer = OperatorRole()
        val skip = action(Ledger.SKIP, ResumeParked::class.java)
        runBlocking { role.authorize(operator, skip) }.assert().isEqualTo(Authorization.Allowed)
        (runBlocking { role.authorize(viewer, skip) } as Authorization.Denied).message.assert()
            .startsWith("victor may not ${Ledger.SKIP}")
        (runBlocking { role.authorize(Caller.ANONYMOUS, skip) } as Authorization.Denied).message.assert()
            .startsWith("An anonymous caller may not")
    }
}
