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
import me.ahoo.wow.api.query.schema.QueryCardinality
import me.ahoo.wow.query.schema.QuerySchemaDeclarationProperties.CARDINALITY
import me.ahoo.wow.query.schema.QuerySchemaDeclarationProperties.DESCRIPTION
import me.ahoo.wow.query.schema.QuerySchemaDeclarationProperties.DYNAMIC_CHILDREN
import me.ahoo.wow.query.schema.QuerySchemaDeclarationProperties.ENUM_VALUES
import me.ahoo.wow.query.schema.QuerySchemaDeclarationProperties.NULLABLE
import me.ahoo.wow.query.schema.QuerySchemaDeclarationProperties.REQUIRED
import me.ahoo.wow.query.schema.QuerySchemaDeclarationProperties.SEMANTIC_TYPE
import me.ahoo.wow.query.schema.QuerySchemaDeclarationProperties.TITLE
import me.ahoo.wow.query.schema.QuerySchemaDeclarationProperties.VALUE_TYPES
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.serialization.state.StateAggregateRecords

internal class QuerySchemaMerger {
    fun merge(
        system: QuerySchemaDeclaration,
        extensions: List<PrioritizedQuerySchemaDeclaration>,
    ): LogicalQuerySchema {
        val extensionRoot = if (LogicalField(StateAggregateRecords.STATE) in system.fields) {
            StateAggregateRecords.STATE
        } else {
            "${MessageRecords.BODY}.${MessageRecords.BODY}"
        }
        extensions.forEach { extension ->
            extension.declaration.fields.forEach { (field, declaration) ->
                if (isEventBodyTypeEnumEnrichment(field, system, extensionRoot, declaration)) {
                    return@forEach
                }
                validateExtensionPath(field, extensionRoot)
                system.fields[field]?.rejectSystemOverwrite(field, declaration)
            }
        }

        val merged = system.fields.toMutableMap()
        extensions.groupBy(PrioritizedQuerySchemaDeclaration::priority)
            .toSortedMap()
            .forEach { (_, declarations) ->
                val priorityFields = mutableMapOf<LogicalField, QueryFieldDeclaration>()
                declarations.forEach { prioritized ->
                    prioritized.declaration.fields.forEach { (field, declaration) ->
                        priorityFields[field] = priorityFields[field]
                            ?.merge(declaration, field, rejectDifferent = true)
                            ?: declaration
                    }
                }
                priorityFields.forEach { (field, declaration) ->
                    merged[field] = merged[field]
                        ?.merge(declaration, field, rejectDifferent = false)
                        ?: declaration
                }
            }

        return LogicalQuerySchema(
            merged.toSortedMap(compareBy(LogicalField::value)).mapValues { (_, declaration) ->
                declaration.materialize()
            },
        )
    }

    private fun validateExtensionPath(field: LogicalField, extensionRoot: String) {
        if (field.value != extensionRoot && !field.value.startsWith("$extensionRoot.")) {
            throw QuerySchemaConflictException("Query schema extension must be under [$extensionRoot]: [$field].")
        }
    }

    private fun isEventBodyTypeEnumEnrichment(
        field: LogicalField,
        system: QuerySchemaDeclaration,
        extensionRoot: String,
        extension: QueryFieldDeclaration,
    ): Boolean =
        extensionRoot == EVENT_PAYLOAD_ROOT &&
            field == EVENT_BODY_TYPE_FIELD &&
            system.fields[field]?.enumValues === DeclarationValue.Unset &&
            extension.hasOnlyEnumValues()

    private fun QueryFieldDeclaration.hasOnlyEnumValues(): Boolean =
        title === DeclarationValue.Unset &&
            description === DeclarationValue.Unset &&
            enumValues is DeclarationValue.Set &&
            valueTypes === DeclarationValue.Unset &&
            nullable === DeclarationValue.Unset &&
            required === DeclarationValue.Unset &&
            cardinality === DeclarationValue.Unset &&
            semanticType === DeclarationValue.Unset &&
            dynamicChildren === DeclarationValue.Unset &&
            maskRule === DeclarationValue.Unset

    private fun QueryFieldDeclaration.rejectSystemOverwrite(
        field: LogicalField,
        extension: QueryFieldDeclaration,
    ) {
        rejectSystemLeaf(field, TITLE, title, extension.title)
        rejectSystemLeaf(field, DESCRIPTION, description, extension.description)
        rejectSystemLeaf(field, ENUM_VALUES, enumValues, extension.enumValues)
        rejectSystemLeaf(field, VALUE_TYPES, valueTypes, extension.valueTypes)
        rejectSystemLeaf(field, NULLABLE, nullable, extension.nullable)
        rejectSystemLeaf(field, REQUIRED, required, extension.required)
        rejectSystemLeaf(field, CARDINALITY, cardinality, extension.cardinality)
        rejectSystemLeaf(field, SEMANTIC_TYPE, semanticType, extension.semanticType)
        rejectSystemLeaf(field, DYNAMIC_CHILDREN, dynamicChildren, extension.dynamicChildren)
    }

    private fun rejectSystemLeaf(
        field: LogicalField,
        leaf: String,
        system: DeclarationValue<*>,
        extension: DeclarationValue<*>,
    ) {
        if (system is DeclarationValue.Set && extension is DeclarationValue.Set) {
            throw QuerySchemaConflictException("System query schema leaf cannot be overwritten: [$field.$leaf].")
        }
    }

    private fun QueryFieldDeclaration.materialize() = LogicalQueryFieldSchema(
        title = title.valueOr(null),
        description = description.valueOr(null),
        enumValues = enumValues.valueOr(null),
        valueTypes = valueTypes.valueOr(emptySet()).also { valueTypes ->
            if (maskRule is DeclarationValue.Set && valueTypes != setOf(me.ahoo.wow.api.query.schema.QueryValueType.STRING)) {
                throw QuerySchemaConflictException("Masked query schema field must have STRING value type.")
            }
        },
        nullable = nullable.valueOr(true),
        required = required.valueOr(false),
        cardinality = cardinality.valueOr(QueryCardinality.SINGLE),
        semanticType = semanticType.valueOr(null),
        dynamicChildren = dynamicChildren.valueOr(false),
        maskRule = (maskRule as? DeclarationValue.Set)?.value,
    )

    private fun <T> DeclarationValue<T>.valueOr(default: T): T =
        when (this) {
            is DeclarationValue.Set -> value
            DeclarationValue.Unset -> default
        }

    private companion object {
        const val EVENT_PAYLOAD_ROOT = "${MessageRecords.BODY}.${MessageRecords.BODY}"
        val EVENT_BODY_TYPE_FIELD = LogicalField("${MessageRecords.BODY}.${MessageRecords.BODY_TYPE}")
    }
}
