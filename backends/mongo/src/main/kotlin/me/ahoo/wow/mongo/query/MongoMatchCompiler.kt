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

package me.ahoo.wow.mongo.query

import com.mongodb.client.model.Filters
import me.ahoo.wow.api.query.AggregateIdFilter
import me.ahoo.wow.api.query.AggregateIdsFilter
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.BetweenFilter
import me.ahoo.wow.api.query.ComparisonOperator
import me.ahoo.wow.api.query.ContainsAllFilter
import me.ahoo.wow.api.query.ContainsFilter
import me.ahoo.wow.api.query.DeletionFilter
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.ElementMatchFilter
import me.ahoo.wow.api.query.EndsWithFilter
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.ExistsFilter
import me.ahoo.wow.api.query.ExpressionFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.GreaterThanFilter
import me.ahoo.wow.api.query.GreaterThanOrEqualFilter
import me.ahoo.wow.api.query.IdFilter
import me.ahoo.wow.api.query.IdsFilter
import me.ahoo.wow.api.query.InFilter
import me.ahoo.wow.api.query.IsEmptyFilter
import me.ahoo.wow.api.query.IsNotNullFilter
import me.ahoo.wow.api.query.IsNullFilter
import me.ahoo.wow.api.query.LessThanFilter
import me.ahoo.wow.api.query.LessThanOrEqualFilter
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.MatchNoneFilter
import me.ahoo.wow.api.query.NorFilter
import me.ahoo.wow.api.query.NotEqualFilter
import me.ahoo.wow.api.query.NotExistsFilter
import me.ahoo.wow.api.query.NotInFilter
import me.ahoo.wow.api.query.OrFilter
import me.ahoo.wow.api.query.OwnerIdFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.SearchFilter
import me.ahoo.wow.api.query.SearchMode
import me.ahoo.wow.api.query.SpaceIdFilter
import me.ahoo.wow.api.query.StartsWithFilter
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.mongo.ExactBson
import me.ahoo.wow.mongo.query.pipeline.toMongoExpression
import me.ahoo.wow.query.AdmittedQuery
import me.ahoo.wow.query.QueryExecutionException
import me.ahoo.wow.query.ignoreCase
import me.ahoo.wow.query.operandValue
import me.ahoo.wow.query.requiredOperandValue
import me.ahoo.wow.query.schema.QueryViolation
import org.bson.BsonType
import org.bson.Document
import org.bson.conversions.Bson
import java.math.BigDecimal
import java.math.BigInteger

/** An always-false match: unlike `$expr: false`, an empty `$in` on `_id` uses the key and works inside `$elemMatch`. */
internal val MATCH_NONE_FILTER: Bson = Filters.`in`(MongoSnapshotDocuments.ID, emptyList<Any>())

/** The MongoDB comparison operator of this comparison, for a match document or an aggregation expression. */
internal val ComparisonOperator.mongoOperator: String
    get() = when (this) {
        ComparisonOperator.EQ -> "\$eq"
        ComparisonOperator.NE -> "\$ne"
        ComparisonOperator.GT -> "\$gt"
        ComparisonOperator.GTE -> "\$gte"
        ComparisonOperator.LT -> "\$lt"
        ComparisonOperator.LTE -> "\$lte"
    }

/**
 * Compiles an admitted filter to a MongoDB match document (decision D131): the 9.x `MongoFilterCompiler`, named apart
 * so that its source path differs from the 9.x module's, which coverage reports match files by. Admission has resolved
 * every field to its physical path in the snapshot document ([MongoSnapshotDocuments]), so nothing here depends on
 * the record's names; each path is [checked][MongoSnapshotDocuments.checked] before it enters a document.
 *
 * Operands are values only: a field never comes from an operand, and no operand becomes an operator. Nothing compiles
 * to `$where`, `$function` or `$accumulator`, so no query runs JavaScript. A number that is not an integer is sent as
 * a `Decimal128` of its decimal digits (decision D122), so it compares with the stored decimals exactly: a `double`
 * `0.1` would never equal the stored `Decimal128` 0.1.
 */
internal object MongoMatchCompiler {
    private val ESCAPE_CHARS = setOf('\\', '^', '$', '.', '|', '?', '*', '+', '(', ')', '[', ']', '{', '}')

    /**
     * Compiles [filter], a filter of [admitted], reading each field's resolution: absolute physical paths, so the
     * filter also applies inside unwound element scopes.
     */
    fun compile(filter: FilterExpression, admitted: AdmittedQuery<*>): Bson =
        compile(filter.also(::validateNativeText), admitted, ROOT)

    /** Compiles an element-scope or metric filter of an aggregation, whose paths stay absolute after `$unwind`. */
    fun compileScoped(filter: FilterExpression, admitted: AdmittedQuery<*>): Bson = compile(filter, admitted, ROOT)

    /** MongoDB runs at most one `$text` per query, and none beneath `$nor`. */
    private fun validateNativeText(filter: FilterExpression) {
        var count = 0
        fun visit(expression: FilterExpression, underNor: Boolean = false) {
            when (expression) {
                is SearchFilter -> if (underNor || ++count > 1) {
                    throw QueryViolation.StorageUnsupported(
                        "more than one search filter, or a search filter beneath NOR"
                    ).rejection()
                }
                is AndFilter -> expression.operands.forEach { visit(it, underNor) }
                is OrFilter -> expression.operands.forEach { visit(it, underNor) }
                is NorFilter -> expression.operands.forEach { visit(it, true) }
                else -> Unit
            }
        }
        visit(filter)
    }

    /**
     * [scope] says where an element field's path starts: [ROOT] reads absolute paths; inside `$elemMatch` paths are
     * relative to the matched element ([ELEMENT]); inside an element scope that holds one object rather than an
     * array, they continue the object's path, [scope] itself.
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun compile(filter: FilterExpression, admitted: AdmittedQuery<*>, scope: String?): Bson = when (filter) {
        MatchAllFilter -> Filters.empty()
        MatchNoneFilter -> MATCH_NONE_FILTER
        is IdFilter -> Filters.eq(system(admitted, filter), filter.value)
        is IdsFilter -> Filters.`in`(system(admitted, filter), filter.values)
        is AggregateIdFilter -> Filters.eq(system(admitted, filter), filter.value)
        is AggregateIdsFilter -> Filters.`in`(system(admitted, filter), filter.values)
        is TenantIdFilter -> Filters.eq(system(admitted, filter), filter.value)
        is OwnerIdFilter -> Filters.eq(system(admitted, filter), filter.value)
        is SpaceIdFilter -> Filters.eq(system(admitted, filter), filter.value)
        is AndFilter -> Filters.and(filter.operands.map { compile(it, admitted, scope) })
        is OrFilter -> Filters.or(filter.operands.map { compile(it, admitted, scope) })
        is NorFilter -> Filters.nor(filter.operands.map { compile(it, admitted, scope) })
        is EqualFilter -> Filters.eq(filter.field.resolve(admitted, scope), filter.value.operand())
        is NotEqualFilter -> Filters.ne(filter.field.resolve(admitted, scope), filter.value.operand())
        is GreaterThanFilter -> Filters.gt(filter.field.resolve(admitted, scope), filter.value.requiredOperand())
        is GreaterThanOrEqualFilter ->
            Filters.gte(filter.field.resolve(admitted, scope), filter.value.requiredOperand())
        is LessThanFilter -> Filters.lt(filter.field.resolve(admitted, scope), filter.value.requiredOperand())
        is LessThanOrEqualFilter ->
            Filters.lte(filter.field.resolve(admitted, scope), filter.value.requiredOperand())
        is ContainsFilter -> regex(
            filter.field.resolve(admitted, scope),
            filter.value.escapeRegex(),
            filter.stringComparison.ignoreCase,
        )
        is StartsWithFilter -> regex(
            filter.field.resolve(admitted, scope),
            "^${filter.value.escapeRegex()}",
            filter.stringComparison.ignoreCase,
        )
        // `\z`, not `$`: PCRE's `$` also matches before a trailing newline, so `abc\n` would end with `abc` here and
        // nowhere else (finding R2.2-4).
        is EndsWithFilter -> regex(
            filter.field.resolve(admitted, scope),
            "${filter.value.escapeRegex()}\\z",
            filter.stringComparison.ignoreCase,
        )
        is InFilter -> Filters.`in`(filter.field.resolve(admitted, scope), filter.values.map { it.operand() })
        is NotInFilter -> Filters.nin(filter.field.resolve(admitted, scope), filter.values.map { it.operand() })
        is BetweenFilter -> Filters.and(
            Filters.gte(filter.field.resolve(admitted, scope), filter.lowerBound.requiredOperand()),
            Filters.lte(filter.field.resolve(admitted, scope), filter.upperBound.requiredOperand()),
        )
        is ContainsAllFilter -> Filters.all(filter.field.resolve(admitted, scope), filter.values.map { it.operand() })
        is IsEmptyFilter -> Filters.size(filter.field.resolve(admitted, scope), 0)
        is IsNullFilter -> Filters.eq(filter.field.resolve(admitted, scope), null)
        is IsNotNullFilter -> Filters.ne(filter.field.resolve(admitted, scope), null)
        is ExistsFilter -> Filters.exists(filter.field.resolve(admitted, scope))
        is NotExistsFilter -> Filters.exists(filter.field.resolve(admitted, scope), false)
        is DeletionFilter -> when (filter.deletionState) {
            DeletionState.ACTIVE -> Filters.eq(system(admitted, filter), false)
            DeletionState.DELETED -> Filters.eq(system(admitted, filter), true)
            DeletionState.ALL -> Filters.empty()
        }
        is ElementMatchFilter -> elementMatch(filter, admitted, scope)
        is SearchFilter -> Filters.text(
            if (filter.mode == SearchMode.PHRASE) {
                if ('"' in filter.query) {
                    throw QueryViolation.StorageUnsupported("double quotes in a PHRASE search query").rejection()
                }
                "\"${filter.query}\""
            } else {
                filter.query
            },
        )
        is ExpressionFilter -> Filters.expr(expressionComparison(filter, admitted))
        else -> throw QueryExecutionException("Filter [${filter.operator}] must be normalized before compilation.")
    }

    private fun system(admitted: AdmittedQuery<*>, filter: FilterExpression): String =
        MongoSnapshotDocuments.checked(admitted.systemPath(filter))

    /** The comparison of a computed value that exists; `null` would order before every number. */
    private fun expressionComparison(filter: ExpressionFilter, admitted: AdmittedQuery<*>): Document = Document(
        "\$let",
        Document("vars", Document("value", filter.expression.toMongoExpression(admitted))).append(
            "in",
            Document(
                "\$and",
                listOf(
                    Document("\$ne", listOf("\$\$value", null)),
                    Document(filter.comparison.mongoOperator, listOf("\$\$value", filter.value)),
                ),
            ),
        ),
    )

    /**
     * The records with one element matching every predicate: an element of the array, or, as the reference semantics
     * read a single object where an array of objects is declared, that object itself. `$elemMatch` reads arrays only,
     * and `$type: "object"` also matches an array holding an object, so the object branch also requires a value that
     * is not an array.
     */
    private fun elementMatch(filter: ElementMatchFilter, admitted: AdmittedQuery<*>, scope: String?): Bson {
        val container = filter.field.resolve(admitted, scope)
        return Filters.or(
            Filters.elemMatch(container, compile(filter.predicate, admitted, ELEMENT)),
            Filters.and(
                Filters.type(container, BsonType.DOCUMENT),
                Filters.not(Filters.type(container, BsonType.ARRAY)),
                compile(filter.predicate, admitted, container),
            ),
        )
    }

    private fun QueryField.resolve(admitted: AdmittedQuery<*>, scope: String?): String {
        val field = admitted.field(this)
        val path = when (scope) {
            null -> admitted.physicalPath(this)
            ELEMENT -> field.relativePhysicalField.path
            else -> scope + "." + field.relativePhysicalField.path
        }
        return MongoSnapshotDocuments.checked(path)
    }

    /** Absolute paths. */
    private val ROOT: String? = null

    /** Paths relative to the element `$elemMatch` matches. */
    private const val ELEMENT = ""

    /** An operand as the BSON value it is stored as: a runtime value as the JSON it writes, a decimal exactly. */
    private fun Any?.operand(): Any? = stored(operandValue())

    /** A non-null scalar operand (a range bound or a term), as the BSON value it is stored as. */
    private fun Any?.requiredOperand(): Any = checkNotNull(stored(requiredOperandValue()))

    internal fun stored(value: Any?): Any? = when (value) {
        is List<*> -> value.map(::stored)
        is Number -> storedNumber(value)
        else -> value
    }

    /** An integer stays one; any other number becomes the `Decimal128` of its decimal digits (decision D122). */
    private fun storedNumber(value: Number): Any = when (value) {
        is Int, is Long, is Short, is Byte -> value
        is BigInteger -> if (value.bitLength() < Long.SIZE_BITS) value.toLong() else decimal(BigDecimal(value))
        is BigDecimal -> decimal(value)
        else -> decimal(BigDecimal(value.toString()))
    }

    private fun decimal(value: BigDecimal) = try {
        ExactBson.decimal128(value)
    } catch (_: IllegalArgumentException) {
        throw QueryViolation.StorageUnsupported("numeric operands beyond 34 significant digits").rejection()
    }

    private fun String.escapeRegex(): String {
        val escaped = StringBuilder(length + ESCAPE_SLACK)
        for (char in this) {
            if (char in ESCAPE_CHARS) escaped.append('\\')
            escaped.append(char)
        }
        return escaped.toString()
    }

    private const val ESCAPE_SLACK = 16

    private fun regex(field: String, value: String, ignoreCase: Boolean): Bson =
        if (ignoreCase) Filters.regex(field, value, "i") else Filters.regex(field, value)
}
