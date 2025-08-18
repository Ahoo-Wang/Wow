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

import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.command.factory.CommandMessageFactory
import me.ahoo.wow.ioc.ServiceProvider
import me.ahoo.wow.ioc.SimpleServiceProvider
import me.ahoo.wow.test.SagaVerifier.sagaVerifier
import me.ahoo.wow.test.dsl.AbstractDynamicTestBuilder

class DefaultStatelessSagaDsl<T : Any>(private val processorType: Class<T>) : StatelessSagaDsl<T>,
    AbstractDynamicTestBuilder() {
    override val publicServiceProvider: ServiceProvider = SimpleServiceProvider()

    override fun on(
        serviceProvider: ServiceProvider,
        commandGateway: CommandGateway,
        commandMessageFactory: CommandMessageFactory,
        block: WhenDsl<T>.() -> Unit
    ) {
        publicServiceProvider.copyTo(serviceProvider)
        val whenStage = processorType.sagaVerifier(
            serviceProvider = serviceProvider,
            commandGateway = commandGateway,
            commandMessageFactory = commandMessageFactory
        )
        val whenDsl = DefaultWhenDsl(whenStage)
        block(whenDsl)
        dynamicNodes.addAll(whenDsl.dynamicNodes)
    }
}
