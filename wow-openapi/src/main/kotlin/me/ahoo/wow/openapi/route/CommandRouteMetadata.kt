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

package me.ahoo.wow.openapi.route

import com.fasterxml.jackson.databind.node.ObjectNode
import me.ahoo.wow.command.metadata.CommandMetadata
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.serialization.asObject

data class CommandRouteMetadata<C>(
    val enabled: Boolean,
    val path: String,
    val appendIdPath: Boolean = false,
    val ignoreAggregateNamePrefix: Boolean = false,
    val commandMetadata: CommandMetadata<C>,
    /**
     * filedName -> PathVariableMetadata
     */
    val pathVariableMetadata: Set<PathVariableMetadata> = setOf()
) : me.ahoo.wow.metadata.Metadata {

    private fun injectPathVariables(commandNode: ObjectNode, pathVariables: Map<String, String>): ObjectNode {
        pathVariableMetadata.forEach { pathVariableMetadata ->
            val pathVariableValue = pathVariables[pathVariableMetadata.pathVariableName]
            if (pathVariableMetadata.required) {
                requireNotNull(pathVariableValue)
            }
            if (pathVariableValue == null) {
                return@forEach
            }
            var fieldObject = commandNode
            for (i in 0 until pathVariableMetadata.fieldPath.size - 1) {
                val fieldPath = pathVariableMetadata.fieldPath[i]
                var nextFieldObject = fieldObject.get(fieldPath)
                if (nextFieldObject == null) {
                    nextFieldObject = ObjectNode(JsonSerializer.nodeFactory)
                    fieldObject.set<ObjectNode>(fieldPath, nextFieldObject)
                }
                fieldObject = nextFieldObject as ObjectNode
            }
            fieldObject.put(pathVariableMetadata.fieldName, pathVariableValue)
        }
        return commandNode
    }

    fun decode(commandNode: ObjectNode, pathVariables: Map<String, String>): C {
        return injectPathVariables(commandNode, pathVariables).asObject(commandMetadata.commandType)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as CommandRouteMetadata<*>

        return commandMetadata == other.commandMetadata
    }

    override fun hashCode(): Int {
        return commandMetadata.hashCode()
    }
}

data class PathVariableMetadata(
    val fieldPath: List<String>,
    /**
     * path variable name
     */
    val pathVariableName: String,
    val required: Boolean
) {
    val fieldName: String by lazy {
        fieldPath.last()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PathVariableMetadata

        return pathVariableName == other.pathVariableName
    }

    override fun hashCode(): Int {
        return pathVariableName.hashCode()
    }
}
