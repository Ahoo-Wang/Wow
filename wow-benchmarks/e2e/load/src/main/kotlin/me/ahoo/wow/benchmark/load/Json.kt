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

package me.ahoo.wow.benchmark.load

/**
 * The JSON the load generator needs without a library: it reads a field's first occurrence out of an answer, and
 * writes its results from maps, lists, strings, numbers and booleans.
 */
object Json {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Regex, Regex>>()

    private fun patterns(field: String): Pair<Regex, Regex> = cache.computeIfAbsent(field) {
        val name = Regex.escape(it)
        Regex("\"$name\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"") to Regex("\"$name\"\\s*:\\s*(-?\\d+)")
    }

    /** The first string value of [field] in [json], or null. */
    fun string(json: String, field: String): String? = patterns(field).first.find(json)?.groupValues?.get(1)

    /** The first integer value of [field] in [json], or null. */
    fun long(
        json: String,
        field: String
    ): Long? = patterns(field).second.find(json)?.groupValues?.get(1)?.toLongOrNull()

    /** [value] as JSON, indented by two spaces per level. */
    fun write(value: Any?): String = StringBuilder().also { write(it, value, 0) }.toString()

    private fun write(out: StringBuilder, value: Any?, depth: Int) {
        when (value) {
            null -> out.append("null")
            is String -> quote(out, value)
            is Boolean -> out.append(value)
            is Double -> out.append(if (value.isFinite()) value.toString() else "null")
            is Float -> write(out, value.toDouble(), depth)
            is Number -> out.append(value)
            is LongArray -> writeList(out, value.toList(), depth)
            is Map<*, *> -> writeMap(out, value, depth)
            is Iterable<*> -> writeList(out, value.toList(), depth)
            is Array<*> -> writeList(out, value.toList(), depth)
            else -> quote(out, value.toString())
        }
    }

    private fun writeMap(out: StringBuilder, map: Map<*, *>, depth: Int) {
        if (map.isEmpty()) {
            out.append("{}")
            return
        }
        out.append("{\n")
        map.entries.forEachIndexed { index, (key, value) ->
            indent(out, depth + 1)
            quote(out, key.toString())
            out.append(": ")
            write(out, value, depth + 1)
            if (index < map.size - 1) out.append(',')
            out.append('\n')
        }
        indent(out, depth)
        out.append('}')
    }

    private fun writeList(out: StringBuilder, list: List<*>, depth: Int) {
        if (list.isEmpty()) {
            out.append("[]")
            return
        }
        // A list of scalars stays on one line: the histogram buckets are thousands of them.
        if (list.all(::isInline)) {
            out.append('[')
            list.forEachIndexed { index, item ->
                if (index > 0) out.append(", ")
                write(out, item, depth)
            }
            out.append(']')
            return
        }
        out.append("[\n")
        list.forEachIndexed { index, item ->
            indent(out, depth + 1)
            write(out, item, depth + 1)
            if (index < list.size - 1) out.append(',')
            out.append('\n')
        }
        indent(out, depth)
        out.append(']')
    }

    private fun isInline(item: Any?): Boolean = item == null || item is Number || item is String || item is Boolean ||
        item is LongArray

    private fun indent(out: StringBuilder, depth: Int) {
        repeat(depth) { out.append("  ") }
    }

    private const val CONTROL = 0x20

    private fun quote(out: StringBuilder, text: String) {
        out.append('"')
        for (c in text) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c.code < CONTROL -> out.append(String.format(java.util.Locale.ROOT, "\\u%04x", c.code))
                else -> out.append(c)
            }
        }
        out.append('"')
    }
}
