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
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import org.bson.Document

/**
 * The format versions of stored structures (decision D59): one document per structure (a log or a snapshot
 * collection) in [COLLECTION], keyed by the structure's name and recorded by the collection, not by each document.
 * `createSchema` claims its structure's version before creating it: the first claim records it, a structure recorded
 * under another version is refused, and so is a collection that exists without a record (an earlier, incompatible
 * build created it), so this version of Wow never reads or writes a layout it does not know. An incompatible layout
 * changes only in a major version, through the migration tool (D22); adding an optional field does not change the
 * version.
 */
object MongoFormat {
    /** The collection of format versions, shared by every structure in the database. */
    const val COLLECTION = "wow_format"

    private const val ID = "_id"
    private const val VERSION = "version"

    /**
     * Records [structure], the collection about to be created in [database], at [version] when it has no version
     * yet. Fails when it is recorded at another version, or when the collection already exists without a record.
     */
    fun claim(database: MongoDatabase, structure: String, version: Int) {
        val formats = database.getCollection(COLLECTION)
        if (formats.find(Filters.eq(ID, structure)).first() == null) {
            // The record is written before the collection is created, so one without a record predates them.
            check(structure !in database.listCollectionNames()) {
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
        val stored = formats.find(Filters.eq(ID, structure)).first()!!.getInteger(VERSION)
        check(stored == version) {
            "$structure is stored in format $stored; this version of Wow reads format $version. " +
                "Migrate it with the migration tool rather than opening it."
        }
    }
}
