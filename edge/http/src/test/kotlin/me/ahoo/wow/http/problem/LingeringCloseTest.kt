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

package me.ahoo.wow.http.problem

import io.netty.buffer.Unpooled
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.DecoderResult
import io.netty.handler.codec.http.DefaultFullHttpRequest
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.DefaultHttpRequest
import io.netty.handler.codec.http.DefaultLastHttpContent
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpHeaderValues
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.TooLongHttpHeaderException
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import reactor.netty.NettyPipeline

/**
 * [LingeringClose] lingers only after an answer written while its request's body was still arriving. An embedded
 * channel half-closes no socket, so where it would linger it closes at once: the lingering itself, on a socket, is the
 * host's `ServerRefusalsTest`'s to show.
 */
class LingeringCloseTest {
    private fun channel(): EmbeddedChannel = EmbeddedChannel(LingeringClose())

    private fun request(length: Long? = null, chunked: Boolean = false): DefaultHttpRequest =
        DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/").apply {
            length?.let { headers().set(HttpHeaderNames.CONTENT_LENGTH, it) }
            if (chunked) headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED)
        }

    private fun answer(status: HttpResponseStatus = HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE) =
        DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.buffer(0))

    private fun closesAtOnce(channel: EmbeddedChannel) {
        channel.close().isDone.assert().isTrue()
        channel.isOpen.assert().isFalse()
    }

    @Test
    fun `an answer after the body was read to its end should close at once`() {
        val channel = channel()
        channel.writeInbound(request(length = 2), DefaultLastHttpContent(Unpooled.copiedBuffer("{}", Charsets.UTF_8)))
        channel.writeOutbound(answer(HttpResponseStatus.OK))
        closesAtOnce(channel)
        channel.finishAndReleaseAll()
    }

    @Test
    fun `an early answer on a channel that does not half-close should close at once`() {
        val channel = channel()
        channel.writeInbound(request(length = 300_000))
        // 100 Continue is no answer: the body is still to come.
        channel.writeOutbound(answer(HttpResponseStatus.CONTINUE))
        channel.writeOutbound(answer())
        closesAtOnce(channel)
        channel.finishAndReleaseAll()
    }

    @Test
    fun `a request without a body, chunked, or that could not be decoded should be told apart`() {
        // No body: an answer is never early, and a chunked body or an undecodable request may still be arriving.
        listOf(
            DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/", Unpooled.buffer(0)),
            request(chunked = true),
            DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/bad-request", Unpooled.buffer(0)).apply {
                setDecoderResult(DecoderResult.failure(TooLongHttpHeaderException("too large")))
            },
        ).forEach { request ->
            val channel = channel()
            channel.writeInbound(request)
            channel.writeOutbound(answer())
            closesAtOnce(channel)
            channel.finishAndReleaseAll()
        }
    }

    @Test
    fun `it should be installed once, before the traffic handler, and nowhere without one`() {
        val http = EmbeddedChannel()
        http.pipeline().addLast(NettyPipeline.HttpTrafficHandler, ChannelInboundHandlerAdapter())
        LingeringClose.install(http)
        LingeringClose.install(http)
        http.pipeline().names().filter { it == LingeringClose.NAME }.assert().hasSize(1)
        http.pipeline().names().indexOf(LingeringClose.NAME).assert()
            .isLessThan(http.pipeline().names().indexOf(NettyPipeline.HttpTrafficHandler))
        val other = EmbeddedChannel()
        LingeringClose.install(other)
        other.pipeline().get(LingeringClose.NAME).assert().isNull()
    }
}
