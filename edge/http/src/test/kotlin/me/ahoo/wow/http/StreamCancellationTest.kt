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

package me.ahoo.wow.http

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.kernel.CommandType
import me.ahoo.wow.query.QueryCaller
import me.ahoo.wow.query.QueryGateway
import me.ahoo.wow.query.backend.PositionedRecord
import me.ahoo.wow.query.json.QueryJson
import me.ahoo.wow.runtime.admission.CommandInput
import me.ahoo.wow.runtime.admission.CommandTarget
import me.ahoo.wow.runtime.admission.CommandTransformer
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToFlux
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.RouterFunctions
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.netty.http.server.HttpServer
import tools.jackson.databind.node.ObjectNode
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

/**
 * A client that leaves a server-sent stream, before or after its first event, cancels what produces the stream (review
 * A3-5, A3-7): the query's backend read, and the command's admission and wait, over a real Reactor Netty server.
 */
class StreamCancellationTest {
    /** The end of the backend's read of a streamed list: its cause, a cancellation when the client left. */
    private val listEnded = CompletableDeferred<Throwable?>()

    private fun queryNode(rows: Flow<ObjectNode>): QueryNode = QueryNode(
        snapshots = { delegate ->
            object : QueryGateway by delegate {
                override fun stream(
                    query: ListQuery,
                    caller: QueryCaller,
                    after: String?,
                    chunkSize: Int,
                ): Flow<PositionedRecord> = rows.map { PositionedRecord(it, it["id"].asString()) }
                    .onCompletion { listEnded.complete(it) }
            }
        },
    )

    private fun row(id: String): ObjectNode = QueryJson.mapper.createObjectNode().put("id", id)

    private fun <T> serving(routes: RouterFunction<ServerResponse>, block: (WebClient) -> T): T {
        val adapter = ReactorHttpHandlerAdapter(RouterFunctions.toHttpHandler(routes))
        val server = HttpServer.create().host("127.0.0.1").port(0).handle(adapter).bindNow()
        try {
            return block(WebClient.create("http://127.0.0.1:${server.port()}"))
        } finally {
            server.disposeNow()
        }
    }

    private fun WebClient.stream(path: String, body: String) = post().uri(path)
        .header("X-User", "alice")
        .contentType(MediaType.APPLICATION_JSON)
        .accept(MediaType.TEXT_EVENT_STREAM)
        .bodyValue(body)
        .retrieve()
        .bodyToFlux<String>()

    private fun ended(deferred: CompletableDeferred<Throwable?>): Throwable? =
        runBlocking { withTimeout(10.seconds) { deferred.await() } }

    @Test
    fun `a client that leaves a streamed query before its first row cancels the backend's read`() {
        val node = queryNode(
            flow {
                delay(30.seconds)
                emit(row("late"))
            },
        )
        serving(node.http.routes) { client ->
            val left = runCatching {
                client.stream("/shop/cart/snapshot/query", """{"kind":"list","filter":{"op":"match-all"}}""")
                    .timeout(Duration.ofMillis(300)).blockFirst()
            }
            left.isFailure.assert().isTrue()
            ended(listEnded).assert().isInstanceOf(CancellationException::class.java)
        }
    }

    @Test
    fun `a client that leaves a streamed query after its first row cancels the backend's read`() {
        val node = queryNode(
            flow {
                emit(row("first"))
                delay(30.seconds)
                emit(row("late"))
            },
        )
        serving(node.http.routes) { client ->
            val first = client.stream("/shop/cart/snapshot/query", """{"kind":"list","filter":{"op":"match-all"}}""")
                .take(1).blockFirst(Duration.ofSeconds(10))
            first.assert().contains("first")
            ended(listEnded).assert().isInstanceOf(CancellationException::class.java)
        }
    }

    @Test
    fun `a client that leaves a command stream before its first result cancels the command's admission`() {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Throwable?>()
        val slow = object : CommandTransformer {
            override val input: CommandType = CommandType("$CONTEXT.cart.AddItem", AddItem::class.java)

            override suspend fun transform(input: CommandInput): CommandTarget {
                started.complete(Unit)
                try {
                    delay(30.seconds)
                } catch (cancellation: CancellationException) {
                    cancelled.complete(cancellation)
                    throw cancellation
                }
                return CommandTarget(this.input, input.aggregateId, input.body)
            }
        }
        val node = HttpNode(transformers = listOf(slow))
        serving(node.routes) { client ->
            // The client leaves once the admission is under way, not after a fixed time: a cold server on a busy
            // machine may not have reached the admission yet, and a client that leaves before it never starts one.
            val received = CopyOnWriteArrayList<String>()
            val subscription = client.stream("/shop/cart/c-1/add-item", """{"sku":"apple"}""")
                .subscribe({ received += it }, {})
            try {
                runBlocking { withTimeout(10.seconds) { started.await() } }
            } finally {
                subscription.dispose()
            }
            ended(cancelled).assert().isInstanceOf(CancellationException::class.java)
            received.assert().isEmpty()
        }
        node.commands.assert().isEmpty()
    }

    @Test
    fun `a client that leaves a command stream before its admission starts never starts it`() {
        val authenticating = CompletableDeferred<Unit>()
        val authenticationEnded = CompletableDeferred<Throwable?>()
        val held = CallerResolver { _ ->
            authenticating.complete(Unit)
            try {
                awaitCancellation()
            } catch (cancellation: CancellationException) {
                authenticationEnded.complete(cancellation)
                throw cancellation
            }
        }
        val transformed = CompletableDeferred<Unit>()
        val transformer = object : CommandTransformer {
            override val input: CommandType = CommandType("$CONTEXT.cart.AddItem", AddItem::class.java)

            override suspend fun transform(input: CommandInput): CommandTarget {
                transformed.complete(Unit)
                return CommandTarget(this.input, input.aggregateId, input.body)
            }
        }
        val node = HttpNode(transformers = listOf(transformer), callers = held)
        serving(node.routes) { client ->
            val subscription = client.stream("/shop/cart/c-1/add-item", """{"sku":"apple"}""").subscribe({}, {})
            try {
                runBlocking { withTimeout(10.seconds) { authenticating.await() } }
            } finally {
                subscription.dispose()
            }
            ended(authenticationEnded).assert().isInstanceOf(CancellationException::class.java)
        }
        transformed.isCompleted.assert().isFalse()
        node.commands.assert().isEmpty()
    }
}
