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

import com.mongodb.MongoWriteException
import com.mongodb.client.MongoCollection
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import me.ahoo.wow.spi.Ddl
import org.bson.Document
import org.bson.conversions.Bson

/**
 * The format versions of stored structures: one document per structure (a log or a snapshot
 * collection) in [COLLECTION], keyed by the structure's name and recorded by the collection, not by each document.
 * `createSchema` claims its structure's version before creating it: the first claim records it, a structure recorded
 * under another version is refused, and so is a collection that exists without a record (an earlier, incompatible
 * build created it), so this version of Wow never reads or writes a layout it does not know. An incompatible layout
 * changes only in a major version, through the migration tool; adding an optional field does not change the
 * version.
 */
internal object MongoFormat {
    /**
     * Makes sure of [structure] as [ddl] says ([Ddl.ensure]): creating it [record]s it, validating it checks its
     * record ([validate]).
     */
    fun claim(database: MongoDatabase, structure: String, version: Int, ddl: Ddl) {
        val bounded = MongoTimeouts.ddl(database)
        ddl.ensure(structure, create = { record(bounded, structure, version) }) {
            validate(bounded, structure, version)
        }
    }

    /** Whether [structure] is recorded; refuses it when it is recorded at another [version]. */
    fun validate(database: MongoDatabase, structure: String, version: Int): Boolean {
        val stored = database.getCollection(COLLECTION).find(Filters.eq(ID, structure)).first()?.getInteger(VERSION)
            ?: return false
        check(stored == version) {
            "$structure is stored in format $stored; this version of Wow reads format $version. " +
                "Migrate it with the migration tool rather than opening it."
        }
        return true
    }

    /**
     * Makes sure of the index [options] names on [collection] as [ddl] says ([Ddl.ensure]): creating it is
     * idempotent, validating it checks that the collection has it.
     */
    fun index(collection: MongoCollection<Document>, keys: Bson, options: IndexOptions, ddl: Ddl) {
        val bounded = MongoTimeouts.ddl(collection)
        ddl.ensure(
            "The index ${options.name} of ${collection.namespace.collectionName}",
            create = { bounded.createIndex(keys, options) },
        ) {
            options.name in bounded.listIndexes().mapNotNull { it.getString("name") }.toSet()
        }
    }

    /** The collection of format versions, shared by every structure in the database. */
    const val COLLECTION = "wow_format"

    private const val ID = "_id"
    private const val VERSION = "version"

    /**
     * Records [structure], the collection about to be created in [database], at [version] when it has no version
     * yet; [validate] checks the version recorded. Fails when the collection already exists without a record.
     */
    fun record(database: MongoDatabase, structure: String, version: Int) {
        val formats = database.getCollection(COLLECTION)
        if (formats.find(Filters.eq(ID, structure)).first() == null) {
            // The record is written before the collection is created, so one without a record predates them; a
            // collection that appeared since the first read was created by a concurrent claim, whose record now exists.
            check(
                structure !in database.listCollectionNames() || formats.find(Filters.eq(ID, structure)).first() != null,
            ) {
                "$structure exists without a format record: an earlier, incompatible build created it. Drop it."
            }
        }
        try {
            formats.insertOne(Document(ID, structure).append(VERSION, version))
        } catch (failure: MongoWriteException) {
            // Another claim recorded it first, or it was recorded before: the stored version decides.
            if (!failure.isDuplicateKey()) {
                throw failure
            }
        }
    }
}
