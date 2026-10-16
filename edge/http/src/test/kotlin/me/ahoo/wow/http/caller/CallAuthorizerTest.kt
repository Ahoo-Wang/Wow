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

package me.ahoo.wow.http.caller

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.kernel.CommandType
import me.ahoo.wow.kernel.Creation
import me.ahoo.wow.runtime.admission.Authorization
import me.ahoo.wow.runtime.admission.CommandAction
import me.ahoo.wow.runtime.admission.CommandAuthorizer
import org.junit.jupiter.api.Test

/** A service identity's commands are authorized by the [CallAuthorizer], every other caller's by the host's (D226). */
class CallAuthorizerTest {
    private val action = CommandAction(
        CommandType("shop.cart.AddItem", Any::class.java),
        AggregateType("shop", "cart").ref("t", "c-1"),
        Scope.NONE,
        Creation.NONE,
    )
    private val denied = Authorization.Denied("Not this service's.")
    private val authorizer = CallAuthorizer { _, _ -> denied }.over(CommandAuthorizer.ALLOW_ALL)

    @Test
    fun `a service's command should be the call authorizer's, any other the host's`(): Unit = runBlocking {
        authorizer.authorize(Caller(subject = "service:basket", service = true), action).assert().isEqualTo(denied)
        authorizer.authorize(Caller(subject = "alice"), action).assert().isEqualTo(Authorization.Allowed)
        authorizer.authorize(Caller.ANONYMOUS, action).assert().isEqualTo(Authorization.Allowed)
        authorizer.toString().assert().contains("ServiceCallsAuthorizer")
    }
}
