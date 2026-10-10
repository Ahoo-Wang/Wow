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

package me.ahoo.wow.openapi.component

import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.models.media.BooleanSchema
import io.swagger.v3.oas.models.media.IntegerSchema
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.media.StringSchema
import io.swagger.v3.oas.models.parameters.Parameter
import me.ahoo.wow.api.Wow
import me.ahoo.wow.command.CommandResult
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.exception.ErrorCodes
import me.ahoo.wow.openapi.Https
import me.ahoo.wow.openapi.component.CommonComponents.withErrorCodeHeader
import me.ahoo.wow.openapi.context.HttpComponentContext
import me.ahoo.wow.openapi.contract.HttpComponent
import me.ahoo.wow.openapi.contract.HttpParameter
import me.ahoo.wow.openapi.contract.HttpParameterLocation
import me.ahoo.wow.openapi.contract.HttpResponse
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

/** The components of the command routes: the command request headers and the command responses. */
internal object CommandComponents {
    private val acceptHeaderParameter = headerParameter(Https.Header.ACCEPT) {
        schema = StringSchema()
            .addEnumItem(Https.MediaType.APPLICATION_JSON)
            .addEnumItem(Https.MediaType.TEXT_EVENT_STREAM)
            ._default(Https.MediaType.APPLICATION_JSON)
    }

    private val tenantIdHeaderParameter = headerParameter(TENANT_ID) {
        schema = StringSchema()
        description = "The tenant ID of the aggregate"
    }

    private val ownerIdHeaderParameter = headerParameter(OWNER_ID) {
        schema = StringSchema()
        description = "The owner ID of the aggregate resource"
    }

    private val aggregateIdHeaderParameter = headerParameter(AGGREGATE_ID) {
        schema = StringSchema()
    }

    private val aggregateVersionHeaderParameter = headerParameter(AGGREGATE_VERSION) {
        schema = IntegerSchema()
        description = "The version of the target aggregate, which is used to control version conflicts"
    }

    private val requestIdHeaderParameter = headerParameter(REQUEST_ID) {
        schema = StringSchema()
        description =
            "The request ID of the command message, which is used to check the idempotency of the command message"
    }

    private val localFirstHeaderParameter = headerParameter(LOCAL_FIRST) {
        schema = BooleanSchema()
        description =
            "Whether to enable local priority mode, if false, it will be turned off, and the default is true."
    }

    private val waitTimeOutHeaderParameter = headerParameter(WAIT_TIME_OUT) {
        schema = IntegerSchema()
        description = "Command timeout period. Milliseconds"
    }

    private val waitStageHeaderParameter = headerParameter(WAIT_STAGE) { context ->
        schema = context.schema(CommandStage::class.java)
    }

    private val waitContextHeaderParameter = stringHeaderParameter(WAIT_CONTEXT)
    private val waitProcessorHeaderParameter = stringHeaderParameter(WAIT_PROCESSOR)
    private val waitFunctionHeaderParameter = stringHeaderParameter(WAIT_FUNCTION)

    private val waitTailStageHeaderParameter = headerParameter(WAIT_TAIL_STAGE) { context ->
        schema = context.schema(CommandStage::class.java)
    }

    private val waitTailContextHeaderParameter = stringHeaderParameter(WAIT_TAIL_CONTEXT)
    private val waitTailProcessorHeaderParameter = stringHeaderParameter(WAIT_TAIL_PROCESSOR)
    private val waitTailFunctionHeaderParameter = stringHeaderParameter(WAIT_TAIL_FUNCTION)

    private val commandAggregateContextHeaderParameter = headerParameter(COMMAND_AGGREGATE_CONTEXT) {
        schema = StringSchema()
        description = "The name of the context to which the command message belongs"
    }

    private val commandAggregateNameHeaderParameter = headerParameter(COMMAND_AGGREGATE_NAME) {
        schema = StringSchema()
        description = "The name of the aggregate to which the command message belongs"
    }

    private val commandTypeHeaderParameter = headerParameter(COMMAND_TYPE) {
        schema = StringSchema()
        description = "The fully qualified name of the command message body"
        required = true
    }

    /** The headers every command route accepts. */
    val commonHeaderParameters: List<HttpParameter> = listOf(
        waitStageHeaderParameter,
        waitContextHeaderParameter,
        waitProcessorHeaderParameter,
        waitFunctionHeaderParameter,
        waitTimeOutHeaderParameter,
        waitTailStageHeaderParameter,
        waitTailContextHeaderParameter,
        waitTailProcessorHeaderParameter,
        waitTailFunctionHeaderParameter,
        aggregateIdHeaderParameter,
        aggregateVersionHeaderParameter,
        requestIdHeaderParameter,
        localFirstHeaderParameter,
        acceptHeaderParameter
    )

    /** The headers of the command facade route, which names the command and its aggregate in headers. */
    val facadeParameters: List<HttpParameter> = listOf(commandTypeHeaderParameter) +
        commonHeaderParameters +
        listOf(
            tenantIdHeaderParameter,
            CommonComponents.spaceIdHeaderParameter,
            ownerIdHeaderParameter,
            commandAggregateContextHeaderParameter,
            commandAggregateNameHeaderParameter
        )

    val responses: List<HttpResponse> = listOf(
        commandResponse(Https.Code.OK, "Command${ErrorCodes.SUCCEEDED}", ErrorCodes.SUCCEEDED_MESSAGE) { result ->
            val textEventStreamSchema = Schema<Any>()
                .addAnyOfItem(result)
                .addAnyOfItem(
                    StringSchema()
                        .title("error")
                        .description("This value is returned when the task fails to be executed")
                )
            content(mediaTypeName = Https.MediaType.TEXT_EVENT_STREAM, schema = textEventStreamSchema)
        },
        commandResponse(Https.Code.BAD_REQUEST, "Command${ErrorCodes.BAD_REQUEST}", "Command Bad Request"),
        commandResponse(Https.Code.NOT_FOUND, "Command${ErrorCodes.NOT_FOUND}", "Aggregate Not Found"),
        commandResponse(Https.Code.CONFLICT, "CommandVersionConflict", "Command Version Conflict"),
        commandResponse(
            Https.Code.TOO_MANY_REQUESTS,
            "Command${ErrorCodes.TOO_MANY_REQUESTS}",
            "Command Too Many Requests"
        ),
        commandResponse(
            Https.Code.REQUEST_TIMEOUT,
            "Command${ErrorCodes.REQUEST_TIMEOUT}",
            "Command Request Timeout"
        ),
        commandResponse(
            Https.Code.GONE,
            "Command${ErrorCodes.ILLEGAL_ACCESS_DELETED_AGGREGATE}",
            "Illegal Access Deleted Aggregate"
        )
    )

    private fun commandResponse(
        statusCode: String,
        name: String,
        description: String,
        extraContent: me.ahoo.wow.openapi.ApiResponseBuilder.(Schema<*>) -> Unit = {}
    ): HttpResponse = HttpResponse(
        statusCode = statusCode,
        component = HttpComponent.response(Wow.WOW_PREFIX + name) { context ->
            val commandResultSchema = context.schema(CommandResult::class.java)
            description(description)
            withErrorCodeHeader(context)
            content(schema = commandResultSchema)
            extraContent(commandResultSchema)
        }
    )

    private fun stringHeaderParameter(name: String): HttpParameter = headerParameter(name) {
        schema = StringSchema()
    }

    /** A header parameter registered as the `wow.{name}` component. */
    private fun headerParameter(
        name: String,
        builder: Parameter.(HttpComponentContext) -> Unit
    ): HttpParameter = HttpParameter(
        name = name,
        location = HttpParameterLocation.HEADER,
        component = HttpComponent.parameter(Wow.WOW_PREFIX + name) { context ->
            this.name = name
            builder(context)
            `in`(ParameterIn.HEADER.toString())
        }
    )
}
