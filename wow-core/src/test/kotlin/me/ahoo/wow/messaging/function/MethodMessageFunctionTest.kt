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

import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.infra.accessor.method.reactive.MonoMethodAccessor
import me.ahoo.wow.tck.modeling.ChangeAggregateDependExternalService
import me.ahoo.wow.tck.modeling.CreateAggregate
import me.ahoo.wow.tck.modeling.ExternalService
import me.ahoo.wow.tck.modeling.MockAggregate
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.instanceOf
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.Test

internal class MethodMessageFunctionTest {
    @Test
    fun asMessageHandler() {
        val handler = MockAggregate::class.java.getDeclaredMethod(
            "onCommand",
            CreateAggregate::class.java,
        ).asFunctionMetadata<MockAggregate, Any>()
            .asMessageFunction<MockAggregate, ServerCommandExchange<*>, Any>(MockAggregate(GlobalIdGenerator.generateAsString()))

        assertThat(handler, notNullValue())
        assertThat(
            handler,
            instanceOf(
                SimpleMethodMessageFunction::class.java,
            ),
        )
        assertThat(
            handler.supportedType,
            equalTo(
                CreateAggregate::class.java,
            ),
        )
    }

    @Test
    fun asMessageHandlerWhenInjectable() {
        val handler = MockAggregate::class.java.getDeclaredMethod(
            "onCommand",
            ChangeAggregateDependExternalService::class.java,
            ExternalService::class.java,
        ).asFunctionMetadata<MockAggregate, Any>()
            .asMessageFunction<MockAggregate, ServerCommandExchange<*>, Any>(MockAggregate(GlobalIdGenerator.generateAsString()))

        assertThat(handler, notNullValue())
        assertThat(
            handler,
            instanceOf(
                InjectableMethodMessageFunction::class.java,
            ),
        )
        assertThat(
            handler.supportedType,
            equalTo(
                ChangeAggregateDependExternalService::class.java,
            ),
        )
    }

    @Test
    fun asMonoMessageHandler() {
        val handler = MockAggregate::class.java.getDeclaredMethod(
            "onCommand",
            CreateAggregate::class.java,
        ).asMonoFunctionMetadata<MockAggregate, Any>()
            .asMessageFunction<MockAggregate, ServerCommandExchange<*>, Any>(MockAggregate(GlobalIdGenerator.generateAsString()))

        assertThat(handler, notNullValue())
        assertThat(
            handler,
            instanceOf(
                SimpleMethodMessageFunction::class.java,
            ),
        )
        assertThat(
            handler.supportedType,
            equalTo(
                CreateAggregate::class.java,
            ),
        )
        assertThat(
            handler.metadata.accessor,
            instanceOf(
                MonoMethodAccessor::class.java,
            ),
        )
    }
}
