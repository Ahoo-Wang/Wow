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
 */
data class OpenApiOptions(
    val title: String = DEFAULT_TITLE,
    val servers: List<String> = emptyList(),
    val datasets: Boolean = true,
    val manifestEndpoint: Boolean = true,
    val openApiEndpoint: Boolean = true,
    val errorStatuses: Map<String, Int> = emptyMap(),
) {
    companion object {
        /** The title of a document whose Manifest names no service. */
        const val DEFAULT_TITLE: String = "Wow service"
    }
}
