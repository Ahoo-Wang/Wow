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

package me.ahoo.wow.migrate

import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Sorts
import org.bson.Document
import tools.jackson.databind.JsonNode

/**
 * One key of a 9.x `PrepareKey` named [name], as 9.x's `MongoPrepareKey` stores it in the collection
 * `prepare_<name>`: `{_id: <key>, value: <the prepared value>, ttlAt: <Date>}`. The value is the `PrepareKey<V>`'s
 * `V` as 9.x serialized it: a string, or a document of the value's fields. [ttlAt] is the expiry in epoch
 * milliseconds; a key prepared forever has 9.x's far-future `TTL_FOREVER` (or no `ttlAt`, which 9.x reads as forever).
 */
internal data class NineXPreparedKey(val name: String, val key: String, val value: JsonNode, val ttlAt: Long?) {
    /** Whether 9.x holds the key for good: prepared forever, not with a time to live. */
    val confirmed: Boolean get() = ttlAt == null || ttlAt >= TTL_FOREVER

    /** Whether a key prepared with a time to live has expired at [now]; a confirmed key never does. */
    fun expired(now: Long): Boolean = ttlAt != null && ttlAt < TTL_FOREVER && ttlAt <= now

    /**
     * The id the value names its owner by: the value itself when [path] is null, else the string at the dotted
     * [path] in it; null when there is no string there.
     */
    fun ownerId(path: String?): String? {
        var node: JsonNode? = value
        path?.split('.')?.forEach { name -> node = node?.takeIf { it.isObject }?.get(name) }
        return node?.takeIf { it.isString }?.stringValue()?.takeIf { it.isNotBlank() }
    }

    override fun toString(): String = "$name/$key"

    companion object {
        /** 9.x's `PreparedValue.TTL_FOREVER`: 6666-06-06, the expiry of a key prepared forever. */
        const val TTL_FOREVER: Long = 148_204_944_000_000L
    }
}

/** The 9.x `PrepareKey`s of a 9.x store (decisions D38, D225, D227 ③). The calls block. */
internal interface NineXReservationSource {
    /** Every `PrepareKey` the store holds, by its name, with the number of its keys. */
    fun prepareKeys(): Map<String, Long>

    /** At most [limit] keys of the `PrepareKey` [name] after the key [after] (from the first when null), in key order. */
    fun keys(name: String, after: String?, limit: Int): List<NineXPreparedKey>

    /** The key [key] of the `PrepareKey` [name]; null when 9.x does not hold it (never prepared, or rolled back). */
    fun key(name: String, key: String): NineXPreparedKey?
}

/** The 9.x `PrepareKey`s of the MongoDB [database], one collection `prepare_<name>` each. */
internal class MongoNineXReservationSource(private val database: MongoDatabase) : NineXReservationSource {
    override fun prepareKeys(): Map<String, Long> =
        database.listCollectionNames().filter { it.startsWith(PREFIX) }.sorted().associate { collection ->
            collection.removePrefix(PREFIX) to database.getCollection(collection).countDocuments()
        }

    override fun keys(name: String, after: String?, limit: Int): List<NineXPreparedKey> =
        database.getCollection(PREFIX + name)
            .find(after?.let { Filters.gt(ID, it) } ?: Filters.empty())
            .sort(Sorts.ascending(ID))
            .limit(limit)
            .map { keyOf(name, it) }
            .toList()

    override fun key(name: String, key: String): NineXPreparedKey? =
        database.getCollection(PREFIX + name).find(Filters.eq(ID, key)).first()?.let { keyOf(name, it) }

    private fun keyOf(name: String, document: Document) = NineXPreparedKey(
        name = name,
        key = document.get(ID).toString(),
        value = MongoNineXEventSource.toNode(document[VALUE]),
        ttlAt = document.getDate(TTL_AT)?.time,
    )

    companion object {
        /** The prefix of a 9.x `PrepareKey`'s collection. */
        const val PREFIX: String = "prepare_"
        private const val ID = "_id"
        private const val VALUE = "value"
        private const val TTL_AT = "ttlAt"
    }
}
