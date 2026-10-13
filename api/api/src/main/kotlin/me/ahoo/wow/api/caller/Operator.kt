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

package me.ahoo.wow.api.caller

/**
 * Whose name a fact is recorded in (decision D163; design 08 §4): the operator of a command and of the event stream
 * it commits, the primary record of an audit.
 *
 * - [id] is the opaque id of the authenticated principal ([Caller.subject]); a command nobody sent, one a framework
 *   component issues on its own, names the component as `system:<component>` ([system]).
 * - [tenantId] is the operator's own tenant, which may differ from the aggregate's.
 * - [crossScope] says the operator acted in another tenant or scope than its own, through `Wow-Scope` (decision D95).
 *
 * Nothing else about the principal is recorded: no display name (names change and are personal data; a reader
 * resolves the [id] against the identity system), no roles, policies or attributes. The log cannot be changed, so
 * what it holds must be safe to keep for good (decision D175).
 *
 * A command a saga derives, an `@OnRejected` compensation and a scheduled command keep the operator of the root
 * command; who triggered them is what their causation records.
 */
data class Operator(val id: String, val tenantId: String, val crossScope: Boolean = false) {
    init {
        require(id.isNotBlank()) { "An operator needs an id." }
        require(tenantId.isNotBlank()) { "An operator needs a tenant." }
    }

    companion object {
        /** The prefix of the id of a framework component that issues a command on its own. */
        const val SYSTEM_PREFIX: String = "system:"

        /** The operator of a command the framework [component] issues on its own, in [tenantId]. */
        @JvmStatic
        @JvmOverloads
        fun system(component: String, tenantId: String = Caller.DEFAULT_TENANT): Operator {
            require(component.isNotBlank()) { "A system operator names its component." }
            return Operator(SYSTEM_PREFIX + component, tenantId)
        }
    }
}
