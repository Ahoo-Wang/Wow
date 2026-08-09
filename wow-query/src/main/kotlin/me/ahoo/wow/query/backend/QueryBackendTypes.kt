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

package me.ahoo.wow.query.backend

@ExperimentalQueryBackendApi
enum class QueryResultShape {
    TYPED,
    DYNAMIC,
    COUNT,
    ANALYTICS,
}

@ExperimentalQueryBackendApi
enum class RecordResultShape {
    TYPED,
    DYNAMIC,
}

@ExperimentalQueryBackendApi
enum class SystemFieldKind {
    IDENTITY,
    AGGREGATE_ID,
    TENANT_ID,
    OWNER_ID,
    SPACE_ID,
    DELETED,
}

@ExperimentalQueryBackendApi
enum class JunctionOperator {
    AND,
    OR,
    NOR,
}

@ExperimentalQueryBackendApi
enum class PredicateOperator(val requiresValue: Boolean) {
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

@ExperimentalQueryBackendApi
enum class CaseSensitivity {
    SENSITIVE,
    INSENSITIVE,
}

@ExperimentalQueryBackendApi
data class NormalizedPredicateOptions(
    val caseSensitivity: CaseSensitivity = CaseSensitivity.SENSITIVE,
)

@ExperimentalQueryBackendApi
@JvmInline
value class SearchScopeId(val value: String) {
    init {
        require(value.isNotBlank()) {
            "Search scope id must not be blank."
        }
    }
}

@ExperimentalQueryBackendApi
@JvmInline
value class BackendId(val value: String) {
    init {
        require(value.isNotBlank()) {
            "Backend id must not be blank."
        }
    }
}

@ExperimentalQueryBackendApi
@JvmInline
value class Utf8Json(val value: String) {
    init {
        require(value.isNotBlank()) {
            "Native JSON must not be blank."
        }
    }
}

@ExperimentalQueryBackendApi
enum class NormalizedSortDirection {
    ASC,
    DESC,
}
