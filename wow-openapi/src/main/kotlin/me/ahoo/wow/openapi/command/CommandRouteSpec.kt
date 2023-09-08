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

package me.ahoo.wow.openapi.command

import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.models.media.IntegerSchema
import io.swagger.v3.oas.models.media.StringSchema
import me.ahoo.wow.api.annotation.CommandRoute
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.command.CommandResult
import me.ahoo.wow.modeling.asStringWithAlias
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import me.ahoo.wow.openapi.AggregateRouteSpec
import me.ahoo.wow.openapi.PathBuilder
import me.ahoo.wow.openapi.RouteSpec
import me.ahoo.wow.openapi.Tags.asTags
import me.ahoo.wow.openapi.route.CommandRouteMetadata

open class CommandRouteSpec(
    override val currentContext: NamedBoundedContext,
    override val aggregateMetadata: AggregateMetadata<*, *>,
    val commandRouteMetadata: CommandRouteMetadata<*>,
) : AggregateRouteSpec() {

    override val id: String
        get() = "${aggregateMetadata.asStringWithAlias()}.${commandRouteMetadata.commandMetadata.name}"
    override val method: String
        get() {
            return commandRouteMetadata.method
        }

    private fun CommandRoute.AppendPath.resolve(default: Boolean): Boolean {
        return when (this) {
            CommandRoute.AppendPath.DEFAULT -> {
                default
            }

            CommandRoute.AppendPath.ALWAYS -> true
            CommandRoute.AppendPath.NEVER -> false
        }
    }

    override val appendTenantPath: Boolean
        get() {
            val default = super.appendTenantPath
            return commandRouteMetadata.appendTenantPath.resolve(default)
        }
    override val appendIdPath: Boolean
        get() {
            val default = commandRouteMetadata.commandMetadata.aggregateIdGetter == null &&
                !commandRouteMetadata.commandMetadata.isCreate
            return commandRouteMetadata.appendIdPath.resolve(default)
        }

    override val path: String
        get() {
            if (commandRouteMetadata.ignoreAggregateNamePrefix) {
                return PathBuilder().append(commandRouteMetadata.prefix).append(commandRouteMetadata.path).build()
            }
            return PathBuilder()
                .append(commandRouteMetadata.prefix)
                .append(super.path)
                .append(commandRouteMetadata.path).build()
        }
    override val summary: String
        get() = commandRouteMetadata.summary
    override val description: String
        get() = commandRouteMetadata.description
    override val tags: List<String>
        get() {
            val tags = mutableListOf<String>()
            tags.addAll(super.tags)
            commandRouteMetadata.commandMetadata.commandType.asTags().let {
                tags.addAll(it)
            }
            return tags
        }
    override val requestBodyType: Class<*>
        get() = commandRouteMetadata.commandMetadata.commandType

    override val responseType: Class<*>
        get() = CommandResult::class.java

    override fun build(): RouteSpec {
        super.build()
        addParameter(CommandHeaders.WAIT_STAGE, ParameterIn.HEADER, CommandStageSchema.schemaRef) {
            it.example(CommandStageSchema.default)
        }
        addParameter(CommandHeaders.WAIT_CONTEXT, ParameterIn.HEADER, StringSchema())
        addParameter(CommandHeaders.WAIT_PROCESSOR, ParameterIn.HEADER, StringSchema())
        addParameter(CommandHeaders.WAIT_TIME_OUT, ParameterIn.HEADER, IntegerSchema()) {
            it.description("Unit: millisecond")
        }
        addParameter(CommandHeaders.AGGREGATE_ID, ParameterIn.HEADER, StringSchema())
        addParameter(CommandHeaders.AGGREGATE_VERSION, ParameterIn.HEADER, IntegerSchema())
        addParameter(CommandHeaders.REQUEST_ID, ParameterIn.HEADER, StringSchema())
        commandRouteMetadata.pathVariableMetadata.forEach { variableMetadata ->
            addParameter(variableMetadata.variableName, ParameterIn.PATH, StringSchema()) {
                it.required(variableMetadata.required)
            }
        }
        commandRouteMetadata.headerVariableMetadata.forEach { variableMetadata ->
            addParameter(variableMetadata.variableName, ParameterIn.HEADER, StringSchema()) {
                it.required(variableMetadata.required)
            }
        }
        return this
    }
}
