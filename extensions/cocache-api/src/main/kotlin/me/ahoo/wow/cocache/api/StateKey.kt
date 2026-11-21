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

package me.ahoo.wow.cocache.api

/**
 * The key of one aggregate's state in a [StateViewCache]: the aggregate's [tenantId] and [id], its identity within
 * its type. The type is the cache's: one cache per aggregate type.
 */
class StateKey(val tenantId: String, val id: String) {
    init {
        require(tenantId.isNotBlank()) { "A state key needs a tenant." }
        require(id.isNotBlank()) { "A state key needs an id." }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is StateKey && tenantId == other.tenantId && id == other.id

    override fun hashCode(): Int = 31 * tenantId.hashCode() + id.hashCode()

    /**
     * `<tenantId>/<id>` as they are, to read in a message. The cache stores the state under another text, the tenant
     * and the id each escaped as an aggregate ref escapes them, so two keys never share one entry.
     */
    override fun toString(): String = "$tenantId/$id"
}
