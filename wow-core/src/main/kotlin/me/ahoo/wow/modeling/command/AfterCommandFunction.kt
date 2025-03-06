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

import me.ahoo.wow.api.annotation.AfterCommand
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.messaging.function.MessageFunction
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono

class AfterCommandFunction<C : Any>(val delegate: MessageFunction<C, ServerCommandExchange<*>, Mono<*>>?) {
    companion object {
        val NOP = AfterCommandFunction<Any>(null)
    }

    private val commands: Set<Class<*>> = delegate?.getAnnotation(AfterCommand::class.java)
        ?.commands
        ?.map { it.java }
        ?.toHashSet()
        ?: emptySet()

    fun matchCommand(commandType: Class<*>): Boolean {
        if (commands.isEmpty()) {
            return true
        }
        return commands.contains(commandType)
    }

    inline fun afterCommand(
        exchange: ServerCommandExchange<*>,
        commandMono: () -> Mono<*>
    ): Mono<*> {
        if (delegate == null || !matchCommand(exchange.message.body.javaClass)) {
            return commandMono()
        }
        return commandMono().flatMap { commandEvent ->
            afterCommand(exchange, commandEvent)
        }
    }

    fun afterCommand(exchange: ServerCommandExchange<*>, commandEvent: Any): Mono<*> {
        if (delegate == null || !matchCommand(exchange.message.body.javaClass)) {
            return commandEvent.toMono()
        }
        return delegate.invoke(exchange).map { afterEvent ->
            mergeEvents(commandEvent, afterEvent)
        }.switchIfEmpty(commandEvent.toMono())
    }
}

private fun mergeEvents(commandEvent: Any, afterEvent: Any): Any {
    val commandEvents: List<Any> = unfoldEvent(commandEvent)
    val afterEvents: List<Any> = unfoldEvent(afterEvent)
    return commandEvents + afterEvents
}

@Suppress("UNCHECKED_CAST")
private fun unfoldEvent(event: Any): List<Any> {
    return when (event) {
        is Iterable<*> -> {
            event.toList() as List<Any>
        }

        is Array<*> -> {
            event.toList() as List<Any>
        }

        else -> {
            listOf(event)
        }
    }
}
