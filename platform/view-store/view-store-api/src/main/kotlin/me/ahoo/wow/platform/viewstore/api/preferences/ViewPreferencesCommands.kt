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

package me.ahoo.wow.platform.viewstore.api.preferences

import me.ahoo.wow.api.annotation.AllowCreate
import me.ahoo.wow.api.annotation.Summary

/**
 * Sets the caller's preferences for one definition in the request's application. The preferences' id is derived on
 * the server from the owner, the application and the definition, so the route names none:
 * `POST /view-store/view_preferences/set-view-preferences`. The expected version `If-Match: "0"` is "never written".
 */
@AllowCreate
@Summary("Set the view preferences of a definition")
data class SetViewPreferences(
    val definitionId: String,
    val order: List<String> = emptyList(),
    val defaultInstanceId: String? = null,
    val autoRun: Boolean? = null,
    val lastTabs: Map<String, String>? = null,
)

@Summary("A definition's view preferences were set")
data class ViewPreferencesSet(
    val definitionId: String,
    val appId: String,
    val order: List<String>,
    val defaultInstanceId: String?,
    val autoRun: Boolean?,
    val lastTabs: Map<String, String>?,
)
