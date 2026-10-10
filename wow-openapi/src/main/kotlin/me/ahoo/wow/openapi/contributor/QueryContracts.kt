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

import io.swagger.v3.oas.models.media.ArraySchema
import io.swagger.v3.oas.models.media.IntegerSchema
import io.swagger.v3.oas.models.media.ObjectSchema
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.media.StringSchema
import me.ahoo.wow.api.query.CursorPage
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.api.query.descriptor.QueryModelDescriptor
import me.ahoo.wow.modeling.metadata.AggregateMetadata
import me.ahoo.wow.openapi.Https
import me.ahoo.wow.openapi.component.CommonComponents.errorCodeHeader
import me.ahoo.wow.openapi.contract.HttpContent
import me.ahoo.wow.openapi.contract.HttpHeader
import me.ahoo.wow.openapi.contract.HttpParameter
import me.ahoo.wow.openapi.contract.HttpParameterLocation
import me.ahoo.wow.openapi.contract.HttpResponse
import me.ahoo.wow.openapi.contract.HttpSchema
import me.ahoo.wow.schema.typed.AggregatedDomainEventStream
import me.ahoo.wow.schema.web.ServerSentEventNonNullData

private val aggregationRowSchema = ObjectSchema().additionalProperties(Schema<Any>().nullable(true))

/** The aggregation routes answer untyped rows, as a JSON array or as server-sent events. */
internal val aggregationResponse: HttpResponse = HttpResponse(
    statusCode = Https.Code.OK,
    headers = listOf(errorCodeHeader),
    content = listOf(
        HttpContent(Https.MediaType.APPLICATION_JSON, HttpSchema.Raw(ArraySchema().items(aggregationRowSchema))),
        HttpContent(
            Https.MediaType.TEXT_EVENT_STREAM,
            HttpSchema.Raw(
                ArraySchema().items(
                    ObjectSchema()
                        .addProperty("id", StringSchema().nullable(true))
                        .addProperty("event", StringSchema().nullable(true))
                        .addProperty("data", aggregationRowSchema)
                        .addProperty("retry", IntegerSchema().nullable(true))
                        .required(listOf("data"))
                )
            )
        )
    )
)

private val QUERY_SCHEMA_ETAG = HttpHeader(
    name = "ETag",
    description = "The descriptor's version, quoted. Send it back as If-None-Match to get 304 while it is unchanged. " +
        "Cross-origin scripts can read it only when the server lists ETag in Access-Control-Expose-Headers.",
)

/** The conditional-GET request header of the capability descriptor routes. */
internal val querySchemaParameters: List<HttpParameter> = listOf(
    HttpParameter(
        name = "If-None-Match",
        location = HttpParameterLocation.HEADER,
        description = "An ETag from an earlier response; the server answers 304 while the descriptor is unchanged.",
    ),
)

internal val querySchemaResponses: List<HttpResponse> = listOf(
    HttpResponse(
        statusCode = Https.Code.OK,
        headers = listOf(errorCodeHeader, QUERY_SCHEMA_ETAG),
        content = listOf(
            HttpContent(
                Https.MediaType.APPLICATION_JSON,
                HttpSchema.TypeRef(QueryModelDescriptor::class.java),
            )
        ),
    ),
    HttpResponse(
        statusCode = Https.Code.NOT_MODIFIED,
        description = "The descriptor matches If-None-Match.",
        headers = listOf(QUERY_SCHEMA_ETAG),
    ),
    HttpResponse(Https.Code.BAD_REQUEST),
    HttpResponse(Https.Code.INTERNAL_SERVER_ERROR),
    HttpResponse(Https.Code.SERVICE_UNAVAILABLE),
)

private val AggregateMetadata<*, *>.eventStream: HttpSchema.TypeRef
    get() = HttpSchema.TypeRef(
        AggregatedDomainEventStream::class.java,
        listOf(HttpSchema.TypeRef(command.aggregateType))
    )

private val AggregateMetadata<*, *>.materializedSnapshot: HttpSchema.TypeRef
    get() = HttpSchema.TypeRef(
        MaterializedSnapshot::class.java,
        listOf(HttpSchema.TypeRef(state.aggregateType))
    )

private val AggregateMetadata<*, *>.stateType: HttpSchema.TypeRef
    get() = HttpSchema.TypeRef(state.aggregateType)

internal fun eventStreamListResponse(aggregateMetadata: AggregateMetadata<*, *>): HttpResponse =
    listResponse(aggregateMetadata.eventStream)

internal fun eventStreamPagedResponse(aggregateMetadata: AggregateMetadata<*, *>): HttpResponse =
    responseWithJson(pagedList(aggregateMetadata.eventStream))

internal fun eventStreamCursorResponse(aggregateMetadata: AggregateMetadata<*, *>): HttpResponse =
    responseWithJson(cursorPage(aggregateMetadata.eventStream))

internal fun materializedSnapshotListResponse(aggregateMetadata: AggregateMetadata<*, *>): HttpResponse =
    listResponse(aggregateMetadata.materializedSnapshot)

internal fun stateListResponse(aggregateMetadata: AggregateMetadata<*, *>): HttpResponse =
    listResponse(aggregateMetadata.stateType)

internal fun materializedSnapshotPagedResponse(aggregateMetadata: AggregateMetadata<*, *>): HttpResponse =
    responseWithJson(pagedList(aggregateMetadata.materializedSnapshot))

internal fun materializedSnapshotCursorResponse(aggregateMetadata: AggregateMetadata<*, *>): HttpResponse =
    responseWithJson(cursorPage(aggregateMetadata.materializedSnapshot))

internal fun statePagedResponse(aggregateMetadata: AggregateMetadata<*, *>): HttpResponse =
    responseWithJson(pagedList(aggregateMetadata.stateType))

internal fun stateCursorResponse(aggregateMetadata: AggregateMetadata<*, *>): HttpResponse =
    responseWithJson(cursorPage(aggregateMetadata.stateType))

internal fun materializedSnapshotSingleResponse(aggregateMetadata: AggregateMetadata<*, *>): HttpResponse =
    responseWithJson(aggregateMetadata.materializedSnapshot)

internal fun stateSingleResponse(aggregateMetadata: AggregateMetadata<*, *>): HttpResponse =
    responseWithJson(aggregateMetadata.stateType)

private fun pagedList(item: HttpSchema.TypeRef) = HttpSchema.TypeRef(PagedList::class.java, listOf(item))

private fun cursorPage(item: HttpSchema.TypeRef) = HttpSchema.TypeRef(CursorPage::class.java, listOf(item))

/** A list as a JSON array, or as server-sent events each carrying one item. */
private fun listResponse(item: HttpSchema.TypeRef): HttpResponse {
    return HttpResponse(
        statusCode = Https.Code.OK,
        headers = listOf(errorCodeHeader),
        content = listOf(
            HttpContent(Https.MediaType.APPLICATION_JSON, HttpSchema.Array(item)),
            HttpContent(
                Https.MediaType.TEXT_EVENT_STREAM,
                HttpSchema.Array(HttpSchema.TypeRef(ServerSentEventNonNullData::class.java, listOf(item)))
            )
        )
    )
}

private fun responseWithJson(schema: HttpSchema): HttpResponse {
    return HttpResponse(
        statusCode = Https.Code.OK,
        headers = listOf(errorCodeHeader),
        content = listOf(HttpContent(Https.MediaType.APPLICATION_JSON, schema))
    )
}
