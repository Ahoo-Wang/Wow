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

package me.ahoo.wow.spring.boot

import io.netty.channel.Channel
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.handler.codec.http.LastHttpContent
import reactor.netty.NettyPipeline
import reactor.netty.channel.ChannelOperations

/**
 * Keeps reading an HTTP/1.1 keep-alive connection of the host's servers, the public one and the [InternalServer].
 *
 * Reactor Netty 1.3.7 reads a connection only on demand. A request that arrives before the previous one has ended
 * (its response written, but the write not yet acknowledged, which happens when the response is completed off the
 * event loop, as a command's wait is) is buffered with its last content. When the request is answered from the buffer
 * at once, the last content is delivered after the request has ended, and nothing asks for the next read: the next
 * request stays unread in the socket until the client gives up and closes the connection. Reactor Netty 1.3.8 fixes
 * this (reactor/reactor-netty@a115965); until then this handler, placed after Reactor Netty's HTTP traffic handler,
 * asks for the read itself when the last content of a request arrives with no request in progress.
 */
@ChannelHandler.Sharable
internal object KeepAliveReads : ChannelInboundHandlerAdapter() {
    /** The handler's name in the pipeline. */
    const val NAME: String = "wow.keepAliveReads"

    /** Adds the handler to [channel] after Reactor Netty's HTTP/1.1 traffic handler, when the channel has one. */
    fun install(channel: Channel) {
        val pipeline = channel.pipeline()
        if (pipeline.get(NettyPipeline.HttpTrafficHandler) != null && pipeline.get(NAME) == null) {
            pipeline.addAfter(NettyPipeline.HttpTrafficHandler, NAME, this)
        }
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        val ended = msg is LastHttpContent && ChannelOperations.get(ctx.channel()) == null
        ctx.fireChannelRead(msg)
        if (ended && ctx.channel().isActive) {
            ctx.read()
        }
    }
}
