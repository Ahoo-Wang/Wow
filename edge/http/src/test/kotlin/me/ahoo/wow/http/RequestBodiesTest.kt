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

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.core.codec.ByteArrayDecoder
import org.springframework.core.codec.StringDecoder
import org.springframework.core.io.buffer.DataBufferLimitException
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.http.codec.DecoderHttpMessageReader
import org.springframework.http.codec.HttpMessageReader
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.reactive.function.server.ServerRequest
import reactor.core.publisher.Flux

/**
 * [awaitBytesOrNull] refuses a body from its `Content-Length` only past a limit its byte array decoder sets: with no
 * limit, no such decoder or no length, the body is read as before (#7001).
 */
class RequestBodiesTest {
    private val large = "a".repeat(300 * 1_024)

    private fun request(readers: List<HttpMessageReader<*>>, body: MockServerHttpRequest): ServerRequest =
        ServerRequest.create(MockServerWebExchange.from(body), readers)

    /** A `POST` that announces a body of [length] bytes and carries [body]. */
    private fun announced(length: Long, body: String): MockServerHttpRequest =
        MockServerHttpRequest.post("/").contentLength(length).body(body)

    private fun bytes(maxInMemorySize: Int): DecoderHttpMessageReader<ByteArray> =
        DecoderHttpMessageReader(ByteArrayDecoder().apply { this.maxInMemorySize = maxInMemorySize })

    @Test
    fun `a body past the limit should be refused from its Content-Length, one within it read`(): Unit = runBlocking {
        // The body itself is small: only its Content-Length is past the limit, so the refusal is the early one.
        val refused = runCatching {
            request(listOf(bytes(LIMIT)), announced(large.length.toLong(), "{}")).awaitBytesOrNull()
        }.exceptionOrNull()
        refused.assert().isInstanceOf(DataBufferLimitException::class.java)
        request(listOf(bytes(LIMIT)), announced(2, "{}")).awaitBytesOrNull()!!
            .toString(Charsets.UTF_8).assert().isEqualTo("{}")
    }

    @Test
    fun `a body should be read when its decoder sets no limit`(): Unit = runBlocking {
        request(listOf(bytes(-1)), announced(large.length.toLong(), large)).awaitBytesOrNull()!!.size.assert()
            .isEqualTo(large.length)
    }

    @Test
    fun `a body without a Content-Length should be read, its limit met by reading`(): Unit = runBlocking {
        val chunked = MockServerHttpRequest.post("/").body(
            Flux.just(DefaultDataBufferFactory.sharedInstance.wrap("{}".toByteArray())),
        )
        request(listOf(bytes(LIMIT)), chunked).awaitBytesOrNull()!!.toString(Charsets.UTF_8).assert()
            .isEqualTo("{}")
    }

    @Test
    fun `no byte array decoder should set no limit to refuse by`(): Unit = runBlocking {
        // With no reader of bytes the body cannot be read at all, but that is not the limit's refusal.
        val failure = runCatching {
            val strings = DecoderHttpMessageReader(StringDecoder.textPlainOnly())
            request(listOf(strings), announced(large.length.toLong(), "{}")).awaitBytesOrNull()
        }.exceptionOrNull()
        failure.assert().isNotNull().isNotInstanceOf(DataBufferLimitException::class.java)
    }

    private companion object {
        /** WebFlux's default in-memory limit. */
        const val LIMIT = 256 * 1_024
    }
}
