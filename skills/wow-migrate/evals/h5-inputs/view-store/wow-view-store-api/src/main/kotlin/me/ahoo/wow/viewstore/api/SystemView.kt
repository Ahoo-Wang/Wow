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
import tools.jackson.databind.node.ObjectNode

/**
 * A view every user of a tenant's application reads, served under the shared owner: one the server configures
 * ([SystemViewSource.CONFIGURED], read-only), or one stored as a view of the tenant `(platform)` under the owner `(system)`
 * ([SystemViewSource.STORED]), global to every tenant. Its id never starts with `system:` (kept for views declared in code), and its revision
 * is a hash of its content, whatever its source.
 *
 * @property id its id: a configured view's own, a stored view's aggregate id.
 * @property definitionId the definition it is a view of.
 * @property title its title.
 * @property kind its kind, `config.kind`.
 * @property revision a hash of its definition, title and config.
 * @property config the engine's `ViewConfig`.
 * @property source where it comes from.
 * @property version the stored view's aggregate version, which a write through the view routes expects
 * (`Command-Aggregate-Version`); `null` for a configured view.
 */
data class SystemView(
    val id: String,
    val definitionId: String,
    val title: String,
    val kind: ViewKind,
    val revision: String,
    val config: ObjectNode,
    val source: SystemViewSource = SystemViewSource.CONFIGURED,
    val version: Int? = null,
) {
    /** Always `system`: the engine reads the scope from it. */
    val scope: String
        get() = SYSTEM_SCOPE

    companion object {
        const val SYSTEM_SCOPE = "system"

        /** The id prefix kept for system views declared in code. */
        const val CODE_SYSTEM_VIEW_PREFIX = "system:"
    }
}

/** Where a [SystemView] comes from. */
enum class SystemViewSource(@get:JsonValue val value: String) {
    /** `wow.view-store.system-views`, or a host's own provider: read-only. */
    CONFIGURED("configured"),

    /** A view stored under the tenant `(platform)` and the owner `(system)`, written through the view routes there. */
    STORED("stored"),
}
