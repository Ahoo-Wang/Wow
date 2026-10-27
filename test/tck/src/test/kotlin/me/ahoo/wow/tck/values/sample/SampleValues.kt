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

package me.ahoo.wow.tck.values.sample

import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import java.math.BigInteger
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.BitSet
import java.util.UUID
import java.util.regex.Pattern

/** A code held boxed when nullable: the guard builds it through its box. */
@JvmInline
value class Code(val value: Int)

/** A name, held boxed as a list's element: the guard builds it through its box, from a string. */
@JvmInline
value class Name(val value: String)

/** A value of the field types the guard builds in their own way: an interface, an array, a boxed inline class. */
class Rich(
    val listener: Runnable,
    val names: Array<String>,
    val code: Code?,
    val bounds: Map<String, MutableList<out Number>>,
    val format: DateTimeFormatter,
    val aliases: List<Name>,
) {
    override fun equals(other: Any?): Boolean = this === other || other is Rich && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    private fun valueFields(): List<Any?> = listOf(listener, names, code, bounds, format, aliases)

    override fun toString(): String = "Rich($listener)"
}

/** A value whose `kind` each class of the package sets alike, left out of equals on purpose. */
class Kinded(val name: String) {
    val kind: String = "sample"

    /** A cache equals reads through, of a declared type: the guard sets it to a value of what it holds. */
    private val upper: Lazy<String> = lazy { name.uppercase() }

    override fun equals(other: Any?): Boolean =
        this === other || other is Kinded && name == other.name && upper.value.isNotEmpty()

    override fun hashCode(): Int = name.hashCode()
}

/** An identity: equal to itself only. */
class Identity(val name: String) {
    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)
}

/** A value of every other kind of field the guard builds: primitives and their boxes, the JDK's values, collections. */
class Everything(
    val text: CharSequence,
    val anything: Any,
    val type: Class<*>,
    val number: Number,
    val decimal: BigDecimal,
    val integer: BigInteger,
    val at: Instant,
    val elapsed: java.time.Duration,
    val id: UUID,
    val uri: URI,
    val pattern: Pattern,
    val regex: Regex,
    val bits: BitSet,
    val zone: ZoneId,
    val json: JsonNode,
    val int: Int,
    val boxedInt: Int?,
    val long: Long,
    val boxedLong: Long?,
    val short: Short,
    val boxedShort: Short?,
    val byte: Byte,
    val boxedByte: Byte?,
    val double: Double,
    val boxedDouble: Double?,
    val float: Float,
    val boxedFloat: Float?,
    val char: Char,
    val boxedChar: Char?,
    val flag: Boolean,
    val boxedFlag: Boolean?,
    val set: Set<String>,
    val pair: Pair<String, Int>,
    val counts: IntArray,
    val unit: Unit,
) {
    override fun equals(other: Any?): Boolean =
        this === other || other is Everything && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    private fun valueFields(): List<Any?> = listOf(
        text, anything, type, number, decimal, integer, at, elapsed, id, uri, pattern, regex, bits, zone, json, int,
        boxedInt, long, boxedLong, short, boxedShort, byte, boxedByte, double, boxedDouble, float, boxedFloat, char,
        boxedChar, flag, boxedFlag, set, pair, counts, unit,
    )
}
