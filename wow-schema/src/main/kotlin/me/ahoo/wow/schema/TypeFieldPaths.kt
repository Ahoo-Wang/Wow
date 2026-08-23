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

package me.ahoo.wow.schema

import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.schema.TypeFieldPaths.allFieldPaths
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.serialization.state.StateAggregateRecords
import tools.jackson.databind.JsonNode
import kotlin.reflect.KClass

/**
 * Utility object to handle state field paths.
 */
object TypeFieldPaths {
    const val JOIN_DELIMITER = "."
    const val MAX_DEPTH = 5
    private val schemaGenerator by lazy { SchemaGeneratorBuilder().build() }

    fun KClass<*>.allFieldPaths(
        parentName: String = "",
        fields: List<String> = emptyList(),
        maxDepth: Int = MAX_DEPTH
    ): Set<String> {
        val fieldPaths = linkedSetOf<String>()
        if (fields.isNotEmpty()) {
            fieldPaths.addAll(fields)
        }
        val rootSchema = schemaGenerator.generateSchema(java)
        rootSchema.collectFieldPaths(rootSchema, fieldPaths, parentName, 1, maxDepth, emptySet())
        return fieldPaths
    }

    private fun JsonNode.collectFieldPaths(
        rootSchema: JsonNode,
        fieldPaths: LinkedHashSet<String>,
        parentName: String,
        depth: Int,
        maxDepth: Int,
        resolvingReferences: Set<String>,
    ) {
        if (depth > maxDepth) {
            return
        }
        get("\$ref")?.asString()?.takeIf { it.startsWith("#/") && it !in resolvingReferences }?.let { reference ->
            rootSchema.at(reference.removePrefix("#")).collectFieldPaths(
                rootSchema,
                fieldPaths,
                parentName,
                depth,
                maxDepth,
                resolvingReferences + reference,
            )
        }
        listOf("allOf", "anyOf", "oneOf").forEach { composition ->
            get(composition)?.forEach { alternative ->
                alternative.collectFieldPaths(
                    rootSchema,
                    fieldPaths,
                    parentName,
                    depth,
                    maxDepth,
                    resolvingReferences,
                )
            }
        }
        get("properties")?.properties()?.forEach { (propertyName, propertySchema) ->
            val fullName = resolveFieldName(parentName, propertyName)
            fieldPaths.add(fullName)
            propertySchema.collectFieldPaths(rootSchema, fieldPaths, fullName, depth + 1, maxDepth, emptySet())
        }
        get("items")?.collectFieldPaths(
            rootSchema,
            fieldPaths,
            parentName,
            depth,
            maxDepth,
            resolvingReferences,
        )
    }

    private fun resolveFieldName(parentName: String, fieldName: String): String =
        if (parentName.isBlank()) fieldName else "$parentName${JOIN_DELIMITER}$fieldName"
}

object AggregatedFieldPaths {
    fun KClass<*>.stateAggregatedFieldPaths(): Set<String> {
        return allFieldPaths(
            parentName = StateAggregateRecords.STATE,
            fields = listOf(
                "",
                MessageRecords.AGGREGATE_ID,
                MessageRecords.TENANT_ID,
                MessageRecords.OWNER_ID,
                MessageRecords.SPACE_ID,
                MessageRecords.VERSION,
                StateAggregateRecords.EVENT_ID,
                StateAggregateRecords.FIRST_OPERATOR,
                StateAggregateRecords.OPERATOR,
                StateAggregateRecords.FIRST_EVENT_TIME,
                StateAggregateRecords.EVENT_TIME,
                StateAggregateRecords.TAGS,
                StateAggregateRecords.DELETED,
                StateAggregateRecords.STATE
            )
        )
    }

    fun KClass<*>.commandAggregatedFieldPaths(): Set<String> {
        val aggregateMetadata = this.java.aggregateMetadata<Any, Any>()
        val stateAggregateType = aggregateMetadata.state.aggregateType.kotlin
        return stateAggregateType.stateAggregatedFieldPaths()
    }
}
