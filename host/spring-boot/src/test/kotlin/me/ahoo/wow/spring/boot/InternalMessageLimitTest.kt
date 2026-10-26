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
import me.ahoo.wow.http.internal.InternalHttp
import me.ahoo.wow.http.internal.InternalPort
import me.ahoo.wow.http.internal.InternalPortSettings
import me.ahoo.wow.http.internal.internalRoutes
import me.ahoo.wow.runtime.cluster.ClusterChannelHandler
import me.ahoo.wow.runtime.cluster.ClusterCodec
import me.ahoo.wow.runtime.cluster.ForwardReply
import me.ahoo.wow.runtime.cluster.Refusal
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * The internal port takes a forward as large as the public port takes a command (review A3-9): its codec limit follows
 * the application's, and a forward over it is refused, never failed, so its sender runs it.
 */
class InternalMessageLimitTest {
    private val handler = ClusterChannelHandler { ForwardReply.refused(Refusal.NOT_OWNER) }

    /** A JSON body of [size] bytes, which is no forward: read whole, it is malformed. */
    private fun body(size: Int): ByteArray = ("{\"x\":\"" + "a".repeat(size) + "\"}").toByteArray()

    private fun post(server: InternalPort, path: String, body: ByteArray): HttpResponse<ByteArray> =
        HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build().send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:${server.boundPort}$path"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )

    private fun <T> serving(server: InternalPort, block: (InternalPort) -> T): T {
        server.start()
        try {
            return block(server)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `the public port's codec limit should set the internal port's`() {
        WowAutoConfiguration.publicCodecLimit(MockEnvironment()).assert().isEqualTo(InternalHttp.MAX_UPGRADE_BODY)
        WowAutoConfiguration.publicCodecLimit(
            MockEnvironment().withProperty("spring.http.codecs.max-in-memory-size", "1MB"),
        ).assert().isEqualTo(1024 * 1024)
        WowAutoConfiguration.publicCodecLimit(
            MockEnvironment().withProperty("spring.codec.max-in-memory-size", "512KB"),
        ).assert().isEqualTo(512 * 1024)
        InternalHttp.messageLimit(1024 * 1024).assert().isEqualTo(1024 * 1024 + InternalHttp.MESSAGE_OVERHEAD)
        InternalHttp.messageLimit(1).assert().isEqualTo(InternalHttp.MAX_UPGRADE_BODY)
        InternalHttp.messageLimit(-1).assert().isEqualTo(Int.MAX_VALUE)
    }

    @Test
    fun `a forward over the internal port's limit should be refused, so its sender runs it`() {
        val server = InternalPort.of(InternalPortSettings(0, "127.0.0.1")) { internalRoutes(handler, {}) }
        serving(server) {
            val answer = post(it, InternalHttp.FORWARD_PATH, body(300 * 1024))
            answer.statusCode().assert().isEqualTo(200)
            ClusterCodec.decodeReply(answer.body()).refused.assert().isEqualTo(Refusal.TOO_LARGE)
            post(it, InternalHttp.SIGNALS_PATH, body(300 * 1024)).statusCode().assert().isEqualTo(413)
        }
    }

    @Test
    fun `a larger limit should take a forward the default one refuses`() {
        val limit = InternalHttp.messageLimit(1024 * 1024)
        val server = InternalPort.of(
            InternalPortSettings(0, "127.0.0.1", maxMessageSize = limit)
        ) { internalRoutes(handler, {}) }
        serving(server) {
            // Read whole: no forward, so malformed.
            post(it, InternalHttp.FORWARD_PATH, body(300 * 1024)).statusCode().assert().isEqualTo(400)
        }
    }
}
