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
import me.ahoo.wow.command.toCommandMessage
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.messaging.propagation.CommandRequestHeaderPropagator.Companion.remoteIp
import me.ahoo.wow.messaging.propagation.CommandRequestHeaderPropagator.Companion.userAgent
import me.ahoo.wow.messaging.propagation.CommandRequestHeaderPropagator.Companion.withRemoteIp
import me.ahoo.wow.messaging.propagation.CommandRequestHeaderPropagator.Companion.withUserAgent
import me.ahoo.wow.tck.mock.MockCreateAggregate
import org.junit.jupiter.api.Test

class CommandRequestHeaderPropagatorTest {
    @Test
    fun propagate() {
        val injectedHeader = DefaultHeader.empty()
        val upstreamMessage =
            MockCreateAggregate(generateGlobalId(), generateGlobalId())
                .toCommandMessage()
        upstreamMessage.header.withUserAgent("userAgent").withRemoteIp("remoteIp")
        CommandRequestHeaderPropagator().propagate(injectedHeader, upstreamMessage)
        injectedHeader.userAgent.assert().isEqualTo(upstreamMessage.header.userAgent)
        injectedHeader.remoteIp.assert().isEqualTo(upstreamMessage.header.remoteIp)
    }

    @Test
    fun propagateIfNull() {
        val injectedHeader = DefaultHeader.empty()
        val upstreamMessage =
            MockCreateAggregate(generateGlobalId(), generateGlobalId())
                .toCommandMessage()
        CommandRequestHeaderPropagator().propagate(injectedHeader, upstreamMessage)
        injectedHeader.userAgent.assert().isNull()
        injectedHeader.remoteIp.assert().isNull()
    }

    @Test
    fun propagateDisabled() {
        System.setProperty(CommandRequestHeaderPropagator.ENABLED_KEY, "false")
        val injectedHeader = DefaultHeader.empty()
        val upstreamMessage =
            MockCreateAggregate(generateGlobalId(), generateGlobalId())
                .toCommandMessage()
        upstreamMessage.header.withUserAgent("userAgent").withRemoteIp("remoteIp")
        CommandRequestHeaderPropagator().propagate(injectedHeader, upstreamMessage)
        injectedHeader.userAgent.assert().isNull()
        injectedHeader.remoteIp.assert().isNull()
        System.clearProperty(CommandRequestHeaderPropagator.ENABLED_KEY)
    }

    @Test
    fun propagateEnabled() {
        System.setProperty(CommandRequestHeaderPropagator.ENABLED_KEY, "true")
        val injectedHeader = DefaultHeader.empty()
        val upstreamMessage =
            MockCreateAggregate(generateGlobalId(), generateGlobalId())
                .toCommandMessage()
        upstreamMessage.header.withUserAgent("userAgent").withRemoteIp("remoteIp")
        CommandRequestHeaderPropagator().propagate(injectedHeader, upstreamMessage)
        injectedHeader.userAgent.assert().isEqualTo(upstreamMessage.header.userAgent)
        injectedHeader.remoteIp.assert().isEqualTo(upstreamMessage.header.remoteIp)
        System.clearProperty(CommandRequestHeaderPropagator.ENABLED_KEY)
    }
}
