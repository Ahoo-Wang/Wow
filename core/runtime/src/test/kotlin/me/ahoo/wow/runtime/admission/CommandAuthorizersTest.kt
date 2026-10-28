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
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.kernel.CommandType
import org.junit.jupiter.api.Test

/** The node's authorizers, all asked: the first denial is the answer, an allowed command every one's. */
class CommandAuthorizersTest {
    private val action = CommandAction.of(
        CommandType.of("shop.cart.AddItem", Any::class.java),
        AggregateType("shop", "cart").ref("t", "c-1"),
        Scope.NONE,
        Creation.NONE,
    )
    private val asked = mutableListOf<String>()

    private fun authorizer(name: String, allows: Boolean) = CommandAuthorizer { _, _ ->
        asked += name
        if (allows) Authorization.Allowed else Authorization.Denied.of("$name objects.")
    }

    @Test
    fun `none should allow every command, and one should be itself`() {
        CommandAuthorizer.allOf(emptyList()).assert().isSameAs(CommandAuthorizer.ALLOW_ALL)
        val one = authorizer("one", true)
        CommandAuthorizer.allOf(listOf(one)).assert().isSameAs(one)
    }

    @Test
    fun `the first denial in order should be the answer, and the rest not asked`(): Unit = runBlocking {
        val all = CommandAuthorizer.allOf(
            listOf(authorizer("a", true), authorizer("b", false), authorizer("c", false)),
        )
        (all.authorize(Caller.ANONYMOUS, action) as Authorization.Denied).message.assert().isEqualTo("b objects.")
        asked.assert().containsExactly("a", "b")
        all.toString().assert().startsWith("CommandAuthorizer.allOf(")
    }

    @Test
    fun `a command every authorizer has no objection to should be allowed`(): Unit = runBlocking {
        CommandAuthorizer.allOf(listOf(authorizer("a", true), authorizer("b", true)))
            .authorize(Caller.ANONYMOUS, action).assert().isEqualTo(Authorization.Allowed)
        asked.assert().containsExactly("a", "b")
    }
}
