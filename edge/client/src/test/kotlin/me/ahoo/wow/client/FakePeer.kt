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

package me.ahoo.wow.client

import com.sun.net.httpserver.HttpServer
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.ErrorInfo
import me.ahoo.wow.api.result.FunctionInfo
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.spi.json.WowJson
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

/** A request the fake peer took: its method, path with query, headers and body. */
data class Taken(val method: String, val path: String, val headers: Map<String, String>, val body: String)

/**
 * One answer of the fake peer: its status, its headers and its body, written in [chunks] (each flushed apart, the
 * stream's pieces) when given.
 */
class Reply(
    val status: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray = ByteArray(0),
    val chunks: List<String>? = null,
    /** Whether the connection is cut after the chunks, without ending the response. */
    val abort: Boolean = false,
) {
    companion object {
        fun json(value: Any, status: Int = 200): Reply =
            Reply(status, mapOf("Content-Type" to "application/json"), WowJson.mapper.writeValueAsBytes(value))

        fun problem(status: Int, members: Map<String, Any?>, retryAfter: String? = null): Reply = Reply(
            status,
            buildMap {
                put("Content-Type", "application/problem+json")
                retryAfter?.let { put("Retry-After", it) }
            },
            WowJson.mapper.writeValueAsBytes(members),
        )

        fun events(vararg chunks: String): Reply =
            Reply(200, mapOf("Content-Type" to "text/event-stream"), chunks = chunks.toList())

        fun cut(vararg chunks: String): Reply =
            Reply(200, mapOf("Content-Type" to "text/event-stream"), chunks = chunks.toList(), abort = true)
    }
}

/**
 * A peer's service on a loopback JDK [HttpServer]: it records every request it takes and answers each with the next
 * [Reply] queued, the last one again once the queue runs out.
 */
class FakePeer : AutoCloseable {
    val taken = CopyOnWriteArrayList<Taken>()
    private val replies = ConcurrentLinkedQueue<Reply>()

    @Volatile
    private var last: Reply = Reply(404)

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            exchange.use {
                taken += Taken(
                    exchange.requestMethod,
                    exchange.requestURI.rawPath + (exchange.requestURI.rawQuery?.let { "?$it" } ?: ""),
                    exchange.requestHeaders.mapValuesTo(sortedMapOf(String.CASE_INSENSITIVE_ORDER)) {
                        it.value.joinToString()
                    },
                    exchange.requestBody.readAllBytes().decodeToString(),
                )
                val reply = replies.poll()?.also { last = it } ?: last
                reply.headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
                val chunks = reply.chunks
                if (chunks != null) {
                    // A cut response promises more than it writes: the server closes the connection short of it.
                    val length = if (reply.abort) chunks.sumOf { it.toByteArray().size } + 1024L else 0L
                    exchange.sendResponseHeaders(reply.status, length)
                    chunks.forEach { chunk ->
                        exchange.responseBody.write(chunk.toByteArray())
                        exchange.responseBody.flush()
                    }
                } else {
                    val body = reply.body
                    exchange.sendResponseHeaders(reply.status, if (body.isEmpty()) -1 else body.size.toLong())
                    if (body.isNotEmpty()) exchange.responseBody.write(body)
                }
            }
        }
        start()
    }

    val base: URI = URI.create("http://127.0.0.1:${server.address.port}")

    /** Queues [replies], answered in order. */
    fun reply(vararg replies: Reply): FakePeer = apply { this.replies += replies }

    override fun close() {
        server.stop(0)
    }
}

/**
 * Runs [block] with a loopback port no server listens on: a socket is bound to it without listening, so a connection
 * to it is refused, and no other process can take the port while [block] runs.
 */
inline fun <T> withRefusingPort(block: (port: Int) -> T): T = Socket().use { socket ->
    socket.bind(InetSocketAddress("127.0.0.1", 0))
    block(socket.localPort)
}

val BOX = AggregateType("mail", "box")

/** A result of the command [commandId] to [ref], at [hop]. */
fun resultOf(
    ref: AggregateRef,
    status: CommandResult.Status = CommandResult.Status.SUCCEEDED,
    error: ErrorInfo? = null,
    hop: Hop = Hop.committed("mail"),
    commandId: String = "c-1",
    requestId: String = "r-1",
): CommandResult = CommandResult(
    commandId = commandId,
    requestId = requestId,
    correlationId = commandId,
    ref = ref,
    hop = hop,
    function = FunctionInfo(FunctionKind.COMMAND, "mail", "box", "DeliverItems"),
    status = status,
    version = 1,
    error = error,
    signalTime = 1L,
)

/** The [T] [block] throws, failing when it throws none. */
inline fun <reified T : Throwable> caught(block: () -> Unit): T {
    try {
        block()
    } catch (thrown: Throwable) {
        if (thrown is T) return thrown
        throw AssertionError(
            "Expected a ${T::class.simpleName}, but ${thrown.javaClass.simpleName} was thrown.",
            thrown
        )
    }
    throw AssertionError("Expected a ${T::class.simpleName}, but nothing was thrown.")
}
