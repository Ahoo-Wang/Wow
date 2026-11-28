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


package me.ahoo.wow.mongo.snapshot

import com.mongodb.MongoWriteException
import com.mongodb.client.MongoCollection
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.ReturnDocument
import com.mongodb.client.model.Updates
import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.wire.WireNames
import me.ahoo.wow.mongo.MongoFormat
import me.ahoo.wow.mongo.isDuplicateKey
import me.ahoo.wow.mongo.snapshot.MongoSnapshotStore.Companion.FORMAT
import me.ahoo.wow.mongo.snapshot.MongoSnapshotStore.Companion.ID
import me.ahoo.wow.mongo.snapshot.MongoSnapshotStore.Companion.OWNER_INDEX
import me.ahoo.wow.mongo.snapshot.MongoSnapshotStore.Companion.TABLES
import me.ahoo.wow.spi.Ddl
import me.ahoo.wow.spi.snapshot.SnapshotFill
import me.ahoo.wow.spi.snapshot.SnapshotTable
import org.bson.Document

/**
 * The registry of a [MongoSnapshotStore]'s collections, [TABLES] in [database]: one record a collection, keyed by its
 * name, claimed and marked as `SnapshotRebuildStore` says, each call blocking on the caller's thread.
 *
 * A collection, or its owner index ([OWNER_INDEX], which the store creates with it before the first claim records it),
 * missing under its record was removed ([checked]); its record says so from then on. Read before anything creates the
 * collection, the index tells it even after an insert recreated the collection, which MongoDB does for any write.
 */
internal class MongoSnapshotTables(private val database: MongoDatabase, private val ddl: Ddl) {
    /**
     * The registry, its format record claimed before this store first reads or writes it: a revision's claim may come
     * before any snapshot collection is made sure of, and its insert would create the registry without its record.
     */
    private val tables: MongoCollection<Document> by lazy {
        MongoFormat.claim(database, TABLES, FORMAT, ddl)
        database.getCollection(TABLES)
    }

    /**
     * The record of [structure]'s collection; one whose collection or owner index is missing, while this store manages
     * its structures, is recorded removed first. One index listing a call, which the maintenance makes once a round.
     */
    fun checked(structure: String): SnapshotTable? {
        val table = tables.find(Filters.eq(ID, structure)).first()?.let(::tableOf) ?: return null
        if (table.removed || !ddl.checks || indexed(database, structure)) {
            return table
        }
        tables.updateOne(Filters.eq(ID, structure), Updates.set(REMOVED, true))
        logger.warn {
            "The collection $structure, or its index $OWNER_INDEX, is missing under its record in $TABLES: the " +
                "snapshots it held were removed, and the store serves it no more."
        }
        return table.copyWith(removed = true)
    }

    /** Claims [structure]'s table as [MongoSnapshotStore.claim] says; its record after. */
    fun claim(structure: String, revision: Int, fill: SnapshotFill, lower: Boolean): SnapshotTable {
        try {
            tables.insertOne(
                Document(ID, structure).append(REVISION, revision).append(REBUILDING, revision != 1)
                    .append(BACKFILLED, false).append(FILL, WireNames.of(fill)),
            )
        } catch (failure: MongoWriteException) {
            if (!failure.isDuplicateKey()) {
                throw failure
            }
            val changes = if (lower) Filters.ne(REVISION, revision) else Filters.lt(REVISION, revision)
            tables.updateOne(
                Filters.and(Filters.eq(ID, structure), changes),
                Updates.combine(Updates.set(REVISION, revision), Updates.set(REBUILDING, true)),
            )
        }
        return tableOf(checkNotNull(tables.find(Filters.eq(ID, structure)).first()))
    }

    /** Marks [structure]'s table as [MongoSnapshotStore.mark] says; its record after, or null. */
    fun mark(structure: String, revision: Int, rebuilding: Boolean?, backfilled: Boolean?): SnapshotTable? {
        val filter = Filters.and(Filters.eq(ID, structure), Filters.eq(REVISION, revision))
        val changes = listOfNotNull(
            rebuilding?.let { Updates.set(REBUILDING, it) },
            backfilled?.let { Updates.set(BACKFILLED, it) },
        )
        val marked = if (changes.isEmpty()) {
            tables.find(filter).first()
        } else {
            tables.findOneAndUpdate(
                filter,
                Updates.combine(changes),
                FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER),
            )
        }
        return marked?.let(::tableOf)
    }

    private fun tableOf(document: Document) = SnapshotTable(
        revision = document.getInteger(REVISION),
        rebuilding = document.getBoolean(REBUILDING),
        backfilled = document.getBoolean(BACKFILLED),
        fill = checkNotNull(WireNames.entryOf<SnapshotFill>(document.getString(FILL))) {
            // By its wire name; no case is added within 10.x (SnapshotFill), so one this version does not know is
            // refused, naming it.
            "A snapshot table's fill ${document.getString(FILL)} is not one this version of Wow reads."
        },
        removed = document.getBoolean(REMOVED, false),
    )

    companion object {
        /** The store's own: these lines moved from it, and an operator's filter names it. */
        private val logger = KotlinLogging.logger(MongoSnapshotStore::class.java.name)

        /**
         * Whether [structure]'s table in [database] was removed under its record, read only: its record says so, or
         * the collection or its owner index is missing. What creates a snapshot collection beside the store asks it
         * first, so that it does not create one again.
         */
        fun removedUnder(database: MongoDatabase, structure: String): Boolean {
            val record = database.getCollection(TABLES).find(Filters.eq(ID, structure)).first() ?: return false
            return record.getBoolean(REMOVED, false) || !indexed(database, structure)
        }

        /** Whether [structure]'s collection has its owner index, which the store creates with it. */
        private fun indexed(database: MongoDatabase, structure: String): Boolean =
            database.getCollection(structure).listIndexes().any { it.getString(NAME) == OWNER_INDEX }

        private const val NAME = "name"
        private const val REVISION = "revision"
        private const val REBUILDING = "rebuilding"
        private const val BACKFILLED = "backfilled"
        private const val FILL = "fill"
        private const val REMOVED = "removed"
    }
}
