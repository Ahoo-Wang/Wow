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

import tools.jackson.databind.node.ObjectNode

/**
 * A read-only view the server configures for a tenant, an application and a definition. It hangs under the shared
 * owner; its id never starts with `system:` (kept for views declared in code), and its revision is a hash of its
 * content.
 */
data class SystemView(
    val id: String,
    val definitionId: String,
    val title: String,
    val kind: ViewKind,
    val revision: String,
    val config: ObjectNode,
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
