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
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.serialization.state.StateAggregateRecords

internal class QuerySchemaMerger {
    fun merge(
        system: QuerySchemaDeclaration,
        extensions: List<PrioritizedQuerySchemaDeclaration>,
    ): LogicalQuerySchema {
        val extensionRoot = if (QueryField(StateAggregateRecords.STATE) in system.fields) {
            StateAggregateRecords.STATE
        } else {
            EVENT_PAYLOAD_ROOT
        }
        extensions.forEach { extension ->
            extension.declaration.fields.forEach { (field, declaration) ->
                if (!isEventBodyTypeEnumEnrichment(field, system, extensionRoot, declaration)) {
                    if (field.path != extensionRoot && !field.path.startsWith("$extensionRoot.")) {
                        throw QuerySchemaConflictException(
                            "Query schema extension must be under [$extensionRoot]: [$field]."
                        )
                    }
                    system.fields[field]?.rejectSystemOverwrite(field, declaration)
                }
            }
        }
        var root = QueryFieldDeclaration()
        system.fields.toSortedMap(
            compareBy<QueryField> { it.path.length }.thenBy { it.path }
        ).forEach { (field, declaration) ->
            root = root.patchAt(field.path.split('.'), declaration, field, false)
        }
        extensions.groupBy(PrioritizedQuerySchemaDeclaration::priority).toSortedMap().forEach { (_, sources) ->
            val fields = linkedMapOf<QueryField, QueryFieldDeclaration>()
            sources.forEach { source ->
                source.declaration.fields.forEach { (field, declaration) ->
                    fields[field] = fields[field]?.merge(declaration, field, true) ?: declaration
                }
            }
            // Validate overlaps between nested declarations and path patches in the same priority.
            var priorityRoot = QueryFieldDeclaration()
            fields.toSortedMap(
                compareBy<QueryField> { it.path.length }.thenBy { it.path }
            ).forEach { (field, declaration) ->
                priorityRoot = priorityRoot.patchAt(field.path.split('.'), declaration, field, true)
                root = root.patchAt(field.path.split('.'), declaration, field, false)
            }
        }
        return LogicalQuerySchema(root.materialize())
    }

    private fun isEventBodyTypeEnumEnrichment(
        field: QueryField,
        system: QuerySchemaDeclaration,
        extensionRoot: String,
        extension: QueryFieldDeclaration,
    ): Boolean = extensionRoot == EVENT_PAYLOAD_ROOT && field == EVENT_BODY_TYPE_FIELD &&
        system.fields[field]?.enumValues === DeclarationValue.Unset && extension.hasOnlyEnumValues()

    private fun QueryFieldDeclaration.hasOnlyEnumValues(): Boolean {
        val values = (enumValues as? DeclarationValue.Set)?.value ?: return false
        return copy(enumValues = DeclarationValue.Unset) == QueryFieldDeclaration() &&
            values.isNotEmpty() && values.all { it.isString } && values.distinct().size == values.size
    }

    private fun QueryFieldDeclaration.rejectSystemOverwrite(field: QueryField, extension: QueryFieldDeclaration) {
        val systemLeaves = listOf(
            title, description, enumValues, valueTypes, nullable, required, kind,
            items, additionalProperties, alternatives, semanticType, maskRule
        )
        val extensionLeaves = listOf(
            extension.title, extension.description, extension.enumValues, extension.valueTypes,
            extension.nullable, extension.required, extension.kind, extension.items, extension.additionalProperties,
            extension.alternatives, extension.semanticType, extension.maskRule
        )
        if (systemLeaves.zip(
                extensionLeaves
            ).any { (left, right) -> left is DeclarationValue.Set && right is DeclarationValue.Set }
        ) {
            throw QuerySchemaConflictException("System query schema leaf cannot be overwritten: [$field].")
        }
    }

    private companion object {
        const val EVENT_PAYLOAD_ROOT = "${MessageRecords.BODY}.${MessageRecords.BODY}"
        val EVENT_BODY_TYPE_FIELD = QueryField("${MessageRecords.BODY}.${MessageRecords.BODY_TYPE}")
    }
}

private fun QueryFieldDeclaration.patchAt(
    path: List<String>,
    patch: QueryFieldDeclaration,
    field: QueryField,
    rejectDifferent: Boolean,
): QueryFieldDeclaration {
    if (path.isEmpty()) return merge(patch, field, rejectDifferent)
    if (inferredKind() == QueryValueKind.ARRAY) {
        return copy(
            items = DeclarationValue.Set(checkNotNull(items.valueOr(null)).patchAt(path, patch, field, rejectDifferent))
        )
    }
    if (inferredKind() == QueryValueKind.UNION) {
        return copy(
            alternatives = DeclarationValue.Set(
                alternatives.valueOr(emptyList()).map { branch ->
                    if (branch.inferredKind() == QueryValueKind.NULL) {
                        branch
                    } else {
                        branch.patchAt(
                            path,
                            patch,
                            field,
                            rejectDifferent
                        )
                    }
                }
            )
        )
    }
    if (inferredKind() !in setOf(QueryValueKind.OBJECT, QueryValueKind.UNKNOWN)) {
        throw QuerySchemaConflictException("Query schema path crosses a non-object value: [$field].")
    }
    val children = properties.valueOr(emptyMap()).toMutableMap()
    val name = path.first()
    val child = children[name] ?: additionalProperties.valueOr(null) ?: QueryFieldDeclaration()
    children[name] = child.patchAt(path.drop(1), patch, field, rejectDifferent)
    return copy(properties = DeclarationValue.Set(children))
}

@Suppress("ThrowsCount") // Publication reports distinct declaration conflicts without changing their exception type.
private fun QueryFieldDeclaration.materialize(): QueryValueSchema {
    val valueKind = inferredKind()
    if (maskRule is DeclarationValue.Set && !isMaskStringDomain()) {
        throw QuerySchemaConflictException("Masked query schema field must have STRING value type.")
    }
    val branches = alternatives.valueOr(emptyList()).map { it.materialize() }
    if (valueKind == QueryValueKind.UNION && nullable == DeclarationValue.Set(false) && branches.any { it.nullable }) {
        throw QuerySchemaConflictException("Union alternatives conflict with non-null query schema value.")
    }
    return try {
        QueryValueSchema(
            kind = valueKind,
            title = title.valueOr(null),
            description = description.valueOr(null),
            enumValues = enumValues.valueOr(null),
            valueTypes = valueTypes.valueOr(
                if (valueKind == QueryValueKind.OBJECT) setOf(QueryValueType.OBJECT) else emptySet()
            ),
            nullable = if (valueKind == QueryValueKind.UNION) branches.any { it.nullable } else nullable.valueOr(true),
            required = required.valueOr(false),
            semanticType = semanticType.valueOr(null),
            maskRule = maskRule.valueOr(null),
            properties = properties.valueOr(emptyMap()).mapValues { (_, child) -> child.materialize() },
            items = items.valueOr(null)?.copy(required = DeclarationValue.Set(false))?.materialize(),
            additionalProperties = additionalProperties.valueOr(
                null
            )?.copy(required = DeclarationValue.Set(false))?.materialize(),
            alternatives = branches,
        )
    } catch (error: IllegalArgumentException) {
        throw QuerySchemaConflictException("Invalid query schema value structure: ${error.message}", error)
    }
}
