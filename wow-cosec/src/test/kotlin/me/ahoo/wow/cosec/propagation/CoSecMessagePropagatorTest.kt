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

package me.ahoo.wow.cosec.propagation

import me.ahoo.test.asserts.assert
import me.ahoo.wow.command.toCommandMessage
import me.ahoo.wow.cosec.propagation.CoSecMessagePropagator.Companion.appId
import me.ahoo.wow.cosec.propagation.CoSecMessagePropagator.Companion.deviceId
import me.ahoo.wow.cosec.propagation.CoSecMessagePropagator.Companion.withAppId
import me.ahoo.wow.cosec.propagation.CoSecMessagePropagator.Companion.withDeviceId
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.tck.mock.MockCreateAggregate
import org.junit.jupiter.api.Test

class CoSecMessagePropagatorTest {
    val coSecMessagePropagator = CoSecMessagePropagator()

    @Test
    fun propagate() {
        val injectedHeader = DefaultHeader.empty()
        val upstreamMessage = MockCreateAggregate(generateGlobalId(), generateGlobalId())
            .toCommandMessage()
        upstreamMessage.header.withAppId("appId").withDeviceId("deviceId")
        coSecMessagePropagator.propagate(injectedHeader, upstreamMessage)
        injectedHeader.appId.assert().isEqualTo(upstreamMessage.header.appId)
        injectedHeader.deviceId.assert().isEqualTo(upstreamMessage.header.deviceId)
    }

    @Test
    fun propagateIfNull() {
        val injectedHeader = DefaultHeader.empty()
        val upstreamMessage =
            MockCreateAggregate(generateGlobalId(), generateGlobalId())
                .toCommandMessage()
        coSecMessagePropagator.propagate(injectedHeader, upstreamMessage)
        injectedHeader.appId.assert().isNull()
        injectedHeader.deviceId.assert().isNull()
    }
}
