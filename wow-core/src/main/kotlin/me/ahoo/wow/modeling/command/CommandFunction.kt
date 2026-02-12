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

import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.infra.Decorator
import me.ahoo.wow.messaging.function.MessageFunction
import me.ahoo.wow.modeling.command.after.AfterCommandFunction
import me.ahoo.wow.modeling.materialize
import reactor.core.publisher.Mono

/**
 * A concrete implementation of command function that wraps a delegate message function.
 *
 * This class provides command execution by delegating to an underlying message function,
 * with support for after-command functions and proper checkpointing for debugging.
 *
 * @param C The type of the command aggregate.
 * @property delegate The underlying message function that handles the command.
 * @param commandAggregate The command aggregate instance.
 * @param afterCommandFunctions List of after-command functions to execute.
 *
 * @constructor Creates a new CommandFunction with the specified delegate and configuration.
 */
class CommandFunction<C : Any>(
    override val delegate: MessageFunction<C, ServerCommandExchange<*>, Mono<*>>,
    commandAggregate: CommandAggregate<C, *>,
    afterCommandFunctions: List<AfterCommandFunction<C>>
) : AbstractCommandFunction<C>(commandAggregate, afterCommandFunctions),
    Decorator<MessageFunction<C, ServerCommandExchange<*>, Mono<*>>> {
    override val contextName: String = delegate.contextName
    override val name: String = delegate.name
    override val supportedType: Class<*> = delegate.supportedType
    override val supportedTopics: Set<NamedAggregate> = setOf(commandAggregate.materialize())
    override val processor: C = delegate.processor
    override val functionKind: FunctionKind = delegate.functionKind

    override fun <A : Annotation> getAnnotation(annotationClass: Class<A>): A? = delegate.getAnnotation(annotationClass)

    /**
     * Invokes the delegate command function with checkpointing for debugging.
     *
     * @param exchange The server command exchange.
     * @return A Mono containing the command result, with a checkpoint for reactive debugging.
     */
    override fun invokeCommand(exchange: ServerCommandExchange<*>): Mono<*> =
        delegate
            .invoke(exchange)
            .checkpoint(
                "[${commandAggregate.aggregateId}] Invoke $qualifiedName Command[${exchange.message.id}] [CommandFunction]",
            )

    override fun toString(): String = "CommandFunction(delegate=$delegate)"
}
