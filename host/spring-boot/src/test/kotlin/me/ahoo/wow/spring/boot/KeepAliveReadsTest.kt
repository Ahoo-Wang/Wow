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
import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPromise
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.http.HttpResponse
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.netty.NettyPipeline
import reactor.netty.http.server.HttpServer
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.concurrent.TimeUnit

class KeepAliveReadsTest {
    /**
     * Holds back the acknowledgement of every other response's write, as a response completed off the event loop
     * has it acknowledged late: the client has the response and sends its next request, which arrives before the
     * request it follows has ended.
     */
    private class LateAcknowledgements : ChannelDuplexHandler() {
        private var responses = 0

        override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
            if (msg !is HttpResponse || responses++ % 2 == 1) {
                ctx.write(msg, promise)
                return
            }
            ctx.write(msg).addListener { written ->
                ctx.executor().schedule(
                    { if (written.isSuccess) promise.trySuccess() else promise.tryFailure(written.cause()) },
                    LATE_MILLIS,
                    TimeUnit.MILLISECONDS,
                )
            }
        }
    }

    private fun lateAcknowledging(channel: Channel) {
        channel.pipeline().addBefore(NettyPipeline.HttpTrafficHandler, "lateAcknowledgements", LateAcknowledgements())
    }

    /** Sends [REQUESTS] GETs one after another on one connection; returns how many were answered in time. */
    private fun answered(server: HttpServer): Int {
        val bound = server.host("127.0.0.1").port(0).handle { _, response -> response.sendString(Mono.just("ok")) }
            .bindNow()
        val client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
        try {
            repeat(REQUESTS) { index ->
                val request = HttpRequest.newBuilder(URI("http://127.0.0.1:${bound.port()}/$index"))
                    .timeout(Duration.ofSeconds(2))
                    .build()
                try {
                    client.send(request, BodyHandlers.ofString()).body().assert().isEqualTo("ok")
                } catch (_: HttpTimeoutException) {
                    return index
                }
            }
            return REQUESTS
        } finally {
            client.shutdownNow()
            bound.disposeNow()
        }
    }

    @Test
    fun `a keep-alive request answered from Reactor Netty's buffer should be followed by a read`() {
        answered(
            HttpServer.create().doOnChannelInit { _, channel, _ ->
                lateAcknowledging(channel)
                KeepAliveReads.install(channel)
            }
        ).assert().isEqualTo(REQUESTS)
    }

    @Test
    fun `without the handler the request after it should stay unread while Reactor Netty has the defect`() {
        // The defect this handler works around (reactor/reactor-netty@a115965). Once the BOM brings a Reactor Netty
        // that fixes it, this fails: drop KeepAliveReads and this test then (docs/todo.md).
        answered(HttpServer.create().doOnChannelInit { _, channel, _ -> lateAcknowledging(channel) })
            .assert().isLessThan(REQUESTS)
    }

    @Test
    fun `the handler should be installed once, and only on an HTTP 1-1 pipeline`() {
        val http = EmbeddedChannel()
        http.pipeline().addLast(NettyPipeline.HttpTrafficHandler, ChannelDuplexHandler())
        KeepAliveReads.install(http)
        KeepAliveReads.install(http)
        http.pipeline().names().filter { it == KeepAliveReads.NAME }.assert().hasSize(1)

        val other = EmbeddedChannel()
        KeepAliveReads.install(other)
        other.pipeline().get(KeepAliveReads.NAME).assert().isNull()
    }

    private companion object {
        const val REQUESTS = 6
        const val LATE_MILLIS = 200L
    }
}
