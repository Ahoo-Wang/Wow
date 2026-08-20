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

package me.ahoo.wow.elasticsearch.query.gateway

import co.elastic.clients.elasticsearch._types.FieldValue
import co.elastic.clients.elasticsearch.core.ClosePointInTimeRequest
import co.elastic.clients.elasticsearch.core.CountRequest
import co.elastic.clients.elasticsearch.core.CountResponse
import co.elastic.clients.elasticsearch.core.OpenPointInTimeRequest
import co.elastic.clients.elasticsearch.core.SearchRequest
import co.elastic.clients.elasticsearch.core.search.TotalHitsRelation
import me.ahoo.wow.api.query.ElementMatchExpression
import me.ahoo.wow.api.query.LogicalExpression
import me.ahoo.wow.api.query.PredicateExpression
import me.ahoo.wow.api.query.PredicateOperator
import me.ahoo.wow.api.query.QueryErrorCode
import me.ahoo.wow.api.query.QueryException
import me.ahoo.wow.api.query.QueryExpression
import me.ahoo.wow.api.query.QueryPage
import me.ahoo.wow.api.query.QueryStage
import me.ahoo.wow.query.backend.QueryBackend
import me.ahoo.wow.query.backend.SecuredQuery
import me.ahoo.wow.serialization.JsonSerializer
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import tools.jackson.databind.node.ObjectNode
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

class ElasticsearchSnapshotQueryBackend(
    private val client: ReactiveElasticsearchClient,
    private val options: ElasticsearchQueryBackendOptions = ElasticsearchQueryBackendOptions()
) : QueryBackend {
    override val id: String = ID
    private val mapping = ElasticsearchQueryMapping(client, options)
    internal var onPitOpened: (String) -> Unit = {}
    internal var onPitClosed: (String) -> Unit = {}
    internal var beforePitSearch: () -> Unit = {}

    override fun validate(query: SecuredQuery) {
        if (query.offset > Int.MAX_VALUE) unsupported()
        if (query.filter.requiresPresenceMetadata()) unsupported()
        if (query.operation == me.ahoo.wow.query.policy.QueryOperation.PAGE) {
            val size = query.limit ?: unsupported()
            if (query.offset + size > options.maxResultWindow) unsupported()
        }
    }

    override fun stream(query: SecuredQuery): Flux<ObjectNode> = mapping.snapshot(query).flatMapMany { snapshot ->
        val compiler = ElasticsearchQueryCompiler(snapshot)
        Flux.usingWhen(
            openPit(snapshot).map { pitId -> PitSession(AtomicReference(pitId), compiler) },
            { session -> searchAfter(session, query, emptyList(), 0) },
            ::closePit,
            { session, _ -> closePit(session) },
            ::closePit
        )
    }

    override fun page(query: SecuredQuery): Mono<QueryPage<ObjectNode>> = mapping.snapshot(query).flatMap { snapshot ->
        val compiler = ElasticsearchQueryCompiler(snapshot)
        val request = SearchRequest.of { builder ->
            builder.index(snapshot.indices)
                .query(compiler.query(query.filter))
                .from(query.offset.toInt())
                .size(query.limit ?: unsupported())
                .trackTotalHits { total -> total.enabled(true) }
                .apply {
                    val sort = compiler.sort(query, pit = false)
                    if (sort.isNotEmpty()) sort(sort)
                }
        }
        client.search(request, Map::class.java).map { response ->
            if (response.timedOut() || response.shards().failed().toInt() > 0) backendFailure()
            val total = response.hits().total()
            if (total == null || total.relation() != TotalHitsRelation.Eq) backendFailure()
            QueryPage(response.hits().hits().map { hit -> decode(hit.source()) }, total.value())
        }
    }

    override fun count(query: SecuredQuery): Mono<Long> = mapping.snapshot(query).flatMap { snapshot ->
        val compiler = ElasticsearchQueryCompiler(snapshot)
        client.count(
            CountRequest.of { builder ->
                builder.index(snapshot.indices).query(compiler.query(query.filter))
            }
        ).map(::exactCount)
    }

    private fun openPit(snapshot: ElasticsearchExecutionSnapshot): Mono<String> = client.openPointInTime(
        OpenPointInTimeRequest.of { request ->
            request.index(snapshot.indices).keepAlive { keepAlive -> keepAlive.time(options.pitKeepAlive) }
        }
    ).map { response -> response.id().also(onPitOpened) }
        .switchIfEmpty(Mono.error(backendFailureException()))

    private fun closePit(session: PitSession): Mono<Void> {
        val id = session.pitId.get()
        return client.closePointInTime(ClosePointInTimeRequest.of { request -> request.id(id) })
            .flatMap { response ->
                if (response.succeeded()) {
                    onPitClosed(id)
                    Mono.empty()
                } else {
                    Mono.error(backendFailureException())
                }
            }
    }

    private fun searchAfter(
        session: PitSession,
        query: SecuredQuery,
        searchAfter: List<FieldValue>,
        emitted: Long
    ): Flux<ObjectNode> = Flux.defer {
        beforePitSearch()
        val window = searchWindow(query, emitted) ?: return@defer Flux.empty()
        val request = SearchRequest.of { builder ->
            builder.pit { pit ->
                pit.id(session.pitId.get()).keepAlive { keepAlive -> keepAlive.time(options.pitKeepAlive) }
            }
                .query(session.compiler.query(query.filter))
                .size(window.size)
                .sort(session.compiler.sort(query, pit = true))
                .trackTotalHits { total -> total.enabled(false) }
                .apply { if (searchAfter.isNotEmpty()) searchAfter(searchAfter) }
        }
        client.search(request, Map::class.java).flatMapMany { response ->
            response.pitId()?.let(session.pitId::set)
            if (response.timedOut() || response.shards().failed().toInt() > 0) backendFailure()
            val hits = response.hits().hits()
            if (hits.isEmpty()) return@flatMapMany Flux.empty()
            val page = hits.map { hit ->
                val sort = hit.sort()
                if (sort.isEmpty()) resultInvalid()
                PitHit(decode(hit.source()), sort)
            }
            validateSort(page, searchAfter)
            emitPage(session, query, page, window, emitted)
        }
    }

    private fun emitPage(
        session: PitSession,
        query: SecuredQuery,
        page: List<PitHit>,
        window: SearchWindow,
        emitted: Long
    ): Flux<ObjectNode> {
        val allowed = listOfNotNull(
            page.size.toLong(),
            window.remainingRequest,
            window.remainingBudget?.coerceAtLeast(0)
        )
            .min()
            .toInt()
        val current = Flux.fromIterable(page.take(allowed).map(PitHit::record))
        val budgetExceeded = window.remainingBudget != null && page.size.toLong() > window.remainingBudget &&
            (window.remainingRequest == null || window.remainingBudget < window.remainingRequest)
        val requestSatisfied = window.remainingRequest != null && allowed.toLong() >= window.remainingRequest
        val next = when {
            budgetExceeded -> Flux.error(QueryException(QueryErrorCode.BUDGET_EXCEEDED, QueryStage.BACKEND))
            requestSatisfied || page.size < window.size -> Flux.empty()
            else -> searchAfter(session, query, page.last().sort, emitted + allowed)
        }
        return current.concatWith(next)
    }

    private fun searchWindow(query: SecuredQuery, emitted: Long): SearchWindow? {
        val remainingRequest = query.limit?.toLong()?.minus(emitted)
        if (remainingRequest != null && remainingRequest <= 0) return null
        val remainingBudget = query.budget.maxRecords?.minus(emitted)
        if (remainingBudget != null && remainingBudget < 0) {
            throw QueryException(QueryErrorCode.BUDGET_EXCEEDED, QueryStage.BACKEND)
        }
        val budgetProbe = remainingBudget?.let { Math.addExact(it, 1) }
        val size = listOfNotNull(options.pitPageSize.toLong(), remainingRequest, budgetProbe).min().toInt()
        return SearchWindow(remainingRequest, remainingBudget, size)
    }

    private fun validateSort(page: List<PitHit>, previous: List<FieldValue>) {
        val signatures = page.map { hit -> hit.sort.map(::signature) }
        if (signatures.toSet().size != signatures.size) resultInvalid()
        if (previous.isNotEmpty() && signatures.first() == previous.map(::signature)) resultInvalid()
    }

    private fun signature(value: FieldValue): String = when (value._kind()) {
        FieldValue.Kind.String -> "s:${value.stringValue()}"
        FieldValue.Kind.Long -> "l:${value.longValue()}"
        FieldValue.Kind.Double -> "d:${value.doubleValue()}"
        FieldValue.Kind.Boolean -> "b:${value.booleanValue()}"
        FieldValue.Kind.Null -> "n:"
        FieldValue.Kind.Any -> resultInvalid()
    }

    @Suppress("UNCHECKED_CAST")
    private fun decode(source: Map<*, *>?): ObjectNode {
        val typed = source as? Map<String, Any?> ?: resultInvalid()
        val result = JsonSerializer.valueToTree<ObjectNode>(typed)
        TIME_FIELDS.forEach { field ->
            val value = result[field]
            if (value?.isIntegralNumber == true) result.put(field, Instant.ofEpochMilli(value.longValue()).toString())
        }
        return result
    }

    private fun backendFailure(): Nothing = throw backendFailureException()

    private fun backendFailureException(): QueryException =
        QueryException(QueryErrorCode.BACKEND_FAILURE, QueryStage.BACKEND)

    private fun resultInvalid(): Nothing = throw QueryException(QueryErrorCode.RESULT_INVALID, QueryStage.BACKEND)

    private fun unsupported(): Nothing = throw QueryException(QueryErrorCode.UNSUPPORTED_QUERY, QueryStage.BACKEND)

    private fun QueryExpression.requiresPresenceMetadata(): Boolean = when (this) {
        is PredicateExpression -> when (operator) {
            PredicateOperator.NE,
            PredicateOperator.NOT_IN,
            PredicateOperator.IS_NULL,
            PredicateOperator.EXISTS,
            PredicateOperator.IS_EMPTY -> true

            PredicateOperator.EQ -> values.single().isNull
            PredicateOperator.IN -> values.any { it.isNull }
            else -> false
        }

        is LogicalExpression -> operands.any { it.requiresPresenceMetadata() }
        is ElementMatchExpression -> predicate.requiresPresenceMetadata()
        else -> false
    }

    private data class PitSession(
        val pitId: AtomicReference<String>,
        val compiler: ElasticsearchQueryCompiler
    )

    private data class PitHit(val record: ObjectNode, val sort: List<FieldValue>)

    private data class SearchWindow(
        val remainingRequest: Long?,
        val remainingBudget: Long?,
        val size: Int
    )

    private companion object {
        const val ID = "elasticsearch"
        val TIME_FIELDS = setOf("firstEventTime", "eventTime", "snapshotTime")
    }
}

internal fun exactCount(response: CountResponse): Long {
    if (response.shards().failed().toInt() > 0) {
        throw QueryException(QueryErrorCode.BACKEND_FAILURE, QueryStage.BACKEND)
    }
    return response.count()
}
