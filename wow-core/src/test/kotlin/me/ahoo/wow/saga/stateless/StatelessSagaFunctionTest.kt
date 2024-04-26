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

package me.ahoo.wow.saga.stateless

import io.mockk.every
import io.mockk.mockk
import me.ahoo.wow.api.annotation.Retry
import me.ahoo.wow.api.messaging.FunctionKind
import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.messaging.function.MessageFunction
import org.hamcrest.MatcherAssert
import org.hamcrest.Matchers
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono

class StatelessSagaFunctionTest {

    @Test
    fun getAnnotation() {
        val delegate = mockk<MessageFunction<Any, DomainEventExchange<*>, Mono<*>>> {
            every { getAnnotation(Retry::class.java) } returns null
            every { name } returns "test"
            every { supportedType } returns Any::class.java
            every { functionKind } returns FunctionKind.COMMAND
            every { contextName } returns "context"
            every { processor } returns "root"
            every { supportedTopics } returns emptySet()
        }
        MatcherAssert.assertThat(
            StatelessSagaFunction(delegate, mockk(), null).getAnnotation(Retry::class.java),
            Matchers.nullValue()
        )
    }
}
