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

package me.ahoo.wow.filter

import io.github.oshai.kotlinlogging.KotlinLogging
import reactor.core.publisher.Mono

fun interface Handler<T> {
    fun handle(context: T): Mono<Void>
}

fun interface ErrorHandler<T> {
    fun handle(context: T, throwable: Throwable): Mono<Void>
}

class LogErrorHandler<T> : ErrorHandler<T> {
    companion object {
        private val log = KotlinLogging.logger { }
    }

    override fun handle(context: T, throwable: Throwable): Mono<Void> {
        log.error(throwable) { throwable.message }
        return Mono.error(throwable)
    }
}

class LogResumeErrorHandler<T> : ErrorHandler<T> {
    companion object {
        private val log = KotlinLogging.logger { }
    }

    override fun handle(context: T, throwable: Throwable): Mono<Void> {
        log.error(throwable) { throwable.message }
        return Mono.empty()
    }
}

abstract class AbstractHandler<T>(
    private val chain: FilterChain<T>,
    private val errorHandler: ErrorHandler<T>
) : Handler<T> {
    override fun handle(context: T): Mono<Void> {
        return chain.filter(context)
            .onErrorResume {
                if (context is ErrorAccessor) {
                    context.setError(it)
                }
                errorHandler.handle(context, it)
            }
    }
}
