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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.messaging.TestNamedMessage
import me.ahoo.wow.messaging.propagation.CommandRequestHeaderPropagator.Companion.ENABLED_KEY
import me.ahoo.wow.messaging.propagation.CommandRequestHeaderPropagator.Companion.remoteIp
import me.ahoo.wow.messaging.propagation.CommandRequestHeaderPropagator.Companion.userAgent
import me.ahoo.wow.messaging.propagation.CommandRequestHeaderPropagator.Companion.withRemoteIp
import me.ahoo.wow.messaging.propagation.CommandRequestHeaderPropagator.Companion.withUserAgent
import org.junit.jupiter.api.Test

class CommandRequestHeaderPropagatorTest {

    @Test
    fun `propagate copies request headers when enabled`() {
        val upstream = TestNamedMessage(
            header = DefaultHeader.empty()
                .withUserAgent("JUnit")
                .withRemoteIp("127.0.0.1"),
        )
        val target = DefaultHeader.empty()

        withSystemProperty(ENABLED_KEY, null) {
            CommandRequestHeaderPropagator().propagate(target, upstream)
        }

        target.userAgent.assert().isEqualTo("JUnit")
        target.remoteIp.assert().isEqualTo("127.0.0.1")
    }

    @Test
    fun `propagate does nothing when disabled by system property`() {
        val upstream = TestNamedMessage(
            header = DefaultHeader.empty()
                .withUserAgent("JUnit")
                .withRemoteIp("127.0.0.1"),
        )
        val target = DefaultHeader.empty()

        withSystemProperty(ENABLED_KEY, "false") {
            CommandRequestHeaderPropagator().propagate(target, upstream)
        }

        target.userAgent.assert().isNull()
        target.remoteIp.assert().isNull()
    }

    private fun withSystemProperty(
        key: String,
        value: String?,
        block: () -> Unit
    ) {
        val previous = System.getProperty(key)
        try {
            if (value == null) {
                System.clearProperty(key)
            } else {
                System.setProperty(key, value)
            }
            block()
        } finally {
            if (previous == null) {
                System.clearProperty(key)
            } else {
                System.setProperty(key, previous)
            }
        }
    }
}
