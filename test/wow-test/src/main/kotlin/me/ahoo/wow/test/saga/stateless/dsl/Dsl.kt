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

package me.ahoo.wow.test.saga.stateless.dsl

import me.ahoo.wow.api.modeling.OwnerId
import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.command.factory.CommandMessageFactory
import me.ahoo.wow.command.factory.SimpleCommandBuilderRewriterRegistry
import me.ahoo.wow.command.factory.SimpleCommandMessageFactory
import me.ahoo.wow.ioc.ServiceProvider
import me.ahoo.wow.ioc.SimpleServiceProvider
import me.ahoo.wow.messaging.function.MessageFunction
import me.ahoo.wow.test.SagaVerifier.defaultCommandGateway
import me.ahoo.wow.test.dsl.InjectServiceCapable
import me.ahoo.wow.test.dsl.NameSpecCapable
import me.ahoo.wow.test.saga.stateless.StatelessSagaExpecter
import me.ahoo.wow.test.validation.TestValidator

interface StatelessSagaDsl<T : Any> : InjectPublicServiceCapable {
    fun on(
        serviceProvider: ServiceProvider = SimpleServiceProvider(),
        commandGateway: CommandGateway = defaultCommandGateway(),
        commandMessageFactory: CommandMessageFactory = SimpleCommandMessageFactory(
            validator = TestValidator,
            commandBuilderRewriterRegistry = SimpleCommandBuilderRewriterRegistry()
        ),
        block: WhenDsl<T>.() -> Unit
    )
}

interface WhenDsl<T : Any> : NameSpecCapable, InjectServiceCapable<Unit> {
    fun functionFilter(filter: (MessageFunction<*, *, *>) -> Boolean)
    fun functionName(functionName: String) {
        functionFilter {
            it.name == functionName
        }
    }

    fun whenEvent(
        event: Any,
        state: Any? = null,
        ownerId: String = OwnerId.DEFAULT_OWNER_ID,
        block: ExpectDsl<T>.() -> Unit
    )
}

interface ExpectDsl<T : Any> : StatelessSagaExpecter<T, ExpectDsl<T>>
