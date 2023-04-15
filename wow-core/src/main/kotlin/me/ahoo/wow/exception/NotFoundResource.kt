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

import me.ahoo.wow.api.exception.ErrorCodes
import me.ahoo.wow.api.exception.NotFoundException
import me.ahoo.wow.api.exception.WowException
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

class NotFoundResourceException(
    errorCode: String = ErrorCodes.NOT_FOUND,
    errorMsg: String = ErrorCodes.NOT_FOUND_MESSAGE,
) : WowException(errorCode, errorMsg), NotFoundException

fun <T> Mono<T>.throwNotFoundIfEmpty(
    errorCode: String = ErrorCodes.NOT_FOUND,
    errorMsg: String = ErrorCodes.NOT_FOUND_MESSAGE,
): Mono<T> {
    return switchIfEmpty(
        Mono.defer {
            Mono.error(NotFoundResourceException(errorCode, errorMsg))
        },
    )
}

fun <T> Flux<T>.throwNotFoundIfEmpty(
    errorCode: String = ErrorCodes.NOT_FOUND,
    errorMsg: String = ErrorCodes.NOT_FOUND_MESSAGE,
): Flux<T> {
    return switchIfEmpty(
        Flux.defer {
            Flux.error(NotFoundResourceException(errorCode, errorMsg))
        },
    )
}

fun <T> T?.throwNotFoundIfNull(
    errorCode: String = ErrorCodes.NOT_FOUND,
    errorMsg: String = ErrorCodes.NOT_FOUND_MESSAGE,
): T {
    return this ?: throw NotFoundResourceException(errorCode, errorMsg)
}
