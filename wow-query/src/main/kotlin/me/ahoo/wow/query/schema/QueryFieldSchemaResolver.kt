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
    val value: String,
    val physicalPath: String?,
    val compatibility: QueryCompatibilityLevel,
    val fieldSchema: QueryFieldSchema? = null,
    val elementScopeAccepted: Boolean = true,
    val physicalField: QueryField? = null,
)

internal class QueryFieldSchemaResolver(
    private val schema: QueryModelSchema,
) {
    private val knownFields = buildMap {
        schema.fields.forEach { (field, fieldSchema) ->
            put(field.path, field)
            fieldSchema.bindings.values.forEach { binding ->
                putIfAbsent(binding.resolvedField.path, binding.resolvedField)
            }
        }
    }
    private val elementScopePaths = buildSet {
        schema.fields.forEach { (field, fieldSchema) ->
            if (QueryCapability.ELEMENT_SCOPE in fieldSchema.bindings) {
                add(field.path)
            }
        }
    }

    fun resolveQueryField(path: String): QueryField = knownFields[path] ?: QueryField(path)

    fun resolveProjectionPath(path: String): QuerySchemaResolution<String> {
        val logicalField = try {
            QueryField(path)
        } catch (_: IllegalArgumentException) {
            return QuerySchemaResolution(path, QueryCompatibilityLevel.COMPATIBLE)
        }
        val fieldSchema = schema.field(logicalField)
            ?: return QuerySchemaResolution(path, QueryCompatibilityLevel.COMPATIBLE)
        return fieldSchema.projectionField?.let {
            QuerySchemaResolution(it.path, QueryCompatibilityLevel.EXACT)
        } ?: QuerySchemaResolution(path, QueryCompatibilityLevel.INCOMPATIBLE)
    }

    fun resolvePath(
        path: String,
        capability: QueryCapability,
        enforceElementScope: Boolean = true,
    ): QuerySchemaResolution<String> {
        val logicalField = try {
            QueryField(path)
        } catch (_: IllegalArgumentException) {
            return QuerySchemaResolution(path, QueryCompatibilityLevel.COMPATIBLE)
        }
        val resolved = resolve(logicalField, capability, null, null, enforceElementScope)
        return QuerySchemaResolution(resolved.value, resolved.compatibility)
    }

    fun resolve(
        field: QueryField,
        capability: QueryCapability,
        logicalParent: QueryField?,
        physicalParent: String?,
        enforceElementScope: Boolean = true,
        fieldIsAbsolute: Boolean = false,
    ): QueryFieldResolution {
        val logical = if (fieldIsAbsolute) field else field.absoluteTo(logicalParent)
        if (enforceElementScope && !logical.isInElementScope(logicalParent)) {
            return QueryFieldResolution(
                logical,
                field.path,
                null,
                QueryCompatibilityLevel.INCOMPATIBLE,
                elementScopeAccepted = false,
            )
        }
        val declaredFieldSchema = schema.fields[logical]
        val fieldSchema = declaredFieldSchema ?: schema.field(logical)
            ?: return QueryFieldResolution(
                logical,
                field.path,
                null,
                if (
                    logical.path.startsWith("${StateAggregateRecords.TAGS}.") &&
                    schema.fields[QueryField(StateAggregateRecords.TAGS)]?.dynamicChildren == false
                ) {
                    QueryCompatibilityLevel.INCOMPATIBLE
                } else {
                    QueryCompatibilityLevel.COMPATIBLE
                },
            )
        val binding = fieldSchema.binding(capability)
            ?: return QueryFieldResolution(
                logical,
                field.path,
                null,
                if (declaredFieldSchema == null && fieldSchema.dynamicChildren) {
                    QueryCompatibilityLevel.COMPATIBLE
                } else {
                    QueryCompatibilityLevel.INCOMPATIBLE
                },
            )
        val relativePath = binding.resolvedField.path.relativeTo(physicalParent)
            ?: return QueryFieldResolution(logical, field.path, null, QueryCompatibilityLevel.INCOMPATIBLE)
        return QueryFieldResolution(
            logical,
            relativePath,
            binding.resolvedField.path,
            QueryCompatibilityLevel.EXACT,
            fieldSchema = fieldSchema,
            physicalField = binding.physicalField,
        )
    }

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
