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

@file:JvmSynthetic

package me.ahoo.wow.elasticsearch.query.backend

import co.elastic.clients.elasticsearch.core.ClosePointInTimeRequest
import co.elastic.clients.elasticsearch.core.CountRequest
import co.elastic.clients.elasticsearch.core.OpenPointInTimeRequest
import co.elastic.clients.elasticsearch.core.SearchRequest
import co.elastic.clients.elasticsearch.core.search.TotalHitsRelation
import me.ahoo.wow.api.query.error.QueryErrorCode
import me.ahoo.wow.api.query.error.QueryErrorReason
import me.ahoo.wow.api.query.error.QueryException
import me.ahoo.wow.api.query.error.QueryStage
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Mono

internal data class ElasticsearchSearchResult(
    val page: PitSearchPage<Map<String, Any?>>,
    val total: Long?,
    val totalIsExact: Boolean = total != null,
)

internal interface ElasticsearchQueryTransport : PitSearchAfterTransport<Map<String, Any?>> {
    fun searchResult(request: SearchRequest): Mono<ElasticsearchSearchResult>

    fun count(request: CountRequest): Mono<Long>

    override fun search(request: SearchRequest): Mono<PitSearchPage<Map<String, Any?>>> =
        searchResult(request).map(ElasticsearchSearchResult::page)
}

internal class ReactiveClientElasticsearchQueryTransport(
    private val client: ReactiveElasticsearchClient,
) : ElasticsearchQueryTransport {
    private val observer = ElasticsearchQueryPublisherObservers.resolve(client)
    override fun open(index: String): Mono<String> = client.openPointInTime(
        OpenPointInTimeRequest.of { request ->
            request.index(index).keepAlive { keepAlive -> keepAlive.time(PIT_KEEP_ALIVE) }
        },
    ).map { response -> response.id() }
        .switchIfEmpty(Mono.error(backendFailure()))
        .onErrorMap(::sanitize)

    override fun searchResult(request: SearchRequest): Mono<ElasticsearchSearchResult> =
        observer.observe(client.search(request, Map::class.java))
            .map { response ->
                if (response.timedOut() || response.shards().failed().toInt() > 0) {
                    throw backendFailure()
                }
                val hits = response.hits()
                ElasticsearchSearchResult(
                    PitSearchPage(
                        hits.hits().map { hit ->
                            @Suppress("UNCHECKED_CAST")
                            val source = hit.source() as? Map<String, Any?> ?: throw invalidResult()
                            PitSearchHit(source, hit.sort())
                        },
                    ),
                    hits.total()?.value(),
                    hits.total()?.relation() == TotalHitsRelation.Eq,
                )
            }
            .switchIfEmpty(Mono.error(backendFailure()))
            .onErrorMap(::sanitize)

    override fun count(request: CountRequest): Mono<Long> = client.count(request)
        .map { response -> response.count() }
        .switchIfEmpty(Mono.error(backendFailure()))
        .onErrorMap(::sanitize)

    override fun close(pitId: String): Mono<Void> = client.closePointInTime(
        ClosePointInTimeRequest.of { request -> request.id(pitId) },
    ).flatMap { response ->
        if (response.succeeded()) Mono.empty<Void>() else Mono.error(backendFailure())
    }.onErrorMap(::sanitize)

    private fun sanitize(error: Throwable): Throwable = if (error is QueryException) error else backendFailure()

    private fun backendFailure() = QueryException(
        QueryErrorCode.BACKEND_FAILURE,
        QueryStage.EXECUTION,
        QueryErrorReason.BACKEND_EXECUTION_FAILED,
    )

    private fun invalidResult() = QueryException(
        QueryErrorCode.RESULT_VALIDATION_FAILED,
        QueryStage.EXECUTION,
        QueryErrorReason.RESULT_INVALID,
    )

    companion object {
        private const val PIT_KEEP_ALIVE = "1m"
    }
}
