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

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.spi.json.WowJson
import tools.jackson.databind.json.JsonMapper

/**
 * What the host configures of the public routes (decision D102 and the `wow.http.*` properties of the host).
 *
 * - [requestFacts] are the request headers copied into a command's headers, as they are named here; a name the
 *   framework reserves (`Wow-*`, `wow.*`) cannot be one. Beside them the edge records the standard request facts
 *   ([me.ahoo.wow.api.decide.RequestFacts]), which no request header sets: `app_id`, the caller's authenticated
 *   application (decision D232), and on the public port `user_agent` and `remote_ip` (09 §17 G-10); a listed name
 *   that is one of them is not copied.
 * - [errorStatuses] overrides the HTTP status of an error code, a domain rejection's (422 by default) or a framework
 *   one's.
 * - [manifestEndpoint] serves `GET /wow/manifest`; [openApiEndpoint] serves `GET /wow/openapi`.
 *
 * Fields may be added (decision D216): Wow builds it from the host's configuration, so the constructor is internal
 * to Wow.
 */
class WowHttpOptions @InternalWowApi constructor(
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

    /** This WowHttpOptions with the given fields replaced: Wow's own copy, left out of the ABI. */
    @InternalWowApi
    fun copyWith(
        requestFacts: Set<String> = this.requestFacts,
        errorStatuses: Map<String, Int> = this.errorStatuses,
        manifestEndpoint: Boolean = this.manifestEndpoint,
        openApiEndpoint: Boolean = this.openApiEndpoint,
    ): WowHttpOptions = WowHttpOptions(requestFacts, errorStatuses, manifestEndpoint, openApiEndpoint)

    override fun equals(other: Any?): Boolean =
        this === other || other is WowHttpOptions && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "WowHttpOptions(requestFacts=$requestFacts, errorStatuses=$errorStatuses, " +
            "manifestEndpoint=$manifestEndpoint, openApiEndpoint=$openApiEndpoint)"

    private fun valueFields(): List<Any?> = listOf(requestFacts, errorStatuses, manifestEndpoint, openApiEndpoint)
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

    /**
     * The query parameter of a state read of at least a version (review R4-Q, Q-13: read your writes), the version a
     * command's result names.
     */
    const val MIN_VERSION: String = "minVersion"

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
 * The JSON of the public port and of the HTTP clients: Wow's one configuration ([WowJson], decisions D105, D122), as
 * the cluster codec's. Spring's codecs are not used: responses are written as bytes. A decimal in a peer's results or
 * a rejection's extensions read back by a synchronous call (`wow-client`) keeps its exact
 * value, as the same result forwarded over the cluster channel does (review A5-6).
 */
internal object HttpJson {
    val mapper: JsonMapper = WowJson.builder().build()

    fun write(value: Any): ByteArray = mapper.writeValueAsBytes(value)

    fun writeString(value: Any): String = mapper.writeValueAsString(value)
}
