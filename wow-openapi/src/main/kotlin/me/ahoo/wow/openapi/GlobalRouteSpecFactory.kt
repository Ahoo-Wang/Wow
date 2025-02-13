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

package me.ahoo.wow.openapi

import io.swagger.v3.oas.models.Components
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.command.CommandResult
import me.ahoo.wow.openapi.ComponentRef.Companion.createComponents
import me.ahoo.wow.openapi.HeaderRef.Companion.ERROR_CODE_HEADER
import me.ahoo.wow.openapi.HeaderRef.Companion.with
import me.ahoo.wow.openapi.ParameterRef.Companion.with
import me.ahoo.wow.openapi.ResponseRef.Companion.with
import me.ahoo.wow.openapi.ResponseRef.Companion.withBadRequest
import me.ahoo.wow.openapi.ResponseRef.Companion.withNotFound
import me.ahoo.wow.openapi.ResponseRef.Companion.withRequestTimeout
import me.ahoo.wow.openapi.ResponseRef.Companion.withTooManyRequests
import me.ahoo.wow.openapi.RoutePaths.BATCH_AFTER_ID_PARAMETER
import me.ahoo.wow.openapi.RoutePaths.BATCH_LIMIT_PARAMETER
import me.ahoo.wow.openapi.RoutePaths.HEAD_VERSION
import me.ahoo.wow.openapi.RoutePaths.TAIL_VERSION
import me.ahoo.wow.openapi.SchemaRef.Companion.toSchemaRef
import me.ahoo.wow.openapi.SchemaRef.Companion.toSchemas
import me.ahoo.wow.openapi.command.CommandRequestParameters.AGGREGATE_ID_PARAMETER
import me.ahoo.wow.openapi.command.CommandRequestParameters.AGGREGATE_VERSION_PARAMETER
import me.ahoo.wow.openapi.command.CommandRequestParameters.COMMAND_AGGREGATE_CONTEXT_PARAMETER
import me.ahoo.wow.openapi.command.CommandRequestParameters.COMMAND_AGGREGATE_NAME_PARAMETER
import me.ahoo.wow.openapi.command.CommandRequestParameters.COMMAND_STAGE_SCHEMA
import me.ahoo.wow.openapi.command.CommandRequestParameters.COMMAND_TYPE_PARAMETER
import me.ahoo.wow.openapi.command.CommandRequestParameters.LOCAL_FIRST_PARAMETER
import me.ahoo.wow.openapi.command.CommandRequestParameters.OWNER_ID_PARAMETER
import me.ahoo.wow.openapi.command.CommandRequestParameters.REQUEST_ID_PARAMETER
import me.ahoo.wow.openapi.command.CommandRequestParameters.TENANT_ID_PARAMETER
import me.ahoo.wow.openapi.command.CommandRequestParameters.WAIT_CONTEXT_PARAMETER
import me.ahoo.wow.openapi.command.CommandRequestParameters.WAIT_PROCESSOR_PARAMETER
import me.ahoo.wow.openapi.command.CommandRequestParameters.WAIT_STAGE_PARAMETER
import me.ahoo.wow.openapi.command.CommandRequestParameters.WAIT_TIME_OUT_PARAMETER
import me.ahoo.wow.openapi.command.CommandResponses.BAD_REQUEST_RESPONSE
import me.ahoo.wow.openapi.command.CommandResponses.COMMAND_RESULT_RESPONSE
import me.ahoo.wow.openapi.command.CommandResponses.ILLEGAL_ACCESS_DELETED_AGGREGATE_RESPONSE
import me.ahoo.wow.openapi.command.CommandResponses.NOT_FOUND_RESPONSE
import me.ahoo.wow.openapi.command.CommandResponses.REQUEST_TIMEOUT_RESPONSE
import me.ahoo.wow.openapi.command.CommandResponses.TOO_MANY_REQUESTS_RESPONSE
import me.ahoo.wow.openapi.command.CommandResponses.VERSION_CONFLICT_RESPONSE
import me.ahoo.wow.openapi.event.EventCompensateRouteSpecFactory.Companion.COMPENSATION_TARGET_SCHEMA
import me.ahoo.wow.openapi.event.LoadEventStreamRouteSpecFactory.Companion.DOMAIN_EVENT_STREAM_ARRAY_RESPONSE

interface GlobalRouteSpecFactory : RouteSpecFactory {
    fun create(currentContext: NamedBoundedContext): List<RouteSpec>
}

class DefaultGlobalRouteSpecFactory : GlobalRouteSpecFactory {
    override val components: Components = createComponents()

    init {
        SchemaRef.ERROR_INFO.schemas.mergeSchemas()
        BatchResult::class.java.toSchemas().mergeSchemas()
        COMPENSATION_TARGET_SCHEMA.schemas.mergeSchemas()
        SingleQuery::class.java.toSchemaRef().schemas.mergeSchemas()
        PagedQuery::class.java.toSchemaRef().schemas.mergeSchemas()
        ListQuery::class.java.toSchemaRef().schemas.mergeSchemas()
        Condition::class.java.toSchemaRef().schemas.mergeSchemas()
        components.headers.with(ERROR_CODE_HEADER)
        components.parameters
            .with(HEAD_VERSION)
            .with(TAIL_VERSION)
            .with(BATCH_AFTER_ID_PARAMETER)
            .with(BATCH_LIMIT_PARAMETER)
        components.responses
            .withBadRequest()
            .withNotFound()
            .withRequestTimeout()
            .withTooManyRequests()
            .with(BatchRouteSpecFactory.BATCH_RESULT_RESPONSE)
            .with(DOMAIN_EVENT_STREAM_ARRAY_RESPONSE)

        initCommandParams()
        initCommandResponses()
    }

    private fun initCommandParams() {
        COMMAND_STAGE_SCHEMA.schemas.mergeSchemas()
        CommandResult::class.java.toSchemas().mergeSchemas()
        components.parameters
            .with(WAIT_STAGE_PARAMETER)
            .with(WAIT_CONTEXT_PARAMETER)
            .with(WAIT_PROCESSOR_PARAMETER)
            .with(WAIT_TIME_OUT_PARAMETER)
            .with(TENANT_ID_PARAMETER)
            .with(OWNER_ID_PARAMETER)
            .with(AGGREGATE_ID_PARAMETER)
            .with(AGGREGATE_VERSION_PARAMETER)
            .with(REQUEST_ID_PARAMETER)
            .with(LOCAL_FIRST_PARAMETER)
            .with(COMMAND_TYPE_PARAMETER)
            .with(COMMAND_AGGREGATE_CONTEXT_PARAMETER)
            .with(COMMAND_AGGREGATE_NAME_PARAMETER)
    }

    private fun initCommandResponses() {
        components.responses
            .with(COMMAND_RESULT_RESPONSE)
            .with(BAD_REQUEST_RESPONSE)
            .with(NOT_FOUND_RESPONSE)
            .with(REQUEST_TIMEOUT_RESPONSE)
            .with(TOO_MANY_REQUESTS_RESPONSE)
            .with(VERSION_CONFLICT_RESPONSE)
            .with(ILLEGAL_ACCESS_DELETED_AGGREGATE_RESPONSE)
    }

    override fun create(currentContext: NamedBoundedContext): List<RouteSpec> {
        return listOf()
    }
}
