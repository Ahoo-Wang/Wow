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
 * A segment is a literal, a variable (`{id}`, or any segment carrying a pattern such as `{id}.json` or `*`, which
 * matches one segment), or a trailing catch-all (`{*rest}` or `**`, which matches the remaining segments, none
 * included).
 */
internal class RouteTemplate(val path: String) : Comparable<RouteTemplate> {
    enum class Kind {
        LITERAL,
        VARIABLE,
        CATCH_ALL
    }

    data class Segment(val kind: Kind, val text: String)

    val segments: List<Segment> = path.split('/')
        .filter { it.isNotEmpty() }
        .map(::parseSegment)

    /**
     * The template with its variable names erased: two templates with the same shape match the same paths.
     */
    val shape: String = segments.joinToString(separator = "/", prefix = "/") {
        when (it.kind) {
            Kind.LITERAL -> it.text
            Kind.VARIABLE -> it.text.replace(VARIABLE_NAME, "{}")
            Kind.CATCH_ALL -> "{*}"
        }
    }

    /**
     * Whether a concrete path exists that both templates match.
     */
    fun overlaps(other: RouteTemplate): Boolean {
        val shared = minOf(segments.size, other.segments.size)
        for (index in 0 until shared) {
            val segment = segments[index]
            val otherSegment = other.segments[index]
            if (segment.kind == Kind.CATCH_ALL || otherSegment.kind == Kind.CATCH_ALL) {
                return true
            }
            if (segment.kind == Kind.LITERAL && otherSegment.kind == Kind.LITERAL && segment.text != otherSegment.text) {
                return false
            }
        }
        if (segments.size == other.segments.size) {
            return true
        }
        val longer = if (segments.size > other.segments.size) segments else other.segments
        return longer.size == shared + 1 && longer.last().kind == Kind.CATCH_ALL
    }

    /**
     * Dispatch precedence: compared segment by segment, a literal comes before a variable and a variable before a
     * catch-all. Of two overlapping templates, the one with a literal where the other has a variable at the first
     * segment they differ is therefore tried first, so the path it names is never taken by the more general one.
     */
    override fun compareTo(other: RouteTemplate): Int {
        val shared = minOf(segments.size, other.segments.size)
        for (index in 0 until shared) {
            val segment = segments[index]
            val otherSegment = other.segments[index]
            val byKind = segment.kind.compareTo(otherSegment.kind)
            if (byKind != 0) {
                return byKind
            }
            if (segment.kind == Kind.LITERAL) {
                val byText = segment.text.compareTo(otherSegment.text)
                if (byText != 0) {
                    return byText
                }
            }
        }
        return segments.size.compareTo(other.segments.size)
    }

    override fun toString(): String = path

    private companion object {
        private val VARIABLE_NAME = Regex("\\{[^}*]*}")

        private fun parseSegment(segment: String): Segment {
            val kind = when {
                segment == "**" || (segment.startsWith("{*") && segment.endsWith("}")) -> Kind.CATCH_ALL
                segment.contains('{') || segment.contains('*') || segment.contains('?') -> Kind.VARIABLE
                else -> Kind.LITERAL
            }
            return Segment(kind, segment)
        }
    }
}
