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

import com.mongodb.client.model.Aggregates
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Projections
import com.mongodb.client.model.Sorts
import me.ahoo.wow.mongo.Documents
import me.ahoo.wow.query.backend.BackendCountQueryPlan
import me.ahoo.wow.query.backend.BackendEnforcedFilter
import me.ahoo.wow.query.backend.BackendPageQueryPlan
import me.ahoo.wow.query.backend.BackendPageWindow
import me.ahoo.wow.query.backend.BackendPlannedCondition
import me.ahoo.wow.query.backend.BackendProjection
import me.ahoo.wow.query.backend.BackendRecordQueryPlan
import me.ahoo.wow.query.backend.BackendRecordResultPlan
import me.ahoo.wow.query.backend.BackendSingleQueryPlan
import me.ahoo.wow.query.backend.BackendStreamQueryPlan
import me.ahoo.wow.query.backend.CaseSensitivity
import me.ahoo.wow.query.backend.JunctionOperator
import me.ahoo.wow.query.backend.NormalizedSortDirection
import me.ahoo.wow.query.backend.NormalizedValue
import me.ahoo.wow.query.backend.PredicateOperator
import me.ahoo.wow.query.backend.QueryBackendException
import me.ahoo.wow.query.backend.QueryBackendFailureKind
import me.ahoo.wow.query.backend.QueryFieldId
import org.bson.Document
import org.bson.conversions.Bson
import org.bson.types.Binary
import org.bson.types.Decimal128

internal object MongoPagePipelineFields {
    const val PREFIX = "__wowQueryPage"
    const val KIND = "${PREFIX}Kind"
    const val POSITION = "${PREFIX}Position"
    const val TOTAL = "${PREFIX}Total"
    const val RECORD_KIND = 0
    const val SENTINEL_KIND = 1
}

internal data class MongoCompiledRecordQuery(
    val filter: Bson,
    val projection: Bson?,
    val pageProjection: Bson?,
    val sort: Bson?,
    val limit: Int?,
    val page: BackendPageWindow?,
) {
    fun pagePipeline(): List<Bson> {
        val window = requireNotNull(page) { "Mongo page pipeline requires a page window." }
        return buildList {
            add(Aggregates.match(filter))
            add(markRecords())
            add(appendSentinel())
            add(setPageWindow())
            add(matchPageRows(window))
            pageProjection?.let { currentProjection -> add(Aggregates.project(currentProjection)) }
            add(replaceSentinel())
            add(clearPageFields())
        }
    }

    private fun markRecords(): Bson =
        Document("\$set", Document(MongoPagePipelineFields.KIND, MongoPagePipelineFields.RECORD_KIND))

    private fun appendSentinel(): Bson = Document(
        "\$unionWith",
        Document(
            "pipeline",
            listOf(
                Document(
                    "\$documents",
                    listOf(Document(MongoPagePipelineFields.KIND, MongoPagePipelineFields.SENTINEL_KIND)),
                ),
            ),
        ),
    )

    private fun setPageWindow(): Bson = Document(
        "\$setWindowFields",
        Document("sortBy", pageSort().toBsonDocument()).append("output", pageWindowOutput()),
    )

    private fun pageSort(): Bson = sort?.let { currentSort ->
        Sorts.orderBy(Sorts.ascending(MongoPagePipelineFields.KIND), currentSort)
    } ?: Sorts.ascending(MongoPagePipelineFields.KIND)

    private fun pageWindowOutput(): Bson = Document(
        MongoPagePipelineFields.POSITION,
        Document("\$sum", 1).append(
            "window",
            Document("documents", listOf("unbounded", "current")),
        ),
    ).append(
        MongoPagePipelineFields.TOTAL,
        Document(
            "\$sum",
            Document(
                "\$cond",
                listOf(
                    Document(
                        "\$eq",
                        listOf("\$${MongoPagePipelineFields.KIND}", MongoPagePipelineFields.RECORD_KIND),
                    ),
                    1,
                    0,
                ),
            ),
        ).append(
            "window",
            Document("documents", listOf("unbounded", "unbounded")),
        ),
    )

    private fun matchPageRows(window: BackendPageWindow): Bson {
        val endInclusive = pageEndInclusive(window)
        return Document(
            "\$match",
            Document(
                "\$or",
                listOf(
                    Document(MongoPagePipelineFields.KIND, MongoPagePipelineFields.SENTINEL_KIND),
                    Document(
                        "\$and",
                        listOf(
                            Document(MongoPagePipelineFields.KIND, MongoPagePipelineFields.RECORD_KIND),
                            Document(MongoPagePipelineFields.POSITION, Document("\$gt", window.offset)),
                            Document(MongoPagePipelineFields.POSITION, Document("\$lte", endInclusive)),
                        ),
                    ),
                ),
            ),
        )
    }

    private fun pageEndInclusive(window: BackendPageWindow): Long = try {
        Math.addExact(window.offset, window.size.toLong())
    } catch (error: ArithmeticException) {
        throw QueryBackendException(QueryBackendFailureKind.BUDGET_EXCEEDED, error)
    }

    private fun replaceSentinel(): Bson = Document(
        "\$replaceWith",
        Document(
            "\$cond",
            listOf(
                Document(
                    "\$eq",
                    listOf("\$${MongoPagePipelineFields.KIND}", MongoPagePipelineFields.SENTINEL_KIND),
                ),
                Document(PAGE_TOTAL_VALUE, "\$${MongoPagePipelineFields.TOTAL}"),
                "\$\$ROOT",
            ),
        ),
    )

    private fun clearPageFields(): Bson = Document(
        "\$unset",
        listOf(
            MongoPagePipelineFields.KIND,
            MongoPagePipelineFields.POSITION,
            MongoPagePipelineFields.TOTAL,
        ),
    )

    companion object {
        const val PAGE_TOTAL_VALUE = "value"
    }
}

internal class MongoRecordQueryCompiler(
    private val binding: MongoPreparedQueryBinding,
) {
    constructor(binding: MongoSnapshotQueryBinding) : this(binding.prepared)

    constructor(binding: MongoEventStreamQueryBinding) : this(binding.prepared)

    fun compile(plan: BackendRecordQueryPlan): MongoCompiledRecordQuery {
        require(plan.target == binding.schema.target) { "Mongo query plan target does not match its binding." }
        require(plan.schemaContractId == binding.schema.contractId) {
            "Mongo query plan schema contract does not match its binding."
        }
        val resultPlan = plan as? BackendRecordResultPlan
        return MongoCompiledRecordQuery(
            filter = compileFilter(plan.filter),
            projection = resultPlan?.projection?.let(::compileProjection),
            pageProjection = (plan as? BackendPageQueryPlan)?.projection?.let(::compilePageProjection),
            sort = resultPlan?.sort?.takeIf(List<*>::isNotEmpty)?.let { sorts ->
                Sorts.orderBy(
                    sorts.map { sort ->
                        val path = requireField(sort.field).path
                        when (sort.direction) {
                            NormalizedSortDirection.ASC -> Sorts.ascending(path)
                            NormalizedSortDirection.DESC -> Sorts.descending(path)
                        }
                    },
                )
            },
            limit = when (plan) {
                is BackendSingleQueryPlan -> 1
                is BackendStreamQueryPlan -> plan.limit
                is BackendPageQueryPlan,
                is BackendCountQueryPlan -> null
            },
            page = (plan as? BackendPageQueryPlan)?.page,
        )
    }

    internal fun compileFilter(filter: BackendEnforcedFilter): Bson {
        validateSearchShape(filter.condition)
        return compileCondition(filter.condition, null)
    }

    internal fun requireFieldBinding(field: QueryFieldId): MongoFieldBinding = requireField(field)

    internal fun encodeFieldValue(field: QueryFieldId, value: NormalizedValue): Any? =
        encodeValue(requireField(field), value)

    private fun compileCondition(condition: BackendPlannedCondition, elementOwner: MongoFieldBinding?): Bson =
        when (condition) {
            BackendPlannedCondition.All -> Filters.empty()
            BackendPlannedCondition.None -> Document("\$expr", false)
            is BackendPlannedCondition.Junction -> {
                val children = condition.children.map { child -> compileCondition(child, elementOwner) }
                when (condition.operator) {
                    JunctionOperator.AND -> Filters.and(children)
                    JunctionOperator.OR -> Filters.or(children)
                    JunctionOperator.NOR -> Filters.nor(children)
                }
            }

            is BackendPlannedCondition.Predicate -> compilePredicate(condition, elementOwner)
            is BackendPlannedCondition.ElementMatch -> {
                val owner = requireField(condition.field)
                Filters.elemMatch(
                    relativePath(owner.path, elementOwner?.path),
                    compileCondition(condition.condition, owner),
                )
            }

            is BackendPlannedCondition.Search -> {
                if (binding.textSearch?.scope != condition.scope) {
                    unsupported()
                }
                Filters.text(condition.text)
            }

            is BackendPlannedCondition.Native -> unsupported()
        }

    private fun validateSearchShape(condition: BackendPlannedCondition) {
        if (condition.searchCount() > 1) {
            unsupported()
        }
        when (condition) {
            is BackendPlannedCondition.ElementMatch -> {
                if (condition.condition.containsSearch()) {
                    unsupported()
                }
            }

            is BackendPlannedCondition.Junction -> when (condition.operator) {
                JunctionOperator.AND -> condition.children.forEach(::validateSearchShape)
                JunctionOperator.OR,
                JunctionOperator.NOR,
                -> if (condition.containsSearch()) {
                    unsupported()
                }
            }

            else -> Unit
        }
    }

    private fun BackendPlannedCondition.searchCount(): Int = when (this) {
        is BackendPlannedCondition.Search -> 1
        is BackendPlannedCondition.Junction -> children.sumOf { child -> child.searchCount() }
        is BackendPlannedCondition.ElementMatch -> condition.searchCount()
        else -> 0
    }

    private fun BackendPlannedCondition.containsSearch(): Boolean = searchCount() > 0

    private fun compilePredicate(
        predicate: BackendPlannedCondition.Predicate,
        elementOwner: MongoFieldBinding?,
    ): Bson {
        if (predicate.options.caseSensitivity != CaseSensitivity.SENSITIVE) {
            unsupported()
        }
        val field = requireField(predicate.field)
        val path = relativePath(field.path, elementOwner?.path)
        val value = predicate.value?.let { normalized -> encodeValue(field, normalized) }
        return when (predicate.operator) {
            PredicateOperator.EQ,
            PredicateOperator.NE,
            PredicateOperator.GT,
            PredicateOperator.LT,
            PredicateOperator.GTE,
            PredicateOperator.LTE,
            -> compileComparison(path, predicate.operator, value)

            PredicateOperator.IN,
            PredicateOperator.NOT_IN,
            PredicateOperator.BETWEEN,
            PredicateOperator.ALL_IN,
            -> compileCollection(path, predicate.operator, value)

            PredicateOperator.CONTAINS,
            PredicateOperator.STARTS_WITH,
            PredicateOperator.ENDS_WITH,
            -> compileLiteral(path, predicate.operator, value)

            PredicateOperator.IS_NULL,
            PredicateOperator.NOT_NULL,
            PredicateOperator.IS_TRUE,
            PredicateOperator.IS_FALSE,
            PredicateOperator.EXISTS,
            -> compileState(path, predicate.operator, value)
        }
    }

    private fun compileComparison(path: String, operator: PredicateOperator, value: Any?): Bson = when (operator) {
        PredicateOperator.EQ -> Filters.eq(path, value)
        PredicateOperator.NE -> Filters.ne(path, value)
        PredicateOperator.GT -> Filters.gt(path, value ?: unsupported())
        PredicateOperator.LT -> Filters.lt(path, value ?: unsupported())
        PredicateOperator.GTE -> Filters.gte(path, value ?: unsupported())
        PredicateOperator.LTE -> Filters.lte(path, value ?: unsupported())
        else -> unsupported()
    }

    private fun compileCollection(path: String, operator: PredicateOperator, value: Any?): Bson {
        val values = value.requireList()
        return when (operator) {
            PredicateOperator.IN -> Filters.`in`(path, values)
            PredicateOperator.NOT_IN -> Filters.nin(path, values)
            PredicateOperator.ALL_IN -> Filters.all(path, values)
            PredicateOperator.BETWEEN -> {
                require(values.size == 2) { "BETWEEN requires exactly two planned values." }
                Filters.and(
                    Filters.gte(path, values[0] ?: unsupported()),
                    Filters.lte(path, values[1] ?: unsupported()),
                )
            }

            else -> unsupported()
        }
    }

    private fun compileLiteral(path: String, operator: PredicateOperator, value: Any?): Bson {
        val literal = value.requireText().escapeRegex()
        return when (operator) {
            PredicateOperator.CONTAINS -> Filters.regex(path, literal)
            PredicateOperator.STARTS_WITH -> Filters.regex(path, "^$literal")
            PredicateOperator.ENDS_WITH -> Filters.regex(path, "$literal$")
            else -> unsupported()
        }
    }

    private fun compileState(path: String, operator: PredicateOperator, value: Any?): Bson = when (operator) {
        PredicateOperator.IS_NULL -> Filters.eq(path, null)
        PredicateOperator.NOT_NULL -> Filters.ne(path, null)
        PredicateOperator.IS_TRUE -> Filters.eq(path, true)
        PredicateOperator.IS_FALSE -> Filters.eq(path, false)
        PredicateOperator.EXISTS -> Filters.exists(path, value as? Boolean ?: unsupported())
        else -> unsupported()
    }

    private fun compileProjection(projection: BackendProjection): Bson? =
        when (projection) {
            BackendProjection.All -> null
            is BackendProjection.Include -> Projections.include(
                canonicalPhysicalPaths(
                    projection.fields.map { field -> requireField(field).path } + Documents.ID_FIELD,
                ),
            )

            is BackendProjection.Exclude -> {
                val excluded = canonicalPhysicalPaths(
                    projection.fields.map { field -> requireField(field).path }
                        .filterNot(Documents.ID_FIELD::equals),
                )
                excluded.takeIf(List<*>::isNotEmpty)?.let(Projections::exclude)
            }
        }

    private fun compilePageProjection(projection: BackendProjection): Bson? =
        when (projection) {
            BackendProjection.All -> null
            is BackendProjection.Include -> Projections.include(
                canonicalPhysicalPaths(
                    projection.fields.map { field -> requireField(field).path } +
                        Documents.ID_FIELD +
                        listOf(
                            MongoPagePipelineFields.KIND,
                            MongoPagePipelineFields.POSITION,
                            MongoPagePipelineFields.TOTAL,
                        ),
                ),
            )

            is BackendProjection.Exclude -> compileProjection(projection)
        }

    private fun requireField(field: QueryFieldId): MongoFieldBinding =
        binding.fields[field] ?: unsupported()

    private fun encodeValue(field: MongoFieldBinding, value: NormalizedValue): Any? =
        when (value) {
            NormalizedValue.Null -> null
            is NormalizedValue.BooleanValue -> value.value
            is NormalizedValue.Text -> value.value
            is NormalizedValue.Int64 -> encodeInt64(field, value)
            is NormalizedValue.Decimal -> encodeDecimal(field, value)
            is NormalizedValue.InstantValue -> encodeInstant(field, value)

            is NormalizedValue.Bytes -> Binary(value.toByteArray())
            is NormalizedValue.ListValue -> value.values.map { nested -> encodeValue(field, nested) }
            is NormalizedValue.ObjectValue -> Document(
                value.values.mapValues { (_, nested) -> encodeValue(field, nested) },
            )
        }

    private fun encodeInt64(field: MongoFieldBinding, value: NormalizedValue.Int64): Any =
        if (field.valueEncoding == MongoValueEncoding.DECIMAL128) Decimal128(value.value) else value.value

    private fun encodeDecimal(field: MongoFieldBinding, value: NormalizedValue.Decimal): Decimal128 {
        if (field.valueEncoding != MongoValueEncoding.DECIMAL128) {
            unsupported()
        }
        return decimal128(value)
    }

    private fun encodeInstant(field: MongoFieldBinding, value: NormalizedValue.InstantValue): Long {
        if (field.valueEncoding != MongoValueEncoding.EPOCH_MILLIS) {
            unsupported()
        }
        return value.value.toEpochMilli()
    }

    private fun decimal128(value: NormalizedValue.Decimal): Decimal128 =
        try {
            Decimal128(value.value)
        } catch (error: NumberFormatException) {
            throw QueryBackendException(QueryBackendFailureKind.UNSUPPORTED, error)
        }

    private fun relativePath(path: String, owner: String?): String {
        if (owner == null) {
            return path
        }
        val prefix = "$owner."
        if (!path.startsWith(prefix)) {
            unsupported()
        }
        return path.removePrefix(prefix).also { relative ->
            if (relative.isBlank()) {
                unsupported()
            }
        }
    }

    private fun Any?.requireText(): String = this as? String ?: unsupported()

    @Suppress("UNCHECKED_CAST")
    private fun Any?.requireList(): List<Any?> = this as? List<Any?> ?: unsupported()

    private fun String.escapeRegex(): String = buildString(length + 8) {
        this@escapeRegex.forEach { char ->
            if (char in REGEX_META) {
                append('\\')
            }
            append(char)
        }
    }

    private fun canonicalPhysicalPaths(paths: List<String>): List<String> =
        paths.distinct().filter { candidate ->
            paths.none { other -> other != candidate && candidate.startsWith("$other.") }
        }

    private fun unsupported(): Nothing = throw QueryBackendException(QueryBackendFailureKind.UNSUPPORTED)

    private companion object {
        val REGEX_META = setOf('\\', '^', '$', '.', '|', '?', '*', '+', '(', ')', '[', ']', '{', '}')
    }
}
