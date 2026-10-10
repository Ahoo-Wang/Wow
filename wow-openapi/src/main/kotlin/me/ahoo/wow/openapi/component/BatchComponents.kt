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
import io.swagger.v3.oas.models.media.StringSchema
import me.ahoo.wow.api.Wow
import me.ahoo.wow.eventsourcing.AggregateIdScanner
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.openapi.Https
import me.ahoo.wow.openapi.component.CommonComponents.withErrorCodeHeader
import me.ahoo.wow.openapi.contract.HttpComponent
import me.ahoo.wow.openapi.contract.HttpResponse
import me.ahoo.wow.rest.BatchResult
import me.ahoo.wow.rest.RouteVariables

/** The components of the batch routes: their path parameters and the batch result. */
internal object BatchComponents {
    val headVersionPathParameter = pathParameter(RouteVariables.HEAD_VERSION) {
        schema = IntegerSchema().description("The head version of the aggregate.")
            .example(EventStore.DEFAULT_HEAD_VERSION)
    }

    val tailVersionPathParameter = pathParameter(RouteVariables.TAIL_VERSION) {
        schema = IntegerSchema().description("The tail version of the aggregate.")
            .example(EventStore.DEFAULT_TAIL_VERSION)
    }

    val batchAfterIdPathParameter = pathParameter(RouteVariables.BATCH_AFTER_ID) {
        schema = StringSchema().description("The ID of the last record in the batch.")
            .example(AggregateIdScanner.FIRST_ID)
    }

    val batchLimitPathParameter = pathParameter(RouteVariables.BATCH_LIMIT) {
        schema = IntegerSchema().description("The size of batch.").example(EventStore.DEFAULT_TAIL_VERSION)
    }

    val batchResultResponse = HttpResponse(
        statusCode = Https.Code.OK,
        component = HttpComponent.response("${Wow.WOW_PREFIX}BatchResult") { context ->
            description("Batch Result")
            withErrorCodeHeader(context)
            content(schema = context.schema(BatchResult::class.java).description("Batch Result"))
        }
    )
}
