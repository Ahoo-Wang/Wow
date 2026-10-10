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
import io.swagger.v3.oas.models.responses.ApiResponse
import io.swagger.v3.oas.models.responses.ApiResponses
import me.ahoo.wow.api.Wow
import me.ahoo.wow.command.CommandResult
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.exception.ErrorCodes
import me.ahoo.wow.openapi.CommonComponent.Header.errorCodeHeader
import me.ahoo.wow.openapi.Https
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Schema.commandResultSchema
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import me.ahoo.wow.rest.CommandHeaders
import me.ahoo.wow.rest.CommandHeaders.AGGREGATE_ID
import me.ahoo.wow.rest.CommandHeaders.AGGREGATE_VERSION
import me.ahoo.wow.rest.CommandHeaders.COMMAND_AGGREGATE_CONTEXT
import me.ahoo.wow.rest.CommandHeaders.COMMAND_AGGREGATE_NAME
import me.ahoo.wow.rest.CommandHeaders.COMMAND_TYPE
import me.ahoo.wow.rest.CommandHeaders.LOCAL_FIRST
import me.ahoo.wow.rest.CommandHeaders.OWNER_ID
import me.ahoo.wow.rest.CommandHeaders.REQUEST_ID
import me.ahoo.wow.rest.CommandHeaders.TENANT_ID
import me.ahoo.wow.rest.CommandHeaders.WAIT_CONTEXT
import me.ahoo.wow.rest.CommandHeaders.WAIT_FUNCTION
import me.ahoo.wow.rest.CommandHeaders.WAIT_PROCESSOR
import me.ahoo.wow.rest.CommandHeaders.WAIT_STAGE
import me.ahoo.wow.rest.CommandHeaders.WAIT_TAIL_CONTEXT
import me.ahoo.wow.rest.CommandHeaders.WAIT_TAIL_FUNCTION
import me.ahoo.wow.rest.CommandHeaders.WAIT_TAIL_PROCESSOR
import me.ahoo.wow.rest.CommandHeaders.WAIT_TAIL_STAGE
import me.ahoo.wow.rest.CommandHeaders.WAIT_TIME_OUT
import me.ahoo.wow.rest.WowHeaders

object CommandComponent {
    object Header {
        @Deprecated(
            "Scheduled for removal in 10.0.0. Use CommandHeaders.COMMAND_HEADERS_PREFIX.",
            ReplaceWith("CommandHeaders.COMMAND_HEADERS_PREFIX", "me.ahoo.wow.rest.CommandHeaders"),
        )
        const val COMMAND_HEADERS_PREFIX = CommandHeaders.COMMAND_HEADERS_PREFIX

        @Deprecated(
            "Scheduled for removal in 10.0.0. Use CommandHeaders.TENANT_ID.",
            ReplaceWith("CommandHeaders.TENANT_ID", "me.ahoo.wow.rest.CommandHeaders"),
        )
        const val TENANT_ID = CommandHeaders.TENANT_ID

        @Deprecated(
            "Scheduled for removal in 10.0.0. Use CommandHeaders.OWNER_ID.",
            ReplaceWith("CommandHeaders.OWNER_ID", "me.ahoo.wow.rest.CommandHeaders"),
        )
        const val OWNER_ID = CommandHeaders.OWNER_ID

        @Deprecated(
            "Scheduled for removal in 10.0.0. Use CommandHeaders.AGGREGATE_ID.",
            ReplaceWith("CommandHeaders.AGGREGATE_ID", "me.ahoo.wow.rest.CommandHeaders"),
        )
        const val AGGREGATE_ID = CommandHeaders.AGGREGATE_ID

        @Deprecated(
            "Scheduled for removal in 10.0.0. Use CommandHeaders.AGGREGATE_VERSION.",
            ReplaceWith("CommandHeaders.AGGREGATE_VERSION", "me.ahoo.wow.rest.CommandHeaders"),
        )
        const val AGGREGATE_VERSION = CommandHeaders.AGGREGATE_VERSION

        @Deprecated(
            "Scheduled for removal in 10.0.0. Use CommandHeaders.WAIT_PREFIX.",
            ReplaceWith("CommandHeaders.WAIT_PREFIX", "me.ahoo.wow.rest.CommandHeaders"),
        )
        const val WAIT_PREFIX = CommandHeaders.WAIT_PREFIX

        @Deprecated(
            "Scheduled for removal in 10.0.0. Use CommandHeaders.WAIT_TIME_OUT.",
            ReplaceWith("CommandHeaders.WAIT_TIME_OUT", "me.ahoo.wow.rest.CommandHeaders"),
        )
        const val WAIT_TIME_OUT = CommandHeaders.WAIT_TIME_OUT

        @Deprecated(
            "Scheduled for removal in 10.0.0. Use CommandHeaders.LEGACY_WAIT_TIME_OUT.",
            ReplaceWith("CommandHeaders.LEGACY_WAIT_TIME_OUT", "me.ahoo.wow.rest.CommandHeaders"),
        )
        const val LEGACY_WAIT_TIME_OUT = CommandHeaders.LEGACY_WAIT_TIME_OUT

        @Deprecated(
            "Scheduled for removal in 10.0.0. Use CommandHeaders.WAIT_STAGE.",
            ReplaceWith("CommandHeaders.WAIT_STAGE", "me.ahoo.wow.rest.CommandHeaders"),
        )
        const val WAIT_STAGE = CommandHeaders.WAIT_STAGE

        @Deprecated(
            "Scheduled for removal in 10.0.0. Use CommandHeaders.WAIT_CONTEXT.",
            ReplaceWith("CommandHeaders.WAIT_CONTEXT", "me.ahoo.wow.rest.CommandHeaders"),
        )
        const val WAIT_CONTEXT = CommandHeaders.WAIT_CONTEXT

        @Deprecated(
            "Scheduled for removal in 10.0.0. Use CommandHeaders.WAIT_PROCESSOR.",
            ReplaceWith("CommandHeaders.WAIT_PROCESSOR", "me.ahoo.wow.rest.CommandHeaders"),
        )
        const val WAIT_PROCESSOR = CommandHeaders.WAIT_PROCESSOR

        @Deprecated(
            "Scheduled for removal in 10.0.0. Use CommandHeaders.WAIT_FUNCTION.",
            ReplaceWith("CommandHeaders.WAIT_FUNCTION", "me.ahoo.wow.rest.CommandHeaders"),
        )
        const val WAIT_FUNCTION = CommandHeaders.WAIT_FUNCTION

        @Deprecated(
            "Scheduled for removal in 10.0.0. Use CommandHeaders.WAIT_TAIL_PREFIX.",
            ReplaceWith("CommandHeaders.WAIT_TAIL_PREFIX", "me.ahoo.wow.rest.CommandHeaders"),
        )
        const val WAIT_TAIL_PREFIX = CommandHeaders.WAIT_TAIL_PREFIX

        @Deprecated(
            "Scheduled for removal in 10.0.0. Use CommandHeaders.WAIT_TAIL_STAGE.",
            ReplaceWith("CommandHeaders.WAIT_TAIL_STAGE", "me.ahoo.wow.rest.CommandHeaders"),
        )
        const val WAIT_TAIL_STAGE = CommandHeaders.WAIT_TAIL_STAGE

        @Deprecated(
            "Scheduled for removal in 10.0.0. Use CommandHeaders.WAIT_TAIL_CONTEXT.",
            ReplaceWith("CommandHeaders.WAIT_TAIL_CONTEXT", "me.ahoo.wow.rest.CommandHeaders"),
        )
        const val WAIT_TAIL_CONTEXT = CommandHeaders.WAIT_TAIL_CONTEXT

        @Deprecated(
            "Scheduled for removal in 10.0.0. Use CommandHeaders.WAIT_TAIL_PROCESSOR.",
            ReplaceWith("CommandHeaders.WAIT_TAIL_PROCESSOR", "me.ahoo.wow.rest.CommandHeaders"),
        )
        const val WAIT_TAIL_PROCESSOR = CommandHeaders.WAIT_TAIL_PROCESSOR

        @Deprecated(
            "Scheduled for removal in 10.0.0. Use CommandHeaders.WAIT_TAIL_FUNCTION.",
            ReplaceWith("CommandHeaders.WAIT_TAIL_FUNCTION", "me.ahoo.wow.rest.CommandHeaders"),
        )
        const val WAIT_TAIL_FUNCTION = CommandHeaders.WAIT_TAIL_FUNCTION

        @Deprecated(
            "Scheduled for removal in 10.0.0. Use CommandHeaders.REQUEST_ID.",
            ReplaceWith("CommandHeaders.REQUEST_ID", "me.ahoo.wow.rest.CommandHeaders"),
        )
        const val REQUEST_ID = CommandHeaders.REQUEST_ID

        @Deprecated(
            "Scheduled for removal in 10.0.0. Use CommandHeaders.LOCAL_FIRST.",
            ReplaceWith("CommandHeaders.LOCAL_FIRST", "me.ahoo.wow.rest.CommandHeaders"),
        )
        const val LOCAL_FIRST = CommandHeaders.LOCAL_FIRST

        @Deprecated(
            "Scheduled for removal in 10.0.0. Use CommandHeaders.COMMAND_AGGREGATE_CONTEXT.",
            ReplaceWith("CommandHeaders.COMMAND_AGGREGATE_CONTEXT", "me.ahoo.wow.rest.CommandHeaders"),
        )
        const val COMMAND_AGGREGATE_CONTEXT = CommandHeaders.COMMAND_AGGREGATE_CONTEXT

        @Deprecated(
            "Scheduled for removal in 10.0.0. Use CommandHeaders.COMMAND_AGGREGATE_NAME.",
            ReplaceWith("CommandHeaders.COMMAND_AGGREGATE_NAME", "me.ahoo.wow.rest.CommandHeaders"),
        )
        const val COMMAND_AGGREGATE_NAME = CommandHeaders.COMMAND_AGGREGATE_NAME

        @Deprecated(
            "Scheduled for removal in 10.0.0. Use CommandHeaders.COMMAND_TYPE.",
            ReplaceWith("CommandHeaders.COMMAND_TYPE", "me.ahoo.wow.rest.CommandHeaders"),
        )
        const val COMMAND_TYPE = CommandHeaders.COMMAND_TYPE

        @Deprecated(
            "Scheduled for removal in 10.0.0. Use CommandHeaders.COMMAND_HEADER_X_PREFIX.",
            ReplaceWith("CommandHeaders.COMMAND_HEADER_X_PREFIX", "me.ahoo.wow.rest.CommandHeaders"),
        )
        const val COMMAND_HEADER_X_PREFIX = CommandHeaders.COMMAND_HEADER_X_PREFIX
    }

    object Schema {
        fun OpenAPIComponentContext.commandResultSchema(): io.swagger.v3.oas.models.media.Schema<*> =
            schema(CommandResult::class.java)
    }

    object Parameter {
        fun OpenAPIComponentContext.acceptHeaderParameter(): io.swagger.v3.oas.models.parameters.Parameter =
            parameter {
                name = Https.Header.ACCEPT
                schema = StringSchema()
                    .addEnumItem(Https.MediaType.APPLICATION_JSON)
                    .addEnumItem(Https.MediaType.TEXT_EVENT_STREAM)
                    ._default(Https.MediaType.APPLICATION_JSON)
                `in`(ParameterIn.HEADER.toString())
            }

        fun OpenAPIComponentContext.tenantIdHeaderParameter(): io.swagger.v3.oas.models.parameters.Parameter =
            parameter {
                name = TENANT_ID
                schema = StringSchema()
                `in`(ParameterIn.HEADER.toString())
                description = "The tenant ID of the aggregate"
            }

        fun OpenAPIComponentContext.ownerIdHeaderParameter(): io.swagger.v3.oas.models.parameters.Parameter =
            parameter {
                name = OWNER_ID
                schema = StringSchema()
                `in`(ParameterIn.HEADER.toString())
                description = "The owner ID of the aggregate resource"
            }

        fun OpenAPIComponentContext.aggregateIdHeaderParameter(): io.swagger.v3.oas.models.parameters.Parameter =
            parameter {
                name = AGGREGATE_ID
                schema = StringSchema()
                `in`(ParameterIn.HEADER.toString())
            }

        fun OpenAPIComponentContext.aggregateVersionHeaderParameter(): io.swagger.v3.oas.models.parameters.Parameter =
            parameter {
                name = AGGREGATE_VERSION
                schema = IntegerSchema()
                `in`(ParameterIn.HEADER.toString())
                description = "The version of the target aggregate, which is used to control version conflicts"
            }

        fun OpenAPIComponentContext.requestIdHeaderParameter(): io.swagger.v3.oas.models.parameters.Parameter =
            parameter {
                name = REQUEST_ID
                schema = StringSchema()
                `in`(ParameterIn.HEADER.toString())
                description =
                    "The request ID of the command message, which is used to check the idempotency of the command message"
            }

        fun OpenAPIComponentContext.localFirstHeaderParameter(): io.swagger.v3.oas.models.parameters.Parameter =
            parameter {
                name = LOCAL_FIRST
                schema = BooleanSchema()
                `in`(ParameterIn.HEADER.toString())
                description =
                    "Whether to enable local priority mode, if false, it will be turned off, and the default is true."
            }

        fun OpenAPIComponentContext.waitTimeOutHeaderParameter(): io.swagger.v3.oas.models.parameters.Parameter =
            parameter {
                name = WAIT_TIME_OUT
                schema = IntegerSchema()
                `in`(ParameterIn.HEADER.toString())
                description = "Command timeout period. Milliseconds"
            }

        //region Wait Stage
        fun OpenAPIComponentContext.waitContextHeaderParameter(): io.swagger.v3.oas.models.parameters.Parameter =
            parameter {
                name = WAIT_CONTEXT
                schema = StringSchema()
                `in`(ParameterIn.HEADER.toString())
            }

        fun OpenAPIComponentContext.waitStageHeaderParameter(): io.swagger.v3.oas.models.parameters.Parameter =
            parameter {
                name = WAIT_STAGE
                schema = schema(CommandStage::class.java)
                `in`(ParameterIn.HEADER.toString())
            }

        fun OpenAPIComponentContext.waitProcessorHeaderParameter(): io.swagger.v3.oas.models.parameters.Parameter =
            parameter {
                name = WAIT_PROCESSOR
                schema = StringSchema()
                `in`(ParameterIn.HEADER.toString())
            }

        fun OpenAPIComponentContext.waitFunctionHeaderParameter(): io.swagger.v3.oas.models.parameters.Parameter =
            parameter {
                name = WAIT_FUNCTION
                schema = StringSchema()
                `in`(ParameterIn.HEADER.toString())
            }

        //endregion
        //region Wait Chain Tail
        fun OpenAPIComponentContext.waitTailContextHeaderParameter(): io.swagger.v3.oas.models.parameters.Parameter =
            parameter {
                name = WAIT_TAIL_CONTEXT
                schema = StringSchema()
                `in`(ParameterIn.HEADER.toString())
            }

        fun OpenAPIComponentContext.waitTailStageHeaderParameter(): io.swagger.v3.oas.models.parameters.Parameter =
            parameter {
                name = WAIT_TAIL_STAGE
                schema = schema(CommandStage::class.java)
                `in`(ParameterIn.HEADER.toString())
            }

        fun OpenAPIComponentContext.waitTailProcessorHeaderParameter(): io.swagger.v3.oas.models.parameters.Parameter =
            parameter {
                name = WAIT_TAIL_PROCESSOR
                schema = StringSchema()
                `in`(ParameterIn.HEADER.toString())
            }

        fun OpenAPIComponentContext.waitTailFunctionHeaderParameter(): io.swagger.v3.oas.models.parameters.Parameter =
            parameter {
                name = WAIT_TAIL_FUNCTION
                schema = StringSchema()
                `in`(ParameterIn.HEADER.toString())
            }

        //endregion

        fun OpenAPIComponentContext.commandCommonHeaderParameters(): List<io.swagger.v3.oas.models.parameters.Parameter> {
            return listOf(
                waitStageHeaderParameter(),
                waitContextHeaderParameter(),
                waitProcessorHeaderParameter(),
                waitFunctionHeaderParameter(),
                waitTimeOutHeaderParameter(),
                waitTailStageHeaderParameter(),
                waitTailContextHeaderParameter(),
                waitTailProcessorHeaderParameter(),
                waitTailFunctionHeaderParameter(),
                aggregateIdHeaderParameter(),
                aggregateVersionHeaderParameter(),
                requestIdHeaderParameter(),
                localFirstHeaderParameter(),
                acceptHeaderParameter()
            )
        }

        fun OpenAPIComponentContext.commandAggregateContextHeaderParameter(): io.swagger.v3.oas.models.parameters.Parameter =
            parameter {
                name = COMMAND_AGGREGATE_CONTEXT
                schema = StringSchema()
                `in`(ParameterIn.HEADER.toString())
                description = "The name of the context to which the command message belongs"
            }

        fun OpenAPIComponentContext.commandAggregateNameHeaderParameter(): io.swagger.v3.oas.models.parameters.Parameter =
            parameter {
                name = COMMAND_AGGREGATE_NAME
                schema = StringSchema()
                `in`(ParameterIn.HEADER.toString())
                description = "The name of the aggregate to which the command message belongs"
            }

        fun OpenAPIComponentContext.commandTypeHeaderParameter(): io.swagger.v3.oas.models.parameters.Parameter =
            parameter {
                name = COMMAND_TYPE
                schema = StringSchema()
                `in`(ParameterIn.HEADER.toString())
                description = "The fully qualified name of the command message body"
                required = true
            }
    }

    object Response {
        fun OpenAPIComponentContext.okCommandResponse(): ApiResponse =
            response("${Wow.WOW_PREFIX}Command${ErrorCodes.SUCCEEDED}") {
                val commandResultSchema = commandResultSchema()
                description(ErrorCodes.SUCCEEDED_MESSAGE)
                header(WowHeaders.ERROR_CODE, errorCodeHeader())
                content(schema = commandResultSchema)
                val textEventStreamSchema = io.swagger.v3.oas.models.media.Schema<Any>()
                    .addAnyOfItem(commandResultSchema)
                    .addAnyOfItem(
                        StringSchema()
                            .title("error")
                            .description("This value is returned when the task fails to be executed")
                    )
                content(mediaTypeName = Https.MediaType.TEXT_EVENT_STREAM, schema = textEventStreamSchema)
            }

        fun OpenAPIComponentContext.badRequestCommandResponse(): ApiResponse =
            response("${Wow.WOW_PREFIX}Command${ErrorCodes.BAD_REQUEST}") {
                description("Command Bad Request")
                header(WowHeaders.ERROR_CODE, errorCodeHeader())
                content(schema = commandResultSchema())
            }

        fun OpenAPIComponentContext.notFoundCommandResponse(): ApiResponse =
            response("${Wow.WOW_PREFIX}Command${ErrorCodes.NOT_FOUND}") {
                description("Aggregate Not Found")
                header(WowHeaders.ERROR_CODE, errorCodeHeader())
                content(schema = commandResultSchema())
            }

        fun OpenAPIComponentContext.requestTimeoutCommandResponse(): ApiResponse =
            response("${Wow.WOW_PREFIX}Command${ErrorCodes.REQUEST_TIMEOUT}") {
                description("Command Request Timeout")
                header(WowHeaders.ERROR_CODE, errorCodeHeader())
                content(schema = commandResultSchema())
            }

        fun OpenAPIComponentContext.tooManyRequestsCommandResponse(): ApiResponse =
            response("${Wow.WOW_PREFIX}Command${ErrorCodes.TOO_MANY_REQUESTS}") {
                description("Command Too Many Requests")
                header(WowHeaders.ERROR_CODE, errorCodeHeader())
                content(schema = commandResultSchema())
            }

        fun OpenAPIComponentContext.versionConflictCommandResponse(): ApiResponse =
            response("${Wow.WOW_PREFIX}CommandVersionConflict") {
                description("Command Version Conflict")
                header(WowHeaders.ERROR_CODE, errorCodeHeader())
                content(schema = commandResultSchema())
            }

        fun OpenAPIComponentContext.illegalAccessDeletedAggregateCommandResponse(): ApiResponse =
            response("${Wow.WOW_PREFIX}Command${ErrorCodes.ILLEGAL_ACCESS_DELETED_AGGREGATE}") {
                description("Illegal Access Deleted Aggregate")
                header(WowHeaders.ERROR_CODE, errorCodeHeader())
                content(schema = commandResultSchema())
            }

        fun OpenAPIComponentContext.commandResponses(): ApiResponses = ApiResponses().apply {
            addApiResponse(Https.Code.OK, okCommandResponse())
            addApiResponse(Https.Code.BAD_REQUEST, badRequestCommandResponse())
            addApiResponse(Https.Code.NOT_FOUND, notFoundCommandResponse())
            addApiResponse(Https.Code.CONFLICT, versionConflictCommandResponse())
            addApiResponse(Https.Code.TOO_MANY_REQUESTS, tooManyRequestsCommandResponse())
            addApiResponse(Https.Code.REQUEST_TIMEOUT, requestTimeoutCommandResponse())
            addApiResponse(Https.Code.GONE, illegalAccessDeletedAggregateCommandResponse())
        }
    }
}
