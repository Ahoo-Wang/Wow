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
import io.swagger.v3.oas.models.parameters.Parameter
import me.ahoo.wow.api.Wow
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.openapi.context.OpenAPIComponentContext

object CommandRequestHeaders {

    const val COMMAND_HEADERS_PREFIX = "Command-"
    const val WAIT_CONTEXT = "${COMMAND_HEADERS_PREFIX}Wait-Context"
    const val TENANT_ID = "${COMMAND_HEADERS_PREFIX}Tenant-Id"
    const val OWNER_ID = "${COMMAND_HEADERS_PREFIX}Owner-Id"
    const val AGGREGATE_ID = "${COMMAND_HEADERS_PREFIX}Aggregate-Id"
    const val AGGREGATE_VERSION = "${COMMAND_HEADERS_PREFIX}Aggregate-Version"
    const val WAIT_STAGE = "${COMMAND_HEADERS_PREFIX}Wait-Stage"
    const val WAIT_TIME_OUT = "${COMMAND_HEADERS_PREFIX}Wait-Timout"

    const val WAIT_PROCESSOR = "${COMMAND_HEADERS_PREFIX}Wait-Processor"
    const val REQUEST_ID = "${COMMAND_HEADERS_PREFIX}Request-Id"
    const val LOCAL_FIRST = "${COMMAND_HEADERS_PREFIX}Local-First"

    const val COMMAND_AGGREGATE_CONTEXT = "${COMMAND_HEADERS_PREFIX}Aggregate-Context"
    const val COMMAND_AGGREGATE_NAME = "${COMMAND_HEADERS_PREFIX}Aggregate-Name"
    const val COMMAND_TYPE = "${COMMAND_HEADERS_PREFIX}Type"

    const val COMMAND_HEADER_X_PREFIX = "${COMMAND_HEADERS_PREFIX}Header-"
    const val WOW_ERROR_CODE = "Wow-Error-Code"

    fun OpenAPIComponentContext.waitContext(): Parameter = parameter("${Wow.WOW_PREFIX}.$WAIT_CONTEXT") {
        name = WAIT_CONTEXT
        schema = schema(String::class.java)
        `in`(ParameterIn.HEADER.toString())
    }

    fun OpenAPIComponentContext.tenantId(): Parameter = parameter("${Wow.WOW_PREFIX}.$TENANT_ID") {
        name = TENANT_ID
        schema = schema(String::class.java)
        `in`(ParameterIn.HEADER.toString())
    }

    fun OpenAPIComponentContext.ownerId(): Parameter = parameter("${Wow.WOW_PREFIX}.$OWNER_ID") {
        name = OWNER_ID
        schema = schema(String::class.java)
        `in`(ParameterIn.HEADER.toString())
    }

    fun OpenAPIComponentContext.aggregateId(): Parameter = parameter("${Wow.WOW_PREFIX}.$AGGREGATE_ID") {
        name = AGGREGATE_ID
        schema = schema(String::class.java)
        `in`(ParameterIn.HEADER.toString())
    }

    fun OpenAPIComponentContext.aggregateVersion(): Parameter = parameter("${Wow.WOW_PREFIX}.$AGGREGATE_VERSION") {
        name = AGGREGATE_VERSION
        schema = schema(Int::class.java)
        `in`(ParameterIn.HEADER.toString())
    }

    fun OpenAPIComponentContext.waitStage(): Parameter = parameter("${Wow.WOW_PREFIX}.$WAIT_STAGE") {
        name = WAIT_STAGE
        schema = schema(CommandStage::class.java)
        `in`(ParameterIn.HEADER.toString())
    }

    fun OpenAPIComponentContext.waitProcessor(): Parameter = parameter("${Wow.WOW_PREFIX}.$WAIT_PROCESSOR") {
        name = WAIT_PROCESSOR
        schema = schema(String::class.java)
        `in`(ParameterIn.HEADER.toString())
    }

    fun OpenAPIComponentContext.waitTimeOut(): Parameter = parameter("${Wow.WOW_PREFIX}.$WAIT_TIME_OUT") {
        name = WAIT_TIME_OUT
        schema = schema(Int::class.java)
        `in`(ParameterIn.HEADER.toString())
        description = "Command timeout period. Milliseconds"
    }

    fun OpenAPIComponentContext.requestId(): Parameter = parameter("${Wow.WOW_PREFIX}.$REQUEST_ID") {
        name = REQUEST_ID
        schema = schema(String::class.java)
        `in`(ParameterIn.HEADER.toString())
    }

    fun OpenAPIComponentContext.localFirst(): Parameter = parameter("${Wow.WOW_PREFIX}.$LOCAL_FIRST") {
        name = LOCAL_FIRST
        schema = schema(Boolean::class.java)
        `in`(ParameterIn.HEADER.toString())
    }

    fun OpenAPIComponentContext.commandAggregateContext(): Parameter =
        parameter("${Wow.WOW_PREFIX}.$COMMAND_AGGREGATE_CONTEXT") {
            name = COMMAND_AGGREGATE_CONTEXT
            schema = schema(String::class.java)
            `in`(ParameterIn.HEADER.toString())
        }

    fun OpenAPIComponentContext.commandAggregateName(): Parameter =
        parameter("${Wow.WOW_PREFIX}.$COMMAND_AGGREGATE_NAME") {
            name = COMMAND_AGGREGATE_NAME
            schema = schema(String::class.java)
            `in`(ParameterIn.HEADER.toString())
        }

    fun OpenAPIComponentContext.commandType(): Parameter = parameter("${Wow.WOW_PREFIX}.$COMMAND_TYPE") {
        name = COMMAND_TYPE
        schema = schema(String::class.java)
        `in`(ParameterIn.HEADER.toString())
    }
}
