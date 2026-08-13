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

package me.ahoo.wow.query.expression

import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.Operator
import me.ahoo.wow.api.query.expression.ElementMatchExpression
import me.ahoo.wow.api.query.expression.FullTextExpression
import me.ahoo.wow.api.query.expression.LogicalField
import me.ahoo.wow.api.query.expression.LogicalOperator
import me.ahoo.wow.api.query.expression.MatchAll
import me.ahoo.wow.api.query.expression.NativeExpression
import me.ahoo.wow.api.query.expression.PortableExpression
import me.ahoo.wow.api.query.expression.PortableOperator
import me.ahoo.wow.api.query.expression.PredicateExpression
import me.ahoo.wow.api.query.expression.QueryCapabilityId
import me.ahoo.wow.api.query.expression.QueryExpression
import me.ahoo.wow.api.query.expression.StringComparisonMode
import me.ahoo.wow.api.query.gateway.DeletionScope
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.api.query.gateway.QueryTarget
import me.ahoo.wow.query.converter.DeleteConditionGuard.guard
import java.time.Instant
import java.time.ZoneId

object LegacyConditionLowerer {
    fun lower(
        condition: Condition,
        target: QueryTarget,
        frozenInstant: Instant,
        zoneId: ZoneId
    ): QueryExpression = LegacyConditionLowering.lower(condition, target, frozenInstant, zoneId)
}

internal object LegacyConditionLowering {
    @JvmSynthetic
    fun lower(
        condition: Condition,
        target: QueryTarget,
        frozenInstant: Instant,
        zoneId: ZoneId
    ): QueryExpression = try {
        val guarded = if (target.documentKind == QueryDocumentKind.SNAPSHOT) condition.guard() else condition
        ExpressionNormalizer.normalize(
            lowerInternal(guarded, target) { relative -> RelativeTimeNormalizer.lower(relative, frozenInstant, zoneId) }
        )
    } catch (error: me.ahoo.wow.api.query.error.QueryException) {
        throw error
    } catch (_: RuntimeException) {
        invalidQuery()
    }

    @JvmSynthetic
    internal fun lowerForGateway(
        condition: Condition,
        target: QueryTarget
    ): Pair<QueryExpression, DeletionScope> = try {
        val extracted = extractDeletion(condition, target)
        Pair(
            ExpressionNormalizer.normalize(
                lowerInternal(extracted.first, target, RelativeTimeExpressionNormalizer::defer)
            ),
            extracted.second
        )
    } catch (error: me.ahoo.wow.api.query.error.QueryException) {
        throw error
    } catch (_: RuntimeException) {
        invalidQuery()
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun lowerInternal(
        condition: Condition,
        target: QueryTarget,
        relativeTime: (Condition) -> QueryExpression
    ): QueryExpression =
        when (condition.operator) {
            Operator.AND -> logical(LogicalOperator.AND, condition, target, relativeTime)
            Operator.OR -> logical(LogicalOperator.OR, condition, target, relativeTime)
            Operator.NOR -> logical(LogicalOperator.NOR, condition, target, relativeTime)
            Operator.ID -> predicate(recordIdentityField(target), PortableOperator.EQ, condition.value)
            Operator.IDS -> predicateElements(recordIdentityField(target), PortableOperator.IN, condition.value)
            Operator.AGGREGATE_ID -> predicate(AGGREGATE_ID_FIELD, PortableOperator.EQ, condition.value)
            Operator.AGGREGATE_IDS -> predicateElements(
                AGGREGATE_ID_FIELD,
                PortableOperator.IN,
                condition.value
            )
            Operator.TENANT_ID -> predicate(TENANT_ID_FIELD, PortableOperator.EQ, condition.value)
            Operator.OWNER_ID -> predicate(OWNER_ID_FIELD, PortableOperator.EQ, condition.value)
            Operator.SPACE_ID -> predicate(SPACE_ID_FIELD, PortableOperator.EQ, condition.value)
            Operator.DELETED -> deleted(condition)
            Operator.ALL -> MatchAll
            Operator.EQ -> predicate(condition.field, PortableOperator.EQ, condition.value)
            Operator.NE -> predicate(condition.field, PortableOperator.NE, condition.value)
            Operator.GT -> predicate(condition.field, PortableOperator.GT, condition.value)
            Operator.LT -> predicate(condition.field, PortableOperator.LT, condition.value)
            Operator.GTE -> predicate(condition.field, PortableOperator.GTE, condition.value)
            Operator.LTE -> predicate(condition.field, PortableOperator.LTE, condition.value)
            Operator.CONTAINS -> stringPredicate(condition, PortableOperator.CONTAINS)
            Operator.IN -> predicateElements(condition.field, PortableOperator.IN, condition.value)
            Operator.NOT_IN -> predicateElements(condition.field, PortableOperator.NOT_IN, condition.value)
            Operator.BETWEEN -> predicateElements(condition.field, PortableOperator.BETWEEN, condition.value)
            Operator.ALL_IN -> predicateElements(condition.field, PortableOperator.ALL_IN, condition.value)
            Operator.STARTS_WITH -> stringPredicate(condition, PortableOperator.STARTS_WITH)
            Operator.ENDS_WITH -> stringPredicate(condition, PortableOperator.ENDS_WITH)
            Operator.ELEM_MATCH -> elementMatch(condition, target, relativeTime)
            Operator.NULL -> predicate(condition.field, PortableOperator.NULL)
            Operator.NOT_NULL -> predicate(condition.field, PortableOperator.NOT_NULL)
            Operator.TRUE -> predicate(condition.field, PortableOperator.TRUE)
            Operator.FALSE -> predicate(condition.field, PortableOperator.FALSE)
            Operator.EXISTS -> predicate(condition.field, PortableOperator.EXISTS, condition.value)
            Operator.TODAY,
            Operator.BEFORE_TODAY,
            Operator.TOMORROW,
            Operator.THIS_WEEK,
            Operator.NEXT_WEEK,
            Operator.LAST_WEEK,
            Operator.THIS_MONTH,
            Operator.LAST_MONTH,
            Operator.RECENT_DAYS,
            Operator.EARLIER_DAYS -> relativeTime(condition)
            Operator.MATCH -> FullTextExpression(
                FULL_TEXT_CAPABILITY,
                condition.value as? String ?: invalidQuery(),
                setOf(LogicalField(condition.field))
            )

            Operator.RAW -> condition.value as? NativeExpression ?: invalidQuery()
        }

    private fun logical(
        operator: LogicalOperator,
        condition: Condition,
        target: QueryTarget,
        relativeTime: (Condition) -> QueryExpression
    ): QueryExpression = ExpressionNormalizer.logical(
        operator,
        condition.children.map { lowerInternal(it, target, relativeTime) }
    )

    private fun elementMatch(
        condition: Condition,
        target: QueryTarget,
        relativeTime: (Condition) -> QueryExpression
    ): ElementMatchExpression {
        if (condition.children.size != 1) {
            invalidQuery()
        }
        val predicate = lowerInternal(condition.children.single(), target, relativeTime) as? PortableExpression
            ?: invalidQuery()
        return ElementMatchExpression(LogicalField(condition.field), predicate)
    }

    private fun deleted(condition: Condition): QueryExpression =
        when (condition.deletionState()) {
            DeletionState.ACTIVE -> predicate(DELETED_FIELD, PortableOperator.EQ, false)
            DeletionState.DELETED -> predicate(DELETED_FIELD, PortableOperator.EQ, true)
            DeletionState.ALL -> MatchAll
        }

    private fun extractDeletion(condition: Condition, target: QueryTarget): Pair<Condition, DeletionScope> {
        if (target.documentKind == QueryDocumentKind.EVENT_STREAM) {
            return Pair(condition, DeletionScope.DEFAULT)
        }
        if (condition.operator == Operator.DELETED) {
            return Pair(Condition.ALL, condition.deletionState().toScope())
        }
        if (condition.operator != Operator.AND) {
            return Pair(condition, DeletionScope.DEFAULT)
        }
        val deletionChildren = condition.children.filter { it.operator == Operator.DELETED }
        if (deletionChildren.isEmpty()) {
            return Pair(condition, DeletionScope.DEFAULT)
        }
        val scopes = deletionChildren.mapTo(LinkedHashSet()) { it.deletionState().toScope() }
        if (scopes.size != 1) {
            invalidQuery()
        }
        return Pair(
            condition.copy(children = condition.children.filterNot { it.operator == Operator.DELETED }),
            scopes.single()
        )
    }

    private fun DeletionState.toScope(): DeletionScope = when (this) {
        DeletionState.ACTIVE -> DeletionScope.ACTIVE
        DeletionState.DELETED -> DeletionScope.DELETED
        DeletionState.ALL -> DeletionScope.ALL
    }

    private fun predicate(field: String, operator: PortableOperator, vararg values: Any?): PredicateExpression =
        PredicateExpression(LogicalField(field), operator, values.map(QueryValueNormalizer::normalize))

    private fun predicateElements(field: String, operator: PortableOperator, value: Any?): PredicateExpression =
        PredicateExpression(LogicalField(field), operator, QueryValueNormalizer.normalizeElements(value))

    private fun stringPredicate(condition: Condition, operator: PortableOperator): PredicateExpression =
        PredicateExpression(
            LogicalField(condition.field),
            operator,
            listOf(QueryValueNormalizer.normalize(condition.value)),
            when (condition.ignoreCase()) {
                null -> StringComparisonMode.DEFAULT
                false -> StringComparisonMode.CASE_SENSITIVE
                true -> StringComparisonMode.CASE_INSENSITIVE
            }
        )

    private fun recordIdentityField(target: QueryTarget): String =
        when (target.documentKind) {
            QueryDocumentKind.SNAPSHOT -> AGGREGATE_ID_FIELD
            QueryDocumentKind.EVENT_STREAM -> ID_FIELD
        }

    private const val ID_FIELD = "id"
    private const val AGGREGATE_ID_FIELD = "aggregateId"
    private const val TENANT_ID_FIELD = "tenantId"
    private const val OWNER_ID_FIELD = "ownerId"
    private const val SPACE_ID_FIELD = "spaceId"
    private const val DELETED_FIELD = "deleted"
    private val FULL_TEXT_CAPABILITY = QueryCapabilityId("full-text")
}
