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

package me.ahoo.wow.modeling.command.dispatcher

import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.filter.AbstractHandler
import me.ahoo.wow.filter.ErrorHandler
import me.ahoo.wow.filter.FilterChain
import me.ahoo.wow.filter.Handler
import me.ahoo.wow.filter.LogResumeErrorHandler

/**
 * Handler interface for processing command exchanges.
 *
 * Implementations of this interface are responsible for handling server command exchanges
 * through a filter chain, providing the main entry point for command processing.
 */
interface CommandHandler : Handler<ServerCommandExchange<*>>

/**
 * Default implementation of CommandHandler using a filter chain.
 *
 * This handler processes commands by passing them through a configured filter chain,
 * with built-in error handling capabilities.
 *
 * @param chain The filter chain to process commands through.
 * @param errorHandler The error handler for handling exceptions during command processing. Defaults to LogResumeErrorHandler.
 */
class DefaultCommandHandler(
    chain: FilterChain<ServerCommandExchange<*>>,
    errorHandler: ErrorHandler<ServerCommandExchange<*>> = LogResumeErrorHandler()
) : AbstractHandler<ServerCommandExchange<*>>(chain, errorHandler),
    CommandHandler
