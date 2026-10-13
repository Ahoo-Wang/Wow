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

package me.ahoo.wow.postgres.snapshot

import java.security.MessageDigest

/** Names of the database objects this module creates. */
internal object PostgresIdentifiers {
    /** PostgreSQL's identifier limit, in bytes; the names here are ASCII. */
    const val MAX_LENGTH = 63
    private const val HASH_LENGTH = 8

    /**
     * [name] within [MAX_LENGTH]: a longer one keeps its first 54 characters and appends `_` and 8 hex digits of the
     * SHA-256 of the whole name (the rule `manifest-tools` derives index names by), so two long names that share a
     * prefix stay distinct, where PostgreSQL would silently truncate both to the same identifier.
     */
    fun limited(name: String): String {
        if (name.length <= MAX_LENGTH) return name
        val digest = MessageDigest.getInstance("SHA-256").digest(name.toByteArray())
        val hash = digest.joinToString("") { "%02x".format(it) }.take(HASH_LENGTH)
        return name.take(MAX_LENGTH - HASH_LENGTH - 1).trimEnd('_') + "_" + hash
    }
}
