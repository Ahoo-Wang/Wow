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

package me.ahoo.wow.cosec

import kotlinx.coroutines.reactor.ReactorContext
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.runtime.http.CoSecParameters
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.api.schedule.ScheduleJobs
import me.ahoo.wow.kernel.CommandType
import me.ahoo.wow.runtime.admission.Authorization
import me.ahoo.wow.runtime.admission.CommandAction
import me.ahoo.wow.spi.snapshot.SnapshotRebuilds
import org.junit.jupiter.api.Test
import reactor.util.context.Context

/** [CoSecCommandAuthorizer]: CoSec's decision on a command as the caller of its request (decision D367). */
class CoSecCommandAuthorizerTest {
    private val cosec = CoSecPipeline()
    private val authorizer = CoSecCommandAuthorizer(cosec.checker)
    private val action = CommandAction(
        type = CommandType("$CONTEXT.cart.AddItem", AddItem::class.java),
        ref = AggregateRef(AggregateType(CONTEXT, "cart"), "acme", "c-1"),
        scope = Scope("alice", "s1"),
        creation = Creation.NONE,
    )

    /** [block] as the request [security] was permitted in: CoSec's filter writes it into the Reactor context. */
    private suspend fun <T> inRequest(security: SecurityContext, block: suspend () -> T): T =
        withContext(ReactorContext(Context.of(SecurityContext.KEY, security))) { block() }

    private suspend fun alice(): SecurityContext =
        cosec.security("alice;acme;editor@s1", mapOf(CoSecParameters.SPACE_ID to "s1")).also { cosec.asked.clear() }

    @Test
    fun `a command is the action of its context and name on its aggregate, in the request's scope`() = runTest {
        val security = alice()
        inRequest(security) { authorizer.authorize(editor(), action) }.assert().isEqualTo(Authorization.Allowed)
        val asked = cosec.asked.single()
        asked.toString().assert().isEqualTo("shop:AddItem cart/c-1")
        asked.principal.assert().isEqualTo("alice")
        asked.space.assert().isEqualTo("s1")
    }

    @Test
    fun `a command CoSec does not permit is denied`() = runTest {
        val security = alice()
        cosec.permits = { it.action != "shop:AddItem" }
        val denied = inRequest(security) { authorizer.authorize(editor(), action) }
        denied.assert().isInstanceOf(Authorization.Denied::class.java)
        (denied as Authorization.Denied).message.assert().isEqualTo("CoSec does not permit shop:AddItem on cart/c-1.")
    }

    @Test
    fun `a system command is its name as the Manifest lists it, on the aggregate it runs on`() = runTest {
        val security = alice()
        suspend fun asked(name: String, type: AggregateType, id: String): String {
            inRequest(security) {
                authorizer.authorize(
                    editor(),
                    CommandAction(CommandType(name, Any::class.java), type.ref("acme", id), Scope("alice"), Creation.NONE)
                )
            }
            return cosec.asked.last().toString()
        }
        asked(Lifecycle.DELETE_AGGREGATE, AggregateType(CONTEXT, "cart"), "c-1")
            .assert().isEqualTo("shop:wow.lifecycle.DeleteAggregate cart/c-1")
        asked(SnapshotRebuilds.REBUILD, AggregateType(CONTEXT, SnapshotRebuilds.AGGREGATE), "cart")
            .assert().isEqualTo("shop:wow.snapshot.Rebuild snapshot/cart")
        asked(ScheduleJobs.ENABLE, AggregateType(CONTEXT, ScheduleJobs.AGGREGATE), "report")
            .assert().isEqualTo("shop:wow.schedule.Enable schedule/report")
    }

    @Test
    fun `a request-less caller gets no objection, and CoSec is not asked (D298)`() = runTest {
        // A saga's, a timer's, a compensation's or a scheduled job's command: no request, so no security context.
        cosec.permits = { false }
        authorizer.authorize(editor(), action).assert().isEqualTo(Authorization.Allowed)
        cosec.asked.assert().isEmpty()
    }

    @Test
    fun `a caller that is not the request's principal is request-less`() = runTest {
        val security = alice()
        cosec.permits = { false }
        val others = listOf(
            editor().copyWith(subject = "bob"),
            editor().copyWith(tenantId = "other"),
            Caller(subject = "alice", service = true),
            Caller.ANONYMOUS,
        )
        others.forEach { caller ->
            inRequest(security) { authorizer.authorize(caller, action) }.assert().isEqualTo(Authorization.Allowed)
        }
        cosec.asked.assert().isEmpty()
    }

    @Test
    fun `the host's default tenant is CoSec's default tenant`() = runTest {
        val security = cosec.security("alice").also { cosec.asked.clear() }
        cosec.permits = { false }
        val home = CoSecCommandAuthorizer(cosec.checker, defaultTenant = "home")
        val caller = Caller("home", Scope("alice"), subject = "alice")
        inRequest(security) { home.authorize(caller, action) }.assert().isInstanceOf(Authorization.Denied::class.java)
        cosec.asked.single().toString().assert().isEqualTo("shop:AddItem cart/c-1")
    }
}
