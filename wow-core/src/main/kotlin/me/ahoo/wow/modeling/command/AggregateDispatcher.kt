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

import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.command.CommandBus
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.configuration.MetadataSearcher
import me.ahoo.wow.ioc.ServiceProvider
import me.ahoo.wow.messaging.dispatcher.AbstractMessageDispatcher
import me.ahoo.wow.messaging.dispatcher.HandledSignal
import me.ahoo.wow.messaging.writeReceiverGroup
import me.ahoo.wow.modeling.annotation.asAggregateMetadata
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import reactor.core.publisher.GroupedFlux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers
import java.time.Duration

/**
 * Aggregate Command Dispatcher .

 * @author ahoo wang
 */
class AggregateDispatcher(
    /**
     * named like `AggregateDispatcher`
     */
    override val name: String = "AggregateDispatcher",
    override val topics: Set<NamedAggregate> = MetadataSearcher.namedAggregateType.keys.toSet(),
    private val aggregateTtl: Duration,
    private val commandBus: CommandBus,
    private val aggregateProcessorFactory: AggregateProcessorFactory,
    private val commandHandler: CommandHandler,
    private val serviceProvider: ServiceProvider,
) : AbstractMessageDispatcher<HandledSignal>() {

    private companion object {
        private val log: Logger = LoggerFactory.getLogger(AggregateDispatcher::class.java)
    }

    private val scheduler: Scheduler = Schedulers.newParallel(AggregateDispatcher::class.java.simpleName)

    override fun start() {
        commandBus
            .receive(topics)
            .writeReceiverGroup(name)
            .groupBy { it.message.aggregateId }
            .flatMap { handleGroupedCommand(it) }
            .subscribe(this)
    }

    /**
     * One AggregateId binds one Worker(Thread).
     * One Worker can be bound by multiple aggregateIds.
     * Workers have aggregate ID affinity.
     *
     */
    private fun handleGroupedCommand(grouped: GroupedFlux<AggregateId, ServerCommandExchange<Any>>): Mono<HandledSignal> {
        if (log.isDebugEnabled) {
            log.debug(
                "[$name] Create {} GroupedFlux - Timeout {}.",
                grouped.key(),
                aggregateTtl,
            )
        }
        val aggregateId = grouped.key()

        @Suppress("UNCHECKED_CAST")
        val aggregateType: Class<Any> =
            MetadataSearcher.namedAggregateType[aggregateId.namedAggregate]!! as Class<Any>
        val aggregateMetadata = aggregateType.asAggregateMetadata<Any, Any>()
        val aggregateProcessor = aggregateProcessorFactory.create(aggregateId, aggregateMetadata)
        return grouped
            .publishOn(scheduler)
            .timeout(
                aggregateTtl,
                Mono.defer {
                    if (log.isDebugEnabled) {
                        log.debug(
                            "[$name] Clear {} group: has not received commands for {}.",
                            aggregateId,
                            aggregateTtl,
                        )
                    }
                    Mono.empty()
                },
            ).concatMap {
                it.serviceProvider = serviceProvider
                it.aggregateProcessor = aggregateProcessor
                commandHandler.handle(it)
            }.then(Mono.just(HandledSignal))
    }
}
