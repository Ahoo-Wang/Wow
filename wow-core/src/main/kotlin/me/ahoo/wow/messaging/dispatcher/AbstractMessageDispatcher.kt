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
import me.ahoo.wow.messaging.MessageDispatcher
import me.ahoo.wow.serialization.toJsonString

/**
 * Abstract base class for message dispatchers that subscribe to topics.
 *
 * This class extends SafeSubscriber to handle messages safely and provides
 * lifecycle management for subscribing to and unsubscribing from topics.
 *
 * @param T The type of message being dispatched
 */
abstract class AbstractMessageDispatcher<T : Any> :
    SafeSubscriber<T>(),
    MessageDispatcher {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    /**
     * The set of topics this dispatcher subscribes to.
     */
    abstract val topics: Set<Any>

    /**
     * Starts the dispatcher by subscribing to topics.
     *
     * Logs the topics being subscribed to and calls the start method.
     * If no topics are configured, logs a warning and returns without starting.
     */
    override fun run() {
        log.info {
            "[$name] Run subscribe to topics:${topics.toJsonString()}."
        }
        if (topics.isEmpty()) {
            log.warn {
                "[$name] Ignore start because topics is empty."
            }
            return
        }
        start()
    }

    /**
     * Starts the actual subscription to topics.
     *
     * Implementations should set up the subscription logic here.
     */
    abstract fun start()

    /**
     * Closes the dispatcher by canceling the subscription.
     *
     * Logs the closure and calls cancel to stop receiving messages.
     */
    override fun close() {
        log.info {
            "[$name] Close."
        }
        cancel()
    }
}
