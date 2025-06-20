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

import me.ahoo.wow.command.toCommandMessage
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.messaging.propagation.CommandRequestHeaderPropagator.Companion.remoteIp
import me.ahoo.wow.messaging.propagation.CommandRequestHeaderPropagator.Companion.userAgent
import me.ahoo.wow.messaging.propagation.CommandRequestHeaderPropagator.Companion.withRemoteIp
import me.ahoo.wow.messaging.propagation.CommandRequestHeaderPropagator.Companion.withUserAgent
import me.ahoo.wow.tck.mock.MockCreateAggregate
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class CommandRequestHeaderPropagatorTest {
    @Test
    fun inject() {
        val injectedHeader = DefaultHeader.empty()
        val upstreamMessage =
            MockCreateAggregate(generateGlobalId(), generateGlobalId())
                .toCommandMessage()
        upstreamMessage.header.withUserAgent("userAgent").withRemoteIp("remoteIp")
        CommandRequestHeaderPropagator().inject(injectedHeader, upstreamMessage)
        assertThat(injectedHeader.userAgent, equalTo(upstreamMessage.header.userAgent))
        assertThat(injectedHeader.remoteIp, equalTo(upstreamMessage.header.remoteIp))
    }

    @Test
    fun injectIfNull() {
        val injectedHeader = DefaultHeader.empty()
        val upstreamMessage =
            MockCreateAggregate(generateGlobalId(), generateGlobalId())
                .toCommandMessage()
        CommandRequestHeaderPropagator().inject(injectedHeader, upstreamMessage)
        assertThat(injectedHeader.userAgent, equalTo(null))
        assertThat(injectedHeader.remoteIp, equalTo(null))
    }

    @Test
    fun injectDisabled() {
        System.setProperty(CommandRequestHeaderPropagator.ENABLED_KEY, "false")
        val injectedHeader = DefaultHeader.empty()
        val upstreamMessage =
            MockCreateAggregate(generateGlobalId(), generateGlobalId())
                .toCommandMessage()
        upstreamMessage.header.withUserAgent("userAgent").withRemoteIp("remoteIp")
        CommandRequestHeaderPropagator().inject(injectedHeader, upstreamMessage)
        assertThat(injectedHeader.userAgent, equalTo(null))
        assertThat(injectedHeader.remoteIp, equalTo(null))
        System.clearProperty(CommandRequestHeaderPropagator.ENABLED_KEY)
    }

    @Test
    fun injectEnabled() {
        System.setProperty(CommandRequestHeaderPropagator.ENABLED_KEY, "true")
        val injectedHeader = DefaultHeader.empty()
        val upstreamMessage =
            MockCreateAggregate(generateGlobalId(), generateGlobalId())
                .toCommandMessage()
        upstreamMessage.header.withUserAgent("userAgent").withRemoteIp("remoteIp")
        CommandRequestHeaderPropagator().inject(injectedHeader, upstreamMessage)
        assertThat(injectedHeader.userAgent, equalTo(upstreamMessage.header.userAgent))
        assertThat(injectedHeader.remoteIp, equalTo(upstreamMessage.header.remoteIp))
        System.clearProperty(CommandRequestHeaderPropagator.ENABLED_KEY)
    }
}
