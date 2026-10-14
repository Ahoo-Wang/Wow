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

/**
 * The names of the public port's routes and headers that the Manifest does not carry and the document describes: the
 * framework's endpoints under `/wow`, the state reads beside the command routes, and the Wow request headers. They are
 * the edge's (`wow-http`'s `WowPaths` and `WowHeaders`), which `wow-http`'s tests hold to these.
 */
object WowHttpContract {
    /** Serves the Manifest the routes were built from. */
    const val MANIFEST_PATH: String = "/wow/manifest"

    /** Serves this document. */
    const val OPENAPI_PATH: String = "/wow/openapi"

    /** Answers a new global id as `text/plain`. */
    const val GLOBAL_ID_PATH: String = "/wow/id/global"

    /** The datasets the caller sees, and the root of each dataset's query route. */
    const val DATASETS_PATH: String = "/wow/dataset"

    /** The last segment of a state read: `GET /{context}/{aggregate}/{id}/state`. */
    const val STATE_SEGMENT: String = "state"

    /** The segment after [STATE_SEGMENT] of a trace. */
    const val TRACE_SEGMENT: String = "trace"

    const val REQUEST_ID_HEADER: String = "Wow-Request-Id"
    const val WAIT_HEADER: String = "Wow-Wait"
    const val WAIT_TIMEOUT_HEADER: String = "Wow-Wait-Timeout"
    const val SCOPE_HEADER: String = "Wow-Scope"
    const val SPACE_HEADER: String = "Wow-Space"
    const val IF_MATCH_HEADER: String = "If-Match"
    const val IF_NONE_MATCH_HEADER: String = "If-None-Match"
    const val ETAG_HEADER: String = "ETag"
    const val RETRY_AFTER_HEADER: String = "Retry-After"

    /** The W3C trace context headers a command request carries its trace in (decision D174). */
    const val TRACEPARENT_HEADER: String = "traceparent"
    const val TRACESTATE_HEADER: String = "tracestate"
    const val BAGGAGE_HEADER: String = "baggage"

    /** The query parameters of a state read and of a trace. */
    const val VERSION_PARAMETER: String = "version"
    const val AT_PARAMETER: String = "at"
    const val FROM_PARAMETER: String = "from"
    const val TO_PARAMETER: String = "to"
    const val LIMIT_PARAMETER: String = "limit"

    /** The most commits a trace holds, and how many it holds when the request names no `limit`. */
    const val MAX_TRACE_LIMIT: Int = 1000
    const val DEFAULT_TRACE_LIMIT: Int = 100

    /** The `kind`s of a query body (decision D127). */
    val QUERY_KINDS: List<String> = listOf("list", "paged", "cursor", "single", "count", "aggregate")

    /** The server-sent event of each record of a streamed list, and the one that ends a list that failed. */
    const val ROW_EVENT: String = "row"
    const val ERROR_EVENT: String = "error"

    /** The stages a command's server-sent events are named after, lower case (decision D61). */
    val STAGES: List<String> = listOf("accepted", "committed", "handled")
}
