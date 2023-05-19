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
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.ioc.ServiceProvider
import me.ahoo.wow.messaging.dispatcher.AggregateMessageDispatcher
import me.ahoo.wow.messaging.dispatcher.MessageParallelism
import me.ahoo.wow.messaging.dispatcher.MessageParallelism.asGroupKey
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler

/**
 * Aggregate Command Dispatcher Grouped by NamedAggregate.
 * ----
 * One AggregateId binds one Worker(Thread).
 * One Worker can be bound by multiple aggregateIds.
 * Workers have aggregate ID affinity.
 *
 * @author ahoo wang
 */
@Suppress("LongParameterList")
class AggregateCommandDispatcher<C : Any, S : Any>(
    val aggregateMetadata: AggregateMetadata<C, S>,
    override val parallelism: Int = MessageParallelism.DEFAULT_PARALLELISM,
    override val scheduler: Scheduler,
    override val messageFlux: Flux<ServerCommandExchange<Any>>,
    override val name: String =
        "${aggregateMetadata.aggregateName}-${AggregateCommandDispatcher::class.simpleName!!}",
    private val aggregateProcessorFactory: AggregateProcessorFactory,
    private val commandHandler: CommandHandler,
    private val serviceProvider: ServiceProvider
) : AggregateMessageDispatcher<ServerCommandExchange<Any>>() {

    override val namedAggregate: NamedAggregate
        get() = aggregateMetadata.namedAggregate

    override fun handleExchange(exchange: ServerCommandExchange<Any>): Mono<Void> {
        val aggregateId = exchange.message.aggregateId
        val aggregateProcessor = aggregateProcessorFactory.create(aggregateId, aggregateMetadata)
        exchange.setServiceProvider(serviceProvider)
        @Suppress("UNCHECKED_CAST")
        exchange.aggregateProcessor = aggregateProcessor as AggregateProcessor<Any>
        return commandHandler.handle(exchange)
    }

    override fun ServerCommandExchange<Any>.asGroupKey(): Int {
        return message.asGroupKey(parallelism)
    }
}
