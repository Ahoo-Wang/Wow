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

package me.ahoo.wow.elasticsearch

import me.ahoo.wow.api.aggregate.AggregateRef
import java.security.MessageDigest

/**
 * The names of the indices a context's storage takes: the rule PostgreSQL's tables and MongoDB's
 * collections follow, so one context's storage has the same names on every backend, behind a store's index [prefix]
 * when it has one (`wow.stores.<name>.index-prefix`, for a cluster several services or environments share).
 */
internal class ElasticsearchNames(prefix: String = "") {
    private val prefix: String = part(prefix).let { if (it.isEmpty()) "" else "${it}_" }

    /** The index of [aggregate]'s snapshots in [context]: `[<prefix>_]wow_snapshot__<context>__<aggregate>`. */
    fun snapshots(context: String, aggregate: String): String = prefix + of(SNAPSHOT, context, aggregate)

    /**
     * The index of [aggregate]'s event streams in [context], written by the event search Sink:
     * `[<prefix>_]wow_events__<context>__<aggregate>`.
     */
    fun events(context: String, aggregate: String): String = prefix + of(EVENTS, context, aggregate)

    /** The index of the snapshot tables' records, one document per snapshot index. */
    val snapshotTables: String get() = prefix + SNAPSHOT_TABLE

    companion object {
        const val SNAPSHOT = "wow_snapshot"
        const val EVENTS = "wow_events"
        const val SNAPSHOT_TABLE = "wow_snapshot_table"

        /** The longest name, PostgreSQL's identifier limit, kept for names alike on every backend. */
        const val MAX_LENGTH = 63
        private const val HASH_LENGTH = 8
        private val OUTSIDE = Regex("[^a-z0-9_]")

        /** [name] lower-cased, each character outside `[a-z0-9_]` replaced by `_`. */
        fun part(name: String): String = name.lowercase().replace(OUTSIDE, "_")

        /** `<kind>__<context>__<aggregate>`, each part as [part] makes it, within [MAX_LENGTH] as [limited] makes it. */
        fun of(kind: String, context: String, aggregate: String): String =
            limited(kind + "__" + part(context) + "__" + part(aggregate))

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

        /**
         * The document id of [ref]'s snapshot in its index: the tenant's length, the tenant and the id
         * (`<length>:<tenant>:<id>`), so no two refs share one whatever characters their parts hold.
         */
        fun documentId(ref: AggregateRef): String = documentId(ref.tenantId, ref.id)

        fun documentId(tenantId: String, id: String): String = "${tenantId.length}:$tenantId:$id"

        /**
         * The document id of [ref]'s event stream at [version] in its events index: its snapshot's id and the version
         * (`<length>:<tenant>:<id>@<version>`), the commit's identity, so a stream written again replaces nothing.
         */
        fun streamId(ref: AggregateRef, version: Int): String = documentId(ref) + "@" + version
    }
}
