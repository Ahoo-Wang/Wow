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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.buildAndAwait
import org.springframework.web.reactive.function.server.coRouter
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletionException
import java.util.concurrent.atomic.AtomicInteger

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

    @Test
    fun `a port takes MAX_CONCURRENT_STREAMS streams on a connection, which the JDK client keeps to`(): Unit =
        runBlocking {
            val release = CompletableDeferred<Unit>()
            val arrived = AtomicInteger()
            val port = InternalPort.of(
                InternalPortSettings(port = 0, bindHost = "127.0.0.1"),
                coRouter {
                    GET("/ping") { ServerResponse.ok().buildAndAwait() }
                    GET("/held") {
                        arrived.incrementAndGet()
                        release.await()
                        ServerResponse.ok().buildAndAwait()
                    }
                },
            )
            port.start()
            try {
                val client = InternalHttp.client()
                fun get(path: String) = client.sendAsync(
                    HttpRequest.newBuilder(InternalHttp.uri("127.0.0.1:${port.boundPort}", path)).build(),
                    HttpResponse.BodyHandlers.discarding(),
                )
                // The first request upgrades the connection to h2c; the others multiplex on it.
                get("/ping").await().statusCode().assert().isEqualTo(200)
                val held = (1..InternalHttp.MAX_CONCURRENT_STREAMS).map { get("/held") }
                eventually { arrived.get() == InternalHttp.MAX_CONCURRENT_STREAMS }
                val refused = runCatching { get("/held").join() }.exceptionOrNull()
                (refused as CompletionException).cause!!.isStreamLimit().assert().isTrue()
                release.complete(Unit)
                held.map { it.await().statusCode() }.assert().containsOnly(200)
            } finally {
                port.stop()
            }
        }
}
