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

import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.query.CursorPosition
import me.ahoo.wow.query.CursorPositionCodec
import org.bson.BsonTimestamp
import org.bson.Document
import org.bson.RawBsonDocument
import org.bson.codecs.DocumentCodec
import org.bson.types.Decimal128
import java.util.Date

/** Cursor positions as the raw BSON of `{values: [...]}`: every native scalar keeps its BSON type. */
internal object MongoCursorCodec : CursorPositionCodec {
    private const val VALUES = "values"
    private val documentCodec = DocumentCodec()

    override fun encode(position: CursorPosition): ByteArray {
        val values = position.values
        require(values.size <= AggregationQuery.MAX_SORT_FIELDS && values.all(Any?::isMongoCursorScalar)) {
            "Cursor values must be BSON scalar values."
        }
        val raw = RawBsonDocument(Document(VALUES, values), documentCodec)
        return raw.backingArray.copyOfRange(raw.byteOffset, raw.byteOffset + raw.byteLength)
    }

    override fun decode(payload: ByteArray, size: Int): CursorPosition {
        require(size in 1..AggregationQuery.MAX_SORT_FIELDS)
        val document = RawBsonDocument(payload).decode(documentCodec)
        require(document.keys == setOf(VALUES))
        val values = document[VALUES] as? List<*> ?: throw IllegalArgumentException()
        require(values.size == size && values.all(Any?::isMongoCursorScalar))
        return CursorPosition(values.toList())
    }
}

internal fun Any?.isMongoCursorScalar(): Boolean = when (this) {
    null, is String, is Boolean, is Int, is Long, is Double, is Date, is BsonTimestamp, is Decimal128 -> true
    else -> false
}

internal data class MongoCursorProjection(
    val queryProjection: Projection,
    val internalFields: Set<String>,
)

internal fun Projection.withCursorFields(sortFields: List<String>): MongoCursorProjection {
    val internalFields = when {
        include.isNotEmpty() -> sortFields.filterNot { field ->
            include.any { included -> field == included.path || field.startsWith("${included.path}.") }
        }.toSet()
        exclude.isNotEmpty() -> exclude.filter { excluded ->
            sortFields.any { field -> field == excluded.path || field.startsWith("${excluded.path}.") }
        }.map(QueryField::path).toSet()
        else -> emptySet()
    }
    val queryProjection = when {
        include.isNotEmpty() -> copy(
            include = (include + internalFields.map { QueryField(it) }).distinct(),
            exclude = exclude.filterNot { excluded ->
                internalFields.any { field -> field == excluded.path || field.startsWith("${excluded.path}.") }
            },
        )
        exclude.isNotEmpty() -> copy(exclude = exclude.filterNot { it.path in internalFields })
        else -> this
    }
    return MongoCursorProjection(queryProjection, internalFields)
}

private fun Document.valueAt(path: String): Any? =
    path.split('.').fold(this as Any?) { current, part -> (current as? Document)?.get(part) }

private fun Document.removeAt(parts: List<String>, index: Int, removeEmptyParents: Boolean) {
    val field = parts[index]
    if (index == parts.lastIndex) {
        remove(field)
        return
    }
    val child = get(field) as? Document ?: return
    child.removeAt(parts, index + 1, removeEmptyParents)
    if (removeEmptyParents && child.isEmpty()) {
        remove(field)
    }
}

/** The rows of one keyset window with each row's native position, in row order. */
internal class KeysetRows<T>(val rows: List<T>, val positions: List<CursorPosition>)

/**
 * A keyset window of these documents: each row's position is read from the native document at the physical sort
 * fields before any field the cursor alone needed is stripped, then [mapper] turns the cleaned document into a row.
 */
internal fun <T> List<Document>.toKeysetRows(
    projection: MongoCursorProjection,
    sortFields: List<String>,
    deferredInternalFields: Set<String> = emptySet(),
    mapper: (Document) -> T,
): KeysetRows<T> {
    val positions = map { document -> CursorPosition(sortFields.map(document::valueAt)) }
    val removablePaths = if (isEmpty() || projection.internalFields.isEmpty()) {
        emptyList()
    } else {
        projection.internalFields.filterNot(deferredInternalFields::contains).map { it.split('.') }
    }
    val removeEmptyParents = projection.queryProjection.include.isNotEmpty()
    return KeysetRows(
        rows = map { document ->
            removablePaths.forEach { parts ->
                document.removeAt(parts, 0, removeEmptyParents)
            }
            mapper(document)
        },
        positions = positions,
    )
}
