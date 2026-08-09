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

@file:OptIn(
    me.ahoo.wow.query.backend.ExperimentalQueryBackendApi::class,
    me.ahoo.wow.query.gateway.ExperimentalQueryGatewayApi::class,
)

package me.ahoo.wow.mongo.query.planned

import com.mongodb.client.model.Collation
import com.mongodb.reactivestreams.client.MongoCollection
import me.ahoo.wow.query.backend.AnalyticsAlias
import me.ahoo.wow.query.backend.AnalyticsQueryBackend
import me.ahoo.wow.query.backend.BackendAnalyticsBucket
import me.ahoo.wow.query.backend.BackendAnalyticsCompleteness
import me.ahoo.wow.query.backend.BackendAnalyticsConsistency
import me.ahoo.wow.query.backend.BackendAnalyticsGrouping
import me.ahoo.wow.query.backend.BackendAnalyticsMetric
import me.ahoo.wow.query.backend.BackendAnalyticsNumericPolicy
import me.ahoo.wow.query.backend.BackendAnalyticsPage
import me.ahoo.wow.query.backend.BackendAnalyticsQueryPlan
import me.ahoo.wow.query.backend.LogicalFieldType
import me.ahoo.wow.query.backend.NormalizedValue
import me.ahoo.wow.query.backend.QueryBackendException
import me.ahoo.wow.query.backend.QueryBackendExecutionOptions
import me.ahoo.wow.query.backend.QueryBackendFailureKind
import me.ahoo.wow.query.backend.QueryFieldId
import me.ahoo.wow.query.gateway.QueryDocumentKind
import org.bson.Document
import org.bson.types.Decimal128
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit

internal class MongoAnalyticsQueryBackend(
    private val collection: MongoCollection<Document>,
    private val binding: MongoPreparedQueryBinding,
    private val clock: Clock = Clock.systemUTC(),
) : AnalyticsQueryBackend {
    private val compiler = MongoAnalyticsQueryCompiler(binding)
    private val mapper = MongoAnalyticsResultMapper(binding)
    private val collation = when (binding.collationMode) {
        MongoCollationMode.SIMPLE_BINARY -> Collation.builder().locale("simple").build()
    }

    init {
        require(binding.documentKind == QueryDocumentKind.SNAPSHOT) {
            "Mongo analytics supports Snapshot documents only."
        }
    }

    override fun analyze(
        plan: BackendAnalyticsQueryPlan,
        options: QueryBackendExecutionOptions,
    ): Mono<BackendAnalyticsPage> = Mono.defer {
        validateOptions(plan, options)
        val compiled = compiler.compile(plan)
        var publisher = collection.aggregate(compiled.pipeline)
            .collation(collation)
            .allowDiskUse(options.allowDiskUse)
        options.remainingMillis()?.let { remaining ->
            publisher = publisher.maxTime(remaining, TimeUnit.MILLISECONDS)
        }
        Flux.from(publisher).collectList().map { documents ->
            mapPage(plan, compiled, documents)
        }
    }.onErrorMap(::mapBackendError)

    private fun validateOptions(plan: BackendAnalyticsQueryPlan, options: QueryBackendExecutionOptions) {
        if (options.maxScannedRecords != null ||
            options.maxCandidateBuckets != null ||
            options.maxCursorPages != null
        ) {
            unsupportedBudget()
        }
        options.maxReturnedBuckets?.let { maximum ->
            if (plan.bucketWindow.limit > maximum) {
                exceededBudget()
            }
        }
        options.remainingMillis()
    }

    private fun mapPage(
        plan: BackendAnalyticsQueryPlan,
        compiled: MongoCompiledAnalyticsQuery,
        documents: List<Document>,
    ): BackendAnalyticsPage {
        if (documents.size > compiled.resultLimit) {
            mappingFailure()
        }
        val mapped = documents.map { document -> mapper.map(document, plan) }.toMutableList()
        if (mapped.isEmpty() && plan.grouping == BackendAnalyticsGrouping.Global) {
            mapped += mapper.emptyGlobal(plan)
        }
        val hasMore = mapped.size > plan.bucketWindow.limit
        val buckets = mapped.take(plan.bucketWindow.limit)
        val afterKey = if (hasMore) {
            val dimensions = (plan.grouping as? BackendAnalyticsGrouping.By)?.dimensions ?: mappingFailure()
            val last = buckets.lastOrNull() ?: mappingFailure()
            dimensions.map { dimension -> last.keys[dimension.alias] ?: mappingFailure() }
        } else {
            null
        }
        return BackendAnalyticsPage(
            buckets,
            afterKey,
            BackendAnalyticsConsistency.EVENTUAL,
            BackendAnalyticsCompleteness.EXACT,
        )
    }

    private fun QueryBackendExecutionOptions.remainingMillis(): Long? {
        val currentDeadline = deadline ?: return null
        val now = clock.instant()
        val remaining = try {
            Duration.between(now, currentDeadline).toMillis()
        } catch (error: ArithmeticException) {
            if (!currentDeadline.isAfter(now)) {
                throw QueryBackendException(
                    QueryBackendFailureKind.TIMEOUT,
                    error,
                )
            }
            Long.MAX_VALUE
        }
        if (remaining <= 0) {
            throw QueryBackendException(QueryBackendFailureKind.TIMEOUT)
        }
        return remaining
    }

    private fun mapBackendError(error: Throwable): Throwable =
        if (error is QueryBackendException) error else QueryBackendException(QueryBackendFailureKind.UNAVAILABLE, error)

    private fun unsupportedBudget(): Nothing = throw QueryBackendException(QueryBackendFailureKind.UNSUPPORTED)

    private fun exceededBudget(): Nothing = throw QueryBackendException(QueryBackendFailureKind.BUDGET_EXCEEDED)
}

internal class MongoAnalyticsResultMapper(
    private val binding: MongoPreparedQueryBinding,
) {
    fun map(source: Document, plan: BackendAnalyticsQueryPlan): BackendAnalyticsBucket = mapFailures {
        val keys = when (val grouping = plan.grouping) {
            BackendAnalyticsGrouping.Global -> {
                if (source[GROUP_ID] != null) mappingFailure()
                emptyMap()
            }

            is BackendAnalyticsGrouping.By -> {
                val id = source[GROUP_ID] as? Map<*, *> ?: mappingFailure()
                LinkedHashMap<AnalyticsAlias, NormalizedValue>(grouping.dimensions.size).also { values ->
                    grouping.dimensions.forEach { dimension ->
                        if (!id.containsKey(dimension.alias.value)) mappingFailure()
                        values[dimension.alias] = mapFieldValue(dimension.field, id[dimension.alias.value])
                    }
                }
            }
        }
        val metrics = LinkedHashMap<AnalyticsAlias, NormalizedValue>(plan.metrics.size)
        plan.metrics.forEach { metric ->
            metrics[metric.alias] = mapMetric(metric, source[metric.alias.value], plan.numericPolicy)
        }
        BackendAnalyticsBucket(keys, metrics)
    }

    fun emptyGlobal(plan: BackendAnalyticsQueryPlan): BackendAnalyticsBucket = mapFailures {
        if (plan.grouping != BackendAnalyticsGrouping.Global) mappingFailure()
        val metrics = LinkedHashMap<AnalyticsAlias, NormalizedValue>(plan.metrics.size)
        plan.metrics.forEach { metric ->
            metrics[metric.alias] = when (metric) {
                is BackendAnalyticsMetric.DocumentCount -> NormalizedValue.Int64(0)
                is BackendAnalyticsMetric.Sum -> normalizeNumeric(BigDecimal.ZERO, requireNotNull(plan.numericPolicy))
                is BackendAnalyticsMetric.Min,
                is BackendAnalyticsMetric.Max,
                is BackendAnalyticsMetric.Average,
                -> NormalizedValue.Null
            }
        }
        BackendAnalyticsBucket(emptyMap(), metrics)
    }

    private fun mapMetric(
        metric: BackendAnalyticsMetric,
        raw: Any?,
        numericPolicy: BackendAnalyticsNumericPolicy?,
    ): NormalizedValue = when (metric) {
        is BackendAnalyticsMetric.DocumentCount -> {
            val value = raw.toExactLong()
            if (value < 0) mappingFailure()
            NormalizedValue.Int64(value)
        }

        is BackendAnalyticsMetric.Min -> mapMetricField(metric.field, raw, numericPolicy)
        is BackendAnalyticsMetric.Max -> mapMetricField(metric.field, raw, numericPolicy)
        is BackendAnalyticsMetric.Sum -> normalizeNumeric(raw.toBigDecimalExact(), requireNotNull(numericPolicy))
        is BackendAnalyticsMetric.Average -> if (raw == null) {
            NormalizedValue.Null
        } else {
            normalizeNumeric(raw.toBigDecimalExact(), requireNotNull(numericPolicy))
        }
    }

    private fun mapMetricField(
        field: QueryFieldId,
        raw: Any?,
        numericPolicy: BackendAnalyticsNumericPolicy?,
    ): NormalizedValue {
        if (raw == null) {
            return NormalizedValue.Null
        }
        val type = requireNotNull(binding.schema.fields[field]).type
        return when (type) {
            LogicalFieldType.Int64,
            LogicalFieldType.Decimal,
            -> normalizeNumeric(raw.toBigDecimalExact(), requireNotNull(numericPolicy))

            LogicalFieldType.Instant -> mapFieldValue(field, raw)
            else -> mappingFailure()
        }
    }

    private fun mapFieldValue(field: QueryFieldId, raw: Any?): NormalizedValue {
        if (raw == null) {
            return NormalizedValue.Null
        }
        return when (requireNotNull(binding.schema.fields[field]).type) {
            LogicalFieldType.Text -> NormalizedValue.Text(raw as? String ?: mappingFailure())
            LogicalFieldType.Boolean -> NormalizedValue.BooleanValue(raw as? Boolean ?: mappingFailure())
            LogicalFieldType.Int64 -> NormalizedValue.Int64(raw.toExactLong())
            LogicalFieldType.Decimal -> NormalizedValue.Decimal(raw.toBigDecimalExact())
            LogicalFieldType.Instant -> NormalizedValue.InstantValue(
                Instant.ofEpochMilli(raw.toExactLong()),
            )

            LogicalFieldType.Bytes,
            LogicalFieldType.Object,
            is LogicalFieldType.Array,
            -> mappingFailure()
        }
    }

    private fun normalizeNumeric(
        value: BigDecimal,
        policy: BackendAnalyticsNumericPolicy,
    ): NormalizedValue.Decimal {
        val normalized = value.setScale(policy.scale, policy.roundingMode)
        if (normalized.precision() > policy.precision) {
            mappingFailure()
        }
        try {
            Decimal128(normalized)
        } catch (error: NumberFormatException) {
            throw QueryBackendException(QueryBackendFailureKind.MAPPING_FAILURE, error)
        }
        return NormalizedValue.Decimal(normalized)
    }

    private fun Any?.toExactLong(): Long = when (this) {
        is Byte -> toLong()
        is Short -> toLong()
        is Int -> toLong()
        is Long -> this
        is BigInteger -> longValueExact()
        is BigDecimal -> longValueExact()
        is Decimal128 -> bigDecimalValue().longValueExact()
        else -> mappingFailure()
    }

    private fun Any?.toBigDecimalExact(): BigDecimal = when (this) {
        is Decimal128 -> bigDecimalValue()
        is BigDecimal -> this
        is BigInteger -> toBigDecimal()
        is Byte -> BigDecimal.valueOf(toLong())
        is Short -> BigDecimal.valueOf(toLong())
        is Int -> toBigDecimal()
        is Long -> toBigDecimal()
        is Float -> if (isFinite()) BigDecimal.valueOf(toDouble()) else mappingFailure()
        is Double -> if (isFinite()) BigDecimal.valueOf(this) else mappingFailure()
        else -> mappingFailure()
    }

    @Suppress("TooGenericExceptionCaught")
    private inline fun <T> mapFailures(block: () -> T): T = try {
        block()
    } catch (error: QueryBackendException) {
        throw error
    } catch (error: RuntimeException) {
        throw QueryBackendException(QueryBackendFailureKind.MAPPING_FAILURE, error)
    }

    private companion object {
        const val GROUP_ID = "_id"
    }
}

private fun mappingFailure(): Nothing = throw QueryBackendException(QueryBackendFailureKind.MAPPING_FAILURE)
