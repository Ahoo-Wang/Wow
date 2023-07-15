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

import com.fasterxml.jackson.annotation.JsonProperty
import me.ahoo.wow.api.annotation.CommandRoute
import me.ahoo.wow.api.annotation.DEFAULT_COMMAND_PATH
import me.ahoo.wow.command.annotation.asCommandMetadata
import me.ahoo.wow.command.metadata.CommandMetadata
import me.ahoo.wow.infra.reflection.AnnotationScanner.scan
import me.ahoo.wow.infra.reflection.ClassMetadata
import me.ahoo.wow.infra.reflection.ClassVisitor
import me.ahoo.wow.metadata.CacheableMetadataParser
import me.ahoo.wow.openapi.Https
import java.lang.reflect.Field

object CommandRouteMetadataParser : CacheableMetadataParser<Class<*>, CommandRouteMetadata<*>>() {
    override fun parseAsMetadata(type: Class<*>): CommandRouteMetadata<*> {
        val visitor = CommandRouteMetadataVisitor(type)
        ClassMetadata.visit(type, visitor)
        return visitor.asMetadata()
    }
}

internal class CommandRouteMetadataVisitor<C>(private val commandType: Class<C>) :
    ClassVisitor {

    private var pathVariables: MutableSet<VariableMetadata> = mutableSetOf()
    private var headerVariables: MutableSet<VariableMetadata> = mutableSetOf()

    private fun Field.asVariableMetadata(name: String, nestedPath: Array<String>, required: Boolean): VariableMetadata {
        val fieldName = scan<JsonProperty>()?.value
            .orEmpty()
            .ifBlank {
                this.name
            }
        val variableName = name.ifBlank { fieldName }
        val fieldPath = buildList {
            add(fieldName)
            addAll(nestedPath)
        }
        return VariableMetadata(
            fieldPath = fieldPath,
            variableName = variableName,
            required = required,
        )
    }

    override fun visitField(field: Field) {
        field.scan<CommandRoute.PathVariable>()?.let {
            val variableMetadata = field.asVariableMetadata(it.name, it.nestedPath, it.required)
            pathVariables.add(variableMetadata)
        }
        field.scan<CommandRoute.HeaderVariable>()?.let {
            val variableMetadata = field.asVariableMetadata(it.name, it.nestedPath, it.required)
            headerVariables.add(variableMetadata)
        }
    }

    private fun CommandMetadata<*>.asMethod(routeMethod: CommandRoute.Method = CommandRoute.Method.DEFAULT): String {
        if (routeMethod != CommandRoute.Method.DEFAULT) {
            return routeMethod.name
        }
        if (isCreate) {
            return Https.Method.POST
        }
        if (isDelete) {
            return Https.Method.DELETE
        }
        return Https.Method.PUT
    }

    fun asMetadata(): CommandRouteMetadata<C> {
        val commandMetadata = commandType.asCommandMetadata()
        return commandType.scan<CommandRoute>()?.let {
            val path = if (it.path == DEFAULT_COMMAND_PATH) {
                commandMetadata.name
            } else {
                it.path
            }
            CommandRouteMetadata(
                enabled = it.enabled,
                path = path,
                method = commandMetadata.asMethod(it.method),
                prefix = it.prefix,
                appendIdPath = it.appendIdPath,
                appendTenantPath = it.appendTenantPath,
                ignoreAggregateNamePrefix = it.ignoreAggregateNamePrefix,
                commandMetadata = commandMetadata,
                pathVariableMetadata = pathVariables,
                headerVariableMetadata = headerVariables,
            )
        } ?: CommandRouteMetadata(
            enabled = true,
            path = commandMetadata.name,
            method = commandMetadata.asMethod(),
            appendIdPath = CommandRoute.AppendPath.DEFAULT,
            appendTenantPath = CommandRoute.AppendPath.DEFAULT,
            ignoreAggregateNamePrefix = false,
            commandMetadata = commandMetadata,
            pathVariableMetadata = pathVariables,
            headerVariableMetadata = headerVariables,
        )
    }
}

fun <C> Class<out C>.asCommandRouteMetadata(): CommandRouteMetadata<C> {
    @Suppress("UNCHECKED_CAST")
    return CommandRouteMetadataParser.parse(this) as CommandRouteMetadata<C>
}

inline fun <reified C> commandRouteMetadata(): CommandRouteMetadata<C> {
    return C::class.java.asCommandRouteMetadata()
}
