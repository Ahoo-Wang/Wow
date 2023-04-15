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

import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono

object LogResumeErrorMessageHandler {
    private val log = LoggerFactory.getLogger(LogResumeErrorMessageHandler::class.java)
    fun <T : Any> handle(handled: Mono<T>): Mono<T> {
        return handled.onErrorResume {
            if (log.isErrorEnabled) {
                log.error(it.message, it)
            }
            Mono.empty()
        }
    }
}

fun <T : Any> Mono<T>.logErrorResume(): Mono<T> {
    return LogResumeErrorMessageHandler.handle(this)
}
