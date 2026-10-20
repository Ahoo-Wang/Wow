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
import me.ahoo.wow.query.CrossTenant
import me.ahoo.wow.runtime.admission.TargetScope

/**
 * The `Wow-Scope` header (decision D95): an RFC 8941 dictionary whose members, all optional, are `tenant`, `owner` and
 * `space`, each a string or a token: `Wow-Scope: tenant="t1", owner="u1", space="s1"`.
 *
 * On a query route alone ([parseQuery], decision D254 ⑦), `tenant` may name several tenants instead: `tenant=*` for
 * every tenant, or an inner list of strings or tokens, `tenant=("t1" "t2")`.
 *
 * A member that is not one of them, one without a value, a value that is not a string or a token (or, for a query's
 * `tenant`, an inner list of them), a member with parameters, and a dictionary without members are malformed. As
 * RFC 8941 says, a member given twice takes its last value.
 */
object ScopeHeader {
    const val TENANT: String = "tenant"
    const val OWNER: String = "owner"
    const val SPACE: String = "space"

    private val MEMBERS = setOf(TENANT, OWNER, SPACE)

    /** Every tenant, as `tenant` names it on a query route. */
    const val ALL_TENANTS: String = "*"

    /** The target scope [value] spells, which names one tenant at most. */
    fun parse(value: String): TargetScope {
        val query = parseQuery(value)
        require(query.crossTenant == null) {
            "Wow-Scope names several tenants (tenant=* or an inner list) on a query route alone."
        }
        return query.target
    }

    /**
     * The target scope [value] spells on a query route (decision D254 ⑦): a `tenant` of `*` or an inner list is the
     * [QueryTarget.crossTenant] of a cross-tenant query, its [QueryTarget.target] then naming no tenant.
     */
    fun parseQuery(value: String): QueryTarget {
        val members = Dictionary(value).parse()
        require(members.isNotEmpty()) { "Wow-Scope names a tenant, an owner or a space." }
        val unknown = members.keys - MEMBERS
        require(unknown.isEmpty()) { "Wow-Scope takes only tenant, owner and space: $unknown" }
        val scope = Scope(members[OWNER]?.single(OWNER), members[SPACE]?.single(SPACE))
        val tenant = members[TENANT]
        return when {
            tenant == null -> QueryTarget(TargetScope(scope = scope), null)
            tenant.inner -> QueryTarget(TargetScope(scope = scope), CrossTenant(tenant.values.toSet()))
            tenant.values.single() == ALL_TENANTS -> QueryTarget(TargetScope(scope = scope), CrossTenant.ALL)
            else -> QueryTarget(TargetScope(tenant.values.single(), scope), null)
        }
    }

    /**
     * What `Wow-Scope` names on a query route: the [target] scope and, for a cross-tenant query, the tenants it reads
     * ([crossTenant]), in which case [target] names no tenant.
     */
    data class QueryTarget(val target: TargetScope, val crossTenant: CrossTenant?)

    private fun Member.single(key: String): String {
        require(!inner) { "Wow-Scope member $key is a string or a token, not a list." }
        return values.single()
    }

    /** A member's value: a string or a token, or the strings and tokens of an [inner] list. */
    private class Member(val values: List<String>, val inner: Boolean)

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

        fun parse(): Map<String, Member> {
            val members = LinkedHashMap<String, Member>()
            skipSpaces()
            while (at < input.length) {
                val key = key()
                require(peek() == '=') { "Wow-Scope member $key has no value." }
                at++
                members[key] = if (peek() == '(') innerList() else Member(listOf(item()), inner = false)
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

        /** An RFC 8941 inner list of strings and tokens, without parameters: `("t1" "t2")`. */
        private fun innerList(): Member {
            at++
            val values = mutableListOf<String>()
            while (true) {
                val start = at
                while (peek() == ' ') {
                    at++
                }
                requireNotNull(peek()) { "A Wow-Scope list is not closed." }
                if (peek() == ')') {
                    at++
                    break
                }
                require(values.isEmpty() || at > start) { "Wow-Scope list items are separated by spaces, at ${at + 1}." }
                values += bareItem()
                require(peek() != ';') { "Wow-Scope list items take no parameters." }
            }
            require(values.isNotEmpty()) { "A Wow-Scope list names at least one tenant." }
            require(peek() != ';') { "Wow-Scope members take no parameters." }
            return Member(values, inner = true)
        }

        private fun item(): String {
            val value = bareItem()
            require(peek() != ';') { "Wow-Scope members take no parameters." }
            return value
        }

        private fun bareItem(): String {
            val value = when (val first = peek()) {
                '"' -> string()
                null -> throw IllegalArgumentException("A Wow-Scope member has no value.")
                else -> {
                    require(first.isLetter() || first == '*') { "Not a string or a token at ${at + 1}." }
                    token()
                }
            }
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
