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

@file:OptIn(me.ahoo.wow.query.backend.ExperimentalQueryBackendApi::class)

package me.ahoo.wow.query.internal.normalization

import me.ahoo.wow.query.backend.NormalizedValue
import java.util.Collections

internal enum class PathBasis {
    ROOT,
    CURRENT_ELEMENT,
}

internal typealias SystemFieldKind = me.ahoo.wow.query.backend.SystemFieldKind

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

internal typealias JunctionOperator = me.ahoo.wow.query.backend.JunctionOperator
internal typealias PredicateOperator = me.ahoo.wow.query.backend.PredicateOperator
internal typealias CaseSensitivity = me.ahoo.wow.query.backend.CaseSensitivity
internal typealias NormalizedPredicateOptions = me.ahoo.wow.query.backend.NormalizedPredicateOptions
internal typealias SearchScopeId = me.ahoo.wow.query.backend.SearchScopeId

internal sealed interface SearchScope {
    data class Named(val id: SearchScopeId) : SearchScope

    data class LegacyField(val field: LogicalField.Path) : SearchScope
}

internal typealias BackendId = me.ahoo.wow.query.backend.BackendId
internal typealias Utf8Json = me.ahoo.wow.query.backend.Utf8Json

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
