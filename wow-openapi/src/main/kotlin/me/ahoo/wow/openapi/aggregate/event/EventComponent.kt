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

package me.ahoo.wow.openapi.aggregate.event

import io.swagger.v3.oas.models.media.IntegerSchema
import io.swagger.v3.oas.models.parameters.RequestBody
import me.ahoo.wow.api.Wow
import me.ahoo.wow.messaging.compensation.CompensationTarget
import me.ahoo.wow.openapi.CommonComponent.Response.withErrorCodeHeader
import me.ahoo.wow.openapi.Https
import me.ahoo.wow.openapi.aggregate.event.EventComponent.Schema.compensationTargetSchema
import me.ahoo.wow.openapi.context.OpenAPIComponentContext

object EventComponent {

    const val COMPENSATION_TARGET_KEY = Wow.WOW_PREFIX + "CompensationTarget"

    object Schema {
        fun OpenAPIComponentContext.compensationTargetSchema(): io.swagger.v3.oas.models.media.Schema<*> {
            return schema(CompensationTarget::class.java)
        }
    }

    object Request {
        fun OpenAPIComponentContext.compensationTargetRequestBody(): RequestBody {
            return requestBody(COMPENSATION_TARGET_KEY) {
                content(schema = compensationTargetSchema())
            }
        }
    }

    object Response {
        fun OpenAPIComponentContext.compensationTargetResponse(): io.swagger.v3.oas.models.responses.ApiResponse {
            return response(COMPENSATION_TARGET_KEY) {
                withErrorCodeHeader(this@compensationTargetResponse)
                description("Number of event streams compensated")
                content(Https.MediaType.APPLICATION_JSON, schema = IntegerSchema())
            }
        }
    }
}
