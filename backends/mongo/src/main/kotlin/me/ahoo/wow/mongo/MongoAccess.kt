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

import me.ahoo.wow.spi.access.AccessValues
import org.bson.BsonArray
import org.bson.BsonBoolean
import org.bson.BsonDocument
import org.bson.BsonInt64
import org.bson.BsonNull
import org.bson.BsonString
import org.bson.BsonValue
import org.bson.Document

/**
 * How a log stream and a snapshot keep their access attributes ([AccessValues]): a sub-document of each attribute's
 * value by its name, a string, an `int64`, a boolean or null, a multiple one's an array of them.
 *
 * A log stream leaves out a built-in attribute ([AccessValues.OWNER], [AccessValues.SPACE]) that has no value
 * (decision D420): its index is partial on the attribute's existence, so a stream of an aggregate without an owner or
 * a space adds no entry to it. Every stamped stream holds both built-ins ([AccessValues.ofStream]), so the readers put
 * a missing one back as null ([restored]): above this store, a null and an absent attribute stay two facts.
 */
internal object MongoAccess {
    private val BUILT_IN = listOf(AccessValues.OWNER, AccessValues.SPACE)

    /** [access] as a log stream's sub-document: a built-in attribute without a value left out. */
    fun log(access: Map<String, Any?>): Document = Document().also { document ->
        access.forEach { (name, value) -> if (value != null || name !in BUILT_IN) document[name] = value }
    }

    /**
     * [document], a log stream's access sub-document, with each built-in attribute it leaves out put back as null,
     * first, as a stream holds them; one written before D420, which holds the nulls, reads the same.
     */
    fun restored(document: Document): Document {
        if (BUILT_IN.all { it in document }) return document
        return Document().also { restored ->
            BUILT_IN.forEach { restored[it] = document[it] }
            document.forEach { (name, value) -> if (name !in BUILT_IN) restored[name] = value }
        }
    }

    /**
     * The attributes of a log stream's [document], the built-in ones put back ([restored]); null for a stream not
     * stamped.
     */
    fun ofLog(document: Document?): Map<String, Any?>? = document?.let { AccessValues.normalized(restored(it)) }

    /** [access] as its BSON sub-document. */
    fun bson(access: Map<String, Any?>): BsonDocument = BsonDocument().apply {
        access.forEach { (name, value) ->
            put(name, if (value is List<*>) BsonArray(value.map(::scalarOf)) else scalarOf(value))
        }
    }

    private fun scalarOf(value: Any?): BsonValue = when (value) {
        null -> BsonNull.VALUE
        is String -> BsonString(value)
        is Long -> BsonInt64(value)
        is Boolean -> BsonBoolean.valueOf(value)
        else -> throw IllegalArgumentException("An access value is a String, a Long or a Boolean, not $value.")
    }
}
