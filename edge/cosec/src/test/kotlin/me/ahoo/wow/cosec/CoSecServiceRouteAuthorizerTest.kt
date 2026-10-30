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
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.runtime.admission.Authorization
import me.ahoo.wow.runtime.admission.RouteAction
import org.junit.jupiter.api.Test
import reactor.util.context.Context

/** [CoSecServiceRouteAuthorizer]: CoSec's decision on a service route call as the caller of its request. */
class CoSecServiceRouteAuthorizerTest {
    private val cosec = CoSecPipeline()
    private val authorizer = CoSecServiceRouteAuthorizer(cosec.checker)
    private val action = RouteAction(CONTEXT, "summary", "GET", "/shop/summaries/mon", "acme", Scope("alice"))
    private val caller = Caller(tenantId = "acme", scope = Scope("alice"), subject = "alice")

    private suspend fun authorize(caller: Caller = this.caller): Authorization {
        val security = cosec.security("alice;acme").also { cosec.asked.clear() }
        return withContext(ReactorContext(Context.of(SecurityContext.KEY, security))) {
            authorizer.authorize(caller, action)
        }
    }

    @Test
    fun `a call is the action of its context and route on the route`() = runTest {
        authorize().assert().isEqualTo(Authorization.Allowed)
        cosec.asked.single().toString().assert().isEqualTo("shop:summary route/summary")
    }

    @Test
    fun `a call CoSec does not permit is denied`() = runTest {
        cosec.permits = { false }
        (authorize() as Authorization.Denied).message.assert()
            .isEqualTo("CoSec does not permit shop:summary on route/summary.")
    }

    @Test
    fun `a request-less caller gets no objection`() = runTest {
        cosec.permits = { false }
        authorizer.authorize(caller, action).assert().isEqualTo(Authorization.Allowed)
        authorize(caller.copyWith(subject = "bob")).assert().isEqualTo(Authorization.Allowed)
        cosec.asked.assert().isEmpty()
    }
}
