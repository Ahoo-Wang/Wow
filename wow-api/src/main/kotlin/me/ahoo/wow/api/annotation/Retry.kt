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

package me.ahoo.wow.api.annotation

import me.ahoo.wow.api.annotation.Retry.Companion.DEFAULT_EXECUTION_TIMEOUT
import me.ahoo.wow.api.annotation.Retry.Companion.DEFAULT_MAX_RETRIES
import me.ahoo.wow.api.annotation.Retry.Companion.DEFAULT_MIN_BACKOFF
import kotlin.reflect.KClass

/**
 * 用于标记函数，以启用重试机制。该注解允许配置重试策略，包括是否启用、最大重试次数、最小回退时间、执行超时时间以及指定可恢复和不可恢复的异常类型。
 *
 * @param enabled 是否启用重试功能，默认为true。
 * @param maxRetries 最大重试次数，默认值为[DEFAULT_MAX_RETRIES]。
 * @param minBackoff 第一次回退的最短持续时间（单位：秒），默认值为[DEFAULT_MIN_BACKOFF]。查看[java.time.temporal.ChronoUnit.SECONDS]获取更多关于时间单位的信息。
 * @param executionTimeout 执行操作的最大超时时间（单位：秒），默认值为[DEFAULT_EXECUTION_TIMEOUT]。同样地，参考[java.time.temporal.ChronoUnit.SECONDS]了解时间单位详情。
 * @param recoverable 指定在遇到这些类型的异常时进行重试。数组形式，允许指定多个异常类。
 * @param unrecoverable 指定遇到这些异常时不进行重试。同样使用数组形式来指定一个或多个异常类。
 */
@Target(AnnotationTarget.FUNCTION)
annotation class Retry(
    val enabled: Boolean = true,
    /**
     * 最大重试次数
     */
    val maxRetries: Int = DEFAULT_MAX_RETRIES,

    /**
     * the minimum Duration for the first backoff
     *
     * @see java.time.temporal.ChronoUnit.SECONDS
     */
    val minBackoff: Int = DEFAULT_MIN_BACKOFF,

    /**
     * 执行超时时间
     *
     * @see java.time.temporal.ChronoUnit.SECONDS
     */
    val executionTimeout: Int = DEFAULT_EXECUTION_TIMEOUT,
    val recoverable: Array<KClass<out Throwable>> = [],
    val unrecoverable: Array<KClass<out Throwable>> = []
) {
    companion object {
        const val DEFAULT_MAX_RETRIES = 10
        const val DEFAULT_MIN_BACKOFF = 180
        const val DEFAULT_EXECUTION_TIMEOUT = 120
    }
}
