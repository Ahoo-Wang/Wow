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

package me.ahoo.wow.openapi.contributor.global

import io.swagger.v3.oas.models.media.ArraySchema
import io.swagger.v3.oas.models.media.IntegerSchema
import io.swagger.v3.oas.models.media.ObjectSchema
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.media.StringSchema
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.openapi.Https
import me.ahoo.wow.openapi.RouteIdSpec
import me.ahoo.wow.openapi.catalog.RouteContributor
import me.ahoo.wow.openapi.component.CommonComponents
import me.ahoo.wow.openapi.component.CommonComponents.errorCodeHeader
import me.ahoo.wow.openapi.component.QueryComponents
import me.ahoo.wow.openapi.component.pathParameter
import me.ahoo.wow.openapi.contract.BuiltInHttpRouteHandlerKeys.Dataset
import me.ahoo.wow.openapi.contract.HttpContent
import me.ahoo.wow.openapi.contract.HttpParameter
import me.ahoo.wow.openapi.contract.HttpRequestBody
import me.ahoo.wow.openapi.contract.HttpResponse
import me.ahoo.wow.openapi.contract.HttpRouteContract
import me.ahoo.wow.openapi.contract.HttpSchema
import me.ahoo.wow.openapi.contract.HttpTag
import me.ahoo.wow.openapi.contributor.aggregationResponse
import me.ahoo.wow.openapi.contributor.querySchemaParameters
import me.ahoo.wow.openapi.contributor.querySchemaResponses
import me.ahoo.wow.rest.RoutePaths
import me.ahoo.wow.rest.RouteVariables

/**
 * The query routes of every dataset this instance serves (dataset query design §6): `/dataset/{context}/{dataset}/…`,
 * and the same under `/tenant/{tenantId}`. A dataset's records are untyped rows, so the routes describe them as JSON
 * objects; a dataset's fields are what its capability descriptor (`…/schema`) says. Not one of
 * [me.ahoo.wow.openapi.contributor.DefaultRouteContributors]: whoever serves datasets (the Spring Boot starter, when
 * datasets are configured) adds it next to the handlers.
 */
@InternalWowApi
object DatasetQueryRouteContributor : RouteContributor {
    private const val RESOURCE = "dataset"
    private val tags = listOf(HttpTag(RESOURCE, "Declared datasets, such as BI wide tables"))
    private val STREAMING_ACCEPT = listOf(Https.MediaType.APPLICATION_JSON, Https.MediaType.TEXT_EVENT_STREAM)

    private val contextParameter = pathParameter(RouteVariables.CONTEXT) {
        schema = StringSchema().description("the bounded context the dataset is named in")
    }
    private val datasetParameter = pathParameter(RouteVariables.DATASET) {
        schema = StringSchema().description("the dataset's name")
    }

    private val rowSchema = ObjectSchema().additionalProperties(Schema<Any>().nullable(true))

    private val listResponse = HttpResponse(
        statusCode = Https.Code.OK,
        headers = listOf(errorCodeHeader),
        content = listOf(
            HttpContent(Https.MediaType.APPLICATION_JSON, HttpSchema.Raw(ArraySchema().items(rowSchema))),
            HttpContent(
                Https.MediaType.TEXT_EVENT_STREAM,
                HttpSchema.Raw(
                    ArraySchema().items(
                        ObjectSchema()
                            .addProperty("id", StringSchema().nullable(true))
                            .addProperty("event", StringSchema().nullable(true))
                            .addProperty("data", rowSchema)
                            .addProperty("retry", IntegerSchema().nullable(true))
                            .required(listOf("data")),
                    ),
                ),
            ),
        ),
    )

    private val pagedResponse = jsonResponse(
        ObjectSchema()
            .addProperty("total", IntegerSchema().format("int64"))
            .addProperty("list", ArraySchema().items(rowSchema))
            .required(listOf("total", "list")),
    )

    private val cursorResponse = jsonResponse(
        ObjectSchema()
            .addProperty("list", ArraySchema().items(rowSchema))
            .addProperty("nextCursor", StringSchema().nullable(true))
            .required(listOf("list")),
    )

    private class Route(
        val handlerKey: String,
        val operation: String,
        val summary: String,
        val path: String,
        val method: String = Https.Method.POST,
        val accept: List<String> = listOf(Https.MediaType.APPLICATION_JSON),
        val parameters: List<HttpParameter> = emptyList(),
        val requestBody: HttpRequestBody? = null,
        val responses: List<HttpResponse>,
    )

    private val routes = listOf(
        Route(
            handlerKey = Dataset.SCHEMA,
            operation = "schema",
            summary = "Get Dataset Schema",
            path = RoutePaths.DATASET_SCHEMA,
            method = Https.Method.GET,
            parameters = querySchemaParameters,
            responses = querySchemaResponses,
        ),
        Route(
            handlerKey = Dataset.COUNT,
            operation = "count",
            summary = "Count Dataset",
            path = RoutePaths.DATASET_COUNT,
            requestBody = QueryComponents.countQueryRequestBody,
            responses = listOf(QueryComponents.countQueryResponse),
        ),
        Route(
            handlerKey = Dataset.AGGREGATION,
            operation = "aggregation",
            summary = "Aggregate Dataset",
            path = RoutePaths.DATASET_AGGREGATION,
            accept = STREAMING_ACCEPT,
            requestBody = QueryComponents.aggregationQueryRequestBody,
            responses = listOf(aggregationResponse),
        ),
        Route(
            handlerKey = Dataset.LIST_QUERY,
            operation = "list_query",
            summary = "List Query Dataset",
            path = RoutePaths.DATASET_LIST,
            accept = STREAMING_ACCEPT,
            requestBody = QueryComponents.listQueryRequestBody,
            responses = listOf(listResponse),
        ),
        Route(
            handlerKey = Dataset.PAGED_QUERY,
            operation = "paged_query",
            summary = "Paged Query Dataset",
            path = RoutePaths.DATASET_PAGED,
            requestBody = QueryComponents.pagedQueryRequestBody,
            responses = listOf(pagedResponse),
        ),
        Route(
            handlerKey = Dataset.CURSOR_QUERY,
            operation = "cursor_query",
            summary = "Cursor Query Dataset",
            path = RoutePaths.DATASET_CURSOR,
            requestBody = QueryComponents.cursorQueryRequestBody,
            responses = listOf(cursorResponse),
        ),
        Route(
            handlerKey = Dataset.SINGLE,
            operation = "single",
            summary = "Single Query Dataset",
            path = RoutePaths.DATASET_SINGLE,
            requestBody = QueryComponents.singleQueryRequestBody,
            responses = listOf(jsonResponse(rowSchema)),
        ),
    )

    override fun contributeGlobal(currentContext: NamedBoundedContext): List<HttpRouteContract> =
        listOf(false, true).flatMap { withinTenant -> routes.map { it.contract(withinTenant) } }

    private fun Route.contract(withinTenant: Boolean): HttpRouteContract = HttpRouteContract(
        routeId = RouteIdSpec().prefix(RESOURCE).appendTenant(withinTenant).resourceName(RESOURCE)
            .operation(operation).build(),
        method = method,
        path = if (withinTenant) RoutePaths.TENANT_PREFIX + path else path,
        handlerKey = handlerKey,
        summary = if (withinTenant) "$summary Within Tenant" else summary,
        accept = accept,
        parameters = listOfNotNull(CommonComponents.tenantIdPathParameter.takeIf { withinTenant }) +
            listOf(contextParameter, datasetParameter) + parameters,
        requestBody = requestBody,
        responses = responses + CommonComponents.notFoundResponse,
        tags = tags,
    )

    private fun jsonResponse(schema: Schema<*>): HttpResponse = HttpResponse(
        statusCode = Https.Code.OK,
        headers = listOf(errorCodeHeader),
        content = listOf(HttpContent(Https.MediaType.APPLICATION_JSON, HttpSchema.Raw(schema))),
    )
}
