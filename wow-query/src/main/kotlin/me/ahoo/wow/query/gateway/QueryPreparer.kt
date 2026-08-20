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

package me.ahoo.wow.query.gateway

import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.ElementMatchExpression
import me.ahoo.wow.api.query.LegacyConditionExpression
import me.ahoo.wow.api.query.LogicalExpression
import me.ahoo.wow.api.query.LogicalField
import me.ahoo.wow.api.query.LogicalOperator
import me.ahoo.wow.api.query.MatchAll
import me.ahoo.wow.api.query.MatchNone
import me.ahoo.wow.api.query.PredicateExpression
import me.ahoo.wow.api.query.PredicateOperator
import me.ahoo.wow.api.query.Query
import me.ahoo.wow.api.query.QueryBudget
import me.ahoo.wow.api.query.QueryErrorCode
import me.ahoo.wow.api.query.QueryException
import me.ahoo.wow.api.query.QueryExpression
import me.ahoo.wow.api.query.QueryProjection
import me.ahoo.wow.api.query.QueryStage
import me.ahoo.wow.api.query.RelativeTimeExpression
import me.ahoo.wow.api.query.RelativeTimeOperator
import me.ahoo.wow.api.query.SearchExpression
import me.ahoo.wow.api.query.StringComparison
import me.ahoo.wow.modeling.metadata.AggregateMetadata
import me.ahoo.wow.query.backend.SecuredQuery
import me.ahoo.wow.query.policy.CapabilityDecision
import me.ahoo.wow.query.policy.QueryAuthorization
import me.ahoo.wow.query.policy.QueryCallContext
import me.ahoo.wow.query.policy.QueryFieldAccess
import me.ahoo.wow.query.policy.QueryOperation
import me.ahoo.wow.query.policy.QueryPolicy
import me.ahoo.wow.query.policy.QueryPolicyContext
import me.ahoo.wow.query.policy.QueryResultKind
import me.ahoo.wow.query.policy.requestedCapabilities
import me.ahoo.wow.query.policy.secureFilter
import me.ahoo.wow.query.schema.QueryCollectionKind
import me.ahoo.wow.query.schema.QueryFieldSchema
import me.ahoo.wow.query.schema.QuerySchema
import me.ahoo.wow.query.schema.QueryValueKind
import reactor.core.publisher.Mono
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory
import java.time.Clock
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.Base64
import java.util.concurrent.TimeoutException

data class QueryLimits(
    val maxPageSize: Int = 1_000,
    val maximumBudget: QueryBudget = QueryBudget()
) {
    init {
        require(maxPageSize > 0) { "maxPageSize must be positive." }
    }
}

internal class QueryPreparer(
    private val policy: QueryPolicy,
    private val limits: QueryLimits,
    private val zoneId: ZoneId,
    private val clock: Clock
) {
    fun prepare(
        metadata: AggregateMetadata<*, *>,
        schema: QuerySchema,
        query: Query,
        operation: QueryOperation,
        resultKind: QueryResultKind,
        call: QueryCallContext,
        page: Int? = null,
        size: Int? = null,
        limit: Int? = null
    ): Mono<SecuredQuery> = Mono.defer {
        try {
            val normalized = snapshotAndNormalize(query, schema, call.subscribedAt)
            validateRequest(normalized, schema, resultKind, page, size, limit)
            val admissionDeadline = deadline(
                call.subscribedAt,
                minBudget(normalized.budget, limits.maximumBudget).timeout
            )
            val context = QueryPolicyContext(
                target = metadata.namedAggregate,
                stateType = metadata.state.aggregateType,
                operation = operation,
                resultKind = resultKind,
                query = normalized,
                authority = call.authority,
                schema = schema,
                subscribedAt = call.subscribedAt,
                deadline = admissionDeadline,
                page = page,
                size = size,
                limit = limit
            )
            enforceDeadline(policy.evaluate(context), admissionDeadline)
                .map { authorization ->
                    secure(metadata, schema, normalized, operation, resultKind, authorization, page, size, limit, call)
                }
        } catch (@Suppress("TooGenericExceptionCaught") error: RuntimeException) {
            Mono.error(mapPreparationError(error))
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun secure(
        metadata: AggregateMetadata<*, *>,
        schema: QuerySchema,
        query: Query,
        operation: QueryOperation,
        resultKind: QueryResultKind,
        authorization: QueryAuthorization,
        page: Int?,
        size: Int?,
        limit: Int?,
        call: QueryCallContext
    ): SecuredQuery {
        val securedFilter = secureFilter(query.filter, authorization.mandatoryFilter)
        validateExpression(securedFilter, schema)
        val capabilities = requestedCapabilities(securedFilter)
        capabilities.forEach { capability ->
            if (authorization.capabilities[capability] != CapabilityDecision.GRANT) policyDenied()
        }
        val legacy = query.filter.containsLegacyCondition()
        val resultFields = resultFields(query.projection, schema, legacy)
        val requiredFields = linkedSetOf<LogicalField>().apply {
            if (legacy) {
                addAll(schema.fields.keys)
            } else {
                addAll(expressionFields(query.filter))
                addAll(query.sort.map { it.field })
            }
            when (resultKind) {
                QueryResultKind.SNAPSHOT -> addAll(schema.fields.keys)
                QueryResultKind.RECORD -> addAll(resultFields)
                QueryResultKind.COUNT -> Unit
            }
        }
        when (val access = authorization.fieldAccess) {
            QueryFieldAccess.Unrestricted -> Unit
            is QueryFieldAccess.Restricted -> if (!access.fields.containsAll(requiredFields)) policyDenied()
        }
        val budget = minBudget(query.budget, authorization.maximumBudget, limits.maximumBudget)
        val effectiveLimit = when (operation) {
            QueryOperation.FIRST -> 1
            QueryOperation.PAGE -> size
            QueryOperation.STREAM -> limit
            QueryOperation.COUNT -> null
        }
        val maxRecords = budget.maxRecords
        if (effectiveLimit != null && maxRecords != null && effectiveLimit > maxRecords) {
            throw QueryException(QueryErrorCode.BUDGET_EXCEEDED, QueryStage.PREPARATION)
        }
        val offset = if (page == null || size == null) 0 else Math.multiplyExact(page.toLong() - 1, size.toLong())
        return SecuredQuery.create(
            target = metadata.namedAggregate,
            operation = operation,
            resultKind = resultKind,
            filter = securedFilter,
            sort = query.sort,
            offset = offset,
            limit = effectiveLimit,
            resultFields = resultFields,
            projection = query.projection,
            recordFields = schema.fields.keys,
            capabilities = capabilities,
            budget = budget,
            deadline = deadline(call.subscribedAt, budget.timeout),
            schema = schema
        )
    }

    private fun snapshotAndNormalize(query: Query, schema: QuerySchema, frozenInstant: Instant): Query = Query(
        filter = normalizeLiterals(
            normalize(snapshotExpression(query.filter.also(::validateExpressionShape)), frozenInstant),
            schema
        ),
        projection = when (val projection = query.projection) {
            QueryProjection.All -> QueryProjection.All
            is QueryProjection.Include -> QueryProjection.Include(projection.fields.toSet())
            is QueryProjection.Exclude -> QueryProjection.Exclude(projection.fields.toSet())
            is QueryProjection.Legacy -> projection.copy(
                include = projection.include.toList(),
                exclude = projection.exclude.toList()
            )
        },
        sort = query.sort.toList(),
        scope = query.scope.copy(),
        budget = query.budget.copy()
    )

    private fun snapshotExpression(expression: QueryExpression): QueryExpression = when (expression) {
        MatchAll -> MatchAll
        MatchNone -> MatchNone
        is LogicalExpression -> expression.copy(operands = expression.operands.map(::snapshotExpression))
        is PredicateExpression -> expression.copy(values = expression.values.map(::snapshotLiteral))
        is ElementMatchExpression -> expression.copy(predicate = snapshotExpression(expression.predicate))
        is LegacyConditionExpression -> expression.copy(condition = snapshotCondition(expression.condition))
        is SearchExpression -> expression.copy(fields = expression.fields.toSet())
        is RelativeTimeExpression -> expression.copy(values = expression.values.map(::snapshotLiteral))
    }

    @Suppress("CyclomaticComplexMethod")
    private fun validateExpressionShape(expression: QueryExpression) {
        var nodes = 0
        val pending = ArrayDeque<Pair<QueryExpression, Int>>()
        pending += expression to 1
        while (pending.isNotEmpty()) {
            val (current, depth) = pending.removeLast()
            if (depth > MAX_EXPRESSION_DEPTH || ++nodes > MAX_QUERY_NODES) invalidQuery()
            when (current) {
                is LogicalExpression -> current.operands.forEach { pending += it to depth + 1 }
                is ElementMatchExpression -> pending += current.predicate to depth + 1
                is LegacyConditionExpression -> {
                    val conditions = ArrayDeque<Pair<Condition, Int>>()
                    conditions += current.condition to depth
                    while (conditions.isNotEmpty()) {
                        val (condition, conditionDepth) = conditions.removeLast()
                        if (conditionDepth > MAX_EXPRESSION_DEPTH || ++nodes > MAX_QUERY_NODES) invalidQuery()
                        condition.children.forEach { conditions += it to conditionDepth + 1 }
                    }
                }
                is PredicateExpression -> nodes += current.values.size
                is SearchExpression -> nodes += current.fields.size
                is RelativeTimeExpression -> nodes += current.values.size
                MatchAll,
                MatchNone -> Unit
            }
            if (nodes > MAX_QUERY_NODES) invalidQuery()
        }
    }

    private fun snapshotLiteral(value: JsonNode): JsonNode = when {
        value.isMissingNode || value.isPojo -> invalidQuery()
        value.isBinary -> JsonNodeFactory.instance.stringNode(Base64.getEncoder().encodeToString(value.binaryValue()))
        value.isFloatingPointNumber && !value.doubleValue().isFinite() -> invalidQuery()
        value is tools.jackson.databind.node.ObjectNode || value is tools.jackson.databind.node.ArrayNode -> invalidQuery()
        else -> value.deepCopy()
    }

    private fun snapshotCondition(condition: Condition): Condition = condition.copy(
        value = when (val value = condition.value) {
            is ByteArray -> value.copyOf()
            is Collection<*> -> value.toList()
            is Map<*, *> -> value.toMap()
            else -> value
        },
        children = condition.children.map(::snapshotCondition),
        options = condition.options.toMap()
    )

    private fun normalize(expression: QueryExpression, frozenInstant: Instant): QueryExpression = when (expression) {
        is RelativeTimeExpression -> lower(expression, frozenInstant)
        is LogicalExpression -> expression.copy(operands = expression.operands.map { normalize(it, frozenInstant) })
        is ElementMatchExpression -> expression.copy(predicate = normalize(expression.predicate, frozenInstant))
        else -> expression
    }

    private fun normalizeLiterals(
        expression: QueryExpression,
        schema: QuerySchema,
        prefix: String? = null
    ): QueryExpression = when (expression) {
        is PredicateExpression -> {
            val field = schema[effectiveField(expression.field, prefix)] ?: invalidQuery()
            expression.copy(values = expression.values.map { value -> normalizeLiteral(value, field.valueKind) })
        }

        is LogicalExpression -> expression.copy(
            operands = expression.operands.map { operand -> normalizeLiterals(operand, schema, prefix) }
        )

        is ElementMatchExpression -> {
            val effective = effectiveField(expression.field, prefix)
            expression.copy(predicate = normalizeLiterals(expression.predicate, schema, effective.value))
        }

        else -> expression
    }

    private fun normalizeLiteral(value: JsonNode, kind: QueryValueKind): JsonNode = when {
        value.isNull -> value
        kind == QueryValueKind.TIME && value.isIntegralNumber ->
            JsonNodeFactory.instance.stringNode(Instant.ofEpochMilli(value.longValue()).toString())

        else -> value
    }

    private fun lower(expression: RelativeTimeExpression, frozenInstant: Instant): QueryExpression {
        val effectiveZone = expression.zoneId?.let(ZoneId::of) ?: zoneId
        val today = frozenInstant.atZone(effectiveZone).toLocalDate()
        return when (expression.operator) {
            RelativeTimeOperator.TODAY -> range(expression.field, today, today.plusDays(1), effectiveZone)
            RelativeTimeOperator.BEFORE_TODAY -> predicate(
                expression.field,
                PredicateOperator.LT,
                today.atTime(operandSeconds(expression)).atZone(effectiveZone).toInstant()
            )

            RelativeTimeOperator.TOMORROW -> range(
                expression.field,
                today.plusDays(1),
                today.plusDays(2),
                effectiveZone
            )
            RelativeTimeOperator.THIS_WEEK -> {
                val start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                range(expression.field, start, start.plusWeeks(1), effectiveZone)
            }

            RelativeTimeOperator.NEXT_WEEK -> {
                val start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(1)
                range(expression.field, start, start.plusWeeks(1), effectiveZone)
            }

            RelativeTimeOperator.LAST_WEEK -> {
                val end = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                range(expression.field, end.minusWeeks(1), end, effectiveZone)
            }

            RelativeTimeOperator.THIS_MONTH -> {
                val start = today.withDayOfMonth(1)
                range(expression.field, start, start.plusMonths(1), effectiveZone)
            }

            RelativeTimeOperator.LAST_MONTH -> {
                val end = today.withDayOfMonth(1)
                range(expression.field, end.minusMonths(1), end, effectiveZone)
            }

            RelativeTimeOperator.RECENT_DAYS -> {
                val days = operandDays(expression)
                range(expression.field, today.minusDays(days - 1), today.plusDays(1), effectiveZone)
            }

            RelativeTimeOperator.EARLIER_DAYS -> predicate(
                expression.field,
                PredicateOperator.LT,
                today.minusDays(operandDays(expression) - 1).atStartOfDay(effectiveZone).toInstant()
            )
        }
    }

    private fun operandSeconds(expression: RelativeTimeExpression): LocalTime {
        val seconds = expression.values.singleOrNull()?.takeIf(JsonNode::isIntegralNumber)?.longValue() ?: invalidQuery()
        return LocalTime.ofSecondOfDay(seconds)
    }

    private fun operandDays(expression: RelativeTimeExpression): Long =
        expression.values.singleOrNull()?.takeIf(JsonNode::isIntegralNumber)?.longValue()?.takeIf { it > 0 }
            ?: invalidQuery()

    private fun range(field: LogicalField, start: LocalDate, end: LocalDate, zone: ZoneId): QueryExpression =
        LogicalExpression(
            LogicalOperator.AND,
            listOf(
                predicate(field, PredicateOperator.GTE, start.atStartOfDay(zone).toInstant()),
                predicate(field, PredicateOperator.LT, end.atStartOfDay(zone).toInstant())
            )
        )

    private fun predicate(field: LogicalField, operator: PredicateOperator, value: Instant): PredicateExpression =
        PredicateExpression(field, operator, listOf(JsonNodeFactory.instance.stringNode(value.toString())))

    private fun validateRequest(
        query: Query,
        schema: QuerySchema,
        resultKind: QueryResultKind,
        page: Int?,
        size: Int?,
        limit: Int?
    ) {
        validateExpression(query.filter, schema)
        if (resultKind == QueryResultKind.SNAPSHOT && query.projection != QueryProjection.All) invalidQuery()
        val legacy = query.filter.containsLegacyCondition()
        resultFields(query.projection, schema, legacy)
        if (!legacy) {
            query.sort.forEach { sort ->
                val field = schema[sort.field] ?: invalidQuery()
                if (!field.sortable) invalidQuery()
            }
        }
        validatePagination(page, size, legacy)
        if (limit != null && limit <= 0) {
            invalidQuery()
        }
    }

    private fun validatePagination(page: Int?, size: Int?, legacy: Boolean) {
        if (page == null && size == null) return
        if (page == null || size == null) invalidQuery()
        if (page < 1 || size < 1) invalidQuery()
        if (!legacy && size > limits.maxPageSize) invalidQuery()
    }

    @Suppress("CyclomaticComplexMethod")
    private fun validateExpression(expression: QueryExpression, schema: QuerySchema, prefix: String? = null) {
        when (expression) {
            MatchAll,
            MatchNone -> Unit

            is LogicalExpression -> expression.operands.forEach { validateExpression(it, schema, prefix) }
            is PredicateExpression -> {
                val field = schema[effectiveField(expression.field, prefix)] ?: invalidQuery()
                if (!field.queryable || expression.operator !in field.operators) invalidQuery()
                validateCardinality(expression)
                validateStringComparison(expression)
                expression.values.forEach { value -> validateLiteral(value, field, expression.operator) }
            }

            is ElementMatchExpression -> {
                val effective = effectiveField(expression.field, prefix)
                val field = schema[effective] ?: invalidQuery()
                if (field.collectionKind != QueryCollectionKind.OBJECT || !field.elementMatch) invalidQuery()
                validateExpression(expression.predicate, schema, effective.value)
            }

            is LegacyConditionExpression -> Unit

            is SearchExpression -> expression.fields.forEach { requested ->
                val field = schema[effectiveField(requested, prefix)] ?: invalidQuery()
                if (!field.fullText) invalidQuery()
            }

            is RelativeTimeExpression -> invalidQuery()
        }
    }

    private fun validateCardinality(expression: PredicateExpression) {
        val count = expression.values.size
        val valid = when (expression.operator) {
            PredicateOperator.IS_NULL,
            PredicateOperator.IS_NOT_NULL,
            PredicateOperator.IS_TRUE,
            PredicateOperator.IS_FALSE,
            PredicateOperator.EXISTS,
            PredicateOperator.IS_EMPTY -> count == 0

            PredicateOperator.IN,
            PredicateOperator.NOT_IN,
            PredicateOperator.CONTAINS_ALL -> count > 0
            PredicateOperator.BETWEEN -> count == 2
            else -> count == 1
        }
        if (!valid) invalidQuery()
    }

    private fun validateStringComparison(expression: PredicateExpression) {
        if (expression.stringComparison == StringComparison.DEFAULT) return
        if (expression.operator !in STRING_OPERATORS) invalidQuery()
    }

    @Suppress("CyclomaticComplexMethod")
    private fun validateLiteral(value: JsonNode, field: QueryFieldSchema, operator: PredicateOperator) {
        if (value.isNull) {
            if (field.collectionKind != QueryCollectionKind.NONE || operator !in NULL_LITERAL_OPERATORS) invalidQuery()
            return
        }
        val valid = when (field.valueKind) {
            QueryValueKind.BOOLEAN -> value.isBoolean
            QueryValueKind.INTEGER -> value.isIntegralNumber && value.canConvertToLong()
            QueryValueKind.DECIMAL -> value.isNumber
            QueryValueKind.STRING,
            QueryValueKind.ENUM -> value.isString

            QueryValueKind.TIME -> value.isString && runCatching { Instant.parse(value.stringValue()) }.isSuccess
            QueryValueKind.BINARY -> value.isString && runCatching {
                Base64.getDecoder().decode(value.stringValue())
            }.isSuccess

            QueryValueKind.OBJECT,
            QueryValueKind.MAP -> false
        }
        if (!valid) invalidQuery()
    }

    private fun resultFields(
        projection: QueryProjection,
        schema: QuerySchema,
        legacy: Boolean
    ): Set<LogicalField> = when (projection) {
        QueryProjection.All -> schema.fields.keys
        is QueryProjection.Include -> {
            if (projection.fields.any { schema[it]?.projectable != true }) invalidQuery()
            projection.fields.flatMapTo(linkedSetOf()) { selected ->
                schema.fields.keys.filter { candidate -> candidate == selected || candidate.isDescendantOf(selected) }
            }
        }

        is QueryProjection.Exclude -> schema.fields.keys.filterNotTo(linkedSetOf()) { candidate ->
            projection.fields.any { selected -> candidate == selected || candidate.isDescendantOf(selected) }
        }.also {
            if (projection.fields.any { field -> schema[field]?.projectable != true }) invalidQuery()
        }

        is QueryProjection.Legacy -> if (legacy) schema.fields.keys else invalidQuery()
    }

    private fun LogicalField.isDescendantOf(parent: LogicalField): Boolean = value.startsWith("${parent.value}.")

    private fun expressionFields(expression: QueryExpression, prefix: String? = null): Set<LogicalField> {
        val fields = linkedSetOf<LogicalField>()
        when (expression) {
            MatchAll,
            MatchNone -> Unit

            is PredicateExpression -> fields += effectiveField(expression.field, prefix)
            is SearchExpression -> expression.fields.mapTo(fields) { effectiveField(it, prefix) }
            is LogicalExpression -> expression.operands.forEach { fields += expressionFields(it, prefix) }
            is ElementMatchExpression -> {
                val effective = effectiveField(expression.field, prefix)
                fields += effective
                fields += expressionFields(expression.predicate, effective.value)
            }

            is LegacyConditionExpression -> Unit

            is RelativeTimeExpression -> fields += effectiveField(expression.field, prefix)
        }
        return fields
    }

    private fun QueryExpression.containsLegacyCondition(): Boolean = when (this) {
        is LegacyConditionExpression -> true
        is LogicalExpression -> operands.any { it.containsLegacyCondition() }
        is ElementMatchExpression -> predicate.containsLegacyCondition()
        else -> false
    }

    private fun effectiveField(field: LogicalField, prefix: String?): LogicalField =
        if (prefix == null) field else LogicalField("$prefix.${field.value}")

    private fun minBudget(vararg budgets: QueryBudget): QueryBudget = QueryBudget(
        timeout = budgets.mapNotNull(QueryBudget::timeout).minOrNull(),
        maxRecords = budgets.mapNotNull(QueryBudget::maxRecords).minOrNull()
    )

    private fun deadline(start: Instant, timeout: Duration?): Instant? = timeout?.let(start::plus)

    private fun <T : Any> enforceDeadline(publisher: Mono<T>, deadline: Instant?): Mono<T> {
        if (deadline == null) return publisher
        val remaining = Duration.between(clock.instant(), deadline)
        if (remaining.isNegative || remaining.isZero) {
            return Mono.error(QueryException(QueryErrorCode.DEADLINE_EXCEEDED, QueryStage.POLICY))
        }
        return publisher.timeout(remaining).onErrorMap(TimeoutException::class.java) {
            QueryException(QueryErrorCode.DEADLINE_EXCEEDED, QueryStage.POLICY)
        }
    }

    private fun mapPreparationError(error: RuntimeException): Throwable {
        return if (error is QueryException) {
            error
        } else {
            QueryException(
                QueryErrorCode.INVALID_QUERY,
                QueryStage.PREPARATION
            )
        }
    }

    private fun invalidQuery(): Nothing = throw QueryException(QueryErrorCode.INVALID_QUERY, QueryStage.PREPARATION)

    private fun policyDenied(): Nothing = throw QueryException(QueryErrorCode.POLICY_DENIED, QueryStage.POLICY)

    private companion object {
        val STRING_OPERATORS = setOf(
            PredicateOperator.CONTAINS,
            PredicateOperator.STARTS_WITH,
            PredicateOperator.ENDS_WITH
        )
        val NULL_LITERAL_OPERATORS = setOf(
            PredicateOperator.EQ,
            PredicateOperator.NE,
            PredicateOperator.IN,
            PredicateOperator.NOT_IN
        )
        const val MAX_EXPRESSION_DEPTH = 128
        const val MAX_QUERY_NODES = 10_000
    }
}
