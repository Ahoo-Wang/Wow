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

package me.ahoo.wow.query.invocation

import java.util.Collections

class QueryAuthorityView(
    val subjectId: String?,
    val tenantId: String?,
    val ownerId: String?,
    spaceIds: Set<String>,
    permissions: Set<String>
) {
    val spaceIds: Set<String> = immutableSet(spaceIds)
    val permissions: Set<String> = immutableSet(permissions)

    init {
        require(subjectId == null || subjectId.isNotBlank()) { "subjectId cannot be blank." }
        require(tenantId == null || tenantId.isNotBlank()) { "tenantId cannot be blank." }
        require(ownerId == null || ownerId.isNotBlank()) { "ownerId cannot be blank." }
        require(this.spaceIds.none(String::isBlank)) { "spaceIds cannot contain blank values." }
        require(this.permissions.none(String::isBlank)) { "permissions cannot contain blank values." }
    }

    fun copy(
        subjectId: String? = this.subjectId,
        tenantId: String? = this.tenantId,
        ownerId: String? = this.ownerId,
        spaceIds: Set<String> = this.spaceIds,
        permissions: Set<String> = this.permissions
    ): QueryAuthorityView = QueryAuthorityView(subjectId, tenantId, ownerId, spaceIds, permissions)

    operator fun component1(): String? = subjectId

    operator fun component2(): String? = tenantId

    operator fun component3(): String? = ownerId

    operator fun component4(): Set<String> = spaceIds

    operator fun component5(): Set<String> = permissions

    override fun equals(other: Any?): Boolean = other is QueryAuthorityView &&
        subjectId == other.subjectId && tenantId == other.tenantId && ownerId == other.ownerId &&
        spaceIds == other.spaceIds && permissions == other.permissions

    override fun hashCode(): Int {
        var result = subjectId?.hashCode() ?: 0
        result = 31 * result + (tenantId?.hashCode() ?: 0)
        result = 31 * result + (ownerId?.hashCode() ?: 0)
        result = 31 * result + spaceIds.hashCode()
        return 31 * result + permissions.hashCode()
    }

    override fun toString(): String = "QueryAuthorityView(<redacted>)"

    private fun <T> immutableSet(source: Set<T>): Set<T> =
        Collections.unmodifiableSet(LinkedHashSet(source))
}
