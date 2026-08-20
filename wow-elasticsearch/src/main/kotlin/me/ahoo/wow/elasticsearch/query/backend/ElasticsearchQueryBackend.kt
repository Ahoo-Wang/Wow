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

import co.elastic.clients.elasticsearch.core.CountRequest
import co.elastic.clients.elasticsearch.core.SearchRequest
import me.ahoo.wow.api.query.error.QueryErrorCode
import me.ahoo.wow.api.query.error.QueryErrorReason
import me.ahoo.wow.api.query.error.QueryException
import me.ahoo.wow.api.query.error.QueryStage
import me.ahoo.wow.api.query.gateway.QueryConsistency
import me.ahoo.wow.api.query.gateway.QueryPage
import me.ahoo.wow.query.backend.QueryBackend
import me.ahoo.wow.query.backend.QueryBackendDescriptor
import me.ahoo.wow.query.backend.QueryBackendReadiness
import me.ahoo.wow.query.plan.CountQueryPlanV1
import me.ahoo.wow.query.plan.ListQueryPlanV1
import me.ahoo.wow.query.plan.PageQueryPlanV1
import me.ahoo.wow.query.plan.SingleQueryPlanV1
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

internal class ElasticsearchQueryBackend(
    client: ReactiveElasticsearchClient,
    private val index: String,
    private val binding: ElasticsearchQueryFieldBinding,
    nativeTemplates: ElasticsearchNativeQueryTemplateRegistry,
    override val descriptor: QueryBackendDescriptor,
    readinessRequirements: ElasticsearchQueryReadinessRequirements,
    private val pitPageSize: Int = 256,
    private val prefetchFirstPitPage: Boolean = false,
    private val prefetchBarrier: (() -> Mono<Void>)? = null,
    private val transport: ElasticsearchQueryTransport = ReactiveClientElasticsearchQueryTransport(client),
    internal val mappingGuard: ElasticsearchQueryMappingGuard =
        ElasticsearchQueryReadiness(client, index, readinessRequirements),
) : QueryBackend {
    private val compiler = ElasticsearchQueryPlanCompiler(binding, nativeTemplates)
    private val decoder = ElasticsearchQueryResultDecoder(binding)
    override fun readiness(): Mono<QueryBackendReadiness> = mappingGuard.inspect()

    override fun <R : Any> single(plan: SingleQueryPlanV1<R>): Mono<R> = Mono.defer {
        requirePlanFields(plan)
        transport.searchResult(searchRequest(plan, from = 0, size = 1, exactTotal = false))
            .flatMap { result ->
                result.page.hits.firstOrNull()?.let { hit -> Mono.just(decode<R>(hit.source, plan)) } ?: Mono.empty()
            }
    }

    override fun <R : Any> list(plan: ListQueryPlanV1<R>): Flux<R> = Flux.defer {
        requirePlanFields(plan)
        val budgetLimit = plan.effectiveBudget.maxResults
        if (plan.limit > 0 && budgetLimit != null && plan.limit.toLong() > budgetLimit) {
            return@defer Flux.error(budgetExceeded())
        }
        if (plan.limit in 1..SAFE_SEARCH_SIZE) {
            return@defer transport.searchResult(
                searchRequest(plan, from = 0, size = plan.limit, exactTotal = false),
            ).flatMapMany { result -> Flux.fromIterable(result.page.hits) }
                .map { hit -> decode<R>(hit.source, plan) }
        }
        val requestLimit = plan.limit.takeIf { it > 0 }?.toLong()
        val maxResults = budgetLimit.takeIf { requestLimit == null }
        PitSearchAfterExecutor(
            transport,
            index,
            pitPageSize,
            prefetchFirstPitPage = prefetchFirstPitPage,
            prefetchBarrier = prefetchBarrier,
        ) { request -> pitSearchRequest(request, plan) }
            .execute(requestLimit, maxResults)
            .map { hit -> decode<R>(hit.source, plan) }
    }

    override fun <R : Any> page(plan: PageQueryPlanV1<R>): Mono<QueryPage<R>> = Mono.defer {
        requirePlanFields(plan)
        val offset = try {
            Math.multiplyExact((plan.page.index - 1).toLong(), plan.page.size.toLong())
        } catch (_: ArithmeticException) {
            throw budgetExceeded()
        }
        if (offset > Int.MAX_VALUE) {
            throw budgetExceeded()
        }
        if (offset + plan.page.size > MAX_RESULT_WINDOW) {
            throw budgetExceeded()
        }
        transport.searchResult(
            searchRequest(plan, offset.toInt(), plan.page.size, exactTotal = true),
        ).map { result ->
            val total = result.total?.takeIf { result.totalIsExact } ?: throw backendFailure()
            QueryPage(
                result.page.hits.map { hit -> decode<R>(hit.source, plan) },
                total,
                QueryConsistency.EXACT,
            )
        }
    }

    override fun count(plan: CountQueryPlanV1): Mono<Long> = Mono.defer {
        requirePlanFields(plan)
        val request = CountRequest.of { builder ->
            builder.index(index).query(compiler.query(plan.securedExpression))
        }
        transport.count(request)
    }

    private fun searchRequest(
        plan: me.ahoo.wow.query.plan.QueryPlanV1,
        from: Int,
        size: Int,
        exactTotal: Boolean,
    ): SearchRequest = SearchRequest.of { builder ->
        builder.index(index)
            .query(compiler.query(plan.securedExpression))
            .from(from)
            .size(size)
            .trackTotalHits { total -> total.enabled(exactTotal) }
            .applyPlan(plan)
    }

    private fun pitSearchRequest(source: SearchRequest, plan: me.ahoo.wow.query.plan.QueryPlanV1): SearchRequest =
        SearchRequest.of { builder ->
            builder.pit(source.pit())
                .size(source.size())
                .query(compiler.query(plan.securedExpression))
                .trackTotalHits { total -> total.enabled(false) }
                .apply {
                    if (source.searchAfter().isNotEmpty()) {
                        searchAfter(source.searchAfter())
                    }
                }
                .applyPlan(plan)
        }

    private fun SearchRequest.Builder.applyPlan(plan: me.ahoo.wow.query.plan.QueryPlanV1): SearchRequest.Builder {
        val sort = compiler.sort(plan)
        if (sort.isNotEmpty()) {
            sort(sort)
        }
        compiler.sourceFilter(plan)?.let(::source)
        return this
    }

    private fun <R : Any> decode(source: Map<String, Any?>, plan: me.ahoo.wow.query.plan.QueryPlanV1): R =
        decoder.decode(source, plan.authorizedResultShape, compiler.resultProjection(plan))

    private fun requirePlanFields(plan: me.ahoo.wow.query.plan.QueryPlanV1) {
        val requirements = LinkedHashSet<ElasticsearchMappingFieldRequirement>()
        plan.sort.forEach { sort ->
            val schema = binding.schema(sort.field)
            requirements += ElasticsearchMappingFieldRequirement(
                binding.physical(sort.field, me.ahoo.wow.query.schema.QueryFieldUsage.SORT),
                schema.valueKind,
                schema.collectionKind,
                schema.system,
                ElasticsearchMappingUsage.SORT,
                schema.stringOptions?.maxLength,
            )
        }
        compiler.resultProjection(plan).forEach { (logical, source) ->
            val schema = binding.schema(logical)
            requirements += ElasticsearchMappingFieldRequirement(
                source,
                schema.valueKind,
                schema.collectionKind,
                schema.system,
                ElasticsearchMappingUsage.SOURCE,
                schema.stringOptions?.maxLength,
            )
        }
        mappingGuard.requireFields(requirements)
    }

    private fun budgetExceeded() = QueryException(
        QueryErrorCode.BUDGET_EXCEEDED,
        QueryStage.EXECUTION,
        QueryErrorReason.BUDGET_LIMIT_REACHED,
    )

    private fun backendFailure() = QueryException(
        QueryErrorCode.BACKEND_FAILURE,
        QueryStage.EXECUTION,
        QueryErrorReason.BACKEND_EXECUTION_FAILED,
    )

    companion object {
        private const val SAFE_SEARCH_SIZE = 256
        private const val MAX_RESULT_WINDOW = 10_000L
    }
}
