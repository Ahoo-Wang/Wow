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

package me.ahoo.wow.spring.boot

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.wire.WowHeaders
import me.ahoo.wow.http.internal.InternalHttp
import me.ahoo.wow.spi.config.WowConfigurationException
import org.junit.jupiter.api.Test
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * The internal port of a host takes the service each request names (`Wow-Service`, decision D300), no token: a
 * forward from a node of its own service alone, a signal from any service, and a node names its service on its own.
 */
class HostDeclaredServicesTest {
    private fun Host.internal(path: String, service: String?): Int {
        val request = HttpRequest.newBuilder(InternalHttp.uri("127.0.0.1:$internalPort", path))
            .header("Content-Type", InternalHttp.JSON)
            .apply { service?.let { header(WowHeaders.SERVICE, it) } }
            .POST(HttpRequest.BodyPublishers.ofString("{}"))
            .build()
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
    }

    @Test
    fun `a host's forward route takes a node of its own service alone`() {
        startHost().use { host ->
            host.internal(InternalHttp.FORWARD_PATH, null).assert().isEqualTo(401)
            host.internal(InternalHttp.FORWARD_PATH, "ordering-service").assert().isEqualTo(403)
            // Taken, then read: this body is no forward.
            host.internal(InternalHttp.FORWARD_PATH, "bank-service").assert().isEqualTo(400)
        }
    }

    @Test
    fun `a host's signal route takes any service that names itself`() {
        startHost().use { host ->
            host.internal(InternalHttp.SIGNALS_PATH, null).assert().isEqualTo(401)
            // Taken, then read: this body is no signal.
            host.internal(InternalHttp.SIGNALS_PATH, "ordering-service").assert().isEqualTo(400)
            host.internal(InternalHttp.SIGNALS_PATH, "bank-service").assert().isEqualTo(400)
        }
    }

    @Test
    fun `a node whose service is no service name refuses to start`() {
        val failure = runCatching { startHost("wow.service" to "bank service").close() }.exceptionOrNull()
        generateSequence(failure) { it.cause }.filterIsInstance<WowConfigurationException>().first()
            .key.assert().isEqualTo("wow.service")
    }
}
