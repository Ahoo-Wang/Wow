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

package me.ahoo.wow.openapi.aggregate.command

import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.models.media.BooleanSchema
import io.swagger.v3.oas.models.media.IntegerSchema
import io.swagger.v3.oas.models.media.StringSchema
import io.swagger.v3.oas.models.parameters.Parameter
import me.ahoo.wow.api.Wow
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.openapi.ParameterRef
import me.ahoo.wow.openapi.SchemaRef.Companion.toSchemaRef

object CommandRequestParameters {
    val COMMAND_STAGE_SCHEMA = CommandStage::class.java.toSchemaRef(CommandStage.PROCESSED.name)
    val WAIT_STAGE_PARAMETER = Parameter()
        .name(CommandRequestHeaders.WAIT_STAGE)
        .`in`(ParameterIn.HEADER.toString())
        .schema(COMMAND_STAGE_SCHEMA.ref).let {
            ParameterRef("${Wow.WOW_PREFIX}${it.name}", it)
        }
    val WAIT_CONTEXT_PARAMETER = Parameter()
        .name(CommandRequestHeaders.WAIT_CONTEXT)
        .`in`(ParameterIn.HEADER.toString())
        .schema(StringSchema()).let {
            ParameterRef("${Wow.WOW_PREFIX}${it.name}", it)
        }
    val WAIT_PROCESSOR_PARAMETER = Parameter()
        .name(CommandRequestHeaders.WAIT_PROCESSOR)
        .`in`(ParameterIn.HEADER.toString())
        .schema(StringSchema()).let {
            ParameterRef("${Wow.WOW_PREFIX}${it.name}", it)
        }
    val WAIT_TIME_OUT_PARAMETER = Parameter()
        .name(CommandRequestHeaders.WAIT_TIME_OUT)
        .`in`(ParameterIn.HEADER.toString())
        .schema(IntegerSchema())
        .description("Unit: millisecond").let {
            ParameterRef("${Wow.WOW_PREFIX}${it.name}", it)
        }
    val TENANT_ID_PARAMETER = Parameter()
        .name(CommandRequestHeaders.TENANT_ID)
        .`in`(ParameterIn.HEADER.toString())
        .schema(StringSchema()).let {
            ParameterRef("${Wow.WOW_PREFIX}${it.name}", it)
        }
    val OWNER_ID_PARAMETER = Parameter()
        .name(CommandRequestHeaders.OWNER_ID)
        .`in`(ParameterIn.HEADER.toString())
        .description("Resource Owner Id")
        .schema(StringSchema()).let {
            ParameterRef("${Wow.WOW_PREFIX}${it.name}", it)
        }
    val AGGREGATE_ID_PARAMETER = Parameter()
        .name(CommandRequestHeaders.AGGREGATE_ID)
        .`in`(ParameterIn.HEADER.toString())
        .schema(StringSchema()).let {
            ParameterRef("${Wow.WOW_PREFIX}${it.name}", it)
        }
    val AGGREGATE_VERSION_PARAMETER = Parameter()
        .name(CommandRequestHeaders.AGGREGATE_VERSION)
        .`in`(ParameterIn.HEADER.toString())
        .schema(IntegerSchema()).let {
            ParameterRef("${Wow.WOW_PREFIX}${it.name}", it)
        }
    val REQUEST_ID_PARAMETER = Parameter()
        .name(CommandRequestHeaders.REQUEST_ID)
        .`in`(ParameterIn.HEADER.toString())
        .schema(StringSchema()).let {
            ParameterRef("${Wow.WOW_PREFIX}${it.name}", it)
        }
    val LOCAL_FIRST_PARAMETER = Parameter()
        .name(CommandRequestHeaders.LOCAL_FIRST)
        .`in`(ParameterIn.HEADER.toString())
        .required(false)
        .schema(BooleanSchema()).let {
            ParameterRef("${Wow.WOW_PREFIX}${it.name}", it)
        }

    // #region CommandFacadeRouteSpec
    val COMMAND_TYPE_PARAMETER = Parameter()
        .name(CommandRequestHeaders.COMMAND_TYPE)
        .required(true)
        .`in`(ParameterIn.HEADER.toString())
        .schema(StringSchema())
        .description("Command Body Class fully qualified name").let {
            ParameterRef("${Wow.WOW_PREFIX}${it.name}", it)
        }
    val COMMAND_AGGREGATE_CONTEXT_PARAMETER = Parameter()
        .name(CommandRequestHeaders.COMMAND_AGGREGATE_CONTEXT)
        .`in`(ParameterIn.HEADER.toString())
        .schema(StringSchema())
        .description("Command Aggregate Context").let {
            ParameterRef("${Wow.WOW_PREFIX}${it.name}", it)
        }
    val COMMAND_AGGREGATE_NAME_PARAMETER = Parameter()
        .name(CommandRequestHeaders.COMMAND_AGGREGATE_NAME)
        .`in`(ParameterIn.HEADER.toString())
        .schema(StringSchema())
        .description("Command Aggregate Name").let {
            ParameterRef("${Wow.WOW_PREFIX}${it.name}", it)
        }
    //endregion
}
