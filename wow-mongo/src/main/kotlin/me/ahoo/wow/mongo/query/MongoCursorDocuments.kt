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
import me.ahoo.wow.api.query.CursorPage
import me.ahoo.wow.api.query.ICursorQuery
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.query.CursorTokenCodec
import org.bson.BsonTimestamp
import org.bson.Document
import org.bson.RawBsonDocument
import org.bson.codecs.DocumentCodec
import org.bson.types.Decimal128
import java.util.Date

internal object MongoCursorCodec {
    private const val VALUES = "values"
    private val documentCodec = DocumentCodec()

    fun encode(tokenCodec: CursorTokenCodec, values: List<Any?>): String = try {
        require(values.size <= AggregationQuery.MAX_SORT_FIELDS && values.all(Any?::isMongoCursorScalar))
        val raw = RawBsonDocument(Document(VALUES, values), documentCodec)
        tokenCodec.encode(
            raw.backingArray.copyOfRange(raw.byteOffset, raw.byteOffset + raw.byteLength),
        )
    } catch (_: Exception) {
        throw IllegalArgumentException("Invalid cursor.")
    }

    fun decode(tokenCodec: CursorTokenCodec, cursor: String, expectedSize: Int): List<Any?> = try {
        require(expectedSize in 1..AggregationQuery.MAX_SORT_FIELDS)
        val document = RawBsonDocument(tokenCodec.decode(cursor)).decode(documentCodec)
        require(document.keys == setOf(VALUES))
        val values = document[VALUES] as? List<*> ?: throw IllegalArgumentException()
        require(values.size == expectedSize && values.all(Any?::isMongoCursorScalar))
        values.toList()
    } catch (_: Exception) {
        throw IllegalArgumentException("Invalid cursor.")
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
            include.any { included -> field == included || field.startsWith("$included.") }
        }.toSet()
        exclude.isNotEmpty() -> exclude.filter { excluded ->
            sortFields.any { field -> field == excluded || field.startsWith("$excluded.") }
        }.toSet()
        else -> emptySet()
    }
    val queryProjection = when {
        include.isNotEmpty() -> copy(
            include = (include + internalFields).distinct(),
            exclude = exclude.filterNot { excluded ->
                internalFields.any { field -> field == excluded || field.startsWith("$excluded.") }
            },
        )
        exclude.isNotEmpty() -> copy(exclude = exclude.filterNot(internalFields::contains))
        else -> this
    }
    return MongoCursorProjection(queryProjection, internalFields)
}

private fun Document.valueAt(path: String): Any? =
    path.split('.').fold(this as Any?) { current, part -> (current as? Document)?.get(part) }

private fun Document.removeAt(path: String) {
    val parts = path.split('.')
    val parent = parts.dropLast(1).fold(this as Document?) { current, part ->
        current?.get(part) as? Document
    }
    parent?.remove(parts.last())
}

internal fun <T : Any> List<Document>.toCursorPage(
    query: ICursorQuery,
    projection: MongoCursorProjection,
    sortFields: List<String> = query.sort.map { it.field },
    tokenCodec: CursorTokenCodec,
    mapper: (Document) -> T,
): CursorPage<T> {
    val returned = take(query.size)
    val nextCursor = if (size > query.size) {
        MongoCursorCodec.encode(tokenCodec, sortFields.map(returned.last()::valueAt))
    } else {
        null
    }
    return CursorPage(
        list = returned.map { document ->
            projection.internalFields.forEach(document::removeAt)
            mapper(document)
        },
        nextCursor = nextCursor,
    )
}
