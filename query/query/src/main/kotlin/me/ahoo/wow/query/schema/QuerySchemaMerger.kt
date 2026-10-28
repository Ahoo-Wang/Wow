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
import me.ahoo.wow.api.query.QueryValueKind
import me.ahoo.wow.spi.json.query.SnapshotFields

internal class QuerySchemaMerger {
    /**
     * Merges [extensions] over the [system] fields. Every extension field lies under [extensionRoot], the model's
     * payload; `null` lets extensions declare fields anywhere, as a dataset's do.
     */
    fun merge(
        system: QuerySchemaDeclaration,
        extensions: List<PrioritizedQuerySchemaDeclaration>,
        sensitivity: QuerySensitivityPolicy = QuerySensitivityPolicy.DEFAULT,
        extensionRoot: String? = payloadRootOf(system),
    ): LogicalQuerySchema {
        extensions.forEach { extension ->
            extension.declaration.fields.forEach { (field, declaration) ->
                if (extensionRoot != null && field.path != extensionRoot && !field.path.startsWith("$extensionRoot.")) {
                    throw QuerySchemaConflictException(
                        "Query schema extension must be under [$extensionRoot]: [$field]."
                    )
                }
                system.fields[field]?.rejectSystemOverwrite(field, declaration)
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
        return LogicalQuerySchema(root.materialize(), sensitivity)
    }

    private fun QueryFieldDeclaration.rejectSystemOverwrite(field: QueryField, extension: QueryFieldDeclaration) {
        val systemLeaves = listOf(
            title, description, enumValues, enumDescriptions, valueTypes, nullable, required, kind,
            items, additionalProperties, alternatives, semanticType, maskRule, aliases, deprecated
        )
        val extensionLeaves = listOf(
            extension.title, extension.description, extension.enumValues, extension.enumDescriptions,
            extension.valueTypes,
            extension.nullable, extension.required, extension.kind, extension.items, extension.additionalProperties,
            extension.alternatives, extension.semanticType, extension.maskRule, extension.aliases,
            extension.deprecated
        )
        if (systemLeaves.zip(
                extensionLeaves
            ).any { (left, right) -> left is DeclarationValue.Set && right is DeclarationValue.Set }
        ) {
            throw QuerySchemaConflictException("System query schema leaf cannot be overwritten: [$field].")
        }
    }

    private companion object {
        /** The payload root of a built-in model's [system] fields: the snapshot state; a dataset declares none. */
        fun payloadRootOf(system: QuerySchemaDeclaration): String? =
            SnapshotFields.STATE.takeIf { QueryField(it) in system.fields }
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
            enumDescriptions = enumDescriptions.valueOr(emptyMap()),
            valueTypes = valueTypes.valueOr(
                if (valueKind == QueryValueKind.OBJECT) setOf(QueryValueType.OBJECT) else emptySet()
            ),
            nullable = if (valueKind == QueryValueKind.UNION) branches.any { it.nullable } else nullable.valueOr(true),
            required = required.valueOr(false),
            semanticType = semanticType.valueOr(null),
            maskRule = maskRule.valueOr(null),
            variant = variant.valueOr(null),
            aliases = aliases.valueOr(emptySet()),
            deprecated = deprecated.valueOr(null),
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
