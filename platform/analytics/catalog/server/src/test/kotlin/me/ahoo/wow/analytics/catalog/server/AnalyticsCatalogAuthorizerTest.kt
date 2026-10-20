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

package me.ahoo.wow.analytics.catalog.server

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.analytics.catalog.api.AnalyticsCatalogContext
import me.ahoo.wow.analytics.catalog.api.source.ApproveSensitivityChange
import me.ahoo.wow.analytics.catalog.api.source.DraftSource
import me.ahoo.wow.analytics.catalog.api.source.MarkSourceReviewed
import me.ahoo.wow.analytics.catalog.api.source.SyncSource
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.kernel.CommandType
import me.ahoo.wow.kernel.Creation
import me.ahoo.wow.query.QueryCaller
import me.ahoo.wow.query.QueryRequestException
import me.ahoo.wow.runtime.admission.Authorization
import me.ahoo.wow.runtime.admission.CommandAction
import me.ahoo.wow.runtime.admission.CommandAuthorizer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AnalyticsCatalogAuthorizerTest {
    private val source = AggregateType(AnalyticsCatalogContext.NAME, AnalyticsCatalogContext.SOURCE).ref("t", "s-1")

    private fun action(type: Class<*>): CommandAction = CommandAction(
        CommandType("${AnalyticsCatalogContext.NAME}.${AnalyticsCatalogContext.SOURCE}.${type.simpleName}", type),
        source,
        Scope.NONE,
        Creation.NONE,
    )

    private fun caller(vararg roles: String): Caller = Caller.of(
        subject = "u",
        attributes = mapOf(AnalyticsCatalogCallers.ROLES_ATTRIBUTE to roles.toList()),
    )

    @Test
    fun `an administrator should send the catalog's commands, an approver only the approval`(): Unit = runBlocking {
        val authorizer = AnalyticsCatalogAuthorizer()
        authorizer.authorize(caller(AnalyticsCatalogCallers.ADMIN), action(DraftSource::class.java))
            .assert().isEqualTo(Authorization.Allowed)
        authorizer.authorize(caller(AnalyticsCatalogCallers.ADMIN), action(ApproveSensitivityChange::class.java))
            .assert().isInstanceOf(Authorization.Denied::class.java)
        authorizer.authorize(caller(AnalyticsCatalogCallers.APPROVER), action(ApproveSensitivityChange::class.java))
            .assert().isEqualTo(Authorization.Allowed)
        authorizer.authorize(caller(AnalyticsCatalogCallers.APPROVER), action(DraftSource::class.java))
            .assert().isInstanceOf(Authorization.Denied::class.java)
        // An editor (an analyst) drafts, revises and reviews; marking reviewed is an approver's or an admin's.
        authorizer.authorize(caller(AnalyticsCatalogCallers.EDITOR), action(DraftSource::class.java))
            .assert().isEqualTo(Authorization.Allowed)
        authorizer.authorize(caller(AnalyticsCatalogCallers.EDITOR), action(MarkSourceReviewed::class.java))
            .assert().isInstanceOf(Authorization.Denied::class.java)
        authorizer.authorize(caller(AnalyticsCatalogCallers.APPROVER), action(MarkSourceReviewed::class.java))
            .assert().isEqualTo(Authorization.Allowed)
        authorizer.authorize(caller(AnalyticsCatalogCallers.ADMIN), action(MarkSourceReviewed::class.java))
            .assert().isEqualTo(Authorization.Allowed)
        authorizer.authorize(caller(AnalyticsCatalogCallers.EDITOR), action(SyncSource::class.java))
            .assert().isInstanceOf(Authorization.Denied::class.java)
        (authorizer.authorize(Caller.ANONYMOUS, action(DraftSource::class.java)) as Authorization.Denied)
            .message.assert().startsWith("An anonymous caller")
    }

    @Test
    fun `CoSec's policies should decide in place of the roles`(): Unit = runBlocking {
        val policies = CommandAuthorizer { _, _ -> Authorization.Denied("no policy") }
        AnalyticsCatalogAuthorizer(
            policies
        ).authorize(caller(AnalyticsCatalogCallers.ADMIN), action(DraftSource::class.java))
            .assert().isEqualTo(Authorization.Denied("no policy"))
    }

    @Test
    fun `the sources should be read by the catalog's roles and in-process code only`(): Unit = runBlocking {
        val policy = AnalyticsCatalogQueryPolicy()
        val context = io.mockk.mockk<me.ahoo.wow.query.filter.QueryContext<*>>()
        policy.evaluate(QueryCaller.IN_PROCESS, context).assert().isEqualTo(me.ahoo.wow.api.query.MatchAllFilter)
        policy.evaluate(QueryCaller(caller(AnalyticsCatalogCallers.VIEWER)), context)
            .assert().isEqualTo(me.ahoo.wow.api.query.MatchAllFilter)
        assertThrows<QueryRequestException> { policy.evaluate(QueryCaller(caller("other")), context) }
            .code.assert().isEqualTo(AnalyticsCatalogQueryPolicy.READER_REQUIRED)
        // It hides no row, so the catalog's events queries need not name their aggregates.
        policy.events.assert().isEqualTo(me.ahoo.wow.query.EventsMode.NONE)
    }
}
