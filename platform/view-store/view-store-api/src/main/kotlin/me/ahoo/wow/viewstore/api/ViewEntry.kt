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

package me.ahoo.wow.viewstore.api

/**
 * One view a caller reads by id, whatever its audience, as `GET /view-store/views/{id}` answers it: a personal view
 * of the caller, a shared view of its tenant, or a system view ([SystemView]) of its tenant and application.
 *
 * @property id a saved view's aggregate id, or a configured system view's own.
 * @property definitionId the definition it is a view of.
 * @property title its title.
 * @property kind its config's kind.
 * @property scope its audience.
 * @property revision what a client compares to see that the view changed: a saved view's [version] as text, a system
 *   view's content hash.
 * @property config the engine's `ViewConfig`.
 * @property version the aggregate version a write of the view expects (`If-Match`); null for a configured system view,
 *   which is no aggregate.
 * @property source where a system view comes from; null for a personal or shared view.
 */
data class ViewEntry(
    val id: String,
    val definitionId: String,
    val title: String,
    val kind: ViewKind,
    val scope: ViewAudience,
    val revision: String,
    val config: Map<String, Any?>,
    val version: Int? = null,
    val source: SystemViewSource? = null,
)
