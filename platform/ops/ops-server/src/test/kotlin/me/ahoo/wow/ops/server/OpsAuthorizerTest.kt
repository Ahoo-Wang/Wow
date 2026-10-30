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

import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.ledger.Ledger
import me.ahoo.wow.api.ledger.RetryParked
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.kernel.CommandType
import me.ahoo.wow.ops.api.parked.ApplyRetrySpec
import me.ahoo.wow.ops.api.parked.MarkRecoverable
import me.ahoo.wow.ops.api.parked.RecordParked
import me.ahoo.wow.ops.api.parked.Retry
import me.ahoo.wow.ops.api.parked.Skip
import me.ahoo.wow.query.QueryCaller
import me.ahoo.wow.query.error.QueryRequestException
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.policy.EventsMode
import me.ahoo.wow.runtime.admission.Authorization
import me.ahoo.wow.runtime.admission.CommandAction
import me.ahoo.wow.runtime.admission.CommandAuthorizer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class OpsAuthorizerTest {
    private val parked = AggregateType("ops", "parked").ref(Caller.DEFAULT_TENANT, "svc:Saga.on:shop.payment:(0):p-1")
    private val operator = Caller.of(
        subject = "alice",
        attributes = mapOf(OpsRoles.ROLES_ATTRIBUTE to listOf(OpsRoles.OPERATOR))
    )
    private val viewer = Caller.of(
        subject = "victor",
        attributes = mapOf(OpsRoles.ROLES_ATTRIBUTE to listOf(OpsRoles.VIEWER))
    )

    private fun action(name: String, type: Class<*>): CommandAction =
        CommandAction.of(CommandType.of(name, type), parked, Scope.NONE, Creation.NONE)

    @Test
    fun `an operator's actions and the system commands should draw no objection, a record a refusal`() {
        val authorizer = OpsAuthorizer()
        listOf(
            "ops.parked.Retry" to Retry::class.java,
            "ops.parked.Skip" to Skip::class.java,
            "ops.parked.MarkRecoverable" to MarkRecoverable::class.java,
            "ops.parked.ApplyRetrySpec" to ApplyRetrySpec::class.java,
            Ledger.RETRY to RetryParked::class.java,
        ).forEach { (name, type) ->
            // Who may act is the operators' authorizer's to say: this one objects to none of them, a viewer's either.
            runBlocking { authorizer.authorize(viewer, action(name, type)) }.assert().isEqualTo(Authorization.Allowed)
        }
        listOf("ops.parked.RecordParked", "ops.parked.RecordResolved", "ops.parked.RecordActionRefused").forEach {
            val denied = runBlocking { authorizer.authorize(operator, action(it, RecordParked::class.java)) }
            (denied as Authorization.Denied).message.assert().contains(it)
        }
    }

    @Test
    fun `the operator role should decide, and name who lacks it`() {
        val role: CommandAuthorizer = OperatorRole()
        val skip = action(Ledger.SKIP, RetryParked::class.java)
        runBlocking { role.authorize(operator, skip) }.assert().isEqualTo(Authorization.Allowed)
        (runBlocking { role.authorize(viewer, skip) } as Authorization.Denied).message.assert()
            .startsWith("victor may not ${Ledger.SKIP}")
        (runBlocking { role.authorize(Caller.ANONYMOUS, skip) } as Authorization.Denied).message.assert()
            .startsWith("An anonymous caller may not")
    }

    @Test
    fun `the reader roles should decide who reads the entries, anyone else refused as forbidden (UI-1)`(): Unit =
        runBlocking {
            val policy = ReaderRole()
            val entries = mockk<QueryContext<*>>()
            policy.evaluate(QueryCaller.IN_PROCESS, entries).assert().isEqualTo(MatchAllFilter)
            policy.evaluate(QueryCaller.of(operator), entries).assert().isEqualTo(MatchAllFilter)
            policy.evaluate(QueryCaller.of(viewer), entries).assert().isEqualTo(MatchAllFilter)
            val stranger = Caller.of(
                subject = "sam",
                attributes = mapOf(OpsRoles.ROLES_ATTRIBUTE to listOf("someone.else"))
            )
            listOf(Caller.of(subject = "nora"), stranger).forEach { reader ->
                val refused = assertThrows<QueryRequestException> { policy.evaluate(QueryCaller.of(reader), entries) }
                refused.errorCode.assert().isEqualTo(FrameworkCodes.AUTH_FORBIDDEN)
                refused.code.assert().isEqualTo(ReaderRole.READER_REQUIRED)
                refused.errorMsg.assert().startsWith("${reader.subject} reads no parked entry")
            }
            // It hides no entry, so the entries' events queries need not name their aggregates.
            policy.events.assert().isEqualTo(EventsMode.NONE)
        }
}
