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
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.runtime.admission.Authorization
import me.ahoo.wow.runtime.admission.RouteAction
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import me.ahoo.cosec.api.authorization.Authorization as CoSecAuthorization

class CoSecServiceRouteAuthorizerTest {
    private val action = RouteAction(CONTEXT, "summary", "GET", "/shop/summaries/mon", "acme", Scope("alice", "s1"))
    private val caller = Caller(tenantId = "acme", scope = Scope("alice", "s1"), subject = "alice")

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
    fun `a call is evaluated as the request of the route's method and path`() = runTest {
        val cosec = Recording(Mono.just(result(true, "")))
        CoSecServiceRouteAuthorizer(cosec).authorize(caller, action).assert().isEqualTo(Authorization.Allowed)
        cosec.request.method.assert().isEqualTo("GET")
        cosec.request.path.assert().isEqualTo("/shop/summaries/mon")
        cosec.request.attributes[CoSecServiceRouteAuthorizer.ROUTE].assert().isEqualTo("shop.summary")
        cosec.request.attributes[CoSecCommandAuthorizer.OWNER].assert().isEqualTo("alice")
        cosec.request.attributes[CoSecCommandAuthorizer.SPACE].assert().isEqualTo("s1")
        cosec.context.principal.id.assert().isEqualTo("alice")
    }

    @Test
    fun `CoSec allows by the app role permission of the caller's application, in the caller's space`() = runTest {
        val cosec = AppRoleAuthorization()
        CoSecServiceRouteAuthorizer(
            cosec.authorization
        ).authorize(editor(), action).assert().isEqualTo(Authorization.Allowed)
        CoSecServiceRouteAuthorizer(cosec.authorization).authorize(editor(app = null), action).assert()
            .isEqualTo(Authorization.Denied(AuthorizeResult.IMPLICIT_DENY.reason))
        cosec.asked.assert().containsExactly("console" to "s1", "" to "s1")
    }

    @Test
    fun `a denial, or no decision, denies the call`() = runTest {
        CoSecServiceRouteAuthorizer(Recording(Mono.just(result(false, "no summaries"))))
            .authorize(caller, action).assert().isEqualTo(Authorization.Denied("no summaries"))
        CoSecServiceRouteAuthorizer(Recording(Mono.just(result(false, ""))))
            .authorize(caller, action).assert().isEqualTo(Authorization.Denied("CoSec denies shop.summary."))
        CoSecServiceRouteAuthorizer(Recording(Mono.empty()))
            .authorize(caller, action.copyOwnerless()).assert()
            .isEqualTo(Authorization.Denied("CoSec gave no decision on shop.summary."))
    }

    private fun RouteAction.copyOwnerless() = RouteAction(context, name, method, path, tenantId, Scope.NONE)
}
