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

package me.ahoo.wow.mongo.query

import com.mongodb.client.model.Filters
import com.mongodb.client.model.Sorts
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.query.CursorPosition
import me.ahoo.wow.query.CursorPositionCodec
import org.bson.Document
import org.bson.RawBsonDocument
import org.bson.codecs.DocumentCodec
import org.bson.conversions.Bson
import org.bson.types.Decimal128

/**
 * Cursor positions as the raw BSON of `{values: [...]}`: every scalar keeps its BSON type, so a keyset condition
 * compares a `Decimal128` sort key against exactly the value the row held (decision D122). Only BSON scalars a
 * snapshot document holds decode; anything else is not a position this backend wrote.
 */
internal object MongoCursorCodec : CursorPositionCodec {
    private const val VALUES = "values"
    private val documentCodec = DocumentCodec()

    override fun encode(position: CursorPosition): ByteArray {
        val values = position.values
        require(values.size <= Sort.MAX_FIELDS && values.all(Any?::isMongoCursorScalar)) {
            "Cursor values must be BSON scalar values."
        }
        val raw = RawBsonDocument(Document(VALUES, values), documentCodec)
        val buffer = raw.byteBuffer.asNIO()
        return ByteArray(buffer.remaining()).also { buffer.get(it) }
    }

    override fun decode(payload: ByteArray, size: Int): CursorPosition {
        require(size in 1..Sort.MAX_FIELDS)
        val document = RawBsonDocument(payload).decode(documentCodec)
        require(document.keys == setOf(VALUES))
        val values = document[VALUES] as? List<*> ?: throw IllegalArgumentException("Cursor values are missing.")
        require(values.size == size && values.all(Any?::isMongoCursorScalar))
        return CursorPosition(values.toList())
    }
}

/** Whether this is a scalar a snapshot document holds: what a sort key can be. */
internal fun Any?.isMongoCursorScalar(): Boolean = when (this) {
    null, is String, is Boolean, is Int, is Long, is Double, is Decimal128 -> true
    else -> false
}

/** The keyset condition and the sort of a cursor's physical sort. */
internal object MongoKeyset {
    /**
     * The documents strictly after [values] in [sort], the expanded row comparison that honors each field's
     * direction and its nulls: a missing field and `null` sort first ascending and last descending, as MongoDB sorts.
     */
    fun after(sort: List<Sort>, values: List<Any?>): Bson {
        require(sort.size == values.size) { "Cursor values must match effective sort fields." }
        require(values.all(Any?::isMongoCursorScalar)) { "Cursor values must be BSON scalar values." }
        return Filters.or(
            sort.indices.map { index ->
                val terms = buildList {
                    repeat(index) { tie -> add(Filters.eq(sort[tie].field.path, values[tie])) }
                    add(after(sort[index], values[index]))
                }
                terms.singleOrNull() ?: Filters.and(terms)
            },
        )
    }

    private fun after(sort: Sort, value: Any?): Bson = when {
        value == null && sort.direction == Sort.Direction.ASC -> Filters.ne(sort.field.path, null)
        value == null -> MATCH_NONE_FILTER
        sort.direction == Sort.Direction.ASC -> Filters.gt(sort.field.path, value)
        else -> Filters.or(Filters.lt(sort.field.path, value), Filters.eq(sort.field.path, null))
    }

    /** [sort], over physical paths, as a MongoDB sort; `null` when it is empty. */
    fun sort(sort: List<Sort>): Bson? {
        if (sort.isEmpty()) return null
        return Sorts.orderBy(
            sort.map {
                when (it.direction) {
                    Sort.Direction.ASC -> Sorts.ascending(it.field.path)
                    Sort.Direction.DESC -> Sorts.descending(it.field.path)
                }
            },
        )
    }

    /** The value at the dotted [path] of [document], or `null` when it is missing. */
    fun valueAt(document: Document, path: String): Any? =
        path.split('.').fold(document as Any?) { current, part -> (current as? Document)?.get(part) }
}
