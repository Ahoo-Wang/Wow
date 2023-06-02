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

package me.ahoo.wow.event

import me.ahoo.wow.messaging.handler.AbstractHandler
import me.ahoo.wow.messaging.handler.ErrorHandler
import me.ahoo.wow.messaging.handler.FilterChain
import me.ahoo.wow.messaging.handler.Handler
import me.ahoo.wow.messaging.handler.LogResumeErrorHandler

interface EventHandler : Handler<DomainEventExchange<*>>
interface DomainEventHandler : EventHandler

class DefaultDomainEventHandler(
    chain: FilterChain<DomainEventExchange<*>>,
    errorHandler: ErrorHandler<DomainEventExchange<*>> = LogResumeErrorHandler()
) : DomainEventHandler,
    AbstractHandler<DomainEventExchange<*>>(
        chain,
        errorHandler,
    )
