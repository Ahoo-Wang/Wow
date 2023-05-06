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
package me.ahoo.wow.modeling.command

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.command.CommandBus
import me.ahoo.wow.configuration.MetadataSearcher
import me.ahoo.wow.configuration.asRequiredAggregateType
import me.ahoo.wow.ioc.ServiceProvider
import me.ahoo.wow.messaging.MessageDispatcher
import me.ahoo.wow.messaging.writeReceiverGroup
import me.ahoo.wow.modeling.annotation.asAggregateMetadata
import me.ahoo.wow.scheduler.AggregateSchedulerRegistrar

/**
 * Aggregate Command Dispatcher .

 * @author ahoo wang
 */
@Suppress("LongParameterList")
class CommandDispatcher(
    override val name: String = CommandDispatcher::class.simpleName!!,
    private val namedAggregates: Set<NamedAggregate> = MetadataSearcher.namedAggregateType.keys.toSet(),
    private val commandBus: CommandBus,
    private val aggregateProcessorFactory: AggregateProcessorFactory,
    private val commandHandler: CommandHandler,
    private val serviceProvider: ServiceProvider
) : MessageDispatcher {
    private val aggregateDispatchers = lazy {
        namedAggregates
            .map {
                val commandFlux = commandBus
                    .receive(setOf(it))
                    .writeReceiverGroup(name)

                val aggregateMetadata = it
                    .asRequiredAggregateType<Any>()
                    .asAggregateMetadata<Any, Any>()
                AggregateDispatcher(
                    aggregateMetadata = aggregateMetadata,
                    scheduler = AggregateSchedulerRegistrar.getOrInitialize(it),
                    commandFlux = commandFlux,
                    aggregateProcessorFactory = aggregateProcessorFactory,
                    commandHandler = commandHandler,
                    serviceProvider = serviceProvider
                )
            }
    }

    override fun run() {
        aggregateDispatchers.value.forEach { it.run() }
    }

    override fun close() {
        aggregateDispatchers.value.forEach { it.close() }
    }
}
