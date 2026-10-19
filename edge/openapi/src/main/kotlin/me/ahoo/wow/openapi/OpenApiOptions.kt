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
 * - [datasets] documents the dataset routes (`/wow/dataset`, decision D145), which a node serves when it has a dataset
 *   backend; [manifestEndpoint] `GET /wow/manifest`; [openApiEndpoint] `GET /wow/openapi`.
 * - [errorStatuses] are the statuses the host configures for error codes (`wow.http.errors.[<code>].status`, decision
 *   D102), which win over the Manifest's tables as they do at the edge.
 * - [securitySchemes] ([withSecuritySchemes]) are how a caller presents its identity, which the host fills from its
 *   caller resolver (decision D233 ③).
 *
 * Fields may be added (decision D216): pass the constructor's arguments by name.
 */
class OpenApiOptions private constructor(
    val title: String,
    val servers: List<String>,
    val datasets: Boolean,
    val manifestEndpoint: Boolean,
    val openApiEndpoint: Boolean,
    val errorStatuses: Map<String, Int>,
    /**
     * The ways a caller presents its identity, by scheme name (decision D233 ③): the document's
     * `components.securitySchemes`, and its global `security`, any one of them. None by default.
     */
    val securitySchemes: Map<String, OpenApiSecurityScheme>,
) {
    /** The default options; a constructor of its own, as the JVM has it for a class whose arguments all default. */
    constructor() : this(DEFAULT_TITLE)

    constructor(
        title: String = DEFAULT_TITLE,
        servers: List<String> = emptyList(),
        datasets: Boolean = true,
        manifestEndpoint: Boolean = true,
        openApiEndpoint: Boolean = true,
        errorStatuses: Map<String, Int> = emptyMap(),
    ) : this(title, servers, datasets, manifestEndpoint, openApiEndpoint, errorStatuses, emptyMap())

    /** These options with [schemes] as the [securitySchemes]. */
    fun withSecuritySchemes(schemes: Map<String, OpenApiSecurityScheme>): OpenApiOptions =
        OpenApiOptions(title, servers, datasets, manifestEndpoint, openApiEndpoint, errorStatuses, schemes.toMap())

    override fun equals(other: Any?): Boolean =
        this === other || other is OpenApiOptions && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "OpenApiOptions(title=$title, servers=$servers, datasets=$datasets, " +
            "manifestEndpoint=$manifestEndpoint, openApiEndpoint=$openApiEndpoint, errorStatuses=$errorStatuses, " +
            "securitySchemes=$securitySchemes)"

    private fun valueFields(): List<Any?> = listOf(
        title,
        servers,
        datasets,
        manifestEndpoint,
        openApiEndpoint,
        errorStatuses,
        securitySchemes,
    )

    companion object {
        /** The title of a document whose Manifest names no service. */
        const val DEFAULT_TITLE: String = "Wow service"
    }
}
