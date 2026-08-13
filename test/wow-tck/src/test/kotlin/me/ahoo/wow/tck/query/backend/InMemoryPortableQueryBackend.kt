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

package me.ahoo.wow.tck.query.backend

import me.ahoo.wow.api.query.ImmutableDynamicDocument
import me.ahoo.wow.api.query.error.QueryErrorCode
import me.ahoo.wow.api.query.error.QueryErrorReason
import me.ahoo.wow.api.query.error.QueryException
import me.ahoo.wow.api.query.error.QueryStage
import me.ahoo.wow.api.query.expression.ElementMatchExpression
import me.ahoo.wow.api.query.expression.FullTextExpression
import me.ahoo.wow.api.query.expression.LogicalExpression
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
import me.ahoo.wow.api.query.gateway.QueryConsistency
import me.ahoo.wow.api.query.gateway.QueryPage
import me.ahoo.wow.api.query.gateway.QuerySort
import me.ahoo.wow.api.query.gateway.QuerySortDirection
import me.ahoo.wow.query.backend.QueryBackend
import me.ahoo.wow.query.backend.QueryBackendDescriptor
import me.ahoo.wow.query.backend.QueryBackendReadiness
import me.ahoo.wow.query.backend.QueryBackendResolutionContext
import me.ahoo.wow.query.backend.QueryPlanVersion
import me.ahoo.wow.query.backend.QueryPortableFeature
import me.ahoo.wow.query.plan.CountQueryPlanV1
import me.ahoo.wow.query.plan.ListQueryPlanV1
import me.ahoo.wow.query.plan.PageQueryPlanV1
import me.ahoo.wow.query.plan.QueryPlanResultShape
import me.ahoo.wow.query.plan.QueryPlanV1
import me.ahoo.wow.query.plan.SingleQueryPlanV1
import me.ahoo.wow.query.validation.QueryBudgetLimit
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.math.BigDecimal

internal class InMemoryPortableQueryBackend(
    private val context: QueryBackendResolutionContext,
    private val documents: () -> List<PortableStoredQueryDocument>
) : QueryBackend {
    override val descriptor: QueryBackendDescriptor = QueryBackendDescriptor(
        backendId = "in-memory-tck",
        documentKinds = setOf(context.target.documentKind),
        planVersions = setOf(QueryPlanVersion.V1),
        portableOperators = PortableOperator.entries.toSet(),
        portableFeatures = QueryPortableFeature.entries.toSet(),
        stringComparisonModes = StringComparisonMode.entries.toSet(),
        capabilities = setOf(PortableQueryDataset.FULL_TEXT_CAPABILITY),
        maxBudget = QueryBudgetLimit.UNBOUNDED
    )

    override fun readiness(): Mono<QueryBackendReadiness> = Mono.just(QueryBackendReadiness.Ready)

    override fun <R : Any> single(plan: SingleQueryPlanV1<R>): Mono<R> = Mono.defer {
        requireBound(plan)
        sortedMatches(plan).firstOrNull()?.let { document -> Mono.just(decode(document, plan.authorizedResultShape)) }
            ?: Mono.empty()
    }

    override fun <R : Any> list(plan: ListQueryPlanV1<R>): Flux<R> = Flux.defer {
        requireBound(plan)
        val matches = sortedMatches(plan).let { values ->
            if (plan.limit == 0) values else values.take(plan.limit)
        }
        Flux.fromIterable(matches).map { document -> decode(document, plan.authorizedResultShape) }
    }

    override fun <R : Any> page(plan: PageQueryPlanV1<R>): Mono<QueryPage<R>> = Mono.fromSupplier {
        requireBound(plan)
        val matches = sortedMatches(plan)
        val start = ((plan.page.index - 1).toLong() * plan.page.size).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val items = if (start >= matches.size) {
            emptyList()
        } else {
            matches.drop(start).take(plan.page.size).map { document -> decode<R>(document, plan.authorizedResultShape) }
        }
        QueryPage(items, matches.size.toLong(), QueryConsistency.EXACT)
    }

    override fun count(plan: CountQueryPlanV1): Mono<Long> = Mono.fromSupplier {
        requireBound(plan)
        documents().count { document -> matches(plan.securedExpression, document.fields) }.toLong()
    }

    private fun requireBound(plan: QueryPlanV1) {
        require(plan.target == context.target) { "TCK backend received a plan for a different target." }
        require(plan.securedExpression == context.securedExpression) {
            "TCK backend resolution context and execution plan expressions differ."
        }
    }

    private fun sortedMatches(plan: QueryPlanV1): List<PortableStoredQueryDocument> = documents()
        .filter { document -> matches(plan.securedExpression, document.fields) }
        .sortedWith(documentComparator(plan.sort))

    private fun documentComparator(sort: List<QuerySort>): Comparator<PortableStoredQueryDocument> =
        Comparator { first, second ->
            sort.firstNotNullOfOrNull { fieldSort ->
                val comparison = compareNullable(first.fields[fieldSort.field], second.fields[fieldSort.field])
                val directed = if (fieldSort.direction == QuerySortDirection.ASC) comparison else -comparison
                directed.takeIf { it != 0 }
            } ?: 0
        }

    private fun compareNullable(first: QueryValue?, second: QueryValue?): Int = when {
        first == null && second == null -> 0
        first == null -> -1
        second == null -> 1
        else -> compareValues(first, second)
    }

    private fun matches(expression: QueryExpression, values: Map<*, QueryValue>): Boolean = when (expression) {
        MatchAll -> true
        MatchNone -> false
        is LogicalExpression -> combine(expression.operator, expression.operands.map { matches(it, values) })
        is PortableLogicalExpression -> combine(expression.operator, expression.operands.map { matches(it, values) })
        is PredicateExpression -> matchesPredicate(expression, values)
        is ElementMatchExpression -> matchesElement(expression, values)
        is FullTextExpression,
        is NativeExpression -> throw unsupportedCapability()
    }

    private fun combine(operator: LogicalOperator, matches: List<Boolean>): Boolean = when (operator) {
        LogicalOperator.AND -> matches.all { it }
        LogicalOperator.OR -> matches.any { it }
        LogicalOperator.NOR -> matches.none { it }
    }

    private fun matchesElement(expression: ElementMatchExpression, values: Map<*, QueryValue>): Boolean {
        val collection = value(values, expression.field.value) as? QueryValue.ListValue ?: return false
        return collection.values.asSequence()
            .filterIsInstance<QueryValue.ObjectValue>()
            .any { element -> matches(expression.predicate, element.values) }
    }

    // Exhaustive dispatch mirrors PortableOperator; splitting it would obscure the test oracle's operator matrix.
    @Suppress("CyclomaticComplexMethod")
    private fun matchesPredicate(predicate: PredicateExpression, values: Map<*, QueryValue>): Boolean {
        val present = contains(values, predicate.field.value)
        val actual = value(values, predicate.field.value)
        return when (predicate.operator) {
            PortableOperator.EQ -> present && equal(actual, predicate.values.single())
            PortableOperator.NE -> present && !equal(actual, predicate.values.single())
            PortableOperator.GT ->
                present && actual != QueryValue.NullValue &&
                    compareValues(requireNotNull(actual), predicate.values.single()) > 0

            PortableOperator.LT ->
                present && actual != QueryValue.NullValue &&
                    compareValues(requireNotNull(actual), predicate.values.single()) < 0

            PortableOperator.GTE ->
                present && actual != QueryValue.NullValue &&
                    compareValues(requireNotNull(actual), predicate.values.single()) >= 0

            PortableOperator.LTE ->
                present && actual != QueryValue.NullValue &&
                    compareValues(requireNotNull(actual), predicate.values.single()) <= 0

            PortableOperator.CONTAINS -> present && stringMatch(actual, predicate, String::contains)
            PortableOperator.STARTS_WITH -> present && stringMatch(actual, predicate, String::startsWith)
            PortableOperator.ENDS_WITH -> present && stringMatch(actual, predicate, String::endsWith)
            PortableOperator.IN -> present && anyEqual(actual, predicate.values)
            PortableOperator.NOT_IN -> present && !anyEqual(actual, predicate.values)
            PortableOperator.BETWEEN ->
                present && actual != QueryValue.NullValue &&
                    compareValues(requireNotNull(actual), predicate.values[0]) >= 0 &&
                    compareValues(actual, predicate.values[1]) <= 0

            PortableOperator.ALL_IN -> present && allIn(actual, predicate.values)
            PortableOperator.NULL -> present && actual == QueryValue.NullValue
            PortableOperator.NOT_NULL -> present && actual != QueryValue.NullValue
            PortableOperator.TRUE -> present && actual == QueryValue.BooleanValue(true)
            PortableOperator.FALSE -> present && actual == QueryValue.BooleanValue(false)
            PortableOperator.EXISTS -> present == (predicate.values.single() as QueryValue.BooleanValue).value
        }
    }

    private fun stringMatch(
        actual: QueryValue?,
        predicate: PredicateExpression,
        comparison: String.(String) -> Boolean
    ): Boolean {
        val actualString = (actual as? QueryValue.StringValue)?.value ?: return false
        val operand = (predicate.values.single() as QueryValue.StringValue).value
        return when (predicate.stringComparison) {
            StringComparisonMode.DEFAULT,
            StringComparisonMode.CASE_SENSITIVE -> actualString.comparison(operand)

            StringComparisonMode.CASE_INSENSITIVE -> actualString.lowercase().comparison(operand.lowercase())
        }
    }

    private fun anyEqual(actual: QueryValue?, expected: List<QueryValue>): Boolean = when (actual) {
        is QueryValue.ListValue -> actual.values.any { candidate -> expected.any { equal(candidate, it) } }
        else -> expected.any { equal(actual, it) }
    }

    private fun allIn(actual: QueryValue?, expected: List<QueryValue>): Boolean {
        val collection = (actual as? QueryValue.ListValue)?.values ?: return false
        return expected.all { item -> collection.any { equal(it, item) } }
    }

    private fun equal(first: QueryValue?, second: QueryValue): Boolean = when {
        first == null -> false
        first.isNumber() && second.isNumber() -> first.decimal().compareTo(second.decimal()) == 0
        else -> first == second
    }

    private fun compareValues(first: QueryValue, second: QueryValue): Int = when {
        first.isNumber() && second.isNumber() -> first.decimal().compareTo(second.decimal())
        first is QueryValue.StringValue && second is QueryValue.StringValue -> first.value.compareTo(second.value)
        first is QueryValue.EnumValue && second is QueryValue.EnumValue -> first.value.compareTo(second.value)
        first is QueryValue.InstantValue && second is QueryValue.InstantValue -> first.value.compareTo(second.value)
        first is QueryValue.BooleanValue && second is QueryValue.BooleanValue -> first.value.compareTo(second.value)
        else -> error("In-memory TCK backend cannot compare incompatible query values.")
    }

    private fun QueryValue?.isNumber(): Boolean =
        this is QueryValue.IntegerValue || this is QueryValue.FloatingValue || this is QueryValue.DecimalValue

    private fun QueryValue.decimal(): BigDecimal = when (this) {
        is QueryValue.IntegerValue -> BigDecimal.valueOf(value)
        is QueryValue.FloatingValue -> BigDecimal.valueOf(value)
        is QueryValue.DecimalValue -> value
        else -> error("Query value is not numeric.")
    }

    private fun contains(values: Map<*, QueryValue>, field: String): Boolean =
        values.keys.any { key -> key.toString() == field }

    private fun value(values: Map<*, QueryValue>, field: String): QueryValue? =
        values.entries.firstOrNull { (key, _) -> key.toString() == field }?.value

    @Suppress("UNCHECKED_CAST")
    private fun <R : Any> decode(
        document: PortableStoredQueryDocument,
        shape: QueryPlanResultShape
    ): R = when (shape) {
        is QueryPlanResultShape.Typed -> {
            require(shape.resultType == PortableQueryResult::class.java) {
                "In-memory TCK backend only supports PortableQueryResult typed decoding."
            }
            PortableQueryResult(document.logicalId) as R
        }

        is QueryPlanResultShape.Dynamic -> {
            val result = LinkedHashMap<String, Any?>()
            shape.fields.forEach { field ->
                if (document.fields.containsKey(field)) {
                    result[field.value] = document.fields.getValue(field).toRawValue()
                }
            }
            ImmutableDynamicDocument.copyOf(result) as R
        }

        QueryPlanResultShape.Count -> error("Count plans do not decode result documents.")
    }

    private fun QueryValue.toRawValue(): Any? = when (this) {
        is QueryValue.BooleanValue -> value
        is QueryValue.IntegerValue -> value
        is QueryValue.FloatingValue -> value
        is QueryValue.DecimalValue -> value
        is QueryValue.StringValue -> value
        is QueryValue.InstantValue -> value
        is QueryValue.EnumValue -> value
        is QueryValue.ListValue -> values.map { it.toRawValue() }
        is QueryValue.ObjectValue -> values.mapValues { (_, value) -> value.toRawValue() }
        is QueryValue.BinaryValue -> value
        QueryValue.NullValue -> null
    }

    private fun unsupportedCapability(): QueryException = QueryException(
        QueryErrorCode.UNSUPPORTED_CAPABILITY,
        QueryStage.EXECUTION,
        QueryErrorReason.CAPABILITY_DENIED
    )
}
