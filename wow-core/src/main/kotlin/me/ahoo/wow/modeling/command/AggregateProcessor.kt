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
import me.ahoo.wow.api.modeling.AggregateIdCapable
import me.ahoo.wow.api.modeling.NamedTypedAggregate
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.messaging.processor.MonoMessageProcessor
import reactor.core.publisher.Mono

/**
 * Processor for handling commands on a specific aggregate instance.
 *
 * This interface defines the contract for processing commands against a particular aggregate,
 * ensuring proper serialization and state management.
 *
 * @param C The type of the command aggregate root.
 */
interface AggregateProcessor<C : Any> :
    AggregateIdCapable,
    NamedTypedAggregate<C>,
    MonoMessageProcessor<C, ServerCommandExchange<*>, Mono<DomainEventStream>> {
    override val aggregateId: AggregateId

    /**
     * Processes a command exchange for this aggregate instance.
     *
     * Command processing must be serial to maintain consistency and prevent race conditions.
     *
     * @param exchange The command exchange to process.
     * @return A Mono containing the resulting domain event stream.
     * @throws CommandExpectVersionConflictException if there's a version conflict.
     */
    @Throws(CommandExpectVersionConflictException::class)
    override fun process(exchange: ServerCommandExchange<*>): Mono<DomainEventStream>
}
