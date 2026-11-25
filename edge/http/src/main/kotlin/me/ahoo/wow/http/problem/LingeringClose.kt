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

import io.netty.channel.Channel
import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPromise
import io.netty.channel.socket.DuplexChannel
import io.netty.handler.codec.DecoderResultProvider
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponse
import io.netty.handler.codec.http.HttpUtil
import io.netty.handler.codec.http.LastHttpContent
import io.netty.util.ReferenceCountUtil
import io.netty.util.concurrent.ScheduledFuture
import me.ahoo.wow.api.annotation.InternalWowApi
import reactor.netty.NettyPipeline
import reactor.netty.http.server.HttpServer
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A lingering close (#7001): a connection that closes after an answer written before its request's body was read to
 * its end does not close at once. A server that closes with bytes of the body unread has its connection reset by a
 * Linux kernel, and the reset throws away the answer the client has not read yet: the 413 of a body past the limit,
 * sent with `Connection: close`, or a refusal of the server's own ([ServerRefusals]). So the handler shuts the output
 * down, the answer written, reads and discards what the client still sends, and closes when the client closes, or after
 * [linger], as nginx's `lingering_timeout` does.
 *
 * It lingers only then: a request answered after its body was read, or a connection kept alive (whose body Reactor
 * Netty reads to its end before the next request), closes or goes on as it did. A close asked again while it lingers,
 * as a server's dispose or the internal port's stop asks it, closes at once: a lingering connection holds no
 * shutdown. HTTP/2 is not covered: a stream reset after a complete response is that protocol's way.
 */
@InternalWowApi
class LingeringClose(private val linger: Duration = LINGER) : ChannelDuplexHandler() {
    /** Whether the current request's body may still be arriving: begun and not yet read to its end. */
    private var bodyOpen = false

    /** Whether an answer was written while [bodyOpen]. */
    private var answeredEarly = false

    /** The close this handler holds while it lingers, and when it gives up. */
    private var held: ChannelPromise? = null
    private var deadline: ScheduledFuture<*>? = null

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if (held != null) {
            ReferenceCountUtil.release(msg)
            ctx.read()
            return
        }
        if (msg is HttpRequest) {
            // A request the server could not decode may have its body behind it; one decoded whole has none left.
            val failed = (msg as DecoderResultProvider).decoderResult().isFailure
            bodyOpen = failed || msg !is LastHttpContent && hasBody(msg)
            answeredEarly = false
        } else if (msg is LastHttpContent) {
            bodyOpen = false
        }
        ctx.fireChannelRead(msg)
    }

    override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
        if (bodyOpen && isAnswer(msg)) {
            answeredEarly = true
        }
        ctx.write(msg, promise)
    }

    override fun close(ctx: ChannelHandlerContext, promise: ChannelPromise) {
        val pending = held
        if (pending != null) {
            // Asked again, by a dispose or a stop: the connection closes now.
            release()
            ctx.close(promise).addListener { pending.trySuccess() }
            return
        }
        val channel = ctx.channel()
        if (!lingers(channel)) {
            ctx.close(promise)
            return
        }
        held = promise
        ctx.flush()
        (channel as DuplexChannel).shutdownOutput()
        deadline = ctx.executor().schedule({ closeHeld(ctx) }, linger.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        channel.config().isAutoRead = true
        ctx.read()
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        // The client closed: what it sent is read, and the close this handler held is done.
        held?.let { pending ->
            release()
            pending.trySuccess()
        }
        ctx.fireChannelInactive()
    }

    /** Whether [msg] ends an answer: a response's last part that is no interim one, as `100 Continue` is. */
    private fun isAnswer(msg: Any): Boolean =
        msg is LastHttpContent && !(msg is HttpResponse && msg.status().code() < FINAL_STATUS)

    /** Whether [channel] lingers as it closes: answered early, its body still arriving, a socket that half-closes. */
    private fun lingers(channel: Channel): Boolean =
        answeredEarly && bodyOpen && channel is DuplexChannel && channel.isActive

    private fun hasBody(request: HttpRequest): Boolean =
        HttpUtil.getContentLength(request, 0L) > 0 || HttpUtil.isTransferEncodingChunked(request)

    private fun closeHeld(ctx: ChannelHandlerContext) {
        val pending = held ?: return
        release()
        ctx.close(pending)
    }

    private fun release() {
        held = null
        deadline?.cancel(false)
        deadline = null
    }

    @InternalWowApi
    companion object {
        /** How long a connection lingers at most, as nginx's `lingering_timeout`: not a key (#6973). */
        val LINGER: Duration = 5.seconds

        /** The first status that is no interim answer (`100 Continue` and the like are not the answer). */
        private const val FINAL_STATUS = 200

        /** The name of the handler in a channel's pipeline. */
        const val NAME: String = "wow.lingeringClose"

        /** Puts a [LingeringClose] before the traffic handler of [channel]'s HTTP/1.1 pipeline; nothing without one. */
        fun install(channel: Channel) {
            val pipeline = channel.pipeline()
            if (pipeline.get(NettyPipeline.HttpTrafficHandler) != null && pipeline.get(NAME) == null) {
                pipeline.addBefore(NettyPipeline.HttpTrafficHandler, NAME, LingeringClose())
            }
        }

        /** [server], whose connections linger after an answer written before the request's body was read. */
        fun of(server: HttpServer): HttpServer = server.doOnChannelInit { _, channel, _ -> install(channel) }
    }
}
