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

    private val dynamicResolvedFieldIndex = HashMap<Pair<QueryCapability, String>, ResolvedField>()

    private val resolvedFieldIndex = buildMap {
        schema.fields.forEach { (logical, fieldSchema) ->
            fieldSchema.bindings.forEach { (capability, binding) ->
                val resolvedField = ResolvedField(logical, fieldSchema, binding)
                val existing = putIfAbsent(capability to binding.resolvedField, resolvedField)
                if (existing != null && existing.binding.physicalField != binding.physicalField) {
                    throw QuerySchemaConflictException(
                        "Capability [$capability] maps resolved field [${binding.resolvedField}] to conflicting " +
                            "physical fields [${existing.binding.physicalField}, ${binding.physicalField}].",
                    )
                }
                if (fieldSchema.dynamicChildren) {
                    dynamicResolvedFieldIndex.putIfAbsent(capability to binding.resolvedField.path, resolvedField)
                }
            }
        }
    }

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
        fieldSchema?.binding(capability)?.let { binding ->
            if (enforceElementScope && !logical.isInElementScope(logicalParent)) {
                return incompatibleElementScope(logical, field)
            }
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
        val compatibility = when {
            fieldSchema != null -> if (declaredFieldSchema == null && fieldSchema.dynamicChildren) {
                QueryCompatibilityLevel.COMPATIBLE
            } else {
                QueryCompatibilityLevel.INCOMPATIBLE
            }
            logical.path.startsWith("${StateAggregateRecords.TAGS}.") &&
                schema.fields[QueryField(StateAggregateRecords.TAGS)]?.dynamicChildren == false ->
                QueryCompatibilityLevel.INCOMPATIBLE
            else -> QueryCompatibilityLevel.COMPATIBLE
        }
        return QueryFieldResolution(
            logical,
            field.relativeTo(logicalParent, resolvedParent),
            compatibility,
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

    private fun resolvedField(field: QueryField, capability: QueryCapability): ResolvedField? {
        resolvedFieldIndex[capability to field]?.let { return it }
        if (dynamicResolvedFieldIndex.isEmpty()) return null
        var source: ResolvedField? = null
        var relative: QueryField? = null
        var separator = field.path.lastIndexOf('.')
        // Continue after the longest match so shorter ancestors still validate their relative paths.
        while (separator > 0) {
            val ancestorPath = field.path.substring(0, separator)
            dynamicResolvedFieldIndex[capability to ancestorPath]?.let {
                val candidateRelative = checkNotNull(field.relativeTo(it.binding.resolvedField))
                if (source == null) {
                    source = it
                    relative = candidateRelative
                }
            }
            separator = field.path.lastIndexOf('.', separator - 1)
        }
        val finalSource = source ?: return null
        if (capability == QueryCapability.ELEMENT_SCOPE) return null
        val finalRelative = checkNotNull(relative)
        val dynamicSchema = finalSource.fieldSchema.resolveDynamic(
            source = finalSource.logical,
            relative = finalRelative,
            elementAncestor = finalSource.logical in schema.elementDescendantDynamicFields,
        )
        return ResolvedField(
            finalSource.logical.append(finalRelative),
            dynamicSchema,
            checkNotNull(dynamicSchema.binding(capability)),
        )
    }

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
