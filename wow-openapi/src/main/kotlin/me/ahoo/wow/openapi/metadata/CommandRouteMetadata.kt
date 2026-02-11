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

import me.ahoo.wow.api.annotation.CommandRoute
import me.ahoo.wow.api.naming.DescriptionCapable
import me.ahoo.wow.api.naming.EnabledCapable
import me.ahoo.wow.api.naming.SummaryCapable
import me.ahoo.wow.command.metadata.CommandMetadata
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.serialization.toObject
import tools.jackson.databind.node.ObjectNode
import java.lang.reflect.Field
import java.lang.reflect.Type

data class CommandRouteMetadata<C>(
    override val enabled: Boolean,
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
) : me.ahoo.wow.metadata.Metadata, EnabledCapable, SummaryCapable, DescriptionCapable {

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
                    nextFieldObject = JsonSerializer.createObjectNode()
                    fieldObject.set(fieldPath, nextFieldObject)
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

/**
 * Represents metadata for a variable, including its field, path, name, and whether it's required or bound.
 *
 * @property field The [Field] object representing the variable. Can be null.
 * @property fieldPath A list of strings representing the path to the variable within a nested structure.
 * @property variableName The name of the variable.
 * @property required Indicates if the variable is required.
 * @property bound Whether the command body field is bound. Defaults to true.
 * @property fieldName Provides the name of the last field in the [fieldPath].
 * @property variableType Dynamically determines the type of the variable based on the provided [field] and [fieldPath]. It returns `null` if the [field] is null or if the [fieldPath] cannot be resolved.
 *
 */
data class VariableMetadata(
    val field: Field?,
    val fieldPath: List<String>,
    val variableName: String,
    val required: Boolean,
    val bound: Boolean = true
) {
    val fieldName: String by lazy {
        fieldPath.last()
    }

    val variableType: Type? by lazy {
        this.field ?: return@lazy null
        if (fieldPath.isEmpty()) {
            return@lazy this.field.genericType
        }
        var currentField: Field? = this.field
        val nestedFieldPath = fieldPath.drop(1)
        for (path in nestedFieldPath) {
            currentField =
                currentField?.type?.declaredFields?.firstOrNull { it.name == path }
        }
        return@lazy currentField?.genericType
    }
}
