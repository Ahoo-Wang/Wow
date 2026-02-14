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

package me.ahoo.wow.spring.boot.starter.command

import io.mockk.every
import io.mockk.mockk
import me.ahoo.cosid.machine.HostAddressSupplier
import me.ahoo.test.asserts.assert
import me.ahoo.wow.openapi.global.CommandWaitRouteSpecFactory
import org.junit.jupiter.api.Test
import org.springframework.boot.web.server.context.WebServerInitializedEvent

class ServerCommandWaitEndpointTest {

    @Test
    fun endpoint() {
        val hostAddress = "127.0.0.1"
        val serverCommandWaitEndpoint = ServerCommandWaitEndpoint(object : HostAddressSupplier {
            override fun getHostAddress(): String? {
                return hostAddress
            }
        })
        val event = mockk<WebServerInitializedEvent> {
            every { webServer.port } returns 8080
        }
        serverCommandWaitEndpoint.onApplicationEvent(event)
        serverCommandWaitEndpoint.endpoint.assert().isEqualTo(
            "http://$hostAddress:8080${CommandWaitRouteSpecFactory.PATH}"
        )
    }
}
