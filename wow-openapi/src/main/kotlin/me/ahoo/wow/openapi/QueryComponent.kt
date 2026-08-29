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

import io.swagger.v3.oas.models.media.StringSchema
import me.ahoo.wow.api.Wow
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.LogicalField
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.modeling.metadata.AggregateMetadata
import me.ahoo.wow.modeling.toStringWithAlias
import me.ahoo.wow.openapi.CommonComponent.Response.withErrorCodeHeader
import me.ahoo.wow.openapi.QueryComponent.Schema.aggregationQuerySchema
import me.ahoo.wow.openapi.QueryComponent.Schema.cursorQuerySchema
import me.ahoo.wow.openapi.QueryComponent.Schema.filterSchema
import me.ahoo.wow.openapi.QueryComponent.Schema.listQuerySchema
import me.ahoo.wow.openapi.QueryComponent.Schema.pagedQuerySchema
import me.ahoo.wow.openapi.QueryComponent.Schema.singleQuerySchema
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.query.schema.SystemQuerySchemaSource
import me.ahoo.wow.schema.query.JsonQuerySchemaSource
import me.ahoo.wow.schema.typed.AggregatedDomainEventStream

private val staticQuerySchemaSource = JsonQuerySchemaSource()

internal fun OpenAPIComponentContext.aggregatedFieldsSchema(
    aggregateMetadata: AggregateMetadata<*, *>,
): io.swagger.v3.oas.models.media.Schema<*> {
    val context = QuerySchemaContext(
        namedAggregate = aggregateMetadata.namedAggregate,
        model = QueryModel.SNAPSHOT,
    )
    val inferred = checkNotNull(staticQuerySchemaSource.load(context).blockFirst())
    val fields = buildSet {
        addAll(SystemQuerySchemaSource.declaration(QueryModel.SNAPSHOT).fields.keys)
        addAll(inferred.fields.keys)
    }.map(LogicalField::value).sorted()
    val key = "${aggregateMetadata.toStringWithAlias()}." +
        "${aggregateMetadata.command.aggregateType.simpleName}${QueryComponent.AGGREGATED_FIELDS_SUFFIX}"
    return componentSchema(key, StringSchema()._enum(fields))
}

object QueryComponent {
    const val SINGLE_QUERY_SUFFIX = ".SingleQuery"
    const val COUNT_QUERY_SUFFIX = ".CountQuery"
    const val LIST_QUERY_SUFFIX = ".ListQuery"
    const val CURSOR_QUERY_SUFFIX = ".CursorQuery"
    const val PAGED_QUERY_SUFFIX = ".PagedQuery"
    const val AGGREGATION_QUERY_SUFFIX = ".AggregationQuery"
    const val AGGREGATED_FIELDS_SUFFIX = "AggregatedFields"
    const val QUERY_FIELDS_EXTENSION = "x-wow-query-fields"
    const val SINGLE_QUERY_KEY = Wow.WOW + SINGLE_QUERY_SUFFIX
    const val COUNT_QUERY_KEY = Wow.WOW + COUNT_QUERY_SUFFIX
    const val LIST_QUERY_KEY = Wow.WOW + LIST_QUERY_SUFFIX
    const val CURSOR_QUERY_KEY = Wow.WOW + CURSOR_QUERY_SUFFIX
    const val PAGED_QUERY_KEY = Wow.WOW + PAGED_QUERY_SUFFIX
    const val AGGREGATION_QUERY_KEY = Wow.WOW + AGGREGATION_QUERY_SUFFIX

    object Schema {

        fun OpenAPIComponentContext.filterSchema(): io.swagger.v3.oas.models.media.Schema<*> {
            return schema(FilterExpression::class.java)
        }

        fun OpenAPIComponentContext.singleQuerySchema(): io.swagger.v3.oas.models.media.Schema<*> {
            return schema(SingleQuery::class.java)
        }

        fun OpenAPIComponentContext.listQuerySchema(): io.swagger.v3.oas.models.media.Schema<*> {
            return schema(ListQuery::class.java)
        }

        fun OpenAPIComponentContext.pagedQuerySchema(): io.swagger.v3.oas.models.media.Schema<*> {
            return schema(PagedQuery::class.java)
        }

        fun OpenAPIComponentContext.cursorQuerySchema(): io.swagger.v3.oas.models.media.Schema<*> {
            return schema(CursorQuery::class.java)
        }

        fun OpenAPIComponentContext.aggregationQuerySchema(): io.swagger.v3.oas.models.media.Schema<*> {
            return schema(AggregationQuery::class.java)
        }
    }

    object RequestBody {

        fun OpenAPIComponentContext.aggregatedSingleQueryRequestBody(
            aggregateMetadata: AggregateMetadata<*, *>,
        ): io.swagger.v3.oas.models.parameters.RequestBody =
            aggregatedQueryRequestBody(aggregateMetadata, SINGLE_QUERY_SUFFIX, singleQuerySchema())

        fun OpenAPIComponentContext.aggregatedAggregationQueryRequestBody(
            aggregateMetadata: AggregateMetadata<*, *>,
        ): io.swagger.v3.oas.models.parameters.RequestBody = aggregatedQueryRequestBody(
            aggregateMetadata,
            AGGREGATION_QUERY_SUFFIX,
            aggregationQuerySchema(),
        )

        fun OpenAPIComponentContext.singleQueryRequestBody(): io.swagger.v3.oas.models.parameters.RequestBody {
            return requestBody(SINGLE_QUERY_KEY) {
                content(schema = singleQuerySchema())
            }
        }

        fun OpenAPIComponentContext.aggregationQueryRequestBody(): io.swagger.v3.oas.models.parameters.RequestBody {
            return requestBody(AGGREGATION_QUERY_KEY) {
                content(schema = aggregationQuerySchema())
            }
        }

        fun OpenAPIComponentContext.countQueryRequestBody(): io.swagger.v3.oas.models.parameters.RequestBody {
            return requestBody(COUNT_QUERY_KEY) {
                content(schema = filterSchema())
            }
        }

        fun OpenAPIComponentContext.aggregatedCountQueryRequestBody(
            aggregateMetadata: AggregateMetadata<*, *>,
        ): io.swagger.v3.oas.models.parameters.RequestBody =
            aggregatedQueryRequestBody(aggregateMetadata, COUNT_QUERY_SUFFIX, filterSchema())

        fun OpenAPIComponentContext.listQueryRequestBody(): io.swagger.v3.oas.models.parameters.RequestBody {
            return requestBody(LIST_QUERY_KEY) {
                content(schema = listQuerySchema())
            }
        }

        fun OpenAPIComponentContext.aggregatedListQueryRequestBody(
            aggregateMetadata: AggregateMetadata<*, *>,
        ): io.swagger.v3.oas.models.parameters.RequestBody =
            aggregatedQueryRequestBody(aggregateMetadata, LIST_QUERY_SUFFIX, listQuerySchema())

        fun OpenAPIComponentContext.pagedQueryRequestBody(): io.swagger.v3.oas.models.parameters.RequestBody {
            return requestBody(PAGED_QUERY_KEY) {
                content(schema = pagedQuerySchema())
            }
        }

        fun OpenAPIComponentContext.aggregatedPagedQueryRequestBody(
            aggregateMetadata: AggregateMetadata<*, *>,
        ): io.swagger.v3.oas.models.parameters.RequestBody =
            aggregatedQueryRequestBody(aggregateMetadata, PAGED_QUERY_SUFFIX, pagedQuerySchema())

        fun OpenAPIComponentContext.cursorQueryRequestBody(): io.swagger.v3.oas.models.parameters.RequestBody {
            return requestBody(CURSOR_QUERY_KEY) {
                content(schema = cursorQuerySchema())
            }
        }

        fun OpenAPIComponentContext.aggregatedCursorQueryRequestBody(
            aggregateMetadata: AggregateMetadata<*, *>,
        ): io.swagger.v3.oas.models.parameters.RequestBody =
            aggregatedQueryRequestBody(aggregateMetadata, CURSOR_QUERY_SUFFIX, cursorQuerySchema())

        private fun OpenAPIComponentContext.aggregatedQueryRequestBody(
            aggregateMetadata: AggregateMetadata<*, *>,
            suffix: String,
            schema: io.swagger.v3.oas.models.media.Schema<*>,
        ): io.swagger.v3.oas.models.parameters.RequestBody {
            val queryFields = aggregatedFieldsSchema(aggregateMetadata)
            return requestBody(aggregateMetadata.toStringWithAlias() + suffix) {
                extension(QUERY_FIELDS_EXTENSION, queryFields)
                content(schema = schema)
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
