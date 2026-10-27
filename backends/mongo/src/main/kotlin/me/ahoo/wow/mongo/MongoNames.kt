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

package me.ahoo.wow.mongo

import java.security.MessageDigest

/**
 * The names of the collections a context's storage takes: the rule PostgreSQL's tables follow,
 * so one context's storage has the same names on either backend.
 */
internal object MongoNames {
    /** The longest name, PostgreSQL's identifier limit. */
    const val MAX_LENGTH = 63
    private const val HASH_LENGTH = 8
    private val OUTSIDE = Regex("[^a-z0-9_]")

    /** [name] lower-cased, each character outside `[a-z0-9_]` replaced by `_`. */
    fun part(name: String): String = name.lowercase().replace(OUTSIDE, "_")

    /** `<prefix>__<context>__<aggregate>`, each part as [part] makes it, within [MAX_LENGTH] as [limited] makes it. */
    fun of(prefix: String, context: String, aggregate: String): String =
        limited(prefix + "__" + part(context) + "__" + part(aggregate))

    /**
     * [name] within [MAX_LENGTH]: a longer one is cut and gets `_` and the first 8 hex digits of the SHA-256 of the
     * whole name, so two long names that share a prefix stay distinct.
     */
    fun limited(name: String): String {
        if (name.length <= MAX_LENGTH) return name
        val digest = MessageDigest.getInstance("SHA-256").digest(name.toByteArray())
        val hash = digest.joinToString("") { "%02x".format(it) }.take(HASH_LENGTH)
        return name.take(MAX_LENGTH - HASH_LENGTH - 1).trimEnd('_') + "_" + hash
    }
}
