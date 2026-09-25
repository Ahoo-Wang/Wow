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

package me.ahoo.wow.api.query.schema

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue

private val QUERY_SCHEMA_IDENTIFIER_PATTERN = Regex("[A-Za-z_][A-Za-z0-9_-]*")

private fun requireQuerySchemaIdentifier(value: String) {
    require(QUERY_SCHEMA_IDENTIFIER_PATTERN.matches(value)) { "Query schema identifier is invalid: [$value]." }
}

data class QueryModel(
    @get:JsonValue val value: String,
) {
    init {
        requireQuerySchemaIdentifier(value)
    }

    override fun toString(): String = value

    companion object {
        val SNAPSHOT = QueryModel("SNAPSHOT")
        val EVENT_STREAM = QueryModel("EVENT_STREAM")

        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun from(value: String): QueryModel = QueryModel(value)
    }
}

/**
 * What storage lets a field do. A closed set: a backend grants a subset per field, and every query rule is written
 * against these values.
 */
enum class QueryCapability {
    PRESENCE,
    EXACT_MATCH,
    LITERAL_MATCH,
    RANGE,
    FULL_TEXT_TERMS,
    FULL_TEXT_PHRASE,
    SORT,
    CURSOR_SORT,
    ELEMENT_SCOPE,
    AGGREGATE_TERMS,
    AGGREGATE_NUMERIC,
    AGGREGATE_TEMPORAL,
}

data class QueryValueType(
    @get:JsonValue val value: String,
) {
    init {
        requireQuerySchemaIdentifier(value)
    }

    override fun toString(): String = value

    companion object {
        val STRING = QueryValueType("STRING")
        val INTEGER = QueryValueType("INTEGER")
        val DECIMAL = QueryValueType("DECIMAL")
        val BOOLEAN = QueryValueType("BOOLEAN")
        val OBJECT = QueryValueType("OBJECT")

        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun from(value: String): QueryValueType = QueryValueType(value)
    }
}

enum class QueryCardinality {
    SINGLE,
    MANY,
}

/** Structural value facts shared by schema declarations and metadata. */
enum class QueryValueKind { UNKNOWN, NULL, SCALAR, OBJECT, ARRAY, UNION }
