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

package me.ahoo.wow.openapi.catalog

/**
 * A route path template read segment by segment, as a path pattern matches it at runtime.
 *
 * A segment is a literal, a plain variable (`{id}` or `*`, which matches any one segment), a constrained variable (any
 * other segment carrying a pattern, such as `{id:\d+}` or `{id}.json`, which matches some single segments), or a
 * trailing catch-all (`{*rest}` or `**`, which matches the remaining segments, none included).
 */
internal class RouteTemplate(val path: String) {
    enum class Kind {
        LITERAL,
        VARIABLE,
        CONSTRAINED,
        CATCH_ALL
    }

    data class Segment(val kind: Kind, val text: String) {
        /**
         * The segment with its variable names erased; a constraint is kept, so `{id:\d+}` and `{name:[a-z]+}` differ.
         */
        val shape: String = when (kind) {
            Kind.LITERAL -> text
            Kind.VARIABLE -> "{}"
            Kind.CONSTRAINED -> text.replace(VARIABLE_NAME, "{")
            Kind.CATCH_ALL -> "{*}"
        }
    }

    val segments: List<Segment> = path.split('/')
        .filter { it.isNotEmpty() }
        .map(::parseSegment)

    /**
     * The template with its variable names erased: two templates with the same shape match the same paths.
     */
    val shape: String = segments.joinToString(separator = "/", prefix = "/") { it.shape }

    val hasCatchAll: Boolean = segments.any { it.kind == Kind.CATCH_ALL }

    /**
     * Whether every path this template matches is also matched by [other], as far as can be told without evaluating
     * constraints: a constrained segment is only known to be within an identical one.
     */
    fun isWithin(other: RouteTemplate): Boolean {
        other.segments.forEachIndexed { index, otherSegment ->
            if (otherSegment.kind == Kind.CATCH_ALL) {
                return true
            }
            val segment = segments.getOrNull(index) ?: return false
            val within = when (otherSegment.kind) {
                Kind.LITERAL -> segment.kind == Kind.LITERAL && segment.text == otherSegment.text
                Kind.VARIABLE -> segment.kind != Kind.CATCH_ALL
                else -> segment.kind == otherSegment.kind && segment.shape == otherSegment.shape
            }
            if (!within) {
                return false
            }
        }
        return segments.size == other.segments.size
    }

    /**
     * Whether this template matches a proper subset of the paths [other] matches, so a first-match router that tried
     * [other] first could never reach this one.
     */
    fun isStrictlyWithin(other: RouteTemplate): Boolean = isWithin(other) && !other.isWithin(this)

    override fun toString(): String = path

    private companion object {
        private val VARIABLE_NAME = Regex("\\{[^}:*]*(?=[:}])")
        private val PLAIN_VARIABLE = Regex("\\{[^}:*]+}")

        private fun parseSegment(segment: String): Segment {
            val kind = when {
                segment == "**" || (segment.startsWith("{*") && segment.endsWith("}")) -> Kind.CATCH_ALL
                segment == "*" || PLAIN_VARIABLE.matches(segment) -> Kind.VARIABLE
                segment.contains('{') || segment.contains('*') || segment.contains('?') -> Kind.CONSTRAINED
                else -> Kind.LITERAL
            }
            return Segment(kind, segment)
        }
    }
}
