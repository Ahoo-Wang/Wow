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

package me.ahoo.wow.query.internal.planning

import me.ahoo.wow.query.internal.normalization.BackendId
import me.ahoo.wow.query.internal.normalization.CaseSensitivity
import me.ahoo.wow.query.internal.normalization.LogicalField
import me.ahoo.wow.query.internal.normalization.NormalizedCondition
import me.ahoo.wow.query.internal.normalization.NormalizedValue
import me.ahoo.wow.query.internal.normalization.PathBasis
import me.ahoo.wow.query.internal.normalization.PredicateOperator
import me.ahoo.wow.query.internal.normalization.SearchScope
import me.ahoo.wow.query.internal.plan.PlannedCondition
import me.ahoo.wow.query.internal.plan.RequiredCapabilities
import me.ahoo.wow.query.internal.plan.SemanticTier
import me.ahoo.wow.query.internal.rejection.QueryRejectedException
import me.ahoo.wow.query.internal.rejection.QueryRejectionCategory
import me.ahoo.wow.query.internal.rejection.QueryRejectionCode
import me.ahoo.wow.query.internal.rejection.QueryRejectionPath
import me.ahoo.wow.query.internal.rejection.rejectQuery
import me.ahoo.wow.query.internal.schema.FieldCapability
import me.ahoo.wow.query.internal.schema.LogicalFieldType
import me.ahoo.wow.query.internal.schema.QueryDocumentSchema
import me.ahoo.wow.query.internal.schema.QueryFieldId
import me.ahoo.wow.query.internal.schema.acceptsOperand
import me.ahoo.wow.query.internal.schema.hasOperandType
import me.ahoo.wow.query.internal.value.NonEmptyList

internal class QueryConditionPlanner(
    internal val schema: QueryDocumentSchema,
) {
    fun plan(
        condition: NormalizedCondition,
        path: QueryRejectionPath,
        mandatory: Boolean,
        fieldConstraint: QueryFieldConstraint = QueryFieldConstraint(),
    ): PlannedConditionResult {
        val state = ConditionPlanningState()
        val planned = planCondition(condition, path, elementScope = null, mandatory, fieldConstraint, state)
        return PlannedConditionResult(planned, state.capabilities(), state.semanticTier)
    }

    fun resolveField(
        field: LogicalField,
        path: QueryRejectionPath,
        elementScope: QueryFieldId.Path? = null,
    ): QueryFieldId =
        when (field) {
            is LogicalField.System -> {
                if (elementScope != null) {
                    rejectQuery(
                        QueryRejectionCategory.INVALID_QUERY,
                        path,
                        QueryRejectionCode.SYSTEM_FIELD_IN_ELEMENT_SCOPE,
                    )
                }
                QueryFieldId.System(field.kind)
            }

            is LogicalField.Path -> resolvePath(field, path, elementScope)
        }.let { unresolved ->
            schema.resolveField(unresolved) ?: rejectQuery(
                QueryRejectionCategory.UNSUPPORTED_FEATURE,
                path,
                QueryRejectionCode.FIELD_NOT_FOUND,
            )
        }.also { resolved ->
            if (elementScope != null && resolved is QueryFieldId.System) {
                rejectQuery(
                    QueryRejectionCategory.INVALID_QUERY,
                    path,
                    QueryRejectionCode.SYSTEM_FIELD_IN_ELEMENT_SCOPE,
                )
            }
        }

    fun resolveAccessibleField(
        field: LogicalField,
        path: QueryRejectionPath,
        access: FieldAccess,
        deniedCode: QueryRejectionCode,
        elementScope: QueryFieldId.Path? = null,
    ): QueryFieldId {
        if (access == FieldAccess.DenyAll) {
            rejectAccess(path, deniedCode)
        }
        val resolved =
            try {
                resolveField(field, path, elementScope)
            } catch (error: QueryRejectedException) {
                if (access is FieldAccess.AllowList && error.rejection.code == QueryRejectionCode.FIELD_NOT_FOUND) {
                    rejectQuery(QueryRejectionCategory.ACCESS_DENIED, path, deniedCode, error)
                }
                throw error
            }
        if (!access.permits(resolved)) {
            rejectAccess(path, deniedCode)
        }
        return resolved
    }

    private fun planCondition(
        condition: NormalizedCondition,
        path: QueryRejectionPath,
        elementScope: QueryFieldId.Path?,
        mandatory: Boolean,
        fieldConstraint: QueryFieldConstraint,
        state: ConditionPlanningState,
    ): PlannedCondition =
        when (condition) {
            NormalizedCondition.All -> PlannedCondition.All
            NormalizedCondition.None -> PlannedCondition.None
            is NormalizedCondition.Junction -> PlannedCondition.Junction(
                condition.operator,
                checkNotNull(
                    NonEmptyList.from(
                        condition.children.mapIndexed { index, child ->
                            planCondition(
                                child,
                                path.property("children").index(index),
                                elementScope,
                                mandatory,
                                fieldConstraint,
                                state,
                            )
                        },
                    ),
                ),
            )

            is NormalizedCondition.Predicate -> planPredicate(condition, path, elementScope, fieldConstraint, state)
            is NormalizedCondition.ElementMatch ->
                planElementMatch(condition, path, elementScope, mandatory, fieldConstraint, state)
            is NormalizedCondition.Search -> planSearch(condition, path, elementScope, fieldConstraint, state)
            is NormalizedCondition.Native -> planNative(condition, path, mandatory, fieldConstraint, state)
        }

    private fun planPredicate(
        condition: NormalizedCondition.Predicate,
        path: QueryRejectionPath,
        elementScope: QueryFieldId.Path?,
        fieldConstraint: QueryFieldConstraint,
        state: ConditionPlanningState,
    ): PlannedCondition.Predicate {
        val field = resolveAccessibleField(
            condition.field,
            path.property("field"),
            fieldConstraint.filterFields,
            QueryRejectionCode.FILTER_FIELD_NOT_ALLOWED,
            elementScope,
        )
        val fieldSchema = schema.fields.getValue(field)
        if (condition.operator !in fieldSchema.allowedOperators) {
            rejectQuery(
                QueryRejectionCategory.UNSUPPORTED_FEATURE,
                path.property("operator"),
                QueryRejectionCode.OPERATOR_NOT_ALLOWED,
            )
        }
        if (condition.options.caseSensitivity == CaseSensitivity.INSENSITIVE) {
            rejectQuery(
                QueryRejectionCategory.UNSUPPORTED_FEATURE,
                path.property("options").property("caseSensitivity"),
                QueryRejectionCode.CASE_INSENSITIVE_UNSUPPORTED,
            )
        }
        val capability = condition.operator.requiredCapability()
        requireCapability(field, capability, path.property("operator"), state)
        validatePredicateValue(condition, fieldSchema, path)
        return PlannedCondition.Predicate(field, condition.operator, condition.value, condition.options)
    }

    private fun validatePredicateValue(
        condition: NormalizedCondition.Predicate,
        fieldSchema: me.ahoo.wow.query.internal.schema.QueryFieldSchema,
        path: QueryRejectionPath,
    ) {
        if (!acceptsPredicateValue(condition, fieldSchema)) {
            rejectQuery(
                QueryRejectionCategory.UNSUPPORTED_FEATURE,
                path.property("value"),
                QueryRejectionCode.VALUE_TYPE_MISMATCH,
            )
        }
    }

    private fun acceptsPredicateValue(
        condition: NormalizedCondition.Predicate,
        fieldSchema: me.ahoo.wow.query.internal.schema.QueryFieldSchema,
    ): Boolean {
        if (condition.operator == PredicateOperator.IS_NULL || condition.operator == PredicateOperator.NOT_NULL) {
            return true
        }
        if (condition.operator.isCollectionOperator()) {
            return acceptsCollectionValue(condition, fieldSchema)
        }
        return when (condition.operator) {
            PredicateOperator.IS_TRUE,
            PredicateOperator.IS_FALSE,
            -> fieldSchema.hasOperandType(LogicalFieldType.Boolean)

            PredicateOperator.EXISTS -> condition.value is NormalizedValue.BooleanValue
            PredicateOperator.CONTAINS,
            PredicateOperator.STARTS_WITH,
            PredicateOperator.ENDS_WITH,
            -> fieldSchema.hasOperandType(LogicalFieldType.Text) && condition.value is NormalizedValue.Text

            else -> condition.value?.let { value -> fieldSchema.acceptsOperand(condition.operator, value) } == true
        }
    }

    private fun acceptsCollectionValue(
        condition: NormalizedCondition.Predicate,
        fieldSchema: me.ahoo.wow.query.internal.schema.QueryFieldSchema,
    ): Boolean {
        val values = condition.value as? NormalizedValue.ListValue ?: return false
        val validSize =
            if (condition.operator == PredicateOperator.BETWEEN) {
                values.values.size == 2
            } else {
                values.values.isNotEmpty()
            }
        return validSize && values.values.all { value -> fieldSchema.acceptsOperand(condition.operator, value) }
    }

    private fun PredicateOperator.isCollectionOperator(): Boolean =
        this == PredicateOperator.IN ||
            this == PredicateOperator.NOT_IN ||
            this == PredicateOperator.ALL_IN ||
            this == PredicateOperator.BETWEEN

    private fun planElementMatch(
        condition: NormalizedCondition.ElementMatch,
        path: QueryRejectionPath,
        elementScope: QueryFieldId.Path?,
        mandatory: Boolean,
        fieldConstraint: QueryFieldConstraint,
        state: ConditionPlanningState,
    ): PlannedCondition.ElementMatch {
        val field = resolveAccessibleField(
            condition.field,
            path.property("field"),
            fieldConstraint.filterFields,
            QueryRejectionCode.FILTER_FIELD_NOT_ALLOWED,
            elementScope,
        )
        if (field !is QueryFieldId.Path) {
            rejectQuery(QueryRejectionCategory.INVALID_QUERY, path.property("field"), QueryRejectionCode.INVALID_FIELD)
        }
        requireCapability(field, FieldCapability.ELEMENT_MATCH, path.property("field"), state)
        return PlannedCondition.ElementMatch(
            field,
            planCondition(
                condition.condition,
                path.property("condition"),
                field,
                mandatory,
                fieldConstraint,
                state,
            ),
        )
    }

    private fun planSearch(
        condition: NormalizedCondition.Search,
        path: QueryRejectionPath,
        elementScope: QueryFieldId.Path?,
        fieldConstraint: QueryFieldConstraint,
        state: ConditionPlanningState,
    ): PlannedCondition.Search {
        if (fieldConstraint.searchScopes == SearchScopeAccess.DenyAll) {
            rejectAccess(path.property("scope"), QueryRejectionCode.SEARCH_SCOPE_NOT_ALLOWED)
        }
        val definition =
            when (val scope = condition.scope) {
                is SearchScope.Named -> schema.searchScopes[scope.id]?.takeIf { it.owner == elementScope }
                is SearchScope.LegacyField -> {
                    val alias = resolveSearchAlias(scope.field, path.property("scope"), elementScope, fieldConstraint)
                    schema.resolveLegacySearchScope(elementScope, alias)
                }
            } ?: rejectQuery(
                if (fieldConstraint.searchScopes is SearchScopeAccess.AllowList) {
                    QueryRejectionCategory.ACCESS_DENIED
                } else {
                    QueryRejectionCategory.UNSUPPORTED_FEATURE
                },
                path.property("scope"),
                if (fieldConstraint.searchScopes is SearchScopeAccess.AllowList) {
                    QueryRejectionCode.SEARCH_SCOPE_NOT_ALLOWED
                } else {
                    QueryRejectionCode.SEARCH_SCOPE_NOT_FOUND
                },
            )
        if (!fieldConstraint.searchScopes.permits(definition.id)) {
            rejectQuery(
                QueryRejectionCategory.ACCESS_DENIED,
                path.property("scope"),
                QueryRejectionCode.SEARCH_SCOPE_NOT_ALLOWED,
            )
        }
        state.searchRequirements += definition.id
        state.semanticTier = state.semanticTier.max(SemanticTier.SEARCH)
        return PlannedCondition.Search(definition.id, condition.text)
    }

    private fun planNative(
        condition: NormalizedCondition.Native,
        path: QueryRejectionPath,
        mandatory: Boolean,
        fieldConstraint: QueryFieldConstraint,
        state: ConditionPlanningState,
    ): PlannedCondition.Native {
        if (mandatory) {
            rejectQuery(
                QueryRejectionCategory.UNSUPPORTED_FEATURE,
                path,
                QueryRejectionCode.MANDATORY_NATIVE_NOT_ALLOWED,
            )
        }
        if (!fieldConstraint.nativeBackends.permits(condition.backendId)) {
            rejectQuery(
                QueryRejectionCategory.ACCESS_DENIED,
                path.property("backendId"),
                QueryRejectionCode.NATIVE_BACKEND_NOT_ALLOWED,
            )
        }
        state.requireNativeBackend(condition.backendId, path)
        state.semanticTier = SemanticTier.NATIVE
        return PlannedCondition.Native(condition.backendId, condition.payload)
    }

    private fun resolvePath(
        field: LogicalField.Path,
        path: QueryRejectionPath,
        elementScope: QueryFieldId.Path?,
    ): QueryFieldId.Path =
        when (field.basis) {
            PathBasis.ROOT -> {
                if (elementScope != null) {
                    rejectQuery(QueryRejectionCategory.INVALID_QUERY, path, QueryRejectionCode.INVALID_FIELD)
                }
                QueryFieldId.Path(field.segments)
            }
            PathBasis.CURRENT_ELEMENT -> {
                val scope = elementScope ?: rejectQuery(
                    QueryRejectionCategory.INVALID_QUERY,
                    path,
                    QueryRejectionCode.INVALID_FIELD,
                )
                QueryFieldId.Path(scope.segments + field.segments)
            }
        }

    private fun requireCapability(
        field: QueryFieldId,
        capability: FieldCapability,
        path: QueryRejectionPath,
        state: ConditionPlanningState,
    ) {
        if (capability !in schema.fields.getValue(field).capabilities) {
            rejectQuery(QueryRejectionCategory.UNSUPPORTED_FEATURE, path, QueryRejectionCode.CAPABILITY_UNAVAILABLE)
        }
        state.fieldRequirements.getOrPut(field, ::linkedSetOf) += capability
    }

    private fun resolveSearchAlias(
        field: LogicalField.Path,
        path: QueryRejectionPath,
        elementScope: QueryFieldId.Path?,
        fieldConstraint: QueryFieldConstraint,
    ): QueryFieldId.Path =
        try {
            resolveField(field, path, elementScope) as? QueryFieldId.Path
                ?: rejectQuery(
                    QueryRejectionCategory.UNSUPPORTED_FEATURE,
                    path,
                    QueryRejectionCode.SEARCH_SCOPE_NOT_FOUND,
                )
        } catch (error: QueryRejectedException) {
            if (fieldConstraint.searchScopes is SearchScopeAccess.AllowList &&
                error.rejection.category == QueryRejectionCategory.UNSUPPORTED_FEATURE
            ) {
                rejectQuery(
                    QueryRejectionCategory.ACCESS_DENIED,
                    path,
                    QueryRejectionCode.SEARCH_SCOPE_NOT_ALLOWED,
                    error,
                )
            }
            throw error
        }

    private fun rejectAccess(path: QueryRejectionPath, code: QueryRejectionCode): Nothing =
        rejectQuery(
            QueryRejectionCategory.ACCESS_DENIED,
            path,
            code,
        )

    private fun PredicateOperator.requiredCapability(): FieldCapability =
        when (this) {
            PredicateOperator.IS_NULL,
            PredicateOperator.NOT_NULL,
            PredicateOperator.EXISTS,
            -> FieldCapability.PRESENCE

            PredicateOperator.GT,
            PredicateOperator.LT,
            PredicateOperator.GTE,
            PredicateOperator.LTE,
            PredicateOperator.BETWEEN,
            -> FieldCapability.RANGE

            PredicateOperator.CONTAINS,
            PredicateOperator.STARTS_WITH,
            PredicateOperator.ENDS_WITH,
            -> FieldCapability.LITERAL_PATTERN

            else -> FieldCapability.EXACT
        }

    private class ConditionPlanningState {
        val fieldRequirements: MutableMap<QueryFieldId, MutableSet<FieldCapability>> = linkedMapOf()
        val searchRequirements: MutableSet<me.ahoo.wow.query.internal.normalization.SearchScopeId> = linkedSetOf()
        var nativeBackend: BackendId? = null
        var semanticTier: SemanticTier = SemanticTier.PORTABLE

        fun requireNativeBackend(backendId: BackendId, path: QueryRejectionPath) {
            val current = nativeBackend
            if (current != null && current != backendId) {
                rejectQuery(
                    QueryRejectionCategory.INVALID_QUERY,
                    path.property("backendId"),
                    QueryRejectionCode.NATIVE_BACKEND_CONFLICT,
                )
            }
            nativeBackend = backendId
        }

        fun capabilities(): RequiredCapabilities = RequiredCapabilities(
            fieldRequirements.mapValues { it.value.toSet() },
            searchRequirements,
            nativeBackend,
        )
    }
}
