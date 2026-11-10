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

package me.ahoo.wow.api.aggregate

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.naming.Escapes

/**
 * The kind of an aggregate: the bounded context it belongs to and its name within it. It is a name, not a JVM class.
 */
data class AggregateType(val context: String, val name: String) {
    init {
        require(context.isNotBlank()) { "An aggregate type needs a context." }
        require(name.isNotBlank()) { "An aggregate type needs a name." }
    }

    /** Refers to the instance [id] of this type under [tenantId]. */
    fun ref(tenantId: String, id: String): AggregateRef = AggregateRef(this, tenantId, id)

    /**
     * Refers to a new instance of this type under [tenantId] whose id is not chosen yet ([AggregateRef.UNASSIGNED]):
     * the target of a creation command that leaves its id to admission.
     */
    fun unassigned(tenantId: String): AggregateRef = AggregateRef(this, tenantId, AggregateRef.UNASSIGNED)

    /** Computed once: a type keys the node's maps on every command's path. */
    private val hash = 31 * context.hashCode() + name.hashCode()

    override fun hashCode(): Int = hash

    override fun toString(): String = "$context.$name"
}

/**
 * The identity of one aggregate instance: its [type], its tenant and its id. Decided once, at admission; every unique
 * key in the log includes the tenant (axiom A6), so two tenants may use the same [id] without meeting.
 *
 * A creation command may name no id yet, its ref's id [UNASSIGNED]: admission gives it the id of the
 * aggregate its request already created, or a new one from the aggregate type's generator. Committed ids never change.
 */
data class AggregateRef(val type: AggregateType, val tenantId: String, val id: String) {
    init {
        require(tenantId.isNotBlank()) { "An aggregate ref needs a tenant." }
        require(id.isNotBlank()) { "An aggregate ref needs an id." }
    }

    /** Whether the id is chosen: not [UNASSIGNED]. */
    val isAssigned: Boolean get() = id != UNASSIGNED

    /** Computed once: a ref keys the state cache, the lanes and the waits on every command's path. */
    private val hash = 31 * (31 * type.hashCode() + tenantId.hashCode()) + id.hashCode()

    override fun hashCode(): Int = hash

    /**
     * `context.aggregate/tenant/id`, each name with its separators and `%` percent-escaped ([Escapes]): `%` and
     * `/` in each, and `.` in the context. A ref whose names hold none of them reads as they are, and
     * no two refs share a text, so the published texts built on it (an envelope's id, a topic message's key, an
     * execution's invocation key) name one aggregate each.
     */
    override fun toString(): String =
        "${Escapes.escaped(type.context, CONTEXT_RESERVED)}.${Escapes.escaped(type.name, RESERVED)}/" +
            "${Escapes.escaped(tenantId, RESERVED)}/${Escapes.escaped(id, RESERVED)}"

    companion object {
        /** The id of a ref whose id admission has not chosen yet. */
        const val UNASSIGNED: String = "(none)"

        private const val RESERVED = "%/"
        private const val CONTEXT_RESERVED = "%/."
        private const val PARTS = 3

        /**
         * The ref whose text ([toString]) is [text]; null when [text] is no such text: not three parts, a type
         * without a `.`, a malformed escape, a blank name, or a text the ref would not write itself.
         */
        @InternalWowApi
        @JvmStatic
        fun parse(text: String): AggregateRef? {
            val parts = text.split('/').takeIf { it.size == PARTS } ?: return null
            val dot = parts[0].indexOf('.').takeIf { it > 0 } ?: return null
            val context = Escapes.unescaped(parts[0].substring(0, dot)) ?: return null
            val name = Escapes.unescaped(parts[0].substring(dot + 1)) ?: return null
            val tenantId = Escapes.unescaped(parts[1]) ?: return null
            val id = Escapes.unescaped(parts[2]) ?: return null
            if (listOf(context, name, tenantId, id).any(String::isBlank)) {
                return null
            }
            return AggregateType(context, name).ref(tenantId, id).takeIf { it.toString() == text }
        }
    }
}
