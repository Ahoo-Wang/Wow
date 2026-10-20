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

private val QUERY_SCHEMA_IDENTIFIER_PATTERN = Regex("[A-Za-z_][A-Za-z0-9_-]*")

private fun requireQuerySchemaIdentifier(value: String) {
    require(QUERY_SCHEMA_IDENTIFIER_PATTERN.matches(value)) { "Query schema identifier is invalid: [$value]." }
}

/**
 * A read model a query runs against: [SNAPSHOT], a [DATASET] ([QueryDataset][me.ahoo.wow.query.dataset.QueryDataset]), or a custom model. The V10 `events` model of an
 * aggregate's log (decision D131) is not a record set a backend filters freely, so it is not one of these (6.2c).
 */
data class QueryModel(
    val value: String,
) {
    init {
        requireQuerySchemaIdentifier(value)
    }

    override fun toString(): String = value

    companion object {
        /** The snapshots of an aggregate type, one record per aggregate (decision D128). */
        val SNAPSHOT = QueryModel("SNAPSHOT")

        /** A declared dataset: a table-like record set named within a bounded context, such as a BI wide table. */
        val DATASET = QueryModel("DATASET")

        /**
         * The event streams of an aggregate type (decision D235): one record per stream, the commit of one command at
         * one version, its events under `events`.
         */
        val EVENTS = QueryModel("EVENTS")

        @JvmStatic
        fun from(value: String): QueryModel = QueryModel(value)
    }
}

/**
 * What storage lets a field do. A closed set: a backend grants a subset per field, and every query rule is written
 * against these values.
 */
@InternalWowApi
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

@InternalWowApi
data class QueryValueType(
    val value: String,
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
        fun from(value: String): QueryValueType = QueryValueType(value)
    }
}

@InternalWowApi
enum class QueryCardinality {
    SINGLE,
    MANY,
}

/** Structural value facts shared by schema declarations and metadata. */
@InternalWowApi
enum class QueryValueKind { UNKNOWN, NULL, SCALAR, OBJECT, ARRAY, UNION }
