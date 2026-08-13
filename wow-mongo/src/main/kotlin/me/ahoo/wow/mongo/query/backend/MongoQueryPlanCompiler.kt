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

package me.ahoo.wow.mongo.query.backend

import com.mongodb.client.model.Filters
import com.mongodb.client.model.Projections
import com.mongodb.client.model.Sorts
import me.ahoo.wow.api.query.error.QueryErrorCode
import me.ahoo.wow.api.query.error.QueryErrorReason
import me.ahoo.wow.api.query.error.QueryException
import me.ahoo.wow.api.query.error.QueryStage
import me.ahoo.wow.api.query.expression.ElementMatchExpression
import me.ahoo.wow.api.query.expression.FullTextExpression
import me.ahoo.wow.api.query.expression.LogicalExpression
import me.ahoo.wow.api.query.expression.LogicalField
import me.ahoo.wow.api.query.expression.LogicalOperator
import me.ahoo.wow.api.query.expression.MatchAll
import me.ahoo.wow.api.query.expression.MatchNone
import me.ahoo.wow.api.query.expression.NativeExpression
import me.ahoo.wow.api.query.expression.PortableLogicalExpression
import me.ahoo.wow.api.query.expression.PortableOperator
import me.ahoo.wow.api.query.expression.PredicateExpression
import me.ahoo.wow.api.query.expression.QueryExpression
import me.ahoo.wow.api.query.expression.QueryValue
import me.ahoo.wow.api.query.expression.StringComparisonMode
import me.ahoo.wow.api.query.gateway.QuerySortDirection
import me.ahoo.wow.query.plan.QueryPlanResultShape
import me.ahoo.wow.query.plan.QueryPlanV1
import org.bson.Document
import org.bson.conversions.Bson

internal class MongoQueryPlanCompiler(
    private val binding: MongoQueryFieldBinding,
    private val nativeTemplates: MongoNativeQueryTemplateRegistry
) {
    fun filter(expression: QueryExpression): Bson = compile(expression, null, null)

    fun sort(plan: QueryPlanV1): Bson? {
        if (plan.sort.isEmpty()) {
            return null
        }
        return Sorts.orderBy(
            plan.sort.map { sort ->
                val field = binding.physical(sort.field, me.ahoo.wow.query.schema.QueryFieldUsage.SORT)
                when (sort.direction) {
                    QuerySortDirection.ASC -> Sorts.ascending(field)
                    QuerySortDirection.DESC -> Sorts.descending(field)
                }
            }
        )
    }

    fun projection(plan: QueryPlanV1): Bson? {
        val fields = when (val shape = plan.authorizedResultShape) {
            QueryPlanResultShape.Count -> return null
            is QueryPlanResultShape.Dynamic -> shape.fields
            is QueryPlanResultShape.Typed -> shape.fields
        }
        if (fields.isEmpty()) {
            return Projections.excludeId()
        }
        val physicalFields = fields.map(::projectionSource).distinct().filter { candidate ->
            fields.none { other ->
                val parent = binding.physical(other)
                parent != candidate && candidate.startsWith("$parent.")
            }
        }
        val include = Projections.include(physicalFields)
        val includesIdentity = physicalFields.contains("_id")
        return if (includesIdentity) include else Projections.fields(include, Projections.excludeId())
    }

    private fun projectionSource(field: LogicalField): String {
        val segments = field.value.split('.')
        val fieldPhysical = binding.physical(field)
        for (endIndex in 1..segments.size) {
            val ancestor = LogicalField(segments.take(endIndex).joinToString("."))
            if (
                binding.contains(ancestor) &&
                binding.schema(ancestor).collectionKind != me.ahoo.wow.query.schema.QueryCollectionKind.NONE &&
                fieldPhysical.startsWith("${binding.physical(ancestor)}.")
            ) {
                return binding.physical(ancestor)
            }
        }
        return fieldPhysical
    }

    fun resultProjection(plan: QueryPlanV1): Map<LogicalField, String> = when (val shape = plan.authorizedResultShape) {
        QueryPlanResultShape.Count -> emptyMap()
        is QueryPlanResultShape.Dynamic -> binding.projection(shape.fields)
        is QueryPlanResultShape.Typed -> binding.projection(shape.fields)
    }

    private fun compile(
        expression: QueryExpression,
        relativeLogical: LogicalField?,
        relativePhysical: String?
    ): Bson = when (expression) {
        MatchAll -> Document()
        MatchNone -> Document("\$expr", Document("\$eq", listOf(1, 0)))
        is LogicalExpression -> logical(expression.operator, expression.operands, relativeLogical, relativePhysical)
        is PortableLogicalExpression ->
            logical(expression.operator, expression.operands, relativeLogical, relativePhysical)
        is PredicateExpression -> predicate(expression, relativeLogical, relativePhysical)
        is ElementMatchExpression -> elementMatch(expression, relativeLogical, relativePhysical)
        is FullTextExpression -> fullText(expression, relativeLogical)
        is NativeExpression -> native(expression, relativeLogical)
    }

    private fun logical(
        operator: LogicalOperator,
        operands: List<QueryExpression>,
        relativeLogical: LogicalField?,
        relativePhysical: String?
    ): Bson {
        val filters = operands.map { compile(it, relativeLogical, relativePhysical) }
        return when (operator) {
            LogicalOperator.AND -> Filters.and(filters)
            LogicalOperator.OR -> Filters.or(filters)
            LogicalOperator.NOR -> Filters.nor(filters)
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun predicate(
        expression: PredicateExpression,
        relativeLogical: LogicalField?,
        relativePhysical: String?
    ): Bson {
        val logicalField = resolve(relativeLogical, expression.field)
        val field = relativePath(binding.physical(logicalField), relativePhysical)
        return when (expression.operator) {
            PortableOperator.EQ -> if (expression.values.single() == QueryValue.NullValue) {
                present(field, Filters.eq(field, null))
            } else {
                Filters.eq(field, value(logicalField, expression.values.single()))
            }

            PortableOperator.NE -> present(field, Filters.ne(field, value(logicalField, expression.values.single())))
            PortableOperator.IN -> present(
                field,
                Filters.`in`(field, expression.values.map { value -> value(logicalField, value) })
            )
            PortableOperator.NOT_IN -> Filters.and(
                Filters.exists(field, true),
                Filters.nin(field, expression.values.map { value -> value(logicalField, value) })
            )

            PortableOperator.ALL_IN -> Filters.all(
                field,
                expression.values.map { value -> value(logicalField, value) }
            )
            PortableOperator.GT -> present(
                field,
                Filters.gt(field, nonNullValue(logicalField, expression.values.single()))
            )
            PortableOperator.LT -> present(
                field,
                Filters.lt(field, nonNullValue(logicalField, expression.values.single()))
            )
            PortableOperator.GTE -> present(
                field,
                Filters.gte(field, nonNullValue(logicalField, expression.values.single()))
            )
            PortableOperator.LTE -> present(
                field,
                Filters.lte(field, nonNullValue(logicalField, expression.values.single()))
            )
            PortableOperator.BETWEEN -> present(
                field,
                Filters.gte(field, nonNullValue(logicalField, expression.values[0])),
                Filters.lte(field, nonNullValue(logicalField, expression.values[1]))
            )
            PortableOperator.CONTAINS -> regex(field, expression, prefix = "", suffix = "")
            PortableOperator.STARTS_WITH -> regex(field, expression, prefix = "^", suffix = "")
            PortableOperator.ENDS_WITH -> regex(field, expression, prefix = "", suffix = "\$")
            PortableOperator.NULL -> present(field, Filters.eq(field, null))
            PortableOperator.NOT_NULL -> present(field, Filters.ne(field, null))
            PortableOperator.TRUE -> Filters.eq(field, true)
            PortableOperator.FALSE -> Filters.eq(field, false)
            PortableOperator.EXISTS -> Filters.exists(
                field,
                (expression.values.single() as QueryValue.BooleanValue).value
            )
        }
    }

    private fun elementMatch(
        expression: ElementMatchExpression,
        relativeLogical: LogicalField?,
        relativePhysical: String?
    ): Bson {
        val logicalField = resolve(relativeLogical, expression.field)
        val fullPhysical = binding.physical(logicalField)
        val field = relativePath(fullPhysical, relativePhysical)
        return Filters.elemMatch(field, compile(expression.predicate, logicalField, fullPhysical))
    }

    private fun fullText(expression: FullTextExpression, relativeLogical: LogicalField?): Bson {
        if (expression.capabilityId.value != FULL_TEXT_CAPABILITY ||
            expression.fields.any { !binding.contains(resolve(relativeLogical, it)) }
        ) {
            unsupported()
        }
        return Filters.text(expression.query)
    }

    private fun native(expression: NativeExpression, relativeLogical: LogicalField?): Bson {
        if (expression.capabilityId.value != MONGO_NATIVE_CAPABILITY || expression.backendId != MONGO_BACKEND_ID ||
            expression.declaredFields.any { !binding.contains(resolve(relativeLogical, it)) }
        ) {
            unsupported()
        }
        val template = nativeTemplates.template(expression.templateId) ?: unsupported()
        return template.build(expression.parameters)
    }

    private fun regex(
        field: String,
        expression: PredicateExpression,
        prefix: String,
        suffix: String
    ): Bson {
        val literal = (expression.values.single() as QueryValue.StringValue).value.escapeRegex()
        val pattern = "$prefix$literal$suffix"
        return when (expression.stringComparison) {
            StringComparisonMode.DEFAULT,
            StringComparisonMode.CASE_SENSITIVE -> Filters.regex(field, pattern)
            StringComparisonMode.CASE_INSENSITIVE -> Filters.regex(field, pattern, "i")
        }
    }

    private fun String.escapeRegex(): String = buildString(length + 16) {
        this@escapeRegex.forEach { character ->
            if (character in REGEX_METACHARACTERS) {
                append('\\')
            }
            append(character)
        }
    }

    private fun resolve(relativeTo: LogicalField?, field: LogicalField): LogicalField =
        if (relativeTo == null) field else LogicalField("${relativeTo.value}.${field.value}")

    private fun relativePath(fullPath: String, relativePhysical: String?): String =
        if (relativePhysical == null) {
            fullPath
        } else {
            fullPath.removePrefix("$relativePhysical.").also {
                require(it != fullPath) { "Nested query field is outside its element binding." }
            }
        }

    private fun present(field: String, vararg predicates: Bson): Bson =
        Filters.and(listOf(Filters.exists(field, true)) + predicates)

    private fun value(logicalField: LogicalField, value: QueryValue): Any? = binding.encode(logicalField, value)

    private fun nonNullValue(logicalField: LogicalField, value: QueryValue): Any =
        requireNotNull(value(logicalField, value)) { "Ordered Mongo query value cannot be null." }

    private fun unsupported(): Nothing = throw QueryException(
        QueryErrorCode.UNSUPPORTED_CAPABILITY,
        QueryStage.PLANNING,
        QueryErrorReason.CAPABILITY_DENIED
    )

    private companion object {
        const val FULL_TEXT_CAPABILITY: String = "full-text"
        const val MONGO_NATIVE_CAPABILITY: String = "x-wow:mongo-native"
        const val MONGO_BACKEND_ID: String = "mongo"
        val REGEX_METACHARACTERS: Set<Char> = setOf(
            '\\', '^', '\$', '.', '|', '?', '*', '+', '(', ')', '[', ']', '{', '}'
        )
    }
}
