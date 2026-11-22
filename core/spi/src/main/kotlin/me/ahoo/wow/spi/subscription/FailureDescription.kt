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

package me.ahoo.wow.spi.subscription

import me.ahoo.wow.api.annotation.WowSpi

/**
 * What a [FailureDescriber] says of a failure, which leaves the service in a parked aggregate's reason: parts that
 * carry no text the failure or a value gave, so that a describer cannot put a value back, not even one its failure's
 * message quotes.
 *
 * - [phrase]: a constant of an enum, written as its name in lower case, `_` as a space (`DUPLICATE_KEY` is
 *   `duplicate key`): words the describer's code holds, never a value;
 * - [name]: the name of a property, an index, a constraint, a topic, or a code such as an SQL state, quoted: at most
 *   [MAX_NAME_LENGTH] letters, digits, `_`, `$`, `.` and `-`, which no free text, quoted value, e-mail address or card
 *   number fits;
 * - [path]: where in a document, by property names ([PathSegment.property]), array indices ([PathSegment.index]) and
 *   map entries ([PathSegment.KEY], written `{key}`: a map's key is data);
 * - [type]: a class, by its simple name;
 * - [code]: a number, such as a driver's error code.
 *
 * None takes a [String] of free text. A name is still a string the describer passes, so a describer names what its
 * failure's structured fields name (`ServerErrorMessage.constraint`, a Jackson reference's property), and Wow's own
 * describers are tested to put no value of the failures they describe into it.
 */
@WowSpi
class FailureDescription private constructor(
    /** The description as text: its parts, each as it is written, separated by a space. */
    val text: String,
) {
    override fun equals(other: Any?): Boolean = this === other || other is FailureDescription && text == other.text

    override fun hashCode(): Int = text.hashCode()

    override fun toString(): String = text

    /** One part of a description, written as [text]; built by [phrase], [name], [path], [type] or [code] alone. */
    class Part internal constructor(val text: String)

    /** One segment of a [path]: a property by its name, an array's index, or a map's entry, whose key is data. */
    class PathSegment private constructor(internal val text: String, internal val dotted: Boolean) {
        companion object {
            /** A map's entry: its key is the map's data, so it is written `{key}`, never as it is. */
            @JvmField
            val KEY: PathSegment = PathSegment("{key}", true)

            /** The property [name], a name as [FailureDescription.name] takes. */
            @JvmStatic
            fun property(name: String): PathSegment = PathSegment(checkedName(name), true)

            /** The array's item at [index]. */
            @JvmStatic
            fun index(index: Int): PathSegment {
                require(index >= 0) { "An index is never negative." }
                return PathSegment("[$index]", false)
            }
        }
    }

    companion object {
        /**
         * The longest name a description takes. What keeps free text out is the characters a name takes; the bound
         * keeps a name a name, and is long enough for a Wow qualified name (`<context>.<aggregate>.<Event>`) and a
         * driver's (a MongoDB index, a schema-qualified PostgreSQL constraint).
         */
        const val MAX_NAME_LENGTH: Int = 128

        private val NAME = Regex("[A-Za-z0-9_$.-]{1,$MAX_NAME_LENGTH}")

        /** A description of [parts], in order; at least one. */
        @JvmStatic
        fun of(vararg parts: Part): FailureDescription {
            require(parts.isNotEmpty()) { "A failure description has at least one part." }
            return FailureDescription(parts.joinToString(" ") { it.text })
        }

        /** The words of [phrase], an enum's constant: its name in lower case, each `_` a space. */
        @JvmStatic
        fun phrase(phrase: Enum<*>): Part = Part(phrase.name.lowercase().replace('_', ' '))

        /**
         * [name], quoted: the name of a property, an index, a constraint, a topic, or a code. Refused unless it is
         * [MAX_NAME_LENGTH] letters, digits, `_`, `$`, `.` and `-` at most.
         */
        @JvmStatic
        fun name(name: String): Part = Part("'${checkedName(name)}'")

        /** The place [segments] name in a document, quoted: `items[0].quantity`, `prices.{key}`. */
        @JvmStatic
        fun path(segments: List<PathSegment>): Part {
            require(segments.isNotEmpty()) { "A path has at least one segment." }
            val written = buildString {
                segments.forEachIndexed { at, segment ->
                    if (at > 0 && segment.dotted) append('.')
                    append(segment.text)
                }
            }
            return Part("'$written'")
        }

        /** [type], by its simple name (`int`, `BigDecimal`, `String[]`); its name when it has no simple one. */
        @JvmStatic
        fun type(type: Class<*>): Part = Part(type.simpleName.ifEmpty { type.name })

        /** The number [code]. */
        @JvmStatic
        fun code(code: Long): Part = Part(code.toString())

        private fun checkedName(name: String): String {
            require(NAME.matches(name)) {
                "A failure description's name is at most $MAX_NAME_LENGTH letters, digits, '_', '\$', '.' and '-'."
            }
            return name
        }
    }
}
