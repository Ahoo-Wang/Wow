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
 * What the document says beyond the Manifest: the routes the host serves or not, and its error statuses.
 *
 * - [title] names a service the Manifest does not ([me.ahoo.wow.manifest.Manifest.service], a fragment's).
 * - [servers] are the base URLs of the document's `servers`; none by default, so a client reads the routes relative
 *   to where it fetched the document.
 * - [datasets] documents the dataset routes (`/wow/dataset`), which a node serves when it has a dataset
 *   backend; [manifestEndpoint] `GET /wow/manifest`; [openApiEndpoint] `GET /wow/openapi`.
 * - [errorStatuses] are the statuses the host configures for error codes (`wow.http.errors.[<code>].status`),
 *   which win over the Manifest's tables as they do at the edge.
 * - [securitySchemes] ([withSecuritySchemes]) are how a caller presents its identity, which the host fills from its
 *   caller resolver: the parts of one identity, presented together.
 * - What the host adds to the routes, which the Manifest does not say: the [requestFacts] (`wow.http.request-facts`)
 *   and the [requestIdHeaders] beside `Wow-Request-Id` (CoSec's `CoSec-Request-Id`), header parameters of every
 *   command; and the [filterCodes] a filter before the routes answers (CoSec's 429 `wow.request.rate-limited` and
 *   503 `wow.auth.unavailable`), listed on every operation.
 *
 * Fields may be added: pass the constructor's arguments by name.
 */
class OpenApiOptions(
    val title: String = DEFAULT_TITLE,
    val servers: List<String> = emptyList(),
    val datasets: Boolean = true,
    val manifestEndpoint: Boolean = true,
    val openApiEndpoint: Boolean = true,
    val errorStatuses: Map<String, Int> = emptyMap(),
    /**
     * How a caller presents its identity, by scheme name: the document's `components.securitySchemes`, and its
     * global `security`, one requirement naming them all, since a caller presents them together (the development
     * callers' `Wow-*` headers). None by default.
     */
    val securitySchemes: Map<String, OpenApiSecurityScheme> = emptyMap(),
    val requestFacts: Set<String> = emptySet(),
    val requestIdHeaders: List<String> = emptyList(),
    val filterCodes: List<String> = emptyList(),
) {
    /** These options with [schemes] as the [securitySchemes]. */
    fun withSecuritySchemes(schemes: Map<String, OpenApiSecurityScheme>): OpenApiOptions = OpenApiOptions(
        title,
        servers,
        datasets,
        manifestEndpoint,
        openApiEndpoint,
        errorStatuses,
        schemes.toMap(),
        requestFacts,
        requestIdHeaders,
        filterCodes,
    )

    override fun equals(other: Any?): Boolean =
        this === other || other is OpenApiOptions && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "OpenApiOptions(title=$title, servers=$servers, datasets=$datasets, " +
            "manifestEndpoint=$manifestEndpoint, openApiEndpoint=$openApiEndpoint, errorStatuses=$errorStatuses, " +
            "securitySchemes=$securitySchemes, requestFacts=$requestFacts, requestIdHeaders=$requestIdHeaders, " +
            "filterCodes=$filterCodes)"

    private fun valueFields(): List<Any?> = listOf(
        title,
        servers,
        datasets,
        manifestEndpoint,
        openApiEndpoint,
        errorStatuses,
        securitySchemes,
        requestFacts,
        requestIdHeaders,
        filterCodes,
    )

    companion object {
        /** The title of a document whose Manifest names no service. */
        const val DEFAULT_TITLE: String = "Wow service"
    }
}
