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

package me.ahoo.wow.query.schema

import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryCompatibilityLevel
import me.ahoo.wow.serialization.state.StateAggregateRecords

internal data class QueryFieldResolution(
    val logical: QueryField,
    val value: QueryField,
    val compatibility: QueryCompatibilityLevel,
    val fieldSchema: QueryFieldSchema? = null,
    val elementScopeAccepted: Boolean = true,
    val resolvedField: QueryField? = null,
    val physicalField: QueryField? = null,
)

internal class QueryFieldSchemaResolver(
    private val schema: QueryModelSchema,
) {
    private data class ResolvedField(
        val logical: QueryField,
        val fieldSchema: QueryFieldSchema,
        val binding: QueryFieldBinding,
    )

    private val elementScopePaths = schema.elementScopePaths

    fun resolveProjection(field: QueryField): QuerySchemaResolution<QueryField> = QuerySchemaResolution(
        field,
        schema.field(field)?.let {
            if (it.projectionField == null) {
                QueryCompatibilityLevel.INCOMPATIBLE
            } else {
                QueryCompatibilityLevel.EXACT
            }
        } ?: QueryCompatibilityLevel.COMPATIBLE,
    )

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    fun resolve(
        field: QueryField,
        capability: QueryCapability,
        logicalParent: QueryField? = null,
        resolvedParent: QueryField? = null,
        physicalParent: QueryField? = null,
        enforceElementScope: Boolean = true,
    ): QueryFieldResolution {
        val logical = field.absoluteTo(logicalParent)
        val declaredFieldSchema = schema.fields[logical]
        val fieldSchema = declaredFieldSchema ?: schema.field(logical)
        if (fieldSchema != null) {
            if (enforceElementScope && !logical.isInElementScope(logicalParent)) {
                return incompatibleElementScope(logical, field)
            }
            val binding = fieldSchema.binding(capability)
                ?: return QueryFieldResolution(
                    logical,
                    field.relativeTo(logicalParent, resolvedParent),
                    if (declaredFieldSchema == null && fieldSchema.dynamicChildren) {
                        QueryCompatibilityLevel.COMPATIBLE
                    } else {
                        QueryCompatibilityLevel.INCOMPATIBLE
                    },
                )
            return resolveBinding(field, logical, fieldSchema, binding, resolvedParent, physicalParent)
        }

        resolvedField(field.absoluteTo(resolvedParent ?: logicalParent), capability)?.let { resolved ->
            if (enforceElementScope && !resolved.logical.isInElementScope(logicalParent)) {
                return incompatibleElementScope(resolved.logical, field)
            }
            return resolveBinding(
                field,
                resolved.logical,
                resolved.fieldSchema,
                resolved.binding,
                resolvedParent,
                physicalParent,
            )
        }

        if (enforceElementScope && !logical.isInElementScope(logicalParent)) {
            return incompatibleElementScope(logical, field)
        }
        return QueryFieldResolution(
            logical,
            field.relativeTo(logicalParent, resolvedParent),
            if (
                logical.path.startsWith("${StateAggregateRecords.TAGS}.") &&
                schema.fields[QueryField(StateAggregateRecords.TAGS)]?.dynamicChildren == false
            ) {
                QueryCompatibilityLevel.INCOMPATIBLE
            } else {
                QueryCompatibilityLevel.COMPATIBLE
            },
        )
    }

    private fun resolveBinding(
        field: QueryField,
        logical: QueryField,
        fieldSchema: QueryFieldSchema,
        binding: QueryFieldBinding,
        resolvedParent: QueryField?,
        physicalParent: QueryField?,
    ): QueryFieldResolution {
        val resolved = if (resolvedParent == null) {
            binding.resolvedField
        } else {
            binding.resolvedField.relativeTo(resolvedParent)
        }
        val physical = if (physicalParent == null) {
            binding.physicalField
        } else {
            binding.physicalField.relativeTo(physicalParent)
        }
        if (resolved == null || physical == null) {
            return QueryFieldResolution(
                logical,
                field,
                QueryCompatibilityLevel.INCOMPATIBLE,
                fieldSchema = fieldSchema,
                resolvedField = binding.resolvedField,
                physicalField = binding.physicalField,
            )
        }
        val topLevelNoRewrite = resolvedParent == null && physicalParent == null &&
            (schema.rewriteMode == QueryRewriteMode.NONE || fieldSchema.rewriteMode == QueryRewriteMode.NONE)
        val value = if (topLevelNoRewrite || resolved == field) {
            field
        } else {
            resolved
        }
        return QueryFieldResolution(
            logical,
            value,
            QueryCompatibilityLevel.EXACT,
            fieldSchema = fieldSchema,
            resolvedField = binding.resolvedField,
            physicalField = binding.physicalField,
        )
    }

    private fun resolvedField(field: QueryField, capability: QueryCapability): ResolvedField? =
        schema.fields.asSequence()
            .mapNotNull { (logical, fieldSchema) ->
                val binding = fieldSchema.binding(capability) ?: return@mapNotNull null
                when {
                    binding.resolvedField == field -> {
                        binding.resolvedField.path.length to ResolvedField(logical, fieldSchema, binding)
                    }

                    fieldSchema.dynamicChildren -> field.relativeTo(binding.resolvedField)?.let { relative ->
                        val dynamicSchema = fieldSchema.resolveDynamic(
                            source = logical,
                            relative = relative,
                            elementAncestor = logical in schema.elementDescendantDynamicFields,
                        )
                        dynamicSchema.binding(capability)?.let { dynamicBinding ->
                            binding.resolvedField.path.length to ResolvedField(
                                logical.append(relative),
                                dynamicSchema,
                                dynamicBinding,
                            )
                        }
                    }

                    else -> null
                }
            }
            .maxByOrNull { (prefixLength) -> prefixLength }
            ?.second

    private fun incompatibleElementScope(logical: QueryField, value: QueryField): QueryFieldResolution =
        QueryFieldResolution(
            logical,
            value,
            QueryCompatibilityLevel.INCOMPATIBLE,
            elementScopeAccepted = false,
        )

    private fun QueryField.relativeTo(logicalParent: QueryField?, resolvedParent: QueryField?): QueryField =
        logicalParent?.let(::relativeTo) ?: resolvedParent?.let(::relativeTo) ?: this

    private fun QueryField.isInElementScope(parent: QueryField?): Boolean {
        if (elementScopePaths.isEmpty()) return true
        var separator = path.lastIndexOf('.')
        while (separator > 0) {
            val ancestorPath = path.substring(0, separator)
            if (ancestorPath in elementScopePaths) {
                return ancestorPath == parent?.path
            }
            separator = path.lastIndexOf('.', separator - 1)
        }
        return true
    }
}

internal fun Iterable<QueryCompatibilityLevel>.combined(): QueryCompatibilityLevel = when {
    QueryCompatibilityLevel.INCOMPATIBLE in this -> QueryCompatibilityLevel.INCOMPATIBLE
    QueryCompatibilityLevel.COMPATIBLE in this -> QueryCompatibilityLevel.COMPATIBLE
    else -> QueryCompatibilityLevel.EXACT
}
