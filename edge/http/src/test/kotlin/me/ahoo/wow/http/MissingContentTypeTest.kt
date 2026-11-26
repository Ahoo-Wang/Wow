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

package me.ahoo.wow.http

import me.ahoo.test.asserts.assert
import me.ahoo.wow.http.internal.InternalHttp
import me.ahoo.wow.http.internal.InternalServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * D485 ⑪: a body sent with no `Content-Type` is 415, as one of another type is (#6818's defence against a cross-site
 * request, which a browser sends without a preflight with no type at all), over a real Reactor Netty server: a body of
 * a known length and a chunked one on HTTP/1.1, and on HTTP/2, where a stream that carries data without a length is
 * given a `Transfer-Encoding` by Netty. A request without a body needs no type.
 */
class MissingContentTypeTest {
    private val node = QueryNode()
    private val server = InternalServer(node.http.routes)

    @AfterEach
    fun close() = server.close()

    private val http1 = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

    private fun post(
        client: HttpClient,
        path: String,
        body: HttpRequest.BodyPublisher,
        type: String? = null,
    ): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("http://${server.address}$path"))
            .header("X-User", "alice")
            .apply { if (type != null) header("Content-Type", type) }
            .POST(body)
            .build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun fixed(text: String) = HttpRequest.BodyPublishers.ofString(text)

    /** [text] of no announced length: chunked on HTTP/1.1, a stream of data without `content-length` on HTTP/2. */
    private fun chunked(text: String): HttpRequest.BodyPublisher {
        val bytes = text.toByteArray()
        return HttpRequest.BodyPublishers.ofInputStream { ByteArrayInputStream(bytes) }
    }

    private fun HttpResponse<String>.assertRefused(what: String) {
        statusCode().assert().isEqualTo(415)
        headers().firstValue("Content-Type").get().assert().isEqualTo("application/problem+json")
        body().assert().contains("$what $REFUSED")
    }

    @Test
    fun `a command's body with no Content-Type should be 415, of a known length or chunked, and the command not run`() {
        post(http1, COMMAND, fixed(ITEM)).assertRefused("A command")
        post(http1, COMMAND, chunked(ITEM)).assertRefused("A command")
        node.http.commands.assert().isEmpty()
        post(http1, COMMAND, fixed(ITEM), type = "application/json").statusCode().assert().isEqualTo(200)
        node.http.commands.assert().hasSize(1)
    }

    @Test
    fun `a query's body with no Content-Type should be 415`() {
        post(http1, QUERY, fixed(COUNT)).assertRefused("A query")
        post(http1, QUERY, chunked(COUNT)).assertRefused("A query")
        post(http1, QUERY, fixed(COUNT), type = "application/json").statusCode().assert().isEqualTo(200)
    }

    @Test
    fun `a request with no body should need no Content-Type`() {
        // Checkout takes no field: an empty body is its empty object, with no type to give.
        post(http1, CHECKOUT, HttpRequest.BodyPublishers.noBody()).statusCode().assert().isNotEqualTo(415)
        post(http1, CHECKOUT, fixed("")).statusCode().assert().isNotEqualTo(415)
    }

    @Test
    fun `a body with no Content-Type should be 415 on HTTP 2, of a known length or none`() {
        val h2 = InternalHttp.client()
        // The first request upgrades the connection to h2c; the ones after it are HTTP/2 streams.
        h2.send(
            HttpRequest.newBuilder(URI.create("http://${server.address}/shop/cart/c-1/state")).header("X-User", "alice")
                .build(),
            HttpResponse.BodyHandlers.discarding(),
        )
        listOf(fixed(ITEM), chunked(ITEM)).forEach { body ->
            val refused = post(h2, COMMAND, body)
            refused.version().assert().isEqualTo(HttpClient.Version.HTTP_2)
            refused.assertRefused("A command")
        }
        node.http.commands.assert().isEmpty()
        val taken = post(h2, COMMAND, chunked(ITEM), type = "application/json")
        taken.version().assert().isEqualTo(HttpClient.Version.HTTP_2)
        taken.statusCode().assert().isEqualTo(200)
    }

    private companion object {
        const val COMMAND = "/shop/cart/c-1/add-item"
        const val CHECKOUT = "/shop/cart/c-1/checkout"
        const val QUERY = "/shop/cart/snapshot/query"
        const val ITEM = """{"sku":"apple"}"""
        const val COUNT = """{"kind":"count","filter":{"op":"match-all"}}"""
        const val REFUSED = "is sent with Content-Type application/json or application/*+json; none was given."
    }
}
