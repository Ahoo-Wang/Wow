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

package me.ahoo.wow.openapi.contributor.global

import me.ahoo.wow.api.Wow
import me.ahoo.wow.exception.ErrorCodes
import me.ahoo.wow.openapi.CommonComponent.Header.SPACE_ID
import me.ahoo.wow.openapi.CommonComponent.Parameter.spaceIdHeaderParameter
import me.ahoo.wow.openapi.Https
import me.ahoo.wow.openapi.RouteIdSpec
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Header.AGGREGATE_ID
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Header.AGGREGATE_VERSION
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Header.COMMAND_AGGREGATE_CONTEXT
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Header.COMMAND_AGGREGATE_NAME
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Header.COMMAND_TYPE
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Header.LOCAL_FIRST
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Header.OWNER_ID
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Header.REQUEST_ID
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Header.TENANT_ID
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Header.WAIT_CONTEXT
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Header.WAIT_FUNCTION
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Header.WAIT_PROCESSOR
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Header.WAIT_STAGE
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Header.WAIT_TAIL_CONTEXT
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Header.WAIT_TAIL_FUNCTION
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Header.WAIT_TAIL_PROCESSOR
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Header.WAIT_TAIL_STAGE
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Header.WAIT_TIME_OUT
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Parameter.commandAggregateContextHeaderParameter
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Parameter.commandAggregateNameHeaderParameter
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Parameter.commandCommonHeaderParameters
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Parameter.commandTypeHeaderParameter
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Parameter.ownerIdHeaderParameter
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Parameter.tenantIdHeaderParameter
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Response.commandResponses
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import me.ahoo.wow.openapi.contract.HttpParameter
import me.ahoo.wow.openapi.contract.HttpParameterLocation
import me.ahoo.wow.openapi.contract.HttpResponse
import me.ahoo.wow.openapi.contract.HttpTag

internal fun wowRouteId(resourceName: String, operation: String): String {
    return RouteIdSpec()
        .prefix(Wow.WOW)
        .resourceName(resourceName)
        .operation(operation)
        .build()
}

internal fun wowTags(): List<HttpTag> {
    return listOf(HttpTag(Wow.WOW, "Wow framework internal interface"))
}

internal fun componentHeaderParameter(name: String): HttpParameter {
    return HttpParameter(
        name = name,
        location = HttpParameterLocation.HEADER,
        componentRef = "${Wow.WOW_PREFIX}$name"
    )
}

internal fun OpenAPIComponentContext.commandFacadeParameters(): List<HttpParameter> {
    commandTypeHeaderParameter()
    commandCommonHeaderParameters()
    tenantIdHeaderParameter()
    spaceIdHeaderParameter()
    ownerIdHeaderParameter()
    commandAggregateContextHeaderParameter()
    commandAggregateNameHeaderParameter()
    return listOf(
        COMMAND_TYPE,
        WAIT_STAGE,
        WAIT_CONTEXT,
        WAIT_PROCESSOR,
        WAIT_FUNCTION,
        WAIT_TIME_OUT,
        WAIT_TAIL_STAGE,
        WAIT_TAIL_CONTEXT,
        WAIT_TAIL_PROCESSOR,
        WAIT_TAIL_FUNCTION,
        AGGREGATE_ID,
        AGGREGATE_VERSION,
        REQUEST_ID,
        LOCAL_FIRST,
        Https.Header.ACCEPT,
        TENANT_ID,
        SPACE_ID,
        OWNER_ID,
        COMMAND_AGGREGATE_CONTEXT,
        COMMAND_AGGREGATE_NAME
    ).map(::componentHeaderParameter)
}

internal fun OpenAPIComponentContext.commandResponseRefs(): List<HttpResponse> {
    commandResponses()
    return listOf(
        HttpResponse(Https.Code.OK, componentRef = "${Wow.WOW_PREFIX}Command${ErrorCodes.SUCCEEDED}"),
        HttpResponse(Https.Code.BAD_REQUEST, componentRef = "${Wow.WOW_PREFIX}Command${ErrorCodes.BAD_REQUEST}"),
        HttpResponse(Https.Code.NOT_FOUND, componentRef = "${Wow.WOW_PREFIX}Command${ErrorCodes.NOT_FOUND}"),
        HttpResponse(Https.Code.CONFLICT, componentRef = "${Wow.WOW_PREFIX}CommandVersionConflict"),
        HttpResponse(
            Https.Code.TOO_MANY_REQUESTS,
            componentRef = "${Wow.WOW_PREFIX}Command${ErrorCodes.TOO_MANY_REQUESTS}"
        ),
        HttpResponse(
            Https.Code.REQUEST_TIMEOUT,
            componentRef = "${Wow.WOW_PREFIX}Command${ErrorCodes.REQUEST_TIMEOUT}"
        ),
        HttpResponse(
            Https.Code.GONE,
            componentRef = "${Wow.WOW_PREFIX}Command${ErrorCodes.ILLEGAL_ACCESS_DELETED_AGGREGATE}"
        )
    )
}
