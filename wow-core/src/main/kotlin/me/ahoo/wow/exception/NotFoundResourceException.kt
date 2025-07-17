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

package me.ahoo.wow.exception

import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toFlux
import reactor.kotlin.core.publisher.toMono

class NotFoundResourceException(
    errorMsg: String = ErrorCodes.NOT_FOUND_MESSAGE,
    cause: Throwable? = null,
) : WowException(ErrorCodes.NOT_FOUND, errorMsg, cause)

fun <T> Mono<T>.throwNotFoundIfEmpty(
    errorMsg: String = ErrorCodes.NOT_FOUND_MESSAGE,
    cause: Throwable? = null,
): Mono<T> {
    return switchIfEmpty(
        Mono.defer {
            NotFoundResourceException(errorMsg, cause).toMono()
        },
    )
}

fun <T> Flux<T>.throwNotFoundIfEmpty(
    errorMsg: String = ErrorCodes.NOT_FOUND_MESSAGE,
    cause: Throwable? = null,
): Flux<T> {
    return switchIfEmpty(
        Flux.defer {
            NotFoundResourceException(errorMsg, cause).toFlux()
        },
    )
}

fun <T> T?.throwNotFoundIfNull(
    errorMsg: String = ErrorCodes.NOT_FOUND_MESSAGE,
    cause: Throwable? = null,
): T {
    return this ?: throw NotFoundResourceException(errorMsg, cause)
}
