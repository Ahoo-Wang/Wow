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
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.util.ReferenceCountUtil
import me.ahoo.wow.api.annotation.InternalWowApi
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import reactor.netty.NettyPipeline
import reactor.netty.http.HttpDecoderSpec
import reactor.netty.http.server.HttpServer

/**
 * The server's own refusals as problems (#6956): a request Reactor Netty cannot decode, a request line or a header
 * block past its limit or malformed HTTP, is answered by it before any route runs, with its status (414, 431 or 400)
 * and an empty body. This handler gives that answer a problem's body, status alone as the 413 of a body past the
 * codecs' limit is ([tooLargeProblem]), its detail naming the limit and the key that sets it ([limits]).
 *
 * It recognises the refusal by its cause, not its shape. Sitting before Reactor Netty's traffic handler, it sees each
 * request on its way in; while the traffic handler handles one whose decoding failed, it rewrites the response written
 * then, which is Reactor Netty's answer to it: Reactor Netty writes it at once, on the channel's event loop
 * (`HttpServerOperations.sendDecodingFailures`). A route's response is written outside that, so it is never rewritten,
 * however it looks.
 */
@InternalWowApi
class ServerRefusals(private val limits: Limits) : ChannelDuplexHandler() {
    /** How the server's limits read in a refusal's detail: each its size and the key that sets it, if any. */
    @InternalWowApi
    class Limits(val requestLine: String, val headers: String) {
        @InternalWowApi
        companion object {
            /** Reactor Netty's own limits, which no key of the host sets: the internal port's. */
            val NETTY: Limits = Limits(
                "${HttpDecoderSpec.DEFAULT_MAX_INITIAL_LINE_LENGTH} bytes",
                "${HttpDecoderSpec.DEFAULT_MAX_HEADER_SIZE} bytes",
            )
        }
    }

    /** Whether the traffic handler is handling a request whose decoding failed: its answer is Reactor Netty's. */
    private var refusing = false

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if (msg !is DecoderResultProvider || !msg.decoderResult().isFailure) {
            ctx.fireChannelRead(msg)
            return
        }
        refusing = true
        try {
            ctx.fireChannelRead(msg)
        } finally {
            refusing = false
        }
    }

    override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
        if (!refusing || msg !is FullHttpResponse) {
            ctx.write(msg, promise)
            return
        }
        refusing = false
        val response = msg
        val status = HttpStatus.valueOf(response.status().code())
        val body = Problem.ofStatus(status, detail(status)).body()
        val problem = response.replace(Unpooled.wrappedBuffer(body))
        ReferenceCountUtil.release(response)
        problem.headers()
            .set(HttpHeaderNames.CONTENT_TYPE, MediaType.APPLICATION_PROBLEM_JSON_VALUE)
            .setInt(HttpHeaderNames.CONTENT_LENGTH, body.size)
        ctx.write(problem, promise)
    }

    private fun detail(status: HttpStatus): String = when (status) {
        HttpStatus.URI_TOO_LONG -> "The request line is longer than this server takes: ${limits.requestLine}."
        HttpStatus.REQUEST_HEADER_FIELDS_TOO_LARGE ->
            "The request's headers are larger than this server takes: ${limits.headers}."
        else -> "The request is not HTTP this server can read."
    }

    @InternalWowApi
    companion object {
        /** The name of the handler in a channel's pipeline. */
        const val NAME: String = "wow.serverRefusals"

        /**
         * Puts a [ServerRefusals] of [limits] before the traffic handler of [channel]'s HTTP/1.1 pipeline; nothing on a
         * channel without one.
         */
        fun install(channel: Channel, limits: Limits) {
            val pipeline = channel.pipeline()
            if (pipeline.get(NettyPipeline.HttpTrafficHandler) != null && pipeline.get(NAME) == null) {
                pipeline.addBefore(NettyPipeline.HttpTrafficHandler, NAME, ServerRefusals(limits))
            }
        }

        /** [server], whose own refusals are problems naming [limits]. */
        fun of(server: HttpServer, limits: Limits): HttpServer =
            server.doOnChannelInit { _, channel, _ -> install(channel, limits) }
    }
}
