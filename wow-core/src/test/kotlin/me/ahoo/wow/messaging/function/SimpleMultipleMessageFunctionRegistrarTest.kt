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
package me.ahoo.wow.messaging.function

import me.ahoo.wow.event.DomainEventExchange
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.empty
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.hasItems
import org.junit.jupiter.api.Test

internal class SimpleMultipleMessageFunctionRegistrarTest {

    @Test
    fun register() {
        val handler =
            MockFunction::class.java.getDeclaredMethod("onEvent", Body::class.java)
                .asFunctionMetadata<Any, Any>()
                .asMessageFunction<Any, DomainEventExchange<*>, Any>(MockFunction())
        val registrar = SimpleMultipleMessageFunctionRegistrar<MessageFunction<*, *, *>>()
        registrar.register(handler)
        var actual: Set<MessageFunction<*, *, *>?> = registrar.getFunctions(handler.supportedType)
        assertThat(actual.size, equalTo(1))
        assertThat(actual, hasItem(handler))

        // 重复注册相同 handler
        registrar.register(handler)
        actual = registrar.getFunctions(handler.supportedType)
        assertThat(actual.size, equalTo(1))
        assertThat(actual, hasItem(handler))
        val anotherHandler = MockAnotherFunction::class.java.getDeclaredMethod("onEvent", Body::class.java)
            .asFunctionMetadata<Any, Any>()
            .asMessageFunction<Any, DomainEventExchange<*>, Any>(MockFunction())
        registrar.register(anotherHandler)
        actual = registrar.getFunctions(handler.supportedType)
        assertThat(actual.size, equalTo(2))
        assertThat(actual, hasItems(handler, anotherHandler))
    }

    @Test
    fun unregister() {
        val handler = MockFunction::class.java.getDeclaredMethod("onEvent", Body::class.java)
            .asFunctionMetadata<Any, Any>()
            .asMessageFunction<Any, DomainEventExchange<*>, Any>(MockFunction())
        val registrar = SimpleMultipleMessageFunctionRegistrar<MessageFunction<*, *, *>>()
        registrar.register(handler)
        registrar.unregister(handler)
        val actual = registrar.getFunctions(handler.supportedType)
        assertThat(actual, empty())
    }
}
