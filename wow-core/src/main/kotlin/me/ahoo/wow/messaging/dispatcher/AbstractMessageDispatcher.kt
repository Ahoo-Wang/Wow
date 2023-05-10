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

import me.ahoo.wow.messaging.MessageDispatcher
import me.ahoo.wow.serialization.asJsonString
import org.slf4j.LoggerFactory

abstract class AbstractMessageDispatcher<T : Any> : MessageDispatcher, SafeSubscriber<T>() {
    companion object {
        private val log = LoggerFactory.getLogger(AbstractMessageDispatcher::class.java)
    }

    abstract val topics: Set<Any>

    override fun run() {
        if (log.isInfoEnabled) {
            log.info("[$name] Run subscribe to topics:${topics.asJsonString()}.")
        }
        if (topics.isEmpty()) {
            if (log.isWarnEnabled) {
                log.warn("[$name] Ignore start because topics is empty.")
            }
            return
        }
        start()
    }

    abstract fun start()

    override fun close() {
        if (log.isInfoEnabled) {
            log.info("[$name] Close.")
        }
        cancel()
    }
}
