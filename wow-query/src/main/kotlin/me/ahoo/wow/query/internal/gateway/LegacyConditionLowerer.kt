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

@file:OptIn(me.ahoo.wow.query.gateway.ExperimentalQueryGatewayApi::class)

package me.ahoo.wow.query.internal.gateway

import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.Operator
import me.ahoo.wow.query.backend.NormalizedValue
import me.ahoo.wow.query.backend.QueryFieldId
import me.ahoo.wow.query.gateway.QueryElementPathMode
import me.ahoo.wow.query.gateway.QueryLegacyDialect
import me.ahoo.wow.query.gateway.QueryMatchScopeMode
import me.ahoo.wow.query.internal.execution.rejectLegacyMandatory
import me.ahoo.wow.query.internal.normalization.CaseSensitivity
import me.ahoo.wow.query.internal.normalization.JunctionOperator
import me.ahoo.wow.query.internal.normalization.LogicalField
import me.ahoo.wow.query.internal.normalization.NormalizedCondition
import me.ahoo.wow.query.internal.normalization.NormalizedDeletionScope
import me.ahoo.wow.query.internal.normalization.NormalizedPredicateOptions
import me.ahoo.wow.query.internal.normalization.PathBasis
import me.ahoo.wow.query.internal.normalization.PredicateOperator
import me.ahoo.wow.query.internal.normalization.SearchScope
import me.ahoo.wow.query.internal.normalization.SystemFieldKind
import me.ahoo.wow.query.internal.plan.PlannedCondition
import me.ahoo.wow.query.internal.rejection.QueryRejectedException
import me.ahoo.wow.serialization.MessageRecords
import java.util.LinkedHashMap

internal data class LoweredLegacyCondition(
    val condition: Condition,
    val matchNone: Boolean,
)

internal class LegacyConditionLowerer(
    private val dialect: QueryLegacyDialect,
    private val deletionMode: LegacyDeletionMode = LegacyDeletionMode.SNAPSHOT,
) {
    fun lower(
        user: NormalizedCondition,
        deletionScope: NormalizedDeletionScope,
        mandatory: PlannedCondition,
    ): LoweredLegacyCondition {
        val loweredUser = lowerNormalized(user, emptyList())
        val loweredMandatory = lowerMandatory(mandatory)
        val matchNone = loweredUser == Lowered.None || loweredMandatory == Lowered.None
        val condition = when (deletionMode) {
            LegacyDeletionMode.SNAPSHOT -> {
                val deletion = when (deletionScope) {
                    NormalizedDeletionScope.DEFAULT_ACTIVE -> Condition.deleted(DeletionState.ACTIVE)
                    NormalizedDeletionScope.EXPLICIT -> Condition.deleted(DeletionState.ALL)
                }
                val rootChildren = buildList {
                    add(deletion)
                    loweredUser.conditionOrNull()?.let(::add)
                    loweredMandatory.conditionOrNull()?.let(::add)
                }
                Condition.and(rootChildren)
            }

            LegacyDeletionMode.NONE -> lowerAnd(listOf(loweredUser, loweredMandatory)).conditionOrNull() ?: Condition.ALL
        }
        return LoweredLegacyCondition(condition, matchNone)
    }

    private fun lowerMandatory(condition: PlannedCondition): Lowered =
        try {
            lowerPlanned(condition, emptyList())
        } catch (error: QueryRejectedException) {
            rejectLegacyMandatory(error)
        }

    private fun lowerNormalized(condition: NormalizedCondition, ancestors: List<String>): Lowered =
        when (condition) {
            NormalizedCondition.All -> Lowered.All
            NormalizedCondition.None -> Lowered.None
            is NormalizedCondition.Junction -> lowerJunction(
                condition.operator,
                condition.children.map { child -> lowerNormalized(child, ancestors) },
            )

            is NormalizedCondition.Predicate -> lowerPredicate(
                condition.field,
                condition.operator,
                condition.value,
                condition.options,
                ancestors,
            )

            is NormalizedCondition.ElementMatch -> lowerElementMatch(
                condition.field.absoluteSegments(ancestors),
                ancestors,
            ) { childAncestors -> lowerNormalized(condition.condition, childAncestors) }

            is NormalizedCondition.Search -> lowerSearch(condition, ancestors)
            is NormalizedCondition.Native -> rejectLegacyLowering()
        }

    private fun lowerPlanned(condition: PlannedCondition, ancestors: List<String>): Lowered =
        when (condition) {
            PlannedCondition.All -> Lowered.All
            PlannedCondition.None -> Lowered.None
            is PlannedCondition.Junction -> lowerJunction(
                condition.operator,
                condition.children.values.map { child -> lowerPlanned(child, ancestors) },
            )

            is PlannedCondition.Predicate -> lowerPredicate(
                condition.field,
                condition.operator,
                condition.value,
                condition.options,
                ancestors,
            )

            is PlannedCondition.ElementMatch -> lowerElementMatch(
                condition.field.segments,
                ancestors,
            ) { childAncestors -> lowerPlanned(condition.condition, childAncestors) }

            is PlannedCondition.Search,
            is PlannedCondition.Native,
            -> rejectLegacyLowering()
        }

    private fun lowerElementMatch(
        absoluteField: List<String>,
        ancestors: List<String>,
        lowerChild: (List<String>) -> Lowered,
    ): Lowered {
        val child = lowerChild(absoluteField)
        if (child == Lowered.None) {
            return Lowered.None
        }
        val childCondition = child.conditionOrNull() ?: Condition.ALL
        val field = renderField(absoluteField, ancestors)
        return Lowered.Wire(
            Condition(
                field = field,
                operator = Operator.ELEM_MATCH,
                children = listOf(childCondition),
            ),
        )
    }

    private fun lowerSearch(condition: NormalizedCondition.Search, ancestors: List<String>): Lowered {
        val scope = condition.scope as? SearchScope.LegacyField ?: rejectLegacyLowering()
        if (dialect.matchScopeMode == QueryMatchScopeMode.DOCUMENT && ancestors.isNotEmpty()) {
            rejectLegacyLowering()
        }
        val field = renderField(scope.field.absoluteSegments(ancestors), ancestors)
        return Lowered.Wire(Condition(field, Operator.MATCH, condition.text))
    }

    private fun lowerPredicate(
        field: LogicalField,
        operator: PredicateOperator,
        value: NormalizedValue?,
        options: NormalizedPredicateOptions,
        ancestors: List<String>,
    ): Lowered =
        when (field) {
            is LogicalField.System -> lowerSystemPredicate(field.kind, operator, value)
            is LogicalField.Path -> wirePredicate(
                renderField(field.absoluteSegments(ancestors), ancestors),
                operator,
                value,
                options,
            )
        }

    private fun lowerPredicate(
        field: QueryFieldId,
        operator: PredicateOperator,
        value: NormalizedValue?,
        options: NormalizedPredicateOptions,
        ancestors: List<String>,
    ): Lowered =
        when (field) {
            is QueryFieldId.System -> lowerSystemPredicate(field.kind, operator, value)
            is QueryFieldId.Path -> wirePredicate(
                renderField(field.segments, ancestors),
                operator,
                value,
                options,
            )
        }

    private fun lowerSystemPredicate(
        kind: SystemFieldKind,
        operator: PredicateOperator,
        value: NormalizedValue?,
    ): Lowered {
        val wire = when (kind) {
            SystemFieldKind.IDENTITY -> systemIdentity(operator, value, Operator.ID, Operator.IDS)
            SystemFieldKind.AGGREGATE_ID -> systemIdentity(
                operator,
                value,
                Operator.AGGREGATE_ID,
                Operator.AGGREGATE_IDS,
            )

            SystemFieldKind.TENANT_ID -> systemText(
                operator,
                value,
                Operator.TENANT_ID,
                MessageRecords.TENANT_ID,
            )

            SystemFieldKind.OWNER_ID -> systemText(operator, value, Operator.OWNER_ID, MessageRecords.OWNER_ID)
            SystemFieldKind.SPACE_ID -> systemText(operator, value, Operator.SPACE_ID, MessageRecords.SPACE_ID)
            SystemFieldKind.DELETED -> when (operator) {
                PredicateOperator.IS_FALSE -> Condition.deleted(DeletionState.ACTIVE)
                PredicateOperator.IS_TRUE -> Condition.deleted(DeletionState.DELETED)
                PredicateOperator.EQ -> Condition.deleted(value.requireBoolean())
                else -> rejectLegacyLowering()
            }
        }
        return Lowered.Wire(wire)
    }

    private fun systemIdentity(
        operator: PredicateOperator,
        value: NormalizedValue?,
        single: Operator,
        multiple: Operator,
    ): Condition =
        when (operator) {
            PredicateOperator.EQ -> Condition(operator = single, value = value.requireText())
            PredicateOperator.IN -> Condition(operator = multiple, value = value.requireTextList())
            else -> rejectLegacyLowering()
        }

    private fun systemText(
        operator: PredicateOperator,
        value: NormalizedValue?,
        wireOperator: Operator,
        field: String,
    ): Condition =
        when (operator) {
            PredicateOperator.EQ -> Condition(operator = wireOperator, value = value.requireText())
            PredicateOperator.IN -> Condition(field, Operator.IN, value.requireTextList())
            else -> rejectLegacyLowering()
        }

    private fun wirePredicate(
        field: String,
        operator: PredicateOperator,
        value: NormalizedValue?,
        options: NormalizedPredicateOptions,
    ): Lowered.Wire {
        val wireOperator = operator.toWireOperator()
        val wireValue = if (operator.requiresValue) value?.toWireValue() ?: rejectLegacyLowering() else Condition.EMPTY_VALUE
        val wireOptions = if (options.caseSensitivity == CaseSensitivity.INSENSITIVE) {
            Condition.ignoreCaseOptions(true)
        } else {
            emptyMap()
        }
        return Lowered.Wire(Condition(field, wireOperator, wireValue, options = wireOptions))
    }

    private fun lowerJunction(operator: JunctionOperator, children: List<Lowered>): Lowered =
        when (operator) {
            JunctionOperator.AND -> lowerAnd(children)
            JunctionOperator.OR -> lowerOr(children)
            JunctionOperator.NOR -> lowerNor(children)
        }

    private fun lowerAnd(children: List<Lowered>): Lowered {
        if (Lowered.None in children) {
            return Lowered.None
        }
        val wire = children.mapNotNull(Lowered::conditionOrNull)
        return when (wire.size) {
            0 -> Lowered.All
            1 -> Lowered.Wire(wire.single())
            else -> Lowered.Wire(Condition.and(wire))
        }
    }

    private fun lowerOr(children: List<Lowered>): Lowered {
        if (Lowered.All in children) {
            return Lowered.All
        }
        val wire = children.mapNotNull(Lowered::conditionOrNull)
        return when (wire.size) {
            0 -> Lowered.None
            1 -> Lowered.Wire(wire.single())
            else -> Lowered.Wire(Condition.or(wire))
        }
    }

    private fun lowerNor(children: List<Lowered>): Lowered {
        if (Lowered.All in children) {
            return Lowered.None
        }
        val wire = children.mapNotNull(Lowered::conditionOrNull)
        return if (wire.isEmpty()) Lowered.All else Lowered.Wire(Condition.nor(wire))
    }

    private fun renderField(absolute: List<String>, ancestors: List<String>): String {
        val rendered = when (dialect.elementPathMode) {
            QueryElementPathMode.ROOT_QUALIFIED -> absolute
            QueryElementPathMode.CURRENT_ELEMENT_RELATIVE ->
                if (ancestors.isNotEmpty() && absolute.startsWith(ancestors)) {
                    absolute.drop(ancestors.size)
                } else {
                    absolute
                }
        }
        if (rendered.isEmpty()) {
            rejectLegacyLowering()
        }
        return rendered.joinToString(".")
    }

    private sealed interface Lowered {
        data object All : Lowered

        data object None : Lowered

        data class Wire(val condition: Condition) : Lowered

        fun conditionOrNull(): Condition? = (this as? Wire)?.condition
    }
}

internal enum class LegacyDeletionMode {
    SNAPSHOT,
    NONE,
}

private fun LogicalField.Path.absoluteSegments(ancestors: List<String>): List<String> =
    when (basis) {
        PathBasis.ROOT -> segments
        PathBasis.CURRENT_ELEMENT -> ancestors + segments
    }

private val WIRE_PREDICATE_OPERATORS = mapOf(
    PredicateOperator.EQ to Operator.EQ,
    PredicateOperator.NE to Operator.NE,
    PredicateOperator.GT to Operator.GT,
    PredicateOperator.LT to Operator.LT,
    PredicateOperator.GTE to Operator.GTE,
    PredicateOperator.LTE to Operator.LTE,
    PredicateOperator.CONTAINS to Operator.CONTAINS,
    PredicateOperator.IN to Operator.IN,
    PredicateOperator.NOT_IN to Operator.NOT_IN,
    PredicateOperator.BETWEEN to Operator.BETWEEN,
    PredicateOperator.ALL_IN to Operator.ALL_IN,
    PredicateOperator.STARTS_WITH to Operator.STARTS_WITH,
    PredicateOperator.ENDS_WITH to Operator.ENDS_WITH,
    PredicateOperator.IS_NULL to Operator.NULL,
    PredicateOperator.NOT_NULL to Operator.NOT_NULL,
    PredicateOperator.IS_TRUE to Operator.TRUE,
    PredicateOperator.IS_FALSE to Operator.FALSE,
    PredicateOperator.EXISTS to Operator.EXISTS,
)

private fun PredicateOperator.toWireOperator(): Operator = WIRE_PREDICATE_OPERATORS.getValue(this)

private fun NormalizedValue?.requireBoolean(): Boolean =
    (this as? NormalizedValue.BooleanValue)?.value ?: rejectLegacyLowering()

private fun NormalizedValue?.requireText(): String = (this as? NormalizedValue.Text)?.value ?: rejectLegacyLowering()

private fun NormalizedValue?.requireTextList(): List<String> =
    (this as? NormalizedValue.ListValue)?.values?.map { it.requireText() } ?: rejectLegacyLowering()

private fun NormalizedValue.toWireValue(): Any? =
    when (this) {
        NormalizedValue.Null -> null
        is NormalizedValue.BooleanValue -> value
        is NormalizedValue.Text -> value
        is NormalizedValue.Int64 -> value
        is NormalizedValue.Decimal -> value
        is NormalizedValue.InstantValue -> value.toEpochMilli()
        is NormalizedValue.Bytes -> toByteArray()
        is NormalizedValue.ListValue -> values.map(NormalizedValue::toWireValue)
        is NormalizedValue.ObjectValue -> {
            val copy = LinkedHashMap<String, Any?>(values.size)
            values.forEach { (key, value) -> copy[key] = value.toWireValue() }
            copy
        }
    }

private fun <T> List<T>.startsWith(prefix: List<T>): Boolean =
    size >= prefix.size && prefix.indices.all { index -> this[index] == prefix[index] }
