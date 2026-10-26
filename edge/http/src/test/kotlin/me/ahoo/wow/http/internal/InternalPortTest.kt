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

package me.ahoo.wow.http.internal

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.buildAndAwait
import org.springframework.web.reactive.function.server.coRouter

/** The internal port (decisions D89, D109): its own server, bound and unbound by its host. */
class InternalPortTest {
    @Test
    fun `a built port should bind and unbind`() {
        val port = InternalPort.of(
            InternalPortSettings(port = 0, bindHost = "127.0.0.1"),
            coRouter { GET("/ping") { ServerResponse.ok().buildAndAwait() } },
        )
        port.isRunning.assert().isFalse()
        port.start()
        try {
            port.isRunning.assert().isTrue()
            port.boundPort.assert().isGreaterThan(0)
            port.start()
        } finally {
            port.stop()
        }
        port.isRunning.assert().isFalse()
    }
}
