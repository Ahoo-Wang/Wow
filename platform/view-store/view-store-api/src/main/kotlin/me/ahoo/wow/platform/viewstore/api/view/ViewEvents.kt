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

package me.ahoo.wow.platform.viewstore.api.view

import me.ahoo.wow.api.annotation.Summary
import me.ahoo.wow.platform.viewstore.api.ViewAudience
import me.ahoo.wow.platform.viewstore.api.ViewKind

@Summary("A view was saved")
data class ViewCreated(
    val definitionId: String,
    val title: String,
    val kind: ViewKind,
    val audience: ViewAudience,
    val appId: String,
    val config: Map<String, Any?>,
)

@Summary("A view's config was overwritten")
data class ViewSaved(val kind: ViewKind, val config: Map<String, Any?>)

@Summary("A view was renamed")
data class ViewRenamed(val title: String)

@Summary("A view was made shared; its owner becomes (shared)")
data object ViewShared

@Summary("A shared view was made personal to toOwnerId, who becomes its owner")
data class ViewClaimed(val toOwnerId: String)
