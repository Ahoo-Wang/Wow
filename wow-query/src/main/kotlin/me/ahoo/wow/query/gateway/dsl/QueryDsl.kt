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

package me.ahoo.wow.query.gateway.dsl

import me.ahoo.wow.api.query.DeletionScope
import me.ahoo.wow.api.query.ElementMatchExpression
import me.ahoo.wow.api.query.LogicalExpression
import me.ahoo.wow.api.query.LogicalField
import me.ahoo.wow.api.query.LogicalOperator
import me.ahoo.wow.api.query.PredicateExpression
import me.ahoo.wow.api.query.PredicateOperator
import me.ahoo.wow.api.query.Query
import me.ahoo.wow.api.query.QueryBudget
import me.ahoo.wow.api.query.QueryExpression
import me.ahoo.wow.api.query.QueryProjection
import me.ahoo.wow.api.query.QueryScope
import me.ahoo.wow.api.query.QuerySort
import me.ahoo.wow.api.query.QuerySortDirection
import me.ahoo.wow.api.query.SearchExpression
import me.ahoo.wow.serialization.JsonSerializer
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory
import java.time.Duration

@DslMarker
annotation class QueryDslMarker

@QueryDslMarker
class QueryScopeDsl {
    private var tenantId: String? = null
    private var ownerId: String? = null
    private var spaceId: String? = null
    private var deletion: DeletionScope = DeletionScope.DEFAULT
    private var deletionSet = false

    fun tenantId(value: String) {
        require(tenantId == null) { "tenantId can only be set once." }
        require(value.isNotBlank()) { "tenantId cannot be blank." }
        tenantId = value
    }

    fun ownerId(value: String) {
        require(ownerId == null) { "ownerId can only be set once." }
        require(value.isNotBlank()) { "ownerId cannot be blank." }
        ownerId = value
    }

    fun spaceId(value: String) {
        require(spaceId == null) { "spaceId can only be set once." }
        require(value.isNotBlank()) { "spaceId cannot be blank." }
        spaceId = value
    }

    fun deletion(value: DeletionScope) {
        require(!deletionSet) { "deletion can only be set once." }
        deletionSet = true
        deletion = value
    }

    internal fun build(): QueryScope = QueryScope(tenantId, ownerId, spaceId, deletion)
}

fun queryScope(block: QueryScopeDsl.() -> Unit): QueryScope = QueryScopeDsl().apply(block).build()

@QueryDslMarker
class QueryBudgetDsl {
    private var timeout: Duration? = null
    private var maxRecords: Long? = null

    fun timeout(value: Duration) {
        require(timeout == null) { "timeout can only be set once." }
        timeout = value
    }

    fun maxRecords(value: Long) {
        require(maxRecords == null) { "maxRecords can only be set once." }
        maxRecords = value
    }

    internal fun build(): QueryBudget = QueryBudget(timeout, maxRecords)
}

fun queryBudget(block: QueryBudgetDsl.() -> Unit): QueryBudget = QueryBudgetDsl().apply(block).build()

@QueryDslMarker
class ExpressionDsl {
    fun field(path: String): FieldReference = FieldReference(LogicalField(path))

    fun and(vararg expressions: QueryExpression): QueryExpression =
        LogicalExpression(LogicalOperator.AND, expressions.toList())

    fun or(vararg expressions: QueryExpression): QueryExpression =
        LogicalExpression(LogicalOperator.OR, expressions.toList())

    fun nor(vararg expressions: QueryExpression): QueryExpression =
        LogicalExpression(LogicalOperator.NOR, expressions.toList())

    fun elementMatch(path: String, block: ExpressionDsl.() -> QueryExpression): QueryExpression =
        ElementMatchExpression(LogicalField(path), ExpressionDsl().block())

    fun search(query: String, vararg fields: String): QueryExpression =
        SearchExpression(query, fields.mapTo(linkedSetOf(), ::LogicalField))
}

class FieldReference internal constructor(internal val field: LogicalField) {
    infix fun eq(value: Any?): QueryExpression = predicate(PredicateOperator.EQ, value)

    infix fun ne(value: Any?): QueryExpression = predicate(PredicateOperator.NE, value)

    infix fun gt(value: Any?): QueryExpression = predicate(PredicateOperator.GT, value)

    infix fun lt(value: Any?): QueryExpression = predicate(PredicateOperator.LT, value)

    infix fun gte(value: Any?): QueryExpression = predicate(PredicateOperator.GTE, value)

    infix fun lte(value: Any?): QueryExpression = predicate(PredicateOperator.LTE, value)

    infix fun contains(value: Any?): QueryExpression = predicate(PredicateOperator.CONTAINS, value)

    infix fun inside(values: Iterable<*>): QueryExpression =
        PredicateExpression(field, PredicateOperator.IN, values.map(::literal))

    infix fun notInside(values: Iterable<*>): QueryExpression =
        PredicateExpression(field, PredicateOperator.NOT_IN, values.map(::literal))

    infix fun between(range: Pair<*, *>): QueryExpression =
        PredicateExpression(field, PredicateOperator.BETWEEN, listOf(literal(range.first), literal(range.second)))

    infix fun containsAll(values: Iterable<*>): QueryExpression =
        PredicateExpression(field, PredicateOperator.CONTAINS_ALL, values.map(::literal))

    infix fun startsWith(value: Any?): QueryExpression = predicate(PredicateOperator.STARTS_WITH, value)

    infix fun endsWith(value: Any?): QueryExpression = predicate(PredicateOperator.ENDS_WITH, value)

    infix fun search(query: String): QueryExpression = SearchExpression(query, setOf(field))

    fun isNull(): QueryExpression = predicate(PredicateOperator.IS_NULL)

    fun isNotNull(): QueryExpression = predicate(PredicateOperator.IS_NOT_NULL)

    fun isTrue(): QueryExpression = predicate(PredicateOperator.IS_TRUE)

    fun isFalse(): QueryExpression = predicate(PredicateOperator.IS_FALSE)

    fun exists(): QueryExpression = predicate(PredicateOperator.EXISTS)

    fun isEmpty(): QueryExpression = predicate(PredicateOperator.IS_EMPTY)

    private fun predicate(operator: PredicateOperator, vararg values: Any?): QueryExpression =
        PredicateExpression(field, operator, values.map(::literal))
}

private fun literal(value: Any?): JsonNode = when (value) {
    null -> JsonNodeFactory.instance.nullNode()
    is JsonNode -> value.deepCopy()
    else -> JsonSerializer.valueToTree(value)
}

@QueryDslMarker
class SortDsl {
    private val values = mutableListOf<QuerySort>()

    fun asc(field: String) {
        values += QuerySort(LogicalField(field), QuerySortDirection.ASC)
    }

    fun desc(field: String) {
        values += QuerySort(LogicalField(field), QuerySortDirection.DESC)
    }

    internal fun build(): List<QuerySort> = values.toList()
}

@QueryDslMarker
class ProjectionDsl {
    private var projection: QueryProjection? = null

    fun include(vararg fields: String) {
        set(QueryProjection.Include(fields.mapTo(linkedSetOf(), ::LogicalField)))
    }

    fun exclude(vararg fields: String) {
        set(QueryProjection.Exclude(fields.mapTo(linkedSetOf(), ::LogicalField)))
    }

    private fun set(value: QueryProjection) {
        require(projection == null) { "projection can only be set once." }
        projection = value
    }

    internal fun build(): QueryProjection = projection ?: QueryProjection.All
}

@QueryDslMarker
open class SnapshotQueryDsl {
    private var filter: QueryExpression? = null
    private var sort: List<QuerySort>? = null
    private var scope: QueryScope? = null
    private var budget: QueryBudget? = null

    fun filter(expression: QueryExpression) {
        require(filter == null) { "filter can only be set once." }
        filter = expression
    }

    fun filter(block: ExpressionDsl.() -> QueryExpression) {
        filter(ExpressionDsl().block())
    }

    fun sort(sort: List<QuerySort>) {
        require(this.sort == null) { "sort can only be set once." }
        this.sort = sort.toList()
    }

    fun sort(block: SortDsl.() -> Unit) {
        sort(SortDsl().apply(block).build())
    }

    fun scope(scope: QueryScope) {
        require(this.scope == null) { "scope can only be set once." }
        this.scope = scope
    }

    fun scope(block: QueryScopeDsl.() -> Unit) {
        scope(queryScope(block))
    }

    fun budget(budget: QueryBudget) {
        require(this.budget == null) { "budget can only be set once." }
        this.budget = budget
    }

    fun budget(block: QueryBudgetDsl.() -> Unit) {
        budget(queryBudget(block))
    }

    internal open fun build(): Query = Query(
        filter = filter ?: me.ahoo.wow.api.query.MatchAll,
        sort = sort ?: emptyList(),
        scope = scope ?: QueryScope(),
        budget = budget ?: QueryBudget()
    )
}

@QueryDslMarker
class SnapshotRecordQueryDsl : SnapshotQueryDsl() {
    private var projection: QueryProjection? = null

    fun projection(projection: QueryProjection) {
        require(this.projection == null) { "projection can only be set once." }
        this.projection = projection
    }

    fun projection(block: ProjectionDsl.() -> Unit) {
        projection(ProjectionDsl().apply(block).build())
    }

    override fun build(): Query = super.build().copy(projection = projection ?: QueryProjection.All)
}

@QueryDslMarker
class SnapshotCountQueryDsl {
    private var filter: QueryExpression? = null
    private var scope: QueryScope? = null
    private var budget: QueryBudget? = null

    fun filter(expression: QueryExpression) {
        require(filter == null) { "filter can only be set once." }
        filter = expression
    }

    fun filter(block: ExpressionDsl.() -> QueryExpression) {
        filter(ExpressionDsl().block())
    }

    fun scope(scope: QueryScope) {
        require(this.scope == null) { "scope can only be set once." }
        this.scope = scope
    }

    fun scope(block: QueryScopeDsl.() -> Unit) {
        scope(queryScope(block))
    }

    fun budget(budget: QueryBudget) {
        require(this.budget == null) { "budget can only be set once." }
        this.budget = budget
    }

    fun budget(block: QueryBudgetDsl.() -> Unit) {
        budget(queryBudget(block))
    }

    internal fun build(): Query = Query(
        filter = filter ?: me.ahoo.wow.api.query.MatchAll,
        scope = scope ?: QueryScope(),
        budget = budget ?: QueryBudget()
    )
}
