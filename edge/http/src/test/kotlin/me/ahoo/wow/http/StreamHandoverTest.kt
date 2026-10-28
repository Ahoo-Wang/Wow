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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.wire.WowHeaders
import me.ahoo.wow.http.caller.Authenticator
import me.ahoo.wow.http.command.CommandHandler
import me.ahoo.wow.http.command.CommandTarget
import me.ahoo.wow.http.command.RequestHeaders
import me.ahoo.wow.http.problem.ErrorStatuses
import me.ahoo.wow.query.QueryCaller
import me.ahoo.wow.query.QueryGateway
import me.ahoo.wow.query.backend.PositionedRecord
import me.ahoo.wow.query.json.QueryJson
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.codec.HttpMessageWriter
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.reactive.function.UnsupportedMediaTypeException
import org.springframework.web.reactive.function.server.HandlerStrategies
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.bodyAndAwait
import org.springframework.web.reactive.result.view.ViewResolver
import reactor.core.publisher.Mono
import tools.jackson.databind.node.ObjectNode
import java.net.URI
import java.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A server-sent stream's producer runs outside the request and hands its rest to the response's body: when that body
 * does not read it (the request cancelled once the handler had its response, or a write cancelled while the body is
 * held at its first event, before it reads the channel), the producer is cancelled all the same, and with it what it
 * reads, instead of waiting on its next send for ever.
 */
class StreamHandoverTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The end of what the producer reads: its cause, a cancellation when the producer was cancelled. */
    private val sourceEnded = CompletableDeferred<Throwable?>()

    @AfterEach
    fun close() {
        scope.cancel()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun producer(): ReceiveChannel<Int> {
        val source = flow {
            emit(1)
            emit(2)
            awaitCancellation()
        }.onCompletion { sourceEnded.complete(it) }
        return scope.produce { source.collect { send(it) } }
    }

    private fun ended(deferred: CompletableDeferred<Throwable?>): Throwable? =
        runBlocking { withTimeout(10.seconds) { deferred.await() } }

    @Test
    fun `a request cancelled after the handover cancels the producer`() {
        runBlocking {
            val channel = producer()
            channel.receiveFirst().assert().isEqualTo(1)
            val handed = CompletableDeferred<ServerResponse>()
            val request = launch {
                handed.complete(channel.events())
                awaitCancellation()
            }
            handed.await()
            sourceEnded.isCompleted.assert().isFalse()
            request.cancel()
        }
        ended(sourceEnded).assert().isInstanceOf(CancellationException::class.java)
    }

    @Test
    fun `a response that fails to build cancels the producer`() {
        val channel = producer()
        assertThrownBy<IllegalStateException> {
            runBlocking {
                channel.receiveFirst()
                channel.handingOver(flow { emitAll(channel) }) { error("No response.") }
            }
        }
        ended(sourceEnded).assert().isInstanceOf(CancellationException::class.java)
    }

    @Test
    fun `a write that ends before its body reads the channel cancels the producer`() {
        val channel = producer()
        val response = runBlocking {
            channel.receiveFirst()
            channel.events()
        }
        sourceEnded.isCompleted.assert().isFalse()
        response.abandon()
        ended(sourceEnded).assert().isInstanceOf(CancellationException::class.java)
    }

    @Test
    fun `a write that fails before it starts the body cancels the producer`() {
        val channel = producer()
        val response = runBlocking {
            channel.receiveFirst()
            channel.events()
        }
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/"))
        // No writer of server-sent events: the write fails before it subscribes the body.
        assertThrownBy<UnsupportedMediaTypeException> {
            response.writeTo(exchange, NO_WRITERS).block(Duration.ofSeconds(10))
        }
        ended(sourceEnded).assert().isInstanceOf(CancellationException::class.java)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `a body read to its end is the producer's whole stream`() {
        val channel = scope.produce {
            send(1)
            send(2)
        }
        val response = runBlocking {
            channel.receiveFirst().assert().isEqualTo(1)
            channel.events()
        }
        response.statusCode().assert().isEqualTo(HttpStatus.OK)
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/"))
        response.writeTo(exchange, WRITE_CONTEXT).block(Duration.ofSeconds(10))
        exchange.response.bodyAsString.block(Duration.ofSeconds(10))!!.assert().contains("data:1", "data:2")
        channel.isClosedForReceive.assert().isTrue()
    }

    @Test
    fun `a command stream whose request is cancelled after the handover cancels the command's wait`() {
        val handler = commandHandler()
        runBlocking {
            val handed = CompletableDeferred<ServerResponse>()
            val request = launch {
                handed.complete(handler.handle(commandRequest(), ADD_ITEM))
                awaitCancellation()
            }
            handed.await().statusCode().assert().isEqualTo(HttpStatus.OK)
            val producer = scope.coroutineContext.job.children.single()
            producer.isActive.assert().isTrue()
            request.cancel()
            withTimeout(10.seconds) { producer.join() }
            producer.isCancelled.assert().isTrue()
        }
    }

    @Test
    fun `a command stream that leaves before its body reads the channel cancels the command's wait`() {
        val handler = commandHandler()
        val response = runBlocking { handler.handle(commandRequest(), ADD_ITEM) }
        val producer = scope.coroutineContext.job.children.single()
        producer.isActive.assert().isTrue()
        response.abandon()
        runBlocking { withTimeout(10.seconds) { producer.join() } }
        producer.isCancelled.assert().isTrue()
    }

    @Test
    fun `a query stream that leaves before its body reads the channel cancels the backend's read`() {
        val listEnded = CompletableDeferred<Throwable?>()
        val rows: Flow<ObjectNode> = flow {
            emit(QueryJson.mapper.createObjectNode().put("id", "first"))
            delay(30.seconds)
        }
        val node = QueryNode(
            snapshots = { delegate ->
                object : QueryGateway by delegate {
                    override fun stream(
                        query: ListQuery,
                        caller: QueryCaller,
                        after: String?,
                        chunkSize: Int,
                    ): Flow<PositionedRecord> = rows.map { PositionedRecord(it, "p") }
                        .onCompletion { listEnded.complete(it) }
                }
            },
        )
        val exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/shop/cart/snapshot/query")
                .header("X-User", "alice")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .body("""{"kind":"list","filter":{"op":"MATCH_ALL"}}"""),
        )
        val request = ServerRequest.create(exchange, HandlerStrategies.withDefaults().messageReaders())
        val response = node.http.routes.route(request).flatMap { it.handle(request) }
            .block(Duration.ofSeconds(10))!!
        response.statusCode().assert().isEqualTo(HttpStatus.OK)
        listEnded.isCompleted.assert().isFalse()
        response.abandon()
        ended(listEnded).assert().isInstanceOf(CancellationException::class.java)
    }

    private fun commandHandler(): CommandHandler {
        val node = HttpNode()
        val statuses = ErrorStatuses(cartManifest())
        return CommandHandler(
            gateway = node.gateway,
            authenticator = Authenticator(headerCallers, statuses),
            headers = RequestHeaders(statuses),
            statuses = statuses,
            options = WowHttpOptions(),
            scope = scope,
        )
    }

    /** A streamed AddItem whose wait, past its first hop, waits on a handler that never answers. */
    private fun commandRequest(): ServerRequest = MockServerRequest.builder()
        .method(HttpMethod.POST)
        .uri(URI.create("/shop/cart/c-1/add-item"))
        .pathVariable(CommandHandler.ID, "c-1")
        .header("X-User", "alice")
        .header(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE)
        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
        .header(WowHeaders.WAIT, "committed > handled(audit)")
        .header(WowHeaders.WAIT_TIMEOUT, "20s")
        .body(Mono.just("""{"sku":"apple"}""".toByteArray()))

    /** The channel's rest as server-sent events, after the first element, as the handlers answer it. */
    private suspend fun ReceiveChannel<Int>.events(): ServerResponse = handingOver(
        flow {
            emit(1)
            emitAll(this@events)
        },
    ) { ServerResponse.ok().contentType(MediaType.TEXT_EVENT_STREAM).bodyAndAwait(it) }

    /** Writes this response to a client that leaves as it starts: its body held at its first event, then cancelled. */
    private fun ServerResponse.abandon() {
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/"))
        exchange.response.setWriteHandler { Mono.never() }
        writeTo(exchange, WRITE_CONTEXT).subscribe().dispose()
    }

    private companion object {
        val ADD_ITEM = CommandTarget(CONTEXT, "cart", "AddItem", byId = true)

        val WRITE_CONTEXT = object : ServerResponse.Context {
            override fun messageWriters() = HandlerStrategies.withDefaults().messageWriters()

            override fun viewResolvers(): List<ViewResolver> = emptyList()
        }

        val NO_WRITERS = object : ServerResponse.Context {
            override fun messageWriters(): List<HttpMessageWriter<*>> = emptyList()

            override fun viewResolvers(): List<ViewResolver> = emptyList()
        }
    }
}
