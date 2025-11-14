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

package me.ahoo.wow.command.factory

import jakarta.validation.Validator
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.command.toCommandMessage
import me.ahoo.wow.command.validation.validateCommand
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono

/**
 * Simple implementation of CommandMessageFactory.
 *
 * This factory validates commands, applies registered rewriters, and converts
 * command builders to command messages. It supports both direct creation and
 * rewriter-based transformation of command builders.
 *
 * @param validator the validator for command validation
 * @param commandBuilderRewriterRegistry registry of command builder rewriters
 * @see CommandMessageFactory
 * @see Validator
 * @see CommandBuilderRewriterRegistry
 */
class SimpleCommandMessageFactory(
    private val validator: Validator,
    private val commandBuilderRewriterRegistry: CommandBuilderRewriterRegistry
) : CommandMessageFactory {
    /**
     * Creates a CommandMessage from a CommandBuilder, applying validation and rewriters.
     *
     * The process involves:
     * 1. Checking for registered rewriters for the command type
     * 2. If no rewriter found, directly convert to CommandMessage
     * 3. If rewriter found, validate the command and apply the rewriter
     * 4. Convert the rewritten builder to CommandMessage
     *
     * @param TARGET the type of the command body
     * @param commandBuilder the command builder to process
     * @return a Mono emitting the created CommandMessage
     * @throws RewriteNoCommandException if rewriter returns empty Mono
     * @see CommandBuilderRewriterRegistry.getRewriter
     * @see Validator.validateCommand
     */
    override fun <TARGET : Any> create(commandBuilder: CommandBuilder): Mono<CommandMessage<TARGET>> {
        val body = commandBuilder.body
        val rewriter = commandBuilderRewriterRegistry.getRewriter(body.javaClass)
            ?: return commandBuilder.toCommandMessage<TARGET>().toMono()
        validator.validateCommand(body)
        return rewriter.rewrite(commandBuilder)
            .checkpoint("Rewrite $rewriter [SimpleCommandMessageFactory]")
            .switchIfEmpty(
                Mono.defer {
                    RewriteNoCommandException(commandBuilder = commandBuilder, rewriter = rewriter).toMono()
                },
            )
            .map {
                it.toCommandMessage()
            }
    }
}
