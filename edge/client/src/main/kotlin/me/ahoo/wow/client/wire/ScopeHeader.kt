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

package me.ahoo.wow.client.wire

import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.runtime.admission.TargetScope

/**
 * The `Wow-Scope` header (decision D95): an RFC 8941 dictionary whose members, all optional, are `tenant`, `owner` and
 * `space`, each a string or a token: `Wow-Scope: tenant="t1", owner="u1", space="s1"`.
 *
 * A member that is not one of them, one without a value, a value that is not a string or a token, a member with
 * parameters, and a dictionary without members are malformed. As RFC 8941 says, a member given twice takes its last
 * value.
 */
object ScopeHeader {
    const val TENANT: String = "tenant"
    const val OWNER: String = "owner"
    const val SPACE: String = "space"

    private val MEMBERS = setOf(TENANT, OWNER, SPACE)

    /** The target scope [value] spells. */
    fun parse(value: String): TargetScope {
        val members = Dictionary(value).parse()
        require(members.isNotEmpty()) { "Wow-Scope names a tenant, an owner or a space." }
        val unknown = members.keys - MEMBERS
        require(unknown.isEmpty()) { "Wow-Scope takes only tenant, owner and space: $unknown" }
        return TargetScope(members[TENANT], Scope(members[OWNER], members[SPACE]))
    }

    /** [target] as [parse] reads it. */
    fun format(target: TargetScope): String = listOfNotNull(
        target.tenantId?.let { "$TENANT=${quote(it)}" },
        target.scope.ownerId?.let { "$OWNER=${quote(it)}" },
        target.scope.spaceId?.let { "$SPACE=${quote(it)}" },
    ).joinToString(", ")

    private fun quote(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** A parser of the subset of RFC 8941 §4.2.2 dictionaries whose members are strings or tokens. */
    private class Dictionary(private val input: String) {
        private var at = 0

        fun parse(): Map<String, String> {
            val members = LinkedHashMap<String, String>()
            skipSpaces()
            while (at < input.length) {
                val key = key()
                require(peek() == '=') { "Wow-Scope member $key has no value." }
                at++
                members[key] = item()
                skipSpaces()
                if (at == input.length) {
                    break
                }
                require(peek() == ',') { "Wow-Scope members are separated by commas, at ${at + 1}." }
                at++
                skipSpaces()
                require(at < input.length) { "Wow-Scope ends with a comma." }
            }
            return members
        }

        private fun key(): String {
            val start = at
            require(peek()?.let { it in 'a'..'z' || it == '*' } == true) { "Not a Wow-Scope key at ${at + 1}." }
            while (peek()?.let { it in 'a'..'z' || it in '0'..'9' || it in "_-.*" } == true) {
                at++
            }
            return input.substring(start, at)
        }

        private fun item(): String {
            val value = when (val first = peek()) {
                '"' -> string()
                null -> throw IllegalArgumentException("A Wow-Scope member has no value.")
                else -> {
                    require(first.isLetter() || first == '*') { "Not a string or a token at ${at + 1}." }
                    token()
                }
            }
            require(peek() != ';') { "Wow-Scope members take no parameters." }
            require(value.isNotBlank()) { "A Wow-Scope member is not blank." }
            return value
        }

        private fun string(): String {
            at++
            val value = StringBuilder()
            while (true) {
                val char = requireNotNull(peek()) { "A Wow-Scope string is not closed." }
                at++
                when (char) {
                    '"' -> return value.toString()
                    '\\' -> {
                        val escaped = peek()
                        require(escaped == '"' || escaped == '\\') { "Only \\\" and \\\\ are escapes, at $at." }
                        value.append(escaped)
                        at++
                    }

                    else -> {
                        require(char.code in PRINTABLE) { "A Wow-Scope string holds printable ASCII only." }
                        value.append(char)
                    }
                }
            }
        }

        private fun token(): String {
            val start = at
            while (peek()?.let { it.isLetterOrDigit() || it in TOKEN_CHARS } == true) {
                at++
            }
            return input.substring(start, at)
        }

        private fun peek(): Char? = input.getOrNull(at)

        private fun skipSpaces() {
            while (peek() == ' ' || peek() == '\t') {
                at++
            }
        }

        private companion object {
            const val TOKEN_CHARS = "!#$%&'*+-.^_`|~:/"
            val PRINTABLE = 0x20..0x7E
        }
    }
}
