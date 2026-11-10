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

import me.ahoo.wow.spi.json.WowJson
import org.bson.BsonUndefined
import org.bson.Document
import org.bson.types.Decimal128
import org.bson.types.ObjectId
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory
import java.util.Date

/**
 * A 9.x MongoDB value as JSON, both ways: [toNode] reads what the driver read from a 9.x store (its streams, snapshots,
 * reserved keys and compensation executions), [bsonOf] writes back what the rollback export writes into it.
 */
internal object NineXBson {
    private val NODES: JsonNodeFactory = WowJson.mapper.nodeFactory

    /** [value], a BSON value the driver read, as JSON; a `Decimal128` stays an exact decimal. */
    fun toNode(value: Any?): JsonNode = when (value) {
        null, is BsonUndefined -> NODES.nullNode()
        is Map<*, *> -> NODES.objectNode().also { node ->
            value.forEach { (key, item) -> node.set(key.toString(), toNode(item)) }
        }

        is Collection<*> -> NODES.arrayNode().also { node -> value.forEach { node.add(toNode(it)) } }
        is String -> NODES.stringNode(value)
        is Boolean -> NODES.booleanNode(value)
        is Number -> numberNode(value)
        is Date -> NODES.numberNode(value.time)
        is ObjectId -> NODES.stringNode(value.toHexString())
        else -> NODES.stringNode(value.toString())
    }

    private fun numberNode(value: Number): JsonNode = when (value) {
        is Decimal128 -> NODES.numberNode(value.bigDecimalValue())
        is Int -> NODES.numberNode(value)
        is Long -> NODES.numberNode(value)
        else -> NODES.numberNode(value.toDouble())
    }

    /** [node] as the BSON value 9.x writes for it: a decimal as an exact `Decimal128`, the inverse of [toNode]. */
    fun bsonOf(node: JsonNode): Any? = when {
        node.isObject -> Document().also { document ->
            node.properties().forEach { (name, value) -> document.append(name, bsonOf(value)) }
        }

        node.isArray -> (0 until node.size()).map { bsonOf(node.get(it)) }
        node.isNull || node.isMissingNode -> null
        node.isBoolean -> node.booleanValue()
        node.isInt -> node.intValue()
        node.isLong -> node.longValue()
        node.isIntegralNumber -> Decimal128(node.decimalValue())
        node.isNumber -> Decimal128(node.decimalValue())
        else -> node.asString()
    }
}
