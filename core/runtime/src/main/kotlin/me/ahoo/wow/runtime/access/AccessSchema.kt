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

package me.ahoo.wow.runtime.access

import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.spi.access.AccessStamp
import me.ahoo.wow.spi.access.AccessStamps
import java.security.MessageDigest

/** The attributes of an aggregate's history as of [version]: what a stamp wrote, or a stream carries. */
@InternalWowApi
class Stamped(val version: Int, val access: Map<String, Any?>)

/**
 * The access schema of an aggregate type: what its streams' attributes are derived as, the declared ones by name, each
 * with the kind of its stored values and whether it holds many (`department:string,teams:string[]`), empty for none.
 * The built-in owner and space are of every schema.
 */
@InternalWowApi
object AccessSchema {
    fun of(model: AggregateModel<*>): String = model.accessAttributes.sortedBy { it.name }.joinToString(",") {
        "${it.name}:${kindOf(it.type)}${if (it.multiple) "[]" else ""}"
    }

    /** A short hash of [schema], for a name. */
    fun hashOf(schema: String): String =
        MessageDigest.getInstance("SHA-256").digest(schema.toByteArray()).take(HASH_BYTES)
            .joinToString("") { "%02x".format(it) }

    private fun kindOf(type: Class<*>): String = when {
        type.isEnum -> "enum"
        type == String::class.java -> "string"
        type == Boolean::class.javaObjectType -> "boolean"
        else -> "integer"
    }

    private const val HASH_BYTES = 8
}

/**
 * Writes [stamps] together ([AccessStamps.stampAll]), in writes of at most [batch] streams each: a stamp of more is cut
 * into chunks of [batch], each written whole in one write. Returns the streams stamped.
 */
@InternalWowApi
suspend fun AccessStamps.stampAllIn(stamps: List<AccessStamp>, batch: Int): Int {
    require(batch >= 1) { "A write stamps at least one stream." }
    val chunks = stamps.flatMap { stamp ->
        (stamp.from until stamp.until step batch).map { from ->
            stamp.copy(from = from, until = minOf(from + batch, stamp.until))
        }
    }
    var written = 0
    var bulk = ArrayList<AccessStamp>()
    var streams = 0
    chunks.forEach { chunk ->
        val size = chunk.until - chunk.from
        if (bulk.isNotEmpty() && streams + size > batch) {
            written += stampAll(bulk)
            bulk = ArrayList()
            streams = 0
        }
        bulk += chunk
        streams += size
    }
    if (bulk.isNotEmpty()) {
        written += stampAll(bulk)
    }
    return written
}

/** Stamps [access] as of [asOf] over the streams of [ref] from [from] until [until], at most [batch] in each stamp. */
internal suspend fun AccessStamps.stampIn(
    ref: AggregateRef,
    access: Map<String, Any?>,
    asOf: Int,
    from: Int,
    until: Int,
    batch: Int,
): Int {
    var start = from
    var written = 0
    while (start < until) {
        val end = minOf(start + batch, until)
        written += stamp(ref, access, asOf, start, end)
        start = end
    }
    return written
}
