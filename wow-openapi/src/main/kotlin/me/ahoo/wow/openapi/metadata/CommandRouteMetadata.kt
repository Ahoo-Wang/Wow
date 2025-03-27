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

package me.ahoo.wow.openapi.metadata

import com.fasterxml.jackson.databind.node.ObjectNode
import me.ahoo.wow.api.annotation.CommandRoute
import me.ahoo.wow.api.naming.DescriptionCapable
import me.ahoo.wow.api.naming.SummaryCapable
import me.ahoo.wow.command.metadata.CommandMetadata
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.serialization.toObject

data class CommandRouteMetadata<C>(
    val enabled: Boolean,
    val action: String,
    val method: String,
    val prefix: String = "",
    val appendIdPath: CommandRoute.AppendPath = CommandRoute.AppendPath.DEFAULT,
    val appendTenantPath: CommandRoute.AppendPath = CommandRoute.AppendPath.DEFAULT,
    val appendOwnerPath: CommandRoute.AppendPath = CommandRoute.AppendPath.DEFAULT,
    val commandMetadata: CommandMetadata<C>,
    override val summary: String = "",
    override val description: String = "",
    /**
     * filedName -> PathVariableMetadata
     */
    val pathVariableMetadata: Set<VariableMetadata> = setOf(),
    val headerVariableMetadata: Set<VariableMetadata> = setOf()
) : me.ahoo.wow.metadata.Metadata, SummaryCapable, DescriptionCapable {

    private fun ObjectNode.injectVariables(
        variableMetadata: Set<VariableMetadata>,
        variableProvider: (String) -> String?
    ): ObjectNode {
        variableMetadata.filter {
            it.bound
        }.forEach { metadata ->
            val variableValue = variableProvider(metadata.variableName)
            if (metadata.required) {
                requireNotNull(variableValue) {
                    "Required variable [${metadata.variableName}] not found."
                }
            }
            if (variableValue == null) {
                return@forEach
            }
            var fieldObject = this
            for (i in 0 until metadata.fieldPath.size - 1) {
                val fieldPath = metadata.fieldPath[i]
                var nextFieldObject = fieldObject.get(fieldPath)
                if (nextFieldObject == null) {
                    nextFieldObject = ObjectNode(JsonSerializer.nodeFactory)
                    fieldObject.set<ObjectNode>(fieldPath, nextFieldObject)
                }
                fieldObject = nextFieldObject as ObjectNode
            }
            fieldObject.put(metadata.fieldName, variableValue)
        }
        return this
    }

    fun decode(
        commandNode: ObjectNode,
        pathVariableProvider: (String) -> String?,
        headerVariableProvider: (String) -> String?
    ): C {
        return commandNode.injectVariables(pathVariableMetadata, pathVariableProvider)
            .injectVariables(headerVariableMetadata, headerVariableProvider)
            .toObject(commandMetadata.commandType)
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

data class VariableMetadata(
    val fieldPath: List<String>,
    /**
     * variable name
     */
    val variableName: String,
    val required: Boolean,
    /**
     * Whether the command body field is bound
     */
    val bound: Boolean = true
) {
    val fieldName: String by lazy {
        fieldPath.last()
    }
}
