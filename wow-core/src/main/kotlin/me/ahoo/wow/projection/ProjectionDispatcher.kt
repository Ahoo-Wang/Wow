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

package me.ahoo.wow.projection

import me.ahoo.wow.event.AbstractEventDispatcher
import me.ahoo.wow.event.DomainEventBus
import me.ahoo.wow.eventsourcing.state.StateEventBus
import me.ahoo.wow.messaging.dispatcher.MessageParallelism
import me.ahoo.wow.scheduler.AggregateSchedulerSupplier
import me.ahoo.wow.scheduler.DefaultAggregateSchedulerSupplier
import reactor.core.publisher.Mono

class ProjectionDispatcher(
    /**
     * named like `applicationName.ProjectionDispatcher`
     */
    override val name: String,
    override val parallelism: Int = MessageParallelism.DEFAULT_PARALLELISM,
    override val domainEventBus: DomainEventBus,
    override val stateEventBus: StateEventBus,
    override val functionRegistrar: ProjectionFunctionRegistrar,
    override val eventHandler: ProjectionHandler,
    override val schedulerSupplier: AggregateSchedulerSupplier =
        DefaultAggregateSchedulerSupplier("ProjectionDispatcher")
) : AbstractEventDispatcher<Mono<*>>()
