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

package me.ahoo.wow.runtime.admission

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import org.junit.jupiter.api.Test

/** A node's service route authorizers, all asked as its command authorizers are: the first denial is the answer. */
class ServiceRouteAuthorizersTest {
    private val action = RouteAction.of("shop", "summary", "GET", "/shop/summaries", "t", Scope.NONE)
    private val asked = mutableListOf<String>()

    private fun authorizer(name: String, allows: Boolean) = ServiceRouteAuthorizer { _, _ ->
        asked += name
        if (allows) Authorization.Allowed else Authorization.Denied.of("$name objects.")
    }

    @Test
    fun `none should allow every call, and one should be itself`(): Unit = runBlocking {
        ServiceRouteAuthorizer.allOf(emptyList()).assert().isSameAs(ServiceRouteAuthorizer.ALLOW_ALL)
        ServiceRouteAuthorizer.ALLOW_ALL.authorize(Caller.ANONYMOUS, action).assert().isEqualTo(Authorization.Allowed)
        val one = authorizer("one", true)
        ServiceRouteAuthorizer.allOf(listOf(one)).assert().isSameAs(one)
    }

    @Test
    fun `the first denial in order should be the answer, and the rest not asked`(): Unit = runBlocking {
        val all = ServiceRouteAuthorizer.allOf(
            listOf(authorizer("a", true), authorizer("b", false), authorizer("c", false)),
        )
        (all.authorize(Caller.ANONYMOUS, action) as Authorization.Denied).message.assert().isEqualTo("b objects.")
        asked.assert().containsExactly("a", "b")
        all.toString().assert().startsWith("ServiceRouteAuthorizer.allOf(")
    }

    @Test
    fun `a call every authorizer has no objection to should be allowed`(): Unit = runBlocking {
        ServiceRouteAuthorizer.allOf(listOf(authorizer("a", true), authorizer("b", true)))
            .authorize(Caller.ANONYMOUS, action).assert().isEqualTo(Authorization.Allowed)
        asked.assert().containsExactly("a", "b")
    }
}
