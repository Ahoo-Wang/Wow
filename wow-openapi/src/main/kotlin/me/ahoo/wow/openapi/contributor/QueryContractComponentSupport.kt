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

package me.ahoo.wow.openapi.contributor

import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.modeling.metadata.AggregateMetadata
import me.ahoo.wow.modeling.toStringWithAlias
import me.ahoo.wow.openapi.Https
import me.ahoo.wow.openapi.QueryComponent
import me.ahoo.wow.openapi.QueryComponent.RequestBody.aggregatedCountQueryRequestBody
import me.ahoo.wow.openapi.QueryComponent.RequestBody.aggregatedListQueryRequestBody
import me.ahoo.wow.openapi.QueryComponent.RequestBody.aggregatedPagedQueryRequestBody
import me.ahoo.wow.openapi.QueryComponent.RequestBody.aggregatedSingleQueryRequestBody
import me.ahoo.wow.openapi.QueryComponent.Response.countQueryResponse
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import me.ahoo.wow.openapi.contract.HttpContent
import me.ahoo.wow.openapi.contract.HttpRequestBody
import me.ahoo.wow.openapi.contract.HttpResponse
import me.ahoo.wow.openapi.contract.HttpSchema
import me.ahoo.wow.schema.web.ServerSentEventNonNullData
import java.lang.reflect.Type

internal fun OpenAPIComponentContext.aggregatedCountQueryRequestBodyRef(
    aggregateMetadata: AggregateMetadata<*, *>
): HttpRequestBody {
    aggregatedCountQueryRequestBody(aggregateMetadata)
    return aggregateMetadata.queryRequestBodyRef(QueryComponent.COUNT_QUERY_SUFFIX)
}

internal fun OpenAPIComponentContext.aggregatedListQueryRequestBodyRef(
    aggregateMetadata: AggregateMetadata<*, *>
): HttpRequestBody {
    aggregatedListQueryRequestBody(aggregateMetadata)
    return aggregateMetadata.queryRequestBodyRef(QueryComponent.LIST_QUERY_SUFFIX)
}

internal fun OpenAPIComponentContext.aggregatedPagedQueryRequestBodyRef(
    aggregateMetadata: AggregateMetadata<*, *>
): HttpRequestBody {
    aggregatedPagedQueryRequestBody(aggregateMetadata)
    return aggregateMetadata.queryRequestBodyRef(QueryComponent.PAGED_QUERY_SUFFIX)
}

internal fun OpenAPIComponentContext.aggregatedSingleQueryRequestBodyRef(
    aggregateMetadata: AggregateMetadata<*, *>
): HttpRequestBody {
    aggregatedSingleQueryRequestBody(aggregateMetadata)
    return aggregateMetadata.queryRequestBodyRef(QueryComponent.SINGLE_QUERY_SUFFIX)
}

internal fun OpenAPIComponentContext.countQueryResponseRef(): HttpResponse {
    countQueryResponse()
    return HttpResponse(
        statusCode = Https.Code.OK,
        componentRef = QueryComponent.COUNT_QUERY_KEY
    )
}

internal fun OpenAPIComponentContext.materializedSnapshotListResponse(
    aggregateMetadata: AggregateMetadata<*, *>
): HttpResponse {
    return listResponse(
        MaterializedSnapshot::class.java,
        aggregateMetadata.state.aggregateType
    )
}

internal fun OpenAPIComponentContext.stateListResponse(
    aggregateMetadata: AggregateMetadata<*, *>
): HttpResponse {
    return listResponse(mainTargetType = aggregateMetadata.state.aggregateType)
}

internal fun OpenAPIComponentContext.materializedSnapshotPagedResponse(
    aggregateMetadata: AggregateMetadata<*, *>
): HttpResponse {
    return responseWithJson(
        schema = HttpSchema.Raw(
            schema(
                PagedList::class.java,
                resolveType(MaterializedSnapshot::class.java, aggregateMetadata.state.aggregateType)
            )
        )
    )
}

internal fun OpenAPIComponentContext.statePagedResponse(
    aggregateMetadata: AggregateMetadata<*, *>
): HttpResponse {
    return responseWithJson(
        schema = HttpSchema.Raw(schema(PagedList::class.java, aggregateMetadata.state.aggregateType))
    )
}

internal fun OpenAPIComponentContext.materializedSnapshotSingleResponse(
    aggregateMetadata: AggregateMetadata<*, *>
): HttpResponse {
    return responseWithJson(
        schema = HttpSchema.TypeRef(
            MaterializedSnapshot::class.java,
            listOf(aggregateMetadata.state.aggregateType)
        )
    )
}

internal fun OpenAPIComponentContext.stateSingleResponse(
    aggregateMetadata: AggregateMetadata<*, *>
): HttpResponse {
    return responseWithJson(schema = schemaRef(aggregateMetadata.state.aggregateType))
}

private fun AggregateMetadata<*, *>.queryRequestBodyRef(suffix: String): HttpRequestBody {
    return HttpRequestBody(componentRef = toStringWithAlias() + suffix)
}

private fun OpenAPIComponentContext.listResponse(
    mainTargetType: Type,
    vararg typeParameter: Type
): HttpResponse {
    val resolvedType = resolveType(mainTargetType, *typeParameter)
    val serverSentEventType = resolveType(ServerSentEventNonNullData::class.java, resolvedType)
    return HttpResponse(
        statusCode = Https.Code.OK,
        headers = listOf(errorCodeHeaderRef()),
        content = listOf(
            HttpContent(
                Https.MediaType.APPLICATION_JSON,
                HttpSchema.Raw(arraySchema(resolvedType))
            ),
            HttpContent(
                Https.MediaType.TEXT_EVENT_STREAM,
                HttpSchema.Raw(arraySchema(serverSentEventType))
            )
        )
    )
}

private fun OpenAPIComponentContext.responseWithJson(schema: HttpSchema): HttpResponse {
    return HttpResponse(
        statusCode = Https.Code.OK,
        headers = listOf(errorCodeHeaderRef()),
        content = listOf(HttpContent(Https.MediaType.APPLICATION_JSON, schema))
    )
}
