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

package me.ahoo.wow.elasticsearch

import co.elastic.clients.json.JsonpMapper
import co.elastic.clients.transport.ElasticsearchTransportBase
import co.elastic.clients.transport.TransportOptions
import co.elastic.clients.transport.http.TransportHttpClient
import co.elastic.clients.transport.rest5_client.Rest5ClientHttpClient
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client
import me.ahoo.wow.api.annotation.InternalWowApi
import java.net.SocketTimeoutException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How long a store's callers wait for Elasticsearch's answer, as MongoDB's and PostgreSQL's stores bound their calls
 * (`operation-timeout`): without it, a caller of a server that stopped answering waits for as long as the application's
 * `Rest5Client` lets its request run, and a command's snapshot load, a snapshot subscription's write and a query with it.
 * Every request a store sends has [OPERATION] unless the store sets another (`wow.stores.<name>.operation-timeout`) or
 * the client's own timeouts end it first. The bound is the caller's wait: the request and its connection are the
 * client's ([BoundedHttpClient]).
 */
@InternalWowApi
object ElasticsearchTimeouts {
    /** The default timeout of a store's requests. */
    val OPERATION: Duration = 30.seconds
}

/**
 * The transport of a store's client over [restClient]: each request bounded by [timeout] ([BoundedHttpClient]), the
 * rest as `Rest5ClientTransport`'s.
 */
internal class BoundedTransport(restClient: Rest5Client, mapper: JsonpMapper, timeout: Duration) :
    ElasticsearchTransportBase(BoundedHttpClient(Rest5ClientHttpClient(restClient), timeout), null, mapper)

/**
 * [delegate], each request's caller answered within [timeout]: a request that has no answer by then fails for its
 * caller as a [SocketTimeoutException], an I/O failure the store reads as transient (`ElasticsearchFailures`), and the
 * same when its caller gives up first. The bound is the caller's wait, not the connection's: the request runs on, and
 * its connection is freed when the server answers or resets it, or at the client's own timeout; an answer that comes
 * after the caller's failure is released at once.
 *
 * Nothing is cancelled. HttpClient 5 keeps a reused connection whose exchange is cancelled, so a cancel frees nothing
 * in the usual case, and it re-runs a request cancelled before its bytes are written, on a new connection that nothing
 * then releases. The store's calls are all asynchronous; a synchronous one is the client's own.
 */
internal class BoundedHttpClient(private val delegate: TransportHttpClient, private val timeout: Duration) :
    TransportHttpClient {
    init {
        require(timeout.isPositive()) { "An Elasticsearch store's operation timeout is positive." }
    }

    override fun createOptions(options: TransportOptions?): TransportOptions = delegate.createOptions(options)

    override fun performRequest(
        endpointId: String,
        node: TransportHttpClient.Node?,
        request: TransportHttpClient.Request,
        options: TransportOptions?,
    ): TransportHttpClient.Response = delegate.performRequest(endpointId, node, request, options)

    override fun performRequestAsync(
        endpointId: String,
        node: TransportHttpClient.Node?,
        request: TransportHttpClient.Request,
        options: TransportOptions?,
    ): CompletableFuture<TransportHttpClient.Response> {
        val sent = delegate.performRequestAsync(endpointId, node, request, options)
        val answered = CompletableFuture<TransportHttpClient.Response>()
        val deadline = deadlines.schedule(
            {
                answered.completeExceptionally(
                    SocketTimeoutException("Elasticsearch gave no answer to $endpointId within $timeout."),
                )
            },
            timeout.inWholeMilliseconds,
            TimeUnit.MILLISECONDS,
        )
        sent.whenComplete { response, failure ->
            deadline.cancel(false)
            when {
                failure != null -> answered.completeExceptionally(failure)
                !answered.complete(response) -> response.close()
            }
        }
        return answered
    }

    override fun close() = delegate.close()

    private companion object {
        /** One daemon thread fails the requests whose time is up; a request answered in time removes its deadline. */
        val deadlines: ScheduledThreadPoolExecutor by lazy {
            ScheduledThreadPoolExecutor(1) { task ->
                Thread(task, "wow-elasticsearch-deadlines").apply { isDaemon = true }
            }.apply { removeOnCancelPolicy = true }
        }
    }
}
