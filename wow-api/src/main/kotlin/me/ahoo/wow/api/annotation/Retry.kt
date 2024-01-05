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

import me.ahoo.wow.api.exception.RecoverableType

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
    val recoverable: RecoverableType = RecoverableType.UNKNOWN
) {
    companion object {
        const val DEFAULT_MAX_RETRIES = 10
        const val DEFAULT_MIN_BACKOFF = 180
        const val DEFAULT_EXECUTION_TIMEOUT = 120
    }
}
