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

package me.ahoo.wow.webflux.route.query

import me.ahoo.wow.api.annotation.ORDER_FIRST
import me.ahoo.wow.api.annotation.Order
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.ConditionCapable
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.Operator
import me.ahoo.wow.api.query.Queryable
import me.ahoo.wow.filter.FilterChain
import me.ahoo.wow.filter.FilterType
import me.ahoo.wow.query.event.filter.EventStreamQueryHandler
import me.ahoo.wow.query.filter.Contexts.getRawRequest
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.filter.QueryFilter
import me.ahoo.wow.query.filter.QueryType
import me.ahoo.wow.query.snapshot.filter.SnapshotQueryHandler
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.webflux.route.acceptsEventStream
import org.springframework.web.reactive.function.server.ServerRequest
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.ArrayDeque

@Order(ORDER_FIRST)
@FilterType(SnapshotQueryHandler::class, EventStreamQueryHandler::class)
class HttpQueryGuardFilter(
    private val maxListSize: Int = 1000,
    private val maxPageSize: Int = 100,
    private val maxPageWindow: Long = 10_000,
    private val maxConditionNodes: Int = 64,
    private val maxConditionValues: Int = 1000,
    private val allowedSortFields: Set<String> = emptySet(),
    private val allowedConditionFields: Set<String> = emptySet(),
    private val allowRaw: Boolean = false,
    private val allowExpensiveOperators: Boolean = false,
    private val idleTimeout: Duration = Duration.ofSeconds(10),
) : QueryFilter<QueryContext<*, *>> {

    init {
        require(maxListSize >= 0) { "maxListSize must be greater than or equal to 0." }
        require(maxPageSize >= 0) { "maxPageSize must be greater than or equal to 0." }
        require(maxPageWindow >= 0) { "maxPageWindow must be greater than or equal to 0." }
        require(maxConditionNodes >= 0) { "maxConditionNodes must be greater than or equal to 0." }
        require(maxConditionValues >= 0) { "maxConditionValues must be greater than or equal to 0." }
        require(!idleTimeout.isNegative) { "idleTimeout must be greater than or equal to 0." }
    }

    override fun filter(
        context: QueryContext<*, *>,
        next: FilterChain<QueryContext<*, *>>,
    ): Mono<Void> = Mono.deferContextual { contextView ->
        val request = contextView.getRawRequest<Any>() as? ServerRequest
        if (request == null) {
            return@deferContextual next.filter(context)
        }
        validate(context.queryType, context.getQuery())
        val downstream = next.filter(context)
        val guardedDownstream = if (idleTimeout.isZero) downstream else downstream.timeout(idleTimeout)
        guardedDownstream.doOnSuccess {
            applyIdleTimeout(context, request)
        }
    }

    private fun validate(queryType: QueryType, query: Any) {
        when (query) {
            is IListQuery -> validateList(query)
            is IPagedQuery -> validatePage(query)
            is AggregationQuery -> {
                validateResultSize(query.limit, "aggregation")
                require(query.sort.isEmpty() || FIELD_WILDCARD in allowedSortFields) {
                    "HTTP aggregation sort requires the allowed-sort-fields wildcard."
                }
            }
        }
        if (query is Queryable<*>) {
            val rejectedSortFields = query.sort.map { it.field }.filterNot { allowedSortFields.allows(it) }
            require(rejectedSortFields.isEmpty()) {
                "HTTP query sort fields$rejectedSortFields are not allowed."
            }
        }
        val condition = when (query) {
            is Condition -> query
            is ConditionCapable<*> -> query.condition
            else -> return
        }
        validateCondition(
            condition = condition,
            rejectMatchAll = !allowExpensiveOperators && queryType in COUNTING_QUERY_TYPES,
        )
    }

    private fun validateList(query: IListQuery) {
        validateResultSize(query.limit, "list")
    }

    private fun validateResultSize(limit: Int, queryName: String) {
        val minimum = if (maxListSize == 0) 0 else 1
        require(limit >= minimum && (maxListSize == 0 || limit <= maxListSize)) {
            "HTTP $queryName query limit[$limit] must be between $minimum and $maxListSize."
        }
    }

    private fun validatePage(query: IPagedQuery) {
        val pagination = query.pagination
        require(pagination.index >= 1) { "HTTP page index[${pagination.index}] must be greater than or equal to 1." }
        require(pagination.size >= 1 && (maxPageSize == 0 || pagination.size <= maxPageSize)) {
            "HTTP page size[${pagination.size}] must be between 1 and $maxPageSize."
        }
        val window = pagination.index.toLong() * pagination.size.toLong()
        require(maxPageWindow == 0L || window <= maxPageWindow) {
            "HTTP page window[$window] must not exceed $maxPageWindow."
        }
        val offset = (pagination.index.toLong() - 1) * pagination.size
        require(offset <= Int.MAX_VALUE) {
            "HTTP page offset[$offset] must not exceed ${Int.MAX_VALUE}."
        }
    }

    private fun validateCondition(condition: Condition, rejectMatchAll: Boolean) {
        val pending = ArrayDeque<Pair<Condition, String>>()
        pending.add(condition to "")
        val validatedNodes = mutableListOf<Pair<Condition, String>>()
        var nodes = 0
        while (pending.isNotEmpty()) {
            val (current, parentField) = pending.removeLast()
            nodes++
            require(maxConditionNodes == 0 || nodes <= maxConditionNodes) {
                "HTTP query condition nodes[$nodes] must not exceed $maxConditionNodes."
            }
            val effectiveField = when {
                current.operator == Operator.SPACE_ID -> MessageRecords.SPACE_ID
                current.field.isEmpty() -> ""
                parentField.isEmpty() -> current.field
                current.field == parentField || current.field.startsWith("$parentField.") -> current.field
                else -> "$parentField.${current.field}"
            }
            validatedNodes.add(current to effectiveField)
            val childParentField = if (current.operator == Operator.ELEM_MATCH) effectiveField else parentField
            current.children.forEach { pending.add(it to childParentField) }
        }
        val aggregateIdScoped = condition.isAggregateIdScoped()
        validatedNodes.forEach { (current, effectiveField) ->
            validateConditionNode(current, effectiveField, aggregateIdScoped)
        }
        require(!rejectMatchAll || !condition.isMatchAll()) {
            "HTTP counting query must not match all documents."
        }
    }

    private fun validateConditionNode(
        condition: Condition,
        effectiveField: String,
        aggregateIdScoped: Boolean,
    ) {
        if (condition.operator == Operator.ELEM_MATCH) {
            require(condition.children.size == 1) {
                "HTTP ELEM_MATCH condition must contain exactly one child."
            }
        }
        val fieldAllowed = effectiveField.isEmpty() && condition.operator in FIELDLESS_OPERATORS ||
            effectiveField in BUILT_IN_CONDITION_FIELDS ||
            effectiveField == MessageRecords.VERSION && aggregateIdScoped ||
            allowedConditionFields.allows(effectiveField)
        val hasIndexedElementChild = condition.operator == Operator.ELEM_MATCH &&
            condition.children.single().hasFieldOrDescendant()
        require(
            fieldAllowed || hasIndexedElementChild
        ) {
            "HTTP query condition field[$effectiveField] is not allowed."
        }
        require(allowRaw || condition.operator != Operator.RAW) {
            "HTTP query operator[RAW] is not allowed."
        }
        require(allowExpensiveOperators || !condition.isExpensive()) {
            "HTTP query operator[${condition.operator}] is disabled because expensive operators are not allowed."
        }
        val values = condition.value
        if (condition.operator in COLLECTION_OPERATORS && values is Collection<*>) {
            require(maxConditionValues == 0 || values.size <= maxConditionValues) {
                "HTTP query condition values[${values.size}] must not exceed $maxConditionValues."
            }
        }
    }

    private fun Condition.hasFieldOrDescendant(): Boolean {
        if (field.isNotEmpty()) return true
        val pending = ArrayDeque(children)
        while (pending.isNotEmpty()) {
            val current = pending.removeLast()
            if (current.field.isNotEmpty()) return true
            pending.addAll(current.children)
        }
        return false
    }

    private fun Set<String>.allows(field: String): Boolean = FIELD_WILDCARD in this || field in this

    private fun Condition.isExpensive(): Boolean =
        operator in EXPENSIVE_OPERATORS ||
            operator == Operator.EXISTS && value == false ||
            operator == Operator.STARTS_WITH && ((value as? String).isNullOrEmpty() || ignoreCase() == true)

    private fun Condition.isAggregateIdScoped(): Boolean = when (operator) {
        Operator.AGGREGATE_ID -> true
        Operator.EQ -> field == MessageRecords.AGGREGATE_ID
        Operator.AND -> children.any { it.isAggregateIdScoped() }
        Operator.OR -> children.isNotEmpty() && children.all { it.isAggregateIdScoped() }
        else -> false
    }

    private fun Condition.isMatchAll(): Boolean {
        val values = value
        return when (operator) {
            Operator.ALL -> true
            Operator.DELETED -> deletionState() == DeletionState.ALL
            Operator.NOT_IN -> values is Collection<*> && values.isEmpty()
            Operator.AND -> children.all { it.isMatchAll() }
            Operator.OR -> children.any { it.isMatchAll() }
            else -> false
        }
    }

    private fun applyIdleTimeout(context: QueryContext<*, *>, request: ServerRequest) {
        if (idleTimeout.isZero) return
        when (context.queryType) {
            QueryType.SINGLE, QueryType.DYNAMIC_SINGLE ->
                context.asSingleQuery<Any>().rewriteResult { it.timeout(idleTimeout) }

            QueryType.LIST, QueryType.DYNAMIC_LIST ->
                context.asListQuery<Any>().rewriteResult {
                    if (request.acceptsEventStream()) {
                        it.timeout(idleTimeout)
                    } else {
                        it.timeout(idleTimeout)
                            .collectList()
                            .flatMapMany(Flux<Any>::fromIterable)
                    }
                }

            QueryType.PAGED, QueryType.DYNAMIC_PAGED ->
                context.asPagedQuery<Any>().rewriteResult { it.timeout(idleTimeout) }

            QueryType.COUNT -> context.asCountQuery().rewriteResult { it.timeout(idleTimeout) }

            QueryType.AGGREGATION -> context.asAggregationQuery().rewriteResult {
                if (request.acceptsEventStream()) {
                    it.timeout(idleTimeout)
                } else {
                    it.timeout(idleTimeout)
                        .collectList()
                        .flatMapMany(Flux<Map<String, Any?>>::fromIterable)
                }
            }
        }
    }

    private companion object {
        val EXPENSIVE_OPERATORS = setOf(
            Operator.NE,
            Operator.NOT_IN,
            Operator.NOR,
            Operator.NULL,
            Operator.NOT_NULL,
            Operator.CONTAINS,
            Operator.ENDS_WITH,
        )
        val BUILT_IN_CONDITION_FIELDS = setOf(MessageRecords.AGGREGATE_ID)
        val FIELDLESS_OPERATORS = setOf(
            Operator.AND,
            Operator.OR,
            Operator.NOR,
            Operator.ID,
            Operator.IDS,
            Operator.AGGREGATE_ID,
            Operator.AGGREGATE_IDS,
            Operator.TENANT_ID,
            Operator.OWNER_ID,
            Operator.DELETED,
            Operator.ALL,
            Operator.RAW,
        )
        val COUNTING_QUERY_TYPES = setOf(
            QueryType.PAGED,
            QueryType.DYNAMIC_PAGED,
            QueryType.COUNT,
            QueryType.AGGREGATION,
        )
        const val FIELD_WILDCARD = "*"
        val COLLECTION_OPERATORS = setOf(
            Operator.IN,
            Operator.NOT_IN,
            Operator.ALL_IN,
            Operator.IDS,
            Operator.AGGREGATE_IDS,
        )
    }
}
