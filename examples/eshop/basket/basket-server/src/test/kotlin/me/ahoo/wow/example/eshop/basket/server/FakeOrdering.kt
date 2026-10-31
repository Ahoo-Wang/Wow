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

package me.ahoo.wow.example.eshop.basket.server

import com.sun.net.httpserver.HttpServer
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.FunctionInfo
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.wire.WowHeaders
import me.ahoo.wow.example.eshop.ordering.api.order.EshopOrderingContract
import me.ahoo.wow.runtime.admission.CallContext
import me.ahoo.wow.runtime.admission.CallHeaders
import me.ahoo.wow.spi.json.WowJson
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The ordering service's command gateway as the checkout saga's synchronous call sees it (decision D193): it records
 * each call and answers that the order was committed at version 1, as [NUMBER] when the call named no order, as the
 * ordering numbers a new one.
 */
class FakeOrdering : AutoCloseable {
    /** A call the gateway took: its [path], the [service] it named, its [call] context and its [body]. */
    data class Call(val path: String, val service: String?, val call: CallContext, val body: ByteArray)

    private val calls = LinkedBlockingQueue<Call>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            exchange.use {
                val call = CallHeaders.parse(exchange.requestHeaders.getFirst(WowHeaders.CALL))
                calls += Call(
                    exchange.requestURI.path,
                    exchange.requestHeaders.getFirst(WowHeaders.SERVICE),
                    call,
                    exchange.requestBody.readAllBytes(),
                )
                val result = CommandResult.succeeded(
                    commandId = call.commandId,
                    requestId = exchange.requestHeaders.getFirst(WowHeaders.REQUEST_ID),
                    correlationId = call.causation.correlationId,
                    ref = ORDER.ref(call.tenantId, idOf(exchange.requestURI.path)),
                    hop = Hop.committed(ORDER.context),
                    function = FunctionInfo.of(FunctionKind.COMMAND, ORDER.context, ORDER.name, "SubmitOrder"),
                    version = 1,
                    signalTime = System.currentTimeMillis(),
                )
                val body = WowJson.mapper.writeValueAsBytes(result)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(OK, body.size.toLong())
                exchange.responseBody.write(body)
            }
        }
        start()
    }

    /** The gateway's base URL. */
    val url: String = "http://127.0.0.1:${server.address.port}"

    /** The next call to [path], waited for. */
    fun called(path: String, timeout: Duration = Duration.ofSeconds(30)): Call {
        val until = System.nanoTime() + timeout.toNanos()
        while (true) {
            val remaining = until - System.nanoTime()
            check(remaining > 0) { "No call to $path." }
            val call = calls.poll(remaining, TimeUnit.NANOSECONDS) ?: continue
            if (call.path == path) {
                return call
            }
        }
    }

    override fun close() {
        server.stop(0)
    }

    companion object {
        /** The number the gateway gives an order created without one. */
        const val NUMBER = "SO-261031-1"
        private const val OK = 200

        /** The segments of `eshop-ordering/order/submit-order`, a route that names no aggregate id. */
        private const val ROUTE_WITHOUT_ID = 3
        private val ORDER = EshopOrderingContract.Order.TYPE

        /** The order a route names, `/eshop-ordering/order/{id}/submit-order`, else [NUMBER]. */
        private fun idOf(path: String): String {
            val segments = path.trim('/').split('/')
            return if (segments.size > ROUTE_WITHOUT_ID) segments[2] else NUMBER
        }
    }
}
