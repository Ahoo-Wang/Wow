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

import me.ahoo.wow.api.query.LogicalField
import me.ahoo.wow.api.query.PredicateOperator
import me.ahoo.wow.modeling.metadata.AggregateMetadata
import java.util.Collections

enum class QueryValueKind {
    BOOLEAN,
    INTEGER,
    DECIMAL,
    STRING,
    TIME,
    ENUM,
    BINARY,
    OBJECT,
    MAP
}

enum class QueryCollectionKind {
    NONE,
    SCALAR,
    OBJECT
}

data class QueryFieldSchema(
    val path: LogicalField,
    val valueKind: QueryValueKind,
    val nullable: Boolean,
    val collectionKind: QueryCollectionKind = QueryCollectionKind.NONE,
    val queryable: Boolean = valueKind != QueryValueKind.OBJECT && valueKind != QueryValueKind.MAP,
    val sortable: Boolean = collectionKind == QueryCollectionKind.NONE &&
        valueKind != QueryValueKind.OBJECT && valueKind != QueryValueKind.MAP && valueKind != QueryValueKind.BINARY,
    val projectable: Boolean = true,
    val elementMatch: Boolean = collectionKind == QueryCollectionKind.OBJECT,
    val operators: Set<PredicateOperator> = defaultOperators(valueKind, collectionKind),
    val fullText: Boolean = valueKind == QueryValueKind.STRING && collectionKind == QueryCollectionKind.NONE,
    val system: Boolean = false
) {
    init {
        require(queryable || operators.isEmpty()) { "Non-queryable fields cannot declare operators." }
        require(!elementMatch || collectionKind == QueryCollectionKind.OBJECT) {
            "Element match requires an object collection."
        }
        require(!fullText || valueKind == QueryValueKind.STRING) { "Full text requires a string field." }
    }

    companion object {
        private val PRESENCE = setOf(
            PredicateOperator.IS_NULL,
            PredicateOperator.IS_NOT_NULL,
            PredicateOperator.EXISTS
        )
        private val EQUALITY = PRESENCE + setOf(
            PredicateOperator.EQ,
            PredicateOperator.NE,
            PredicateOperator.IN,
            PredicateOperator.NOT_IN
        )

        @JvmStatic
        fun defaultOperators(kind: QueryValueKind, collection: QueryCollectionKind): Set<PredicateOperator> =
            when (collection) {
                QueryCollectionKind.SCALAR -> setOf(
                    PredicateOperator.IN,
                    PredicateOperator.NOT_IN,
                    PredicateOperator.CONTAINS_ALL,
                    PredicateOperator.IS_EMPTY,
                    PredicateOperator.EXISTS
                )

                QueryCollectionKind.OBJECT -> setOf(PredicateOperator.IS_EMPTY, PredicateOperator.EXISTS)
                QueryCollectionKind.NONE -> when (kind) {
                    QueryValueKind.BOOLEAN -> EQUALITY + setOf(
                        PredicateOperator.IS_TRUE,
                        PredicateOperator.IS_FALSE
                    )

                    QueryValueKind.INTEGER,
                    QueryValueKind.DECIMAL,
                    QueryValueKind.TIME -> EQUALITY + setOf(
                        PredicateOperator.GT,
                        PredicateOperator.LT,
                        PredicateOperator.GTE,
                        PredicateOperator.LTE,
                        PredicateOperator.BETWEEN
                    )

                    QueryValueKind.STRING -> EQUALITY + setOf(
                        PredicateOperator.CONTAINS,
                        PredicateOperator.STARTS_WITH,
                        PredicateOperator.ENDS_WITH
                    )

                    QueryValueKind.ENUM,
                    QueryValueKind.BINARY -> EQUALITY

                    QueryValueKind.OBJECT,
                    QueryValueKind.MAP -> emptySet()
                }
            }
    }
}

data class QuerySchema(val fields: Map<LogicalField, QueryFieldSchema>) {
    operator fun get(field: LogicalField): QueryFieldSchema? = fields[field]

    internal fun validatedSnapshot(): QuerySchema {
        val snapshot = fields.mapValuesTo(LinkedHashMap()) { (_, field) ->
            field.copy(operators = Collections.unmodifiableSet(LinkedHashSet(field.operators)))
        }
        require(snapshot.size == fields.size) { "Query schema field cardinality changed." }
        snapshot.forEach { (path, field) -> require(path == field.path) { "Query schema path is inconsistent." } }
        CANONICAL_FIELDS.forEach { expected ->
            require(snapshot[expected.path] == expected) { "Canonical query schema is invalid." }
        }
        return QuerySchema(Collections.unmodifiableMap(snapshot))
    }
}

fun interface QuerySchemaProvider {
    fun getSchema(metadata: AggregateMetadata<*, *>): QuerySchema
}

internal val CANONICAL_FIELDS: List<QueryFieldSchema> = listOf(
    systemString("contextName"),
    systemString("aggregateName"),
    systemString("tenantId"),
    systemString("ownerId"),
    systemString("spaceId"),
    system("version", QueryValueKind.INTEGER),
    systemString("aggregateId"),
    systemString("eventId"),
    systemString("firstOperator"),
    systemString("operator"),
    system("firstEventTime", QueryValueKind.TIME),
    system("eventTime", QueryValueKind.TIME),
    system("snapshotTime", QueryValueKind.TIME),
    system("tags", QueryValueKind.MAP, queryable = false),
    system("deleted", QueryValueKind.BOOLEAN),
    system("state", QueryValueKind.OBJECT, queryable = false)
)

private fun systemString(path: String): QueryFieldSchema = system(path, QueryValueKind.STRING)

private fun system(
    path: String,
    kind: QueryValueKind,
    queryable: Boolean = true
): QueryFieldSchema = QueryFieldSchema(
    path = LogicalField(path),
    valueKind = kind,
    nullable = false,
    queryable = queryable,
    operators = if (queryable) QueryFieldSchema.defaultOperators(kind, QueryCollectionKind.NONE) else emptySet(),
    fullText = false,
    system = true
)
