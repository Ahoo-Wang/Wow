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

package me.ahoo.wow.query.schema

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.query.QueryField

/**
 * One part of a [QueryPathTemplate]: a named [Property], a dynamic [Key] whose name the core captures from the
 * logical path (a map's key, numbered by its [Key.slot]), or an array's [Item].
 */
sealed interface QueryPathSegment {
    /** The property [name]. */
    class Property @InternalWowApi constructor(val name: String) : QueryPathSegment {
        override fun equals(other: Any?): Boolean = this === other || other is Property && name == other.name

        override fun hashCode(): Int = name.hashCode()

        override fun toString(): String = "Property(name=$name)"

        companion object {
            @JvmStatic
            fun of(name: String): Property = Property(name)
        }
    }

    /** The dynamic key captured in [slot], from 0 in the order the keys occur. */
    class Key @InternalWowApi constructor(val slot: Int) : QueryPathSegment {
        override fun equals(other: Any?): Boolean = this === other || other is Key && slot == other.slot

        override fun hashCode(): Int = slot

        override fun toString(): String = "Key(slot=$slot)"

        companion object {
            @JvmStatic
            fun of(slot: Int): Key = Key(slot)
        }
    }

    /** Each element of an array. */
    data object Item : QueryPathSegment
}

/**
 * A logical or physical path of a model as segments ([QueryPathSegment]): `state.lines[].sku` is
 * `Property(state), Property(lines), Item, Property(sku)`. A storage adapter reports its bindings by the logical
 * template ([LogicalQuerySchema.values]) and gives each binding's physical template ([of]).
 */
class QueryPathTemplate private constructor(input: List<QueryPathSegment>, validatedField: QueryField<*>?) {
    @InternalWowApi
    constructor(segments: List<QueryPathSegment>) : this(segments, null)

    val segments: List<QueryPathSegment> = java.util.List.copyOf(input)
    internal val keyCount: Int = this.segments.count { it is QueryPathSegment.Key }
    internal val hasOrderedKeys: Boolean
    private val hash = this.segments.hashCode()
    private val fixedField: QueryField<*>?

    init {
        val seen = if (keyCount == 0) null else BooleanArray(keyCount)
        var nextSlot = 0
        var ordered = true
        var firstProperty: String? = null
        for (segment in this.segments) {
            when (segment) {
                is QueryPathSegment.Key -> {
                    require(segment.slot in 0 until keyCount && !checkNotNull(seen)[segment.slot]) {
                        "Dynamic path slots must occur exactly once, starting at zero."
                    }
                    checkNotNull(seen)[segment.slot] = true
                    if (segment.slot != nextSlot++) ordered = false
                }
                is QueryPathSegment.Property -> {
                    if (validatedField == null) requireQueryPathSegment(segment.name)
                    if (firstProperty == null) firstProperty = segment.name
                }
                QueryPathSegment.Item -> Unit
            }
        }
        hasOrderedKeys = ordered
        // Root and numeric-leading fragments are valid templates, but not standalone QueryFields.
        fixedField = validatedField ?: if (keyCount == 0 && firstProperty != null && firstProperty.first() !in '0'..'9') {
            QueryField(render(emptyList()))
        } else {
            null
        }
    }

    /** Native field paths omit array traversal while retaining each captured key's position. */
    fun field(keys: List<String>): QueryField<*> {
        require(keys.size == keyCount) { "Dynamic path key count does not match its template." }
        fixedField?.let { return it }
        keys.forEach(::requireQueryPathSegment)
        return QueryField(render(keys))
    }

    private fun render(keys: List<String>): String = buildString {
        for (segment in segments) {
            val part = when (segment) {
                is QueryPathSegment.Property -> segment.name
                is QueryPathSegment.Key -> keys[segment.slot]
                QueryPathSegment.Item -> continue
            }
            if (isNotEmpty()) append('.')
            append(part)
        }
    }

    override fun equals(other: Any?): Boolean = this === other || other is QueryPathTemplate && segments == other.segments

    override fun hashCode(): Int = hash

    override fun toString(): String = "QueryPathTemplate($segments)"

    companion object {
        /** The template of [segments]. */
        @JvmStatic
        fun of(segments: List<QueryPathSegment>): QueryPathTemplate = QueryPathTemplate(segments)

        /** The template of [segments]. */
        @JvmStatic
        fun of(vararg segments: QueryPathSegment): QueryPathTemplate = QueryPathTemplate(segments.toList())

        internal fun fromField(field: QueryField<*>): QueryPathTemplate =
            QueryPathTemplate(field.path.split('.').map(QueryPathSegment::Property), field)
    }
}

internal fun requireQueryPathSegment(value: String) {
    require(value.isValidQueryPathSegment()) { "Invalid query path segment: [$value]." }
}

internal fun String.isValidQueryPathSegment(): Boolean {
    if (isEmpty()) return false
    val start = if (first() == '@') 1 else 0
    if (start == length) return false
    if (this[start] in '0'..'9') return start == 0 && all { it in '0'..'9' }
    if (!this[start].isQueryLetter()) return false
    for (index in start + 1 until length) {
        val char = this[index]
        if (!char.isQueryLetter() && char !in '0'..'9' && char != '-') return false
    }
    return true
}

private fun Char.isQueryLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this == '_'

internal fun QueryField<*>.toPathTemplate(): QueryPathTemplate = QueryPathTemplate.fromField(this)
