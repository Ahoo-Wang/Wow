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

import me.ahoo.wow.tck.container.ElasticsearchTestFixture
import org.apache.hc.core5.http.protocol.HttpCoreContext
import org.springframework.data.elasticsearch.client.ClientConfiguration
import org.springframework.data.elasticsearch.client.elc.ElasticsearchClients
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import org.springframework.data.elasticsearch.client.elc.rest5_client.Rest5Clients
import org.springframework.data.elasticsearch.support.HttpHeaders
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object ReactiveElasticsearchClients {
    private const val CLIENT_RESOURCE_KEY = "reactive-elasticsearch-client"

    fun createReactiveElasticsearchClient(
        elasticsearch: ElasticsearchTestFixture,
        searchResponseGate: ElasticsearchSearchResponseGate? = null,
        requestObserver: ((method: String, path: String) -> Unit)? = null,
    ): ReactiveElasticsearchClient {
        return elasticsearch.getOrCreateResource(CLIENT_RESOURCE_KEY) {
            createReactiveElasticsearchClientResource(elasticsearch, searchResponseGate, requestObserver)
        }
    }

    private fun createReactiveElasticsearchClientResource(
        elasticsearch: ElasticsearchTestFixture,
        searchResponseGate: ElasticsearchSearchResponseGate?,
        requestObserver: ((method: String, path: String) -> Unit)?,
    ): ReactiveElasticsearchClient {
        val httpHeaders = HttpHeaders()
        val clientConfiguration =
            ClientConfiguration
                .builder()
                .connectedTo(elasticsearch.hostAddress)
                .usingSsl(elasticsearch.sslContext)
                .withBasicAuth(elasticsearch.username, elasticsearch.password)
                .withSocketTimeout(Duration.ofSeconds(30))
                .withConnectTimeout(Duration.ofSeconds(5))
                .withDefaultHeaders(httpHeaders)
                .apply {
                    if (searchResponseGate != null || requestObserver != null) {
                        withClientConfigurer(
                            Rest5Clients.ElasticsearchHttpClientConfigurationCallback.from { builder ->
                                requestObserver?.let { observer ->
                                    builder.addRequestInterceptorLast { request, _, _ ->
                                        observer(request.method, request.requestUri)
                                    }
                                }
                                searchResponseGate?.let { gate ->
                                    builder.addResponseInterceptorLast { _, _, context ->
                                        gate.intercept(HttpCoreContext.cast(context).request?.requestUri)
                                    }
                                }
                                builder
                            },
                        )
                    }
                }
                .build()
        val rest5Client = Rest5Clients.getRest5Client(clientConfiguration)
        return try {
            ElasticsearchClients.createReactive(
                rest5Client,
                null,
                WowJsonpMapper,
            )
        } catch (error: Throwable) {
            rest5Client.close()
            throw error
        }
    }
}

class ElasticsearchSearchResponseGate {
    private val tokenSequence = java.util.concurrent.atomic.AtomicLong()
    private val armed = AtomicReference<ArmedResponse?>()

    fun reset() {
        armed.getAndSet(null)?.release?.countDown()
    }

    fun arm(): String {
        val token = "wowquerygate${tokenSequence.incrementAndGet()}"
        check(armed.compareAndSet(null, ArmedResponse(token))) {
            "An Elasticsearch response gate is already armed."
        }
        return token
    }

    fun awaitIntercepted(): Boolean = armed.get()?.intercepted?.await(2, TimeUnit.SECONDS) == true

    fun intercepted(): Mono<Void> = armed.get()?.interceptedSignal?.asMono()
        ?: Mono.error(IllegalStateException("An Elasticsearch response gate is not armed."))

    fun release() {
        armed.get()?.release?.countDown()
    }

    fun intercept(requestUri: String?) {
        if (requestUri?.substringBefore('?')?.endsWith("/_search") != true) {
            return
        }
        val current = armed.get() ?: return
        val queryParameters = requestUri.substringAfter('?', "").split('&')
        if ("preference=${current.token}" !in queryParameters || !current.claimed.compareAndSet(false, true)) {
            return
        }
        current.intercepted.countDown()
        current.interceptedSignal.tryEmitEmpty()
        check(current.release.await(10, TimeUnit.SECONDS)) {
            "Timed out waiting to release an Elasticsearch SEARCH response."
        }
    }

    private class ArmedResponse(
        val token: String,
        val claimed: java.util.concurrent.atomic.AtomicBoolean = java.util.concurrent.atomic.AtomicBoolean(),
        val intercepted: CountDownLatch = CountDownLatch(1),
        val interceptedSignal: Sinks.One<Void> = Sinks.one(),
        val release: CountDownLatch = CountDownLatch(1),
    )
}
