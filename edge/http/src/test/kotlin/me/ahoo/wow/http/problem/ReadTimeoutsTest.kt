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
import io.netty.handler.codec.http.DefaultHttpContent
import io.netty.handler.codec.http.DefaultHttpRequest
import io.netty.handler.codec.http.DefaultHttpResponse
import io.netty.handler.codec.http.DefaultLastHttpContent
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpHeaderValues
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.TooLongHttpHeaderException
import io.netty.handler.timeout.ReadTimeoutException
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import reactor.netty.NettyPipeline
import reactor.netty.http.server.HttpServer
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * [ReadTimeouts]' handlers on an embedded channel whose time is frozen: what each does at the timeout. On a real
 * server, with Reactor Netty's own timeout, the internal port's `InternalPortTest` and the host's `ReadTimeoutTest`.
 */
class ReadTimeoutsTest {
    private val timeout = 30.seconds

    private fun request(length: Long? = null, chunked: Boolean = false): DefaultHttpRequest =
        DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/").apply {
            length?.let { headers().set(HttpHeaderNames.CONTENT_LENGTH, it) }
            if (chunked) headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED)
        }

    private fun answer(status: HttpResponseStatus = HttpResponseStatus.NOT_FOUND): FullHttpResponse =
        DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.buffer(0))

    private fun content(): DefaultHttpContent = DefaultHttpContent(Unpooled.copiedBuffer("ab", Charsets.UTF_8))

    private fun drains(): EmbeddedChannel = EmbeddedChannel(ReadTimeouts.Drains(timeout)).apply { freezeTime() }

    private fun EmbeddedChannel.after(duration: Duration) {
        advanceTimeBy(duration.inWholeNanoseconds, TimeUnit.NANOSECONDS)
        runScheduledPendingTasks()
    }

    @Test
    fun `a body still arriving after its answer should close its connection once it stalls for the timeout`() {
        val channel = drains()
        channel.writeInbound(request(length = 300_000), content())
        channel.writeOutbound(answer())
        channel.after(timeout - 1.seconds)
        channel.isOpen.assert().isTrue()
        channel.after(1.seconds)
        channel.isOpen.assert().isFalse()
        channel.finishAndReleaseAll()
    }

    @Test
    fun `each read of the body after its answer should push the close back by the timeout`() {
        val channel = drains()
        channel.writeInbound(request(length = 300_000))
        channel.writeOutbound(answer())
        channel.after(20.seconds)
        channel.writeInbound(content())
        // 30 seconds after the answer, 10 after the last read: the check waits for the rest.
        channel.after(10.seconds)
        channel.isOpen.assert().isTrue()
        channel.after(20.seconds)
        channel.isOpen.assert().isFalse()
        channel.finishAndReleaseAll()
    }

    @Test
    fun `an answer ended by a last part of its own should start one check, however many ends follow`() {
        val channel = drains()
        channel.writeInbound(request(chunked = true))
        channel.writeOutbound(DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND))
        channel.writeOutbound(DefaultLastHttpContent())
        channel.writeOutbound(DefaultLastHttpContent())
        channel.after(timeout)
        channel.isOpen.assert().isFalse()
        channel.finishAndReleaseAll()
    }

    @Test
    fun `a body read to its end should leave its connection to the next request, however long it waits`() {
        val channel = drains()
        channel.writeInbound(request(length = 300_000))
        channel.writeOutbound(answer())
        channel.writeInbound(DefaultLastHttpContent(Unpooled.copiedBuffer("ab", Charsets.UTF_8)))
        channel.after(timeout * 3)
        channel.isOpen.assert().isTrue()
        // A body read before its answer, as a route reads it: the answer leaves no check either.
        channel.writeInbound(request(length = 2), DefaultLastHttpContent(Unpooled.copiedBuffer("{}", Charsets.UTF_8)))
        channel.writeOutbound(answer(HttpResponseStatus.OK))
        channel.after(timeout * 3)
        channel.isOpen.assert().isTrue()
        channel.finishAndReleaseAll()
    }

    @Test
    fun `no check should start for an interim answer, a request without a body, or one that was not decoded`() {
        val interim = drains()
        interim.writeInbound(request(chunked = true))
        interim.writeOutbound(answer(HttpResponseStatus.CONTINUE))
        // A response's head alone is not its end.
        interim.writeOutbound(DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK))
        interim.after(timeout * 3)
        interim.isOpen.assert().isTrue()
        interim.finishAndReleaseAll()
        listOf(
            DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/", Unpooled.buffer(0)),
            request(),
            request(length = 300_000).apply {
                setDecoderResult(DecoderResult.failure(TooLongHttpHeaderException("too large")))
            },
        ).forEach { request ->
            val channel = drains()
            channel.writeInbound(request)
            channel.writeOutbound(answer())
            channel.after(timeout * 3)
            channel.isOpen.assert().isTrue()
            channel.finishAndReleaseAll()
        }
    }

    @Test
    fun `a connection closed during the check should leave nothing behind`() {
        val channel = drains()
        channel.writeInbound(request(length = 300_000))
        channel.writeOutbound(answer())
        channel.close()
        channel.after(timeout * 3)
        channel.isOpen.assert().isFalse()
        channel.finishAndReleaseAll()
    }

    @Test
    fun `a body that stalls while it is handled should be answered 408 naming the timeout and its key`() {
        val channel = EmbeddedChannel(ReadTimeouts.Answers(timeout))
        channel.writeInbound(request(length = 300_000))
        channel.pipeline().fireExceptionCaught(ReadTimeoutException.INSTANCE)
        val response = channel.readOutbound<FullHttpResponse>()
        response.status().code().assert().isEqualTo(408)
        response.headers()[HttpHeaderNames.CONTENT_TYPE].assert().isEqualTo("application/problem+json")
        response.headers()[HttpHeaderNames.CONNECTION].assert().isEqualTo("close")
        val body = response.content().toString(Charsets.UTF_8)
        response.release()
        body.assert().contains("The request's body stalled longer than this server waits: 30s (wow.http.read-timeout).")
        // The route is not handed the timeout: nothing reached the end of the pipeline.
        channel.checkException()
        channel.finishAndReleaseAll()
    }

    @Test
    fun `an interim answer should not count as the answer begun`() {
        val channel = EmbeddedChannel(ReadTimeouts.Answers(timeout))
        channel.writeInbound(request(length = 300_000))
        channel.writeOutbound(answer(HttpResponseStatus.CONTINUE))
        channel.readOutbound<FullHttpResponse>().release()
        channel.pipeline().fireExceptionCaught(ReadTimeoutException.INSTANCE)
        channel.readOutbound<FullHttpResponse>().apply { status().code().assert().isEqualTo(408) }.release()
        channel.finishAndReleaseAll()
    }

    @Test
    fun `a stall after the answer began should not be answered again, and other failures should go on`() {
        val channel = EmbeddedChannel(ReadTimeouts.Answers(timeout))
        channel.writeInbound(request(length = 300_000))
        channel.writeOutbound(DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK))
        channel.readOutbound<Any>()
        channel.pipeline().fireExceptionCaught(ReadTimeoutException.INSTANCE)
        channel.readOutbound<Any>().assert().isNull()
        // The next request's answer has not begun.
        channel.writeInbound(request(length = 300_000))
        channel.pipeline().fireExceptionCaught(ReadTimeoutException.INSTANCE)
        channel.readOutbound<FullHttpResponse>().apply { status().code().assert().isEqualTo(408) }.release()
        channel.pipeline().fireExceptionCaught(IllegalStateException("other"))
        assertThrownBy<IllegalStateException> { channel.checkException() }
        channel.finishAndReleaseAll()
    }

    @Test
    fun `they should be installed once, around the traffic handler, the drains before a lingering close`() {
        val channel = EmbeddedChannel()
        channel.pipeline()
            .addLast(LingeringClose.NAME, ChannelInboundHandlerAdapter())
            .addLast(NettyPipeline.HttpTrafficHandler, ChannelInboundHandlerAdapter())
            .addLast(NettyPipeline.ReactiveBridge, ChannelInboundHandlerAdapter())
        ReadTimeouts.install(channel, timeout)
        ReadTimeouts.install(channel, timeout)
        channel.pipeline().names().filter { it.startsWith("wow.readTimeout") }.assert()
            .containsExactly(ReadTimeouts.DRAINS, ReadTimeouts.ANSWERS)
        channel.pipeline().names().subList(0, 5).assert().containsExactly(
            ReadTimeouts.DRAINS,
            LingeringClose.NAME,
            NettyPipeline.HttpTrafficHandler,
            ReadTimeouts.ANSWERS,
            NettyPipeline.ReactiveBridge,
        )
        // Without a lingering close, the drains go right before the traffic handler; without a bridge, no answers.
        val plain = EmbeddedChannel()
        plain.pipeline().addLast(NettyPipeline.HttpTrafficHandler, ChannelInboundHandlerAdapter())
        ReadTimeouts.install(plain, timeout)
        plain.pipeline().names().subList(0, 2).assert()
            .containsExactly(ReadTimeouts.DRAINS, NettyPipeline.HttpTrafficHandler)
        plain.pipeline().get(ReadTimeouts.ANSWERS).assert().isNull()
        // Nothing on a channel without a traffic handler.
        val other = EmbeddedChannel()
        ReadTimeouts.install(other, timeout)
        other.pipeline().get(ReadTimeouts.DRAINS).assert().isNull()
    }

    @Test
    fun `a timeout that is not positive should be refused`() {
        assertThrownBy<IllegalArgumentException> { ReadTimeouts.of(HttpServer.create(), Duration.ZERO) }
    }
}
