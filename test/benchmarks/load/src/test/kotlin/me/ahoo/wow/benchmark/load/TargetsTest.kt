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

package me.ahoo.wow.benchmark.load

import com.sun.net.httpserver.HttpServer
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.util.concurrent.atomic.AtomicInteger

/** The open loop's nodes (roadmap §6 performance item 6): a stopped node is left out, and what it refused moves on. */
class TargetsTest {
    private val answered = AtomicInteger()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            answered.incrementAndGet()
            val body = """{"status":"SUCCEEDED"}""".toByteArray()
            exchange.sendResponseHeaders(VersionAdapter.OK, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        start()
    }
    private val up = URI.create("http://127.0.0.1:${server.address.port}")

    /** A port nothing listens on: a stopped node, which refuses connections. */
    private val stopped = URI.create("http://127.0.0.1:${ServerSocket(0).use { it.localPort }}")
    private val client = Client(HttpClient.newHttpClient(), VersionAdapter.V10)

    @AfterEach
    fun stop() = server.stop(0)

    @Test
    fun `operations go round robin over the nodes`() {
        val targets = Targets(listOf(up, stopped, up))
        (0L until 6L).map(targets::pick).assert().containsExactly(0, 1, 2, 0, 1, 2)
    }

    @Test
    fun `an operation a stopped node refused runs on the next node, and the node is left out`() {
        val targets = Targets(listOf(stopped, up))
        targets.run(Scenario.E1, client, 0, 1, 1).join().assert().isNull()
        targets.rerouted.get().assert().isEqualTo(1L)
        answered.get().assert().isEqualTo(1)
        // Left out for the cooldown: the next operations that would go to it go to the node that is up.
        (0L until 4L).map(targets::pick).assert().containsExactly(1, 1, 1, 1)
        (0L until 4L).forEach { targets.run(Scenario.E1, client, it, 1, 1).join().assert().isNull() }
        targets.rerouted.get().assert().isEqualTo(1L)
    }

    @Test
    fun `a node is tried again after its cooldown`() {
        val targets = Targets(listOf(stopped, up), cooldownNanos = 0)
        targets.run(Scenario.E1, client, 0, 1, 1).join()
        targets.pick(0).assert().isEqualTo(0)
    }

    @Test
    fun `when every node refuses, the operation fails`() {
        val targets = Targets(listOf(stopped))
        runCatching { targets.run(Scenario.E1, client, 0, 1, 1).join() }.isFailure.assert().isTrue()
        targets.pick(0).assert().isEqualTo(0)
    }
}
