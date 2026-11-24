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

import com.mongodb.client.model.IndexOptions
import org.bson.BsonDocument
import org.bson.BsonNumber
import org.bson.BsonValue
import org.bson.Document
import org.bson.conversions.Bson

/**
 * An index as one of Wow's stores creates it ([MongoFormat.index]): its [keys] and its [options] (name, `unique`,
 * `sparse`, partial filter, and no collation). What a collection holds under its name is compared with it, so that a
 * schema made by hand or by another tool, which `ddl: validate` accepts, holds the same keys unique over the same
 * documents (#6748): an index of the right name that is not unique would let two streams commit at one version.
 */
internal class MongoIndexDefinition(private val keys: Bson, private val options: IndexOptions) {
    val name: String = checkNotNull(options.name) { "An index of Wow's stores has a name." }

    private val key: BsonDocument = keys.toBsonDocument()
    private val partial: BsonDocument? = options.partialFilterExpression?.toBsonDocument()

    /** The index as `createIndexes` takes it: what an operator creates by hand when Wow does not. */
    val specification: String
        get() = BsonDocument("key", key).append("name", org.bson.BsonString(name)).apply {
            if (options.isUnique) append("unique", org.bson.BsonBoolean.TRUE)
            if (options.isSparse) append("sparse", org.bson.BsonBoolean.TRUE)
            partial?.let { append("partialFilterExpression", it) }
        }.toJson()

    /** Whether [existing], as `listIndexes` reports an index, has this one's keys in this order. */
    fun hasKeys(existing: Document): Boolean = sameKey(existing.toBsonDocument().getDocument(KEY, BsonDocument()))

    /**
     * How [existing], as `listIndexes` reports the index of this one's name, differs from it, each named; empty when it
     * is this index. Directions compare as numbers (`1` and `1.0` alike), a partial filter as its BSON.
     */
    fun differences(existing: Document): List<String> = buildList {
        val stored = existing.toBsonDocument()
        val storedKey = stored.getDocument(KEY, BsonDocument())
        if (!sameKey(storedKey)) add("its key is ${storedKey.toJson()}, not ${key.toJson()}")
        val unique = flag(stored[UNIQUE])
        if (unique != options.isUnique) add(if (unique) "it is unique, and Wow's is not" else "it is not unique")
        val sparse = flag(stored[SPARSE])
        if (sparse != options.isSparse) add(if (sparse) "it is sparse, and Wow's is not" else "it is not sparse")
        val storedPartial = stored[PARTIAL]?.takeIf { it.isDocument }?.asDocument()
        if (storedPartial != partial) {
            add("its partial filter is ${storedPartial?.toJson() ?: "none"}, not ${partial?.toJson() ?: "none"}")
        }
        stored[COLLATION]?.let { add("it has the collation ${it.asDocument().toJson()}, and Wow's has none") }
    }

    /** The same fields in the same order, each direction or type (`hashed`) alike. */
    private fun sameKey(stored: BsonDocument): Boolean =
        stored.keys.toList() == key.keys.toList() && key.all { (field, value) -> same(stored.getValue(field), value) }

    private fun same(stored: BsonValue, expected: BsonValue): Boolean =
        if (stored is BsonNumber && expected is BsonNumber) {
            stored.doubleValue() == expected.doubleValue()
        } else {
            stored == expected
        }

    /** An index option as `listIndexes` reports it: a boolean, or a number a shell wrote (`1`). */
    private fun flag(value: BsonValue?): Boolean = when (value) {
        null -> false
        is BsonNumber -> value.doubleValue() != 0.0
        else -> value.isBoolean && value.asBoolean().value
    }

    private companion object {
        const val KEY = "key"
        const val UNIQUE = "unique"
        const val SPARSE = "sparse"
        const val PARTIAL = "partialFilterExpression"
        const val COLLATION = "collation"
    }
}
