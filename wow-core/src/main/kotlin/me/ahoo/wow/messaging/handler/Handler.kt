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

package me.ahoo.wow.messaging.handler

import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono

fun interface Handler<T : MessageExchange<*, *>> {
    fun handle(exchange: T): Mono<Void>
}

fun interface ErrorHandler<T : MessageExchange<*, *>> {
    fun handle(exchange: T, throwable: Throwable): Mono<Void>
}

class LogErrorHandler<T : MessageExchange<*, *>> : ErrorHandler<T> {
    companion object {
        private val log = LoggerFactory.getLogger(LogErrorHandler::class.java)
    }

    override fun handle(exchange: T, throwable: Throwable): Mono<Void> {
        if (log.isErrorEnabled) {
            log.error(throwable.message, throwable)
        }
        return Mono.error(throwable)
    }
}

class LogResumeErrorHandler<T : MessageExchange<*, *>> : ErrorHandler<T> {
    companion object {
        private val log = LoggerFactory.getLogger(LogResumeErrorHandler::class.java)
    }

    override fun handle(exchange: T, throwable: Throwable): Mono<Void> {
        if (log.isErrorEnabled) {
            log.error(throwable.message, throwable)
        }
        return Mono.empty()
    }
}

abstract class AbstractHandler<T : MessageExchange<*, *>>(
    private val chain: FilterChain<T>,
    private val errorHandler: ErrorHandler<T>
) :
    Handler<T> {
    override fun handle(exchange: T): Mono<Void> {
        return chain.filter(exchange)
            .onErrorResume {
                exchange.setError(it)
                errorHandler.handle(exchange, it)
            }
    }
}
