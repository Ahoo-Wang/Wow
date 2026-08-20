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

package me.ahoo.wow.mongo.query.gateway

import com.mongodb.client.model.Aggregates
import com.mongodb.client.model.Facet
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Sorts
import com.mongodb.reactivestreams.client.MongoCollection
import com.mongodb.reactivestreams.client.MongoDatabase
import me.ahoo.wow.api.query.ElementMatchExpression
import me.ahoo.wow.api.query.LegacyConditionExpression
import me.ahoo.wow.api.query.LogicalExpression
import me.ahoo.wow.api.query.LogicalField
import me.ahoo.wow.api.query.LogicalOperator
import me.ahoo.wow.api.query.MatchAll
import me.ahoo.wow.api.query.MatchNone
import me.ahoo.wow.api.query.PredicateExpression
import me.ahoo.wow.api.query.PredicateOperator
import me.ahoo.wow.api.query.QueryErrorCode
import me.ahoo.wow.api.query.QueryException
import me.ahoo.wow.api.query.QueryExpression
import me.ahoo.wow.api.query.QueryPage
import me.ahoo.wow.api.query.QuerySortDirection
import me.ahoo.wow.api.query.QueryStage
import me.ahoo.wow.api.query.RelativeTimeExpression
import me.ahoo.wow.api.query.SearchExpression
import me.ahoo.wow.mongo.AggregateSchemaInitializer.toSnapshotCollectionName
import me.ahoo.wow.mongo.Documents
import me.ahoo.wow.mongo.Documents.replacePrimaryKeyToAggregateId
import me.ahoo.wow.mongo.query.snapshot.SnapshotConditionConverter
import me.ahoo.wow.query.backend.QueryBackend
import me.ahoo.wow.query.backend.SecuredQuery
import me.ahoo.wow.query.schema.QuerySchema
import me.ahoo.wow.query.schema.QueryValueKind
import me.ahoo.wow.serialization.JsonSerializer
import org.bson.Document
import org.bson.conversions.Bson
import org.bson.types.Decimal128
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toFlux
import reactor.kotlin.core.publisher.toMono
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import java.time.Instant
import java.util.Base64
import java.util.regex.Pattern
import kotlin.math.min

class MongoSnapshotQueryBackend private constructor(
    private val collectionProvider: (me.ahoo.wow.api.modeling.NamedAggregate) -> MongoCollection<Document>,
    private val batchSize: Int = 256
) : QueryBackend {
    constructor(database: MongoDatabase, batchSize: Int = 256) : this(
        { target -> database.getCollection(target.toSnapshotCollectionName()) },
        batchSize
    )

    internal constructor(collection: MongoCollection<Document>, batchSize: Int = 256) : this(
        { collection },
        batchSize
    )

    override val id: String = ID

    init {
        require(batchSize > 0) { "batchSize must be positive." }
    }

    override fun validate(query: SecuredQuery) {
        if (query.offset > Int.MAX_VALUE) unsupported()
        validateSearchPlacement(query.filter)
    }

    override fun stream(query: SecuredQuery): Flux<ObjectNode> = executionSnapshot(query).flatMapMany { snapshot ->
        val limit = executionLimit(query)
        var publisher = snapshot.collection.find(compile(query.filter, snapshot))
            .sort(compileSort(query))
            .batchSize(min(batchSize, limit ?: batchSize))
        if (query.offset > 0) publisher = publisher.skip(query.offset.toInt())
        if (limit != null) publisher = publisher.limit(limit)
        publisher.toFlux().map(::decode)
    }

    override fun page(query: SecuredQuery): Mono<QueryPage<ObjectNode>> = executionSnapshot(query).flatMap { snapshot ->
        val size = query.limit ?: unsupported()
        val itemPipeline = mutableListOf<Bson>()
        val sort = compileSort(query).toBsonDocument()
        if (!sort.isEmpty()) itemPipeline += Aggregates.sort(sort)
        itemPipeline += Aggregates.skip(query.offset.toInt())
        itemPipeline += Aggregates.limit(size)
        val facet = Aggregates.facet(
            Facet("items", itemPipeline),
            Facet("total", Aggregates.count("value"))
        )
        snapshot.collection.aggregate(listOf(Aggregates.match(compile(query.filter, snapshot)), facet))
            .first()
            .toMono()
            .map(::decodePage)
            .defaultIfEmpty(QueryPage(emptyList(), 0))
    }

    override fun count(query: SecuredQuery): Mono<Long> = executionSnapshot(query).flatMap { snapshot ->
        snapshot.collection.countDocuments(compile(query.filter, snapshot)).toMono()
    }

    private fun executionSnapshot(query: SecuredQuery): Mono<MongoExecutionSnapshot> = Mono.defer {
        val collection = collectionProvider(query.target)
        val requestedSearchFields = searchFields(query.filter)
        if (requestedSearchFields.isEmpty()) {
            Mono.just(MongoExecutionSnapshot(collection, emptySet(), query.schema))
        } else {
            collection.listIndexes().toFlux()
                .mapNotNull(::textIndexFields)
                .singleOrEmpty()
                .switchIfEmpty(Mono.error(notReady()))
                .map { indexedFields ->
                    if (indexedFields != requestedSearchFields) throw notReady()
                    MongoExecutionSnapshot(collection, indexedFields, query.schema)
                }
        }
    }

    private fun textIndexFields(index: Document): Set<LogicalField>? {
        val weights = index["weights"] as? Document ?: return null
        return weights.keys.mapTo(linkedSetOf(), ::LogicalField)
    }

    private fun compile(
        expression: QueryExpression,
        snapshot: MongoExecutionSnapshot,
        logicalPrefix: String? = null,
        relativePhysical: Boolean = false
    ): Bson = when (expression) {
        MatchAll -> Filters.empty()
        MatchNone -> Document("\$expr", Document("\$eq", listOf(1, 0)))
        is LogicalExpression -> {
            val operands = expression.operands.map { compile(it, snapshot, logicalPrefix, relativePhysical) }
            when (expression.operator) {
                LogicalOperator.AND -> Filters.and(operands)
                LogicalOperator.OR -> Filters.or(operands)
                LogicalOperator.NOR -> Filters.nor(operands)
            }
        }

        is PredicateExpression -> compilePredicate(expression, snapshot, logicalPrefix, relativePhysical)
        is LegacyConditionExpression -> SnapshotConditionConverter.convert(expression.condition)
        is ElementMatchExpression -> {
            val effective = effectiveField(expression.field, logicalPrefix)
            val field = if (relativePhysical) expression.field.value else physicalField(effective)
            Filters.elemMatch(
                field,
                compile(expression.predicate, snapshot, effective.value, relativePhysical = true)
            )
        }

        is SearchExpression -> {
            if (snapshot.textFields != expression.fields.mapTo(linkedSetOf()) { effectiveField(it, logicalPrefix) }) {
                throw notReady()
            }
            Filters.text(expression.query)
        }

        is RelativeTimeExpression -> unsupported()
    }

    @Suppress("CyclomaticComplexMethod")
    private fun compilePredicate(
        expression: PredicateExpression,
        snapshot: MongoExecutionSnapshot,
        logicalPrefix: String?,
        relativePhysical: Boolean
    ): Bson {
        val effective = effectiveField(expression.field, logicalPrefix)
        val field = if (relativePhysical) expression.field.value else physicalField(effective)
        val values = expression.values.map { value -> physicalValue(value, effective, snapshot) }
        return when (expression.operator) {
            PredicateOperator.EQ -> if (values.single() == null) {
                Filters.and(Filters.exists(field, true), Filters.eq(field, null))
            } else {
                Filters.eq(field, values.single())
            }
            PredicateOperator.NE -> Filters.and(Filters.exists(field, true), Filters.ne(field, values.single()))
            PredicateOperator.GT -> Filters.gt(field, checkNotNull(values.single()))
            PredicateOperator.LT -> Filters.lt(field, checkNotNull(values.single()))
            PredicateOperator.GTE -> Filters.gte(field, checkNotNull(values.single()))
            PredicateOperator.LTE -> Filters.lte(field, checkNotNull(values.single()))
            PredicateOperator.CONTAINS -> regex(field, values.single().toString(), expression)
            PredicateOperator.IN -> if (values.any { it == null }) {
                Filters.and(Filters.exists(field, true), Filters.`in`(field, values))
            } else {
                Filters.`in`(field, values)
            }
            PredicateOperator.NOT_IN -> Filters.and(Filters.exists(field, true), Filters.nin(field, values))
            PredicateOperator.BETWEEN -> Filters.and(
                Filters.gte(field, checkNotNull(values[0])),
                Filters.lte(field, checkNotNull(values[1]))
            )

            PredicateOperator.CONTAINS_ALL -> Filters.all(field, values)
            PredicateOperator.STARTS_WITH -> regex(
                field,
                "^${Pattern.quote(values.single().toString())}",
                expression,
                false
            )
            PredicateOperator.ENDS_WITH -> regex(
                field,
                "${Pattern.quote(values.single().toString())}$",
                expression,
                false
            )
            PredicateOperator.IS_NULL -> Filters.and(Filters.exists(field, true), Filters.eq(field, null))
            PredicateOperator.IS_NOT_NULL -> Filters.and(Filters.exists(field, true), Filters.ne(field, null))
            PredicateOperator.IS_TRUE -> Filters.eq(field, true)
            PredicateOperator.IS_FALSE -> Filters.eq(field, false)
            PredicateOperator.EXISTS -> Filters.exists(field, true)
            PredicateOperator.IS_EMPTY -> Filters.size(field, 0)
        }
    }

    private fun regex(
        field: String,
        value: String,
        expression: PredicateExpression,
        quote: Boolean = true
    ): Bson {
        val pattern = if (quote) Pattern.quote(value) else value
        val options = if (expression.stringComparison == me.ahoo.wow.api.query.StringComparison.CASE_INSENSITIVE) {
            "i"
        } else {
            ""
        }
        return Filters.regex(field, pattern, options)
    }

    private fun physicalValue(value: JsonNode, field: LogicalField, snapshot: MongoExecutionSnapshot): Any? {
        val kind = requireNotNull(snapshot.schema[field]).valueKind
        return when {
            value.isNull -> null
            kind == QueryValueKind.TIME -> Instant.parse(value.asString()).toEpochMilli()
            kind == QueryValueKind.BINARY -> Base64.getDecoder().decode(value.asString())
            value.isString -> value.asString()
            value.isBoolean -> value.booleanValue()
            value.isIntegralNumber && value.canConvertToInt() -> value.intValue()
            value.isIntegralNumber -> value.longValue()
            value.isFloatingPointNumber -> Decimal128(value.decimalValue())
            else -> unsupported()
        }
    }

    private fun compileSort(query: SecuredQuery): Bson = if (query.sort.isEmpty()) {
        Document()
    } else {
        Sorts.orderBy(
            query.sort.map { sort ->
                when (sort.direction) {
                    QuerySortDirection.ASC -> Sorts.ascending(physicalField(sort.field))
                    QuerySortDirection.DESC -> Sorts.descending(physicalField(sort.field))
                }
            }
        )
    }

    private fun executionLimit(query: SecuredQuery): Int? {
        val budgetProbe = query.budget.maxRecords?.let { maximum ->
            val probe = Math.addExact(maximum, 1)
            probe.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        return listOfNotNull(query.limit, budgetProbe).minOrNull()
    }

    private fun decodePage(document: Document): QueryPage<ObjectNode> {
        val items = document.getList("items", Document::class.java).map(::decode)
        val total = document.getList("total", Document::class.java)
            .firstOrNull()
            ?.get("value")
            ?.let { it as Number }
            ?.toLong()
            ?: 0
        return QueryPage(items, total)
    }

    private fun decode(document: Document): ObjectNode {
        val copy = Document(document).replacePrimaryKeyToAggregateId()
        val result = JsonSerializer.valueToTree<ObjectNode>(copy)
        TIME_FIELDS.forEach { field ->
            val value = result[field]
            if (value?.isIntegralNumber == true) result.put(field, Instant.ofEpochMilli(value.longValue()).toString())
        }
        return result
    }

    private fun validateSearchPlacement(expression: QueryExpression): Int = when (expression) {
        is SearchExpression -> 1
        is LogicalExpression -> {
            val count = expression.operands.sumOf(::validateSearchPlacement)
            if (count > 0 && expression.operator != LogicalOperator.AND) unsupported()
            if (count > 1) unsupported()
            count
        }

        is ElementMatchExpression -> {
            if (validateSearchPlacement(expression.predicate) > 0) unsupported()
            0
        }

        else -> 0
    }

    private fun searchFields(expression: QueryExpression): Set<LogicalField> = when (expression) {
        is SearchExpression -> expression.fields
        is LogicalExpression -> expression.operands.flatMapTo(linkedSetOf()) { searchFields(it) }
        is ElementMatchExpression -> searchFields(expression.predicate)
        else -> emptySet()
    }

    private fun effectiveField(field: LogicalField, prefix: String?): LogicalField =
        if (prefix == null) field else LogicalField("$prefix.${field.value}")

    private fun physicalField(field: LogicalField): String =
        if (field.value == "aggregateId") Documents.ID_FIELD else field.value

    private fun notReady(): QueryException = QueryException(QueryErrorCode.BACKEND_NOT_READY, QueryStage.BACKEND)

    private fun unsupported(): Nothing = throw QueryException(QueryErrorCode.UNSUPPORTED_QUERY, QueryStage.BACKEND)

    private data class MongoExecutionSnapshot(
        val collection: MongoCollection<Document>,
        val textFields: Set<LogicalField>,
        val schema: QuerySchema
    )

    private companion object {
        const val ID = "mongo"
        val TIME_FIELDS = setOf("firstEventTime", "eventTime", "snapshotTime")
    }
}
