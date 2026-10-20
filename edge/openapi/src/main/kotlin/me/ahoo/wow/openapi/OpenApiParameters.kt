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

import me.ahoo.wow.openapi.Json.obj
import me.ahoo.wow.openapi.Json.type
import me.ahoo.wow.openapi.WowHttpContract as Http

/**
 * The parameters and response headers the routes share, as `components.parameters` and `components.headers`: the Wow
 * request headers (decisions D95, D96, D98, D99), the path variables and the query parameters of the state reads.
 */
internal object OpenApiParameters {
    const val ID: String = "id"
    const val DATASET_CONTEXT: String = "context"
    const val DATASET: String = "dataset"

    /** The headers a command route reads, in order; [Http.IF_MATCH_HEADER] only on a route with the aggregate id. */
    val COMMAND_HEADERS: List<String> = listOf(
        Http.REQUEST_ID_HEADER,
        Http.WAIT_HEADER,
        Http.WAIT_TIMEOUT_HEADER,
        Http.SCOPE_HEADER,
        Http.SPACE_HEADER,
        Http.TRACEPARENT_HEADER,
        Http.TRACESTATE_HEADER,
        Http.BAGGAGE_HEADER,
    )

    fun ref(name: String): Map<String, Any?> = Json.ref(Json.PARAMETERS, name)

    fun all(): Map<String, Any?> = linkedMapOf(
        ID to path(ID, "The aggregate's id."),
        DATASET_CONTEXT to path(DATASET_CONTEXT, "The context the dataset belongs to."),
        DATASET to path(DATASET, "The dataset's name in its context."),
        Http.REQUEST_ID_HEADER to header(
            Http.REQUEST_ID_HEADER,
            "The caller's id of the request, kept as the command's request id: a retry with the same one is " +
                "deduplicated.",
        ),
        Http.WAIT_HEADER to header(
            Http.WAIT_HEADER,
            "The wait plan (decision D99): hops separated by `>`, each `accepted`, `committed`, `snapshot` or " +
                "`handled(subscription)`, with an optional `(context)`, such as " +
                "`handled(order/payment-saga) > committed(inventory)`. Without it the command waits for its snapshot.",
        ),
        Http.WAIT_TIMEOUT_HEADER to header(
            Http.WAIT_TIMEOUT_HEADER,
            "How long to wait: `500ms`, `10s`, `1m` or an ISO-8601 duration (`PT10S`).",
        ),
        Http.SCOPE_HEADER to header(
            Http.SCOPE_HEADER,
            "The scope to act in, for a caller allowed to act across scopes: an RFC 8941 dictionary (decision D95).",
        ),
        Http.SPACE_HEADER to header(Http.SPACE_HEADER, "The space, among the caller's own, to act in (decision D96)."),
        Http.TRACEPARENT_HEADER to header(
            Http.TRACEPARENT_HEADER,
            "The W3C trace context of the caller's span (decision D174): the command's admission continues the trace.",
        ),
        Http.TRACESTATE_HEADER to header(Http.TRACESTATE_HEADER, "The W3C trace state that goes with `traceparent`."),
        Http.BAGGAGE_HEADER to header(Http.BAGGAGE_HEADER, "The W3C baggage that goes with `traceparent`."),
        Http.IF_MATCH_HEADER to header(
            Http.IF_MATCH_HEADER,
            "The version the aggregate is expected at, as an entity tag: `\"3\"`. A mismatch is 412.",
        ),
        Http.IF_NONE_MATCH_HEADER to header(
            Http.IF_NONE_MATCH_HEADER,
            "The entity tag the client holds: 304 when it is still the current one.",
        ),
        Http.LAST_EVENT_ID_HEADER to header(
            Http.LAST_EVENT_ID_HEADER,
            "Resumes a streamed `list` strictly after this position, the `id` of the last `row` event read (decision " +
                "D253): send the same query body again. A position of another model or sort is `wow.query.invalid`.",
        ),
        Http.VERSION_PARAMETER to query(Http.VERSION_PARAMETER, minimum(1), "Reads the state at this version."),
        Http.AT_PARAMETER to query(
            Http.AT_PARAMETER,
            obj("type" to "string", "format" to "date-time"),
            "Reads the state after the last commit at or before this ISO-8601 instant (decision D40).",
        ),
        Http.FROM_PARAMETER to query(Http.FROM_PARAMETER, minimum(1), "The first version of the trace; 1 by default."),
        Http.TO_PARAMETER to query(Http.TO_PARAMETER, minimum(1), "The last version of the trace; the last by default."),
        Http.LIMIT_PARAMETER to query(
            Http.LIMIT_PARAMETER,
            obj("type" to "integer", "minimum" to 1, "maximum" to Http.MAX_TRACE_LIMIT),
            "The most commits the trace holds; ${Http.DEFAULT_TRACE_LIMIT} by default.",
        ),
    )

    fun headers(): Map<String, Any?> = linkedMapOf(
        Http.ETAG_HEADER to obj(
            "description" to "The entity tag of the representation, which If-None-Match names to be answered 304.",
            "schema" to type("string"),
        ),
        Http.RETRY_AFTER_HEADER to obj(
            "description" to "The seconds after which the request may be retried.",
            "schema" to type("integer"),
        ),
    )

    private fun minimum(value: Int): Map<String, Any?> = obj("type" to "integer", "minimum" to value)

    private fun path(name: String, description: String): Map<String, Any?> = obj(
        "name" to name,
        "in" to "path",
        "required" to true,
        "description" to description,
        "schema" to type("string"),
    )

    private fun header(name: String, description: String): Map<String, Any?> =
        obj("name" to name, "in" to "header", "description" to description, "schema" to type("string"))

    private fun query(name: String, schema: Map<String, Any?>, description: String): Map<String, Any?> =
        obj("name" to name, "in" to "query", "description" to description, "schema" to schema)
}
