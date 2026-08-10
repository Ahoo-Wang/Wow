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

@file:OptIn(me.ahoo.wow.query.backend.ExperimentalQueryBackendApi::class)

package me.ahoo.wow.elasticsearch.query.planned

import co.elastic.clients.elasticsearch._types.FieldValue
import co.elastic.clients.elasticsearch._types.ShardStatistics
import co.elastic.clients.elasticsearch._types.aggregations.CompositeBucket
import co.elastic.clients.elasticsearch.core.ClosePointInTimeRequest
import co.elastic.clients.elasticsearch.core.OpenPointInTimeRequest
import co.elastic.clients.elasticsearch.core.SearchRequest
import co.elastic.clients.elasticsearch.core.search.ResponseBody
import co.elastic.clients.elasticsearch.core.search.TotalHitsRelation
import me.ahoo.wow.query.backend.AnalyticsAlias
import me.ahoo.wow.query.backend.AnalyticsQueryBackend
import me.ahoo.wow.query.backend.AnalyticsQueryCursorLifecycle
import me.ahoo.wow.query.backend.BackendAnalyticsBucket
import me.ahoo.wow.query.backend.BackendAnalyticsCompleteness
import me.ahoo.wow.query.backend.BackendAnalyticsConsistency
import me.ahoo.wow.query.backend.BackendAnalyticsCursorState
import me.ahoo.wow.query.backend.BackendAnalyticsDimension
import me.ahoo.wow.query.backend.BackendAnalyticsGrouping
import me.ahoo.wow.query.backend.BackendAnalyticsMetric
import me.ahoo.wow.query.backend.BackendAnalyticsMissingPolicy
import me.ahoo.wow.query.backend.BackendAnalyticsPage
import me.ahoo.wow.query.backend.BackendAnalyticsQueryPlan
import me.ahoo.wow.query.backend.LogicalFieldType
import me.ahoo.wow.query.backend.NormalizedValue
import me.ahoo.wow.query.backend.QueryBackendException
import me.ahoo.wow.query.backend.QueryBackendExecutionOptions
import me.ahoo.wow.query.backend.QueryBackendFailureKind
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Mono
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.LinkedHashMap

internal class ElasticsearchAnalyticsQueryBackend(
    private val client: ReactiveElasticsearchClient,
    private val binding: ElasticsearchPreparedQueryBinding,
    private val clock: Clock = Clock.systemUTC(),
) : AnalyticsQueryBackend, AnalyticsQueryCursorLifecycle {
    private val compiler = ElasticsearchAnalyticsQueryCompiler(binding)

    override fun analyze(
        plan: BackendAnalyticsQueryPlan,
        options: QueryBackendExecutionOptions,
    ): Mono<BackendAnalyticsPage> = analyze(plan, options, null)

    override fun analyze(
        plan: BackendAnalyticsQueryPlan,
        options: QueryBackendExecutionOptions,
        cursorState: BackendAnalyticsCursorState?,
    ): Mono<BackendAnalyticsPage> = Mono.defer {
        validateOptions(plan, options)
        val compiled = compiler.compile(plan)
        when (plan.requiredConsistency) {
            BackendAnalyticsConsistency.EVENTUAL -> {
                if (cursorState != null) unsupported()
                client.search(searchRequest(plan, compiled, options, null), Map::class.java)
                    .map { response -> mapResponse(plan, response, BackendAnalyticsConsistency.EVENTUAL, null) }
            }

            BackendAnalyticsConsistency.SNAPSHOT -> analyzeSnapshot(plan, compiled, options, cursorState)
        }
    }.onErrorMap(::mapBackendError)

    override fun close(cursorState: BackendAnalyticsCursorState): Mono<Void> = Mono.defer {
        closePit(PitLease(decodePitId(cursorState)))
    }.onErrorMap(::mapBackendError)

    private fun analyzeSnapshot(
        plan: BackendAnalyticsQueryPlan,
        compiled: ElasticsearchCompiledAnalyticsQuery,
        options: QueryBackendExecutionOptions,
        cursorState: BackendAnalyticsCursorState?,
    ): Mono<BackendAnalyticsPage> {
        val resource = cursorState?.let { state -> Mono.just(PitLease(decodePitId(state))) } ?: openPit()
        return Mono.usingWhen(
            resource,
            { lease ->
                client.search(searchRequest(plan, compiled, options, lease.id), Map::class.java)
                    .map { response ->
                        response.pitId()?.takeIf(String::isNotBlank)?.let(lease::update)
                        val grouped = plan.grouping is BackendAnalyticsGrouping.By
                        val page = mapResponse(
                            plan,
                            response,
                            BackendAnalyticsConsistency.SNAPSHOT,
                            if (grouped) BackendAnalyticsCursorState(lease.id.encodeToByteArray()) else null,
                        )
                        if (grouped) lease.transfer()
                        page
                    }
            },
            ::closeUnlessTransferred,
            { lease, original -> closeAfterError(lease, original) },
            ::closeAfterCancel,
        )
    }

    private fun openPit(): Mono<PitLease> = client.openPointInTime(
        OpenPointInTimeRequest.of { request ->
            request.index(binding.indexName).keepAlive { keepAlive -> keepAlive.time(PIT_KEEP_ALIVE) }
        },
    ).switchIfEmpty(Mono.error(QueryBackendException(QueryBackendFailureKind.UNAVAILABLE)))
        .map { response ->
            if (response.id().isBlank() || response.shards().failed() != 0) incomplete()
            PitLease(response.id())
        }

    private fun searchRequest(
        plan: BackendAnalyticsQueryPlan,
        compiled: ElasticsearchCompiledAnalyticsQuery,
        options: QueryBackendExecutionOptions,
        pitId: String?,
    ): SearchRequest = SearchRequest.of { request ->
        request.query(compiled.query)
            .size(0)
            .allowPartialSearchResults(false)
            .trackTotalHits { total -> total.enabled(plan.grouping == BackendAnalyticsGrouping.Global) }
            .also { builder ->
                if (pitId == null) {
                    builder.index(binding.indexName)
                } else {
                    builder.pit { pit ->
                        pit.id(pitId).keepAlive { keepAlive -> keepAlive.time(PIT_KEEP_ALIVE) }
                    }
                }
                compiled.aggregation?.let { aggregation ->
                    builder.aggregations(ANALYTICS_AGGREGATION, aggregation)
                }
                options.remainingMillis()?.let { remaining -> builder.timeout("${remaining}ms") }
            }
    }

    private fun mapResponse(
        plan: BackendAnalyticsQueryPlan,
        response: ResponseBody<*>,
        consistency: BackendAnalyticsConsistency,
        cursorState: BackendAnalyticsCursorState?,
    ): BackendAnalyticsPage = mapFailures {
        if (response.timedOut()) timeout()
        requireCompleteShards(response.shards())
        when (val grouping = plan.grouping) {
            BackendAnalyticsGrouping.Global -> mapGlobal(plan, response, consistency)
            is BackendAnalyticsGrouping.By -> mapComposite(plan, grouping, response, consistency, cursorState)
        }
    }

    private fun mapGlobal(
        plan: BackendAnalyticsQueryPlan,
        response: ResponseBody<*>,
        consistency: BackendAnalyticsConsistency,
    ): BackendAnalyticsPage {
        val total = response.hits().total() ?: incomplete()
        if (total.relation() != TotalHitsRelation.Eq || total.value() < 0) incomplete()
        val metrics = plan.metrics.associate { metric ->
            val count = metric as? BackendAnalyticsMetric.DocumentCount ?: mappingFailure()
            count.alias to NormalizedValue.Int64(total.value())
        }
        return BackendAnalyticsPage(
            listOf(BackendAnalyticsBucket(emptyMap(), metrics)),
            null,
            consistency,
            BackendAnalyticsCompleteness.EXACT,
        )
    }

    private fun mapComposite(
        plan: BackendAnalyticsQueryPlan,
        grouping: BackendAnalyticsGrouping.By,
        response: ResponseBody<*>,
        consistency: BackendAnalyticsConsistency,
        cursorState: BackendAnalyticsCursorState?,
    ): BackendAnalyticsPage {
        val aggregate = response.aggregations()[ANALYTICS_AGGREGATION]
        if (aggregate == null || !aggregate.isComposite) mappingFailure()
        val composite = aggregate.composite()
        val buckets = composite.buckets().array()
        if (buckets.size > plan.bucketWindow.limit) incomplete()
        val mapped = buckets.map { bucket -> mapBucket(grouping.dimensions, plan.metrics, bucket) }
        val afterKey = mapAfterKey(grouping.dimensions, composite.afterKey())
        return BackendAnalyticsPage(
            mapped,
            afterKey,
            consistency,
            BackendAnalyticsCompleteness.EXACT,
            cursorState,
        )
    }

    private fun closeUnlessTransferred(lease: PitLease): Mono<Void> =
        if (lease.transferred) Mono.empty() else closePit(lease)

    private fun closeAfterError(lease: PitLease, original: Throwable): Mono<Void> =
        closePit(lease).onErrorResume { closeError ->
            original.addSuppressed(closeError)
            Mono.empty()
        }

    private fun closeAfterCancel(lease: PitLease): Mono<Void> = closePit(lease).onErrorResume { Mono.empty() }

    private fun closePit(lease: PitLease): Mono<Void> = client.closePointInTime(
        ClosePointInTimeRequest.of { request -> request.id(lease.id) },
    ).switchIfEmpty(
        Mono.error(QueryBackendException(QueryBackendFailureKind.INCOMPLETE_RESULT)),
    ).flatMap { response ->
        if (response.succeeded()) {
            Mono.empty()
        } else {
            Mono.error(QueryBackendException(QueryBackendFailureKind.INCOMPLETE_RESULT))
        }
    }

    private fun decodePitId(state: BackendAnalyticsCursorState): String = try {
        val payload = state.payload()
        if (payload.size > MAX_PIT_ID_BYTES) unsupported()
        val decoded = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(payload))
            .toString()
        if (decoded.isBlank()) unsupported()
        decoded
    } catch (error: java.nio.charset.CharacterCodingException) {
        throw QueryBackendException(QueryBackendFailureKind.UNSUPPORTED, error)
    }

    private fun mapBucket(
        dimensions: List<BackendAnalyticsDimension>,
        metrics: List<BackendAnalyticsMetric>,
        bucket: CompositeBucket,
    ): BackendAnalyticsBucket {
        if (bucket.docCount() < 0 || bucket.key().keys != dimensions.map { it.alias.value }.toSet()) {
            mappingFailure()
        }
        val keys = LinkedHashMap<AnalyticsAlias, NormalizedValue>(dimensions.size)
        dimensions.forEach { dimension ->
            keys[dimension.alias] = decodeDimensionValue(dimension, bucket.key().getValue(dimension.alias.value))
        }
        val metricValues = LinkedHashMap<AnalyticsAlias, NormalizedValue>(metrics.size)
        metrics.forEach { metric ->
            val count = metric as? BackendAnalyticsMetric.DocumentCount ?: mappingFailure()
            metricValues[count.alias] = NormalizedValue.Int64(bucket.docCount())
        }
        return BackendAnalyticsBucket(keys, metricValues)
    }

    private fun mapAfterKey(
        dimensions: List<BackendAnalyticsDimension>,
        raw: Map<String, FieldValue>,
    ): List<NormalizedValue>? {
        if (raw.isEmpty()) return null
        if (raw.keys != dimensions.map { it.alias.value }.toSet()) mappingFailure()
        return dimensions.map { dimension ->
            decodeDimensionValue(dimension, raw.getValue(dimension.alias.value))
        }
    }

    private fun decodeDimensionValue(
        dimension: BackendAnalyticsDimension,
        raw: FieldValue,
    ): NormalizedValue {
        if (raw.isNull) {
            if (dimension.missingPolicy == BackendAnalyticsMissingPolicy.AS_NULL_BUCKET) {
                return NormalizedValue.Null
            }
            mappingFailure()
        }
        return when (binding.schema.fields.getValue(dimension.field).type) {
            LogicalFieldType.Text -> if (raw.isString) {
                NormalizedValue.Text(raw.stringValue())
            } else {
                mappingFailure()
            }

            LogicalFieldType.Boolean -> if (raw.isBoolean) {
                NormalizedValue.BooleanValue(raw.booleanValue())
            } else {
                mappingFailure()
            }

            LogicalFieldType.Int64 -> if (raw.isLong) {
                NormalizedValue.Int64(raw.longValue())
            } else {
                mappingFailure()
            }

            LogicalFieldType.Instant -> if (raw.isLong) {
                NormalizedValue.InstantValue(Instant.ofEpochMilli(raw.longValue()))
            } else {
                mappingFailure()
            }

            LogicalFieldType.Decimal,
            LogicalFieldType.Bytes,
            LogicalFieldType.Object,
            is LogicalFieldType.Array,
            -> mappingFailure()
        }
    }

    private fun validateOptions(plan: BackendAnalyticsQueryPlan, options: QueryBackendExecutionOptions) {
        if (options.maxScannedRecords != null ||
            options.maxCandidateBuckets != null ||
            options.maxCursorPages != null
        ) {
            unsupported()
        }
        options.maxReturnedBuckets?.let { maximum ->
            if (plan.bucketWindow.limit > maximum) budgetExceeded()
        }
        options.remainingMillis()
    }

    private fun QueryBackendExecutionOptions.remainingMillis(): Long? {
        val currentDeadline = deadline ?: return null
        val now = clock.instant()
        val remaining = try {
            Duration.between(now, currentDeadline).toMillis()
        } catch (error: ArithmeticException) {
            if (currentDeadline.isAfter(now)) {
                Long.MAX_VALUE
            } else {
                throw QueryBackendException(
                    QueryBackendFailureKind.TIMEOUT,
                    error,
                )
            }
        }
        if (remaining <= 0) {
            timeout()
        }
        return remaining
    }

    private fun requireCompleteShards(shards: ShardStatistics) {
        if (shards.failed().toLong() != 0L) incomplete()
    }

    @Suppress("TooGenericExceptionCaught")
    private inline fun <T> mapFailures(block: () -> T): T = try {
        block()
    } catch (error: QueryBackendException) {
        throw error
    } catch (error: RuntimeException) {
        throw QueryBackendException(QueryBackendFailureKind.MAPPING_FAILURE, error)
    }

    private fun mapBackendError(error: Throwable): Throwable = when {
        error is QueryBackendException -> error
        error.isMissingElasticsearchSearchContext() ->
            QueryBackendException(QueryBackendFailureKind.INCOMPLETE_RESULT, error)
        else -> QueryBackendException(QueryBackendFailureKind.UNAVAILABLE, error)
    }

    private fun unsupported(): Nothing = throw QueryBackendException(QueryBackendFailureKind.UNSUPPORTED)

    private fun budgetExceeded(): Nothing = throw QueryBackendException(QueryBackendFailureKind.BUDGET_EXCEEDED)

    private fun timeout(): Nothing = throw QueryBackendException(QueryBackendFailureKind.TIMEOUT)

    private fun incomplete(): Nothing = throw QueryBackendException(QueryBackendFailureKind.INCOMPLETE_RESULT)

    private fun mappingFailure(): Nothing = throw QueryBackendException(QueryBackendFailureKind.MAPPING_FAILURE)

    internal companion object {
        const val ANALYTICS_AGGREGATION = "wow_analytics"
        const val PIT_KEEP_ALIVE = "2m"
        const val MAX_PIT_ID_BYTES = 4_096
    }

    private class PitLease(initialId: String) {
        var id: String = initialId
            private set
        var transferred: Boolean = false
            private set

        fun update(value: String) {
            id = value
        }

        fun transfer() {
            transferred = true
        }
    }
}
