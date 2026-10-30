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

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.reactor.ReactorContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.SecurityScope
import me.ahoo.cosec.api.tenant.Tenant
import me.ahoo.cosec.runtime.AuthorizationChecker
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.cosec.CoSecCommandAuthorizer
import me.ahoo.wow.kernel.CommandType
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.ops.api.parked.RecordParked
import me.ahoo.wow.ops.api.parked.Retry
import me.ahoo.wow.query.QueryCaller
import me.ahoo.wow.query.error.QueryRequestException
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.policy.EventsMode
import me.ahoo.wow.query.policy.QueryPolicy
import me.ahoo.wow.runtime.admission.Authorization
import me.ahoo.wow.runtime.admission.CommandAction
import me.ahoo.wow.runtime.admission.CommandAuthorizer
import me.ahoo.wow.spring.boot.CoSecAutoConfiguration
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.core.env.MapPropertySource
import reactor.util.context.Context
import java.util.function.Supplier

@OptIn(InternalWowApi::class) // The host's settings, as the configuration reads them.
class OpsConfigurationTest {
    private val retry = CommandAction(
        CommandType("ops.parked.Retry", Retry::class.java),
        AggregateType("ops", "parked").ref(Caller.DEFAULT_TENANT, "p-1"),
        Scope.NONE,
        Creation.NONE,
    )

    /** A caller without the operator's role, whom only CoSec's policies may let act. */
    private val granted = Caller.of(subject = "grace")

    private val record = CommandAction(
        CommandType("ops.parked.RecordParked", RecordParked::class.java),
        AggregateType("ops", "parked").ref(Caller.DEFAULT_TENANT, "p-1"),
        Scope.NONE,
        Creation.NONE,
    )

    /** CoSec deciding here (enforce mode, its checker), permitting what [permits] says. */
    private fun checker(permits: Boolean): AuthorizationChecker = mockk {
        coEvery { check(any<SecurityContext>(), any<String>(), any<String>()) } returns permits
    }

    /** The security context CoSec's pipeline permitted [subject]'s request in, in CoSec's default tenant. */
    private fun request(subject: String): SecurityContext = mockk(relaxed = true) {
        every { scope } returns mockk<SecurityScope>(relaxed = true)
        every { principal.id } returns subject
        every { principal.anonymous } returns false
        every { tenant.tenantId } returns Tenant.DEFAULT_TENANT_ID
    }

    /**
     * The node's decision on [action] by [caller], within the request [security] when there is one: every authorizer
     * the ops service and the host register, all asked.
     */
    private fun authorize(
        checker: AuthorizationChecker?,
        authorizerEnabled: Boolean,
        caller: Caller = granted,
        action: CommandAction = retry,
        security: SecurityContext? = request(caller.subject!!),
    ): Authorization = configured(checker, authorizerEnabled) { context ->
        val authorizers = context.getBeanProvider(CommandAuthorizer::class.java).orderedStream().toList()
        runBlocking {
            val reactor = security?.let { Context.of(SecurityContext.KEY, it) } ?: Context.empty()
            withContext(ReactorContext(reactor)) { CommandAuthorizer.allOf(authorizers).authorize(caller, action) }
        }
    }

    /** Whether a query of the entries by [caller] passes every query policy the ops service registers. */
    private fun reads(checker: AuthorizationChecker?, authorizerEnabled: Boolean, caller: Caller = granted): Boolean =
        configured(checker, authorizerEnabled) { context ->
            val policies = context.getBeanProvider(QueryPolicy::class.java).orderedStream().toList()
            runBlocking {
                try {
                    policies.forEach { it.evaluate(QueryCaller.of(caller), mockk<QueryContext<*>>()) }
                    true
                } catch (refused: QueryRequestException) {
                    refused.errorCode.assert().isEqualTo(FrameworkCodes.AUTH_FORBIDDEN)
                    false
                }
            }
        }

    /** [use] of the ops service's configuration, with CoSec deciding here as [checker] says. */
    private fun <T> configured(
        checker: AuthorizationChecker?,
        authorizerEnabled: Boolean,
        use: (AnnotationConfigApplicationContext) -> T,
    ): T {
        val properties = mapOf<String, Any>("wow.cosec.authorizer.enabled" to authorizerEnabled)
        return AnnotationConfigApplicationContext().use { context ->
            context.environment.propertySources.addFirst(MapPropertySource("test", properties))
            context.registerBean(Manifest::class.java, Supplier { Manifest() })
            checker?.let { context.registerBean(AuthorizationChecker::class.java, Supplier { it }) }
            context.register(CoSecAutoConfiguration::class.java, OpsConfiguration::class.java)
            context.refresh()
            use(context)
        }
    }

    @Test
    fun `a record should be refused whoever decides who the operators are, CoSec allowing all`() {
        authorize(checker(permits = true), authorizerEnabled = true, action = record).assert()
            .isInstanceOf(Authorization.Denied::class.java)
        val operator = Caller.of(
            subject = "alice",
            attributes = mapOf(OpsRoles.ROLES_ATTRIBUTE to listOf(OpsRoles.OPERATOR))
        )
        authorize(null, authorizerEnabled = false, caller = operator, action = record).assert()
            .isInstanceOf(Authorization.Denied::class.java)
        authorize(null, authorizerEnabled = false, caller = operator).assert().isEqualTo(Authorization.Allowed)
    }

    @Test
    fun `CoSec's policies should decide who is an operator where CoSec decides and its authorizer is on`() {
        authorize(checker(permits = true), authorizerEnabled = true).assert().isEqualTo(Authorization.Allowed)
        authorize(checker(permits = false), authorizerEnabled = true).assert()
            .isInstanceOf(Authorization.Denied::class.java)
        // A command without a request of its own, a saga's, carries what admission authorized at its root.
        authorize(checker(permits = false), authorizerEnabled = true, security = null).assert()
            .isEqualTo(Authorization.Allowed)
    }

    @Test
    fun `the operator's role should decide behind the gateway, where CoSec decides nothing, or with it off`() {
        authorize(checker(permits = true), authorizerEnabled = false).assert()
            .isInstanceOf(Authorization.Denied::class.java)
        authorize(null, authorizerEnabled = true).assert().isInstanceOf(Authorization.Denied::class.java)
        authorize(null, authorizerEnabled = true, security = null).assert()
            .isInstanceOf(Authorization.Denied::class.java)
    }

    @Test
    fun `the reader roles should decide who reads, unless CoSec decides and its authorizer is on (UI-1)`() {
        // A caller with no ops role: CoSec's policies of the query routes may let it read; the roles never do.
        reads(checker(permits = true), authorizerEnabled = true).assert().isTrue()
        reads(checker(permits = true), authorizerEnabled = false).assert().isFalse()
        reads(null, authorizerEnabled = true).assert().isFalse()
        val viewer = Caller.of(
            subject = "victor",
            attributes = mapOf(OpsRoles.ROLES_ATTRIBUTE to listOf(OpsRoles.VIEWER))
        )
        reads(null, authorizerEnabled = false, caller = viewer).assert().isTrue()
    }

    @Test
    fun `the node's read policy should step aside for CoSec where it decides, else be the reader roles (UI-1)`() {
        val entries = mockk<QueryContext<*>>()
        val nobody = QueryCaller.of(Caller.of(subject = "nobody"))
        configured(checker(permits = true), authorizerEnabled = true) { context ->
            context.getBeanProvider(CoSecCommandAuthorizer::class.java).ifAvailable.assert().isNotNull()
            val policy = context.getBean(QueryPolicy::class.java)
            policy.assert().isSameAs(ReaderRole.COSEC_DECIDES)
            runBlocking {
                policy.evaluate(nobody, entries).assert().isEqualTo(MatchAllFilter)
                policy.evaluate(QueryCaller.IN_PROCESS, entries).assert().isEqualTo(MatchAllFilter)
            }
            // It hides no entry, so the entries' events queries need not name their aggregates there either.
            policy.events.assert().isEqualTo(EventsMode.NONE)
        }
        configured(null, authorizerEnabled = true) { context ->
            context.getBeanProvider(CoSecCommandAuthorizer::class.java).ifAvailable.assert().isNull()
            val policy = context.getBean(QueryPolicy::class.java)
            policy.assert().isInstanceOf(ReaderRole::class.java)
            runBlocking {
                assertThrows<QueryRequestException> { policy.evaluate(nobody, entries) }
                // Trusted in-process code, such as the ops saga or the node itself, reads every entry.
                policy.evaluate(QueryCaller.IN_PROCESS, entries).assert().isEqualTo(MatchAllFilter)
            }
        }
    }
}
