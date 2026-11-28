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


package me.ahoo.wow.elasticsearch.snapshot

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.elasticsearch.ElasticsearchIndices
import me.ahoo.wow.elasticsearch.ElasticsearchNames
import me.ahoo.wow.spi.Ddl
import java.util.concurrent.ConcurrentHashMap

/**
 * The indices of an [ElasticsearchSnapshotStore] of [context]: each aggregate type's ([of]) and the registry of the
 * tables ([tables]), each made sure of on its first use, as [ddl] says.
 *
 * An aggregate type's index missing under its table's record was removed ([removedUnder] says so, and records it):
 * it is created no more, and [of] answers null for it from then on (`SnapshotRebuildStore`'s **A table removed under
 * its record**). One with no record yet is created, as at a first claim.
 */
internal class ElasticsearchSnapshotIndexes(
    private val indices: ElasticsearchIndices,
    private val names: ElasticsearchNames,
    private val context: String,
    private val ddl: Ddl,
    /** Whether [aggregate]'s table has a record, read when its index is missing: true records it removed. */
    private val removedUnder: suspend (aggregate: String) -> Boolean,
) {
    /** The indices whose definition this store has made sure of. */
    private val ensured = ConcurrentHashMap.newKeySet<String>()

    /** The aggregate types whose index was removed under their table's record. */
    private val removed = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var tablesEnsured = false

    /** [aggregate]'s snapshot index, made sure of as the DDL mode says; null once it was removed under its record. */
    suspend fun of(aggregate: String): String? {
        val index = names.snapshots(context, aggregate)
        if (aggregate in removed) {
            return null
        }
        if (index in ensured) {
            return index
        }
        val found = indices.ensure(
            index,
            ElasticsearchSnapshotIndex.KIND,
            ElasticsearchSnapshotIndex.FORMAT,
            ElasticsearchSnapshotIndex.DEFINITION,
            ddl,
        ) { removedUnder(aggregate) }
        if (!found) {
            removed += aggregate
            return null
        }
        ensured += index
        return index
    }

    /** [aggregate]'s table is recorded removed: its index is served no more, whatever is behind its alias now. */
    fun removed(aggregate: String) {
        removed += aggregate
        ensured -= names.snapshots(context, aggregate)
    }

    /** The snapshot table registry's index, made sure of as the DDL mode says. */
    suspend fun tables(): String {
        val index = names.snapshotTables
        if (!tablesEnsured) {
            indices.ensure(
                index,
                ElasticsearchSnapshotTables.TABLE_KIND,
                ElasticsearchSnapshotTables.TABLE_FORMAT,
                ElasticsearchSnapshotTables.TABLE_DEFINITION,
                ddl,
            )
            tablesEnsured = true
        }
        return index
    }

    /**
     * Forgets that [index] was made sure of: its alias is gone (deleted under a running node, or never created under
     * `none`), and the next use makes sure of it again, which under `auto` creates the pair afresh, empty, when its
     * table has no record yet, and otherwise finds it removed.
     */
    fun forget(index: String) {
        if (index == names.snapshotTables) tablesEnsured = false else ensured -= index
        if (ddl.creates) {
            logger.warn {
                "The Elasticsearch alias [$index] is missing, its index deleted under a running node: a snapshot index " +
                    "whose table has a record is not created again, and what it held comes back only from the log."
            }
        }
    }

    private companion object {
        /** The store's own: these lines moved from it, and an operator's filter names it. */
        val logger = KotlinLogging.logger(ElasticsearchSnapshotStore::class.java.name)
    }
}
