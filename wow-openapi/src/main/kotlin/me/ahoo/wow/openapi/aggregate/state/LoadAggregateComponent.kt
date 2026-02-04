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

package me.ahoo.wow.openapi.aggregate.state

import io.swagger.v3.oas.models.responses.ApiResponses
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import me.ahoo.wow.openapi.ApiResponseBuilder
import me.ahoo.wow.openapi.CommonComponent.Header
import me.ahoo.wow.openapi.CommonComponent.Header.errorCodeHeader
import me.ahoo.wow.openapi.CommonComponent.Response.badRequestResponse
import me.ahoo.wow.openapi.CommonComponent.Response.notFoundResponse
import me.ahoo.wow.openapi.Https
import me.ahoo.wow.openapi.context.OpenAPIComponentContext

object LoadAggregateComponent {
    object Response {
        fun OpenAPIComponentContext.loadAggregateResponses(
            summary: String,
            aggregateMetadata: AggregateMetadata<*, *>
        ): ApiResponses = ApiResponses().apply {
            ApiResponseBuilder()
                .description(summary)
                .header(Header.ERROR_CODE, errorCodeHeader())
                .content(schema = schema(aggregateMetadata.state.aggregateType))
                .build()
                .let {
                    addApiResponse(Https.Code.OK, it)
                }
            addApiResponse(Https.Code.BAD_REQUEST, badRequestResponse())
            addApiResponse(Https.Code.NOT_FOUND, notFoundResponse())
        }
    }
}
