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

package me.ahoo.wow.messaging.transport

import io.github.oshai.kotlinlogging.KotlinLogging
import reactor.core.publisher.Mono

/**
 * A record that [TransportMessageBus] could not decode: its payload is missing or not a [messageType], or its key or
 * topic does not match the decoded message.
 */
class TransportDecodeFailure(
    val record: TransportRecord,
    val group: String,
    val messageType: Class<*>,
    val cause: Exception,
) {
    /** Where the record came from, without its payload. */
    val description: String
        get() = "topic=${record.topic}, id=${record.id}, group=$group, messageType=${messageType.name}, " +
            "cause=${cause.javaClass.name}"
}

/**
 * What the bus does with an undecodable record once [TransportDecodeFailureHandler] has seen it.
 */
enum class TransportDecodeFailureAction {
    /** Leave the record unacknowledged and fail the receive stream with a [TransportDecodeException]. */
    FAIL,

    /** Acknowledge the record and continue with the next one. */
    ACKNOWLEDGE,

    /** Leave the record unacknowledged (pending, where the backend keeps one) and continue with the next one. */
    LEAVE_PENDING,
}

/**
 * Decides what happens to a record the bus cannot decode. An error from [handle] fails the receive stream like
 * [TransportDecodeFailureAction.FAIL].
 */
fun interface TransportDecodeFailureHandler {
    fun handle(failure: TransportDecodeFailure): Mono<TransportDecodeFailureAction>

    companion object {
        /** Stop: the receive stream fails and the record stays unacknowledged. The Kafka default. */
        @JvmField
        val FAIL: TransportDecodeFailureHandler = TransportDecodeFailureHandler {
            Mono.just(TransportDecodeFailureAction.FAIL)
        }

        /** Log the record (without its payload), acknowledge it and continue. */
        @JvmField
        val ACKNOWLEDGE: TransportDecodeFailureHandler = TransportDecodeFailureHandler { failure ->
            Mono.fromSupplier {
                log.error { "Skip undecodable transport record [${failure.description}]." }
                TransportDecodeFailureAction.ACKNOWLEDGE
            }
        }
    }
}

private val log = KotlinLogging.logger {}

/**
 * The receive stream's error for [TransportDecodeFailureAction.FAIL]. It names the record and the cause's type but
 * carries neither the cause nor the payload, since a decoder's message can quote the payload.
 */
class TransportDecodeException(
    failure: TransportDecodeFailure,
) : RuntimeException("Failed to decode transport record [${failure.description}].")
