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

import io.swagger.v3.oas.models.media.IntegerSchema
import me.ahoo.wow.messaging.compensation.CompensationTarget
import me.ahoo.wow.openapi.Https
import me.ahoo.wow.openapi.aggregate.event.EventComponent.COMPENSATION_TARGET_KEY
import me.ahoo.wow.openapi.component.CommonComponents.withErrorCodeHeader
import me.ahoo.wow.openapi.contract.HttpComponent
import me.ahoo.wow.openapi.contract.HttpRequestBody
import me.ahoo.wow.openapi.contract.HttpResponse

/** The components of the event compensation route. */
internal object EventComponents {
    val compensationTargetRequestBody = HttpRequestBody(
        component = HttpComponent.requestBody(COMPENSATION_TARGET_KEY) { context ->
            content(schema = context.schema(CompensationTarget::class.java))
        }
    )

    val compensationTargetResponse = HttpResponse(
        statusCode = Https.Code.OK,
        component = HttpComponent.response(COMPENSATION_TARGET_KEY) { context ->
            withErrorCodeHeader(context)
            description("Number of event streams compensated")
            content(Https.MediaType.APPLICATION_JSON, schema = IntegerSchema())
        }
    )
}
