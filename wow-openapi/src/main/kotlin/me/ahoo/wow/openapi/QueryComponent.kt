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
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.api.query.Pagination
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.modeling.metadata.AggregateMetadata
import me.ahoo.wow.modeling.toStringWithAlias
import me.ahoo.wow.openapi.CommonComponent.Response.withErrorCodeHeader
import me.ahoo.wow.openapi.QueryComponent.Schema.filterSchema
import me.ahoo.wow.openapi.QueryComponent.Schema.listQuerySchema
import me.ahoo.wow.openapi.QueryComponent.Schema.pagedQuerySchema
import me.ahoo.wow.openapi.QueryComponent.Schema.singleQuerySchema
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import me.ahoo.wow.schema.typed.AggregatedDomainEventStream

object QueryComponent {
    const val SINGLE_QUERY_SUFFIX = ".SingleQuery"
    const val COUNT_QUERY_SUFFIX = ".CountQuery"
    const val LIST_QUERY_SUFFIX = ".ListQuery"
    const val PAGED_QUERY_SUFFIX = ".PagedQuery"
    const val COUNT_QUERY_KEY = Wow.WOW + COUNT_QUERY_SUFFIX
    const val LIST_QUERY_KEY = Wow.WOW + LIST_QUERY_SUFFIX
    const val PAGED_QUERY_KEY = Wow.WOW + PAGED_QUERY_SUFFIX

    object Schema {

        fun OpenAPIComponentContext.filterSchema(): io.swagger.v3.oas.models.media.Schema<*> {
            return schema(FilterExpression::class.java)
        }

        fun OpenAPIComponentContext.singleQuerySchema(): io.swagger.v3.oas.models.media.Schema<*> {
            return baseQuerySchema()
        }

        fun OpenAPIComponentContext.listQuerySchema(): io.swagger.v3.oas.models.media.Schema<*> {
            val querySchema = baseQuerySchema()
            val limitSchema = io.swagger.v3.oas.models.media.IntegerSchema()
            limitSchema.minimum(java.math.BigDecimal.ZERO)
            limitSchema.setDefault(0)
            querySchema.addProperty("limit", limitSchema.asAnySchema())
            return querySchema
        }

        fun OpenAPIComponentContext.pagedQuerySchema(): io.swagger.v3.oas.models.media.Schema<*> {
            val querySchema = baseQuerySchema()
            querySchema.addProperty("pagination", schema(Pagination::class.java).asAnySchema())
            return querySchema
        }

        private fun OpenAPIComponentContext.baseQuerySchema(): io.swagger.v3.oas.models.media.ObjectSchema {
            val querySchema = io.swagger.v3.oas.models.media.ObjectSchema()
            querySchema.addProperty("filter", filterSchema().asAnySchema())
            querySchema.addProperty("projection", schema(Projection::class.java).asAnySchema())
            val sortSchema = io.swagger.v3.oas.models.media.ArraySchema().items(schema(Sort::class.java))
            querySchema.addProperty("sort", sortSchema.asAnySchema())
            querySchema.required(listOf("filter"))
            querySchema.additionalProperties(false)
            return querySchema
        }

        @Suppress("UNCHECKED_CAST")
        private fun io.swagger.v3.oas.models.media.Schema<*>.asAnySchema():
            io.swagger.v3.oas.models.media.Schema<Any> = this as io.swagger.v3.oas.models.media.Schema<Any>
    }

    object RequestBody {

        fun OpenAPIComponentContext.aggregatedSingleQueryRequestBody(aggregateMetadata: AggregateMetadata<*, *>): io.swagger.v3.oas.models.parameters.RequestBody {
            return requestBody(aggregateMetadata.toStringWithAlias() + SINGLE_QUERY_SUFFIX) {
                content(schema = singleQuerySchema())
            }
        }

        fun OpenAPIComponentContext.countQueryRequestBody(): io.swagger.v3.oas.models.parameters.RequestBody {
            return requestBody(COUNT_QUERY_KEY) {
                content(schema = filterSchema())
            }
        }

        fun OpenAPIComponentContext.aggregatedCountQueryRequestBody(aggregateMetadata: AggregateMetadata<*, *>): io.swagger.v3.oas.models.parameters.RequestBody {
            return requestBody(aggregateMetadata.toStringWithAlias() + COUNT_QUERY_SUFFIX) {
                content(schema = filterSchema())
            }
        }

        fun OpenAPIComponentContext.listQueryRequestBody(): io.swagger.v3.oas.models.parameters.RequestBody {
            return requestBody(LIST_QUERY_KEY) {
                content(schema = listQuerySchema())
            }
        }

        fun OpenAPIComponentContext.aggregatedListQueryRequestBody(
            aggregateMetadata: AggregateMetadata<*, *>
        ): io.swagger.v3.oas.models.parameters.RequestBody {
            return requestBody(aggregateMetadata.toStringWithAlias() + LIST_QUERY_SUFFIX) {
                content(schema = listQuerySchema())
            }
        }

        fun OpenAPIComponentContext.pagedQueryRequestBody(): io.swagger.v3.oas.models.parameters.RequestBody {
            return requestBody(PAGED_QUERY_KEY) {
                content(schema = pagedQuerySchema())
            }
        }

        fun OpenAPIComponentContext.aggregatedPagedQueryRequestBody(
            aggregateMetadata: AggregateMetadata<*, *>
        ): io.swagger.v3.oas.models.parameters.RequestBody {
            return requestBody(aggregateMetadata.toStringWithAlias() + PAGED_QUERY_SUFFIX) {
                content(schema = pagedQuerySchema())
            }
        }
    }

    object Response {

        fun OpenAPIComponentContext.countQueryResponse(): io.swagger.v3.oas.models.responses.ApiResponse {
            return response(COUNT_QUERY_KEY) {
                withErrorCodeHeader(this@countQueryResponse)
                content(Https.MediaType.APPLICATION_JSON, schema = schema(Long::class.java))
            }
        }

        fun OpenAPIComponentContext.pagedListEventStreamResponse(aggregateMetadata: AggregateMetadata<*, *>): io.swagger.v3.oas.models.responses.ApiResponse {
            return ApiResponseBuilder().withErrorCodeHeader(this)
                .content(
                    schema = schema(
                        PagedList::class.java,
                        resolveType(AggregatedDomainEventStream::class.java, aggregateMetadata.command.aggregateType)
                    )
                ).build()
        }

        fun OpenAPIComponentContext.loadEventStreamResponse(aggregateMetadata: AggregateMetadata<*, *>): io.swagger.v3.oas.models.responses.ApiResponse {
            return ApiResponseBuilder().withErrorCodeHeader(this)
                .listContent(
                    this,
                    AggregatedDomainEventStream::class.java,
                    aggregateMetadata.command.aggregateType
                ).build()
        }
    }
}
