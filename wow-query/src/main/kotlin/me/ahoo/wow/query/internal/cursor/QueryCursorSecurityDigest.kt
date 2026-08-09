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

package me.ahoo.wow.query.internal.cursor

import me.ahoo.wow.query.internal.policy.QueryAuthority
import me.ahoo.wow.query.internal.policy.QueryExecutionContext
import me.ahoo.wow.query.internal.policy.QueryOwnerGrant
import me.ahoo.wow.query.internal.policy.QuerySpaceGrant
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

internal object QueryCursorSecurityDigest {
    fun compute(context: QueryExecutionContext): QueryCursorSecurityContextDigest {
        val bytes = ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { output -> output.writeContext(context) }
            buffer.toByteArray()
        }
        return QueryCursorSecurityContextDigest(MessageDigest.getInstance("SHA-256").digest(bytes).toHex())
    }

    private fun DataOutputStream.writeContext(context: QueryExecutionContext) {
        writeUtf8("wow.query.cursor.security.v1")
        writeUtf8(context.purpose.value)
        writeUtf8(context.executionMode.name)
        writeUtf8(context.validationMode.name)
        writeNullableUtf8(context.resourceScope.tenantId)
        writeNullableUtf8(context.resourceScope.ownerId)
        writeNullableUtf8(context.resourceScope.spaceId)
        writeAuthority(context.authority)
    }

    private fun DataOutputStream.writeAuthority(authority: QueryAuthority) {
        when (authority) {
            is QueryAuthority.Subject -> writeSubject(authority)
            is QueryAuthority.Service -> writeService(authority)
            is QueryAuthority.System -> {
                writeByte(SYSTEM_AUTHORITY)
                writeUtf8(authority.principalId)
                writeUtf8(authority.justification)
            }

            is QueryAuthority.Legacy -> {
                writeByte(LEGACY_AUTHORITY)
                writeUtf8(authority.grant.callerId.value)
                writeUtf8(authority.grant.target.namedAggregate.contextName)
                writeUtf8(authority.grant.target.namedAggregate.aggregateName)
                writeUtf8(authority.grant.target.documentKind.name)
                writeUtf8(authority.grant.purpose.value)
                writeUtf8(authority.grant.executionMode.name)
                writeNullableUtf8(authority.grant.resourceScope.tenantId)
                writeNullableUtf8(authority.grant.resourceScope.ownerId)
                writeNullableUtf8(authority.grant.resourceScope.spaceId)
            }
        }
    }

    private fun DataOutputStream.writeSubject(authority: QueryAuthority.Subject) {
        writeByte(SUBJECT_AUTHORITY)
        writeUtf8(authority.subjectId)
        writeUtf8(authority.tenantId)
        when (val owner = authority.ownerGrant) {
            QueryOwnerGrant.Unrestricted -> writeByte(UNRESTRICTED_GRANT)
            is QueryOwnerGrant.Only -> {
                writeByte(ONLY_GRANT)
                writeUtf8(owner.ownerId)
            }
        }
        when (val space = authority.spaceGrant) {
            QuerySpaceGrant.Unrestricted -> writeByte(UNRESTRICTED_GRANT)
            QuerySpaceGrant.DenyAll -> writeByte(DENY_ALL_GRANT)
            is QuerySpaceGrant.AllowList -> {
                writeByte(ALLOW_LIST_GRANT)
                writeInt(space.spaceIds.size)
                space.spaceIds.sorted().forEach { spaceId -> writeUtf8(spaceId) }
            }
        }
    }

    private fun DataOutputStream.writeService(authority: QueryAuthority.Service) {
        writeByte(SERVICE_AUTHORITY)
        writeUtf8(authority.serviceId)
        writeUtf8(authority.tenantId)
        writeInt(authority.purposes.size)
        authority.purposes.sortedBy { purpose -> purpose.value }.forEach { purpose -> writeUtf8(purpose.value) }
    }

    private fun DataOutputStream.writeNullableUtf8(value: String?) {
        writeBoolean(value != null)
        value?.let { current -> writeUtf8(current) }
    }

    private fun DataOutputStream.writeUtf8(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte) }

    private const val SUBJECT_AUTHORITY = 0
    private const val SERVICE_AUTHORITY = 1
    private const val SYSTEM_AUTHORITY = 2
    private const val LEGACY_AUTHORITY = 3
    private const val UNRESTRICTED_GRANT = 0
    private const val ONLY_GRANT = 1
    private const val DENY_ALL_GRANT = 1
    private const val ALLOW_LIST_GRANT = 2
}
