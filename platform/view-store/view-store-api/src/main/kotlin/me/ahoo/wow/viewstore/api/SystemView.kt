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

import com.fasterxml.jackson.annotation.JsonValue

/**
 * A view every user of a tenant's application reads, as `GET /view-store/system-views` answers it: one the service
 * configures ([SystemViewSource.CONFIGURED], read-only), or one stored as a view of the tenant `(platform)` under the
 * owner `(system)` ([SystemViewSource.STORED]), global to every tenant. Its revision is a hash of its content, whatever
 * its source; a stored one also has the aggregate [version] its writes expect (`If-Match`).
 *
 * Its [id] is a configured view's own or a stored view's aggregate id, never `system:…`, which is kept for the views a
 * host declares in code; its [config] is the engine's `ViewConfig`.
 */
data class SystemView(
    val id: String,
    val definitionId: String,
    val title: String,
    val kind: ViewKind,
    val revision: String,
    val config: Map<String, Any?>,
    val source: SystemViewSource = SystemViewSource.CONFIGURED,
    val version: Int? = null,
) {
    /** Always `system`: the engine reads the scope from it. */
    val scope: String
        get() = SYSTEM_SCOPE

    companion object {
        const val SYSTEM_SCOPE: String = "system"

        /** The id prefix kept for the system views a host declares in code. */
        const val CODE_SYSTEM_VIEW_PREFIX: String = "system:"
    }
}

/** Where a [SystemView] comes from. */
enum class SystemViewSource(@get:JsonValue val value: String) {
    /** `wow.view-store.system-views`: read-only. */
    CONFIGURED("configured"),

    /** A view stored under the tenant `(platform)` and the owner `(system)`, written through the view routes. */
    STORED("stored"),
}
