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

    companion object {
        val SNAPSHOT = QueryModel("SNAPSHOT")
        val EVENT_STREAM = QueryModel("EVENT_STREAM")

        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun from(value: String): QueryModel = QueryModel(value)
    }
}

data class QueryCapability(
    @get:JsonValue val value: String,
) {
    init {
        requireQuerySchemaIdentifier(value)
    }

    companion object {
        val PRESENCE = QueryCapability("PRESENCE")
        val EXACT_MATCH = QueryCapability("EXACT_MATCH")
        val LITERAL_MATCH = QueryCapability("LITERAL_MATCH")
        val RANGE = QueryCapability("RANGE")
        val FULL_TEXT_TERMS = QueryCapability("FULL_TEXT_TERMS")
        val FULL_TEXT_PHRASE = QueryCapability("FULL_TEXT_PHRASE")
        val SORT = QueryCapability("SORT")
        val ELEMENT_SCOPE = QueryCapability("ELEMENT_SCOPE")
        val AGGREGATE_TERMS = QueryCapability("AGGREGATE_TERMS")
        val AGGREGATE_NUMERIC = QueryCapability("AGGREGATE_NUMERIC")
        val AGGREGATE_TEMPORAL = QueryCapability("AGGREGATE_TEMPORAL")

        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun from(value: String): QueryCapability = QueryCapability(value)
    }
}

data class QueryValueType(
    @get:JsonValue val value: String,
) {
    init {
        requireQuerySchemaIdentifier(value)
    }

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

/** Ordered from least to most restrictive for compatibility merging. */
enum class QueryCompatibilityLevel {
    EXACT,
    COMPATIBLE,
    INCOMPATIBLE,
}
