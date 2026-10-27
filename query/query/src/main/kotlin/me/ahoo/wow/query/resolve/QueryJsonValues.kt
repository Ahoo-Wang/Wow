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

package me.ahoo.wow.query.resolve

import me.ahoo.wow.spi.json.WowJson
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import java.math.BigInteger

/*
 * The query protocol carries filter values as plain values (wow-query-api, decision D125): a scalar, a list of
 * scalars for EQ / NE, or a runtime value built in process (an enum, a value object). Records are JSON trees as
 * WowJson writes them, so the core compares a value as the tree WowJson writes for it, as 9.x compared a POJO node
 * by the tree its serializer wrote.
 */

/** The shared factory of the trees the core builds itself. */
internal val jsonNodes: JsonNodeFactory = JsonNodeFactory.instance

/** A new, empty record tree. */
internal fun newRecord(): ObjectNode = jsonNodes.objectNode()

/** This filter value as the JSON a record stores it as. */
internal fun Any?.jsonValue(): JsonNode = when (this) {
    null -> jsonNodes.nullNode()
    is JsonNode -> this
    else -> WowJson.mapper.valueToTree(this)
}

/** [jsonValue], or `null` when the value has no JSON form, such as a runtime value Jackson cannot write. */
internal fun Any?.jsonValueOrNull(): JsonNode? = try {
    jsonValue().takeUnless { it.isPojo || it.isMissingNode }
} catch (_: JacksonException) {
    null
} catch (_: IllegalArgumentException) {
    null
}

/** This value as a long when it is an integral number a long holds (a JSON integer), else `null`. */
internal fun Any?.integralLong(): Long? = when (this) {
    is Long -> this
    is Int -> toLong()
    is Short -> toLong()
    is Byte -> toLong()
    is BigInteger -> if (bitLength() < Long.SIZE_BITS) toLong() else null
    is JsonNode -> if (isIntegralNumber && canConvertToLong()) longValue() else null
    else -> null
}

/** Whether this value is a JSON null. */
internal val Any?.isNullValue: Boolean
    get() = this == null || this is JsonNode && this.isNull
