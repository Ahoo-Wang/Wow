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
import io.netty.channel.Channel
import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPromise
import io.netty.handler.codec.DecoderResultProvider
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpHeaderValues
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponse
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpUtil
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.LastHttpContent
import io.netty.handler.timeout.ReadTimeoutException
import io.netty.util.concurrent.ScheduledFuture
import me.ahoo.wow.api.annotation.InternalWowApi
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import reactor.netty.NettyPipeline
import reactor.netty.http.server.HttpServer
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/**
 * A server's read timeout (#6973): the longest a request's body may stall, nothing of it read, from the request's
 * headers to the body's end. It never bounds the answer: a stream or a wait runs as long as it does.
 *
 * - **While its request is handled**, Reactor Netty times the body (`HttpServer.readTimeout`) and closes the connection
 *   past the timeout, and [Answers] answers 408 first, its detail naming the timeout and its key as the server's other
 *   refusals do ([ServerRefusals]). The route's body ends as a client's that went away. Over HTTP/2 the stream is reset
 *   after its answer, its request still open; a client still sending, as the JDK's is, keeps the reset alone.
 * - **Once its request is answered**, a body a route did not read to its end (a 401 or a 404 answered from the
 *   headers) is read on over HTTP/1.1, so that the kept-alive connection takes the next request; Reactor Netty's timeout
 *   ended with the answer. [Drains] bounds that read too: a connection whose body stalls as long is closed. An HTTP/2
 *   stream is reset after its answer instead.
 *
 * Over HTTP/1.1, both are installed once per connection the server sets up for it at its start (cleartext, or TLS
 * without ALPN), as [LingeringClose] is; [Answers] is installed per request otherwise, an HTTP/2 stream's included.
 */
@InternalWowApi
object ReadTimeouts {
    /** The key of the host that sets the timeout. */
    const val KEY: String = "wow.http.read-timeout"

    /** The name of [Answers] in a pipeline. */
    const val ANSWERS: String = "wow.readTimeoutAnswers"

    /** The name of [Drains] in an HTTP/1.1 connection's pipeline. */
    const val DRAINS: String = "wow.readTimeoutDrains"

    /** The first status that is no interim answer, as `100 Continue` is. */
    private const val FINAL_STATUS = 200

    /** [server], whose requests' bodies may stall for [timeout] at most. */
    fun of(server: HttpServer, timeout: Duration): HttpServer {
        require(timeout.isPositive()) { "A read timeout is positive: $timeout" }
        return server.readTimeout(timeout.toJavaDuration())
            .doOnChannelInit { _, channel, _ -> install(channel, timeout) }
            // A request on a channel set up otherwise: before the route, after Reactor Netty's timeout handler.
            .doOnConnection { connection ->
                if (connection.channel().pipeline().get(ANSWERS) == null) {
                    connection.addHandlerLast(ANSWERS, Answers(timeout))
                }
            }
    }

    /**
     * Puts the handlers of [timeout] in [channel]'s HTTP/1.1 pipeline, once; nothing on a channel without Reactor
     * Netty's traffic handler.
     *
     * - [Drains] before the traffic handler, and before a [LingeringClose], so that its own close does not linger.
     * - [Answers] right before Reactor Netty's bridge to the route: after the traffic handler, so after the timeout
     *   handler Reactor Netty puts there for each request.
     */
    fun install(channel: Channel, timeout: Duration) {
        val pipeline = channel.pipeline()
        if (pipeline.get(NettyPipeline.HttpTrafficHandler) == null || pipeline.get(DRAINS) != null) {
            return
        }
        val first = if (pipeline.get(LingeringClose.NAME) != null) LingeringClose.NAME else NettyPipeline.HttpTrafficHandler
        pipeline.addBefore(first, DRAINS, Drains(timeout))
        if (pipeline.get(NettyPipeline.ReactiveBridge) != null) {
            pipeline.addBefore(NettyPipeline.ReactiveBridge, ANSWERS, Answers(timeout))
        }
    }

    /** The answer to a request whose body stalls past [timeout]: a 408 naming the timeout and [KEY]. */
    fun stalled(timeout: Duration): Problem = Problem.ofStatus(
        HttpStatus.REQUEST_TIMEOUT,
        "The request's body stalled longer than this server waits: $timeout ($KEY).",
    )

    /** Whether [msg] begins an answer: a response that is no interim one. */
    private fun begins(msg: Any): Boolean = msg is HttpResponse && msg.status().code() >= FINAL_STATUS

    /** Whether [msg] ends an answer: a response's last part that is no interim one. */
    private fun ends(msg: Any): Boolean =
        msg is LastHttpContent && !(msg is HttpResponse && msg.status().code() < FINAL_STATUS)

    /** Whether [request]'s body may still be arriving: one is announced, and the request was decoded. */
    private fun bodyOpens(request: HttpRequest): Boolean =
        !(request as DecoderResultProvider).decoderResult().isFailure &&
            request !is LastHttpContent &&
            (HttpUtil.getContentLength(request, 0L) > 0 || HttpUtil.isTransferEncodingChunked(request))

    /** [problem] as a response that closes its connection. */
    private fun responseOf(problem: Problem): FullHttpResponse {
        val body = problem.body()
        val response = DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            HttpResponseStatus.valueOf(problem.status),
            Unpooled.wrappedBuffer(body),
        )
        response.headers()
            .set(HttpHeaderNames.CONTENT_TYPE, MediaType.APPLICATION_PROBLEM_JSON_VALUE)
            .setInt(HttpHeaderNames.CONTENT_LENGTH, body.size)
            .set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE)
        return response
    }

    /**
     * Answers a request whose body stalled while it was handled: 408 with [stalled]'s problem, unless its answer has
     * begun; Reactor Netty's timeout handler then closes the connection (over HTTP/2, the stream). The route is not
     * handed the timeout as a failure: it would answer it as its own, a 500 logged as an error, and Reactor Netty signals
     * an inbound failure twice to a route that answers, the second logged as an error too.
     */
    internal class Answers(private val timeout: Duration) : ChannelDuplexHandler() {
        /** Whether the current request's answer has begun. */
        private var answering = false

        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            if (msg is HttpRequest) {
                answering = false
            }
            ctx.fireChannelRead(msg)
        }

        override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
            if (begins(msg)) {
                answering = true
            }
            ctx.write(msg, promise)
        }

        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            if (cause !is ReadTimeoutException) {
                ctx.fireExceptionCaught(cause)
                return
            }
            if (!answering) {
                answering = true
                ctx.writeAndFlush(responseOf(stalled(timeout)))
            }
        }
    }

    /**
     * Closes an HTTP/1.1 connection whose request was answered while its body was still arriving, once nothing of the
     * body has been read for [timeout].
     */
    internal class Drains(private val timeout: Duration) : ChannelDuplexHandler() {
        /** Whether the current request's body may still be arriving: begun and not yet read to its end. */
        private var bodyOpen = false

        /** When the body was last read, by the event loop's ticker. */
        private var lastRead = 0L

        /** The check of the stall, from the answer to the body's end. */
        private var check: ScheduledFuture<*>? = null

        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            if (msg is HttpRequest) {
                cancel()
                bodyOpen = bodyOpens(msg)
            } else if (msg is LastHttpContent) {
                bodyOpen = false
                cancel()
            }
            lastRead = ctx.executor().ticker().nanoTime()
            ctx.fireChannelRead(msg)
        }

        override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
            val answered = bodyOpen && ends(msg)
            ctx.write(msg, promise)
            if (answered && check == null) {
                schedule(ctx, timeout.inWholeNanoseconds)
            }
        }

        override fun channelInactive(ctx: ChannelHandlerContext) {
            cancel()
            ctx.fireChannelInactive()
        }

        private fun schedule(ctx: ChannelHandlerContext, delayNanos: Long) {
            check = ctx.executor().schedule({ stalled(ctx) }, delayNanos, TimeUnit.NANOSECONDS)
        }

        /** The check, which runs only while the body is open: its end, or the next request, cancels it. */
        private fun stalled(ctx: ChannelHandlerContext) {
            check = null
            val left = timeout.inWholeNanoseconds - (ctx.executor().ticker().nanoTime() - lastRead)
            if (left > 0) {
                schedule(ctx, left)
                return
            }
            bodyOpen = false
            ctx.close()
        }

        private fun cancel() {
            check?.cancel(false)
            check = null
        }
    }
}
