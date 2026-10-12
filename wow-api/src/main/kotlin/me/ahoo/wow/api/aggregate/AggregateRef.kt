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

    override fun toString(): String = "$context.$name"
}

/**
 * The identity of one aggregate instance: its [type], its tenant and its id. Decided once, at admission; every unique
 * key in the log includes the tenant (V10 axiom A6), so two tenants may use the same [id] without meeting.
 */
data class AggregateRef(val type: AggregateType, val tenantId: String, val id: String) {
    init {
        require(tenantId.isNotBlank()) { "An aggregate ref needs a tenant." }
        require(id.isNotBlank()) { "An aggregate ref needs an id." }
    }

    override fun toString(): String = "$type/$tenantId/$id"
}
