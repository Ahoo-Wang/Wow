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

package me.ahoo.wow.messaging.function

import io.github.oshai.kotlinlogging.KotlinLogging
import reactor.core.publisher.Mono

/**
 * Error handler that logs errors and resumes with an empty Mono.
 *
 * Useful for error-tolerant message processing where failures should be logged
 * but not propagated.
 */
object LogResumeErrorMessageHandler {
    private val log = KotlinLogging.logger { }

    /**
     * Handles errors in a Mono by logging them and returning an empty Mono.
     *
     * @param handled The Mono that might contain errors
     * @return A Mono that logs errors and resumes with empty
     */
    fun <T : Any> handle(handled: Mono<T>): Mono<T> =
        handled.onErrorResume {
            log.error(it) { it.message }
            Mono.empty()
        }
}

/**
 * Extension function to log errors and resume with empty Mono.
 *
 * @receiver The Mono that might contain errors
 * @return A Mono that logs errors and resumes with empty
 */
fun <T : Any> Mono<T>.logErrorResume(): Mono<T> = LogResumeErrorMessageHandler.handle(this)
