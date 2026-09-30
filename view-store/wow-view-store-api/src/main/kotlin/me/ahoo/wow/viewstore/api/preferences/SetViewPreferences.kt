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

package me.ahoo.wow.viewstore.api.preferences

import me.ahoo.wow.api.annotation.AllowCreate
import me.ahoo.wow.api.annotation.CommandRoute
import me.ahoo.wow.api.annotation.Summary

/**
 * Sets one owner's preferences for one definition. The preferences' id is derived on the server from the owner,
 * the application and the definition, so this command has no generated route: the view store's preferences route
 * dispatches it. The expected version `0` is "never written".
 */
@Summary("Set the view preferences of a definition")
@AllowCreate
@CommandRoute(enabled = false)
data class SetViewPreferences(
    val definitionId: String,
    val order: List<String> = emptyList(),
    val defaultInstanceId: String? = null,
    val autoRun: Boolean? = null,
    val lastTabs: Map<String, String>? = null,
)

data class ViewPreferencesSet(
    val definitionId: String,
    val appId: String,
    val order: List<String>,
    val defaultInstanceId: String?,
    val autoRun: Boolean?,
    val lastTabs: Map<String, String>?,
)
