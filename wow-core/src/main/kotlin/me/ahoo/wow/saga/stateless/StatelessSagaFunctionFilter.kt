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

import me.ahoo.wow.event.dispatcher.DomainEventFunctionFilter
import me.ahoo.wow.filter.FilterType
import me.ahoo.wow.ioc.ServiceProvider

/**
 * Filter for stateless saga functions that applies to [StatelessSagaDispatcher].
 * This filter extends [DomainEventFunctionFilter] to provide filtering capabilities
 * specific to stateless saga processing.
 *
 * @param serviceProvider The service provider for dependency injection.
 */
@FilterType(StatelessSagaDispatcher::class)
class StatelessSagaFunctionFilter(
    serviceProvider: ServiceProvider
) : DomainEventFunctionFilter(serviceProvider)
