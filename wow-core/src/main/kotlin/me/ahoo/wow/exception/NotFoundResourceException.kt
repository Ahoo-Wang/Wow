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
import reactor.kotlin.core.publisher.switchIfEmpty
import reactor.kotlin.core.publisher.switchIfEmptyDeferred
import reactor.kotlin.core.publisher.toFlux
import reactor.kotlin.core.publisher.toMono

/**
 * Exception thrown when a requested resource cannot be found.
 *
 * This exception is used to indicate that a resource (such as an aggregate, command result,
 * or query result) was not found. It extends WowException and uses the standard NOT_FOUND
 * error code for consistent error handling.
 *
 * Example usage:
 * ```kotlin
 * if (aggregate == null) {
 *     throw NotFoundResourceException("Aggregate with ID $id not found")
 * }
 * ```
 *
 * @param errorMsg the error message (defaults to standard not found message)
 * @param cause the underlying cause of this exception, if any
 * @see WowException
 * @see ErrorCodes.NOT_FOUND
 */
class NotFoundResourceException(
    errorMsg: String = ErrorCodes.NOT_FOUND_MESSAGE,
    cause: Throwable? = null
) : WowException(ErrorCodes.NOT_FOUND, errorMsg, cause)

/**
 * Throws NotFoundResourceException if the Mono is empty.
 *
 * This extension function converts an empty Mono into an error signal containing
 * a NotFoundResourceException. It's useful for handling cases where a resource
 * lookup returns no results.
 *
 * Example usage:
 * ```kotlin
 * val resource = repository.findById(id)
 *     .throwNotFoundIfEmpty("Resource with ID $id not found")
 * ```
 *
 * @param errorMsg the error message for the exception
 * @param cause the underlying cause, if any
 * @return a Mono that signals NotFoundResourceException if empty
 */
fun <T : Any> Mono<T>.throwNotFoundIfEmpty(
    errorMsg: String = ErrorCodes.NOT_FOUND_MESSAGE,
    cause: Throwable? = null
): Mono<T> =
    switchIfEmpty {
        NotFoundResourceException(errorMsg, cause).toMono()
    }

/**
 * Throws NotFoundResourceException if the Flux is empty.
 *
 * This extension function converts an empty Flux into an error signal containing
 * a NotFoundResourceException. It's useful for handling cases where a collection
 * lookup returns no results.
 *
 * Example usage:
 * ```kotlin
 * val resources = repository.findAll()
 *     .throwNotFoundIfEmpty("No resources found")
 * ```
 *
 * @param errorMsg the error message for the exception
 * @param cause the underlying cause, if any
 * @return a Flux that signals NotFoundResourceException if empty
 */
fun <T : Any> Flux<T>.throwNotFoundIfEmpty(
    errorMsg: String = ErrorCodes.NOT_FOUND_MESSAGE,
    cause: Throwable? = null
): Flux<T> =
    switchIfEmptyDeferred {
        NotFoundResourceException(errorMsg, cause).toFlux()
    }

/**
 * Throws NotFoundResourceException if the value is null.
 *
 * This extension function checks if the receiver is null and throws a
 * NotFoundResourceException if it is. Otherwise, it returns the non-null value.
 *
 * Example usage:
 * ```kotlin
 * val resource = findResource(id).throwNotFoundIfNull("Resource $id not found")
 * // resource is guaranteed to be non-null here
 * ```
 *
 * @param errorMsg the error message for the exception
 * @param cause the underlying cause, if any
 * @return the non-null value
 * @throws NotFoundResourceException if the value is null
 */
fun <T> T?.throwNotFoundIfNull(
    errorMsg: String = ErrorCodes.NOT_FOUND_MESSAGE,
    cause: Throwable? = null
): T = this ?: throw NotFoundResourceException(errorMsg, cause)
