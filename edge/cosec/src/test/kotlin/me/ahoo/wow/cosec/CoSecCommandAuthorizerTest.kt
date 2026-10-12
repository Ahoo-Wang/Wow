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

import kotlinx.coroutines.test.runTest
import me.ahoo.cosec.api.authorization.AuthorizeResult
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.api.tenant.Tenant
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.kernel.CommandType
import me.ahoo.wow.kernel.Creation
import me.ahoo.wow.runtime.FrameworkCodes
import me.ahoo.wow.runtime.admission.Authorization
import me.ahoo.wow.runtime.admission.Caller
import me.ahoo.wow.runtime.admission.CommandAction
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import java.net.URI
import me.ahoo.cosec.api.authorization.Authorization as CoSecAuthorization

class CoSecCommandAuthorizerTest {
    private val action = CommandAction(
        type = CommandType("AddItem", AddItem::class.java),
        ref = AggregateRef(AggregateType(CONTEXT, "cart"), "acme", "c-1"),
        scope = Scope("alice", "s1"),
        creation = Creation.ALLOWED,
    )
    private val caller = Caller(
        tenantId = "acme",
        scope = Scope("alice", "s1"),
        subject = "alice",
        attributes = mapOf(
            CoSecCallerResolver.ROLES to listOf("user"),
            CoSecCallerResolver.POLICIES to listOf("shop"),
            "abac.dept" to listOf("sales"),
        ),
    )

    /** A CoSec authorization answering [answer], recording what it was asked. */
    private class Recording(private val answer: Mono<AuthorizeResult>) : CoSecAuthorization {
        lateinit var request: Request
        lateinit var context: SecurityContext

        override fun authorize(request: Request, context: SecurityContext): Mono<AuthorizeResult> {
            this.request = request
            this.context = context
            return answer
        }
    }

    private fun result(authorized: Boolean, reason: String) = object : AuthorizeResult {
        override val authorized: Boolean = authorized
        override val reason: String = reason
    }

    @Test
    fun `the action is evaluated as the request of its command route`() = runTest {
        val cosec = Recording(Mono.just(result(true, "")))
        CoSecCommandAuthorizer(cosec).authorize(caller, action).assert().isEqualTo(Authorization.Allowed)
        val request = cosec.request
        request.path.assert().isEqualTo("/shop/cart/c-1/add-item")
        request.method.assert().isEqualTo("POST")
        request.attributes.assert().isEqualTo(
            mapOf(
                CoSecCommandAuthorizer.COMMAND to "shop.cart.AddItem",
                CoSecCommandAuthorizer.AGGREGATE_ID to "c-1",
                CoSecCommandAuthorizer.TENANT to "acme",
                CoSecCommandAuthorizer.OWNER to "alice",
                CoSecCommandAuthorizer.SPACE to "s1",
                CoSecCommandAuthorizer.CREATION to "ALLOWED",
            ),
        )
        request.getHeader(CoSecRequestFacts.APP_ID).assert().isEmpty()
        request.getQuery("q").assert().isEmpty()
        request.getCookieValue("c").assert().isEmpty()
        request.remoteIp.assert().isEmpty()
        request.origin.assert().isEqualTo(URI.create(""))
        request.referer.assert().isEqualTo(URI.create(""))
        request.withAttributes(mapOf("k" to "v")).attributes.assert().isEqualTo(mapOf("k" to "v"))
    }

    @Test
    fun `the principal is rebuilt from the caller`() = runTest {
        val cosec = Recording(Mono.just(result(true, "")))
        CoSecCommandAuthorizer(cosec).authorize(caller, action)
        val principal = cosec.context.principal
        principal.id.assert().isEqualTo("alice")
        principal.authenticated.assert().isTrue()
        principal.roles.assert().containsExactly("user")
        principal.policies.assert().containsExactly("shop")
        principal.attributes.assert().isEqualTo(mapOf("abac.dept" to listOf("sales")))
        cosec.context.tenant.tenantId.assert().isEqualTo("acme")
    }

    @Test
    fun `the host's default tenant is CoSec's, and a caller without a subject is anonymous`() = runTest {
        val cosec = Recording(Mono.just(result(true, "")))
        CoSecCommandAuthorizer(cosec, defaultTenant = "home").authorize(Caller(tenantId = "home"), action)
        cosec.context.tenant.tenantId.assert().isEqualTo(Tenant.DEFAULT_TENANT_ID)
        cosec.context.principal.anonymous.assert().isTrue()
    }

    @Test
    fun `a denial keeps CoSec's reason, and no decision is a denial`() = runTest {
        val authorizer = { answer: Mono<AuthorizeResult> -> CoSecCommandAuthorizer(Recording(answer)) }
        val denied = authorizer(Mono.just(result(false, "Explicit Deny"))).authorize(caller, action)
        denied.assert().isEqualTo(Authorization.Denied("Explicit Deny", FrameworkCodes.AUTH_FORBIDDEN))
        val blank = authorizer(Mono.just(result(false, ""))).authorize(caller, action) as Authorization.Denied
        blank.message.assert().isEqualTo("CoSec denies AddItem.")
        val none = authorizer(Mono.empty()).authorize(caller, action) as Authorization.Denied
        none.message.assert().isEqualTo("CoSec gave no decision on AddItem.")
    }
}
