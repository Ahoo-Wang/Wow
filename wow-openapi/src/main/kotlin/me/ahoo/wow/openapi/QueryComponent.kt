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

import me.ahoo.wow.api.Wow
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.openapi.CommonComponent.Response.withErrorCodeHeader
import me.ahoo.wow.openapi.QueryComponent.Schema.conditionSchema
import me.ahoo.wow.openapi.QueryComponent.Schema.listQuerySchema
import me.ahoo.wow.openapi.QueryComponent.Schema.pagedQuerySchema
import me.ahoo.wow.openapi.QueryComponent.Schema.singleQuerySchema
import me.ahoo.wow.openapi.context.OpenAPIComponentContext

object QueryComponent {

    const val SINGLE_QUERY_KEY = Wow.WOW_PREFIX + "SingleQuery"
    const val COUNT_QUERY_KEY = Wow.WOW_PREFIX + "CountQuery"
    const val LIST_QUERY_KEY = Wow.WOW_PREFIX + "ListQuery"
    const val PAGED_QUERY_KEY = Wow.WOW_PREFIX + "PagedQuery"

    const val PAGED_LIST_EVENT_STREAM_RESPONSE_KEY = Wow.WOW_PREFIX + "PagedListEventStream"
    const val LOAD_EVENT_STREAM_RESPONSE_KEY = Wow.WOW_PREFIX + "LoadEventStream"
    object Schema {
        fun OpenAPIComponentContext.singleQuerySchema(): io.swagger.v3.oas.models.media.Schema<*> {
            return schema(SingleQuery::class.java)
        }

        fun OpenAPIComponentContext.conditionSchema(): io.swagger.v3.oas.models.media.Schema<*> {
            return schema(Condition::class.java)
        }

        fun OpenAPIComponentContext.listQuerySchema(): io.swagger.v3.oas.models.media.Schema<*> {
            return schema(ListQuery::class.java)
        }

        fun OpenAPIComponentContext.pagedQuerySchema(): io.swagger.v3.oas.models.media.Schema<*> {
            return schema(PagedQuery::class.java)
        }
    }

    object RequestBody {
        fun OpenAPIComponentContext.singleQueryRequestBody(): io.swagger.v3.oas.models.parameters.RequestBody {
            return requestBody(SINGLE_QUERY_KEY) {
                content(schema = singleQuerySchema())
            }
        }

        fun OpenAPIComponentContext.countQueryRequestBody(): io.swagger.v3.oas.models.parameters.RequestBody {
            return requestBody(COUNT_QUERY_KEY) {
                content(schema = conditionSchema())
            }
        }

        fun OpenAPIComponentContext.listQueryRequestBody(): io.swagger.v3.oas.models.parameters.RequestBody {
            return requestBody(LIST_QUERY_KEY) {
                content(schema = listQuerySchema())
            }
        }

        fun OpenAPIComponentContext.pagedQueryRequestBody(): io.swagger.v3.oas.models.parameters.RequestBody {
            return requestBody(PAGED_QUERY_KEY) {
                content(schema = pagedQuerySchema())
            }
        }
    }

    object Response {

        fun OpenAPIComponentContext.countQueryResponse(): io.swagger.v3.oas.models.responses.ApiResponse {
            return response(COUNT_QUERY_KEY) {
                withErrorCodeHeader(this@countQueryResponse)
                content(Https.MediaType.TEXT_PLAIN, schema = schema(Long::class.java))
            }
        }

        fun OpenAPIComponentContext.pagedListEventStreamResponse(): io.swagger.v3.oas.models.responses.ApiResponse {
            return response(PAGED_LIST_EVENT_STREAM_RESPONSE_KEY) {
                withErrorCodeHeader(this@pagedListEventStreamResponse)
                content(schema = schema(PagedList::class.java, DomainEventStream::class.java))
            }
        }

        fun OpenAPIComponentContext.loadEventStreamResponse(): io.swagger.v3.oas.models.responses.ApiResponse {
            return response(LOAD_EVENT_STREAM_RESPONSE_KEY) {
                withErrorCodeHeader(this@loadEventStreamResponse)
                content(schema = arraySchema(DomainEventStream::class.java))
            }
        }
    }
}
