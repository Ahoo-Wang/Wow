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
    fun `it decides Wow's operational commands, by CoSec's policies of their actions`() {
        authorizer.decidesOperational.assert().isTrue()
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
                    CommandAction(
                        CommandType(name, Any::class.java),
                        type.ref("acme", id),
                        Scope("alice"),
                        Creation.NONE
                    )
                )
            }
            return cosec.asked.last().toString()
        }
        asked(Lifecycle.DELETE_AGGREGATE, AggregateType(CONTEXT, "cart"), "c-1")
            .assert().isEqualTo("shop:wow.lifecycle.DeleteAggregate cart/c-1")
        asked(SnapshotRebuilds.REBUILD_SNAPSHOT, AggregateType(CONTEXT, SnapshotRebuilds.AGGREGATE), "cart")
            .assert().isEqualTo("shop:wow.snapshot.RebuildSnapshot snapshot/cart")
        asked(ScheduleJobs.ENABLE_JOB, AggregateType(CONTEXT, ScheduleJobs.AGGREGATE), "report")
            .assert().isEqualTo("shop:wow.schedule.EnableJob schedule/report")
    }

    @Test
    fun `a request-less caller gets no objection, and CoSec is not asked (D298)`() = runTest {
        // A saga's, a timer's, a compensation's or a scheduled job's command: no request, so no security context.
        cosec.permits = { false }
        authorizer.authorize(editor(), action).assert().isEqualTo(Authorization.Allowed)
        cosec.asked.assert().isEmpty()
    }

    @Test
    fun `a caller of the request that is not its principal is denied, and CoSec is not asked (D485)`() = runTest {
        // What an application's CallerResolver beside CoSec's pipeline gives (another subject or tenant, or the
        // anonymous caller), or a command its code sends as another caller while it handles the request: CoSec cannot
        // evaluate it, and abstaining would open what the authorizer is on for.
        val security = alice()
        val foreign = listOf(
            editor().copyWith(subject = "bob"),
            editor().copyWith(tenantId = "other"),
            Caller.ANONYMOUS,
        )
        foreign.forEach { caller ->
            val denied = inRequest(security) { authorizer.authorize(caller, action) }
            (denied as Authorization.Denied).message.assert().isEqualTo(
                "CoSec cannot authorize shop:AddItem on cart/c-1: the caller is not the principal CoSec " +
                    "authenticated for the request. Send it as the request's caller, or decide such callers with an " +
                    "authorizer of your own in place of wow.cosec.authorizer.enabled.",
            )
        }
        cosec.asked.assert().isEmpty()
    }

    @Test
    fun `an operational command of a caller that is not the request's principal is denied too`() = runTest {
        val security = alice()
        val rebuild = CommandAction(
            CommandType(SnapshotRebuilds.REBUILD_SNAPSHOT, Any::class.java),
            AggregateType(CONTEXT, SnapshotRebuilds.AGGREGATE).ref("acme", "cart"),
            Scope.NONE,
            Creation.NONE,
        )
        val operator = Caller.of("acme", subject = "ops", roles = listOf("operator"))
        inRequest(security) { authorizer.authorize(operator, rebuild) }
            .assert().isInstanceOf(Authorization.Denied::class.java)
        cosec.asked.assert().isEmpty()
    }

    @Test
    fun `a service identity is request-less, the internal port alone taking it and CoSec not running there`() =
        runTest {
            cosec.permits = { false }
            inRequest(alice()) { authorizer.authorize(Caller(subject = "alice", service = true), action) }
                .assert().isEqualTo(Authorization.Allowed)
            cosec.asked.assert().isEmpty()
        }

    @Test
    fun `a security context CoSec did not build is no request of the caller's`() = runTest {
        // CoSec's Reactor context reads only the contexts its pipeline made: one an application's code built is none.
        cosec.permits = { false }
        inRequest(unproven("alice", roles = listOf("editor"))) { authorizer.authorize(editor(), action) }
            .assert().isEqualTo(Authorization.Allowed)
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
