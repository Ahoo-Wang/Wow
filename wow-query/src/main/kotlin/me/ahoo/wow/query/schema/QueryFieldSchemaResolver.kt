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

import me.ahoo.wow.api.query.LogicalField
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryCompatibilityLevel
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.serialization.state.StateAggregateRecords

internal data class QueryFieldResolution(
    val logical: LogicalField,
    val value: String,
    val physicalPath: String?,
    val compatibility: QueryCompatibilityLevel,
    val fieldSchema: QueryFieldSchema? = null,
    val declaredValueTypes: Set<QueryValueType> = emptySet(),
    val elementScopeAccepted: Boolean = true,
)

internal class QueryFieldSchemaResolver(
    private val schema: QueryModelSchema,
) {
    private val elementScopePaths = buildSet {
        schema.fields.forEach { (field, fieldSchema) ->
            if (QueryCapability.ELEMENT_SCOPE in fieldSchema.bindings) {
                add(field.value)
            }
        }
    }

    fun resolveProjectionPath(path: String): QuerySchemaResolution<String> {
        val logicalField = try {
            LogicalField(path)
        } catch (_: IllegalArgumentException) {
            return QuerySchemaResolution(path, QueryCompatibilityLevel.COMPATIBLE)
        }
        val fieldSchema = schema.fields[logicalField] ?: schema.resolve(logicalField)
            ?: return QuerySchemaResolution(path, QueryCompatibilityLevel.COMPATIBLE)
        return fieldSchema.projectionPath?.let {
            QuerySchemaResolution(it, QueryCompatibilityLevel.EXACT)
        } ?: QuerySchemaResolution(path, QueryCompatibilityLevel.INCOMPATIBLE)
    }

    fun resolvePath(
        path: String,
        capability: QueryCapability,
        enforceElementScope: Boolean = true,
    ): QuerySchemaResolution<String> {
        val logicalField = try {
            LogicalField(path)
        } catch (_: IllegalArgumentException) {
            return QuerySchemaResolution(path, QueryCompatibilityLevel.COMPATIBLE)
        }
        val resolved = resolve(logicalField, capability, null, null, enforceElementScope)
        return QuerySchemaResolution(resolved.value, resolved.compatibility)
    }

    fun resolve(
        field: LogicalField,
        capability: QueryCapability,
        logicalParent: LogicalField?,
        physicalParent: String?,
        enforceElementScope: Boolean = true,
        fieldIsAbsolute: Boolean = false,
    ): QueryFieldResolution {
        val logical = if (fieldIsAbsolute) field else field.absoluteTo(logicalParent)
        if (enforceElementScope && !logical.isInElementScope(logicalParent)) {
            return QueryFieldResolution(
                logical,
                field.value,
                null,
                QueryCompatibilityLevel.INCOMPATIBLE,
                elementScopeAccepted = false,
            )
        }
        val declaredFieldSchema = schema.fields[logical]
        val fieldSchema = declaredFieldSchema ?: schema.resolve(logical)
            ?: return QueryFieldResolution(
                logical,
                field.value,
                null,
                if (
                    logical.value.startsWith("${StateAggregateRecords.TAGS}.") &&
                    schema.fields[LogicalField(StateAggregateRecords.TAGS)]?.dynamicChildren == false
                ) {
                    QueryCompatibilityLevel.INCOMPATIBLE
                } else {
                    QueryCompatibilityLevel.COMPATIBLE
                },
            )
        val binding = fieldSchema.bindings[capability]
            ?: return QueryFieldResolution(
                logical,
                field.value,
                null,
                if (declaredFieldSchema == null && fieldSchema.dynamicChildren) {
                    QueryCompatibilityLevel.COMPATIBLE
                } else {
                    QueryCompatibilityLevel.INCOMPATIBLE
                },
            )
        val relativePath = binding.physicalPath.relativeTo(physicalParent)
            ?: return QueryFieldResolution(logical, field.value, null, QueryCompatibilityLevel.INCOMPATIBLE)
        return QueryFieldResolution(
            logical,
            relativePath,
            binding.physicalPath,
            QueryCompatibilityLevel.EXACT,
            fieldSchema = fieldSchema,
            declaredValueTypes = declaredFieldSchema?.valueTypes.orEmpty(),
        )
    }

    private fun LogicalField.isInElementScope(parent: LogicalField?): Boolean {
        if (elementScopePaths.isEmpty()) return true
        var separator = value.lastIndexOf('.')
        while (separator > 0) {
            val ancestorPath = value.substring(0, separator)
            if (ancestorPath in elementScopePaths) {
                return ancestorPath == parent?.value
            }
            separator = value.lastIndexOf('.', separator - 1)
        }
        return true
    }

    private fun String.relativeTo(parent: String?): String? = when {
        parent == null -> this
        startsWith("$parent.") -> substring(parent.length + 1)
        else -> null
    }
}

internal fun Iterable<QueryCompatibilityLevel>.combined(): QueryCompatibilityLevel = when {
    QueryCompatibilityLevel.INCOMPATIBLE in this -> QueryCompatibilityLevel.INCOMPATIBLE
    QueryCompatibilityLevel.COMPATIBLE in this -> QueryCompatibilityLevel.COMPATIBLE
    else -> QueryCompatibilityLevel.EXACT
}
