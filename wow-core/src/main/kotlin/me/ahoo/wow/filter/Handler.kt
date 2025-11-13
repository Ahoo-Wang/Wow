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

/**
 * Functional interface for handling logic on a given context.
 *
 * @param T the type of the context
 */
fun interface Handler<T> {
    /**
     * Method to execute the handling logic.
     *
     * @param context the context to handle
     * @return a Mono signaling completion
     */
    fun handle(context: T): Mono<Void>
}

/**
 * Functional interface for handling contexts in error situations.
 *
 * @param T the type of the context
 */
fun interface ErrorHandler<T> {
    /**
     * Method to execute error handling logic.
     *
     * @param context the context for error handling
     * @param throwable the exception that occurred
     * @return a Mono signaling completion
     */
    fun handle(
        context: T,
        throwable: Throwable
    ): Mono<Void>
}

/**
 * Implementation of ErrorHandler interface for logging error information.
 *
 * @param T the type of the context
 */
class LogErrorHandler<T> : ErrorHandler<T> {
    companion object {
        private val log = KotlinLogging.logger { }
    }

    /**
     * Logs error information and re-throws the exception.
     *
     * @param context the context for error handling
     * @param throwable the exception that occurred
     * @return a Mono signaling completion, here re-throwing the exception via Mono.error
     */
    override fun handle(
        context: T,
        throwable: Throwable
    ): Mono<Void> {
        log.error(throwable) { throwable.message }
        return Mono.error(throwable)
    }
}

/**
 * Implementation of ErrorHandler interface for logging error information and continuing execution.
 *
 * @param T the type of the context
 */
class LogResumeErrorHandler<T> : ErrorHandler<T> {
    companion object {
        // Logger
        private val log = KotlinLogging.logger { }
    }

    /**
     * Logs error information and returns an empty Mono, indicating error handling is complete and execution continues.
     *
     * @param context the context for error handling
     * @param throwable the exception that occurred
     * @return an empty Mono, indicating error handling is complete
     */
    override fun handle(
        context: T,
        throwable: Throwable
    ): Mono<Void> {
        log.error(throwable) { throwable.message }
        return Mono.empty()
    }
}

/**
 * 抽象处理器类，封装了处理链和错误处理逻辑
 * @param T 上下文的类型
 * @param chain 处理链，用于执行一系列处理逻辑
 * @param errorHandler 错误处理器，用于处理发生异常时的逻辑
 */
abstract class AbstractHandler<T>(
    private val chain: FilterChain<T>,
    private val errorHandler: ErrorHandler<T>
) : Handler<T> {
    /**
     * 执行处理逻辑的方法，当发生异常时使用错误处理器进行处理
     * @param context 处理的上下文
     * @return 完成信号的Mono，当发生异常时通过错误处理器进行处理
     */
    override fun handle(context: T): Mono<Void> =
        chain
            .filter(context)
            .onErrorResume {
                // 如果上下文实现了ErrorAccessor接口，则设置错误信息
                if (context is ErrorAccessor) {
                    context.setError(it)
                }
                // 使用错误处理器处理异常
                errorHandler.handle(context, it)
            }
}
