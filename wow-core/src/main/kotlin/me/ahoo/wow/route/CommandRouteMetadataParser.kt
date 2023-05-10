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

package me.ahoo.wow.route

import com.fasterxml.jackson.annotation.JsonProperty
import me.ahoo.wow.api.annotation.CommandRoute
import me.ahoo.wow.command.annotation.asCommandMetadata
import me.ahoo.wow.infra.reflection.AnnotationScanner.scan
import me.ahoo.wow.infra.reflection.ClassMetadata
import me.ahoo.wow.infra.reflection.ClassVisitor
import me.ahoo.wow.metadata.CacheableMetadataParser
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

    private var pathVariables: MutableSet<PathVariableMetadata> = mutableSetOf()

    override fun visitField(field: Field) {
        field.scan<CommandRoute.PathVariable>()?.let {
            val fieldName = field.scan<JsonProperty>()?.value
                .orEmpty()
                .ifBlank {
                    field.name
                }
            val pathVariableName = it.name.ifBlank { fieldName }
            val fieldPath = buildList<String> {
                add(fieldName)
                addAll(it.nestedPath)
            }
            val pathVariableMetadata = PathVariableMetadata(
                fieldPath = fieldPath,
                pathVariableName = pathVariableName,
                required = it.required
            )
            pathVariables.add(pathVariableMetadata)
        }
    }

    fun asMetadata(): CommandRouteMetadata<C> {
        val commandMetadata = commandType.asCommandMetadata()

        return commandType.scan<CommandRoute>()?.let {
            CommandRouteMetadata(
                path = it.path,
                enabled = it.enabled,
                ignoreAggregateNamePrefix = it.ignoreAggregateNamePrefix,
                commandMetadata = commandMetadata,
                pathVariableMetadata = pathVariables,
            )
        } ?: CommandRouteMetadata(
            path = commandMetadata.name,
            enabled = true,
            ignoreAggregateNamePrefix = false,
            commandMetadata = commandMetadata,
            pathVariableMetadata = pathVariables,
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
