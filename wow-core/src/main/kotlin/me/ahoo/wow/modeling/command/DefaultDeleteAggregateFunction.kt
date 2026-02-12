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

import me.ahoo.wow.api.command.DefaultDeleteAggregate
import me.ahoo.wow.api.event.DefaultAggregateDeleted
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.modeling.command.after.AfterCommandFunction
import me.ahoo.wow.modeling.materialize
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono

/**
 * Default implementation of delete aggregate command function.
 *
 * This function handles the DefaultDeleteAggregate command by generating a DefaultAggregateDeleted event.
 * It provides a standard way to mark aggregates as deleted in the system.
 *
 * @param C The type of the command aggregate root.
 * @param commandAggregate The command aggregate instance this function belongs to.
 * @param afterCommandFunctions List of after-command functions to execute after deletion.
 */
class DefaultDeleteAggregateFunction<C : Any>(
    commandAggregate: CommandAggregate<C, *>,
    afterCommandFunctions: List<AfterCommandFunction<C>>
) : AbstractCommandFunction<C>(commandAggregate, afterCommandFunctions) {
    override val contextName: String = commandAggregate.contextName
    override val supportedType: Class<*> = DefaultDeleteAggregate::class.java
    override val supportedTopics: Set<NamedAggregate> = setOf(commandAggregate.materialize())
    override val processor: C = commandAggregate.commandRoot
    override val name: String = "${processor.javaClass.simpleName}.${supportedType.simpleName}"
    override val functionKind: FunctionKind = FunctionKind.COMMAND

    override fun <A : Annotation> getAnnotation(annotationClass: Class<A>): A? = null

    /**
     * Invokes the delete command by returning a DefaultAggregateDeleted event.
     *
     * @param exchange The server command exchange containing the delete command.
     * @return A Mono containing the DefaultAggregateDeleted event.
     */
    override fun invokeCommand(exchange: ServerCommandExchange<*>): Mono<*> = DefaultAggregateDeleted.toMono()
}
