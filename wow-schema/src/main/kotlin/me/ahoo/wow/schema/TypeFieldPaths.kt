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

import com.fasterxml.jackson.annotation.JsonSubTypes
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.schema.TypeFieldPaths.allFieldPaths
import me.ahoo.wow.schema.Types.isStdType
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.serialization.state.StateAggregateRecords
import me.ahoo.wow.serialization.toBeanDescription
import tools.jackson.databind.JavaType
import tools.jackson.databind.introspect.BeanPropertyDefinition
import tools.jackson.databind.util.NameTransformer
import kotlin.reflect.KClass
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.isSubclassOf

/**
 * Utility object to handle state field paths.
 */
object TypeFieldPaths {
    /**
     * Delimiter used to join property names in the path.
     */
    const val JOIN_DELIMITER = "."
    const val MAX_DEPTH = 5

    /**
     * Retrieves all property paths for a given KClass.
     *
     * @param parentName The name of the parent property (used for nested properties).
     * @param fields A list of initial properties to include in the result.
     * @param maxDepth The maximum nested-property depth to inspect.
     * @return A list of all property paths.
     */
    fun KClass<*>.allFieldPaths(
        parentName: String = "",
        fields: List<String> = emptyList(),
        maxDepth: Int = MAX_DEPTH
    ): Set<String> {
        val fieldPaths = linkedSetOf<String>()
        if (fields.isNotEmpty()) {
            fieldPaths.addAll(fields)
        }
        JsonSerializer.constructType(java).allFieldPathsInternal(fieldPaths, parentName, 1, maxDepth)
        return fieldPaths
    }

    /**
     * Internal method to recursively retrieve all property paths.
     *
     * @param fieldPaths The list to store property paths.
     * @param parentName The name of the parent property (used for nested properties).
     * @param depth The current nested-property depth.
     * @param maxDepth The maximum nested-property depth to inspect.
     * @param nameTransformer The Jackson name transformer inherited from an unwrapped parent.
     */
    private fun JavaType.allFieldPathsInternal(
        fieldPaths: LinkedHashSet<String>,
        parentName: String,
        depth: Int,
        maxDepth: Int,
        nameTransformer: NameTransformer? = null,
    ) {
        if (depth > maxDepth) {
            return
        }
        val kotlinType = rawClass.kotlin
        if (kotlinType.isSubclassOf(AggregateId::class)) {
            listOf(
                MessageRecords.CONTEXT_NAME,
                MessageRecords.AGGREGATE_NAME,
                MessageRecords.AGGREGATE_ID,
                MessageRecords.TENANT_ID
            ).forEach { field ->
                fieldPaths.add(resolveFieldName(parentName, field))
            }
        }
        val jsonSubTypes = kotlinType.findAnnotation<JsonSubTypes>()
        if (jsonSubTypes != null) {
            for (jsonSubType in jsonSubTypes.value) {
                JsonSerializer.constructType(jsonSubType.value.java).allFieldPathsInternal(
                    fieldPaths = fieldPaths,
                    parentName = parentName,
                    depth = depth,
                    maxDepth = maxDepth,
                    nameTransformer = nameTransformer,
                )
            }
            return
        }

        toBeanDescription().findProperties().asSequence()
            .filter(BeanPropertyDefinition::couldSerialize)
            .forEach { property ->
                val nestedType = property.primaryType.resolveNestedType()
                val unwrappingTransformer = nestedType?.let { property.unwrappingNameTransformer() }
                if (unwrappingTransformer != null) {
                    requireNotNull(nestedType).allFieldPathsInternal(
                        fieldPaths = fieldPaths,
                        parentName = parentName,
                        depth = depth,
                        maxDepth = maxDepth,
                        nameTransformer = nameTransformer?.let {
                            NameTransformer.chainedTransformer(it, unwrappingTransformer)
                        } ?: unwrappingTransformer,
                    )
                    return@forEach
                }

                val propertyName = nameTransformer?.transform(property.name) ?: property.name
                val fullName = resolveFieldName(parentName, propertyName)
                fieldPaths.add(fullName)
                nestedType?.allFieldPathsInternal(
                    fieldPaths = fieldPaths,
                    parentName = fullName,
                    depth = depth + 1,
                    maxDepth = maxDepth,
                )
            }
    }

    private fun BeanPropertyDefinition.unwrappingNameTransformer(): NameTransformer? {
        val member = primaryMember ?: accessor ?: return null
        val config = JsonSerializer.serializationConfig()
        return config.annotationIntrospector.findUnwrappingNameTransformer(config, member)
    }

    private fun resolveFieldName(parentName: String, fieldName: String): String =
        if (parentName.isBlank()) fieldName else "$parentName${JOIN_DELIMITER}$fieldName"

    /**
     * Resolves the nested type of a property.
     *
     * @return The nested type if it's not a standard type, otherwise null.
     */
    private fun JavaType.resolveNestedType(): JavaType? {
        val nestedType = if (isCollectionLikeType || isArrayType) {
            contentType ?: return null
        } else {
            this
        }

        if (nestedType.rawClass.isStdType()) {
            return null
        }
        return nestedType
    }
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
