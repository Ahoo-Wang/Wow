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

import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.models.media.IntegerSchema
import io.swagger.v3.oas.models.media.StringSchema
import me.ahoo.wow.api.Wow
import me.ahoo.wow.eventsourcing.AggregateIdScanner
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.openapi.BatchComponent.Schema.batchResultSchema
import me.ahoo.wow.openapi.CommonComponent.Header
import me.ahoo.wow.openapi.CommonComponent.Header.errorCodeHeader
import me.ahoo.wow.openapi.context.OpenAPIComponentContext

object BatchComponent {
    object PathVariable {
        const val HEAD_VERSION = "headVersion"
        const val TAIL_VERSION = "tailVersion"

        const val BATCH_AFTER_ID = "afterId"
        const val BATCH_LIMIT = "limit"
    }

    object Schema {

        fun OpenAPIComponentContext.batchResultSchema(): io.swagger.v3.oas.models.media.Schema<*> =
            schema(BatchResult::class.java)
                .description("Batch Result")
    }

    object Parameter {
        fun OpenAPIComponentContext.headVersionPathParameter(): io.swagger.v3.oas.models.parameters.Parameter =
            parameter {
                name = PathVariable.HEAD_VERSION
                schema = IntegerSchema().description("The head version of the aggregate.")
                    .example(EventStore.DEFAULT_HEAD_VERSION)
                `in`(ParameterIn.PATH.toString())
            }

        fun OpenAPIComponentContext.tailVersionPathParameter(): io.swagger.v3.oas.models.parameters.Parameter =
            parameter {
                name = PathVariable.TAIL_VERSION
                schema = IntegerSchema().description("The tail version of the aggregate.").example(EventStore.DEFAULT_TAIL_VERSION)
                `in`(ParameterIn.PATH.toString())
            }

        fun OpenAPIComponentContext.batchAfterIdPathParameter(): io.swagger.v3.oas.models.parameters.Parameter =
            parameter {
                name = PathVariable.BATCH_AFTER_ID
                schema = StringSchema().description("The ID of the last record in the batch.")
                    .example(AggregateIdScanner.FIRST_ID)
                `in`(ParameterIn.PATH.toString())
            }

        fun OpenAPIComponentContext.batchLimitPathParameter(): io.swagger.v3.oas.models.parameters.Parameter =
            parameter {
                name = PathVariable.BATCH_LIMIT
                schema = IntegerSchema().description("The size of batch.").example(EventStore.DEFAULT_TAIL_VERSION)
                `in`(ParameterIn.PATH.toString())
            }
    }

    object Response {

        fun OpenAPIComponentContext.batchResultResponse(): io.swagger.v3.oas.models.responses.ApiResponse =
            response("${Wow.WOW_PREFIX}BatchResult") {
                description("Batch Result")
                header(Header.ERROR_CODE, errorCodeHeader())
                content(schema = batchResultSchema())
            }
    }
}
