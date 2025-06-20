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

package me.ahoo.wow.cosec.appender

import me.ahoo.test.asserts.assert
import me.ahoo.wow.cosec.appender.CoSecCommandRequestHeaderAppender.APP_ID_KEY
import me.ahoo.wow.cosec.appender.CoSecCommandRequestHeaderAppender.DEVICE_ID_KEY
import me.ahoo.wow.cosec.propagation.CoSecMessagePropagator.Companion.appId
import me.ahoo.wow.cosec.propagation.CoSecMessagePropagator.Companion.deviceId
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.messaging.DefaultHeader
import org.junit.jupiter.api.Test
import org.springframework.mock.web.reactive.function.server.MockServerRequest

class CoSecCommandRequestHeaderAppenderTest {
    @Test
    fun append() {
        val request = MockServerRequest.builder()
            .header(APP_ID_KEY, generateGlobalId())
            .header(DEVICE_ID_KEY, generateGlobalId())
            .build()
        val commandHeader = DefaultHeader.empty()
        CoSecCommandRequestHeaderAppender.append(request, commandHeader)
        commandHeader.appId.assert().isNotEmpty()
        commandHeader.deviceId.assert().isNotEmpty()
    }

    @Test
    fun appendEmpty() {
        val request = MockServerRequest.builder()
            .build()
        val commandHeader = DefaultHeader.empty()
        CoSecCommandRequestHeaderAppender.append(request, commandHeader)
        commandHeader.appId.assert().isNull()
        commandHeader.deviceId.assert().isNull()
    }
}
