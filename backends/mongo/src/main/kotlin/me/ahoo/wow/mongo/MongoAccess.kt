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
 */
internal object MongoAccess {
    /** [access] as its BSON sub-document. */
    fun bson(access: Map<String, Any?>): BsonDocument = BsonDocument().apply {
        access.forEach { (name, value) ->
            put(name, if (value is List<*>) BsonArray(value.map(::scalarOf)) else scalarOf(value))
        }
    }

    /** The attributes [document] holds, in their stored form; null for none, a stream not stamped. */
    fun of(document: Document?): Map<String, Any?>? = document?.let(AccessValues::normalized)

    private fun scalarOf(value: Any?): BsonValue = when (value) {
        null -> BsonNull.VALUE
        is String -> BsonString(value)
        is Long -> BsonInt64(value)
        is Boolean -> BsonBoolean.valueOf(value)
        else -> throw IllegalArgumentException("An access value is a String, a Long or a Boolean, not $value.")
    }
}
