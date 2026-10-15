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

package me.ahoo.wow.http

import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule

/**
 * The request headers of the public port (decisions D95, D96, D98, D99). A request header that is neither one of these
 * nor a request fact the host lists ([WowHttpOptions.requestFacts]) is ignored.
 */
object WowHeaders {
    /** The caller's id of the request, kept as the command's request id: a retry with the same one is deduplicated. */
    const val REQUEST_ID: String = "Wow-Request-Id"

    /** The wait plan, in the grammar of [me.ahoo.wow.http.command.WaitHeader] (decision D99). */
    const val WAIT: String = "Wow-Wait"

    /** How long to wait: `500ms`, `10s` or an ISO-8601 duration (`PT10S`). */
    const val WAIT_TIMEOUT: String = "Wow-Wait-Timeout"

    /** The scope to act in, for a caller allowed to act across scopes: an RFC 8941 dictionary (decision D95). */
    const val SCOPE: String = "Wow-Scope"

    /** The space, among the caller's own, to act in (decision D96); the caller resolver checks the membership. */
    const val SPACE: String = "Wow-Space"

    /**
     * The root command a service's synchronous call acts for (decision D193), in the form of
     * [me.ahoo.wow.http.command.CallHeader]: taken on the internal port alone, from a service identity
     * ([me.ahoo.wow.http.callRoutes]); refused (403) on the public port whatever the credential.
     */
    const val CALL: String = "Wow-Call"

    /** The expected version of the aggregate, as an entity tag: `If-Match: "3"`. */
    const val IF_MATCH: String = "If-Match"

    /** The version of a state read, as an entity tag: `ETag: "3"`. */
    const val ETAG: String = "ETag"

    /**
     * On the answer of a snapshot query or its descriptor, `true` while the aggregate type's snapshots are being
     * rebuilt or backfilled (decision D172): the query answers from them as they are, some not yet rewritten.
     */
    const val REBUILDING: String = "Wow-Rebuilding"
}

/**
 * What the host configures of the public routes (decision D102 and the `wow.http.*` properties of the host).
 *
 * - [requestFacts] are the request headers copied into a command's headers, as they are named here; a name the
 *   framework reserves (`Wow-*`, `wow.*`) cannot be one.
 * - [errorStatuses] overrides the HTTP status of an error code, a domain rejection's (422 by default) or a framework
 *   one's.
 * - [manifestEndpoint] serves `GET /wow/manifest`; [openApiEndpoint] serves `GET /wow/openapi`.
 */
data class WowHttpOptions(
    val requestFacts: Set<String> = emptySet(),
    val errorStatuses: Map<String, Int> = emptyMap(),
    val manifestEndpoint: Boolean = true,
    val openApiEndpoint: Boolean = true,
) {
    init {
        val reserved = requestFacts.filter { it.startsWith("wow-", ignoreCase = true) || it.startsWith("wow.") }
        require(reserved.isEmpty()) { "Request facts cannot take a name the framework reserves: $reserved" }
        require(requestFacts.none { it.isBlank() }) { "A request fact is named." }
        val invalid = errorStatuses.filterValues { it !in MIN_ERROR_STATUS..MAX_ERROR_STATUS }
        require(invalid.isEmpty()) { "An error status is a 4xx or 5xx status: $invalid" }
    }

    private companion object {
        const val MIN_ERROR_STATUS = 400
        const val MAX_ERROR_STATUS = 599
    }
}

/** The paths of the framework's own public endpoints, under the reserved context `wow` (decision D104). */
internal object WowPaths {
    /** Serves the Manifest the routes were built from. */
    const val MANIFEST: String = "/wow/manifest"

    /** Serves the OpenAPI 3.1 document of the routes (roadmap 5a.2), from the Manifest they were built from. */
    const val OPENAPI: String = "/wow/openapi"

    /** Answers a new global id (09 §13 #15, decision D171), as 9.x did. */
    const val GLOBAL_ID: String = "/wow/id/global"

    /**
     * The datasets the caller sees (decision D145), and the root of each dataset's query route,
     * `/wow/dataset/{context}/{dataset}/query`: a dataset is not an aggregate of the Manifest, so its routes live
     * under the framework's context rather than its own.
     */
    const val DATASETS: String = "/wow/dataset"

    /** The last segment of a state read: `GET /{context}/{aggregate}/{id}/state`. */
    const val STATE: String = "state"

    /** The query parameter of a state read at a version. */
    const val VERSION: String = "version"

    /** The query parameter of a state read as of a time, an ISO-8601 instant (decision D40). */
    const val AT: String = "at"

    /** The segment after [STATE] of a trace: `GET /{context}/{aggregate}/{id}/state/trace` (decision D40). */
    const val TRACE: String = "trace"

    /** The query parameter of a trace's first version. */
    const val FROM: String = "from"

    /** The query parameter of a trace's last version. */
    const val TO: String = "to"

    /** The query parameter of the most commits a trace holds. */
    const val LIMIT: String = "limit"
}

/**
 * The JSON of the public port: the Kotlin module only, unknown fields ignored, as the cluster codec (decision D105).
 * Spring's codecs are not used: responses are written as bytes.
 */
internal object HttpJson {
    val mapper: JsonMapper = JsonMapper.builder()
        .addModule(kotlinModule())
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build()

    fun write(value: Any): ByteArray = mapper.writeValueAsBytes(value)

    fun writeString(value: Any): String = mapper.writeValueAsString(value)
}
