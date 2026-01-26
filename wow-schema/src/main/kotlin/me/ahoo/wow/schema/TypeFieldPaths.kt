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

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonSubTypes
import me.ahoo.wow.infra.reflection.AnnotationScanner.scanAnnotation
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.schema.TypeFieldPaths.allFieldPaths
import me.ahoo.wow.schema.Types.isStdType
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.serialization.state.StateAggregateRecords
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.KVisibility
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.jvmErasure

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
        allFieldPathsInternal(fieldPaths, parentName, 1, maxDepth)
        return fieldPaths
    }

    /**
     * Internal method to recursively retrieve all property paths.
     *
     * @param fieldPaths The list to store property paths.
     * @param resolved A set to keep track of resolved properties to avoid duplicates.
     * @param parentName The name of the parent property (used for nested properties).
     */
    private fun KClass<*>.allFieldPathsInternal(
        fieldPaths: LinkedHashSet<String>,
        parentName: String,
        depth: Int,
        maxDepth: Int
    ) {
        if (depth > MAX_DEPTH) {
            return
        }
        val jsonSubTypes = this.findAnnotation<JsonSubTypes>()
        if (jsonSubTypes != null) {
            for (jsonSubType in jsonSubTypes.value) {
                val subType = jsonSubType.value
                subType.allFieldPathsInternal(
                    fieldPaths = fieldPaths,
                    parentName = parentName,
                    depth = depth + 1,
                    maxDepth = maxDepth
                )
            }
            return
        }

        memberProperties.filter {
            it.visibility == KVisibility.PUBLIC &&
                // 排除静态属性
                it.isConst.not() &&
                it.scanAnnotation<JsonIgnore>()?.value != true
        }.forEach { property ->
            val fullName = property.resolveFieldName(parentName)
            fieldPaths.add(fullName)
            val nestedType = property.resolveNestedType() ?: return@forEach
            nestedType.allFieldPathsInternal(
                fieldPaths = fieldPaths,
                parentName = fullName,
                depth = depth + 1,
                maxDepth = maxDepth
            )
        }
    }

    /**
     * Resolves the full field name including the parent name.
     *
     * @param parentName The name of the parent field.
     * @return The full field name.
     */
    private fun KProperty1<*, *>.resolveFieldName(parentName: String): String {
        if (parentName.isBlank()) {
            return name
        }
        return "$parentName${JOIN_DELIMITER}$name"
    }

    /**
     * Resolves the nested type of a property.
     *
     * @return The nested type if it's not a standard type, otherwise null.
     */
    private fun KProperty1<*, *>.resolveNestedType(): KClass<*>? {
        val returnKClass = returnType.jvmErasure
        val nestedType = if (returnKClass.isSubclassOf(Collection::class) || returnKClass.java.isArray) {
            returnType.arguments.firstOrNull()?.type?.jvmErasure ?: return null
        } else {
            returnType.jvmErasure
        }

        if (nestedType.java.isStdType()) {
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
