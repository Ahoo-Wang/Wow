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
 * 定义一个函数式接口，用于处理给定上下文的逻辑
 * @param T 上下文的类型
 */
fun interface Handler<T> {
    /**
     * 执行处理逻辑的方法
     * @param context 处理的上下文
     * @return 完成信号的Mono
     */
    fun handle(context: T): Mono<Void>
}

/**
 * 定义一个函数式接口，用于处理错误情况下的上下文
 * @param T 上下文的类型
 */
fun interface ErrorHandler<T> {
    /**
     * 执行错误处理逻辑的方法
     * @param context 错误处理的上下文
     * @param throwable 发生的异常
     * @return 完成信号的Mono
     */
    fun handle(context: T, throwable: Throwable): Mono<Void>
}

/**
 * 实现ErrorHandler接口，用于日志记录错误信息
 * @param T 上下文的类型
 */
class LogErrorHandler<T> : ErrorHandler<T> {
    companion object {
        private val log = KotlinLogging.logger { }
    }

    /**
     * 记录错误信息并重新抛出异常
     * @param context 错误处理的上下文
     * @param throwable 发生的异常
     * @return 完成信号的Mono，这里通过Mono.error重新抛出异常
     */
    override fun handle(context: T, throwable: Throwable): Mono<Void> {
        log.error(throwable) { throwable.message }
        return Mono.error(throwable)
    }
}

/**
 * 实现ErrorHandler接口，用于日志记录错误信息后继续执行
 * @param T 上下文的类型
 */
class LogResumeErrorHandler<T> : ErrorHandler<T> {
    companion object {
        // 日志记录器
        private val log = KotlinLogging.logger { }
    }

    /**
     * 记录错误信息并返回空的Mono，表示错误处理完毕，继续执行
     * @param context 错误处理的上下文
     * @param throwable 发生的异常
     * @return 空的Mono，表示错误处理完毕
     */
    override fun handle(context: T, throwable: Throwable): Mono<Void> {
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
    override fun handle(context: T): Mono<Void> {
        return chain.filter(context)
            .onErrorResume {
                // 如果上下文实现了ErrorAccessor接口，则设置错误信息
                if (context is ErrorAccessor) {
                    context.setError(it)
                }
                // 使用错误处理器处理异常
                errorHandler.handle(context, it)
            }
    }
}
