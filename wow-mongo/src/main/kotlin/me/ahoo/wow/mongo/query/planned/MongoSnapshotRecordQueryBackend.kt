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

package me.ahoo.wow.mongo.query.planned

import com.mongodb.client.model.Collation
import com.mongodb.client.model.CountOptions
import com.mongodb.reactivestreams.client.FindPublisher
import com.mongodb.reactivestreams.client.MongoCollection
import me.ahoo.wow.mongo.Documents
import me.ahoo.wow.query.backend.BackendCountQueryPlan
import me.ahoo.wow.query.backend.BackendPage
import me.ahoo.wow.query.backend.BackendPageConsistency
import me.ahoo.wow.query.backend.BackendPageQueryPlan
import me.ahoo.wow.query.backend.BackendProjection
import me.ahoo.wow.query.backend.BackendRecord
import me.ahoo.wow.query.backend.BackendRecordCompleteness
import me.ahoo.wow.query.backend.BackendRecordQueryPlan
import me.ahoo.wow.query.backend.BackendSingleQueryPlan
import me.ahoo.wow.query.backend.BackendStreamQueryPlan
import me.ahoo.wow.query.backend.BackendTotalRelation
import me.ahoo.wow.query.backend.NormalizedValue
import me.ahoo.wow.query.backend.QueryBackendException
import me.ahoo.wow.query.backend.QueryBackendExecutionOptions
import me.ahoo.wow.query.backend.QueryBackendFailureKind
import me.ahoo.wow.query.backend.QueryFieldId
import me.ahoo.wow.query.backend.RecordQueryBackend
import me.ahoo.wow.query.backend.SystemFieldKind
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.serialization.state.StateAggregateRecords
import org.bson.Document
import org.bson.types.Binary
import org.bson.types.Decimal128
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Date
import java.util.IdentityHashMap
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit

internal class MongoRecordQueryBackend(
    private val collection: MongoCollection<Document>,
    private val binding: MongoPreparedQueryBinding,
    private val clock: Clock = Clock.systemUTC(),
) : RecordQueryBackend {
    private val compiler = MongoRecordQueryCompiler(binding)
    private val mapper = MongoRecordMapper(binding)
    private val collation = when (binding.collationMode) {
        MongoCollationMode.SIMPLE_BINARY -> Collation.builder().locale("simple").build()
    }

    override fun single(
        plan: BackendSingleQueryPlan,
        options: QueryBackendExecutionOptions,
    ): Mono<BackendRecord> = Mono.defer {
        validateOptions(plan, options)
        val query = compiler.compile(plan)
        Mono.from(applyQuery(collection.find(query.filter), query, options).first())
            .map { source -> mapper.map(source, plan.projection) }
    }.mapBackendErrors()

    override fun stream(
        plan: BackendStreamQueryPlan,
        options: QueryBackendExecutionOptions,
    ): Flux<BackendRecord> = Flux.defer {
        validateOptions(plan, options)
        val query = compiler.compile(plan)
        Flux.from(applyQuery(collection.find(query.filter), query, options))
            .map { source -> mapper.map(source, plan.projection) }
    }.mapBackendErrors()

    override fun page(
        plan: BackendPageQueryPlan,
        options: QueryBackendExecutionOptions,
    ): Mono<BackendPage> = Mono.defer {
        validateOptions(plan, options)
        val query = compiler.compile(plan)
        var publisher = collection.aggregate(query.pagePipeline())
            .collation(collation)
            .allowDiskUse(options.allowDiskUse)
        options.remainingMillis()?.let { remaining ->
            publisher = publisher.maxTime(remaining, TimeUnit.MILLISECONDS)
        }
        Mono.from(publisher.first())
            .map { result -> result.toPage(plan.projection) }
    }.mapBackendErrors()

    override fun count(
        plan: BackendCountQueryPlan,
        options: QueryBackendExecutionOptions,
    ): Mono<Long> = Mono.defer {
        validateOptions(plan, options)
        val countOptions = CountOptions().collation(collation)
        options.remainingMillis()?.let { remaining -> countOptions.maxTime(remaining, TimeUnit.MILLISECONDS) }
        Mono.from(
            collection.countDocuments(
                compiler.compile(plan).filter,
                countOptions,
            ),
        )
    }.mapBackendErrors()

    private fun applyQuery(
        source: FindPublisher<Document>,
        query: MongoCompiledRecordQuery,
        options: QueryBackendExecutionOptions,
    ): FindPublisher<Document> {
        var result = source.collation(collation).allowDiskUse(options.allowDiskUse)
        options.remainingMillis()?.let { remaining ->
            result = result.maxTime(remaining, TimeUnit.MILLISECONDS)
        }
        query.projection?.let { projection -> result = result.projection(projection) }
        query.sort?.let { sort -> result = result.sort(sort) }
        query.limit?.let { limit -> result = result.limit(limit) }
        return result
    }

    private fun validateOptions(plan: BackendRecordQueryPlan, options: QueryBackendExecutionOptions) {
        requireSupportedRecordBudget(options)
        validateReturnedBudget(plan, options)
        if (plan is BackendPageQueryPlan) {
            validatePageBudget(plan, options)
        }
        options.remainingMillis()
    }

    private fun requireSupportedRecordBudget(options: QueryBackendExecutionOptions) {
        val unsupported = listOfNotNull(
            options.maxScannedRecords,
            options.maxCandidateBuckets,
            options.maxReturnedBuckets,
            options.maxCursorPages,
        )
        if (unsupported.isNotEmpty()) {
            unsupportedBudget()
        }
    }

    private fun validateReturnedBudget(plan: BackendRecordQueryPlan, options: QueryBackendExecutionOptions) {
        options.maxReturnedRecords?.let { maximum ->
            val requested = when (plan) {
                is BackendSingleQueryPlan -> 1L
                is BackendStreamQueryPlan -> plan.limit.toLong()
                is BackendPageQueryPlan -> plan.page.size.toLong()
                is BackendCountQueryPlan -> 0L
            }
            if (requested > maximum) {
                exceededBudget()
            }
        }
    }

    private fun validatePageBudget(plan: BackendPageQueryPlan, options: QueryBackendExecutionOptions) {
        options.maxPageWindow?.let { maximum ->
            val endExclusive = try {
                Math.addExact(plan.page.offset, plan.page.size.toLong())
            } catch (error: ArithmeticException) {
                throw QueryBackendException(QueryBackendFailureKind.BUDGET_EXCEEDED, error)
            }
            if (endExclusive > maximum) {
                exceededBudget()
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
                throw QueryBackendException(
                    QueryBackendFailureKind.TIMEOUT,
                    error,
                )
            }
        }
        if (remaining <= 0) {
            throw QueryBackendException(QueryBackendFailureKind.TIMEOUT)
        }
        return remaining
    }

    private fun unsupportedBudget(): Nothing = throw QueryBackendException(QueryBackendFailureKind.UNSUPPORTED)

    private fun exceededBudget(): Nothing = throw QueryBackendException(QueryBackendFailureKind.BUDGET_EXCEEDED)

    private fun <T : Any> Mono<T>.mapBackendErrors(): Mono<T> = onErrorMap(::mapBackendError)

    private fun <T : Any> Flux<T>.mapBackendErrors(): Flux<T> = onErrorMap(::mapBackendError)

    private fun mapBackendError(error: Throwable): Throwable =
        if (error is QueryBackendException) error else QueryBackendException(QueryBackendFailureKind.UNAVAILABLE, error)

    private fun Document.toPage(projection: BackendProjection): BackendPage {
        val records = getList(MongoCompiledRecordQuery.PAGE_RECORDS, Document::class.java)
            ?.map { source -> mapper.map(source, projection) }
            ?: mappingFailure()
        val totalEntries = getList(MongoCompiledRecordQuery.PAGE_TOTAL, Document::class.java) ?: mappingFailure()
        val total = when (totalEntries.size) {
            0 -> 0L
            1 -> (totalEntries.single()[MongoCompiledRecordQuery.PAGE_TOTAL_VALUE] as? Number)?.toLong()
                ?: mappingFailure()
            else -> mappingFailure()
        }
        return BackendPage(
            records,
            total,
            BackendTotalRelation.EXACT,
            BackendPageConsistency.SAME_INPUT,
        )
    }
}

internal class MongoRecordMapper(
    private val binding: MongoPreparedQueryBinding,
    private val limits: MappingLimits = MappingLimits(),
) {
    @Suppress("TooGenericExceptionCaught")
    fun map(
        source: Document,
        projection: BackendProjection = BackendProjection.All,
    ): BackendRecord = try {
        mapResult(source, projection)
    } catch (error: QueryBackendException) {
        throw error
    } catch (error: RuntimeException) {
        throw QueryBackendException(QueryBackendFailureKind.MAPPING_FAILURE, error)
    }

    private fun mapResult(source: Document, projection: BackendProjection): BackendRecord {
        val session = MappingSession(limits)
        val frozen = session.objectValue(source, 0)
        val identity = (frozen.values[Documents.ID_FIELD] as? NormalizedValue.Text)?.value
            ?: mappingFailure()
        val document = frozen.toLogicalDocument().apply(projection, binding.identityOutputField)
        return BackendRecord(
            identity,
            document,
            BackendRecordCompleteness.COMPLETE,
        )
    }

    private fun NormalizedValue.ObjectValue.toLogicalDocument(): NormalizedValue.ObjectValue {
        val pathFields = binding.schema.fields.keys.filterIsInstance<QueryFieldId.Path>()
            .map(QueryFieldId.Path::segments)
        val logical = LinkedHashMap(include(pathFields).values)
        binding.schema.fields.keys.filterIsInstance<QueryFieldId.System>().forEach { field ->
            val physicalPath = requireNotNull(binding.fields[field]).path.split('.')
            valueAt(physicalPath)?.let { value ->
                logical[field.outputPath(binding.identityOutputField).single()] = value
            }
        }
        return NormalizedValue.ObjectValue(logical)
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
        private var nodes: Int = 0

        fun objectValue(source: Map<*, *>, depth: Int): NormalizedValue.ObjectValue =
            withContainer(source, depth) {
                val values = LinkedHashMap<String, NormalizedValue>()
                var count = 0
                source.entries.forEach { entry ->
                    count++
                    if (count > limits.maxCollectionSize) {
                        mappingFailure()
                    }
                    val key = entry.key as? String ?: mappingFailure()
                    if (values.containsKey(key)) {
                        mappingFailure()
                    }
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
                is Decimal128 -> NormalizedValue.Decimal(source.bigDecimalValue())
                is Number -> numberValue(source)
                is Instant -> NormalizedValue.InstantValue(source)
                is Date -> NormalizedValue.InstantValue(source.toInstant())
                is ByteArray -> NormalizedValue.Bytes(source)
                is Binary -> NormalizedValue.Bytes(source.data)
                else -> mappingFailure()
            }
        }

        private fun numberValue(source: Number): NormalizedValue = when (source) {
            is Byte, is Short, is Int, is Long -> NormalizedValue.Int64(source.toLong())
            is BigInteger -> source.longValueExactOrDecimal()
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
                    if (values.size >= limits.maxCollectionSize) {
                        mappingFailure()
                    }
                    values += value(iterator.next(), depth + 1)
                }
                NormalizedValue.ListValue(values)
            }

        private fun enterNode(depth: Int) {
            if (depth > limits.maxDepth || ++nodes > limits.maxNodes) {
                mappingFailure()
            }
        }

        private fun <T> withContainer(source: Any, depth: Int, block: () -> T): T {
            enterNode(depth)
            if (active.put(source, Unit) != null) {
                mappingFailure()
            }
            return try {
                block()
            } finally {
                active.remove(source)
            }
        }

        private fun BigInteger.longValueExactOrDecimal(): NormalizedValue =
            try {
                NormalizedValue.Int64(longValueExact())
            } catch (_: ArithmeticException) {
                NormalizedValue.Decimal(BigDecimal(this))
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
            projection.fields.map { field -> field.outputPath(identityOutputField) }
        )
        is BackendProjection.Exclude -> exclude(
            projection.fields.map { field -> field.outputPath(identityOutputField) }
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

private fun mappingFailure(): Nothing = throw QueryBackendException(QueryBackendFailureKind.MAPPING_FAILURE)
