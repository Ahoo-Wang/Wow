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
import me.ahoo.wow.messaging.compensation.CompensationConfig
import me.ahoo.wow.openapi.ComponentRef.Companion.createComponents
import me.ahoo.wow.openapi.HeaderRef.Companion.ERROR_CODE_HEADER
import me.ahoo.wow.openapi.HeaderRef.Companion.with
import me.ahoo.wow.openapi.ParameterRef.Companion.with
import me.ahoo.wow.openapi.RequestBodyRef.Companion.with
import me.ahoo.wow.openapi.ResponseRef.Companion.with
import me.ahoo.wow.openapi.ResponseRef.Companion.withBadRequest
import me.ahoo.wow.openapi.ResponseRef.Companion.withNotFound
import me.ahoo.wow.openapi.ResponseRef.Companion.withRequestTimeout
import me.ahoo.wow.openapi.ResponseRef.Companion.withTooManyRequests
import me.ahoo.wow.openapi.RoutePaths.BATCH_CURSOR_ID_PARAMETER
import me.ahoo.wow.openapi.RoutePaths.BATCH_LIMIT_PARAMETER
import me.ahoo.wow.openapi.RoutePaths.COMPENSATE_HEAD_VERSION
import me.ahoo.wow.openapi.RoutePaths.COMPENSATE_TAIL_VERSION
import me.ahoo.wow.openapi.SchemaRef.Companion.asSchemas
import me.ahoo.wow.openapi.event.EventCompensateRouteSpecFactory.Companion.COMPENSATION_CONFIG_REQUEST
import me.ahoo.wow.openapi.event.LoadEventStreamRouteSpecFactory.Companion.DOMAIN_EVENT_STREAM_ARRAY_RESPONSE
import me.ahoo.wow.openapi.event.LoadEventStreamRouteSpecFactory.Companion.DOMAIN_EVENT_STREAM_SCHEMA

interface GlobalRouteSpecFactory : RouteSpecFactory {
    fun create(currentContext: NamedBoundedContext): List<RouteSpec>
}

class DefaultGlobalRouteSpecFactory : GlobalRouteSpecFactory {
    override val components: Components = createComponents()

    init {
        SchemaRef.ERROR_INFO.schemas.mergeSchemas()
        DOMAIN_EVENT_STREAM_SCHEMA.schemas.mergeSchemas()
        BatchResult::class.java.asSchemas().mergeSchemas()
        CompensationConfig::class.java.asSchemas().mergeSchemas()
        components.headers.with(ERROR_CODE_HEADER)
        components.parameters
            .with(COMPENSATE_HEAD_VERSION)
            .with(COMPENSATE_TAIL_VERSION)
            .with(BATCH_CURSOR_ID_PARAMETER)
            .with(BATCH_LIMIT_PARAMETER)
        components.requestBodies
            .with(COMPENSATION_CONFIG_REQUEST)
        components.responses
            .withBadRequest()
            .withNotFound()
            .withRequestTimeout()
            .withTooManyRequests()
            .with(BatchRouteSpecFactory.BATCH_RESULT_RESPONSE)
            .with(DOMAIN_EVENT_STREAM_ARRAY_RESPONSE)
    }

    override fun create(currentContext: NamedBoundedContext): List<RouteSpec> {
        return listOf()
    }
}
