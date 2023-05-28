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
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

internal class SimpleSingleMessageFunctionRegistrarTest {

    @Test
    fun register() {
        val registrar = SimpleSingleMessageFunctionRegistrar<MessageFunction<*, *, *>>()
        val handler = MockFunction::class.java.getDeclaredMethod("onEvent", MockEventBody::class.java)
            .asFunctionMetadata<Any, Any>()
            .asMessageFunction<Any, DomainEventExchange<*>, Any>(MockFunction())

        var actual = registrar.getFunction(handler.supportedType)
        assertThat(actual, nullValue())

        registrar.register(handler)
        actual = registrar.getFunction(handler.supportedType)
        assertThat(actual, equalTo(handler))

        val anotherHandler = MockAnotherFunction::class.java.getDeclaredMethod("onEvent", MockEventBody::class.java)
            .asFunctionMetadata<Any, Any>()
            .asMessageFunction<Any, DomainEventExchange<*>, Any>(MockFunction())

        registrar.register(anotherHandler)
        actual = registrar.getFunction(handler.supportedType)
        assertThat(actual, equalTo(anotherHandler))
    }

    @Test
    fun unregister() {
        val registrar = SimpleSingleMessageFunctionRegistrar<MessageFunction<*, *, *>>()
        val handler =
            MockFunction::class.java.getDeclaredMethod("onEvent", MockEventBody::class.java)
                .asFunctionMetadata<Any, Any>()
                .asMessageFunction<Any, DomainEventExchange<*>, Any>(MockFunction())

        registrar.register(handler)
        var actual = registrar.getFunction(handler.supportedType)
        assertThat(actual, equalTo(handler))

        registrar.unregister(handler)
        actual = registrar.getFunction(handler.supportedType)
        assertThat(actual, nullValue())
    }
}
