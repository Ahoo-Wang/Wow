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

import me.ahoo.wow.schema.Types.isStdType
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.KVisibility
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.jvmErasure

/**
 * Utility object to handle state field paths.
 */
object StateFieldPaths {
    /**
     * Delimiter used to join property names in the path.
     */
    const val JOIN_DELIMITER = "."

    /**
     * Retrieves all property paths for a given KClass.
     *
     * @param parentName The name of the parent property (used for nested properties).
     * @param fields A list of initial properties to include in the result.
     * @return A list of all property paths.
     */
    fun KClass<*>.allFieldPaths(parentName: String = "", fields: List<String> = emptyList()): List<String> {
        val fieldPaths = mutableListOf<String>()
        if (fields.isNotEmpty()) {
            fieldPaths.addAll(fields)
        }
        val resolved = mutableSetOf<KProperty1<*, *>>()
        allFieldPathsInternal(fieldPaths, resolved, parentName)
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
        fieldPaths: MutableList<String>,
        resolved: MutableSet<KProperty1<*, *>>,
        parentName: String
    ) {
        memberProperties.filter {
            it.visibility == KVisibility.PUBLIC
        }.forEach { property ->
            if (resolved.contains(property)) {
                return@forEach
            }
            resolved.add(property)
            val fullName = property.resolveFieldName(parentName)
            fieldPaths.add(fullName)
            val nestedType = property.resolveNestedType() ?: return@forEach
            nestedType.allFieldPathsInternal(
                fieldPaths = fieldPaths,
                resolved = resolved,
                parentName = fullName
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
        val nestedType = returnType.jvmErasure

        if (nestedType.java.isStdType()) {
            return null
        }
        return nestedType
    }
}
