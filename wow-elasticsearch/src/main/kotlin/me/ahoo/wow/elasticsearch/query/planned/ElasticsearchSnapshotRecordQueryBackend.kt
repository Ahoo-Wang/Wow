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

import co.elastic.clients.elasticsearch._types.ShardStatistics
import co.elastic.clients.elasticsearch.core.CountRequest
import co.elastic.clients.elasticsearch.core.SearchRequest
import co.elastic.clients.elasticsearch.core.search.Hit
import co.elastic.clients.elasticsearch.core.search.ResponseBody
import me.ahoo.wow.query.backend.BackendCountQueryPlan
import me.ahoo.wow.query.backend.BackendPage
import me.ahoo.wow.query.backend.BackendPageQueryPlan
import me.ahoo.wow.query.backend.BackendProjection
import me.ahoo.wow.query.backend.BackendRecord
import me.ahoo.wow.query.backend.BackendRecordCompleteness
import me.ahoo.wow.query.backend.BackendRecordQueryPlan
import me.ahoo.wow.query.backend.BackendSingleQueryPlan
import me.ahoo.wow.query.backend.BackendStreamQueryPlan
import me.ahoo.wow.query.backend.LogicalFieldType
import me.ahoo.wow.query.backend.NormalizedValue
import me.ahoo.wow.query.backend.QueryBackendException
import me.ahoo.wow.query.backend.QueryBackendExecutionOptions
import me.ahoo.wow.query.backend.QueryBackendFailureKind
import me.ahoo.wow.query.backend.QueryFieldId
import me.ahoo.wow.query.backend.RecordQueryBackend
import me.ahoo.wow.query.backend.SystemFieldKind
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.serialization.state.StateAggregateRecords
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.IdentityHashMap
import java.util.LinkedHashMap

internal class ElasticsearchSnapshotRecordQueryBackend(
    private val client: ReactiveElasticsearchClient,
    private val binding: ElasticsearchPreparedQueryBinding,
    private val clock: Clock = Clock.systemUTC(),
) : RecordQueryBackend {
    private val compiler = ElasticsearchRecordQueryCompiler(binding)
    private val mapper = ElasticsearchSnapshotRecordMapper(binding)
    private val pitPageExecutor = ElasticsearchPitPageExecutor(client, binding, compiler, mapper, clock)

    override fun single(
        plan: BackendSingleQueryPlan,
        options: QueryBackendExecutionOptions,
    ): Mono<BackendRecord> = Mono.defer {
        validateOptions(plan, options)
        val compiled = compiler.compile(plan)
        client.search(searchRequest(compiled, options, 0, 1, trackTotalHits = false), Map::class.java)
            .flatMap { response ->
                validateResponse(response)
                if (response.hits().hits().size > 1) incomplete()
                val hit = response.hits().hits().singleOrNull()
                if (hit == null) Mono.empty() else Mono.just(hit.toRecord(plan.projection))
            }
    }.mapBackendErrors()

    override fun stream(
        plan: BackendStreamQueryPlan,
        options: QueryBackendExecutionOptions,
    ): Flux<BackendRecord> = Flux.defer {
        validateOptions(plan, options)
        val compiled = compiler.compile(plan)
        client.search(
            searchRequest(compiled, options, 0, plan.limit, trackTotalHits = false),
            Map::class.java,
        ).flatMapMany { response ->
            validateResponse(response)
            if (response.hits().hits().size > plan.limit) incomplete()
            Flux.fromIterable(response.hits().hits()).map { hit -> hit.toRecord(plan.projection) }
        }
    }.mapBackendErrors()

    override fun page(
        plan: BackendPageQueryPlan,
        options: QueryBackendExecutionOptions,
    ): Mono<BackendPage> = Mono.defer {
        validateOptions(plan, options)
        pitPageExecutor.execute(plan, options)
    }.mapBackendErrors()

    override fun count(
        plan: BackendCountQueryPlan,
        options: QueryBackendExecutionOptions,
    ): Mono<Long> = Mono.defer {
        validateOptions(plan, options)
        val compiled = compiler.compile(plan)
        val request = CountRequest.of { builder ->
            builder.index(binding.indexName).query(compiled.query)
        }
        client.count(request).map { response ->
            requireCompleteShards(response.shards())
            response.count()
        }
    }.mapBackendErrors()

    private fun searchRequest(
        compiled: ElasticsearchCompiledRecordQuery,
        options: QueryBackendExecutionOptions,
        from: Int,
        size: Int,
        trackTotalHits: Boolean,
    ): SearchRequest = SearchRequest.of { builder ->
        builder.index(binding.indexName)
            .query(compiled.query)
            .from(from)
            .size(size)
            .allowPartialSearchResults(false)
            .trackTotalHits { hits -> hits.enabled(trackTotalHits) }
            .also { request ->
                if (compiled.sort.isNotEmpty()) request.sort(compiled.sort)
                compiled.sourceFilter?.let { sourceFilter ->
                    request.source { source -> source.filter(sourceFilter) }
                }
                options.remainingMillis()?.let { remaining -> request.timeout("${remaining}ms") }
            }
    }

    private fun validateOptions(plan: BackendRecordQueryPlan, options: QueryBackendExecutionOptions) {
        if (plan is BackendStreamQueryPlan && plan.limit > MAX_DIRECT_STREAM_RESULT_WINDOW) {
            unsupported()
        }
        requireSupportedOptions(options)
        requireReturnedRecordsBudget(plan, options)
        requirePageWindowBudget(plan, options)
        options.remainingMillis()
    }

    private fun requireSupportedOptions(options: QueryBackendExecutionOptions) {
        val unsupportedBudgets = listOf(
            options.maxScannedRecords,
            options.maxCandidateBuckets,
            options.maxReturnedBuckets,
        )
        if (unsupportedBudgets.any { budget -> budget != null }) {
            unsupported()
        }
    }

    private fun requireReturnedRecordsBudget(
        plan: BackendRecordQueryPlan,
        options: QueryBackendExecutionOptions,
    ) {
        options.maxReturnedRecords?.let { maximum ->
            val requested = when (plan) {
                is BackendSingleQueryPlan -> 1L
                is BackendStreamQueryPlan -> plan.limit.toLong()
                is BackendPageQueryPlan -> plan.page.size.toLong()
                is BackendCountQueryPlan -> 0L
            }
            if (requested > maximum) budgetExceeded()
        }
    }

    private fun requirePageWindowBudget(
        plan: BackendRecordQueryPlan,
        options: QueryBackendExecutionOptions,
    ) {
        if (plan !is BackendPageQueryPlan) {
            return
        }
        val endExclusive = try {
            Math.addExact(plan.page.offset, plan.page.size.toLong())
        } catch (error: ArithmeticException) {
            throw QueryBackendException(QueryBackendFailureKind.BUDGET_EXCEEDED, error)
        }
        options.maxPageWindow?.let { maximum ->
            if (endExclusive > maximum) {
                budgetExceeded()
            }
        }
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
                throw QueryBackendException(QueryBackendFailureKind.TIMEOUT, error)
            }
        }
        if (remaining <= 0) throw QueryBackendException(QueryBackendFailureKind.TIMEOUT)
        return remaining
    }

    private fun validateResponse(response: ResponseBody<*>) {
        if (response.timedOut()) throw QueryBackendException(QueryBackendFailureKind.TIMEOUT)
        requireCompleteShards(response.shards())
    }

    private fun requireCompleteShards(shards: ShardStatistics) {
        if (shards.failed().toLong() != 0L) incomplete()
    }

    @Suppress("UNCHECKED_CAST")
    private fun Hit<*>.toRecord(projection: BackendProjection): BackendRecord {
        if (ignored().isNotEmpty()) incomplete()
        val source = source() as? Map<String, Any?> ?: incomplete()
        val identity = id() ?: incomplete()
        return mapper.map(identity, source, projection)
    }

    private fun <T : Any> Mono<T>.mapBackendErrors(): Mono<T> = onErrorMap(::mapBackendError)

    private fun <T : Any> Flux<T>.mapBackendErrors(): Flux<T> = onErrorMap(::mapBackendError)

    private fun mapBackendError(error: Throwable): Throwable =
        if (error is QueryBackendException) error else QueryBackendException(QueryBackendFailureKind.UNAVAILABLE, error)

    private fun unsupported(): Nothing = throw QueryBackendException(QueryBackendFailureKind.UNSUPPORTED)

    private fun budgetExceeded(): Nothing = throw QueryBackendException(QueryBackendFailureKind.BUDGET_EXCEEDED)

    private companion object {
        const val MAX_DIRECT_STREAM_RESULT_WINDOW = 10_000
    }
}

internal class ElasticsearchSnapshotRecordMapper(
    private val binding: ElasticsearchPreparedQueryBinding,
    private val identityOutputField: String = MessageRecords.AGGREGATE_ID,
    private val limits: MappingLimits = MappingLimits(),
) {
    @Suppress("TooGenericExceptionCaught")
    fun map(
        hitIdentity: String,
        source: Map<String, Any?>,
        projection: BackendProjection = BackendProjection.All,
    ): BackendRecord = try {
        val frozen = MappingSession(limits).objectValue(source, 0)
        val sourceIdentity = (frozen.values[identityOutputField] as? NormalizedValue.Text)?.value ?: mappingFailure()
        if (hitIdentity.isBlank() || sourceIdentity != hitIdentity) mappingFailure()
        val logical = frozen.toLogicalDocument()
        BackendRecord(
            hitIdentity,
            logical.apply(projection, identityOutputField),
            BackendRecordCompleteness.COMPLETE,
        )
    } catch (error: QueryBackendException) {
        throw error
    } catch (error: RuntimeException) {
        throw QueryBackendException(QueryBackendFailureKind.MAPPING_FAILURE, error)
    }

    private fun NormalizedValue.ObjectValue.toLogicalDocument(): NormalizedValue.ObjectValue {
        val paths = binding.schema.fields.keys.filterIsInstance<QueryFieldId.Path>().map(QueryFieldId.Path::segments)
        var logical = include(paths)
        binding.schema.fields.filterKeys { field -> field is QueryFieldId.Path }.forEach { (field, schemaField) ->
            field as QueryFieldId.Path
            logical = logical.transformAt(field.segments) { value ->
                decodeValue(value, schemaField.type, binding.fields.getValue(field).valueEncoding)
            }
        }
        val values = LinkedHashMap(logical.values)
        binding.schema.fields.filterKeys { field -> field is QueryFieldId.System }.forEach { (field, schemaField) ->
            field as QueryFieldId.System
            val physical = binding.fields.getValue(field)
            val sourcePath = physical.sourceField.split('.')
            valueAt(sourcePath)?.let { value ->
                values[field.outputPath(identityOutputField).single()] =
                    decodeValue(value, schemaField.type, physical.valueEncoding)
            }
        }
        return NormalizedValue.ObjectValue(values)
    }

    private fun NormalizedValue.ObjectValue.transformAt(
        path: List<String>,
        transform: (NormalizedValue) -> NormalizedValue,
    ): NormalizedValue.ObjectValue {
        val head = path.firstOrNull() ?: mappingFailure()
        val current = values[head] ?: return this
        val copy = LinkedHashMap(values)
        copy[head] = current.transformAt(path.drop(1), transform)
        return NormalizedValue.ObjectValue(copy)
    }

    private fun NormalizedValue.transformAt(
        path: List<String>,
        transform: (NormalizedValue) -> NormalizedValue,
    ): NormalizedValue = when {
        path.isEmpty() -> transform(this)
        this == NormalizedValue.Null -> this
        this is NormalizedValue.ObjectValue -> transformAt(path, transform)
        this is NormalizedValue.ListValue -> NormalizedValue.ListValue(
            values.map { value -> value.transformAt(path, transform) },
        )
        else -> mappingFailure()
    }

    private fun decodeValue(
        value: NormalizedValue,
        type: LogicalFieldType,
        encoding: ElasticsearchValueEncoding,
    ): NormalizedValue {
        if (value == NormalizedValue.Null) return value
        return when (type) {
            LogicalFieldType.Instant -> {
                if (encoding != ElasticsearchValueEncoding.EPOCH_MILLIS || value !is NormalizedValue.Int64) {
                    mappingFailure()
                }
                NormalizedValue.InstantValue(Instant.ofEpochMilli(value.value))
            }

            is LogicalFieldType.Array -> {
                val list = value as? NormalizedValue.ListValue ?: mappingFailure()
                NormalizedValue.ListValue(
                    list.values.map { element ->
                        decodeValue(element, type.elementType, encoding)
                    },
                )
            }

            else -> value
        }
    }

    private fun NormalizedValue.ObjectValue.valueAt(path: List<String>): NormalizedValue? {
        var current: NormalizedValue = this
        path.forEach { segment ->
            current = (current as? NormalizedValue.ObjectValue)?.values?.get(segment) ?: return null
        }
        return current
    }

    internal data class MappingLimits(
        val maxDepth: Int = 32,
        val maxNodes: Int = 100_000,
        val maxCollectionSize: Int = 100_000,
    ) {
        init {
            require(maxDepth > 0 && maxNodes > 0 && maxCollectionSize > 0)
        }
    }

    private class MappingSession(private val limits: MappingLimits) {
        private val active = IdentityHashMap<Any, Unit>()
        private var nodes = 0

        fun objectValue(source: Map<*, *>, depth: Int): NormalizedValue.ObjectValue =
            withContainer(source, depth) {
                val values = LinkedHashMap<String, NormalizedValue>()
                var count = 0
                source.entries.forEach { entry ->
                    if (++count > limits.maxCollectionSize) mappingFailure()
                    val key = entry.key as? String ?: mappingFailure()
                    if (values.containsKey(key)) mappingFailure()
                    values[key] = value(entry.value, depth + 1)
                }
                NormalizedValue.ObjectValue(values)
            }

        private fun value(source: Any?, depth: Int): NormalizedValue =
            when (source) {
                is Map<*, *> -> objectValue(source, depth)
                is Iterable<*> -> listValue(source, depth)
                is Array<*> -> listValue(source.asIterable(), depth)
                else -> scalarValue(source, depth)
            }

        private fun scalarValue(source: Any?, depth: Int): NormalizedValue {
            enterNode(depth)
            return when (source) {
                null -> NormalizedValue.Null
                is Boolean -> NormalizedValue.BooleanValue(source)
                is String -> NormalizedValue.Text(source)
                is Number -> numberValue(source)
                is Instant -> NormalizedValue.InstantValue(source)
                is ByteArray -> NormalizedValue.Bytes(source)
                else -> mappingFailure()
            }
        }

        private fun numberValue(source: Number): NormalizedValue = when (source) {
            is Byte, is Short, is Int, is Long -> NormalizedValue.Int64(source.toLong())
            is BigInteger -> try {
                NormalizedValue.Int64(source.longValueExact())
            } catch (_: ArithmeticException) {
                NormalizedValue.Decimal(BigDecimal(source))
            }

            is BigDecimal -> NormalizedValue.Decimal(source)
            is Float, is Double -> {
                val value = source.toDouble()
                if (!value.isFinite()) mappingFailure()
                NormalizedValue.Decimal(BigDecimal.valueOf(value))
            }

            else -> mappingFailure()
        }

        private fun listValue(source: Iterable<*>, depth: Int): NormalizedValue.ListValue =
            withContainer(source, depth) {
                val values = ArrayList<NormalizedValue>()
                val iterator = source.iterator()
                while (iterator.hasNext()) {
                    if (values.size >= limits.maxCollectionSize) mappingFailure()
                    values += value(iterator.next(), depth + 1)
                }
                NormalizedValue.ListValue(values)
            }

        private fun enterNode(depth: Int) {
            if (depth > limits.maxDepth || ++nodes > limits.maxNodes) mappingFailure()
        }

        private fun <T> withContainer(source: Any, depth: Int, block: () -> T): T {
            enterNode(depth)
            if (active.put(source, Unit) != null) mappingFailure()
            return try {
                block()
            } finally {
                active.remove(source)
            }
        }
    }
}

private fun NormalizedValue.ObjectValue.apply(
    projection: BackendProjection,
    identityOutputField: String,
): NormalizedValue.ObjectValue =
    when (projection) {
        BackendProjection.All -> this
        is BackendProjection.Include -> include(
            projection.fields.map { field ->
                field.outputPath(identityOutputField)
            },
        )
        is BackendProjection.Exclude -> exclude(
            projection.fields.map { field ->
                field.outputPath(identityOutputField)
            },
        )
    }

private fun QueryFieldId.outputPath(identityOutputField: String): List<String> =
    when (this) {
        is QueryFieldId.Path -> segments
        is QueryFieldId.System -> listOf(
            when (kind) {
                SystemFieldKind.IDENTITY -> identityOutputField
                SystemFieldKind.AGGREGATE_ID -> MessageRecords.AGGREGATE_ID
                SystemFieldKind.TENANT_ID -> MessageRecords.TENANT_ID
                SystemFieldKind.OWNER_ID -> MessageRecords.OWNER_ID
                SystemFieldKind.SPACE_ID -> MessageRecords.SPACE_ID
                SystemFieldKind.DELETED -> StateAggregateRecords.DELETED
            },
        )
    }

private fun NormalizedValue.ObjectValue.include(paths: List<List<String>>): NormalizedValue.ObjectValue {
    val result = LinkedHashMap<String, NormalizedValue>()
    values.forEach { (key, value) ->
        val matching = paths.filter { path -> path.firstOrNull() == key }
        if (matching.any { path -> path.size == 1 }) {
            result[key] = value
        } else if (matching.isNotEmpty()) {
            result[key] = value.includeNested(matching.map { path -> path.drop(1) })
        }
    }
    return NormalizedValue.ObjectValue(result)
}

private fun NormalizedValue.includeNested(paths: List<List<String>>): NormalizedValue =
    when (this) {
        NormalizedValue.Null -> NormalizedValue.Null
        is NormalizedValue.ObjectValue -> include(paths)
        is NormalizedValue.ListValue -> NormalizedValue.ListValue(values.map { value -> value.includeNested(paths) })
        else -> mappingFailure()
    }

private fun NormalizedValue.ObjectValue.exclude(paths: List<List<String>>): NormalizedValue.ObjectValue {
    val result = LinkedHashMap<String, NormalizedValue>()
    values.forEach { (key, value) ->
        val matching = paths.filter { path -> path.firstOrNull() == key }
        if (matching.none { path -> path.size == 1 }) {
            val nested = matching.filter { path -> path.size > 1 }.map { path -> path.drop(1) }
            result[key] = if (nested.isEmpty()) value else value.excludeNested(nested)
        }
    }
    return NormalizedValue.ObjectValue(result)
}

private fun NormalizedValue.excludeNested(paths: List<List<String>>): NormalizedValue =
    when (this) {
        is NormalizedValue.ObjectValue -> exclude(paths)
        is NormalizedValue.ListValue -> NormalizedValue.ListValue(values.map { value -> value.excludeNested(paths) })
        else -> this
    }

private fun incomplete(): Nothing = throw QueryBackendException(QueryBackendFailureKind.INCOMPLETE_RESULT)

private fun mappingFailure(): Nothing = throw QueryBackendException(QueryBackendFailureKind.MAPPING_FAILURE)
