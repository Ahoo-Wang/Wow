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

package me.ahoo.wow.api.wire

/**
 * How Wow spells a value on the wire: lower-kebab, as its headers, server-sent event names and error
 * codes are. A Wow enumeration's value is its constant's name in lower case, words joined by hyphens
 * (`CATCH_UP` is `catch-up`); a violation's code is its constraint's name so spelled (`NotBlank` is `not-blank`,
 * `UNKNOWN_FIELD` is `unknown-field`). A domain's own enumerations are the domain's: Wow never respells them.
 */
object WireNames {
    private val WORD_END = Regex("([a-z0-9])([A-Z])")
    private val ACRONYM_END = Regex("([A-Z]+)([A-Z][a-z])")

    /**
     * Each enumeration's wire names, by its constants' ordinals, spelled once when the enumeration is first written and
     * kept with its class: one entry per enumeration class, released with its class loader, so it is as bounded as the
     * enumerations an application has. Every thread reads the one array [ClassValue] keeps, so a constant's name is
     * the same instance every time.
     */
    private val NAMES = object : ClassValue<Array<String>>() {
        override fun computeValue(type: Class<*>): Array<String> =
            type.enumConstants.map { of((it as Enum<*>).name) }.toTypedArray()
    }

    /**
     * The wire name of [value], a constant of a Wow enumeration: `CATCH_UP` is `catch-up`. Spelled once per constant
     * ([of] of its name), then read: a reply writes several on every command.
     */
    fun of(value: Enum<*>): String = NAMES.get(value.declaringJavaClass)[value.ordinal]

    /**
     * [name] in lower-kebab: a word starts at an upper-case letter after a lower-case letter or a digit, and at the
     * last letter of an upper-case run followed by a lower-case one; underscores separate words too.
     * `NotBlank` is `not-blank`, `UNKNOWN_FIELD` is `unknown-field`, `URLPattern` is `url-pattern`.
     *
     * Spelled on every call, never kept: its names are its callers', a constraint's name per violation among them, and
     * a cache keyed by them would grow with whatever they pass.
     */
    fun of(name: String): String =
        name.replace(ACRONYM_END, "$1-$2").replace(WORD_END, "$1-$2").replace('_', '-').lowercase()

    /** The constant of [E] whose wire name is [wire], or null when none is: `catch-up` is `CATCH_UP`, `CATCH_UP` none. */
    inline fun <reified E : Enum<E>> entryOf(wire: String): E? = enumValues<E>().firstOrNull { of(it) == wire }

    /** The wire names of [E]'s constants, in their order. */
    inline fun <reified E : Enum<E>> namesOf(): List<String> = enumValues<E>().map { of(it) }
}
