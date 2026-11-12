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

package me.ahoo.wow.benchmark.search

import co.elastic.clients.transport.rest5_client.low_level.Request
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client
import me.ahoo.test.asserts.assert
import org.apache.hc.core5.http.HttpHost
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * This server's Elasticsearch client with its [SearchClientTimeout], the customizer applied as Spring Boot applies every
 * `Rest5ClientBuilderCustomizer` bean (the builder's request config callback): a request the server never answers fails
 * at the response timeout, its connection freed.
 */
class SearchClientTimeoutTest {
    /** A server that takes every connection and never answers it, as a paused Elasticsearch does. */
    private val server = ServerSocket(0)
    private val held = CopyOnWriteArrayList<Socket>()

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) runCatching { held += server.accept() }
        }
    }

    @AfterEach
    fun close() {
        server.close()
        held.forEach(Socket::close)
    }

    // Bounded: without the response timeout, the request would wait for the held server for ever.
    @Test
    @Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `a request the server never answers should fail at the response timeout`() {
        val customizer = SearchClientTimeout.responseTimeout(Duration.ofMillis(200))
        Rest5Client.builder(HttpHost("http", "127.0.0.1", server.localPort))
            .setRequestConfigCallback(customizer::customize)
            .build().use { client ->
                val started = TimeSource.Monotonic.markNow()
                assertThrows<SocketTimeoutException> { client.performRequest(Request("GET", "/")) }
                started.elapsedNow().assert().isLessThan(5.seconds)
                held.assert().isNotEmpty()
            }
    }

    @Test
    fun `the server's response timeout should be the store's operation timeout`() {
        SearchClientTimeout.RESPONSE_TIMEOUT.assert().isEqualTo(Duration.ofSeconds(30))
    }
}
