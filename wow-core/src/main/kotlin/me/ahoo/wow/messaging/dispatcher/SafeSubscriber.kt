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

package me.ahoo.wow.messaging.dispatcher

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.naming.Named
import org.reactivestreams.Subscription
import reactor.core.publisher.BaseSubscriber
import reactor.core.publisher.SignalType

/**
 * A safe subscriber that wraps message processing with error handling.
 *
 * Extends BaseSubscriber to provide lifecycle hooks with logging and
 * safe error handling for message processing.
 *
 * @param T The type of message being subscribed to
 */
abstract class SafeSubscriber<T : Any> :
    BaseSubscriber<T>(),
    Named {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    /**
     * Handles the next message with error safety.
     *
     * Calls safeOnNext and catches any exceptions, logging them and
     * calling safeOnNextError for custom error handling.
     *
     * @param value The message value received
     */
    @Suppress("TooGenericExceptionCaught")
    override fun hookOnNext(value: T) {
        log.debug {
            "[$name] OnNext $value."
        }
        try {
            safeOnNext(value)
        } catch (e: Throwable) {
            log.error(e) {
                "[$name] OnNext handle error."
            }
            safeOnNextError(value, throwable = e)
        }
    }

    /**
     * Safely processes the next message.
     *
     * Override this method to handle messages. Any exceptions thrown
     * will be caught and handled by safeOnNextError.
     *
     * @param value The message value to process
     */
    open fun safeOnNext(value: T) {}

    /**
     * Handles errors that occur during message processing.
     *
     * Called when safeOnNext throws an exception.
     *
     * @param value The message value that caused the error
     * @param throwable The exception that was thrown
     */
    open fun safeOnNextError(
        value: T,
        throwable: Throwable
    ) {}

    /**
     * Called when the subscription is established.
     *
     * Logs the subscription event and delegates to the parent implementation.
     *
     * @param subscription The subscription object
     */
    override fun hookOnSubscribe(subscription: Subscription) {
        log.debug {
            "[$name] OnSubscribe."
        }
        super.hookOnSubscribe(subscription)
    }

    /**
     * Called when an error occurs in the subscription.
     *
     * Logs the error with context.
     *
     * @param throwable The error that occurred
     */
    override fun hookOnError(throwable: Throwable) {
        log.error(throwable) {
            "[$name] OnError."
        }
    }

    /**
     * Called when the subscription terminates (normally or with error).
     *
     * Logs the termination event.
     *
     * @param type The type of signal that terminated the subscription
     */
    override fun hookFinally(type: SignalType) {
        log.info {
            "[$name] Finally $type."
        }
    }
}
