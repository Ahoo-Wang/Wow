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

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.DecoderResult
import io.netty.handler.codec.http.DefaultFullHttpRequest
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.TooLongHttpHeaderException
import io.netty.handler.codec.http.TooLongHttpLineException
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import reactor.netty.NettyPipeline

/**
 * [ServerRefusals] rewrites the answer Reactor Netty's traffic handler writes to a request whose decoding failed, and
 * nothing else: here a stand-in traffic handler answers a failed request at once, as Reactor Netty does
 * (`HttpServerOperations.sendDecodingFailures`), and a route's answer is written apart from it.
 */
class ServerRefusalsTest {
    private val limits = ServerRefusals.Limits("4096 bytes (line key)", "8192 bytes (header key)")

    /** Answers a request whose decoding failed with Reactor Netty's empty refusal, there and then. */
    private class Refusing : ChannelInboundHandlerAdapter() {
        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            val request = msg as DefaultFullHttpRequest
            val cause = request.decoderResult().cause()
            request.release()
            if (cause == null) return
            val status = when (cause) {
                is TooLongHttpLineException -> HttpResponseStatus.REQUEST_URI_TOO_LONG
                is TooLongHttpHeaderException -> HttpResponseStatus.REQUEST_HEADER_FIELDS_TOO_LARGE
                else -> HttpResponseStatus.BAD_REQUEST
            }
            ctx.writeAndFlush(empty(status))
        }
    }

    private fun channel(): EmbeddedChannel = EmbeddedChannel(ServerRefusals(limits), Refusing())

    private fun failed(cause: Throwable?): DefaultFullHttpRequest =
        DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/", Unpooled.buffer(0)).apply {
            if (cause != null) setDecoderResult(DecoderResult.failure(cause))
        }

    private fun answerTo(cause: Throwable): FullHttpResponse {
        val channel = channel()
        channel.writeInbound(failed(cause))
        return channel.readOutbound<FullHttpResponse>()
    }

    @Test
    fun `a request line past the limit should be answered 414 with a problem naming the limit`() {
        val answer = answerTo(TooLongHttpLineException("too long"))
        answer.status().code().assert().isEqualTo(414)
        answer.headers()[HttpHeaderNames.CONTENT_TYPE].assert().isEqualTo("application/problem+json")
        answer.headers()[HttpHeaderNames.CONNECTION].assert().isEqualTo("close")
        val body = answer.content().toString(Charsets.UTF_8)
        answer.headers().getInt(HttpHeaderNames.CONTENT_LENGTH).assert().isEqualTo(body.toByteArray().size)
        body.assert().isEqualTo(
            Problem.ofStatus(
                org.springframework.http.HttpStatus.URI_TOO_LONG,
                "The request line is longer than this server takes: 4096 bytes (line key).",
            ).body().toString(Charsets.UTF_8),
        )
        answer.release()
    }

    @Test
    fun `a header block past the limit should be answered 431, and any other failure 400`() {
        answerTo(TooLongHttpHeaderException("too large")).let {
            it.status().code().assert().isEqualTo(431)
            it.content().toString(Charsets.UTF_8).assert()
                .contains("The request's headers are larger than this server takes: 8192 bytes (header key).")
            it.release()
        }
        answerTo(IllegalArgumentException("Multiple Content-Length values found")).let {
            it.status().code().assert().isEqualTo(400)
            it.content().toString(Charsets.UTF_8).assert().contains("The request is not HTTP this server can read.")
                .doesNotContain("Content-Length")
            it.release()
        }
    }

    @Test
    fun `an answer written apart from a failed request's handling should never be rewritten, however it looks`() {
        val channel = channel()
        // A request decoded whole, then an empty 400 that closes, as a route could write it: left as it is.
        channel.writeInbound(failed(null))
        channel.writeOutbound(empty(HttpResponseStatus.BAD_REQUEST))
        channel.readOutbound<FullHttpResponse>().let {
            it.content().readableBytes().assert().isEqualTo(0)
            it.headers()[HttpHeaderNames.CONTENT_TYPE].assert().isNull()
            it.release()
        }
        // After a refusal, the next answer is no refusal's.
        channel.writeInbound(failed(TooLongHttpLineException("too long")))
        channel.readOutbound<FullHttpResponse>().release()
        channel.writeOutbound(empty(HttpResponseStatus.REQUEST_URI_TOO_LONG))
        channel.readOutbound<FullHttpResponse>().let {
            it.content().readableBytes().assert().isEqualTo(0)
            it.release()
        }
        channel.finishAndReleaseAll()
    }

    @Test
    fun `only the full response of a refusal should be rewritten, what is written before it passed on`() {
        // A stand-in that writes some bytes ahead of its refusal: they go on as they are.
        val ahead = Unpooled.copiedBuffer("ahead", Charsets.UTF_8)
        val channel = EmbeddedChannel(
            ServerRefusals(limits),
            object : ChannelInboundHandlerAdapter() {
                override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                    (msg as DefaultFullHttpRequest).release()
                    ctx.write(ahead.retainedDuplicate())
                    ctx.writeAndFlush(empty(HttpResponseStatus.REQUEST_URI_TOO_LONG))
                }
            },
        )
        channel.writeInbound(failed(TooLongHttpLineException("too long")))
        channel.readOutbound<ByteBuf>().let {
            it.toString(Charsets.UTF_8).assert().isEqualTo("ahead")
            it.release()
        }
        channel.readOutbound<FullHttpResponse>().let {
            it.status().code().assert().isEqualTo(414)
            it.headers()[HttpHeaderNames.CONTENT_TYPE].assert().isEqualTo("application/problem+json")
            it.release()
        }
        ahead.release()
        channel.finishAndReleaseAll()
    }

    @Test
    fun `it should be installed once, before the traffic handler, and nowhere without one`() {
        val http = EmbeddedChannel()
        http.pipeline().addLast(NettyPipeline.HttpTrafficHandler, Refusing())
        ServerRefusals.install(http, limits)
        ServerRefusals.install(http, limits)
        http.pipeline().names().filter { it == ServerRefusals.NAME }.assert().hasSize(1)
        http.pipeline().names().indexOf(ServerRefusals.NAME).assert()
            .isLessThan(http.pipeline().names().indexOf(NettyPipeline.HttpTrafficHandler))
        val other = EmbeddedChannel()
        ServerRefusals.install(other, limits)
        other.pipeline().get(ServerRefusals.NAME).assert().isNull()
    }

    private companion object {
        /** Reactor Netty's refusal: an empty body that closes the connection. */
        fun empty(status: HttpResponseStatus): FullHttpResponse =
            DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.buffer(0)).apply {
                headers().setInt(HttpHeaderNames.CONTENT_LENGTH, 0).set(HttpHeaderNames.CONNECTION, "close")
            }
    }
}
