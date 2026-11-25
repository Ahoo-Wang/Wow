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

package me.ahoo.wow.analytics.catalog.server

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.analytics.catalog.testing.MemoryWarehouseConfiguration
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.wire.WowHeaders
import me.ahoo.wow.client.WowClient
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ManifestTree
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The catalog reads an owning service's Manifest as the Kotlin peers do: in `wow.peer.mode`, `service` by default,
 * from the peer's internal port (`wow.peers.<context>.url`) as a service, so the owning service need not serve it on
 * its public port, where `wow.http.manifest.enabled` is off by default. Its relayed commands still go in the context's
 * own mode (`mode: user`), which [ReferenceActionsServerTest] holds.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ManifestReadModeTest {
    /** What each port of the owning service took: the path and the service the request named. */
    private val internalTaken = CopyOnWriteArrayList<Pair<String, String?>>()
    private val publicTaken = CopyOnWriteArrayList<String>()

    private val internal: HttpServer = server { exchange ->
        internalTaken += exchange.requestURI.path to exchange.requestHeaders.getFirst(WowHeaders.SERVICE)
        reply(exchange, ManifestTree.write(MANIFEST))
    }
    private val public: HttpServer = server { exchange ->
        publicTaken += exchange.requestURI.path
        exchange.sendResponseHeaders(NOT_FOUND, -1)
        exchange.close()
    }

    @AfterAll
    fun close() {
        internal.stop(0)
        public.stop(0)
    }

    @Test
    fun `a peer's Manifest should be read from its internal port as a service, by default`() {
        startCatalog(
            "wow.peers.crm.url" to "http://127.0.0.1:${internal.address.port}",
            "wow.peers.crm.public-url" to "http://127.0.0.1:${public.address.port}",
            "wow.peers.crm.contract" to "manifest",
            "wow.peers.crm.mode" to "user",
            sources = listOf(MemoryWarehouseConfiguration::class.java),
        ).use { node ->
            val client = node.context.getBean(WowClient::class.java)
            runBlocking { client.manifestOf("crm", Caller.ANONYMOUS) }.contexts.map { it.name }.assert()
                .containsExactly("crm")
            internalTaken.single().first.assert().isEqualTo("/wow/manifest")
            internalTaken.single().second.assert().isNotBlank()
            publicTaken.assert().isEmpty()
        }
    }

    private fun server(handle: (HttpExchange) -> Unit): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange -> handle(exchange) }
            start()
        }

    private fun reply(exchange: HttpExchange, value: Any) {
        val bytes = WowJson.mapper.writeValueAsBytes(value)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(OK, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private companion object {
        const val OK = 200
        const val NOT_FOUND = 404

        @OptIn(InternalWowApi::class)
        val MANIFEST: Manifest = Manifest(contexts = listOf(ContextManifest(name = "crm")))
    }
}
