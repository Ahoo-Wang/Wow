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

import io.swagger.v3.oas.models.media.ArraySchema
import io.swagger.v3.oas.models.media.ComposedSchema
import io.swagger.v3.oas.models.media.IntegerSchema
import io.swagger.v3.oas.models.media.ObjectSchema
import io.swagger.v3.oas.models.media.StringSchema
import me.ahoo.wow.api.Wow
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.analytics.AnalyticsCompleteness
import me.ahoo.wow.api.query.analytics.AnalyticsConsistency
import me.ahoo.wow.api.query.analytics.AnalyticsCursor
import me.ahoo.wow.api.query.analytics.AnalyticsDimension
import me.ahoo.wow.api.query.analytics.AnalyticsNumericPolicy
import me.ahoo.wow.api.query.analytics.AnalyticsPage
import me.ahoo.wow.modeling.metadata.AggregateMetadata
import me.ahoo.wow.modeling.toStringWithAlias
import me.ahoo.wow.openapi.CommonComponent.Response.withErrorCodeHeader
import me.ahoo.wow.openapi.QueryComponent.Schema.analyticsPageSchema
import me.ahoo.wow.openapi.QueryComponent.Schema.analyticsQuerySchema
import me.ahoo.wow.openapi.QueryComponent.Schema.conditionSchema
import me.ahoo.wow.openapi.QueryComponent.Schema.listQuerySchema
import me.ahoo.wow.openapi.QueryComponent.Schema.pagedQuerySchema
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import me.ahoo.wow.schema.typed.AggregatedDomainEventStream
import me.ahoo.wow.schema.typed.query.AggregatedCondition
import me.ahoo.wow.schema.typed.query.AggregatedListQuery
import me.ahoo.wow.schema.typed.query.AggregatedPagedQuery
import me.ahoo.wow.schema.typed.query.AggregatedSingleQuery

object QueryComponent {
    const val ANALYTICS_QUERY_SUFFIX = ".AnalyticsQuery"
    const val ANALYTICS_PAGE_SUFFIX = ".AnalyticsPage"
    const val SINGLE_QUERY_SUFFIX = ".SingleQuery"
    const val COUNT_QUERY_SUFFIX = ".CountQuery"
    const val LIST_QUERY_SUFFIX = ".ListQuery"
    const val PAGED_QUERY_SUFFIX = ".PagedQuery"
    const val COUNT_QUERY_KEY = Wow.WOW + COUNT_QUERY_SUFFIX
    const val LIST_QUERY_KEY = Wow.WOW + LIST_QUERY_SUFFIX
    const val PAGED_QUERY_KEY = Wow.WOW + PAGED_QUERY_SUFFIX
    const val ANALYTICS_QUERY_KEY = Wow.WOW + ANALYTICS_QUERY_SUFFIX
    const val ANALYTICS_PAGE_KEY = Wow.WOW + ANALYTICS_PAGE_SUFFIX

    object Schema {

        fun OpenAPIComponentContext.conditionSchema(): io.swagger.v3.oas.models.media.Schema<*> {
            return schema(Condition::class.java)
        }

        fun OpenAPIComponentContext.listQuerySchema(): io.swagger.v3.oas.models.media.Schema<*> {
            return schema(ListQuery::class.java)
        }

        fun OpenAPIComponentContext.pagedQuerySchema(): io.swagger.v3.oas.models.media.Schema<*> {
            return schema(PagedQuery::class.java)
        }

        fun OpenAPIComponentContext.analyticsQuerySchema(): io.swagger.v3.oas.models.media.Schema<*> {
            return ComposedSchema().oneOf(
                listOf(
                    analyticsQueryVariant(global = true),
                    analyticsQueryVariant(global = false),
                ),
            )
        }

        private fun OpenAPIComponentContext.analyticsQueryVariant(global: Boolean): ObjectSchema =
            ObjectSchema().apply {
                additionalProperties = false
                addProperties("condition", schema(Condition::class.java))
                addProperties("grouping", analyticsGroupingSchema(global))
                addProperties("metrics", ArraySchema().items(analyticsMetricSchema()).minItems(1))
                addProperties("window", analyticsBucketWindowSchema(global))
                addProperties("numericPolicy", schema(AnalyticsNumericPolicy::class.java))
                addProperties("consistency", schema(AnalyticsConsistency::class.java))
                addProperties("completeness", schema(AnalyticsCompleteness::class.java))
                required = listOf("grouping", "metrics", "window")
            }

        private fun OpenAPIComponentContext.analyticsGroupingSchema(global: Boolean): ObjectSchema =
            ObjectSchema().apply {
                additionalProperties = false
                addProperties(
                    "kind",
                    StringSchema()._const(if (global) "GLOBAL" else "BY"),
                )
                addProperties(
                    "dimensions",
                    ArraySchema().items(schema(AnalyticsDimension::class.java)).also { dimensions ->
                        if (global) dimensions.maxItems(0) else dimensions.minItems(1)
                    },
                )
                required = if (global) listOf("kind") else listOf("kind", "dimensions")
            }

        private fun OpenAPIComponentContext.analyticsMetricSchema(): ComposedSchema = ComposedSchema().apply {
            oneOf = listOf(
                ObjectSchema().apply {
                    additionalProperties = false
                    addProperties("alias", boundedString(128))
                    addProperties("kind", StringSchema()._const("DOCUMENT_COUNT"))
                    required = listOf("alias", "kind")
                },
                ObjectSchema().apply {
                    additionalProperties = false
                    addProperties("alias", boundedString(128))
                    addProperties(
                        "kind",
                        StringSchema()._enum(listOf("MIN", "MAX", "SUM", "AVERAGE")),
                    )
                    addProperties("field", boundedString(512))
                    required = listOf("alias", "kind", "field")
                },
            )
        }

        private fun OpenAPIComponentContext.analyticsBucketWindowSchema(global: Boolean): ObjectSchema =
            ObjectSchema().apply {
                additionalProperties = false
                addProperties(
                    "limit",
                    IntegerSchema().minimum(java.math.BigDecimal.ONE).also { limit ->
                        if (global) limit.maximum(java.math.BigDecimal.ONE)
                    },
                )
                if (!global) addProperties("cursor", schema(AnalyticsCursor::class.java))
                required = listOf("limit")
            }

        private fun boundedString(maxLength: Int): StringSchema = StringSchema().apply {
            minLength = 1
            this.maxLength = maxLength
        }

        fun OpenAPIComponentContext.analyticsPageSchema(): io.swagger.v3.oas.models.media.Schema<*> {
            return schema(AnalyticsPage::class.java)
        }
    }

    object RequestBody {

        fun OpenAPIComponentContext.aggregatedSingleQueryRequestBody(aggregateMetadata: AggregateMetadata<*, *>): io.swagger.v3.oas.models.parameters.RequestBody {
            return requestBody(aggregateMetadata.toStringWithAlias() + SINGLE_QUERY_SUFFIX) {
                content(schema = schema(AggregatedSingleQuery::class.java, aggregateMetadata.command.aggregateType))
            }
        }

        fun OpenAPIComponentContext.countQueryRequestBody(): io.swagger.v3.oas.models.parameters.RequestBody {
            return requestBody(COUNT_QUERY_KEY) {
                content(schema = conditionSchema())
            }
        }

        fun OpenAPIComponentContext.aggregatedCountQueryRequestBody(aggregateMetadata: AggregateMetadata<*, *>): io.swagger.v3.oas.models.parameters.RequestBody {
            return requestBody(aggregateMetadata.toStringWithAlias() + COUNT_QUERY_SUFFIX) {
                content(schema = schema(AggregatedCondition::class.java, aggregateMetadata.command.aggregateType))
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
                content(schema = schema(AggregatedListQuery::class.java, aggregateMetadata.command.aggregateType))
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
                content(schema = schema(AggregatedPagedQuery::class.java, aggregateMetadata.command.aggregateType))
            }
        }

        fun OpenAPIComponentContext.analyticsQueryRequestBody(): io.swagger.v3.oas.models.parameters.RequestBody {
            return requestBody(ANALYTICS_QUERY_KEY) {
                content(schema = analyticsQuerySchema())
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

        fun OpenAPIComponentContext.analyticsPageResponse(): io.swagger.v3.oas.models.responses.ApiResponse {
            return response(ANALYTICS_PAGE_KEY) {
                withErrorCodeHeader(this@analyticsPageResponse)
                content(Https.MediaType.APPLICATION_JSON, schema = analyticsPageSchema())
            }
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
