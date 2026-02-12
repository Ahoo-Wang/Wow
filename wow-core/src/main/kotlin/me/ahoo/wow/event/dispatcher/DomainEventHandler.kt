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

package me.ahoo.wow.event.dispatcher

import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.filter.AbstractHandler
import me.ahoo.wow.filter.ErrorHandler
import me.ahoo.wow.filter.FilterChain
import me.ahoo.wow.filter.Handler
import me.ahoo.wow.filter.LogResumeErrorHandler

/**
 * Base interface for event handlers that process domain event exchanges.
 *
 * @see Handler
 * @see me.ahoo.wow.event.DomainEventExchange
 */
interface EventHandler : Handler<DomainEventExchange<*>>

/**
 * Interface for domain event handlers.
 *
 * This interface extends EventHandler to specifically handle domain events.
 * Implementations should process domain event exchanges through a filter chain.
 *
 * @see EventHandler
 * @see DomainEventExchange
 */
interface DomainEventHandler : EventHandler

/**
 * Default implementation of DomainEventHandler.
 *
 * This class provides a standard implementation of domain event handling using
 * a filter chain and error handling. It extends AbstractHandler to manage
 * the processing lifecycle.
 *
 * @param chain The filter chain to process domain events
 * @param errorHandler The error handler for processing failures (default: LogResumeErrorHandler)
 *
 * @see DomainEventHandler
 * @see AbstractHandler
 * @see FilterChain
 * @see ErrorHandler
 */
class DefaultDomainEventHandler(
    chain: FilterChain<DomainEventExchange<*>>,
    errorHandler: ErrorHandler<DomainEventExchange<*>> = LogResumeErrorHandler()
) : AbstractHandler<DomainEventExchange<*>>(
    chain,
    errorHandler,
),
    DomainEventHandler
