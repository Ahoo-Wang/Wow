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

package me.ahoo.wow.query.internal.normalization

import java.util.Collections

internal enum class PathBasis {
    ROOT,
    CURRENT_ELEMENT,
}

internal enum class SystemFieldKind {
    IDENTITY,
    AGGREGATE_ID,
    TENANT_ID,
    OWNER_ID,
    SPACE_ID,
    DELETED,
}

internal sealed interface LogicalField {
    data class System(val kind: SystemFieldKind) : LogicalField

    class Path(
        segments: Iterable<String>,
        val basis: PathBasis,
    ) : LogicalField {
        val segments: List<String> = Collections.unmodifiableList(segments.toList())

        init {
            require(this.segments.isNotEmpty()) {
                "Logical field path must not be empty."
            }
            require(this.segments.none { it.isBlank() }) {
                "Logical field path segments must not be blank."
            }
        }

        override fun equals(other: Any?): Boolean =
            this === other || other is Path && segments == other.segments && basis == other.basis

        override fun hashCode(): Int = 31 * segments.hashCode() + basis.hashCode()

        override fun toString(): String = "Path(segments=$segments, basis=$basis)"
    }
}

internal enum class JunctionOperator {
    AND,
    OR,
    NOR,
}

internal enum class PredicateOperator(val requiresValue: Boolean) {
    EQ(true),
    NE(true),
    GT(true),
    LT(true),
    GTE(true),
    LTE(true),
    CONTAINS(true),
    IN(true),
    NOT_IN(true),
    BETWEEN(true),
    ALL_IN(true),
    STARTS_WITH(true),
    ENDS_WITH(true),
    IS_NULL(false),
    NOT_NULL(false),
    IS_TRUE(false),
    IS_FALSE(false),
    EXISTS(true),
}

internal enum class CaseSensitivity {
    SENSITIVE,
    INSENSITIVE,
}

internal data class NormalizedPredicateOptions(
    val caseSensitivity: CaseSensitivity = CaseSensitivity.SENSITIVE,
)

@JvmInline
internal value class SearchScopeId(val value: String) {
    init {
        require(value.isNotBlank()) {
            "Search scope id must not be blank."
        }
    }
}

internal sealed interface SearchScope {
    data class Named(val id: SearchScopeId) : SearchScope

    data class LegacyField(val field: LogicalField.Path) : SearchScope
}

@JvmInline
internal value class BackendId(val value: String) {
    init {
        require(value.isNotBlank()) {
            "Backend id must not be blank."
        }
    }
}

@JvmInline
internal value class Utf8Json(val value: String) {
    init {
        require(value.isNotBlank()) {
            "Native JSON must not be blank."
        }
    }
}

internal sealed interface NormalizedCondition {
    data object All : NormalizedCondition

    data object None : NormalizedCondition

    class Junction(
        val operator: JunctionOperator,
        children: Iterable<NormalizedCondition>,
    ) : NormalizedCondition {
        val children: List<NormalizedCondition> = Collections.unmodifiableList(children.toList())

        init {
            require(this.children.isNotEmpty()) {
                "Junction children must not be empty."
            }
        }

        override fun equals(other: Any?): Boolean =
            this === other || other is Junction && operator == other.operator && children == other.children

        override fun hashCode(): Int = 31 * operator.hashCode() + children.hashCode()

        override fun toString(): String = "Junction(operator=$operator, children=$children)"
    }

    data class Predicate(
        val field: LogicalField,
        val operator: PredicateOperator,
        val value: NormalizedValue? = null,
        val options: NormalizedPredicateOptions = NormalizedPredicateOptions(),
    ) : NormalizedCondition {
        init {
            require(operator.requiresValue == (value != null)) {
                "Predicate value does not match operator $operator."
            }
        }
    }

    data class ElementMatch(
        val field: LogicalField.Path,
        val condition: NormalizedCondition,
    ) : NormalizedCondition

    data class Search(
        val scope: SearchScope,
        val text: String,
    ) : NormalizedCondition {
        init {
            require(text.isNotBlank()) {
                "Search text must not be blank."
            }
        }
    }

    data class Native(
        val backendId: BackendId,
        val payload: Utf8Json,
    ) : NormalizedCondition
}
