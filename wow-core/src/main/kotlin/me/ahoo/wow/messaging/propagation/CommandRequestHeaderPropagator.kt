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

package me.ahoo.wow.messaging.propagation

import me.ahoo.wow.api.messaging.Header
import me.ahoo.wow.api.messaging.Message

/**
 * Propagator that copies request-related headers like user agent and remote IP.
 *
 * This propagator helps maintain client request context across message boundaries,
 * useful for auditing and security purposes.
 */
class CommandRequestHeaderPropagator : MessagePropagator {
    companion object {
        /**
         * System property key to enable/disable this propagator.
         */
        const val ENABLED_KEY = "wow.messaging.propagation.request"

        /**
         * Header key for user agent information.
         */
        private const val USER_AGENT = "user_agent"

        /**
         * Header key for remote IP address.
         */
        private const val REMOTE_IP = "remote_ip"
        val Header.userAgent: String?
            get() {
                return this[USER_AGENT]
            }

        fun Header.withUserAgent(userAgent: String): Header = this.with(USER_AGENT, userAgent)

        val Header.remoteIp: String?
            get() {
                return this[REMOTE_IP]
            }

        fun Header.withRemoteIp(remoteIp: String): Header = this.with(REMOTE_IP, remoteIp)
    }

    /**
     * Whether this propagator is enabled, controlled by system property.
     */
    private val enabled: Boolean = System.getProperty(ENABLED_KEY)?.toBoolean() != false

    /**
     * Propagates user agent and remote IP information if enabled.
     *
     * @param header The target header to propagate to
     * @param upstream The upstream message to propagate from
     */
    override fun propagate(
        header: Header,
        upstream: Message<*, *>
    ) {
        if (!enabled) {
            return
        }
        upstream.header.userAgent?.let {
            header.withUserAgent(it)
        }
        upstream.header.remoteIp?.let {
            header.withRemoteIp(it)
        }
    }
}
